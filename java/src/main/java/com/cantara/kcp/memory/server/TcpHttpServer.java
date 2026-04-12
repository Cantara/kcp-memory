package com.cantara.kcp.memory.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Minimal HTTP/1.1 server backed by a plain {@link ServerSocket}.
 *
 * <p>Drop-in replacement for {@code com.sun.net.httpserver.HttpServer} that avoids
 * Java 21's NIO selector machinery ({@code WEPollSelectorImpl} / {@code PipeImpl})
 * which requires AF_UNIX sockets — unavailable inside Windows MSIX sandboxes.
 *
 * <p>Each accepted connection is dispatched to a virtual thread. Handlers are matched
 * by path prefix (longest wins, exact match preferred).
 */
public class TcpHttpServer {

    private static final Logger LOG = Logger.getLogger(TcpHttpServer.class.getName());

    private final int port;
    private final Map<String, KcpHandler> handlers = new LinkedHashMap<>();

    private volatile boolean running = false;
    private ServerSocket serverSocket;

    public TcpHttpServer(int port) {
        this.port = port;
    }

    /** Register a handler for the given path prefix (exact match preferred at dispatch time). */
    public void createContext(String path, KcpHandler handler) {
        handlers.put(path, handler);
    }

    /** Bind the socket and start the acceptor virtual thread. */
    public void start() throws IOException {
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress("127.0.0.1", port));
        running = true;

        Thread.ofVirtual().name("kcp-tcp-acceptor").start(() -> {
            while (running) {
                try {
                    Socket conn = serverSocket.accept();
                    Thread.ofVirtual().start(() -> dispatch(conn));
                } catch (IOException e) {
                    if (running) LOG.fine("Acceptor error: " + e.getMessage());
                }
            }
        });
    }

    /** Stop accepting new connections. In-flight requests are not interrupted. */
    public void stop() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
    }

    // --- internal ---

    private void dispatch(Socket conn) {
        try (TcpExchange ex = new TcpExchange(conn)) {
            KcpHandler handler = resolve(ex.getRequestURI().getPath());
            if (handler != null) {
                handler.handle(ex);
            } else {
                ex.sendResponse(404, "application/json; charset=utf-8",
                        "{\"error\":\"Not found\"}".getBytes());
            }
        } catch (IOException e) {
            LOG.fine("Request handling error: " + e.getMessage());
        }
    }

    /** Exact match first, then longest-prefix match. */
    private KcpHandler resolve(String path) {
        KcpHandler exact = handlers.get(path);
        if (exact != null) return exact;

        KcpHandler best = null;
        int bestLen = -1;
        for (Map.Entry<String, KcpHandler> entry : handlers.entrySet()) {
            String prefix = entry.getKey();
            if (path.startsWith(prefix) && prefix.length() > bestLen) {
                best = entry.getValue();
                bestLen = prefix.length();
            }
        }
        return best;
    }
}
