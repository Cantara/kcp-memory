# kcp-memory + kcp-commands — Pi Integration

[Pi](https://github.com/earendil-works/pi-coding-agent) is a terminal-based AI coding agent with a TypeScript extension API. This directory contains drop-in extensions that bring kcp-memory and kcp-commands into pi sessions, replicating what the Claude Code hook system provides.

## What This Provides

| Phase | Claude Code mechanism | Pi equivalent |
|---|---|---|
| A — Syntax injection | `PreToolUse` hook rewrites bash command | `tool_call` event mutates `event.input.command` |
| B — Output filtering | Hook pipes output through `/filter/<cmd>` | `tool_result` event modifies returned content |
| C — Event logging | `PostToolUse` hook writes `events.jsonl` | `tool_result` event appends to `events.jsonl` |
| Session context | CLAUDE.md instructs the model | `session_start` event + registered tools |

## Install

### Prerequisites

- kcp-memory daemon installed (`~/.kcp/kcp-memory-daemon.jar`)
- kcp-commands daemon installed (`~/.kcp/kcp-commands-daemon.jar`)
- Pi coding agent installed (`npm install -g @earendil-works/pi-coding-agent`)

### Extensions (global — apply to all pi projects)

```bash
mkdir -p ~/.pi/agent/extensions
cp pi/extensions/kcp-commands.ts ~/.pi/agent/extensions/
cp pi/extensions/kcp-memory.ts ~/.pi/agent/extensions/
```

### Skill (global — available to all pi projects)

```bash
mkdir -p ~/.pi/agent/skills/kcp-memory
cp pi/skills/kcp-memory/SKILL.md ~/.pi/agent/skills/kcp-memory/
```

## Extensions

### `kcp-commands.ts`

Bridges the kcp-commands Java daemon into pi's tool pipeline.

- **`session_start`**: Checks daemon health; starts it from `~/.kcp/kcp-commands-daemon.jar` if not running.
- **`tool_call`** (Phase A + B): Sends each bash command to `POST /hook` on port 7734. The daemon returns an `updatedInput.command` rewritten with syntax guidance (Phase A) and optionally piped through `/filter/<cmd>` for noise reduction (Phase B). The extension applies the rewrite by mutating `event.input.command`.
- **`tool_result`** (Phase C): Extracts output text and appends a structured event to `~/.kcp/events.jsonl` for kcp-memory indexing.

### `kcp-memory.ts`

Connects pi to the kcp-memory daemon (port 7735).

- **`session_start`**: Triggers a scan and confirms the daemon is reachable.
- **`session_shutdown`**: Triggers a final scan so the session is indexed before next time.
- **`/memory <query>`**: Slash command for interactive session search.
- **`kcp_memory_search`**: Registered tool — model can search past sessions inline.
- **`kcp_memory_events`**: Registered tool — model can search past tool-call events.
- **`kcp_memory_project_context`**: Registered tool — model can load recent sessions for the current project.

## Known Gaps vs Claude Code

### Phase A context injection

Claude Code's `additionalContext` field injects invisible context the model sees before running the tool — no tokens used in the visible conversation. Pi's `tool_call` event can only mutate `event.input.command`. The current approach relies on the kcp-memory skill providing awareness of past sessions, and on Phase B filtering for the context-window savings. A future improvement would use pi's `context` event to prepend manifest guidance into the message list before each LLM call.

### Session indexing source

kcp-memory currently indexes `~/.claude/projects/` (Claude Code session transcripts). Pi sessions are stored in a different location (`~/.pi/`). The daemon needs a `--sessions-dir` flag or auto-detection to pick up pi session files. Until then, the event log (`events.jsonl`) written by the extension is the primary episodic data source for pi sessions.

## Skill

The `kcp-memory` skill in `pi/skills/kcp-memory/SKILL.md` is a global skill that tells the model when and how to use the memory tools. Pi discovers skills at `~/.pi/agent/skills/` automatically — descriptions are always in the system prompt, and the full instructions load on demand when the task matches.
