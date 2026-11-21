import { WebSocket, type RawData } from "ws";
import { randomUUID } from "node:crypto";
import type { ToolResult } from "../core/core.js";
import { asResult, parseObject, type ControlCall, type ControlHello } from "./frames.js";

const CONNECT_TIMEOUT_MS = 4000;

interface PendingCall {
  resolve: (r: ToolResult) => void;
  reject: (e: Error) => void;
  timer: NodeJS.Timeout;
}

/**
 * Control channel (adapter side): used by the stdio MCP adapter to reach the
 * relay daemon. Connects lazily on the first call and reconnects after a drop.
 */
export class ControlClient {
  private ws: WebSocket | null = null;
  private connecting: Promise<void> | null = null;
  private readonly pending = new Map<string, PendingCall>();

  constructor(
    private readonly url: string,
    private readonly token: string,
    private readonly timeoutMs = 15000,
  ) {}

  /** Run a tool through the daemon. Rejects if the daemon is unreachable, closes, or does not answer in time. */
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
      ws.send(JSON.stringify({ type: "call", id, tool, args } satisfies ControlCall));
    });
  }

  close(): void {
    this.ws?.close();
    this.ws = null;
  }

  private ensureConnected(): Promise<void> {
    if (this.ws && this.ws.readyState === WebSocket.OPEN) return Promise.resolve();
    if (this.connecting) return this.connecting;
    this.connecting = new Promise<void>((resolve, reject) => {
      const ws = new WebSocket(this.url);
      const to = setTimeout(() => {
        ws.terminate();
        reject(new Error("control connect timeout"));
      }, CONNECT_TIMEOUT_MS);
      ws.on("open", () => {
        ws.send(JSON.stringify({ type: "hello", token: this.token } satisfies ControlHello));
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
    const f = parseObject(raw.toString());
    const res = f && asResult(f);
    if (!res) return;
    const p = this.pending.get(res.id);
    if (!p) return;
    clearTimeout(p.timer);
    this.pending.delete(res.id);
    p.resolve({ ok: res.ok, data: res.data, error: res.error });
  }
}
