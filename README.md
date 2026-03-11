# kcp-memory

**Episodic memory for Claude Code, Gemini CLI, and Codex CLI.** Indexes your session transcripts and tool-call events into a local SQLite database — searchable in milliseconds. Available as a CLI, an HTTP API, and an MCP server so Claude can query its own history inline.

```bash
# CLI
kcp-memory search "OAuth implementation"
kcp-memory events search "kubectl apply"
kcp-memory stats

# MCP — Claude queries directly during a session (6 tools)
# Register once in ~/.claude/settings.json, then call inline:
#   kcp_memory_search · kcp_memory_events_search · kcp_memory_list
#   kcp_memory_stats · kcp_memory_session_detail · kcp_memory_project_context
```

Part of the [KCP ecosystem](https://github.com/Cantara/knowledge-context-protocol).

---

## The three-layer memory model

| Layer | What it is | Provided by |
|-------|-----------|-------------|
| **Working** | Current context window | Claude Code |
| **Episodic** | What happened in past sessions | **kcp-memory** |
| **Semantic** | What this codebase means | [Synthesis](https://github.com/exoreaction/synthesis) |

kcp-memory fills the episodic layer. Without it, every session starts from zero. With it,
Claude can answer "what was I doing in this project last week?" and "which projects did I
run `kubectl apply` in?" in milliseconds.

---

## Quick start

### 1. Install

```bash
curl -fsSL https://raw.githubusercontent.com/Cantara/kcp-memory/main/bin/install.sh | bash
```

Downloads the JAR to `~/.kcp/`, starts the daemon on port 7735, and runs an initial scan of `~/.claude/projects/`.

### 2. Index your sessions

```bash
kcp-memory scan
```

Scans these transcript roots:

- `~/.claude/projects/**/*.jsonl`
- `~/.gemini/tmp/*/chats/session-*.json`
- `~/.codex/sessions/YYYY/MM/DD/rollout-*.jsonl`

Incremental by default — only new or changed files re-indexed.

### 3. Search session transcripts

```bash
kcp-memory search "authentication refactor"
kcp-memory search "database migration flyway"
```

### 4. Search tool-call events (v0.2.0)

Requires [kcp-commands v0.9.0](https://github.com/Cantara/kcp-commands) to be writing `~/.kcp/events.jsonl`.

```bash
kcp-memory events search "kubectl apply"
kcp-memory events search "mvn package"
kcp-memory events search "docker build"
```

Returns every time Claude ran that command, with project directory, session ID, and timestamp.

### 5. List and stats

```bash
kcp-memory list
kcp-memory list --project /src/myapp
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
    Bash                       9,442
    Edit                       7,103
```

### 6. Register as MCP server (v0.3.0)

Add to `~/.claude/settings.json`:

```json
{
  "mcpServers": {
    "kcp-memory": {
      "command": "java",
      "args": ["-jar", "/home/you/.kcp/kcp-memory-daemon.jar", "mcp"]
    }
  }
}
```

Claude Code can now call all six tools inline during any session — no manual CLI call,
no context-switching: `kcp_memory_search`, `kcp_memory_events_search`, `kcp_memory_list`,
`kcp_memory_stats`, `kcp_memory_session_detail`, and `kcp_memory_project_context`.

---

## How it works

Claude Code stores every session as a `.jsonl` file in `~/.claude/projects/<slug>/`.
kcp-commands v0.9.0 writes every Bash tool call to `~/.kcp/events.jsonl`.

kcp-memory:
1. **Scans** `~/.claude/projects/` for `.jsonl` session transcripts
2. **Scans** `~/.kcp/events.jsonl` for tool-call events (incremental, byte-offset cursor)
3. **Indexes** both into `~/.kcp/memory.db` (SQLite + FTS5)
4. **Serves** an HTTP API on port 7735 and an MCP stdio server

The daemon runs a background scan every 30 minutes. The PostToolUse hook triggers an
async scan after every tool call (near-real-time). The MCP server runs an inline scan
before every `kcp_memory_events_search` call.

---

## MCP server

The MCP server exposes six tools over stdio (JSON-RPC 2.0):

| Tool | What it answers |
|------|----------------|
| `kcp_memory_search` | "What did we do with OAuth last month?" — FTS5 over session transcripts |
| `kcp_memory_events_search` | "Which projects did I run `kubectl apply` in?" — FTS5 over tool-call events |
| `kcp_memory_list` | Recent sessions, optionally filtered by project directory |
| `kcp_memory_stats` | Total sessions, turns, tool calls, date range, top tools |
| `kcp_memory_session_detail` | Full content of a specific session — user messages, files touched, tools used *(v0.4.0)* |
| `kcp_memory_project_context` | Auto-detect current project from `PWD`, return last 5 sessions + 20 events — call at session start *(v0.4.0)* |

Registration (`~/.claude/settings.json`):

```json
{
  "mcpServers": {
    "kcp-memory": {
      "command": "java",
      "args": ["-jar", "/home/you/.kcp/kcp-memory-daemon.jar", "mcp"]
    }
  }
}
```

The MCP server opens its own database connection — it does not require the HTTP daemon to be running.

---

## Tool-call events (v0.2.0)

Requires [kcp-commands v0.9.0](https://github.com/Cantara/kcp-commands) running as a
PreToolUse hook. On every Bash tool call, kcp-commands appends a JSON event to
`~/.kcp/events.jsonl`:

```json
{"ts":"2026-03-03T14:32:01Z","session_id":"abc123","project_dir":"/src/myapp","tool":"Bash","command":"kubectl apply -f deploy.yaml","manifest_key":"kubectl-apply"}
```

kcp-memory reads this file using a byte-offset cursor — each scan reads only the bytes
appended since last time, typically one event in under 1ms.

For Gemini/Codex hook integrations, this repo also ships a lightweight logger utility:

```bash
node /path/to/kcp-memory/bin/kcp-logger.js '{"session_id":"abc123","project_dir":"'$PWD'","tool":"post_tool_use","command":"mvn test"}'
```

It appends a normalized JSON line to `~/.kcp/events.jsonl`, so Gemini `AfterTool` and Codex
`post_tool_use` hooks can feed the existing event index without any daemon changes.

Search example:

```
$ kcp-memory events search "kubectl apply"

[kcp-memory] 3 event(s) for "kubectl apply":

  2026-03-03 14:32  /src/cantara/kcp-commands
  abc12345  [kubectl-apply]
  $ kubectl apply -f deploy.yaml

  2026-02-28 11:17  /src/exoreaction/lib-pcb-app
  def67890  [kubectl-apply]
  $ kubectl apply -f k8s/production.yaml
```

---

## Daemon API

The HTTP daemon runs on `http://localhost:7735`:

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/health` | GET | Liveness check + session count + version |
| `/search?q=<query>&limit=20` | GET | FTS5 search over session transcripts |
| `/sessions?project=<dir>&limit=50` | GET | List recent sessions |
| `/stats` | GET | Aggregate statistics |
| `/scan?force=true` | POST | Trigger an incremental scan (async) |
| `/events/search?q=<query>&limit=20` | GET | FTS5 search over tool-call events *(v0.2.0)* |

```bash
# Check health
curl http://localhost:7735/health

# Search sessions
curl "http://localhost:7735/search?q=OAuth+login&limit=5"

# Search events
curl "http://localhost:7735/events/search?q=kubectl+apply"
```

---

## PostToolUse hook (optional)

For near-real-time session indexing, add to `~/.claude/settings.json`:

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

Fires after every tool call, triggers an async POST to `/scan`. Never blocks — if the
daemon is not running the hook exits silently in under 1 second.

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

| Version | Notes |
|---------|-------|
| v0.1.0 | Session-level indexing — `~/.claude/projects/**/*.jsonl` → SQLite+FTS5 |
| v0.2.0 | Tool-level events — ingests `~/.kcp/events.jsonl` (kcp-commands v0.9.0), `kcp-memory events search` CLI + `/events/search` endpoint |
| v0.3.0 | MCP server — `kcp-memory mcp` subcommand; four MCP tools for Claude Code inline use |
| v0.4.0 | `kcp_memory_session_detail` (find → read flow) + `kcp_memory_project_context` (proactive session-start context from `PWD`) |

---

## Knowledge manifest

This repository ships a [`knowledge.yaml`](knowledge.yaml) and [`llms.txt`](llms.txt) for AI agent navigation.

---

## How it relates to kcp-commands

[kcp-commands](https://github.com/Cantara/kcp-commands) saves context window by injecting
CLI syntax before Bash tool calls and filtering noisy output after. kcp-memory is
complementary — it makes the past retrievable and queryable.

| | kcp-commands | kcp-memory |
|--|-------------|-----------|
| **Port** | 7734 | 7735 |
| **Hook** | PreToolUse | PostToolUse |
| **Stores** | Nothing (stateless) | `~/.kcp/memory.db` (SQLite) |
| **Reads** | 283 command manifests | `~/.claude/projects/**/*.jsonl` + `~/.kcp/events.jsonl` |
| **Answers** | "How do I run this?" | "What did I do before?" |
| **MCP** | — | 6 tools (v0.4.0) |

Both use `~/.kcp/` and are part of the [KCP ecosystem](https://github.com/Cantara/knowledge-context-protocol).

---

## Building from source

```bash
cd java
mvn package -q
# Output: target/kcp-memory-daemon.jar
```

Java 21 required. No Spring, no framework, no cloud calls. Dependencies: `sqlite-jdbc`, `jackson-databind`, `picocli`.

---

## Related

- [Release post](https://wiki.totto.org/blog/2026/03/03/kcp-memory/) — design rationale, three-layer model, benchmark numbers
- [kcp-commands](https://github.com/Cantara/kcp-commands) — writes `~/.kcp/events.jsonl`; PreToolUse companion
- [Knowledge Context Protocol](https://github.com/Cantara/knowledge-context-protocol) — the KCP specification

---

## License

Apache 2.0 — [Cantara](https://github.com/Cantara)
