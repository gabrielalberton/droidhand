/**
 * DroidHand wire protocol — the single contract shared by the relay daemon (host)
 * and the Android app (phone). The phone is always the WebSocket *client* and
 * dials out to the relay; the relay never connects to the phone.
 *
 * Every message is one JSON text frame with a string `type`. The phone must send
 * `hello` first (within 5 s) or the relay closes with 4001 "auth timeout"; a bad
 * token closes with 4003 "unauthorized". On success the relay answers with an
 * `event` frame named `welcome`. Close code 4000 means the session was replaced
 * or severed by the operator.
 *
 * The relay may accept several phones, each with its own token (the token tells
 * the relay which phone it is). A phone may also ask the relay something with a
 * `req` frame; the relay answers with a `res` frame (older relays ignore it).
 *
 * Changing any of these shapes breaks the Android app.
 */

/** Risk tier of a tool, recorded in the audit log. */
export type Tier = "standard" | "privileged" | "sensitive";

/** Reported by the phone on connect. `suggestionLogging` is OFF by default. */
export interface DeviceInfo {
  model?: string;
  androidSdk?: number;
  appVersion?: string;
  /** Whether the on-device feature-suggestion log is enabled (default false). */
  suggestionLogging: boolean;
}

/** First frame the phone must send; authenticates the session. */
export interface HelloFrame {
  type: "hello";
  token: string;
  device: DeviceInfo;
}

/** Relay -> phone: run a tool. */
export interface CmdFrame {
  type: "cmd";
  id: string;
  tool: string;
  args: Record<string, unknown>;
}

/** Phone -> relay: result of a cmd, correlated by `id`. */
export interface ResultFrame {
  type: "result";
  id: string;
  ok: boolean;
  data?: unknown;
  error?: string;
}

/** Phone -> relay: an unsolicited event (notification, foreground change, …). */
export interface EventFrame {
  type: "event";
  event: string;
  payload: unknown;
}

/** Phone -> relay: a request to the relay itself (e.g. `relay_status`). */
export interface ReqFrame {
  type: "req";
  id: string;
  op: string;
  args?: Record<string, unknown>;
}

/** Relay -> phone: answer to a `req`, correlated by `id`. */
export interface ResFrame {
  type: "res";
  id: string;
  ok: boolean;
  data?: unknown;
  error?: string;
}

/** One phone as listed by `list_devices` and the `relay_status` request. */
export interface DeviceStatus {
  id: string;
  name: string;
  role: "admin" | "device";
  connected: boolean;
  /** ISO time the current session started (null when disconnected). */
  connectedSince: string | null;
  /** ISO time of the last frame from this phone, this relay run (null if never). */
  lastSeen: string | null;
  model?: string;
  androidSdk?: number;
  appVersion?: string;
  adb: { mode: "on-demand" | "always"; sessionOpen: boolean };
  /** What an agent must do differently on this phone (from model + SDK; see core/deviceRules.ts). */
  rules?: { id: string; rule: string; basis: string }[];
}

/** Answer to `relay_status` (admin phones only). */
export interface RelayStatus {
  version: string;
  startedAt: string;
  /** MCP adapters (agent sessions) connected to the control channel. */
  agents: number;
  /** Id of the phone that asked. */
  self: string;
  devices: DeviceStatus[];
}

/** Either side: liveness. */
export interface PingFrame {
  type: "ping" | "pong";
}

export type Frame = HelloFrame | CmdFrame | ResultFrame | EventFrame | ReqFrame | ResFrame | PingFrame;

/** Close codes the relay uses on the phone socket. */
export const CloseCode = {
  /** Replaced by a newer session, or severed by panic_disconnect. */
  Replaced: 4000,
  AuthTimeout: 4001,
  /** The phone stopped answering commands (e.g. frozen by the system): reconnect. */
  Unresponsive: 4002,
  Unauthorized: 4003,
} as const;

/** Parse one text frame. Returns null for invalid JSON or anything without a string `type`. */
export function parseFrame(raw: string): Frame | null {
  let f: unknown;
  try {
    f = JSON.parse(raw);
  } catch {
    return null;
  }
  if (typeof f === "object" && f !== null && typeof (f as { type?: unknown }).type === "string") return f as Frame;
  return null;
}

/** Serialize a frame for the wire. */
export function encodeFrame(frame: Frame): string {
  return JSON.stringify(frame);
}
