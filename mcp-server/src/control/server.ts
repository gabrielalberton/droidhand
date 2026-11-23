import { WebSocketServer, WebSocket, type RawData } from "ws";
import type { Core } from "../core/core.js";
import { CloseCode } from "../protocol.js";
import { AUTH_TIMEOUT_MS } from "../relay/server.js";
import { asCall, parseObject, type ControlEvent, type ControlResult } from "./frames.js";

/**
 * Control channel (daemon side): the single relay daemon owns the phone
 * connections and exposes a loopback WebSocket that per-session MCP adapters
 * connect to. This keeps the phone-facing port owned by exactly one process
 * while any number of agent sessions (Claude Code, Codex, …) drive the phone
 * through their own adapter.
 */
export interface ControlServerHandle {
  close(): Promise<void>;
  /** The actual bound port (useful when port 0 was requested in tests). */
  address(): { port: number } | null;
}

/** Start the control server; resolves once it is listening. */
export function startControlServer(core: Core, host: string, port: number, token: string): Promise<ControlServerHandle> {
  return new Promise((resolve, reject) => {
    const wss = new WebSocketServer({ host, port });
    const clients = new Set<WebSocket>();

    // Fan device events out to every authenticated adapter.
    const unsubscribe = core.relay.onEvent((device, event, payload) => {
      const msg = JSON.stringify({ type: "event", device, event, payload } satisfies ControlEvent);
      for (const c of clients) if (c.readyState === WebSocket.OPEN) c.send(msg);
    });

    wss.on("error", reject);
    wss.on("connection", (ws) => {
      let authed = false;
      const authTimer = setTimeout(() => ws.close(CloseCode.AuthTimeout, "auth timeout"), AUTH_TIMEOUT_MS);

      ws.on("message", async (raw: RawData) => {
        const f = parseObject(raw.toString());
        if (!f) return;
        if (!authed) {
          if (f.type !== "hello" || f.token !== token || token === "") {
            ws.close(CloseCode.Unauthorized, "unauthorized");
            return;
          }
          clearTimeout(authTimer);
          authed = true;
          clients.add(ws);
          core.agents = clients.size;
          return;
        }
        const call = asCall(f);
        if (!call) return;
        // relay_invite: setup link for the terminal (droidhand-invite); not an agent tool.
        const res = call.tool === "relay_invite"
          ? (() => { try { return { ok: true, data: core.createInvite(call.args) }; } catch (err) { return { ok: false, error: err instanceof Error ? err.message : String(err) }; } })()
          : await core.callTool(call.tool, call.args);
        if (ws.readyState === WebSocket.OPEN) {
          ws.send(JSON.stringify({ type: "result", id: call.id, ...res } satisfies ControlResult));
        }
      });
      ws.on("close", () => {
        clearTimeout(authTimer);
        clients.delete(ws);
        core.agents = clients.size;
      });
      ws.on("error", () => {});
    });

    wss.on("listening", () =>
      resolve({
        close: () =>
          new Promise<void>((res) => {
            unsubscribe();
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
