---
name: exocortex-peer-setup
description: Configure and test kcp-memory --peer connections between ExoCortex instances
tags: [exocortex, infrastructure, kcp, sync, ssh]
status: draft
priority: high
relatedSkills: [exocortex-serve-setup, exocortex-debug]
---

# ExoCortex Peer Setup

## When to Use This

Use this skill when:

- **Setting up peer sync**: Connecting two or more kcp-memory instances for bidirectional event replication
- **Adding a new node**: Connecting a laptop or new EC2 instance to an existing ExoCortex hub
- **SSH tunnel issues**: Tunnels won't establish or keep dropping
- **Testing sync**: Verifying events flow correctly between instances

You're in the right place if:
- You're deploying kcp-memory with `--peer` for the first time
- You need to verify SSH connectivity between EC2 instances
- You want to confirm events are replicating between nodes

## Quick Start

```bash
# On EC2-A (hub): peer with EC2-B
kcp-memory daemon --peer ssh://ec2-user@ec2-b.internal

# On EC2-B: peer with EC2-A
kcp-memory daemon --peer ssh://ec2-user@ec2-a.internal

# Verify sync is working (run on either instance)
curl -s http://localhost:7735/stats | jq '.peer_sync'
```

## Overview

The `--peer` flag establishes bidirectional sync between kcp-memory instances.
Each sync cycle (every 30s) pulls new sessions/events from the remote peer
and pushes local sessions/events to it. Events carry a `source_instance` tag
and an `event_hash` for deduplication, enabling transitive sync through a hub.

Key concepts:
- **Hub-and-spoke topology**: One instance (EC2-A) is the hub, others peer with it
- **SSH tunnels**: Outbound-only, inherits ~/.ssh/config and SSM ProxyCommand
- **Transitive sync**: Events flow through the hub preserving original source tags
- **Append-only**: No conflicts — events deduplicated by SHA-256 hash

Relevant files:
- `peer/SshTunnel.java` — SSH tunnel lifecycle with auto-reconnect
- `peer/PeerSyncService.java` — Bidirectional pull + push sync loop
- `store/PeerCursorStore.java` — Replication cursor tracking
- `handler/IngestHandler.java` — POST /ingest endpoint for receiving pushes

## Implementation Guide

### Step 1: Verify SSH connectivity

Before configuring `--peer`, confirm SSH works between instances:

```bash
# From EC2-A, test SSH to EC2-B
ssh ec2-user@ec2-b.internal echo "SSH OK"

# If using SSM Session Manager, verify ProxyCommand works
ssh -v ec2-user@ec2-b.internal echo "SSM OK"

# Test the specific tunnel command kcp-memory will use
ssh -N -L 17735:127.0.0.1:7735 ec2-user@ec2-b.internal &
curl -s http://localhost:17735/health
kill %1
```

### Step 2: Verify kcp-memory is running on both instances

```bash
# On each instance
curl -s http://localhost:7735/health | jq .
# Should return: {"status":"ok","sessions":<count>}
```

### Step 3: Configure hub-and-spoke topology

For a 3-node setup (laptop + EC2-A hub + EC2-B):

```bash
# EC2-A (hub) — peers with EC2-B, serves mobile
kcp-memory daemon \
  --peer ssh://ec2-user@ec2-b.internal \
  --serve 0.0.0.0:8443 \
  --tls-cert /etc/kcp/cert.pem \
  --tls-key /etc/kcp/key.pem \
  --api-key "$KCP_API_KEY"

# EC2-B — peers with hub only
kcp-memory daemon \
  --peer ssh://ec2-user@ec2-a.internal

# Laptop — peers with hub only (outbound SSH, works behind NAT)
kcp-memory daemon \
  --peer ssh://ec2-user@ec2-a.internal
```

### Step 4: Verify sync is working

```bash
# Check peer cursor state
sqlite3 ~/.kcp/memory.db "SELECT * FROM peer_cursors;"

# Check that remote events appear locally
curl -s http://localhost:7735/search?q=* | jq '.results[] | select(.source_instance != "local")'

# Check event counts by source
sqlite3 ~/.kcp/memory.db "SELECT source_instance, COUNT(*) FROM tool_events GROUP BY source_instance;"
sqlite3 ~/.kcp/memory.db "SELECT source_instance, COUNT(*) FROM sessions GROUP BY source_instance;"
```

### Step 5: Adding a new node later

```bash
# On the hub (EC2-A), add another --peer flag
kcp-memory daemon \
  --peer ssh://ec2-user@ec2-b.internal \
  --peer ssh://ec2-user@ec2-c.internal \
  --serve 0.0.0.0:8443 ...

# On the new node (EC2-C)
kcp-memory daemon --peer ssh://ec2-user@ec2-a.internal
```

Events from EC2-C flow to the hub, which forwards them to EC2-B (and vice versa)
via transitive sync.

## Troubleshooting

### Issue 1: SSH tunnel fails to establish

**Symptom:**
```
WARNING: Failed to spawn SSH tunnel: Cannot run program "ssh"
```

**Cause:** SSH binary not found or not in PATH.

**Solution:**
```bash
# Verify ssh is available
which ssh
ssh -V

# If using a custom SSH path
export PATH="/usr/bin:$PATH"
```

### Issue 2: Tunnel drops frequently

**Symptom:** Log shows repeated "SSH tunnel exited" / "Reconnecting" messages.

**Cause:** Network instability or missing SSH keepalive.

**Solution:** The tunnel already uses `ServerAliveInterval=30` and
`ServerAliveCountMax=3`. If still dropping, check:

```bash
# Test long-lived SSH connection manually
ssh -N -o ServerAliveInterval=30 ec2-user@ec2-b.internal
# Leave running for 10 minutes and observe

# Check if a firewall is killing idle connections
# If so, reduce the interval:
# In ~/.ssh/config:
Host ec2-b.internal
  ServerAliveInterval=15
```

### Issue 3: Events not syncing

**Symptom:** `peer_cursors` table is empty or timestamps are stale.

**Cause:** Tunnel is up but remote API isn't responding, or `since` parameter
isn't working.

**Solution:**
```bash
# Check if tunnel is forwarding correctly
TUNNEL_PORT=$(sqlite3 ~/.kcp/memory.db "SELECT 1;")  # get from logs
curl -s http://localhost:$TUNNEL_PORT/health

# Manually test the sync endpoints
curl -s "http://localhost:$TUNNEL_PORT/sessions?limit=5" | jq .
curl -s "http://localhost:$TUNNEL_PORT/events/search?q=*&limit=5" | jq .

# Check cursor state
sqlite3 ~/.kcp/memory.db "SELECT * FROM peer_cursors;"
```

### Issue 4: Duplicate events across nodes

**Symptom:** Same event appears multiple times with different source_instance.

**Cause:** Missing `event_hash` — events inserted before V6 migration.

**Solution:**
```bash
# Backfill event hashes for existing events
sqlite3 ~/.kcp/memory.db "
  UPDATE tool_events
  SET event_hash = hex(sha256(timestamp || '|' || tool || '|' || command || '|' || session_id))
  WHERE event_hash IS NULL;
"

# Verify no duplicates
sqlite3 ~/.kcp/memory.db "
  SELECT event_hash, COUNT(*) c FROM tool_events
  GROUP BY event_hash HAVING c > 1;
"
```

## Best Practices

✅ **Use hub-and-spoke**: One hub with `--serve` sees everything. Spokes only
need one `--peer` connection.

✅ **Let the laptop initiate outbound**: Laptops behind NAT can push/pull
through their own SSH tunnel. The hub doesn't need to reach back.

✅ **Use SSM Session Manager**: Avoids opening port 22. Add ProxyCommand to
`~/.ssh/config` on each instance.

❌ **Don't create full mesh for >3 nodes**: Hub-and-spoke scales linearly.
Full mesh grows quadratically.

❌ **Don't run --serve on a laptop**: Laptops sleep. Put --serve on an
always-on EC2 instance.

## Related Skills

**Next Steps:**
- **exocortex-serve-setup** — Configure the mobile-facing external API
- **exocortex-debug** — Diagnose sync issues, check event flow, verify health

---

**Status:** draft | **Priority:** high
**Tags:** #exocortex #infrastructure #kcp #sync #ssh
**Last Updated:** 2026-04-12
