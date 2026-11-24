import { test } from "node:test";
import assert from "node:assert/strict";
import { deviceRules } from "../src/core/deviceRules.js";
import { Core } from "../src/core/core.js";
import { startMockPhone } from "../src/dev/mock-phone.js";
import { tmpConfig } from "./helpers/env.js";

const ids = (model: string | undefined, sdk: number | undefined) => deviceRules(model, sdk).map((r) => r.id);

test("rules follow the tested phones: Android 13 Samsung vs SDK 37", () => {
  assert.deepEqual(ids("SM-G980F", 33), [
    "move_recreates_app", "native_window_not_accessible", "background_apps_freeform", "native_window_debug_text", "native_close_lands_on_top",
  ]);
  assert.deepEqual(ids("SM-S928B", 37), ["move_keeps_app", "native_window_debug_text", "native_close_lands_on_top"]);
  assert.match(deviceRules("SM-G980F", 33)[0]!.basis, /^tested on Galaxy S20/);
});

test("untested versions get the cautious rule and say so", () => {
  for (const sdk of [34, 36, undefined]) {
    const move = deviceRules("Pixel 8", sdk)[0]!;
    assert.equal(move.id, "move_recreates_app");
    assert.match(move.basis, /^untested/);
  }
  // Not Samsung: no One UI rules.
  assert.deepEqual(ids("Pixel 9", 37), ["move_keeps_app", "native_window_debug_text"]);
});

test("list_devices and get_device_info carry the phone's rules", async () => {
  const core = new Core(tmpConfig({ token: "t" }));
  await core.start();
  try {
    const url = `ws://127.0.0.1:${core.relay.address()!.port}`;
    const phone = await startMockPhone({ url, token: "t", device: { model: "SM-G980F", androidSdk: 33 } });
    const list = (await core.callTool("list_devices", {})).data as { devices: { rules?: { id: string }[] }[] };
    assert.equal(list.devices[0]!.rules?.[0]?.id, "move_recreates_app");
    const info = (await core.callTool("get_device_info", {})).data as { model: string; rules: { id: string }[] };
    assert.equal(info.model, "SM-G980F");
    assert.ok(info.rules.some((r) => r.id === "native_window_not_accessible"));
    await phone.close();
  } finally {
    await core.stop();
  }
});
