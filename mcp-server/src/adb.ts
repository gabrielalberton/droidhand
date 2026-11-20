import { execFile } from "node:child_process";

const PKG = "work.alberton.droidhand";
const MAIN = "work.alberton.droidhand.shell.ShellMain";
/** Bracket trick: the pattern does not match the pkill command line itself. */
const MAIN_PATTERN = "work.alberton.droidhand.shell.Shell[M]ain";

function run(adb: string, args: string[], timeoutMs = 15000): Promise<string> {
  return new Promise((resolve, reject) => {
    execFile(adb, args, { timeout: timeoutMs, env: { ...process.env, ADB_MDNS_AUTO_CONNECT: "0" } }, (err, stdout, stderr) => {
      if (err) reject(new Error((stderr || err.message).trim()));
      else resolve(stdout);
    });
  });
}

/** Serial of a reachable phone: the configured one, an attached device, or one advertised over mDNS. */
async function pickSerial(adb: string, configured: string): Promise<string> {
  if (configured) return configured;
  const listed = (await run(adb, ["devices"])).split("\n").slice(1)
    .map((l) => l.trim().split(/\s+/)).filter((p) => p[1] === "device").map((p) => p[0] ?? "");
  if (listed[0]) return listed[0];
  const mdns = await run(adb, ["mdns", "services"]).catch(() => "");
  const hit = mdns.match(/adb-tls-connect\S*\s+(\d+\.\d+\.\d+\.\d+:\d+)/);
  if (hit?.[1]) {
    await run(adb, ["connect", hit[1]]);
    return hit[1];
  }
  throw new Error("no phone reachable over adb: turn on wireless debugging (set_setting global/adb_wifi_enabled=1) and make sure the NUC is paired");
}

export async function startShellHelper(adb: string, configuredSerial: string): Promise<{ serial: string }> {
  const serial = await pickSerial(adb, configuredSerial);
  const apk = (await run(adb, ["-s", serial, "shell", "pm", "path", PKG])).split("\n")[0]?.replace("package:", "").trim();
  if (!apk) throw new Error("DroidHand app is not installed on the phone");
  await run(adb, ["-s", serial, "shell", `pkill -f ${MAIN_PATTERN}; true`]);
  // Detached so it outlives this adb shell (but not USB debugging being turned off).
  await run(adb, ["-s", serial, "shell",
    `CLASSPATH=${apk} setsid app_process /system/bin ${MAIN} </dev/null >/dev/null 2>&1 &`]);
  return { serial };
}

export async function stopShellHelper(adb: string, configuredSerial: string): Promise<void> {
  const serial = await pickSerial(adb, configuredSerial);
  await run(adb, ["-s", serial, "shell", `pkill -f ${MAIN_PATTERN}; true`]);
}
