import { hostname } from "node:os";
import { Server } from "@modelcontextprotocol/sdk/server/index.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import {
  CallToolRequestSchema,
  ListToolsRequestSchema,
} from "@modelcontextprotocol/sdk/types.js";

const KCP_BASE_URL = process.env.KCP_MEMORY_URL ?? "http://localhost:7735";
// Matches the peer-id convention used for dispatch throughout ExoCortex — see
// exocortex-neurons.md — so this needs no per-node config: it's whatever the
// host is already known as.
const NODE_ID = process.env.KCP_PEER_ID ?? hostname();
const POLL_INTERVAL_MS = 3000;

interface PendingTask {
  taskId: string;
  prompt: string;
  status: string;
}

// The daemon claims atomically (queued -> claimed) and won't hand the same
// task to another poll until it's completed, so at most one task is ever
// "current" for this node at a time — that's also what `reply` targets.
let currentTask: PendingTask | null = null;

const server = new Server(
  {
    name: "kcp-channel",
    version: "1.1.0",
  },
  {
    capabilities: {
      tools: {},
      experimental: {
        "claude/channel": {},
      },
    },
  }
);

server.setRequestHandler(ListToolsRequestSchema, async () => {
  return {
    tools: [
      {
        name: "reply",
        description: "Send a reply back through the kcp-memory channel",
        inputSchema: {
          type: "object",
          properties: {
            text: {
              type: "string",
              description: "The reply text to send",
            },
          },
          required: ["text"],
        },
      },
    ],
  };
});

server.setRequestHandler(CallToolRequestSchema, async (request) => {
  if (request.params.name !== "reply") {
    return {
      content: [{ type: "text", text: `Unknown tool: ${request.params.name}` }],
      isError: true,
    };
  }

  const { text } = request.params.arguments as { text: string };

  if (!currentTask) {
    process.stderr.write(`[kcp-channel] reply with no active task: ${text}\n`);
    return {
      content: [
        {
          type: "text",
          text: "No pending task to reply to — nothing was sent.",
        },
      ],
      isError: true,
    };
  }

  const taskId = currentTask.taskId;

  try {
    const response = await fetch(`${KCP_BASE_URL}/pending/result`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ taskId, result: text }),
      signal: AbortSignal.timeout(5000),
    });

    if (!response.ok) {
      process.stderr.write(
        `[kcp-channel] reply POST failed: HTTP ${response.status}\n`
      );
      return {
        content: [
          { type: "text", text: `Reply failed to send: HTTP ${response.status}` },
        ],
        isError: true,
      };
    }

    process.stderr.write(`[kcp-channel] reply sent for task ${taskId}\n`);
    currentTask = null;

    return {
      content: [{ type: "text", text: "Reply sent." }],
    };
  } catch (err) {
    process.stderr.write(`[kcp-channel] reply error for task ${taskId}: ${err}\n`);
    return {
      content: [{ type: "text", text: `Reply failed to send: ${err}` }],
      isError: true,
    };
  }
});

async function pollPending(): Promise<void> {
  try {
    const url = `${KCP_BASE_URL}/pending?peer=${encodeURIComponent(NODE_ID)}`;
    const response = await fetch(url, { signal: AbortSignal.timeout(5000) });

    if (response.status === 204) {
      return; // nothing queued
    }

    if (!response.ok) {
      process.stderr.write(
        `[kcp-channel] poll failed: HTTP ${response.status}\n`
      );
      return;
    }

    const task = (await response.json()) as PendingTask;
    if (!task?.taskId || !task.prompt) {
      process.stderr.write(
        `[kcp-channel] poll returned an unexpected shape: ${JSON.stringify(task)}\n`
      );
      return;
    }

    // Still the task we already surfaced and are waiting on a reply for.
    if (currentTask?.taskId === task.taskId) {
      return;
    }

    currentTask = task;

    process.stderr.write(
      `[kcp-channel] emitting task ${task.taskId}: ${task.prompt}\n`
    );

    try {
      await server.notification({
        method: "notifications/claude/channel",
        params: {
          content: task.prompt,
          meta: { sender_id: "kcp-memory", task_id: task.taskId },
        },
      });
    } catch (err) {
      process.stderr.write(
        `[kcp-channel] notification error for task ${task.taskId}: ${err}\n`
      );
    }
  } catch (err) {
    process.stderr.write(`[kcp-channel] poll error: ${err}\n`);
  }
}

async function main(): Promise<void> {
  const transport = new StdioServerTransport();
  await server.connect(transport);

  process.stderr.write(
    `[kcp-channel] connected as peer "${NODE_ID}", polling ${KCP_BASE_URL} every 3s\n`
  );

  setInterval(() => {
    pollPending().catch((err) => {
      process.stderr.write(`[kcp-channel] unexpected poll error: ${err}\n`);
    });
  }, POLL_INTERVAL_MS);
}

main().catch((err) => {
  process.stderr.write(`[kcp-channel] fatal: ${err}\n`);
  process.exit(1);
});
