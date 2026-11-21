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

/** Either side: liveness. */
export interface PingFrame {
  type: "ping" | "pong";
}

export type Frame = HelloFrame | CmdFrame | ResultFrame | EventFrame | PingFrame;

/** Close codes the relay uses on the phone socket. */
export const CloseCode = {
  /** Replaced by a newer session, or severed by panic_disconnect. */
  Replaced: 4000,
  AuthTimeout: 4001,
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
