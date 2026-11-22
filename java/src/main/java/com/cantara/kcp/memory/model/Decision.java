package com.cantara.kcp.memory.model;

import java.util.List;

/**
 * Decision record from project .sdd/decisions/*.yaml files.
 * Represents architectural decisions, constraints, anti-patterns, and workarounds
 * discovered across Claude Code sessions.
 */
public record Decision(
        String id,
        String type,           // decision | anti-pattern | constraint | workaround
        String domain,         // deployment | testing | video-build | etc.
        String what,           // One-sentence summary
        String why,            // Reasoning/context
        List<String> alternatives,  // What was rejected (optional)
        String learned,        // Session ID or skill where discovered
        String updated,        // Session ID if decision revised (optional)
        List<String> tags,     // Keywords for search
        String projectPath,    // Which project this came from
        Source source          // How that project was found (see Source)
) {
    public Decision {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id required");
        if (type == null || type.isBlank()) throw new IllegalArgumentException("type required");
        if (domain == null || domain.isBlank()) throw new IllegalArgumentException("domain required");
        if (what == null || what.isBlank()) throw new IllegalArgumentException("what required");
        if (why == null || why.isBlank()) throw new IllegalArgumentException("why required");
        if (projectPath == null || projectPath.isBlank()) throw new IllegalArgumentException("projectPath required");

        source = source == null ? Source.SESSION : source;
        alternatives = alternatives == null ? List.of() : List.copyOf(alternatives);
        tags = tags == null ? List.of() : List.copyOf(tags);
    }

    /** A decision without a recorded source is treated as unverified. */
    public Decision(String id, String type, String domain, String what, String why,
                    List<String> alternatives, String learned, String updated,
                    List<String> tags, String projectPath) {
        this(id, type, domain, what, why, alternatives, learned, updated, tags, projectPath, Source.SESSION);
    }

    /**
     * How the decision's project was found. Repository-authored text from a project the user never
     * configured must not read as the user's own memory, so a session-discovered source is unverified.
     */
    public enum Source {
        /** Listed in KCP_MEMORY_DECISION_PROJECTS, or found through the legacy project.json source. */
        CONFIGURED("configured"),
        /** Discovered from a session working directory only. */
        SESSION("session");

        private final String value;

        Source(String value) { this.value = value; }

        /** The value stored in the database. */
        public String value() { return value; }

        public boolean verified() { return this == CONFIGURED; }

        /** Anything other than "configured", including null and unknown values, is unverified. */
        public static Source fromValue(String v) { return "configured".equals(v) ? CONFIGURED : SESSION; }

        /** CONFIGURED wins when a project is reached both ways. */
        public static Source stronger(Source a, Source b) {
            return a == CONFIGURED || b == CONFIGURED ? CONFIGURED : SESSION;
        }
    }
}
