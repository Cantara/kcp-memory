package com.cantara.kcp.memory.server;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.KeyStore;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * TLS-enabled HTTP server for external (mobile) access to kcp-memory.
 *
 * <p>Wraps the same handler dispatch pattern as {@link TcpHttpServer} but:
 * <ul>
 *   <li>Binds to an external address (0.0.0.0:8443)</li>
 *   <li>Requires TLS (PEM cert + key)</li>
 *   <li>Validates API key on every request before dispatch</li>
 * </ul>
 *
 * <p>Internal endpoints (search, sessions, stats, events) are registered alongside
 * new mobile-specific endpoints (dispatch, capture, synthesis proxy).
 */
public class ExternalHttpServer {

    private static final Logger LOG = Logger.getLogger(ExternalHttpServer.class.getName());

    private final String bindAddress;
    private final int port;
    private final String tlsCertPath;
    private final String tlsKeyPath;
    private final String apiKey;

    private final Map<String, KcpHandler> handlers = new LinkedHashMap<>();
    private volatile boolean running = false;
    private ServerSocket serverSocket;

    /**
     * @param bindAddress interface to bind (e.g., "0.0.0.0")
     * @param port        listen port (e.g., 8443)
     * @param tlsCertPath path to PEM certificate (or PKCS12 keystore)
     * @param tlsKeyPath  path to PEM private key (or keystore password file)
     * @param apiKey      required Bearer token for all requests
     */
    public ExternalHttpServer(String bindAddress, int port,
                              String tlsCertPath, String tlsKeyPath, String apiKey) {
        this.bindAddress = bindAddress;
        this.port = port;
        this.tlsCertPath = tlsCertPath;
        this.tlsKeyPath = tlsKeyPath;
        this.apiKey = apiKey;
    }

    /** Register a handler for the given path prefix. */
    public void createContext(String path, KcpHandler handler) {
        handlers.put(path, handler);
    }

    /** Bind with TLS and start the acceptor. */
    public void start() throws Exception {
        if (tlsCertPath != null && tlsKeyPath != null) {
            serverSocket = createTlsServerSocket();
        } else {
            // Fallback to plain TCP (for development / reverse proxy setups)
            LOG.warning("No TLS cert/key provided -- external server running WITHOUT TLS");
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(bindAddress, port));
        }

        running = true;

        Thread.ofVirtual().name("external-acceptor").start(() -> {
            while (running) {
                try {
                    Socket conn = serverSocket.accept();
                    Thread.ofVirtual().start(() -> dispatch(conn));
                } catch (IOException e) {
                    if (running) LOG.fine("External acceptor error: " + e.getMessage());
                }
            }
        });

        LOG.info("External API server started on " + bindAddress + ":" + port
                + (tlsCertPath != null ? " (TLS)" : " (PLAINTEXT)"));
    }

    /** Stop the external server. */
    public void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {
        }
    }

    // --- internal ---

    private void dispatch(Socket conn) {
        try (TcpExchange ex = new TcpExchange(conn)) {
            // API key check before any handler
            if (!authenticateRequest(ex)) return;

            // CORS preflight
            if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
                sendCorsOk(ex);
                return;
            }

            KcpHandler handler = resolve(ex.getRequestURI().getPath());
            if (handler != null) {
                handler.handle(ex);
            } else {
                ex.sendResponse(404, "application/json; charset=utf-8",
                        "{\"error\":\"Not found\"}".getBytes());
            }
        } catch (IOException e) {
            LOG.fine("External request error: " + e.getMessage());
        } catch (Exception e) {
            LOG.warning("Unhandled exception in external handler: " + e);
        }
    }

    private boolean authenticateRequest(TcpExchange ex) throws IOException {
        // Skip auth for OPTIONS (CORS preflight)
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) return true;

        String authHeader = ex.getRequestHeader("Authorization");
        if (authHeader == null || !authHeader.equals("Bearer " + apiKey)) {
            ex.sendResponse(401, "application/json; charset=utf-8",
                    "{\"error\":\"Unauthorized\"}".getBytes());
            return false;
        }
        return true;
    }

    private void sendCorsOk(TcpExchange ex) throws IOException {
        ex.sendResponse(204, "text/plain", new byte[0]);
    }

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

    private ServerSocket createTlsServerSocket() throws Exception {
        // Load PKCS12 keystore (convert PEM to PKCS12 with:
        //   openssl pkcs12 -export -in cert.pem -inkey key.pem -out keystore.p12 -password pass:changeit)
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (FileInputStream fis = new FileInputStream(tlsCertPath)) {
            // tlsKeyPath is either the keystore password directly, or a file containing it
            String password = readPassword(tlsKeyPath);
            ks.load(fis, password.toCharArray());

            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, password.toCharArray());

            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(kmf.getKeyManagers(), null, null);

            SSLServerSocketFactory factory = ctx.getServerSocketFactory();
            SSLServerSocket ss = (SSLServerSocket) factory.createServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(bindAddress, port));

            // Only allow TLS 1.2+
            ss.setEnabledProtocols(new String[]{"TLSv1.2", "TLSv1.3"});

            return ss;
        }
    }

    private String readPassword(String pathOrPassword) {
        // If it looks like a file path, try reading it
        try {
            java.nio.file.Path p = java.nio.file.Path.of(pathOrPassword);
            if (java.nio.file.Files.exists(p)) {
                return new String(java.nio.file.Files.readAllBytes(p)).trim();
            }
        } catch (Exception ignored) {
        }
        // Otherwise treat it as the password itself
        return pathOrPassword;
    }
}
