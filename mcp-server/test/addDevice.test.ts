import { test } from "node:test";
import assert from "node:assert/strict";
import { existsSync, mkdtempSync, readFileSync, statSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { parseDevices } from "../src/config.js";
import { validateNewDevice, writeNewDevice } from "../src/core/addDevice.js";

function setup(): string {
  const dir = mkdtempSync(join(tmpdir(), "dh-add-"));
  writeFileSync(join(dir, "token"), "a".repeat(64));
  writeFileSync(join(dir, "devices.json"), JSON.stringify({ devices: [{ id: "main", tokenFile: "token", role: "admin" }] }));
  return join(dir, "devices.json");
}

test("add_device: validates id and name", () => {
  assert.deepEqual(validateNewDevice({ id: " Tablet ", name: " Meu tablet " }, ["main"]), { id: "tablet", name: "Meu tablet", role: "device" });
  assert.equal(validateNewDevice({ id: "x", role: "admin" }, []).role, "admin");
  assert.equal(validateNewDevice({ id: "x" }, []).name, "x");
  assert.throws(() => validateNewDevice({ id: "a b" }, []), /invalid_id/);
  assert.throws(() => validateNewDevice({ id: "main" }, ["main"]), /id_taken/);
});

test("add_device: writes a 0600 token and a parseable devices file, keeps a backup", () => {
  const file = setup();
  const added = writeNewDevice(file, { id: "tablet", name: "Tablet", role: "device" });
  assert.match(added.token, /^[0-9a-f]{64}$/);
  const tok = join(file, "..", "devices", "tablet.token");
  assert.equal(readFileSync(tok, "utf8").trim(), added.token);
  assert.equal(statSync(tok).mode & 0o777, 0o600);
  assert.equal(statSync(file).mode & 0o777, 0o600);
  assert.ok(existsSync(file + ".bak"));
  const all = parseDevices(readFileSync(file, "utf8"), join(file, ".."));
  assert.deepEqual(all.map((d) => d.id), ["main", "tablet"]);
  assert.throws(() => writeNewDevice(file, { id: "tablet", name: "", role: "device" }), /id_taken/);
});

test("add_device: refuses without a devices file", () => {
  assert.throws(() => writeNewDevice(join(tmpdir(), "nope-dh", "devices.json"), { id: "x", name: "x", role: "device" }), /no_devices_file/);
});
