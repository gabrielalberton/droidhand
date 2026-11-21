import { WebSocketServer, WebSocket, type RawData } from "ws";
import { randomUUID } from "node:crypto";
import { parseFrame, type DeviceInfo, type Frame } from "../protocol.js";

interface Pending {
  resolve: (data: unknown) => void;
  reject: (err: Error) => void;
  timer: NodeJS.Timeout;
}

export type EventHandler = (event: string, payload: unknown) => void;

/**
 * Relay: a WebSocket server the phone dials into (outbound-only from the phone).
 * Binds to the configured host (loopback or the Tailscale IP) and authenticates
 * the single phone session with a shared bearer token. Correlates cmd→result and
 * fans out device events.
 */
export class Relay {
  private wss: WebSocketServer | null = null;
  private phone: WebSocket | null = null;
  private device: DeviceInfo | null = null;
  private readonly pending = new Map<string, Pending>();
  private readonly eventHandlers = new Set<EventHandler>();

  constructor(
    private readonly host: string,
    private readonly port: number,
    private readonly token: string,
    private readonly cmdTimeoutMs: number,
  ) {}

  start(): Promise<void> {
    return new Promise((resolve, reject) => {
      const wss = new WebSocketServer({ host: this.host, port: this.port });
      wss.on("listening", () => resolve());
      wss.on("error", reject);
      wss.on("connection", (ws) => this.onConnection(ws));
      this.wss = wss;
    });
  }

  async stop(): Promise<void> {
    for (const p of this.pending.values()) {
      clearTimeout(p.timer);
      p.reject(new Error("relay shutting down"));
    }
    this.pending.clear();
    this.phone?.close();
    this.phone = null;
    await new Promise<void>((res) => (this.wss ? this.wss.close(() => res()) : res()));
    this.wss = null;
  }

  get connected(): boolean {
    return this.phone !== null && this.phone.readyState === WebSocket.OPEN;
  }

  get deviceInfo(): DeviceInfo | null {
    return this.device;
  }

  onEvent(handler: EventHandler): void {
    this.eventHandlers.add(handler);
  }

  /** The actual bound port (useful when port 0 was requested in tests). */
  address(): { host: string; port: number } | null {
    const a = this.wss?.address();
    if (a && typeof a === "object") return { host: this.host, port: a.port };
    return null;
  }

  /** Send a tool command to the phone and await its result. */
  dispatch(tool: string, args: Record<string, unknown>): Promise<unknown> {
    const ws = this.phone;
    if (!ws || ws.readyState !== WebSocket.OPEN) {
      return Promise.reject(new Error("no device connected"));
    }
    const id = randomUUID();
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new Error(`timeout after ${this.cmdTimeoutMs}ms waiting for '${tool}'`));
      }, this.cmdTimeoutMs);
      this.pending.set(id, { resolve, reject, timer });
      ws.send(JSON.stringify({ type: "cmd", id, tool, args }));
    });
  }

  /** Sever the phone session immediately (panic / kill switch). */
  disconnectPhone(): void {
    this.phone?.close(4000, "disconnected by operator");
    this.phone = null;
    this.device = null;
  }

  private onConnection(ws: WebSocket): void {
    // The first frame must be a valid hello within a short window, or we close.
    const authTimer = setTimeout(() => ws.close(4001, "auth timeout"), 5000);
    let authed = false;

    ws.on("message", (raw: RawData) => {
      const frame = parseFrame(raw.toString());
      if (!frame) return;

      if (!authed) {
        if (frame.type !== "hello" || frame.token !== this.token || this.token === "") {
          ws.close(4003, "unauthorized");
          return;
        }
        clearTimeout(authTimer);
        authed = true;
        // Replace any previous phone (last writer wins; single session).
        if (this.phone && this.phone !== ws) this.phone.close(4000, "replaced by new session");
        this.phone = ws;
        this.device = frame.device;
        // Lets the app show "connected" only once the token was accepted.
        ws.send(JSON.stringify({ type: "event", event: "welcome", payload: null }));
        return;
      }

      this.onFrame(frame);
    });

    ws.on("close", () => {
      clearTimeout(authTimer);
      if (this.phone === ws) {
        this.phone = null;
        this.device = null;
        for (const [id, p] of this.pending) {
          clearTimeout(p.timer);
          p.reject(new Error("device disconnected"));
          this.pending.delete(id);
        }
      }
    });

    ws.on("error", () => {
      /* close handler does cleanup */
    });
  }

  private onFrame(frame: Frame): void {
    if (frame.type === "result") {
      const p = this.pending.get(frame.id);
      if (!p) return;
      clearTimeout(p.timer);
      this.pending.delete(frame.id);
      if (frame.ok) p.resolve(frame.data);
      else p.reject(new Error(frame.error ?? "device reported failure"));
    } else if (frame.type === "event") {
      // The phone re-sends its device info when a device-side toggle changes.
      if (frame.event === "device_info" && frame.payload && typeof frame.payload === "object") {
        this.device = { ...(this.device ?? { suggestionLogging: false }), ...(frame.payload as DeviceInfo) };
      }
      for (const h of this.eventHandlers) h(frame.event, frame.payload);
    } else if (frame.type === "ping") {
      this.phone?.send(JSON.stringify({ type: "pong" }));
    }
  }
}
