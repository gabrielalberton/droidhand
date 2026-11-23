import { test } from "node:test";
import assert from "node:assert/strict";
import { lockoutReason } from "../src/core/lockout.js";

test("lockout guard: refuses commands that cut the agent off for good", () => {
  for (const c of [
    "am force-stop work.alberton.droidhand",
    "pm disable-user --user 0 work.alberton.droidhand",
    "pm uninstall work.alberton.droidhand",
    "pm clear work.alberton.droidhand",
    "pm revoke work.alberton.droidhand android.permission.WRITE_SECURE_SETTINGS",
    "appops set work.alberton.droidhand RUN_ANY_IN_BACKGROUND ignore",
    "am force-stop com.tailscale.ipn",
    "settings put global adb_enabled 0",
    "settings put secure enabled_accessibility_services com.other/.Svc",
    "cmd connectivity airplane-mode enable",
    "svc wifi disable",
    "reboot",
    "sync; reboot",
    "su -c 'reboot'",
    "svc power shutdown",
  ]) assert.ok(lockoutReason(c), c);
});

test("lockout guard: leaves ordinary commands alone", () => {
  for (const c of [
    "dumpsys package work.alberton.droidhand | grep -i disable",
    "pm grant work.alberton.droidhand android.permission.WRITE_SECURE_SETTINGS",
    "am start -n work.alberton.droidhand/.ui.MainActivity",
    "am force-stop com.android.chrome",
    "settings get global adb_enabled",
    "settings put global stay_on_while_plugged_in 7",
    "getprop ro.build.version.release",
    "logcat -d | grep reboot",
    "cmd deviceidle whitelist +work.alberton.droidhand",
  ]) assert.equal(lockoutReason(c), null, c);
});
