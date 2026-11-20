/**
 * DroidHand wire protocol — the single contract shared by the relay (NUC) and
 * the Portal app (phone). The phone is always the WebSocket *client* and dials
 * out to the relay; the relay never connects to the phone.
 */

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

export function parseFrame(raw: string): Frame | null {
  try {
    const f = JSON.parse(raw) as Frame;
    if (f && typeof (f as { type?: unknown }).type === "string") return f;
    return null;
  } catch {
    return null;
  }
}
