package com.cantara.kcp.memory.server;

import java.io.IOException;

/**
 * Minimal HTTP handler interface — replaces {@code com.sun.net.httpserver.HttpHandler}.
 * Avoids the NIO-selector dependency that blocks Java 21 inside MSIX sandboxes.
 */
@FunctionalInterface
public interface KcpHandler {
    void handle(TcpExchange exchange) throws IOException;
}
