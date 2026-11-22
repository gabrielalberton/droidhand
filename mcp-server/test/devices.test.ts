import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { WebSocket, type RawData } from "ws";
import { Core } from "../src/core/core.js";
import { parseDevices, loadConfig, type DeviceConfig } from "../src/config.js";
import { startMockPhone } from "../src/dev/mock-phone.js";
import { tmpConfig, waitFor } from "./helpers/env.js";

const dev = (id: string, token: string, role: "admin" | "device" = "device", adb: Partial<DeviceConfig["adb"]> = {}): DeviceConfig => ({
  id, name: id.toUpperCase(), token, role, adb: { mode: "on-demand", host: "", serial: "", reverse: false, ...adb },
});

async function withCore(devices: DeviceConfig[], fn: (core: Core, url: string) => Promise<void>): Promise<void> {
  const core = new Core(tmpConfig({ token: "control-token", devices }));
  await core.start();
  try {
    await fn(core, `ws://127.0.0.1:${core.relay.address()!.port}`);
  } finally {
    await core.stop();
  }
}

test("devices file: token files, defaults and validation", () => {
  const dir = mkdtempSync(join(tmpdir(), "droidhand-dev-"));
  writeFileSync(join(dir, "a.token"), "tok-a\n");
  const devices = parseDevices(JSON.stringify({ devices: [
    { id: "s24", name: "Galaxy S24", tokenFile: "a.token", role: "admin" },
    { id: "s20", token: "tok-b", adb: { mode: "always", serial: "192.168.1.9:5555", reverse: true } },
  ] }), dir);
  assert.deepEqual(devices[0], { id: "s24", name: "Galaxy S24", token: "tok-a", role: "admin", adb: { mode: "on-demand", host: "", serial: "", reverse: false } });
  assert.deepEqual(devices[1], { id: "s20", name: "s20", token: "tok-b", role: "device", adb: { mode: "always", host: "", serial: "192.168.1.9:5555", reverse: true } });

  const bad = (devices: unknown[]) => () => parseDevices(JSON.stringify({ devices }), dir);
  assert.throws(bad([]), /non-empty/);
  assert.throws(bad([{ id: "Bad Id", token: "x" }]), /invalid id/);
  assert.throws(bad([{ id: "a", token: "x" }, { id: "a", token: "y" }]), /duplicate id/);
  assert.throws(bad([{ id: "a", token: "x" }, { id: "b", token: "x" }]), /reuses/);
  assert.throws(bad([{ id: "a" }]), /no token/);
  assert.throws(bad([{ id: "a", token: "x", adb: { mode: "always" } }]), /without a serial/);
});

test("loadConfig: no devices file means one phone with the shared token; the adapter skips devices", () => {
  const env = { DROIDHAND_TOKEN: "t", DROIDHAND_DEVICES_FILE: "/nonexistent/devices.json", DROIDHAND_ADB_HOST: "h" };
  assert.deepEqual(loadConfig(env).devices.map((d) => [d.id, d.token, d.adb.host]), [["phone", "t", "h"]]);
  assert.deepEqual(loadConfig(env, false).devices, []);
});

test("each phone authenticates with its own token and gets its own calls", async () => {
  await withCore([dev("a", "tok-a", "admin"), dev("b", "tok-b")], async (core, url) => {
    const a = await startMockPhone({ url, token: "tok-a", handlers: { tap: () => "from a" } });
    // With one phone connected, device may be left out.
    assert.deepEqual(await core.callTool("tap", { x: 1, y: 1 }), { ok: true, data: "from a" });
    const b = await startMockPhone({ url, token: "tok-b", handlers: { tap: (args) => ({ b: args }) } });
    await waitFor(() => core.relay.connectedDevices().length === 2);

    const ambiguous = await core.callTool("tap", { x: 1, y: 1 });
    assert.equal(ambiguous.ok, false);
    assert.match(ambiguous.error ?? "", /several phones .*pass device/);

    assert.deepEqual(await core.callTool("tap", { x: 1, y: 1, device: "a" }), { ok: true, data: "from a" });
    // The device argument never reaches the phone.
    assert.deepEqual(await core.callTool("tap", { x: 2, y: 3, device: "b" }), { ok: true, data: { b: { x: 2, y: 3 } } });
    assert.match((await core.callTool("tap", { device: "zz" })).error ?? "", /unknown device 'zz'/);

    const list = (await core.callTool("list_devices", {})).data as { devices: { id: string; connected: boolean; model?: string }[] };
    assert.deepEqual(list.devices.map((d) => [d.id, d.connected, d.model]), [["a", true, "MockPhone"], ["b", true, "MockPhone"]]);

    // panic on one phone leaves the other connected.
    await core.callTool("panic_disconnect", { device: "b" });
    assert.deepEqual(core.relay.connectedDevices(), ["a"]);
    await a.close(); // b was already closed by the panic
    void b;
  });
});

test("panic_disconnect without a device severs every phone", async () => {
  await withCore([dev("a", "tok-a"), dev("b", "tok-b")], async (core, url) => {
    await startMockPhone({ url, token: "tok-a" });
    await startMockPhone({ url, token: "tok-b" });
    await waitFor(() => core.relay.connectedDevices().length === 2);
    const res = await core.callTool("panic_disconnect", {});
    assert.deepEqual(res.data, { disconnected: ["a", "b"] });
    assert.deepEqual(core.relay.connectedDevices(), []);
  });
});

/** Send one `req` as a raw phone and return the `res`. */
async function request(url: string, token: string, op: string): Promise<Record<string, unknown>> {
  const ws = new WebSocket(url);
  await new Promise((r) => ws.once("open", r));
  const frames: Record<string, unknown>[] = [];
  ws.on("message", (raw: RawData) => frames.push(JSON.parse(raw.toString()) as Record<string, unknown>));
  ws.send(JSON.stringify({ type: "hello", token, device: { suggestionLogging: false, model: "Raw" } }));
  await waitFor(() => frames.some((f) => f.event === "welcome"));
  assert.deepEqual(frames[0]!.payload, { device: token === "tok-a" ? "a" : "b" });
  ws.send(JSON.stringify({ type: "req", id: "r1", op }));
  await waitFor(() => frames.some((f) => f.type === "res"));
  ws.close();
  return frames.find((f) => f.type === "res")!;
}

test("relay_status: admin phones see every phone, other phones are refused", async () => {
  await withCore([dev("a", "tok-a", "admin"), dev("b", "tok-b")], async (core, url) => {
    const ok = await request(url, "tok-a", "relay_status");
    assert.equal(ok.ok, true);
    const st = ok.data as { self: string; agents: number; devices: { id: string; connected: boolean; role: string; adb: unknown }[] };
    assert.equal(st.self, "a");
    assert.equal(st.agents, 0);
    assert.deepEqual(st.devices.map((d) => [d.id, d.role, d.connected]), [["a", "admin", true], ["b", "device", false]]);
    assert.deepEqual(st.devices[1]!.adb, { mode: "on-demand", sessionOpen: false });

    const refused = await request(url, "tok-b", "relay_status");
    assert.equal(refused.ok, false);
    assert.match(String(refused.error), /not_admin/);

    const unknown = await request(url, "tok-a", "nope");
    assert.match(String(unknown.error), /unsupported request/);
  });
});
