import { test } from "node:test";
import assert from "node:assert/strict";
import { WebSocket } from "ws";
import { Core } from "../src/core/core.js";
import { startControlServer } from "../src/control/server.js";
import { ControlClient } from "../src/control/client.js";
import { asCall, asResult, parseObject } from "../src/control/frames.js";
import { startMockPhone } from "../src/dev/mock-phone.js";
import { tmpConfig, waitFor } from "./helpers/env.js";

test("control frame parsing", () => {
  for (const bad of ["x", "null", "[]", "3"]) assert.equal(parseObject(bad), null);
  assert.deepEqual(parseObject('{"type":"hello","token":"t"}'), { type: "hello", token: "t" });

  assert.deepEqual(asCall({ type: "call", id: "1", tool: "tap", args: { x: 1 } }), {
    type: "call", id: "1", tool: "tap", args: { x: 1 },
  });
  assert.deepEqual(asCall({ type: "call", id: "1", tool: "tap" })?.args, {});
  assert.deepEqual(asCall({ type: "call", id: "1", tool: "tap", args: [1] })?.args, {});
  assert.equal(asCall({ type: "call", id: "", tool: "tap" }), null);
  assert.equal(asCall({ type: "call", id: "1" }), null);
  assert.equal(asCall({ type: "result", id: "1", tool: "tap" }), null);

  assert.deepEqual(asResult({ type: "result", id: "9", ok: true, data: [1] }), {
    type: "result", id: "9", ok: true, data: [1], error: undefined,
  });
  assert.equal(asResult({ type: "result", id: "9", ok: 0, error: "boom" })?.ok, false);
  assert.equal(asResult({ type: "event", id: "9" }), null);
});

async function withDaemon(
  fn: (ctx: { core: Core; controlUrl: string; relayUrl: string; token: string }) => Promise<void>,
): Promise<void> {
  const cfg = tmpConfig({ token: "ctl-token" });
  const core = new Core(cfg);
  await core.start();
  const ctrl = await startControlServer(core, cfg.controlHost, cfg.controlPort, cfg.token);
  try {
    await fn({
      core,
      controlUrl: `ws://127.0.0.1:${ctrl.address()?.port}`,
      relayUrl: `ws://127.0.0.1:${core.relay.address()?.port}`,
      token: cfg.token,
    });
  } finally {
    await ctrl.close();
    await core.stop();
  }
}

test("control server ignores malformed frames and keeps serving", async () => {
  await withDaemon(async ({ controlUrl, token }) => {
    const ws = new WebSocket(controlUrl);
    await new Promise((r) => ws.once("open", r));
    const replies: Record<string, unknown>[] = [];
    ws.on("message", (raw) => replies.push(JSON.parse(raw.toString()) as Record<string, unknown>));
    ws.send(JSON.stringify({ type: "hello", token }));
    for (const junk of ["null", "[]", "not json", '{"type":"call"}', '{"type":"call","id":"x"}']) ws.send(junk);
    ws.send(JSON.stringify({ type: "call", id: "c1", tool: "no_such_tool", args: {} }));
    await waitFor(() => replies.length > 0);
    assert.deepEqual(replies, [{ type: "result", id: "c1", ok: false, error: "unknown tool 'no_such_tool'" }]);
    ws.close();
  });
});

test("control server closes unauthenticated adapters with 4003", async () => {
  await withDaemon(async ({ controlUrl }) => {
    const ws = new WebSocket(controlUrl);
    await new Promise((r) => ws.once("open", r));
    const code = new Promise<number>((r) => ws.once("close", (c) => r(c)));
    ws.send(JSON.stringify({ type: "call", id: "1", tool: "tap", args: {} }));
    assert.equal(await code, 4003);
  });
});

test("device events are fanned out to authenticated adapters", async () => {
  await withDaemon(async ({ controlUrl, relayUrl, token }) => {
    const ws = new WebSocket(controlUrl);
    await new Promise((r) => ws.once("open", r));
    const events: Record<string, unknown>[] = [];
    ws.on("message", (raw) => events.push(JSON.parse(raw.toString()) as Record<string, unknown>));
    ws.send(JSON.stringify({ type: "hello", token }));
    // Prove the hello was processed before the phone emits anything.
    const client = new ControlClient(controlUrl, token);
    const phone = await startMockPhone({ url: relayUrl, token });
    await client.call("get_device_info", {});
    phone.sendEvent("notification", { pkg: "com.example" });
    await waitFor(() => events.some((e) => e.event === "notification"));
    assert.deepEqual(events.find((e) => e.event === "notification"), {
      type: "event", event: "notification", payload: { pkg: "com.example" },
    });
    client.close();
    ws.close();
    await phone.close();
  });
});

test("ControlClient rejects pending calls when the daemon goes away", async () => {
  const cfg = tmpConfig();
  const core = new Core(cfg);
  await core.start();
  const ctrl = await startControlServer(core, cfg.controlHost, cfg.controlPort, cfg.token);
  const client = new ControlClient(`ws://127.0.0.1:${ctrl.address()?.port}`, cfg.token, 5000);
  // A phone that authenticates but never answers, so the call stays pending.
  const phone = new WebSocket(`ws://127.0.0.1:${core.relay.address()?.port}`);
  await new Promise((r) => phone.once("open", r));
  phone.send(JSON.stringify({ type: "hello", token: cfg.token, device: { suggestionLogging: false } }));
  await waitFor(() => core.relay.connected);
  try {
    await client.call("no_such_tool", {}); // connected and authenticated
    const pending = client.call("bg_status", {});
    await new Promise((r) => setTimeout(r, 30));
    await ctrl.close();
    await assert.rejects(pending, /control channel closed/);
  } finally {
    client.close();
    phone.close();
    await core.stop();
  }
});
