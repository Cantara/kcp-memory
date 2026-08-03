package com.cantara.kcp.memory.store;

/**
 * Provenance-string formats (#47). Deliberately distinguishable prefixes so a
 * memory's origin — scanned locally vs. received via peer sync — stays greppable
 * without needing a separate column.
 */
public final class ProvenanceFormat {

    private ProvenanceFormat() {}

    /** A session indexed by this instance's own local scan. */
    public static String local(String slug, String projectDir, String sessionId) {
        String origin = slug != null && !slug.isBlank() ? slug : (projectDir != null ? projectDir : "");
        return "claude-code:" + origin + "#" + sessionId;
    }

    /**
     * A session received from a peer, via push (/ingest/sessions) or pull.
     * Omits slug — the peer wire payload doesn't carry it today — so this
     * falls back to project_dir only, unlike {@link #local}.
     */
    public static String peer(String sourceInstance, String projectDir, String sessionId) {
        String peer = sourceInstance != null && !sourceInstance.isBlank() ? sourceInstance : "unknown";
        String origin = projectDir != null ? projectDir : "";
        return "claude-code-peer:" + peer + ":" + origin + "#" + sessionId;
    }
}
