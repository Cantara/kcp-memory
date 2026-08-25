# kcp-memory

Episodic memory for Claude Code, Gemini CLI, and Codex CLI. Indexes session transcripts,
subagent transcripts, and tool-call events into a local SQLite+FTS5 database, exposed via a
CLI, an HTTP daemon (port 7735), and an MCP server so an agent can query its own history inline.

## Start here

Read `knowledge.yaml` first — it's the canonical agent-navigable index of README, the
three-layer memory model, and this repo's four governed operational skills. Query it the
standard KCP way: `npx kcp-agent plan '<intent>' --manifest .`

## Skill conventions

Governed-skill authoring conventions — envelope shape, `action_scope` as a firewall rule, the
skill/knowledge/policy split — live in [Cantara/kcp-skill](https://github.com/Cantara/kcp-skill)
`PROFILE.md`. Read that before writing or reviewing any `kind: skill` unit here.

## This repo's local skills

`.claude/skills/` holds ExoCortex fleet-operator procedures, not general kcp-memory usage:
`exocortex-peer-setup` (`--peer` sync between instances), `exocortex-serve-setup` (`--serve`
external API for mobile), `exocortex-debug` (diagnose sync/tunnel failures across nodes), and
`exocortex-android-app` (the `kcp-sync-android` client that talks to `--serve`).

## Gotchas

- **README and `knowledge.yaml` are stale against the actual release**: both stop documenting
  at v0.33.0 / `app_version: 0.34.0`, but the repo is at v0.37.1 — `kcp_memory_decisions`,
  `suggest-skill`, `analyze --propose`, and daemon `--port`/`--db-path` are undocumented there,
  and the MCP server now exposes 12 tools, not the 11 both docs claim. Check
  `KcpMemoryCli.java` / `McpServer.java` for ground truth.
- **The daemon now runs under process supervision by default** (`bin/install.sh`, #32): a
  `systemd --user` unit on Linux, a launchd LaunchAgent on macOS — not the old session-scoped
  `nohup`. `kcp-memory status` reports which. The shipped unit's `ExecStart` has no
  `--peer`/`--serve` flags; adding them needs a `systemctl --user edit kcp-memory` drop-in.
- **CLI binary is not on PATH** — invoke via the alias in the README
  (`java --enable-native-access=ALL-UNNAMED -jar ~/.kcp/kcp-memory-daemon.jar`) or you'll get
  "command not found".
