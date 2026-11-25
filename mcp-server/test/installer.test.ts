import { test } from "node:test";
import assert from "node:assert/strict";
import { parseApkPath, parseInstaller } from "../src/core/installer.js";

test("parseInstaller reads the recorded installer", () => {
  assert.equal(parseInstaller("  Packages:\n    installerPackageName=com.android.vending\n    foo=1"), "com.android.vending");
  assert.equal(parseInstaller("    installerPackageName=com.google.android.packageinstaller\n"), "com.google.android.packageinstaller");
  assert.equal(parseInstaller("    installerPackageName=null\n"), null);
  assert.equal(parseInstaller("nothing here"), null);
});

test("parseApkPath accepts one base APK and refuses splits or odd paths", () => {
  assert.equal(parseApkPath("package:/data/app/~~abc==/work.alberton.droidhand-xyz==/base.apk\n"), "/data/app/~~abc==/work.alberton.droidhand-xyz==/base.apk");
  assert.throws(() => parseApkPath("package:/data/app/a/base.apk\npackage:/data/app/a/split_config.apk\n"), /split_apk/);
  assert.throws(() => parseApkPath("package:/data/app/a b/base.apk;rm -rf /\n"), /bad_apk_path/);
  assert.throws(() => parseApkPath(""), /split_apk/);
});
