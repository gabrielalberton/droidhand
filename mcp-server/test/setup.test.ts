import { test } from "node:test";
import assert from "node:assert/strict";
import { chmodSync, existsSync, mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { Core } from "../src/core/core.js";
import type { DeviceConfig } from "../src/config.js";
import { SetupLinks } from "../src/core/setupLinks.js";
import { pairAndSetUp, validatePair } from "../src/core/adbPair.js";
import { startMockPhone } from "../src/dev/mock-phone.js";
import { tmpConfig, waitFor } from "./helpers/env.js";

const dev = (id: string, token: string, role: "admin" | "device" = "device"): DeviceConfig => ({
  id, name: id.toUpperCase(), token, role, adb: { mode: "on-demand", host: "", serial: "", reverse: false },
});

test("setup links: random, expire, spent when the phone connects", () => {
  let now = 1000;
  const s = new SetupLinks(60_000, () => now);
  const a = s.create({ device: "x", name: "X", token: "t", relayUrl: "ws://h:1" });
  const b = s.create({ device: "y", name: "Y", token: "u", relayUrl: "ws://h:1" });
  assert.notEqual(a.code, b.code);
  assert.match(a.code, /^[A-Za-z0-9_-]{22}$/);
  assert.equal(s.get(a.code)?.token, "t");
  assert.equal(s.get("../etc/passwd"), null);
  s.spend("x");
  assert.equal(s.get(a.code), null);
  assert.equal(s.get(b.code)?.device, "y");
  now += 60_000;
  assert.equal(s.get(b.code), null);
});

test("setup page and JSON are served on the relay port; the link dies when the phone connects", async () => {
  const core = new Core(tmpConfig({ token: "control-token", devices: [dev("a", "tok-a", "admin")] }));
  await core.start();
  try {
    const port = core.relay.address()!.port;
    const inv = core.createInvite({ relayUrl: `ws://127.0.0.1:${port}` });
    assert.equal(inv.device, "a");
    assert.match(inv.appLink, /^droidhand:\/\/connect#relay=ws:\/\/127\.0\.0\.1:\d+&token=tok-a&name=A$/);
    const base = `http://127.0.0.1:${port}${inv.setupPath}`;
    const page = await fetch(base, { headers: { "accept-language": "pt-BR" } });
    assert.equal(page.status, 200);
    assert.equal(page.headers.get("cache-control"), "no-store");
    const html = await page.text();
    assert.match(html, /Abrir no DroidHand/);
    assert.match(html, /droidhand:\/\/connect#relay=/);
    assert.deepEqual(await (await fetch(`${base}.json`)).json(), { relay: `ws://127.0.0.1:${port}`, token: "tok-a", name: "A" });
    assert.equal((await fetch(`http://127.0.0.1:${port}/other`)).status, 404);

    const phone = await startMockPhone({ url: `ws://127.0.0.1:${port}`, token: "tok-a", handlers: {} });
    await waitFor(() => core.relay.isConnected("a"));
    assert.equal((await fetch(base)).status, 410);
    assert.equal((await fetch(`${base}.json`)).status, 410);
    await phone.close();
  } finally {
    await core.stop();
  }
});

test("invite: a new phone is added; bad input is refused", async () => {
  const dir = mkdtempSync(join(tmpdir(), "dh-inv-"));
  writeFileSync(join(dir, "token"), "a".repeat(64));
  const file = join(dir, "devices.json");
  writeFileSync(file, JSON.stringify({ devices: [{ id: "main", tokenFile: "token", role: "admin" }] }));
  const core = new Core(tmpConfig({ devices: [dev("main", "a".repeat(64), "admin")], devicesFile: file }));
  await core.start();
  try {
    assert.throws(() => core.createInvite({ relayUrl: "http://x" }), /bad_relay_url/);
    const inv = core.createInvite({ relayUrl: "ws://h:8787", id: "tab", name: "Tablet" });
    assert.equal(inv.device, "tab");
    assert.match(inv.token, /^[0-9a-f]{64}$/);
    assert.ok(core.devices.some((d) => d.id === "tab"));
    assert.throws(() => core.createInvite({ relayUrl: "ws://h:8787" }), /which_phone/);
    assert.equal(core.createInvite({ relayUrl: "ws://h:8787", existing: "main" }).device, "main");
  } finally {
    await core.stop();
  }
});

test("adb pair: validates what the phone sends", () => {
  assert.deepEqual(validatePair({ code: "123 456", port: 37000, hosts: ["100.64.0.9", "x", "192.168.1.9", "999.1.1.1"], ports: [37000, 41000, "z"] }),
    { code: "123456", port: 37000, hosts: ["100.64.0.9", "192.168.1.9"], ports: [41000] });
  assert.throws(() => validatePair({ code: "12345", port: 1, hosts: ["1.2.3.4"] }), /bad_code/);
  assert.throws(() => validatePair({ code: "123456", port: 0, hosts: ["1.2.3.4"] }), /bad_port/);
  assert.throws(() => validatePair({ code: "123456", port: 5, hosts: [] }), /no_address/);
});

/** Fake adb: pairing works only at 192.168.1.9:37000 with code 123456; 41000 is wireless debugging. */
function fakeAdb(): { adb: string; log: () => string[] } {
  const dir = mkdtempSync(join(tmpdir(), "fake-adb-pair-"));
  const adb = join(dir, "adb");
  writeFileSync(adb, `#!/bin/sh
D="${dir}"
echo "$*" >> "$D/log"
if [ "$1" = "-s" ]; then shift 2; fi
case "$1" in
  pair)
    case "$2" in
      192.168.1.9:37000) if [ "$3" = "123456" ]; then echo "Successfully paired to $2 [guid=adb-x]"; else echo "Failed: Wrong password or connection was dropped."; exit 1; fi ;;
      *) echo "Failed: Unable to start pairing client. error: connect: Connection refused"; exit 1 ;;
    esac ;;
  connect) case "$2" in *:41000) echo "connected to $2" ;; *) echo "failed to connect to '$2'"; exit 1 ;; esac ;;
  disconnect) echo "disconnected $2" ;;
  tcpip) echo "restarting in TCP mode port: $2" ;;
  shell) shift; if [ "$*" = "echo dh-ok" ]; then echo dh-ok; else echo "ok"; fi ;;
esac
`);
  chmodSync(adb, 0o755);
  return { adb, log: () => (existsSync(join(dir, "log")) ? readFileSync(join(dir, "log"), "utf8").trim().split("\n") : []) };
}

const fast = { pair: 2000, connect: 500, retry: 10 };

test("adb pair: falls back to the next address, grants and arms the TCP port", async () => {
  const { adb, log } = fakeAdb();
  const r = await pairAndSetUp(adb, { code: "123456", port: 37000, hosts: ["100.64.0.9", "192.168.1.9"], ports: [39000, 41000] }, fast);
  assert.equal(r.host, "192.168.1.9");
  assert.equal(r.connected, "192.168.1.9:41000");
  assert.ok(r.granted.includes("android.permission.WRITE_SECURE_SETTINGS"));
  assert.equal(r.tcpip, true);
  const l = log();
  assert.ok(l.includes("-s 192.168.1.9:41000 tcpip 5555"));
  assert.equal(l.at(-1), "disconnect 192.168.1.9:41000");
});

test("adb pair: a wrong code stops at once with a clear error", async () => {
  const { adb } = fakeAdb();
  await assert.rejects(pairAndSetUp(adb, { code: "000000", port: 37000, hosts: ["192.168.1.9", "100.64.0.9"], ports: [] }, fast), /pair_failed/);
  await assert.rejects(pairAndSetUp(adb, { code: "123456", port: 1, hosts: ["10.0.0.1"], ports: [] }, fast), /pair_unreachable/);
});
