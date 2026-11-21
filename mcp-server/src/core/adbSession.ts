import { run, runFull, type AdbOutput } from "./adb.js";

/**
 * ADB on demand. Debugging stays OFF on the phone; a session turns USB debugging
 * on for a limited time and connects to adbd over TCP (default port 5555) on the
 * phone's tailnet (or LAN) address, which works on mobile data too.
 *
 * Android forgets the TCP port at every reboot. The first session after a reboot
 * "arms" it: the phone (on any Wi-Fi) turns wireless debugging on, the host finds
 * that port, runs `adb tcpip 5555` and turns wireless debugging off again. This
 * needs the host to have been paired once with the phone (`adb pair`).
 *
 * The agent ends the session itself when done (adb_session_end → debugging off at
 * once). As a fallback the session closes after IDLE_S without any agent tool
 * call: every call renews it (`touch`). The phone enforces the same deadline on
 * its own (AdbSession.kt), so debugging goes off even if the relay or the
 * connection to the phone is lost.
 */

export const ADB_TCP_PORT = 5555;
/** Fallback: close the session this long after the agent's last tool call. */
export const IDLE_S = 600;
/** Renew the phone-side deadline at most this often. */
const RENEW_EVERY_MS = 60_000;

type Dispatch = (tool: string, args: Record<string, unknown>) => Promise<unknown>;

interface PhoneAdbStatus {
  onWifi?: boolean;
  addresses?: { tailnet?: string; lan?: string };
}

export interface SessionInfo {
  serial: string;
  expiresAt: string;
  /** True when this session had to arm the TCP port (first use after a reboot). */
  armed: boolean;
}

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

/** Waits, in ms (overridable for tests). */
export interface AdbTimings {
  /** How long to retry the TCP port before arming it. */
  direct: number;
  /** How long to retry the TCP port after arming. */
  afterArm: number;
  /** Pause between retries, and before asking the phone for its open ports. */
  retry: number;
}
const DEFAULT_TIMINGS: AdbTimings = { direct: 10_000, afterArm: 15_000, retry: 1000 };

export class AdbSessions {
  private serial: string | null = null;
  private expiresAt = 0;
  private timer: NodeJS.Timeout | undefined;
  private renewedAt = 0;

  constructor(
    private readonly adb: string,
    /** Fixed host for the phone (DROIDHAND_ADB_HOST); empty = the address the phone reports. */
    private readonly hostOverride: string,
    private readonly dispatch: Dispatch,
    private readonly onEnd: (why: string) => void = () => {},
    private readonly t: AdbTimings = DEFAULT_TIMINGS,
  ) {}

  get active(): boolean {
    return this.serial !== null && Date.now() < this.expiresAt;
  }

  get current(): SessionInfo | null {
    return this.active ? { serial: this.serial!, expiresAt: new Date(this.expiresAt).toISOString(), armed: false } : null;
  }

  /** Open (or renew) a session; resolves once `adb -s <serial> shell` works. */
  async begin(): Promise<SessionInfo> {
    const ttl = IDLE_S;
    const st = (await this.dispatch("adb_enable", { ttlSeconds: ttl })) as PhoneAdbStatus;
    if (this.active) {
      this.arm(ttl);
      return { serial: this.serial!, expiresAt: new Date(this.expiresAt).toISOString(), armed: false };
    }
    try {
      const host = this.hostOverride || st.addresses?.tailnet || st.addresses?.lan;
      if (!host) throw new Error("the phone reported no tailnet or Wi-Fi address (set DROIDHAND_ADB_HOST)");
      const serial = `${host}:${ADB_TCP_PORT}`;
      let armed = false;
      if (!(await this.connect(serial, this.t.direct))) {
        await this.armTcpPort(host, ttl);
        armed = true;
        if (!(await this.connect(serial, this.t.afterArm))) throw new Error(`adbd did not come up on ${serial} after arming`);
      }
      this.serial = serial;
      this.arm(ttl);
      return { serial, expiresAt: new Date(this.expiresAt).toISOString(), armed };
    } catch (err) {
      await this.end("begin_failed", true);
      throw err;
    }
  }

  /** The agent is still active: push the idle deadline back (here and, at most once a minute, on the phone). */
  touch(): void {
    if (!this.active) return;
    this.arm(IDLE_S);
    if (Date.now() - this.renewedAt < RENEW_EVERY_MS) return;
    this.renewedAt = Date.now();
    void this.dispatch("adb_enable", { ttlSeconds: IDLE_S }).catch(() => undefined);
  }

  /** Serial of the open session, reconnecting first if adb lost it (e.g. "device offline"). */
  async ready(): Promise<string> {
    if (!this.active) throw new Error("no ADB session: call adb_session_begin first");
    const serial = this.serial!;
    const state = await runFull(this.adb, ["-s", serial, "get-state"], 5000).catch(() => null);
    if (state?.stdout.trim() === "device") return serial;
    await run(this.adb, ["disconnect", serial], 3000).catch(() => undefined);
    if (!(await this.connect(serial, this.t.afterArm))) throw new Error(`lost the ADB connection to ${serial}`);
    return serial;
  }

  /** Run a shell command in the open session. */
  async shell(command: string, timeoutMs = 30_000): Promise<AdbOutput> {
    const serial = await this.ready();
    return runFull(this.adb, ["-s", serial, "shell", command], timeoutMs);
  }

  /**
   * Close the session and turn debugging off on the phone. Without [force] it only
   * acts on a session this relay opened, so e.g. restarting the relay never turns
   * off debugging the user enabled by hand. Idempotent; never throws.
   */
  async end(why = "session_end", force = false): Promise<void> {
    if (this.timer) clearTimeout(this.timer);
    this.timer = undefined;
    const serial = this.serial;
    this.serial = null;
    this.expiresAt = 0;
    this.renewedAt = 0;
    if (!serial && !force) return;
    if (serial) await run(this.adb, ["disconnect", serial], 5000).catch(() => undefined);
    await this.dispatch("adb_disable", { why }).catch(() => undefined);
    this.onEnd(why);
  }

  private arm(ttl: number): void {
    if (ttl === IDLE_S && this.renewedAt === 0) this.renewedAt = Date.now();
    this.expiresAt = Date.now() + ttl * 1000;
    if (this.timer) clearTimeout(this.timer);
    this.timer = setTimeout(() => void this.end("expired"), ttl * 1000);
    this.timer.unref?.();
  }

  /** `adb connect` + a trivial shell command, retried until [totalMs]. */
  private async connect(serial: string, totalMs: number): Promise<boolean> {
    const until = Date.now() + totalMs;
    while (Date.now() < until) {
      const out = await runFull(this.adb, ["connect", serial], 6000).catch(() => null);
      if (out && /connected to/.test(out.stdout)) {
        const probe = await runFull(this.adb, ["-s", serial, "shell", "echo", "dh-ok"], 6000).catch(() => null);
        if (probe?.stdout.includes("dh-ok")) return true;
        const why = `${probe?.stderr ?? ""}${probe?.stdout ?? ""}`;
        if (/unauthorized/.test(why)) throw new Error(`this computer is not authorized on the phone (${serial}): accept the 'Allow debugging' prompt once`);
        await run(this.adb, ["disconnect", serial], 3000).catch(() => undefined);
      }
      await sleep(this.t.retry);
    }
    return false;
  }

  /** First session after a reboot: open adbd's TCP port through wireless debugging. */
  private async armTcpPort(host: string, ttl: number): Promise<void> {
    try {
      await this.dispatch("adb_enable", { ttlSeconds: ttl, wifi: true });
    } catch (err) {
      throw new Error("adb_not_armed: after a reboot ADB must be armed once while the phone is on a Wi-Fi network " +
        `(any Wi-Fi). ${err instanceof Error ? err.message : String(err)}`);
    }
    try {
      let tls: string | null = null;
      for (let attempt = 0; attempt < 3 && !tls; attempt++) {
        await sleep(this.t.retry * 2);
        const { ports } = (await this.dispatch("adb_local_ports", {})) as { ports: number[] };
        for (const p of ports ?? []) {
          if (p === ADB_TCP_PORT) continue;
          if (await this.connect(`${host}:${p}`, this.t.retry)) { tls = `${host}:${p}`; break; }
        }
      }
      if (!tls) throw new Error("could not reach wireless debugging; pair this computer once (Developer options → Wireless debugging → Pair device, then `adb pair <ip:port>`)");
      await runFull(this.adb, ["-s", tls, "tcpip", String(ADB_TCP_PORT)], 10_000);
      await run(this.adb, ["disconnect", tls], 3000).catch(() => undefined);
    } finally {
      await this.dispatch("adb_wifi_off", {}).catch(() => undefined);
    }
  }
}
