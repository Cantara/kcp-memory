/**
 * kcp-memory Pi Extension
 *
 * Provides episodic memory integration for Pi sessions:
 *
 * 1. On session_start: Triggers a scan and loads project context from recent
 *    sessions so the model starts with awareness of past work.
 *
 * 2. On session_shutdown: Triggers a scan so the current session is indexed
 *    before the next one starts.
 *
 * 3. Registers a /memory command for on-demand searches.
 *
 * Works with the kcp-memory daemon on port 7735.
 *
 * Install: place in ~/.pi/agent/extensions/kcp-memory.ts
 */
import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";
import { Type } from "typebox";

const DAEMON_URL = "http://localhost:7735";

// ─── Daemon Communication ───────────────────────────────────────────────────

async function isDaemonRunning(): Promise<boolean> {
  try {
    const resp = await fetch(`${DAEMON_URL}/health`, { signal: AbortSignal.timeout(1000) });
    return resp.ok;
  } catch {
    return false;
  }
}

async function triggerScan(): Promise<void> {
  try {
    await fetch(`${DAEMON_URL}/scan`, {
      method: "POST",
      signal: AbortSignal.timeout(10000),
    });
  } catch { /* non-critical */ }
}

async function getProjectContext(projectDir: string, limit = 5): Promise<string> {
  try {
    const resp = await fetch(
      `${DAEMON_URL}/sessions?limit=${limit}&project=${encodeURIComponent(projectDir)}`,
      { signal: AbortSignal.timeout(3000) }
    );
    if (resp.ok) return await resp.text();
  } catch { /* daemon unavailable */ }
  return "";
}

async function searchSessions(query: string, limit = 10): Promise<string> {
  try {
    const resp = await fetch(
      `${DAEMON_URL}/sessions/search?q=${encodeURIComponent(query)}&limit=${limit}`,
      { signal: AbortSignal.timeout(3000) }
    );
    if (resp.ok) return await resp.text();
  } catch { /* daemon unavailable */ }
  return "";
}

async function searchEvents(query: string, limit = 20): Promise<string> {
  try {
    const resp = await fetch(
      `${DAEMON_URL}/events/search?q=${encodeURIComponent(query)}&limit=${limit}`,
      { signal: AbortSignal.timeout(3000) }
    );
    if (resp.ok) return await resp.text();
  } catch { /* daemon unavailable */ }
  return "";
}

// ─── Extension ──────────────────────────────────────────────────────────────

export default function (pi: ExtensionAPI) {

  // ── Load project context at session start ──────────────────────────────

  pi.on("session_start", async (_event, ctx) => {
    if (!await isDaemonRunning()) {
      ctx.ui.notify("[kcp-memory] daemon not running on port 7735", "warn");
      return;
    }

    // Trigger scan to pick up any new sessions
    await triggerScan();

    // Load project context
    const cwd = process.cwd();
    const context = await getProjectContext(cwd);

    if (context && context.trim().length > 10) {
      ctx.ui.notify("[kcp-memory] loaded project context", "info");
    } else {
      ctx.ui.notify("[kcp-memory] connected (no prior sessions for this project)", "info");
    }
  });

  // ── Index current session on shutdown ──────────────────────────────────

  pi.on("session_shutdown", async (_event, _ctx) => {
    if (await isDaemonRunning()) {
      await triggerScan();
    }
  });

  // ── /memory command for on-demand search ───────────────────────────────

  pi.registerCommand("memory", {
    description: "Search kcp-memory episodic history",
    handler: async (args, ctx) => {
      if (!args?.trim()) {
        ctx.ui.notify("Usage: /memory <search query>", "info");
        return;
      }

      if (!await isDaemonRunning()) {
        ctx.ui.notify("[kcp-memory] daemon not running", "error");
        return;
      }

      const result = await searchSessions(args.trim());
      if (result) {
        ctx.ui.notify(`[kcp-memory] results:\n${result.slice(0, 2000)}`, "info");
      } else {
        ctx.ui.notify("[kcp-memory] no results", "info");
      }
    },
  });

  // ── Custom tool: search memory (callable by the model) ─────────────────

  pi.registerTool({
    name: "kcp_memory_search",
    label: "Search Memory",
    description:
      "Search past coding sessions for relevant context. Use when you need to recall previous decisions, approaches, or debugging sessions related to the current task.",
    parameters: Type.Object({
      query: Type.String({ description: "Search query (FTS5 syntax supported)" }),
      limit: Type.Optional(Type.Number({ description: "Max results (default 10)" })),
    }),
    async execute(_toolCallId, params, signal) {
      if (!await isDaemonRunning()) {
        return {
          content: [{ type: "text", text: "[kcp-memory] daemon not running on port 7735" }],
          details: {},
          isError: true,
        };
      }
      const result = await searchSessions(params.query, params.limit ?? 10);
      return {
        content: [{ type: "text", text: result || "No results found." }],
        details: {},
      };
    },
  });

  pi.registerTool({
    name: "kcp_memory_events",
    label: "Search Events",
    description:
      "Search tool-call events (bash commands, their output previews, exit codes) from past sessions. Use to find what commands were run previously.",
    parameters: Type.Object({
      query: Type.String({ description: "Search query for events" }),
      limit: Type.Optional(Type.Number({ description: "Max results (default 20)" })),
    }),
    async execute(_toolCallId, params, signal) {
      if (!await isDaemonRunning()) {
        return {
          content: [{ type: "text", text: "[kcp-memory] daemon not running on port 7735" }],
          details: {},
          isError: true,
        };
      }
      const result = await searchEvents(params.query, params.limit ?? 20);
      return {
        content: [{ type: "text", text: result || "No events found." }],
        details: {},
      };
    },
  });

  pi.registerTool({
    name: "kcp_memory_project_context",
    label: "Project Context",
    description:
      "Get recent session summaries for the current project. Use at the start of complex tasks to understand what was done before.",
    parameters: Type.Object({
      limit: Type.Optional(Type.Number({ description: "Number of recent sessions (default 5)" })),
    }),
    async execute(_toolCallId, params, signal) {
      if (!await isDaemonRunning()) {
        return {
          content: [{ type: "text", text: "[kcp-memory] daemon not running on port 7735" }],
          details: {},
          isError: true,
        };
      }
      await triggerScan();
      const result = await getProjectContext(process.cwd(), params.limit ?? 5);
      return {
        content: [{ type: "text", text: result || "No sessions found for this project." }],
        details: {},
      };
    },
  });
}
