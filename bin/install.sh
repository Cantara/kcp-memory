#!/usr/bin/env bash
# kcp-memory installer
# Usage: curl -fsSL https://raw.githubusercontent.com/Cantara/kcp-memory/main/bin/install.sh | bash
#
# By default, installs process supervision (#32) so the daemon survives crashes
# and reboots: a systemd --user unit on Linux, a launchd LaunchAgent on macOS.
# Pass --no-supervision to opt back into the old session-scoped `nohup` behavior
# instead — a curl|bash installer silently starting a service that persists
# across logins is a bigger jump than the old behavior, and shouldn't surprise
# someone who just wants to try the tool.

set -euo pipefail

NO_SUPERVISION=false
for arg in "$@"; do
    case "$arg" in
        --no-supervision) NO_SUPERVISION=true ;;
    esac
done

KCP_DIR="${HOME}/.kcp"
RELEASE_URL="https://github.com/Cantara/kcp-memory/releases/latest/download/kcp-memory-daemon.jar"
HOOK_URL="https://raw.githubusercontent.com/Cantara/kcp-memory/main/bin/memory-hook.sh"
SYSTEMD_UNIT_URL="https://raw.githubusercontent.com/Cantara/kcp-memory/main/bin/systemd/kcp-memory.service"
LAUNCHD_PLIST_URL="https://raw.githubusercontent.com/Cantara/kcp-memory/main/bin/launchd/com.cantara.kcp-memory.plist"
SETTINGS="${HOME}/.claude/settings.json"
PORT="${KCP_MEMORY_PORT:-7735}"

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

install_nohup_fallback() {
    pkill -f kcp-memory-daemon 2>/dev/null || true
    nohup java -jar "${KCP_DIR}/kcp-memory-daemon.jar" daemon \
        > /tmp/kcp-memory-daemon.log 2>&1 &
}

install_systemd() {
    if ! command -v systemctl >/dev/null 2>&1; then
        echo "[kcp-memory] systemctl not found — falling back to nohup"
        install_nohup_fallback
        return
    fi
    SYSTEMD_DIR="${HOME}/.config/systemd/user"
    mkdir -p "${SYSTEMD_DIR}"
    curl -fsSL -o "${SYSTEMD_DIR}/kcp-memory.service" "${SYSTEMD_UNIT_URL}"
    systemctl --user daemon-reload
    systemctl --user enable --now kcp-memory
}

install_launchd() {
    PLIST_DIR="${HOME}/Library/LaunchAgents"
    PLIST_PATH="${PLIST_DIR}/com.cantara.kcp-memory.plist"
    mkdir -p "${PLIST_DIR}"
    curl -fsSL -o "${PLIST_PATH}.tmpl" "${LAUNCHD_PLIST_URL}"
    if command -v python3 >/dev/null 2>&1; then
        python3 - "${PLIST_PATH}.tmpl" "${PLIST_PATH}" "${HOME}" <<'PYEOF'
import plistlib, sys
tmpl, out, home = sys.argv[1], sys.argv[2], sys.argv[3]
with open(tmpl, "rb") as f:
    data = plistlib.load(f)
data["ProgramArguments"] = [a.replace("__HOME__", home) for a in data["ProgramArguments"]]
data["StandardOutPath"] = data["StandardOutPath"].replace("__HOME__", home)
data["StandardErrorPath"] = data["StandardErrorPath"].replace("__HOME__", home)
with open(out, "wb") as f:
    plistlib.dump(data, f)
PYEOF
    else
        sed "s|__HOME__|${HOME}|g" "${PLIST_PATH}.tmpl" > "${PLIST_PATH}"
    fi
    rm -f "${PLIST_PATH}.tmpl"
    launchctl unload "${PLIST_PATH}" 2>/dev/null || true
    launchctl load -w "${PLIST_PATH}"
}

# Start daemon
echo "[kcp-memory] starting daemon..."
if [ "${NO_SUPERVISION}" = true ]; then
    install_nohup_fallback
else
    case "$(uname -s)" in
        Linux)  install_systemd ;;
        Darwin) install_launchd ;;
        *)
            echo "[kcp-memory] unsupported OS for process supervision — falling back to nohup"
            install_nohup_fallback
            ;;
    esac
fi

# Wait a moment for startup
sleep 2

# Verify
if curl -sf --max-time 3 "http://localhost:${PORT}/health" >/dev/null 2>&1; then
    echo "[kcp-memory] daemon running on port ${PORT}"
else
    echo "[kcp-memory] warning: daemon may not have started — check ~/.kcp/daemon.log (or /tmp/kcp-memory-daemon.log with --no-supervision)"
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
echo "  kcp-memory status                     # health, uptime, freshness, supervision state"
echo "  kcp-memory scan                       # index sessions"
echo "  kcp-memory search 'query'            # search session history"
echo "  kcp-memory events search 'query'     # search tool-call events (requires kcp-commands v0.9.0)"
echo "  kcp-memory list                       # list recent sessions"
echo "  kcp-memory stats                      # aggregate statistics"
echo "  kcp-memory mcp                        # run as MCP server (register in ~/.claude/settings.json)"
echo ""
echo "Alias suggestion (add to ~/.bashrc or ~/.zshrc):"
echo "  alias kcp-memory='java -jar ${KCP_DIR}/kcp-memory-daemon.jar'"
