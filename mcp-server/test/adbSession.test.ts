import { test } from "node:test";
import assert from "node:assert/strict";
import { chmodSync, existsSync, mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { AdbSessions } from "../src/core/adbSession.js";

/**
 * A fake `adb`: port 5555 answers only once `tcpip 5555` ran (state file "armed");
 * port 40001 is the wireless-debugging port. Every call is logged.
 */
function fakeAdb(armed: boolean): { adb: string; log: () => string[] } {
  const dir = mkdtempSync(join(tmpdir(), "fake-adb-"));
  if (armed) writeFileSync(join(dir, "armed"), "");
  const adb = join(dir, "adb");
  writeFileSync(adb, `#!/bin/sh
D="${dir}"
echo "$*" >> "$D/log"
if [ "$1" = "-s" ]; then S="$2"; shift 2; fi
case "$1" in
  connect)
    case "$2" in
      *:5555) if [ -f "$D/armed" ]; then echo "connected to $2"; else echo "failed to connect to '$2': Connection refused"; exit 1; fi ;;
      *:40001) echo "connected to $2" ;;
      *) echo "failed to connect to '$2'"; exit 1 ;;
    esac ;;
  disconnect) echo "disconnected $2" ;;
  tcpip) touch "$D/armed"; echo "restarting in TCP mode port: $2" ;;
  shell) shift; if [ "$*" = "echo dh-ok" ]; then echo dh-ok; else echo "ran: $*"; exit 3; fi ;;
esac
`);
  chmodSync(adb, 0o755);
  return { adb, log: () => (existsSync(join(dir, "log")) ? readFileSync(join(dir, "log"), "utf8").trim().split("\n") : []) };
}

function fakePhone(opts: { onWifi: boolean }) {
  const calls: Array<[string, Record<string, unknown>]> = [];
  const dispatch = async (tool: string, args: Record<string, unknown>) => {
    calls.push([tool, args]);
    if (tool === "adb_enable" && args.wifi && !opts.onWifi) throw new Error("not_on_wifi");
    if (tool === "adb_local_ports") return { ports: [38000, 40001] };
    return { onWifi: opts.onWifi, addresses: { tailnet: "100.64.0.9" } };
  };
  return { calls, dispatch, tools: () => calls.map((c) => c[0]) };
}

const fast = { direct: 50, afterArm: 500, retry: 10 };

test("ADB session: connects to the armed TCP port, runs commands, turns debugging off at the end", async () => {
  const { adb, log } = fakeAdb(true);
  const phone = fakePhone({ onWifi: false });
  const s = new AdbSessions(adb, "", phone.dispatch, () => {}, fast);
  const info = await s.begin();
  assert.equal(info.serial, "100.64.0.9:5555");
  assert.equal(info.armed, false);
  assert.deepEqual(phone.calls[0], ["adb_enable", { ttlSeconds: 600 }]);
  const out = await s.shell("pm list users");
  assert.equal(out.exitCode, 3);
  assert.match(out.stdout, /ran: pm list users/);
  await s.end();
  assert.equal(s.active, false);
  assert.deepEqual(phone.tools().at(-1), "adb_disable");
  assert.ok(log().includes("disconnect 100.64.0.9:5555"));
  await assert.rejects(s.shell("id"), /no ADB session/);
});

test("ADB session: first use after a reboot arms port 5555 through wireless debugging", async () => {
  const { adb, log } = fakeAdb(false);
  const phone = fakePhone({ onWifi: true });
  const s = new AdbSessions(adb, "", phone.dispatch, () => {}, fast);
  const info = await s.begin();
  assert.equal(info.armed, true);
  assert.deepEqual(phone.tools(), ["adb_enable", "adb_enable", "adb_local_ports", "adb_wifi_off"]);
  assert.ok(log().includes("-s 100.64.0.9:40001 tcpip 5555"));
  await s.end();
});

test("ADB session: not armed and not on Wi-Fi fails cleanly with debugging turned off", async () => {
  const { adb } = fakeAdb(false);
  const phone = fakePhone({ onWifi: false });
  const s = new AdbSessions(adb, "", phone.dispatch, () => {}, fast);
  await assert.rejects(s.begin(), /adb_not_armed/);
  assert.equal(s.active, false);
  assert.equal(phone.tools().at(-1), "adb_disable");
});

test("ADB session: expires on its own and turns debugging off", async () => {
  const { adb } = fakeAdb(true);
  const phone = fakePhone({ onWifi: false });
  let ended = "";
  const s = new AdbSessions(adb, "", phone.dispatch, (why) => { ended = why; }, fast);
  await s.begin();
  (s as unknown as { arm(t: number): void }).arm(0.05);
  await new Promise((r) => setTimeout(r, 200));
  assert.equal(ended, "expired");
  assert.equal(s.active, false);
  assert.equal(phone.tools().at(-1), "adb_disable");
});

test("ADB session: agent activity renews the deadline on the phone, at most once a minute", async () => {
  const { adb } = fakeAdb(true);
  const phone = fakePhone({ onWifi: false });
  const s = new AdbSessions(adb, "", phone.dispatch, () => {}, fast);
  await s.begin();
  s.touch(); s.touch();
  assert.equal(phone.tools().filter((t) => t === "adb_enable").length, 1, "no renew right after begin");
  (s as unknown as { renewedAt: number }).renewedAt = Date.now() - 61_000;
  s.touch(); s.touch();
  await new Promise((r) => setTimeout(r, 20));
  assert.equal(phone.tools().filter((t) => t === "adb_enable").length, 2);
  await s.end();
  s.touch();
  assert.equal(phone.tools().filter((t) => t === "adb_enable").length, 2, "no renew after the end");
});

test("ADB session: stopping the relay without a session leaves the user's debugging alone", async () => {
  const { adb } = fakeAdb(true);
  const phone = fakePhone({ onWifi: false });
  const s = new AdbSessions(adb, "", phone.dispatch, () => {}, fast);
  await s.end("relay_stopped");
  assert.deepEqual(phone.tools(), []);
  await s.end("agent", true);
  assert.deepEqual(phone.tools(), ["adb_disable"]);
});
