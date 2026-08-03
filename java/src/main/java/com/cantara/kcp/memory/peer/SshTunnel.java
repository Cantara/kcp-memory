package com.cantara.kcp.memory.peer;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * Manages an SSH tunnel to a remote kcp-memory instance.
 *
 * <p>Spawns {@code ssh -N -L <localPort>:127.0.0.1:<remotePort> <user>@<host>}
 * as a child process and monitors its lifecycle. Reconnects with exponential
 * backoff on failure.
 *
 * <p>Uses the system SSH binary, so it inherits ~/.ssh/config, SSH keys,
 * and SSM ProxyCommand — no Java SSH library needed.
 */
public class SshTunnel {

    private static final Logger LOG = Logger.getLogger(SshTunnel.class.getName());

    /** Remote kcp-memory port assumed when a peer URI doesn't specify one (#46). */
    private static final int DEFAULT_REMOTE_PORT = 7735;
    private static final int INITIAL_BACKOFF_MS = 2_000;
    private static final int MAX_BACKOFF_MS = 60_000;

    private final String sshUser;
    private final String sshHost;
    private final int sshPort;
    private final int remotePort;
    private final int localPort;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private volatile Process sshProcess;
    private Thread watcherThread;

    /**
     * @param sshUser SSH username
     * @param sshHost remote hostname or IP
     * @param sshPort SSH port (typically 22)
     */
    public SshTunnel(String sshUser, String sshHost, int sshPort) {
        this(sshUser, sshHost, sshPort, DEFAULT_REMOTE_PORT);
    }

    /**
     * @param sshUser SSH username
     * @param sshHost remote hostname or IP
     * @param sshPort SSH port (typically 22)
     * @param remotePort the remote kcp-memory daemon's HTTP port (#46)
     */
    public SshTunnel(String sshUser, String sshHost, int sshPort, int remotePort) {
        this.sshUser = sshUser;
        this.sshHost = sshHost;
        this.sshPort = sshPort;
        this.remotePort = remotePort;
        this.localPort = findFreePort();
    }

    /**
     * Parse a peer URI into an SshTunnel.
     * Supports: {@code ssh://user@host}, {@code ssh://user@host:sshport}, and an
     * optional {@code ?port=} query param naming the remote kcp-memory HTTP port
     * (#46), e.g. {@code ssh://user@host:22?port=7799}. Fully backward-compatible —
     * no URI in production use today carries a {@code ?}.
     *
     * @return SshTunnel instance, or null if URI is not an SSH URI
     */
    public static SshTunnel fromUri(String uri) {
        if (!uri.startsWith("ssh://")) return null;

        String remainder = uri.substring("ssh://".length());

        int remotePort = DEFAULT_REMOTE_PORT;
        int qIdx = remainder.indexOf('?');
        if (qIdx >= 0) {
            String query = remainder.substring(qIdx + 1);
            remainder = remainder.substring(0, qIdx);
            for (String param : query.split("&")) {
                String[] kv = param.split("=", 2);
                if (kv.length == 2 && kv[0].equals("port")) {
                    remotePort = Integer.parseInt(kv[1]);
                }
            }
        }

        String user, host;
        int port = 22;

        int atIdx = remainder.indexOf('@');
        if (atIdx < 0) throw new IllegalArgumentException("SSH URI must include user: ssh://user@host");
        user = remainder.substring(0, atIdx);
        String hostPart = remainder.substring(atIdx + 1);

        int colonIdx = hostPart.lastIndexOf(':');
        if (colonIdx >= 0) {
            host = hostPart.substring(0, colonIdx);
            port = Integer.parseInt(hostPart.substring(colonIdx + 1));
        } else {
            host = hostPart;
        }

        return new SshTunnel(user, host, port, remotePort);
    }

    /** Start the tunnel. Non-blocking — spawns a watcher thread. */
    public void start() {
        if (running.getAndSet(true)) return;
        LOG.info("Starting SSH tunnel to " + sshUser + "@" + sshHost + ":" + sshPort
                + " (local port " + localPort + ")");

        watcherThread = Thread.ofVirtual().name("ssh-tunnel-watcher").start(this::watchLoop);
    }

    /** Stop the tunnel and kill the SSH process. */
    public void stop() {
        running.set(false);
        if (sshProcess != null) {
            sshProcess.destroyForcibly();
        }
        if (watcherThread != null) {
            watcherThread.interrupt();
        }
        LOG.info("SSH tunnel stopped");
    }

    /** The local port that forwards to the remote daemon's port. */
    public int getLocalPort() {
        return localPort;
    }

    /** True if the SSH process is currently alive. */
    public boolean isConnected() {
        return sshProcess != null && sshProcess.isAlive();
    }

    /** Host identifier for this peer (used as peer_id in cursor table). */
    public String getPeerId() {
        return sshUser + "@" + sshHost;
    }

    // --- internal ---

    private void watchLoop() {
        while (running.get()) {
            try {
                sshProcess = spawnSsh();
                LOG.info("SSH tunnel established to " + sshHost);
                consecutiveFailures.set(0);

                // Block until SSH process exits
                int exitCode = sshProcess.waitFor();
                if (!running.get()) break; // clean shutdown

                LOG.warning("SSH tunnel exited with code " + exitCode);
            } catch (IOException e) {
                LOG.warning("Failed to spawn SSH tunnel: " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }

            // Exponential backoff on reconnect
            if (running.get()) {
                int failures = consecutiveFailures.incrementAndGet();
                int backoff = Math.min(INITIAL_BACKOFF_MS * (1 << (failures - 1)), MAX_BACKOFF_MS);
                LOG.info("Reconnecting SSH tunnel in " + backoff + "ms (attempt " + failures + ")");
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private Process spawnSsh() throws IOException {
        // ssh -N -L localPort:127.0.0.1:7735 -p sshPort user@host
        // -N: no remote command
        // -o ServerAliveInterval=30: keepalive every 30s
        // -o ServerAliveCountMax=3: fail after 3 missed keepalives (90s)
        // -o ExitOnForwardFailure=yes: exit if port forward fails
        ProcessBuilder pb = new ProcessBuilder(
                "ssh", "-N",
                "-L", localPort + ":127.0.0.1:" + remotePort,
                "-p", String.valueOf(sshPort),
                "-o", "ServerAliveInterval=30",
                "-o", "ServerAliveCountMax=3",
                "-o", "ExitOnForwardFailure=yes",
                "-o", "StrictHostKeyChecking=accept-new",
                sshUser + "@" + sshHost
        );
        pb.inheritIO();
        return pb.start();
    }

    private static int findFreePort() {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        } catch (IOException e) {
            throw new RuntimeException("No free port available for SSH tunnel", e);
        }
    }
}
