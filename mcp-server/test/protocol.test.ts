import { test } from "node:test";
import assert from "node:assert/strict";
import { WebSocket, type RawData } from "ws";
import { CloseCode, encodeFrame, parseFrame } from "../src/protocol.js";
import { Relay } from "../src/relay/server.js";

test("parseFrame accepts objects with a string type and rejects everything else", () => {
  assert.deepEqual(parseFrame('{"type":"ping"}'), { type: "ping" });
  assert.deepEqual(parseFrame('{"type":"result","id":"1","ok":true,"data":{"a":1}}'), {
    type: "result", id: "1", ok: true, data: { a: 1 },
  });
  for (const bad of ["", "not json", "null", "42", '"ping"', "[]", "{}", '{"type":1}', '{"kind":"cmd"}']) {
    assert.equal(parseFrame(bad), null, `should reject ${JSON.stringify(bad)}`);
  }
});

test("encodeFrame produces the exact wire JSON", () => {
  assert.equal(
    encodeFrame({ type: "cmd", id: "abc", tool: "tap", args: { x: 1, y: 2 } }),
    '{"type":"cmd","id":"abc","tool":"tap","args":{"x":1,"y":2}}',
  );
  assert.equal(encodeFrame({ type: "pong" }), '{"type":"pong"}');
});

/** A raw phone socket: records frames and the close code. */
class RawPhone {
  readonly frames: Record<string, unknown>[] = [];
  closeCode: number | null = null;
  readonly ws: WebSocket;
  readonly opened: Promise<void>;
  readonly closed: Promise<number>;

  constructor(url: string) {
    this.ws = new WebSocket(url);
    this.opened = new Promise((res, rej) => {
      this.ws.once("open", () => res());
      this.ws.once("error", rej);
    });
    this.closed = new Promise((res) => this.ws.once("close", (code) => res((this.closeCode = code))));
    this.ws.on("message", (raw: RawData) => this.frames.push(JSON.parse(raw.toString()) as Record<string, unknown>));
  }

  send(obj: unknown): void {
    this.ws.send(typeof obj === "string" ? obj : JSON.stringify(obj));
  }

  async next(pred: (f: Record<string, unknown>) => boolean, timeoutMs = 1000): Promise<Record<string, unknown>> {
    const start = Date.now();
    for (;;) {
      const hit = this.frames.find(pred);
      if (hit) return hit;
      if (Date.now() - start > timeoutMs) throw new Error("frame not received");
      await new Promise((r) => setTimeout(r, 10));
    }
  }
}

async function withRelay(fn: (relay: Relay, url: string) => Promise<void>, token = "tok"): Promise<void> {
  const relay = new Relay("127.0.0.1", 0, token, 2000);
  await relay.start();
  try {
    await fn(relay, `ws://127.0.0.1:${relay.address()?.port}`);
  } finally {
    await relay.stop();
  }
}

const hello = (token: string) => ({ type: "hello", token, device: { suggestionLogging: false, model: "Raw" } });

test("relay: valid hello gets a welcome event and registers the device", async () => {
  await withRelay(async (relay, url) => {
    const p = new RawPhone(url);
    await p.opened;
    p.send("garbage before hello is ignored");
    p.send(hello("tok"));
    assert.deepEqual(await p.next((f) => f.type === "event"), { type: "event", event: "welcome", payload: null });
    assert.equal(relay.connected, true);
    assert.equal(relay.deviceInfo?.model, "Raw");
    p.ws.close();
  });
});

test("relay: wrong token, non-hello first frame, or empty server token close with 4003", async () => {
  await withRelay(async (_relay, url) => {
    const wrong = new RawPhone(url);
    await wrong.opened;
    wrong.send(hello("nope"));
    assert.equal(await wrong.closed, CloseCode.Unauthorized);

    const early = new RawPhone(url);
    await early.opened;
    early.send({ type: "ping" });
    assert.equal(await early.closed, CloseCode.Unauthorized);
  });
  await withRelay(async (_relay, url) => {
    const p = new RawPhone(url);
    await p.opened;
    p.send(hello(""));
    assert.equal(await p.closed, CloseCode.Unauthorized);
  }, "");
});

test("relay: cmd/result correlation, failures, ping/pong and device_info events", async () => {
  await withRelay(async (relay, url) => {
    const p = new RawPhone(url);
    await p.opened;
    p.send(hello("tok"));
    await p.next((f) => f.event === "welcome");

    // Success: result data resolves the dispatch.
    const ok = relay.dispatch("tap", { x: 3 });
    const cmd = await p.next((f) => f.type === "cmd" && f.tool === "tap");
    assert.deepEqual(cmd.args, { x: 3 });
    p.send({ type: "result", id: "unknown-id", ok: true, data: "ignored" });
    p.send({ type: "result", id: cmd.id, ok: true, data: { done: 1 } });
    assert.deepEqual(await ok, { done: 1 });

    // Failure: the phone's error string becomes the rejection message.
    const bad = relay.dispatch("swipe", {});
    const cmd2 = await p.next((f) => f.tool === "swipe");
    p.send({ type: "result", id: cmd2.id, ok: false, error: "awaiting_user" });
    await assert.rejects(bad, /^Error: awaiting_user$/);

    // Failure without an error string.
    const bad2 = relay.dispatch("scroll", {});
    const cmd3 = await p.next((f) => f.tool === "scroll");
    p.send({ type: "result", id: cmd3.id, ok: false });
    await assert.rejects(bad2, /device reported failure/);

    p.send({ type: "ping" });
    await p.next((f) => f.type === "pong");

    const events: string[] = [];
    const off = relay.onEvent((e) => events.push(e));
    p.send({ type: "event", event: "device_info", payload: { suggestionLogging: true } });
    await p.next(() => relay.deviceInfo?.suggestionLogging === true).catch(() => undefined);
    assert.equal(relay.deviceInfo?.suggestionLogging, true);
    assert.equal(relay.deviceInfo?.model, "Raw", "device_info merges into the hello info");
    assert.deepEqual(events, ["device_info"]);
    off();
    p.send({ type: "event", event: "later", payload: null });
    await new Promise((r) => setTimeout(r, 50));
    assert.deepEqual(events, ["device_info"], "unsubscribed handlers are not called");
    p.ws.close();
  });
});

test("relay: a new session replaces the old one (4000); disconnectPhone severs it", async () => {
  await withRelay(async (relay, url) => {
    const first = new RawPhone(url);
    await first.opened;
    first.send(hello("tok"));
    await first.next((f) => f.event === "welcome");

    const second = new RawPhone(url);
    await second.opened;
    second.send(hello("tok"));
    assert.equal(await first.closed, CloseCode.Replaced);
    await second.next((f) => f.event === "welcome");
    assert.equal(relay.connected, true);

    relay.disconnectPhone();
    assert.equal(await second.closed, CloseCode.Replaced);
    assert.equal(relay.connected, false);
    await assert.rejects(relay.dispatch("tap", {}), /no device connected/);
  });
});
