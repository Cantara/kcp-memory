---
name: exocortex-debug
description: Diagnose and fix ExoCortex sync issues, tunnel failures, and event flow problems
tags: [exocortex, infrastructure, kcp, debug, troubleshooting]
status: draft
priority: high
relatedSkills: [exocortex-peer-setup, exocortex-serve-setup]
---

# ExoCortex Debug

## When to Use This

Use this skill when:

- **Events aren't flowing**: One node has events the others don't
- **Tunnel keeps dropping**: SSH tunnels reconnect repeatedly
- **Mobile app can't connect**: External API isn't responding or auth fails
- **Stale data**: Dashboard shows old data, sessions aren't updating
- **Post-setup verification**: Confirming the full stack works end-to-end

You're in the right place if:
- Something in the ExoCortex stack isn't working and you need to find out what
- You want a quick health check across all nodes
- You're debugging after a node restart or network change

## Quick Start: Full Health Check

Run this diagnostic script on the hub instance (EC2-A):

```bash
#!/bin/bash
# exocortex-health-check.sh — run on the hub instance
set -e

echo "=== kcp-memory daemon ==="
curl -sf http://localhost:7735/health | jq . || echo "FAIL: internal API not responding"

echo ""
echo "=== External API ==="
curl -sfk https://localhost:8443/health -H "Authorization: Bearer $KCP_API_KEY" | jq . \
  || echo "FAIL: external API not responding"

echo ""
echo "=== Peer sync status ==="
sqlite3 ~/.kcp/memory.db "
  SELECT peer_id,
         last_session_ts,
         last_event_ts,
         last_push_session_ts,
         last_push_event_ts,
         updated_at
  FROM peer_cursors;
" -header -column

echo ""
echo "=== Event distribution ==="
sqlite3 ~/.kcp/memory.db "
  SELECT source_instance, COUNT(*) as events
  FROM tool_events
  GROUP BY source_instance
  ORDER BY events DESC;
" -header -column

echo ""
echo "=== Session distribution ==="
sqlite3 ~/.kcp/memory.db "
  SELECT source_instance, COUNT(*) as sessions
  FROM sessions
  GROUP BY source_instance
  ORDER BY sessions DESC;
" -header -column

echo ""
echo "=== SSH tunnels ==="
ps aux | grep 'ssh -N -L' | grep -v grep || echo "No active SSH tunnels"

echo ""
echo "=== Recent sync activity (last 10 events) ==="
sqlite3 ~/.kcp/memory.db "
  SELECT timestamp, tool, source_instance, substr(event_hash, 1, 12) as hash
  FROM tool_events
  ORDER BY timestamp DESC
  LIMIT 10;
" -header -column

echo ""
echo "=== Duplicate check ==="
DUPES=$(sqlite3 ~/.kcp/memory.db "
  SELECT COUNT(*) FROM (
    SELECT event_hash, COUNT(*) c FROM tool_events
    WHERE event_hash IS NOT NULL
    GROUP BY event_hash HAVING c > 1
  );
")
echo "Duplicate events: $DUPES"
```

## Diagnostic Procedures

### 1. Check if sync is running

```bash
# Are the sync threads alive?
# Look for peer sync log entries
journalctl -u kcp-memory --since "5 minutes ago" | grep -i "peer\|sync\|tunnel"

# Check cursor freshness — if updated_at is stale, sync has stalled
sqlite3 ~/.kcp/memory.db "
  SELECT peer_id,
         updated_at,
         ROUND((julianday('now') - julianday(updated_at)) * 86400) as seconds_ago
  FROM peer_cursors;
" -header -column
```

**Healthy**: `seconds_ago` should be < 60 (syncs every 30s).
**Stale**: If > 120, the sync loop has stopped or the tunnel is down.

### 2. Check SSH tunnel health

```bash
# List active tunnels
ps aux | grep 'ssh -N -L' | grep -v grep

# Test tunnel manually
# Find the local port from logs:
journalctl -u kcp-memory | grep "local port" | tail -1

# Probe through the tunnel
TUNNEL_PORT=<from-logs>
curl -sf http://localhost:$TUNNEL_PORT/health | jq .
```

**If tunnel is dead:**
```bash
# Check if SSH can reach the peer at all
ssh -o ConnectTimeout=5 ec2-user@ec2-b.internal echo "reachable"

# Check SSH config
ssh -vvv -N -L 0:127.0.0.1:7735 ec2-user@ec2-b.internal 2>&1 | head -50
```

### 3. Trace an event through the system

Pick a recent event from node A and verify it reached node B:

```bash
# On EC2-A: find a recent local event
sqlite3 ~/.kcp/memory.db "
  SELECT event_hash, timestamp, tool, command
  FROM tool_events
  WHERE source_instance = 'local'
  ORDER BY timestamp DESC
  LIMIT 1;
"
# Note the event_hash

# On EC2-B: check if it arrived
HASH="<event-hash-from-above>"
sqlite3 ~/.kcp/memory.db "
  SELECT event_hash, timestamp, source_instance
  FROM tool_events
  WHERE event_hash = '$HASH';
"
```

**If missing**: The event hasn't been synced yet. Check:
1. Is the push cursor on EC2-A past that event's timestamp?
2. Is the tunnel to EC2-B alive?
3. Does `/ingest/events` on EC2-B respond?

### 4. Check push path (for laptop behind NAT)

The laptop pushes to the hub. Verify:

```bash
# On the hub (EC2-A): check if laptop events are arriving
sqlite3 ~/.kcp/memory.db "
  SELECT source_instance, COUNT(*), MAX(timestamp) as latest
  FROM tool_events
  WHERE source_instance LIKE '%laptop%' OR source_instance NOT IN ('local', '<ec2-b-peer-id>')
  GROUP BY source_instance;
" -header -column

# On the hub: check ingest endpoint is registered
curl -sf http://localhost:7735/ingest/sessions \
  -H "Content-Type: application/json" \
  -d '{"sessions":[]}'
# Should return {"ingested":0,"type":"sessions"}
```

### 5. Verify transitive sync

Event from laptop should reach EC2-B via the hub:

```bash
# On laptop: find a recent local event hash
HASH=$(sqlite3 ~/.kcp/memory.db "
  SELECT event_hash FROM tool_events
  WHERE source_instance = 'local'
  ORDER BY timestamp DESC LIMIT 1;
")

# On EC2-A (hub): verify it arrived
sqlite3 ~/.kcp/memory.db "SELECT * FROM tool_events WHERE event_hash = '$HASH';" -header

# On EC2-B: verify it propagated through the hub
sqlite3 ~/.kcp/memory.db "SELECT * FROM tool_events WHERE event_hash = '$HASH';" -header
```

### 6. Check external API from mobile perspective

```bash
# Simulate what the Android app does
API="https://ec2-a.internal:8443"
KEY="$KCP_API_KEY"

# 1. Health check
curl -sfk "$API/health" -H "Authorization: Bearer $KEY" -w "\nHTTP %{http_code}\n"

# 2. Search (what the app does on the search screen)
curl -sfk "$API/search?q=test&limit=5" -H "Authorization: Bearer $KEY" | jq '.count'

# 3. Dispatch (what the app does on the chat screen)
curl -sfk "$API/dispatch" -H "Authorization: Bearer $KEY" \
  -H "Content-Type: application/json" \
  -d '{"prompt":"echo hello world","allowed_tools":["Bash"]}' \
  --max-time 30 --no-buffer

# 4. Capture (what the app does on the capture screen)
curl -sfk "$API/capture" -H "Authorization: Bearer $KEY" \
  -H "Content-Type: application/json" \
  -d '{"type":"note","content":"test capture from debug","tags":["debug"]}' \
  | jq .

# 5. Verify capture was written
ls -la ~/.kcp/captures/ | tail -1
```

## Common Failure Modes

### Tunnel reconnect loop

**Pattern**: Logs show "SSH tunnel exited" → "Reconnecting in 2000ms" → repeat

**Causes**:
1. SSH key not accepted (check `authorized_keys` on remote)
2. SSM ProxyCommand failing (check AWS credentials)
3. Remote kcp-memory not running (check remote :7735)

**Fix**: Test SSH manually first, then restart.

### Split-brain (nodes have different events)

**Pattern**: Nodes A and B have events the other doesn't, even after sync.

**Causes**:
1. Push cursor ahead of actual push (cursor updated but POST failed)
2. Tunnel was down during a sync window

**Fix**: Reset cursors to force re-sync:
```bash
sqlite3 ~/.kcp/memory.db "
  UPDATE peer_cursors SET
    last_session_ts = NULL,
    last_event_ts = NULL,
    last_push_session_ts = NULL,
    last_push_event_ts = NULL
  WHERE peer_id = '<problematic-peer>';
"
# Restart kcp-memory — it will do a full re-sync
```

### Mobile app shows stale data

**Pattern**: Dashboard counts or session list don't update.

**Causes**:
1. External API caching (shouldn't be, but check)
2. Peer sync not running on hub
3. Hub's own scanner hasn't run (30-min interval)

**Fix**:
```bash
# Trigger a scan on the hub
curl -sf -X POST http://localhost:7735/scan

# Check hub's peer cursors are advancing
watch -n5 'sqlite3 ~/.kcp/memory.db "SELECT * FROM peer_cursors;" -header -column'
```

## Reference: Key Files and Ports

| Component | Location | Port |
|-----------|----------|------|
| Internal API | localhost | 7735 |
| External API | 0.0.0.0 | 8443 |
| Memory DB | `~/.kcp/memory.db` | - |
| Events JSONL | `~/.kcp/events.jsonl` | - |
| Captures | `~/.kcp/captures/` | - |
| SSH tunnels | dynamic local port | varies |
| Logs | journalctl -u kcp-memory | - |

| SQLite Table | Purpose |
|-------------|---------|
| `sessions` | Indexed session transcripts |
| `tool_events` | Tool-call events with `event_hash` |
| `agent_sessions` | Subagent transcripts |
| `peer_cursors` | Pull + push sync cursors per peer |
| `schema_migrations` | Applied migrations (V1-V6) |

## Related Skills

**Prerequisites:**
- **exocortex-peer-setup** — How to configure --peer connections
- **exocortex-serve-setup** — How to configure --serve for mobile

---

**Status:** draft | **Priority:** high
**Tags:** #exocortex #infrastructure #kcp #debug #troubleshooting
**Last Updated:** 2026-04-12
