---
name: kcp-memory
description: Episodic memory for coding sessions. Recalls past decisions, debugging sessions, and approaches from previous work. Use at the start of complex tasks or when you need to understand what was done before in this project.
---

# kcp-memory — Episodic Memory

kcp-memory indexes past coding sessions into SQLite+FTS5, making them searchable in milliseconds.

## When to Use

- **Start of session**: Call `kcp_memory_project_context` to see what was done here recently
- **Before complex tasks**: Call `kcp_memory_search` to find past approaches
- **Debugging**: Search for how similar issues were resolved before
- **Finding commands**: Call `kcp_memory_events` to see what was run previously

## Available Tools

The kcp-memory extension registers these tools — use them directly:

- `kcp_memory_search` — Full-text search across all past sessions
- `kcp_memory_events` — Search tool-call events (commands, output, exit codes)
- `kcp_memory_project_context` — Recent sessions for the current project

## HTTP API (alternative)

```bash
# Search sessions
curl -s "http://localhost:7735/sessions/search?q=OAuth+implementation&limit=10"

# Project context
curl -s "http://localhost:7735/sessions?limit=5&project=$(pwd)"

# Search events
curl -s "http://localhost:7735/events/search?q=docker+build&limit=20"

# Session detail
curl -s "http://localhost:7735/sessions/<session-id>"

# Session tree (parent + child agents)
curl -s "http://localhost:7735/sessions/<session-id>/tree"

# Subagent search
curl -s "http://localhost:7735/agents/search?q=factory+adapter&limit=10"

# Stats
curl -s "http://localhost:7735/stats"
```

## Tips

- FTS5 query syntax: use `"quoted phrases"`, `word1 AND word2`, `word1 OR word2`
- The daemon auto-scans on `project_context` calls; for manual scan: `curl -s -X POST http://localhost:7735/scan`
- If daemon isn't running: `java -jar ~/.kcp/kcp-memory-daemon.jar daemon &`
