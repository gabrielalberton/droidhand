import { run, runFull, type AdbOutput } from "./adb.js";
import { ADB_TCP_PORT } from "./adbSession.js";

/**
 * ADB pairing started from the phone (app → Permissions → Secure settings → Pair
 * from the app). The app reads the pairing code and port from Android's "Pair device
 * with pairing code" dialog and sends them; the host runs `adb pair`, connects over
 * wireless debugging and does the one-time setup that used to need a computer at
 * hand: grants the secure-settings (and dump) permission and arms adbd's TCP port
 * for ADB on demand. The app then turns wireless debugging off itself.
 */

const PKG = "work.alberton.droidhand";
/** Granted after pairing; only the first is required. */
const GRANTS = ["android.permission.WRITE_SECURE_SETTINGS", "android.permission.DUMP", "android.permission.POST_NOTIFICATIONS"];

export interface PairRequest {
  code: string;
  port: number;
  /** Addresses to try, in order (tailnet first, then Wi-Fi). */
  hosts: string[];
  /** Ports listening on the phone: one of them is wireless debugging's connect port. */
  ports: number[];
}

export interface PairResult {
  host: string;
  paired: true;
  /** Serial used for the setup, or null when wireless debugging's port was not reached. */
  connected: string | null;
  granted: string[];
  tcpip: boolean;
  note?: string;
}

const IPV4 = /^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/;
const isPort = (n: unknown): n is number => Number.isInteger(n) && (n as number) > 0 && (n as number) < 65536;

export function validatePair(args: Record<string, unknown>): PairRequest {
  const code = String(args.code ?? "").replace(/\s+/g, "");
  if (!/^\d{6}$/.test(code)) throw new Error("bad_code: the pairing code has 6 digits");
  const port = Number(args.port);
  if (!isPort(port)) throw new Error("bad_port: the pairing port is missing");
  const hosts = (Array.isArray(args.hosts) ? args.hosts : []).map(String)
    .filter((h) => IPV4.test(h) && h.split(".").every((o) => Number(o) <= 255));
  if (!hosts.length) throw new Error("no_address: the phone reported no Wi-Fi or tailnet address");
  const ports = (Array.isArray(args.ports) ? args.ports : []).map(Number).filter(isPort).filter((p) => p !== port).slice(0, 64);
  return { code, port, hosts: [...new Set(hosts)], ports: [...new Set(ports)] };
}

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

export interface PairTimings { pair: number; connect: number; retry: number }
const DEFAULT: PairTimings = { pair: 12_000, connect: 12_000, retry: 1000 };

export async function pairAndSetUp(adb: string, req: PairRequest, t: PairTimings = DEFAULT): Promise<PairResult> {
  let host: string | null = null;
  let lastErr = "";
  for (const h of req.hosts) {
    const out: AdbOutput | null = await runFull(adb, ["pair", `${h}:${req.port}`, req.code], t.pair).catch((e: Error) => {
      lastErr = e.message;
      return null;
    });
    const text = `${out?.stdout ?? ""}${out?.stderr ?? ""}`;
    if (/Successfully paired/i.test(text)) { host = h; break; }
    // Real adb says "Failed: Wrong password or connection was dropped." for a bad code.
    if (/wrong password|protocol fault/i.test(text)) {
      throw new Error("pair_failed: Android refused the code (wrong or expired); open the pairing dialog again");
    }
    if (text.trim()) lastErr = text.trim();
  }
  if (!host) throw new Error(`pair_unreachable: this computer could not reach the phone's pairing port (${lastErr || "no answer"}). ` +
    "Both must be on the same Wi-Fi or the same tailnet.");

  // The connect port: try the listening ports the phone reported until adbd answers.
  let serial: string | null = null;
  const until = Date.now() + t.connect;
  while (!serial && Date.now() < until) {
    for (const p of req.ports) {
      if (p === ADB_TCP_PORT) continue;
      const s = `${host}:${p}`;
      const c = await runFull(adb, ["connect", s], 5000).catch(() => null);
      if (!c || !/connected to/.test(c.stdout)) continue;
      const probe = await runFull(adb, ["-s", s, "shell", "echo", "dh-ok"], 5000).catch(() => null);
      if (probe?.stdout.includes("dh-ok")) { serial = s; break; }
      await run(adb, ["disconnect", s], 3000).catch(() => undefined);
    }
    if (!serial) await sleep(t.retry);
  }
  if (!serial) {
    return { host, paired: true, connected: null, granted: [], tcpip: false,
      note: "paired, but wireless debugging's port did not answer: the first ADB session will finish the setup" };
  }
  const granted: string[] = [];
  try {
    for (const g of GRANTS) {
      const r = await runFull(adb, ["-s", serial, "shell", "pm", "grant", PKG, g], 10_000).catch(() => null);
      if (r && r.exitCode === 0) granted.push(g);
    }
    const tcp = await runFull(adb, ["-s", serial, "tcpip", String(ADB_TCP_PORT)], 10_000).catch(() => null);
    return { host, paired: true, connected: serial, granted, tcpip: !!tcp && tcp.exitCode === 0 };
  } finally {
    await run(adb, ["disconnect", serial], 3000).catch(() => undefined);
  }
}
