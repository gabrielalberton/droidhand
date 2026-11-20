import { WebSocketServer, WebSocket, type RawData } from "ws";
import { randomUUID } from "node:crypto";
import type { Core, ToolResult } from "./core.js";

/**
 * Control channel: the single relay daemon owns the phone connection and exposes
 * a loopback WebSocket that per-session MCP adapters connect to. This keeps the
 * phone-facing port owned by exactly one process while any number of agent
 * sessions (Claude Code, Codex, …) drive the phone through their own adapter.
 */

export interface ControlServerHandle {
  close(): Promise<void>;
  address(): { port: number } | null;
}

export function startControlServer(core: Core, host: string, port: number, token: string): Promise<ControlServerHandle> {
  return new Promise((resolve, reject) => {
    const wss = new WebSocketServer({ host, port });
    const clients = new Set<WebSocket>();

    core.relay.onEvent((event, payload) => {
      const msg = JSON.stringify({ type: "event", event, payload });
      for (const c of clients) if (c.readyState === WebSocket.OPEN) c.send(msg);
    });

    wss.on("error", reject);
    wss.on("connection", (ws) => {
      let authed = false;
      const authTimer = setTimeout(() => ws.close(4001, "auth timeout"), 5000);
      ws.on("message", async (raw: RawData) => {
        let f: { type?: string; token?: string; id?: string; tool?: string; args?: Record<string, unknown> };
        try {
          f = JSON.parse(raw.toString());
        } catch {
          return;
        }
        if (!authed) {
          if (f.type !== "hello" || f.token !== token || token === "") {
            ws.close(4003, "unauthorized");
            return;
          }
          clearTimeout(authTimer);
          authed = true;
          clients.add(ws);
          return;
        }
        if (f.type === "call" && f.id && f.tool) {
          const res: ToolResult = await core.callTool(f.tool, f.args ?? {});
          ws.send(JSON.stringify({ type: "result", id: f.id, ...res }));
        }
      });
      ws.on("close", () => {
        clearTimeout(authTimer);
        clients.delete(ws);
      });
      ws.on("error", () => {});
    });

    wss.on("listening", () =>
      resolve({
        close: () =>
          new Promise<void>((res) => {
            for (const c of clients) c.close();
            wss.close(() => res());
          }),
        address: () => {
          const a = wss.address();
          return a && typeof a === "object" ? { port: a.port } : null;
        },
      }),
    );
  });
}

/** Client used by the stdio MCP adapter to reach the relay daemon. */
export class ControlClient {
  private ws: WebSocket | null = null;
  private connecting: Promise<void> | null = null;
  private readonly pending = new Map<string, { resolve: (r: ToolResult) => void; reject: (e: Error) => void; timer: NodeJS.Timeout }>();

  constructor(
    private readonly url: string,
    private readonly token: string,
    private readonly timeoutMs = 15000,
  ) {}

  private ensureConnected(): Promise<void> {
    if (this.ws && this.ws.readyState === WebSocket.OPEN) return Promise.resolve();
    if (this.connecting) return this.connecting;
    this.connecting = new Promise<void>((resolve, reject) => {
      const ws = new WebSocket(this.url);
      const to = setTimeout(() => {
        ws.terminate();
        reject(new Error("control connect timeout"));
      }, 4000);
      ws.on("open", () => {
        ws.send(JSON.stringify({ type: "hello", token: this.token }));
        clearTimeout(to);
        this.ws = ws;
        resolve();
      });
      ws.on("message", (raw: RawData) => this.onMessage(raw));
      ws.on("close", () => {
        this.ws = null;
        for (const [id, p] of this.pending) {
          clearTimeout(p.timer);
          p.reject(new Error("control channel closed"));
          this.pending.delete(id);
        }
      });
      ws.on("error", (err) => {
        clearTimeout(to);
        reject(err);
      });
    }).finally(() => {
      this.connecting = null;
    });
    return this.connecting;
  }

  private onMessage(raw: RawData): void {
    let f: { type?: string; id?: string; ok?: boolean; data?: unknown; error?: string };
    try {
      f = JSON.parse(raw.toString());
    } catch {
      return;
    }
    if (f.type === "result" && f.id) {
      const p = this.pending.get(f.id);
      if (!p) return;
      clearTimeout(p.timer);
      this.pending.delete(f.id);
      p.resolve({ ok: Boolean(f.ok), data: f.data, error: f.error });
    }
  }

  async call(tool: string, args: Record<string, unknown>): Promise<ToolResult> {
    await this.ensureConnected();
    const ws = this.ws;
    if (!ws) throw new Error("control channel not connected");
    const id = randomUUID();
    return new Promise<ToolResult>((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new Error(`control call timeout for '${tool}'`));
      }, this.timeoutMs);
      this.pending.set(id, { resolve, reject, timer });
      ws.send(JSON.stringify({ type: "call", id, tool, args }));
    });
  }

  close(): void {
    this.ws?.close();
    this.ws = null;
  }
}
