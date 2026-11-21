import { execFile } from "node:child_process";

/**
 * Low-level adb helpers used by ADB sessions (`core/adbSession.ts`) and the
 * `own_helper_*` tools (start/stop the app's shell helper, `app_process`
 * running ShellMain from the installed APK).
 */

const PKG = "work.alberton.droidhand";
const MAIN = "work.alberton.droidhand.shell.ShellMain";
/** Bracket trick: the pattern does not match the pkill command line itself. */
const MAIN_PATTERN = "work.alberton.droidhand.shell.Shell[M]ain";

export interface AdbOutput {
  stdout: string;
  stderr: string;
  /** Process exit code (adb forwards the remote shell's exit code). */
  exitCode: number;
}

/** Run adb and resolve with stdout/stderr/exit code; rejects only if adb could not run or timed out. */
export function runFull(adb: string, args: string[], timeoutMs = 15000): Promise<AdbOutput> {
  return new Promise((resolve, reject) => {
    execFile(adb, args, { timeout: timeoutMs, maxBuffer: 4 * 1024 * 1024, env: { ...process.env, ADB_MDNS_AUTO_CONNECT: "0" } },
      (err, stdout, stderr) => {
        const e = err as (NodeJS.ErrnoException & { killed?: boolean; code?: number | string }) | null;
        if (e && (e.killed || typeof e.code !== "number")) {
          reject(new Error(e.killed ? `adb timed out after ${timeoutMs} ms` : (stderr || e.message).trim()));
          return;
        }
        resolve({ stdout, stderr, exitCode: e ? Number(e.code) : 0 });
      });
  });
}

/** Run adb and resolve with stdout; rejects on a non-zero exit. */
export async function run(adb: string, args: string[], timeoutMs = 15000): Promise<string> {
  const r = await runFull(adb, args, timeoutMs);
  if (r.exitCode !== 0) throw new Error((r.stderr || r.stdout).trim() || `adb exited with ${r.exitCode}`);
  return r.stdout;
}

/** (Re)start the shell helper detached on the phone at [serial]. */
export async function startShellHelper(adb: string, serial: string): Promise<void> {
  const apk = (await run(adb, ["-s", serial, "shell", "pm", "path", PKG])).split("\n")[0]?.replace("package:", "").trim();
  if (!apk) throw new Error("DroidHand app is not installed on the phone");
  await run(adb, ["-s", serial, "shell", `pkill -f ${MAIN_PATTERN}; true`]);
  // Detached so it outlives this adb shell (but not debugging being turned off).
  await run(adb, ["-s", serial, "shell",
    `CLASSPATH=${apk} setsid app_process /system/bin ${MAIN} </dev/null >/dev/null 2>&1 &`]);
}

/** Kill the shell helper on the phone (no-op if it is not running). */
export async function stopShellHelper(adb: string, serial: string): Promise<void> {
  await run(adb, ["-s", serial, "shell", `pkill -f ${MAIN_PATTERN}; true`]);
}
