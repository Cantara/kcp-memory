package com.cantara.kcp.memory.handler;

import com.cantara.kcp.memory.model.SearchResult;
import com.cantara.kcp.memory.store.MemoryDatabase;
import com.cantara.kcp.memory.server.TcpExchange;
import com.cantara.kcp.memory.store.SessionStore;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * GET /sessions[?project=&lt;dir&gt;][&limit=50] — list recent sessions.
 *
 * <p>With {@code updated_since} the endpoint is the change feed peer sync pages through
 * instead: sessions that changed after the cursor, oldest change first, plus a {@code next}
 * cursor to send as the next {@code updated_since}. An empty value starts from the beginning.
 * A peer that predates the feed ignores the parameter and returns the newest sessions.
 */
public class ListHandler extends BaseHandler {

    /** Upper bound for one page of the change feed, whatever the caller asks for. */
    static final int MAX_FEED_PAGE = 500;

    private final SessionStore sessionStore;

    public ListHandler(MemoryDatabase db) {
        this.sessionStore = new SessionStore(db);
    }

    @Override
    public void handle(TcpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            sendError(ex, 405, "Method not allowed");
            return;
        }
        Map<String, String> params = queryParams(ex);
        String projectDir = params.get("project");
        int limit = 50;
        try { limit = Integer.parseInt(params.getOrDefault("limit", "50")); }
        catch (NumberFormatException ignored) {}

        try {
            if (params.containsKey("updated_since")) {
                int pageSize = Math.max(1, Math.min(limit, MAX_FEED_PAGE));
                String cursor = params.get("updated_since");
                SessionStore.ChangePage page = sessionStore.changesSince(cursor, pageSize, true);
                List<SearchResult> changed = page.sessions().stream().map(SessionStore.Changed::session).toList();
                sendJson(ex, 200, Map.of(
                        "project", "",
                        "count", changed.size(),
                        "sessions", changed,
                        "next", page.next()
                ));
                return;
            }
            List<SearchResult> sessions = sessionStore.list(projectDir, limit);
            sendJson(ex, 200, Map.of(
                    "project", projectDir != null ? projectDir : "",
                    "count", sessions.size(),
                    "sessions", sessions
            ));
        } catch (Exception e) {
            sendError(ex, 500, "List failed: " + e.getMessage());
        }
    }
}
