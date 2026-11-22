import { WebSocketServer, WebSocket, type RawData } from "ws";
import { randomUUID } from "node:crypto";
import { CloseCode, encodeFrame, parseFrame, type DeviceInfo, type Frame } from "../protocol.js";

/** Grace period for the first (hello) frame before the socket is closed. */
export const AUTH_TIMEOUT_MS = 5000;

interface Pending {
  device: string;
  resolve: (data: unknown) => void;
  reject: (err: Error) => void;
  timer: NodeJS.Timeout;
}

/** A device event: which phone, which event, its payload. */
export type EventHandler = (device: string, event: string, payload: unknown) => void;

/** Answers a phone's `req` frame (e.g. `relay_status`); a throw becomes an error answer. */
export type RequestHandler = (device: string, op: string, args: Record<string, unknown>) => Promise<unknown>;

/** One authenticated phone session. */
export interface PhoneSession {
  readonly device: string;
  readonly info: DeviceInfo;
  readonly connectedSince: Date;
}

interface Session {
  ws: WebSocket;
  info: DeviceInfo;
  connectedSince: Date;
}

/**
 * Relay: a WebSocket server the phones dial into (outbound-only from each phone).
 * Binds to the configured host (loopback or a private-network IP). Each phone
 * authenticates with its own token, which also identifies it; a new session of the
 * same phone replaces the old one. Correlates cmd→result per phone, fans out device
 * events and answers phone requests. See `protocol.ts` for the frames.
 */
export class Relay {
  private wss: WebSocketServer | null = null;
  private readonly sessions = new Map<string, Session>();
  private readonly lastSeenAt = new Map<string, Date>();
  private readonly pending = new Map<string, Pending>();
  private readonly eventHandlers = new Set<EventHandler>();
  private requestHandler: RequestHandler | null = null;
  private readonly byToken: Map<string, string>;

  constructor(
    private readonly host: string,
    private readonly port: number,
    /** token → phone id. Empty tokens are ignored (fail closed). */
    tokens: ReadonlyMap<string, string>,
    private readonly cmdTimeoutMs: number,
  ) {
    this.byToken = new Map([...tokens].filter(([t]) => t !== ""));
  }

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
    this.rejectPending(null, "relay shutting down");
    for (const s of this.sessions.values()) s.ws.close();
    this.sessions.clear();
    await new Promise<void>((res) => (this.wss ? this.wss.close(() => res()) : res()));
    this.wss = null;
  }

  /** True while [device] has an authenticated session open. */
  isConnected(device: string): boolean {
    return this.sessions.get(device)?.ws.readyState === WebSocket.OPEN;
  }

  /** Ids of the connected phones. */
  connectedDevices(): string[] {
    return [...this.sessions.keys()].filter((d) => this.isConnected(d));
  }

  /** What [device] reported in `hello` (updated by `device_info` events); null when disconnected. */
  deviceInfo(device: string): DeviceInfo | null {
    return this.sessions.get(device)?.info ?? null;
  }

  session(device: string): PhoneSession | null {
    const s = this.sessions.get(device);
    return s ? { device, info: s.info, connectedSince: s.connectedSince } : null;
  }

  /** Last frame received from [device] during this relay run. */
  lastSeen(device: string): Date | null {
    return this.lastSeenAt.get(device) ?? null;
  }

  /** Subscribe to device events. Returns an unsubscribe function. */
  onEvent(handler: EventHandler): () => void {
    this.eventHandlers.add(handler);
    return () => this.eventHandlers.delete(handler);
  }

  /** Set who answers phone requests (only one). */
  onRequest(handler: RequestHandler): void {
    this.requestHandler = handler;
  }

  /** The actual bound port (useful when port 0 was requested in tests). */
  address(): { host: string; port: number } | null {
    const a = this.wss?.address();
    if (a && typeof a === "object") return { host: this.host, port: a.port };
    return null;
  }

  /** Send a tool command to [device] and await its result data (rejects with the phone's error). */
  dispatch(device: string, tool: string, args: Record<string, unknown>): Promise<unknown> {
    const ws = this.sessions.get(device)?.ws;
    if (!ws || ws.readyState !== WebSocket.OPEN) {
      return Promise.reject(new Error(`device '${device}' is not connected`));
    }
    const id = randomUUID();
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new Error(`timeout after ${this.cmdTimeoutMs}ms waiting for '${tool}'`));
      }, this.cmdTimeoutMs);
      this.pending.set(id, { device, resolve, reject, timer });
      ws.send(encodeFrame({ type: "cmd", id, tool, args }));
    });
  }

  /** Sever [device]'s session immediately (panic / kill switch). */
  disconnect(device: string): void {
    const s = this.sessions.get(device);
    if (!s) return;
    this.sessions.delete(device);
    this.rejectPending(device, "device disconnected");
    s.ws.close(CloseCode.Replaced, "disconnected by operator");
  }

  private rejectPending(device: string | null, reason: string): void {
    for (const [id, p] of this.pending) {
      if (device !== null && p.device !== device) continue;
      clearTimeout(p.timer);
      p.reject(new Error(reason));
      this.pending.delete(id);
    }
  }

  private onConnection(ws: WebSocket): void {
    // The first frame must be a valid hello within a short window, or we close.
    const authTimer = setTimeout(() => ws.close(CloseCode.AuthTimeout, "auth timeout"), AUTH_TIMEOUT_MS);
    let device: string | null = null;

    ws.on("message", (raw: RawData) => {
      const frame = parseFrame(raw.toString());
      if (!frame) return;

      if (device === null) {
        const id = frame.type === "hello" && typeof frame.token === "string" ? this.byToken.get(frame.token) : undefined;
        if (!id) {
          ws.close(CloseCode.Unauthorized, "unauthorized");
          return;
        }
        clearTimeout(authTimer);
        device = id;
        // A new session of the same phone replaces the old one (last writer wins).
        const old = this.sessions.get(id);
        if (old && old.ws !== ws) {
          this.rejectPending(id, "replaced by new session");
          old.ws.close(CloseCode.Replaced, "replaced by new session");
        }
        this.sessions.set(id, { ws, info: (frame as { device: DeviceInfo }).device ?? { suggestionLogging: false }, connectedSince: new Date() });
        this.lastSeenAt.set(id, new Date());
        // Lets the app show "connected" only once the token was accepted.
        ws.send(encodeFrame({ type: "event", event: "welcome", payload: { device: id } }));
        return;
      }

      this.lastSeenAt.set(device, new Date());
      this.onFrame(device, ws, frame);
    });

    ws.on("close", () => {
      clearTimeout(authTimer);
      if (device !== null && this.sessions.get(device)?.ws === ws) {
        this.sessions.delete(device);
        this.rejectPending(device, "device disconnected");
      }
    });

    ws.on("error", () => {
      /* the close handler does the cleanup */
    });
  }

  private onFrame(device: string, ws: WebSocket, frame: Frame): void {
    if (frame.type === "result") {
      const p = this.pending.get(frame.id);
      if (!p || p.device !== device) return;
      clearTimeout(p.timer);
      this.pending.delete(frame.id);
      if (frame.ok) p.resolve(frame.data);
      else p.reject(new Error(frame.error ?? "device reported failure"));
    } else if (frame.type === "event") {
      // The phone re-sends its device info when a device-side toggle changes.
      const s = this.sessions.get(device);
      if (s && frame.event === "device_info" && frame.payload && typeof frame.payload === "object") {
        s.info = { ...s.info, ...(frame.payload as Partial<DeviceInfo>) };
      }
      for (const h of this.eventHandlers) h(device, frame.event, frame.payload);
    } else if (frame.type === "req") {
      void this.answer(device, ws, frame.id, frame.op, frame.args ?? {});
    } else if (frame.type === "ping") {
      ws.send(encodeFrame({ type: "pong" }));
    }
  }

  private async answer(device: string, ws: WebSocket, id: string, op: string, args: Record<string, unknown>): Promise<void> {
    if (typeof id !== "string" || !id) return;
    let res: Frame;
    try {
      if (!this.requestHandler) throw new Error(`unsupported request '${op}'`);
      res = { type: "res", id, ok: true, data: await this.requestHandler(device, String(op), args) };
    } catch (err) {
      res = { type: "res", id, ok: false, error: err instanceof Error ? err.message : String(err) };
    }
    if (ws.readyState === WebSocket.OPEN) ws.send(encodeFrame(res));
  }
}
