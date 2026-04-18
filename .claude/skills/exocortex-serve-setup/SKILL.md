---
name: exocortex-serve-setup
description: Configure and test kcp-memory --serve external API for Android mobile access
tags: [exocortex, infrastructure, kcp, mobile, tls, api]
status: draft
priority: high
relatedSkills: [exocortex-peer-setup, exocortex-debug]
---

# ExoCortex Mobile Serve Setup

## When to Use This

Use this skill when:

- **Setting up mobile access**: Configuring `--serve` for the first time on the hub instance
- **TLS certificate issues**: Self-signed certs, Let's Encrypt, or PKCS12 keystore problems
- **Testing endpoints**: Verifying dispatch, capture, search, and Synthesis proxy work from outside
- **Android app connectivity**: The mobile app can't reach or authenticate with the API

You're in the right place if:
- You need to expose kcp-memory's API externally with TLS and auth
- You're setting up the Android ExoCortex app for the first time
- You want to test the API with curl before connecting the mobile app

## Quick Start

```bash
# Generate a self-signed cert for development
openssl req -x509 -newkey rsa:2048 -keyout key.pem -out cert.pem -days 365 -nodes \
  -subj "/CN=exocortex.internal"

# Convert to PKCS12 (what Java TLS expects)
openssl pkcs12 -export -in cert.pem -inkey key.pem -out keystore.p12 -password pass:changeit

# Start with external API
export KCP_API_KEY=$(openssl rand -hex 32)
kcp-memory daemon \
  --serve 0.0.0.0:8443 \
  --tls-cert keystore.p12 \
  --tls-key changeit \
  --api-key "$KCP_API_KEY"

# Test from another machine
curl -sk https://ec2-a.internal:8443/health \
  -H "Authorization: Bearer $KCP_API_KEY"
```

## Overview

The `--serve` flag starts a second HTTP server (alongside the internal :7735)
that is TLS-encrypted, API-key-protected, and bound to an external interface.
This is the single entry point for the Android app and any other external client.

It exposes all internal endpoints (search, sessions, stats, events) plus
mobile-specific endpoints:

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/health` | GET | Liveness check |
| `/search?q=` | GET | Full-text session search |
| `/sessions` | GET | List recent sessions (all nodes via peer sync) |
| `/events/search?q=` | GET | Tool event search |
| `/stats` | GET | Aggregate statistics |
| `/sessions/{id}/content` | GET | Full session transcript as parsed messages |
| `/dispatch` | POST | Send task to Claude Code, stream results |
| `/capture` | POST | Ingest voice/photo/note from mobile |
| `/synthesis/search?q=` | GET | Proxy query to local Synthesis |
| `/ws` | WebSocket | Live session_message + tool event broadcast |

All requests require `Authorization: Bearer <api-key>`.

Relevant files:
- `server/ExternalHttpServer.java` — TLS + API key auth server
- `server/EventBroadcaster.java` — WebSocket fan-out (tool events + session messages)
- `server/WsHandler.java` — WebSocket upgrade handler on `/ws`
- `scanner/SessionFileWatcher.java` — watches `~/.claude/projects/**/*.jsonl`, broadcasts new messages
- `handler/SessionContentHandler.java` — reads JSONL transcript, parses user/assistant turns
- `handler/DispatchHandler.java` — Claude Code task dispatch
- `handler/CaptureHandler.java` — Mobile knowledge capture ingest
- `handler/SynthesisProxyHandler.java` — Synthesis search proxy

### WebSocket event types

The `/ws` endpoint broadcasts two event shapes:

**Tool event** (from kcp hook / event log):
```json
{"type":"tool_event","tool":"Bash","command":"...","peerId":"...","sessionId":"...","timestamp":"..."}
```

**Session message** (from SessionFileWatcher watching JSONL files):
```json
{"type":"session_message","sessionId":"...","role":"user|assistant","text":"...","timestamp":"...","uuid":"..."}
```

Android's `ExoCortexService` consumes tool events. `SessionDetailViewModel.watchLive()` consumes session messages filtered by sessionId.

### Session content endpoint

`GET /sessions/{sessionId}/content` reads the JSONL transcript:
```json
{
  "sessionId": "abc123",
  "slug": "-home-totto-src-myproject",
  "messageCount": 47,
  "messages": [
    {"role": "user", "text": "...", "timestamp": "...", "uuid": "..."},
    {"role": "assistant", "text": "...", "timestamp": "...", "uuid": "..."}
  ]
}
```
Parses `message.content` as either a plain String or an array of `{type:"text", text:"..."}` blocks.

## Implementation Guide

### Step 1: TLS Certificate

**Option A: Self-signed (development)**
```bash
openssl req -x509 -newkey rsa:2048 -keyout key.pem -out cert.pem -days 365 -nodes \
  -subj "/CN=$(hostname)"
openssl pkcs12 -export -in cert.pem -inkey key.pem -out ~/.kcp/keystore.p12 -password pass:changeit
```

**Option B: Let's Encrypt (production)**
```bash
# Using certbot with a domain pointed at your EC2 instance
sudo certbot certonly --standalone -d exocortex.yourdomain.com
openssl pkcs12 -export \
  -in /etc/letsencrypt/live/exocortex.yourdomain.com/fullchain.pem \
  -inkey /etc/letsencrypt/live/exocortex.yourdomain.com/privkey.pem \
  -out ~/.kcp/keystore.p12 -password pass:changeit
```

**Option C: No TLS (behind a reverse proxy)**

If you run nginx/caddy in front, skip TLS on kcp-memory:
```bash
# kcp-memory without TLS (proxy handles it)
kcp-memory daemon --serve 127.0.0.1:8080 --api-key "$KCP_API_KEY"
```

### Step 2: API Key

```bash
# Generate a strong key
export KCP_API_KEY=$(openssl rand -hex 32)
echo "$KCP_API_KEY" > ~/.kcp/api-key
chmod 600 ~/.kcp/api-key

# Or set in environment for systemd
# In /etc/systemd/system/kcp-memory.service:
# Environment=KCP_API_KEY=<your-key>
```

### Step 3: Start the external server

```bash
kcp-memory daemon \
  --peer ssh://ec2-user@ec2-b.internal \
  --serve 0.0.0.0:8443 \
  --tls-cert ~/.kcp/keystore.p12 \
  --tls-key changeit \
  --api-key "$KCP_API_KEY" \
  --capture-dir ~/.kcp/captures \
  --synthesis-cmd "synthesis search"
```

### Step 4: Test every endpoint

```bash
API="https://ec2-a.internal:8443"
AUTH="Authorization: Bearer $KCP_API_KEY"

# Health
curl -sk "$API/health" -H "$AUTH" | jq .

# Search sessions
curl -sk "$API/search?q=lib-pcb&limit=5" -H "$AUTH" | jq .

# List sessions (includes remote via peer sync)
curl -sk "$API/sessions?limit=10" -H "$AUTH" | jq .

# Search events
curl -sk "$API/events/search?q=mvn+test&limit=5" -H "$AUTH" | jq .

# Stats
curl -sk "$API/stats" -H "$AUTH" | jq .

# Dispatch a task (streaming response)
curl -sk "$API/dispatch" -H "$AUTH" \
  -H "Content-Type: application/json" \
  -d '{"prompt":"What files are in the current directory?","allowed_tools":["Bash","Glob"]}' \
  --no-buffer

# Capture a note
curl -sk "$API/capture" -H "$AUTH" \
  -H "Content-Type: application/json" \
  -d '{"type":"note","content":"Remember to update the CI pipeline","tags":["ops"]}'

# Synthesis search
curl -sk "$API/synthesis/search?q=PCBDesign&limit=5" -H "$AUTH" | jq .

# Verify auth is enforced (should return 401)
curl -sk "$API/health"
```

### Step 5: AWS Security Group

```bash
# Allow inbound 8443 from your IP / VPN only
aws ec2 authorize-security-group-ingress \
  --group-id sg-xxx \
  --protocol tcp \
  --port 8443 \
  --cidr <your-ip>/32
```

### Step 6: systemd service

```ini
# /etc/systemd/system/kcp-memory.service
[Unit]
Description=kcp-memory ExoCortex daemon
After=network.target

[Service]
Type=simple
User=ec2-user
Environment=KCP_API_KEY=<your-key>
ExecStart=/usr/bin/java --enable-native-access=ALL-UNNAMED \
  -jar /home/ec2-user/.kcp/kcp-memory-daemon.jar daemon \
  --peer ssh://ec2-user@ec2-b.internal \
  --serve 0.0.0.0:8443 \
  --tls-cert /home/ec2-user/.kcp/keystore.p12 \
  --tls-key changeit \
  --capture-dir /home/ec2-user/.kcp/captures \
  --synthesis-cmd "synthesis search"
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl enable kcp-memory
sudo systemctl start kcp-memory
sudo journalctl -u kcp-memory -f
```

## Troubleshooting

### Issue 1: "Unauthorized" from mobile app

**Symptom:** All requests return `{"error":"Unauthorized"}`

**Solution:**
```bash
# Verify the API key matches
echo $KCP_API_KEY
# Compare with what the app is sending

# Test with explicit header
curl -sk https://host:8443/health -H "Authorization: Bearer YOUR_KEY"
```

### Issue 2: TLS handshake failure

**Symptom:** `javax.net.ssl.SSLHandshakeException` or `curl: SSL certificate problem`

**Solution:**
```bash
# Verify the keystore is valid
keytool -list -keystore ~/.kcp/keystore.p12 -storetype PKCS12 -storepass changeit

# Test TLS with openssl
openssl s_client -connect localhost:8443 -servername localhost

# For self-signed certs, use -k with curl (or install the CA on Android)
curl -sk https://host:8443/health -H "Authorization: Bearer $KEY"
```

### Issue 3: Dispatch hangs or returns empty

**Symptom:** POST /dispatch returns no output or times out.

**Solution:**
```bash
# Verify claude is installed and on PATH
which claude
claude --version

# Test claude -p directly
claude -p "echo hello" --output-format stream-json --allowedTools "Bash"

# Check the working directory exists
ls -la /home/ec2-user/projects/lib-pcb
```

### Issue 4: Capture files not being indexed by Synthesis

**Symptom:** Notes captured via mobile don't appear in Synthesis search.

**Solution:**
```bash
# Check captures directory
ls -la ~/.kcp/captures/

# Verify Synthesis watches this directory
synthesis status

# Trigger a manual Synthesis scan
synthesis scan ~/.kcp/captures/
```

## Best Practices

✅ **Use Let's Encrypt for production**: Self-signed certs require
installing the CA on every Android device.

✅ **Restrict Security Group**: Only allow :8443 from your IP or VPN CIDR.

✅ **Rotate the API key periodically**: Generate a new one, update the
Android app, restart the daemon.

✅ **Run behind a reverse proxy**: Caddy with automatic HTTPS is simpler
than managing PKCS12 keystores.

❌ **Don't expose without TLS**: Even with an API key, traffic would be
readable in transit.

❌ **Don't use --serve on port 7735**: That's the internal API. Keep them
on separate ports.

## Related Skills

**Prerequisites:**
- **exocortex-peer-setup** — Set up peer sync before enabling mobile access

**Next Steps:**
- **exocortex-debug** — Diagnose issues with the full ExoCortex stack

---

**Status:** draft | **Priority:** high
**Tags:** #exocortex #infrastructure #kcp #mobile #tls #api
**Last Updated:** 2026-04-12
