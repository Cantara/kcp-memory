# V11 Auto-Tagging — Installation & Bootstrap

Requires kcp-memory **v0.31.0** or later.

---

## 1. Build and deploy the JAR

```bash
cd /src/cantara/kcp-memory/java
mvn clean package -DskipTests
cp target/kcp-memory-*.jar ~/.kcp/kcp-memory-daemon.jar
```

The daemon picks up V11 schema changes automatically on first start — no manual migration needed.

---

## 2. Install the hook script

```bash
cp /src/cantara/kcp-memory/java/scripts/kcp-tag-hook.sh ~/.kcp/kcp-tag-hook.sh
# or, if working from repo:
cp ~/.kcp/kcp-tag-hook.sh ~/.kcp/kcp-tag-hook.sh   # already in place on X1-Carbon
chmod +x ~/.kcp/kcp-tag-hook.sh
```

---

## 3. Register the hook in Claude Code

Edit `~/.claude.json` (use Python — file is large):

```bash
python3 - << 'EOF'
import json
with open('/home/totto/.claude.json', 'r') as f:
    d = json.load(f)
d['hooks'] = d.get('hooks', {})
d['hooks']['UserPromptSubmit'] = d['hooks'].get('UserPromptSubmit', [])
# Add only if not already present
hook_cmd = "bash /home/totto/.kcp/kcp-tag-hook.sh"
existing = [h for entry in d['hooks']['UserPromptSubmit'] for h in entry.get('hooks', []) if h.get('command') == hook_cmd]
if not existing:
    d['hooks']['UserPromptSubmit'].append({
        "matcher": "",
        "hooks": [{"type": "command", "command": hook_cmd}]
    })
with open('/home/totto/.claude.json', 'w') as f:
    json.dump(d, f, indent=2)
print("done")
EOF
```

**For Neurons:** adjust the path to match the node's home directory.

---

## 4. Verify it's working

Start a new Claude Code session, then check if pending tags were queued:

```bash
# Should return 200 + tag list for the current session
SESSION_ID=$(cat ~/.claude/projects/*/*)  # or get from hook payload logs
curl -s "http://localhost:7735/tags/pending?session_id=$SESSION_ID"
```

After the next scan (or restart), tags appear on the session:

```bash
kcp-memory list --tag node:X1-Carbon-2022 --limit 5
```

---

## 5. Project-level tags via `.kcp-tags`

Drop a `.kcp-tags` file in any project root. All sessions started from within that
directory tree will automatically receive those tags. Lines starting with `#` are comments.

```
# .kcp-tags — auto-applied to all sessions in this repo
mynder
ExoCortex-CC
```

Multiple `.kcp-tags` files are merged as the hook walks up the directory tree.

---

## 6. Per-session label from environment

Set `KCP_SESSION_LABEL` before launching Claude Code:

```bash
KCP_SESSION_LABEL=pr-919 claude
```

Useful for CI/CD, overnight batch jobs, or short sprint tags.

---

## 7. Tag a specific past session manually

```bash
# Full UUID
kcp-memory tag add <session-id> mynder pr-919

# Unique prefix works too
kcp-memory tag add bbbb2222 mynder
```

Remove a tag:

```bash
kcp-memory tag remove <session-id> mynder
```

---

## 8. Deploy on a Neuron

On each Neuron, run steps 1–3 with paths adjusted for the node user:

```bash
# On neuron-demo (ec2-user)
scp ~/.kcp/kcp-memory-daemon.jar neuron-demo:~/.kcp/
scp ~/.kcp/kcp-tag-hook.sh neuron-demo:~/.kcp/
ssh neuron-demo 'chmod +x ~/.kcp/kcp-tag-hook.sh'
# Then register hook in neuron's ~/.claude.json (same Python snippet above,
# adjust username/path)
```

After restart, sessions on Neuron get `node:ip-172-31-...` auto-tagged and the tag
syncs back to Mimir/X1-Carbon via peer sync.

---

## 9. Fallback: daemon down

If the kcp-memory daemon isn't running when a session starts, the hook writes a
JSON file to `~/.kcp/pending-tags/<session-id>.json`. These are **not** automatically
applied — to apply them manually once the daemon is back:

```bash
for f in ~/.kcp/pending-tags/*.json; do
    curl -s -X POST http://localhost:7735/tags/pending \
         -H "Content-Type: application/json" \
         -d "$(cat "$f")" && rm "$f"
done
```

---

## Tag taxonomy (auto-derived)

| Tag | Source | Example |
|-----|--------|---------|
| `basename $PWD` | working directory name | `kcp-memory` |
| `branch:X` | git branch | `branch:feat/v11-tags` |
| `repo:org/name` | git remote origin | `repo:cantara/kcp-memory` |
| `node:X` | hostname -s | `node:X1-Carbon-2022` |
| `stack:java` | pom.xml / build.gradle | `stack:java` |
| `stack:node` | package.json | `stack:node` |
| `stack:rust` | Cargo.toml | `stack:rust` |
| `stack:go` | go.mod | `stack:go` |
| `stack:python` | pyproject.toml / requirements.txt | `stack:python` |
| `.kcp-tags` file | project root walk-up | `mynder`, `ExoCortex-CC` |
| `$KCP_SESSION_LABEL` | environment variable | `pr-919` |
