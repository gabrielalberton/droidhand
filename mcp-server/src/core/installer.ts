import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { run, runFull } from "./adb.js";

/**
 * "Compatibility with banking apps": banks and Google's Play Integrity treat an accessibility
 * service installed from outside the Play Store as a threat, and one from the Play Store as
 * fine (tested on a Galaxy S24 with Nubank). Android records who installed an app; a package
 * installed with `adb install -i com.android.vending` is recorded as installed from the Play
 * Store. A normal update from the app resets that, so the phone asks the relay to put it back.
 * The relay reinstalls the APK already on the phone (same signature, data kept).
 */

const PKG = "work.alberton.droidhand";
export const PLAY_STORE = "com.android.vending";

/** `installerPackageName=` from `dumpsys package`, or null when none is recorded. */
export function parseInstaller(dump: string): string | null {
  const m = /^\s*installerPackageName=(\S+)/m.exec(dump);
  return m && m[1] !== "null" ? m[1]! : null;
}

/** The single base APK path from `pm path`; split installs are not supported. */
export function parseApkPath(out: string): string {
  const paths = out.split("\n").map((l) => l.trim()).filter((l) => l.startsWith("package:")).map((l) => l.slice(8));
  if (paths.length !== 1) throw new Error("split_apk: only a single-APK install can be re-installed this way");
  if (!/^\/[A-Za-z0-9_/=+.~-]+\.apk$/.test(paths[0]!)) throw new Error("bad_apk_path: the APK path on the phone could not be verified");
  return paths[0]!;
}

export interface InstallerResult {
  installer: string;
  reinstalled: boolean;
}

export async function installerOf(adb: string, serial: string): Promise<string | null> {
  return parseInstaller(await run(adb, ["-s", serial, "shell", "dumpsys", "package", PKG]));
}

/** Make the Play Store the recorded installer of DroidHand, reinstalling in place when it is not. */
export async function ensurePlayInstaller(adb: string, serial: string): Promise<InstallerResult> {
  const before = await installerOf(adb, serial);
  if (before === PLAY_STORE) return { installer: PLAY_STORE, reinstalled: false };
  const apk = parseApkPath(await run(adb, ["-s", serial, "shell", "pm", "path", PKG]));
  const dir = await mkdtemp(join(tmpdir(), "droidhand-reinstall-"));
  try {
    const local = join(dir, "base.apk");
    await run(adb, ["-s", serial, "pull", apk, local], 120_000);
    const out = await runFull(adb, ["-s", serial, "install", "-r", "-i", PLAY_STORE, local], 180_000);
    if (!/Success/.test(out.stdout)) throw new Error(`reinstall_failed: ${(out.stderr || out.stdout).trim().slice(0, 300)}`);
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
  // The app was restarted by the install; adbd keeps running, so the check works at once.
  const after = await installerOf(adb, serial).catch(() => null);
  if (after !== PLAY_STORE) throw new Error(`installer_not_set: Android recorded '${after ?? "none"}'`);
  return { installer: PLAY_STORE, reinstalled: true };
}
