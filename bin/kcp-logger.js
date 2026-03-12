#!/usr/bin/env node

const fs = require("fs");
const os = require("os");
const path = require("path");

async function main() {
  const payload = await readPayload();
  const event = normalizeEvent(payload);
  const outputPath = process.env.KCP_EVENTS_FILE || path.join(os.homedir(), ".kcp", "events.jsonl");

  fs.mkdirSync(path.dirname(outputPath), { recursive: true });
  fs.appendFileSync(outputPath, JSON.stringify(event) + "\n", "utf8");
}

async function readPayload() {
  if (process.argv[2]) {
    try {
      return JSON.parse(process.argv[2]);
    } catch (_) {
      return { command: process.argv.slice(2).join(" ") };
    }
  }

  const chunks = [];
  for await (const chunk of process.stdin) chunks.push(chunk);
  const text = Buffer.concat(chunks).toString("utf8").trim();
  if (!text) return {};

  try {
    return JSON.parse(text);
  } catch (_) {
    return { raw: text };
  }
}

function normalizeEvent(payload) {
  const ts = payload.ts || payload.timestamp || new Date().toISOString();
  const sessionId =
    payload.session_id ||
    payload.sessionId ||
    payload.conversation_id ||
    payload.conversationId ||
    process.env.CODEX_SESSION_ID ||
    process.env.GEMINI_SESSION_ID ||
    "unknown-session";
  const projectDir =
    payload.project_dir ||
    payload.projectDir ||
    payload.cwd ||
    payload.working_directory ||
    payload.workspace ||
    process.cwd();
  const tool =
    payload.tool ||
    payload.tool_name ||
    payload.toolName ||
    payload.hook_event_name ||
    payload.event ||
    "tool";
  const command =
    payload.command ||
    payload.cmd ||
    payload.description ||
    payload.raw ||
    JSON.stringify(payload);
  const manifestKey =
    payload.manifest_key ||
    payload.manifestKey ||
    payload.matcher ||
    tool.toLowerCase().replace(/[^a-z0-9]+/g, "-");

  return {
    ts,
    session_id: String(sessionId),
    project_dir: String(projectDir),
    tool: String(tool),
    command: String(command),
    manifest_key: String(manifestKey)
  };
}

main().catch((err) => {
  console.error("[kcp-logger] " + err.message);
  process.exit(1);
});
