/**
 * Control-channel frames between the relay daemon and the per-session MCP
 * adapters (loopback WebSocket, JSON text frames). The adapter must send `hello`
 * first; a bad token closes with 4003 "unauthorized", silence with 4001 "auth timeout".
 */

/** Adapter → daemon: authenticate. */
export interface ControlHello {
  type: "hello";
  token: string;
}

/** Adapter → daemon: run a tool. */
export interface ControlCall {
  type: "call";
  id: string;
  tool: string;
  args: Record<string, unknown>;
}

/** Daemon → adapter: result of a call, correlated by `id`. */
export interface ControlResult {
  type: "result";
  id: string;
  ok: boolean;
  data?: unknown;
  error?: string;
}

/** Daemon → adapter: a device event relayed from a phone. */
export interface ControlEvent {
  type: "event";
  /** Which phone sent it. */
  device: string;
  event: string;
  payload: unknown;
}

export type ControlFrame = ControlHello | ControlCall | ControlResult | ControlEvent;

/** Parse a JSON text frame into a plain object; null for invalid JSON or non-objects (null, arrays, scalars). */
export function parseObject(raw: string): Record<string, unknown> | null {
  let f: unknown;
  try {
    f = JSON.parse(raw);
  } catch {
    return null;
  }
  return typeof f === "object" && f !== null && !Array.isArray(f) ? (f as Record<string, unknown>) : null;
}

/** Narrow a parsed object to a well-formed `call` frame (args default to `{}`). */
export function asCall(f: Record<string, unknown>): ControlCall | null {
  if (f.type !== "call" || typeof f.id !== "string" || !f.id || typeof f.tool !== "string" || !f.tool) return null;
  const args = typeof f.args === "object" && f.args !== null && !Array.isArray(f.args) ? (f.args as Record<string, unknown>) : {};
  return { type: "call", id: f.id, tool: f.tool, args };
}

/** Narrow a parsed object to a `result` frame. */
export function asResult(f: Record<string, unknown>): ControlResult | null {
  if (f.type !== "result" || typeof f.id !== "string" || !f.id) return null;
  return {
    type: "result",
    id: f.id,
    ok: Boolean(f.ok),
    data: f.data,
    error: typeof f.error === "string" ? f.error : undefined,
  };
}
