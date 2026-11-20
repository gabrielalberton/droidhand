import { WebSocket, type RawData } from "ws";
import type { DeviceInfo } from "./protocol.js";

export interface MockPhoneOptions {
  url: string;
  token: string;
  device?: Partial<DeviceInfo>;
  /** Override or add command handlers. Return value becomes the result data. */
  handlers?: Record<string, (args: Record<string, unknown>) => unknown>;
}

export interface MockPhone {
  close(): Promise<void>;
  /** Suggestions the server forwarded to the device via store_suggestion. */
  storedSuggestions: unknown[];
}

/**
 * A stand-in for the Portal app: connects to the relay, authenticates, and
 * answers commands with canned data. Used by tests and for manual end-to-end runs
 * before the real Android app exists.
 */
export function startMockPhone(opts: MockPhoneOptions): Promise<MockPhone> {
  const device: DeviceInfo = {
    model: "MockPhone",
    androidSdk: 34,
    appVersion: "0.1.0-mock",
    suggestionLogging: false,
    ...opts.device,
  };
  const stored: unknown[] = [];

  const defaults: Record<string, (args: Record<string, unknown>) => unknown> = {
    screen_snapshot: () => ({ windows: [{ package: "com.example", nodes: [{ ref: "@1", role: "button", text: "OK" }] }] }),
    get_device_info: () => ({ model: device.model, androidSdk: device.androidSdk }),
    tap: (a) => ({ tapped: a }),
    type_text: (a) => ({ typed: a.text }),
    store_suggestion: (a) => {
      stored.push(a);
      return { stored: true };
    },
  };
  const handlers = { ...defaults, ...(opts.handlers ?? {}) };

  return new Promise((resolve, reject) => {
    const ws = new WebSocket(opts.url);
    ws.on("open", () => ws.send(JSON.stringify({ type: "hello", token: opts.token, device })));
    ws.on("error", reject);
    ws.on("message", (raw: RawData) => {
      let frame: { type?: string; id?: string; tool?: string; args?: Record<string, unknown> };
      try {
        frame = JSON.parse(raw.toString());
      } catch {
        return;
      }
      if (frame.type === "cmd" && frame.id && frame.tool) {
        const handler = handlers[frame.tool];
        try {
          const data = handler ? handler(frame.args ?? {}) : { echo: frame.tool, args: frame.args };
          ws.send(JSON.stringify({ type: "result", id: frame.id, ok: true, data }));
        } catch (err) {
          ws.send(JSON.stringify({ type: "result", id: frame.id, ok: false, error: String(err) }));
        }
      }
    });
    // Resolve shortly after hello is sent so the relay has registered the session.
    ws.on("open", () =>
      setTimeout(
        () =>
          resolve({
            storedSuggestions: stored,
            close: () =>
              new Promise<void>((res) => {
                ws.once("close", () => res());
                ws.close();
              }),
          }),
        50,
      ),
    );
  });
}
