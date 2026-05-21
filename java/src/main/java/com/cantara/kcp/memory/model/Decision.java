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
        String projectPath     // Which project this came from
) {
    public Decision {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id required");
        if (type == null || type.isBlank()) throw new IllegalArgumentException("type required");
        if (domain == null || domain.isBlank()) throw new IllegalArgumentException("domain required");
        if (what == null || what.isBlank()) throw new IllegalArgumentException("what required");
        if (why == null || why.isBlank()) throw new IllegalArgumentException("why required");
        if (projectPath == null || projectPath.isBlank()) throw new IllegalArgumentException("projectPath required");

        alternatives = alternatives == null ? List.of() : List.copyOf(alternatives);
        tags = tags == null ? List.of() : List.copyOf(tags);
    }
}
