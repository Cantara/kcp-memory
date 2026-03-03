#!/usr/bin/env bash
# kcp-memory PostToolUse hook
# Wired into ~/.claude/settings.json: PostToolUse → hook.sh
# Fire-and-forget: never blocks Claude Code.
# Passes tool event to kcp-memory daemon on port 7735 if running.

DAEMON_URL="http://localhost:7735"
HOOK_URL="${DAEMON_URL}/scan"

# Only act on Bash tool calls (where interesting work happens)
# For now: trigger a lightweight async scan signal
# Future (v0.2): stream tool events directly to daemon

# Check if daemon is running (fail silently if not)
if curl -sf --max-time 0.5 "${DAEMON_URL}/health" >/dev/null 2>&1; then
    # Fire-and-forget: POST to /scan, don't wait for response
    curl -sf --max-time 1 -X POST "${HOOK_URL}" \
         -H "Content-Type: application/json" \
         -d '{}' >/dev/null 2>&1 &
fi
