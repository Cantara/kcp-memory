#!/usr/bin/env bash
# kcp-memory installer
# Usage: curl -fsSL https://raw.githubusercontent.com/Cantara/kcp-memory/main/bin/install.sh | bash

set -euo pipefail

KCP_DIR="${HOME}/.kcp"
RELEASE_URL="https://github.com/Cantara/kcp-memory/releases/latest/download/kcp-memory-daemon.jar"
HOOK_URL="https://raw.githubusercontent.com/Cantara/kcp-memory/main/bin/memory-hook.sh"
SETTINGS="${HOME}/.claude/settings.json"

echo "[kcp-memory] installing..."

# Create ~/.kcp directory
mkdir -p "${KCP_DIR}"

# Download JAR
echo "[kcp-memory] downloading kcp-memory-daemon.jar..."
curl -fsSL -o "${KCP_DIR}/kcp-memory-daemon.jar" "${RELEASE_URL}"

# Download hook script
echo "[kcp-memory] downloading memory-hook.sh..."
curl -fsSL -o "${KCP_DIR}/memory-hook.sh" "${HOOK_URL}"
chmod +x "${KCP_DIR}/memory-hook.sh"

# Start daemon
echo "[kcp-memory] starting daemon..."
pkill -f kcp-memory-daemon 2>/dev/null || true
nohup java -jar "${KCP_DIR}/kcp-memory-daemon.jar" daemon \
    > /tmp/kcp-memory-daemon.log 2>&1 &

# Wait a moment for startup
sleep 2

# Verify
if curl -sf --max-time 3 http://localhost:7735/health >/dev/null 2>&1; then
    echo "[kcp-memory] daemon running on port 7735"
else
    echo "[kcp-memory] warning: daemon may not have started — check /tmp/kcp-memory-daemon.log"
fi

# Wire PostToolUse hook into ~/.claude/settings.json
HOOK_ENTRY="{\"matcher\":\".*\",\"hooks\":[{\"type\":\"command\",\"command\":\"${KCP_DIR}/memory-hook.sh\"}]}"

if [ -f "${SETTINGS}" ]; then
    # Check if the hook is already registered
    if grep -q "memory-hook" "${SETTINGS}" 2>/dev/null; then
        echo "[kcp-memory] PostToolUse hook already registered in ${SETTINGS}"
    elif command -v python3 > /dev/null 2>&1; then
        python3 - "${SETTINGS}" "${HOOK_ENTRY}" <<'PYEOF'
import json, sys
path, entry = sys.argv[1], json.loads(sys.argv[2])
with open(path) as f:
    cfg = json.load(f)
hooks = cfg.setdefault("hooks", {})
post = hooks.setdefault("PostToolUse", [])
post.append(entry)
with open(path, "w") as f:
    json.dump(cfg, f, indent=2)
    f.write("\n")
PYEOF
        echo "[kcp-memory] PostToolUse hook registered in ${SETTINGS}"
    else
        echo "[kcp-memory] python3 not found — add this to ${SETTINGS} manually:"
        echo "  \"hooks\": {\"PostToolUse\": [${HOOK_ENTRY}]}"
    fi
else
    echo "[kcp-memory] note: ${SETTINGS} not found — Claude Code not installed?"
fi

# Run initial scan
echo "[kcp-memory] running initial scan..."
java -jar "${KCP_DIR}/kcp-memory-daemon.jar" scan

echo ""
echo "[kcp-memory] installation complete!"
echo ""
echo "Commands:"
echo "  kcp-memory scan                       # index sessions"
echo "  kcp-memory search 'query'            # search session history"
echo "  kcp-memory events search 'query'     # search tool-call events (requires kcp-commands v0.9.0)"
echo "  kcp-memory list                       # list recent sessions"
echo "  kcp-memory stats                      # aggregate statistics"
echo "  kcp-memory mcp                        # run as MCP server (register in ~/.claude/settings.json)"
echo ""
echo "Alias suggestion (add to ~/.bashrc or ~/.zshrc):"
echo "  alias kcp-memory='java -jar ${KCP_DIR}/kcp-memory-daemon.jar'"
