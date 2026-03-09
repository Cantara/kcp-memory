# kcp-memory

## Purpose
Episodic memory for Claude Code. Indexes session transcripts and tool-call events into a local SQLite database, making past sessions searchable in milliseconds. Available as a CLI, HTTP API, and MCP server so Claude can query its own history inline.

## Tech Stack
- Language: Java 21
- Framework: Picocli (CLI), built-in Java HttpServer
- Build: Maven
- Key dependencies: SQLite JDBC, Picocli
- Storage: SQLite with FTS5 full-text search

## Architecture
Three-layer memory model:
- **Working memory:** Current context window (provided by Claude Code)
- **Episodic memory:** Past session history (provided by kcp-memory)
- **Semantic memory:** Codebase knowledge (provided by Synthesis)

Daemon runs on port 7735, scans `~/.claude/projects/` for session transcripts, indexes them into SQLite with FTS5. Provides 6 MCP tools for inline session querying.

## Key Entry Points
- `bin/install.sh` - Installation script
- `java/` - Java source code
- CLI: `kcp-memory search`, `kcp-memory scan`, `kcp-memory stats`
- MCP tools: `kcp_memory_search`, `kcp_memory_events_search`, `kcp_memory_list`, `kcp_memory_stats`, `kcp_memory_session_detail`, `kcp_memory_project_context`
- `knowledge.yaml` - KCP manifest

## Development
```bash
# Install
curl -fsSL https://raw.githubusercontent.com/Cantara/kcp-memory/main/bin/install.sh | bash

# Build from source
cd java && mvn clean install

# Scan sessions
kcp-memory scan

# Search
kcp-memory search "OAuth implementation"
```

## Domain Context
AI agent memory infrastructure. Part of the KCP ecosystem. Fills the episodic memory gap so Claude Code sessions can reference past decisions, debugging sessions, and context from previous conversations.
