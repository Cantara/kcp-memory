package com.cantara.kcp.memory.handler;

import com.cantara.kcp.memory.server.KcpHandler;
import com.cantara.kcp.memory.server.TcpExchange;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Shared utilities for all HTTP handlers.
 */
public abstract class BaseHandler implements KcpHandler {

    protected static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    protected void sendJson(TcpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = MAPPER.writeValueAsBytes(body);
        ex.sendResponse(status, "application/json; charset=utf-8", bytes);
    }

    protected void sendError(TcpExchange ex, int status, String message) throws IOException {
        sendJson(ex, status, Map.of("error", message));
    }

    protected String readBody(TcpExchange ex) throws IOException {
        try (InputStream is = ex.getRequestBody()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Parse query string into a map. */
    protected Map<String, String> queryParams(TcpExchange ex) {
        URI uri = ex.getRequestURI();
        String query = uri.getQuery();
        Map<String, String> params = new HashMap<>();
        if (query == null || query.isBlank()) return params;
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) {
                params.put(decode(kv[0]), decode(kv[1]));
            } else if (kv.length == 1) {
                params.put(decode(kv[0]), "");
            }
        }
        return params;
    }

    private String decode(String s) {
        try {
            return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }
}
