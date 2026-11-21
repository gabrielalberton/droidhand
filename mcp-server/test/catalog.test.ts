import { test } from "node:test";
import assert from "node:assert/strict";
import { z } from "zod";
import { CATALOG, CATALOG_AREAS, CATALOG_BY_NAME, DISPLAY_PARAM } from "../src/catalog.js";

/**
 * Tool names are a contract with the Android app. This list must only change
 * together with the app; update it deliberately.
 */
const EXPECTED_TOOLS = [
  "screen_snapshot", "screen_capture", "get_foreground_app", "list_windows", "get_notifications", "list_apps",
  "tap", "long_press", "swipe", "scroll", "type_text", "key_event", "global_action",
  "launch_app", "open_url", "go_home",
  "get_device_info", "get_setting", "set_setting",
  "get_lock_state", "unlock_device",
  "adb_session_begin", "adb_shell", "adb_install", "pm_grant", "adb_session_end",
  "bg_open", "bg_status", "bg_hide", "bg_show", "bg_close", "request_user_help", "own_helper_start", "own_helper_stop",
  "suggest_feature", "list_suggestions", "export_suggestions",
  "get_audit_log", "panic_disconnect",
];

const LOCAL_TOOLS = [
  "own_helper_start", "own_helper_stop", "suggest_feature", "list_suggestions", "export_suggestions",
  "get_audit_log", "panic_disconnect",
];

test("catalog has exactly the expected tools, in order", () => {
  assert.deepEqual(CATALOG.map((t) => t.name), EXPECTED_TOOLS);
});

test("tool names are unique, snake_case, and indexed by name", () => {
  const names = CATALOG.map((t) => t.name);
  assert.equal(new Set(names).size, names.length);
  for (const n of names) assert.match(n, /^[a-z][a-z0-9_]*$/);
  assert.equal(CATALOG_BY_NAME.size, CATALOG.length);
  for (const t of CATALOG) assert.equal(CATALOG_BY_NAME.get(t.name), t);
});

test("every tool has a description, a tier and a zod input shape", () => {
  for (const t of CATALOG) {
    assert.ok(t.description.trim().length > 10, `${t.name}: description`);
    assert.ok(["standard", "privileged", "sensitive"].includes(t.tier), `${t.name}: tier`);
    assert.equal(typeof t.confirm, "boolean", `${t.name}: confirm`);
    assert.equal(typeof t.input, "object", `${t.name}: input`);
    for (const [k, schema] of Object.entries(t.input)) {
      assert.ok(schema instanceof z.ZodType, `${t.name}.${k} is a zod schema`);
    }
  }
});

test("only the host-side tools are local", () => {
  assert.deepEqual(CATALOG.filter((t) => t.local).map((t) => t.name), LOCAL_TOOLS);
});

test("phone tools get the optional display param; local tools do not", () => {
  for (const t of CATALOG) {
    if (t.local) {
      assert.ok(!("display" in t.input), `${t.name} should not take display`);
    } else {
      assert.equal(t.input.display, DISPLAY_PARAM, `${t.name} should take display`);
      assert.ok(DISPLAY_PARAM.isOptional());
      assert.equal(DISPLAY_PARAM.safeParse(-1).success, false);
      assert.equal(DISPLAY_PARAM.safeParse(1.5).success, false);
      assert.equal(DISPLAY_PARAM.safeParse(2).success, true);
    }
  }
});

test("areas partition the catalog and have unique ids", () => {
  const ids = CATALOG_AREAS.map((a) => a.id);
  assert.equal(new Set(ids).size, ids.length);
  for (const a of CATALOG_AREAS) {
    assert.ok(a.title && a.summary, `${a.id}: title/summary`);
    assert.ok(a.tools.length > 0, `${a.id}: has tools`);
  }
  assert.deepEqual(CATALOG_AREAS.flatMap((a) => a.tools), [...CATALOG]);
});

test("privileged settings writes and ADB actions require on-device confirmation", () => {
  for (const name of ["set_setting", "adb_session_begin", "adb_shell", "adb_install", "pm_grant"]) {
    const t = CATALOG_BY_NAME.get(name);
    assert.ok(t);
    assert.equal(t.tier, "privileged");
    assert.equal(t.confirm, true, `${name} must require confirmation`);
  }
});
