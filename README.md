# kcp-memory

**Episodic memory for Claude Code, Gemini CLI, and Codex CLI.** Indexes your session transcripts and tool-call events into a local SQLite database — searchable in milliseconds. Available as a CLI, an HTTP API, and an MCP server so Claude can query its own history inline.

```bash
# CLI
kcp-memory search "OAuth implementation"
kcp-memory events search "kubectl apply"
kcp-memory stats

# MCP — Claude queries directly during a session (10 tools)
# Register once in ~/.claude/settings.json, then call inline:
#   kcp_memory_search · kcp_memory_events_search · kcp_memory_list
#   kcp_memory_stats · kcp_memory_session_detail · kcp_memory_project_context
#   kcp_memory_subagent_search · kcp_memory_session_tree          (v0.5.0)
#   kcp_memory_analyze                                            (v0.17.0)
#   kcp_memory_stats (includes RFC-0017 bridge usage)             (v0.19.0)
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

> **Upgrading from v0.20.0 or earlier?** Run `kcp-memory scan --force` after upgrading.
> v0.21.0 fixed a bug where `SessionParser` only accepted `"human"` message type but Claude Code sends `"user"` — all sessions had NULL `first_message` and FTS returned 0 results. A force-scan repopulates the data.

```bash
kcp-memory scan
```

Scans these transcript roots:

- `~/.claude/projects/**/*.jsonl`
- `~/.gemini/tmp/*/chats/session-*.json`
- `~/.codex/sessions/YYYY/MM/DD/rollout-*.jsonl`

Incremental by default — only new or changed files re-indexed.

#### Automate with a git post-commit hook (recommended)

Rather than running `kcp-memory scan` manually, add a global git hook so every commit triggers an incremental scan automatically:

```bash
# Create the global hooks directory (if needed)
git config --global core.hooksPath ~/.git-hooks
mkdir -p ~/.git-hooks

# Add post-commit hook
cat >> ~/.git-hooks/post-commit << 'EOF'
#!/bin/bash
# Trigger kcp-memory incremental scan in the background
kcp-memory scan > /dev/null 2>&1 &
EOF
chmod +x ~/.git-hooks/post-commit
```

The incremental scan is near-instant (only new files are processed), so the hook adds no perceptible delay to commits.

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

### 6. Analyze manifest quality (v0.7.0) + version tracking (v0.16.0)

Requires [kcp-commands v0.15.0](https://github.com/Cantara/kcp-commands) to be writing `exit_code_hint` fields to `~/.kcp/events.jsonl`.

```bash
kcp-memory analyze
kcp-memory analyze --top 10 --since 30 --min-calls 5
```

Reads indexed tool-call events and computes per-manifest quality metrics — retry rate (same manifest called twice within 90s), help-followup rate (agent ran `--help` after getting a manifest), and error rate (output_preview contains error signals). Outputs a ranked table of manifests needing attention:

```
[kcp-memory] manifest quality analysis — 47 manifests, 1,234 total calls

  MANIFEST KEY         CALLS   RETRIES  HELP-FOLLOWUP  ERRORS   SCORE
  ──────────────────────────────────────────────────────────────────────
  kubectl-apply          34      38%         12%         22%     0.31  ← needs attention
  terraform               28      29%         18%          7%     0.24
  mvn                     87       8%          2%          3%     0.05  ok

  Tip: improve manifests at ~/.kcp/commands/<key>.yaml or submit a PR.
```

Options: `--top N` (default 20), `--since DAYS` (default 30), `--min-calls N` (default 5, filters low-signal manifests).

#### Before/after version comparison (v0.16.0)

Requires [kcp-commands v0.16.0](https://github.com/Cantara/kcp-commands) to be writing `manifest_version` fields (SHA-256 content hash of the active YAML).

```bash
kcp-memory analyze --by-version
```

Groups metrics by `(manifest_key, manifest_version)` so you can see whether a manifest improvement actually reduced retries and errors:

```
kubectl-apply:
  [a3f8c2d1]  2026-03-01 → 2026-03-20   calls=34  retry=38%  score=0.31  ← before
  [b7e1a920]  2026-03-21 → present       calls=12  retry=12%  score=0.08  ↓ 0.23
```

Events before v0.16.0 show as `[unknown]`. The hash changes automatically when the YAML file content changes — no manual tagging required.

### 7. Register as MCP server (v0.3.0)

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
2. **Scans** `~/.claude/projects/**/<session>/subagents/agent-*.jsonl` for subagent transcripts, linking each to its parent session *(v0.5.0)*
3. **Scans** `~/.kcp/events.jsonl` for tool-call events (incremental, byte-offset cursor)
4. **Indexes** all three into `~/.kcp/memory.db` (SQLite + FTS5)
5. **Serves** an HTTP API on port 7735 and an MCP stdio server

The daemon runs a background scan every 30 minutes. The PostToolUse hook triggers an
async scan after every tool call (near-real-time). The MCP server runs an inline scan
before every `kcp_memory_events_search` call.

---

## MCP server

The MCP server exposes ten tools over stdio (JSON-RPC 2.0):

| Tool | What it answers |
|------|----------------|
| `kcp_memory_search` | "What did we do with OAuth last month?" — FTS5 over session transcripts |
| `kcp_memory_events_search` | "Which projects did I run `kubectl apply` in?" — FTS5 over tool-call events |
| `kcp_memory_list` | Recent sessions, optionally filtered by project directory |
| `kcp_memory_stats` | Total sessions, turns, tool calls, date range, top tools. Also includes `kcpBridgeUsage` (searches, units fetched, tokens saved, top units) if `~/.kcp/usage.db` exists (RFC-0017). |
| `kcp_memory_session_detail` | Full content of a specific session — user messages, files touched, tools used *(v0.4.0)* |
| `kcp_memory_project_context` | Auto-detect current project from `PWD`, return recent sessions + events — call at session start. Accepts `session_limit` and `event_limit` params *(v0.4.0)* |
| `kcp_memory_subagent_search` | FTS5 search within subagent transcripts — finds architectural discoveries, rejected approaches, and reasoning buried in delegated tasks *(v0.5.0)* |
| `kcp_memory_session_tree` | Show a parent session and all its child subagents as a tree — reveals delegated scope and per-agent tool usage *(v0.5.0)* |
| `kcp_memory_analyze` | Manifest quality metrics — retry rate, help-followup rate, error rate per manifest key. Set `by_version=true` to compare before/after a manifest improvement (requires kcp-commands v0.16.0+). *(v0.17.0)* |

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

## Subagent memory (v0.5.0)

When Claude Code delegates tasks via the Task tool, it spawns subagents that write their own
transcript files alongside the main session:

```
~/.claude/projects/<project>/<session-uuid>/
├── <session-uuid>.jsonl          ← main session (already indexed)
└── subagents/
    ├── agent-ae5fb06d98fb195b2.jsonl   ← subagent 1 (now indexed)
    ├── agent-a6036b037ec3f4eed.jsonl   ← subagent 2 (now indexed)
    └── ...
```

Each subagent file contains the full reasoning trail — tool calls, intermediate discoveries,
rejected approaches — that the main session only receives as a compressed summary. In a
typical session with heavy agent use, subagents contain 19% of total transcript data at a
40:1 to 100:1 compression ratio in the summaries.

v0.5.0 indexes all subagent files and links them to their parent sessions:

```bash
# Search within subagent transcripts
kcp-memory agents search "CatalystOne HRIS"
kcp-memory agents search "Flyway migration V3"

# List agents spawned in a session
kcp-memory agents list --session 624a7854-a7d2-4331-8ddb-7dab21e7064c

# Show a session tree (parent + all child agents)
kcp-memory session-tree 624a7854-a7d2-4331-8ddb-7dab21e7064c
```

MCP tools (for Claude to use inline):

```
kcp_memory_subagent_search  — "what did the agent discover about co-events architecture?"
kcp_memory_session_tree     — "show me what agents ran in session 624a and what they investigated"
```

**What was previously lost:**

| Knowledge type | Example | Now recoverable? |
|---------------|---------|-----------------|
| Reasoning trails | "I'll read pom.xml to understand the dependency tree" | ✅ FTS5 indexed |
| Dead ends | "PATH NOT FOUND — pivoting to root listing" | ✅ FTS5 indexed |
| Cross-repo discoveries | "co-converter depends on Xorcery Alchemy, not just core" | ✅ FTS5 indexed |
| Investigation methodology | "Read knowledge.yaml first, then pom.xml, then source tree" | ✅ FTS5 indexed |

Run `kcp-memory scan` after upgrading — it will retroactively index all existing subagent
files and link them to their parent sessions.

---

## Tool-call events (v0.2.0)

Requires [kcp-commands v0.9.0](https://github.com/Cantara/kcp-commands) running as a
PreToolUse hook. On every Bash tool call, kcp-commands appends a JSON event to
`~/.kcp/events.jsonl`:

```json
{"ts":"2026-03-03T14:32:01Z","session_id":"abc123","project_dir":"/src/myapp","tool":"Bash","command":"kubectl apply -f deploy.yaml","manifest_key":"kubectl-apply","manifest_version":"cabf7009"}
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

The CLI binary is **not** installed to `PATH`. To use `kcp-memory` as a command, add an alias to `~/.bashrc` or `~/.zshrc`:

```bash
alias kcp-memory='java --enable-native-access=ALL-UNNAMED -jar ~/.kcp/kcp-memory-daemon.jar'
```

Without the alias, invoke directly:

```bash
java --enable-native-access=ALL-UNNAMED -jar ~/.kcp/kcp-memory-daemon.jar search "query"
```

---

## Releases

| Version | Notes |
|---------|-------|
| v0.1.0 | Session-level indexing — `~/.claude/projects/**/*.jsonl` → SQLite+FTS5 |
| v0.2.0 | Tool-level events — ingests `~/.kcp/events.jsonl` (kcp-commands v0.9.0), `kcp-memory events search` CLI + `/events/search` endpoint |
| v0.3.0 | MCP server — `kcp-memory mcp` subcommand; four MCP tools for Claude Code inline use |
| v0.4.0 | `kcp_memory_session_detail` (find → read flow) + `kcp_memory_project_context` (proactive session-start context from `PWD`) |
| v0.5.0 | Subagent memory — indexes `subagents/agent-*.jsonl` files, parent-child session linking, `kcp_memory_subagent_search` + `kcp_memory_session_tree` MCP tools, `kcp-memory agents` CLI commands |
| v0.7.0 | `kcp-memory analyze` — manifest quality feedback loop; reads indexed tool-call events and computes retry rate, help-followup rate, error rate, and composite quality score per manifest key. Pairs with kcp-commands v0.15.0 `exit_code_hint` events. |
| v0.16.0 | **Manifest version tracking.** `kcp-memory analyze --by-version` groups quality metrics by `(manifest_key, manifest_version)` — SHA-256 content hash of the active YAML — enabling before/after comparison when a manifest is improved. Migration tracking added to schema so upgrades are safe on existing databases. Pairs with kcp-commands v0.16.0. |
| v0.17.0 | **`kcp_memory_analyze` MCP tool** — 9th MCP tool. Claude can now call manifest quality analysis inline during a session without switching to the CLI. Supports `since_days`, `min_calls`, `top`, and `by_version` parameters. |
| v0.18.0 | **Auto-update.** New `update` subcommand with `--check` (scriptable, exit 1 if update available) and `--yes` (non-interactive) flags. Updates both kcp-memory and kcp-commands JARs. Startup update notification on first run each day (24h-rate-limited, shared `~/.kcp/last-update-check` cache). Fix: `GET /health` now returns the real version string (was hardcoded `"0.5.0"`). |
| v0.19.0 | **RFC-0017 bridge usage in stats.** `GET /stats` (and `kcp_memory_stats` MCP tool — 10th tool) now includes a `kcpBridgeUsage` block when `~/.kcp/usage.db` exists — total searches, units fetched, tokens saved, and top 5 most-accessed units. Completes the observability loop: bridge writes events, kcp-memory surfaces the aggregate view. |
| v0.20.0 | **RFC-0017 UsageLogger.** CLI `search` and `events search` now log to `~/.kcp/usage.db` via synchronous `logSearchSync()` — no daemon-thread race on JVM exit. Populates the same usage database that kcp-dashboard reads. |
| v0.21.0 | **FTS session fix.** `SessionParser` accepted only `"human"` type for user messages, but Claude Code sends `"user"`. All 3,742 sessions had NULL `first_message` — FTS returned 0 results. Fixed with regression test. **After upgrading, run `kcp-memory scan --force`** to repopulate session data. |
| v0.22.0 | **Documentation and version alignment.** Updated README: 10 MCP tools (was 9), CLI alias note (`--enable-native-access`), FTS fix upgrade instructions. Coordinated release with kcp-commands v0.22.0 and kcp-dashboard v0.22.0. |

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
| **Reads** | 289 command manifests | `~/.claude/projects/**/*.jsonl` + `~/.kcp/events.jsonl` |
| **Answers** | "How do I run this?" | "What did I do before?" |
| **CLI** | — | `scan`, `search`, `list`, `stats`, `analyze` / `analyze --by-version` (v0.16.0), `events`, `agents`, `update` (v0.18.0) |
| **MCP** | — | 10 tools — includes `kcp_memory_analyze` (v0.17.0), `kcp_memory_stats` with RFC-0017 bridge usage (v0.19.0) |

Both use `~/.kcp/` and are part of the [KCP ecosystem](https://github.com/Cantara/knowledge-context-protocol).

**[kcp-dashboard](https://github.com/Cantara/kcp-dashboard)** is the live terminal dashboard for KCP usage statistics. It reads `~/.kcp/usage.db` (RFC-0017) and `~/.kcp/memory.db` — showing commands guided, context delivered, manifest coverage, session profile, guidance quality metrics, and memory search recall. Refreshes every 2 seconds. Single Go binary, no runtime deps.

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
