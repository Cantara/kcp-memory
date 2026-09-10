# kcp-channel — ExoCortex MCP Bridge

Pushes kcp-memory pending tasks into a running Claude Code session via the Channels API.

## Install
```bash
cd /src/cantara/kcp-memory/channel-server
bun install
```

## Configure Claude Code

Add to your project's `.mcp.json`:
```json
{
  "mcpServers": {
    "kcp-channel": {
      "command": "bun",
      "args": ["/src/cantara/kcp-memory/channel-server/kcp-channel.ts"]
    }
  }
}
```

## How it works

- Polls `localhost:7735/pending` every 3s
- Pushes new tasks into Claude Code via `notifications/claude/channel`
- Claude can reply back using the `reply` tool
- Android → ironclaw0 hub → peer sync → local kcp-memory → this server → Claude Code
