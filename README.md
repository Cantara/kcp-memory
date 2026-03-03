# kcp-memory

**Episodic memory for Claude Code.** Indexes your session transcripts into a local SQLite database so you can search, list, and analyze everything Claude Code has ever done in your projects.

```
kcp-memory search "OAuth implementation"
kcp-memory list --project /src/myapp
kcp-memory stats
```

Part of the [KCP ecosystem](https://github.com/Cantara/knowledge-context-protocol).

---

## The three-layer memory model

| Layer | What it is | Provided by |
|-------|-----------|-------------|
| **Working** | Current context window | Claude Code |
| **Episodic** | What happened in past sessions | **kcp-memory** |
| **Semantic** | What this codebase means | [Synthesis](https://github.com/exoreaction/synthesis) |

kcp-memory fills the episodic layer. Without it, every session starts from zero. With it, you can ask "what was I doing in this project last week?" and get an answer in milliseconds.

---

## Quick start

### 1. Install

```bash
curl -fsSL https://raw.githubusercontent.com/Cantara/kcp-memory/main/bin/install.sh | bash
```

This downloads the JAR, starts the daemon on port 7735, and runs an initial scan.

### 2. Index your sessions

```bash
kcp-memory scan
```

Scans `~/.claude/projects/` and indexes all `.jsonl` session transcripts. Incremental by default — only new or changed files are re-indexed.

### 3. Search

```bash
kcp-memory search "authentication refactor"
kcp-memory search "how to deploy"
```

Uses SQLite FTS5 full-text search across all session content.

### 4. List recent sessions

```bash
kcp-memory list
kcp-memory list --project /src/myapp
kcp-memory list --limit 5
```

### 5. Statistics

```bash
kcp-memory stats
```

```
[kcp-memory] statistics
─────────────────────────────────
  Sessions:    847
  Turns:       12,431
  Tool calls:  38,209
  Oldest:      2026-01-15T09:12:00Z
  Newest:      2026-03-03T14:55:00Z

  Top tools:
    Read                      14,821
    Bash                      9,442
    Edit                      7,103
    Glob                      3,218
    Grep                      2,901
    Write                     644
```

---

## How it works

Claude Code stores every session as a `.jsonl` file in `~/.claude/projects/<slug>/`. Each line is a JSON object representing one turn in the conversation.

kcp-memory:
1. **Scans** `~/.claude/projects/` for `.jsonl` files
2. **Parses** each file: extracts session metadata, user messages, tool calls, and file paths
3. **Indexes** into `~/.kcp/memory.db` (SQLite + FTS5)
4. **Serves** a local HTTP API on port 7735 for fast queries

The daemon runs a background scan every 30 minutes. The PostToolUse hook (optional) triggers an async scan after every tool call.

---

## Daemon API

The daemon runs on `http://localhost:7735`:

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/health` | GET | Liveness check + session count |
| `/search?q=<query>&limit=20` | GET | FTS5 full-text search |
| `/sessions?project=<dir>&limit=50` | GET | List recent sessions |
| `/stats` | GET | Aggregate statistics |
| `/scan?force=true` | POST | Trigger a scan (async) |

```bash
# Check health
curl http://localhost:7735/health

# Search
curl "http://localhost:7735/search?q=OAuth+login&limit=5"

# List sessions for a project
curl "http://localhost:7735/sessions?project=/src/myapp"
```

---

## PostToolUse hook (optional)

For near-real-time indexing, wire the hook into Claude Code's `~/.claude/settings.json`:

```json
{
  "hooks": {
    "PostToolUse": [
      {
        "matcher": ".*",
        "hooks": [{"type": "command", "command": "~/.kcp/memory-hook.sh"}]
      }
    ]
  }
}
```

The hook fires after every tool call and triggers an async scan in the background. It never blocks Claude Code — if the daemon is not running, the hook exits silently in under 1 second.

---

## Daemon management

```bash
# Check if running
curl -sf http://localhost:7735/health

# Start
nohup java -jar ~/.kcp/kcp-memory-daemon.jar daemon > /tmp/kcp-memory-daemon.log 2>&1 &

# Stop
pkill -f kcp-memory-daemon

# View logs
cat /tmp/kcp-memory-daemon.log
```

---

## CLI alias

Add to `~/.bashrc` or `~/.zshrc`:

```bash
alias kcp-memory='java -jar ~/.kcp/kcp-memory-daemon.jar'
```

---

## Releases

| Version | Sessions indexed | Notes |
|---------|-----------------|-------|
| v0.1.0 | All `.jsonl` files in `~/.claude/projects/` | Initial release |

---

## How it relates to kcp-commands

[kcp-commands](https://github.com/Cantara/kcp-commands) saves context window by injecting CLI syntax before Bash tool calls and filtering noisy output after. kcp-memory is complementary:

| | kcp-commands | kcp-memory |
|--|-------------|-----------|
| **Port** | 7734 | 7735 |
| **Scope** | CLI commands | Session transcripts |
| **Hook** | PreToolUse | PostToolUse |
| **Stores** | Nothing (stateless) | SQLite |
| **Answers** | "How do I run this?" | "What did I do before?" |

Both use the same `~/.kcp/` directory and are part of the KCP ecosystem.

---

## Building from source

```bash
cd java
mvn package -q
# Output: target/kcp-memory-daemon.jar
```

Java 21 required.

---

## License

Apache 2.0 — [Cantara](https://github.com/Cantara)
