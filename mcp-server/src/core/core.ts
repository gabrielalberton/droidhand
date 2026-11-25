import { legacyDevice, type Config, type DeviceConfig } from "../config.js";
import { lockoutError, lockoutReason } from "./lockout.js";
import { validateNewDevice, writeNewDevice } from "./addDevice.js";
import { Relay } from "../relay/server.js";
import { CATALOG_BY_NAME } from "../catalog.js";
import type { DeviceStatus, RelayStatus } from "../protocol.js";
import { VERSION } from "../version.js";
import { Audit, redact } from "./audit.js";
import { SuggestionStore, isSuggestionCategory } from "./suggestions.js";
import { run, runPinUnlock, startShellHelper, stopShellHelper } from "./adb.js";
import { AdbSessions } from "./adbSession.js";
import { ensurePlayInstaller, type InstallerResult } from "./installer.js";
import { pairAndSetUp, validatePair, type PairResult } from "./adbPair.js";
import { SetupLinks } from "./setupLinks.js";
import { appLink, pickLang, renderGonePage, renderSetupPage } from "../relay/setupPage.js";
import type { IncomingMessage, ServerResponse } from "node:http";
import { deviceRules } from "./deviceRules.js";

/** Outcome of one tool call, as returned to the MCP adapter over the control channel. */
export interface ToolResult {
  ok: boolean;
  data?: unknown;
  error?: string;
}

type ToolArgs = Record<string, unknown>;

/** How many times / how often own_helper_start polls bg_status for the helper to show up. */
const HELPER_POLL_ATTEMPTS = 20;
const HELPER_POLL_INTERVAL_MS = 250;
/** How often the relay re-establishes `adb reverse` for disconnected "always" phones. */
const REVERSE_EVERY_MS = 20_000;

/** Tools that never act on one phone (no `device` needed). */
const HOST_TOOLS = new Set(["list_devices", "get_audit_log", "list_suggestions", "export_suggestions"]);

function errorMessage(err: unknown): string {
  return err instanceof Error ? err.message : String(err);
}

function ownHelperAvailable(status: unknown): boolean {
  return typeof status === "object" && status !== null && (status as { ownAvailable?: unknown }).ownAvailable === true;
}

/** Per-phone state on the host. */
interface Phone {
  readonly cfg: DeviceConfig;
  readonly adb: AdbSessions;
  /** True while the open ADB session exists only for the own window (closed with it). */
  adbForWindow: boolean;
  unlockInProgress?: boolean;
}

/**
 * Core wires the relay, audit log and suggestion store together and routes each
 * tool call either to a local handler (tools with `local: true` in the catalog)
 * or out to one phone. Each phone has its own settings (token, adb mode) and its
 * own ADB session; a call names its phone with the `device` argument, which may be
 * left out when only one phone is configured or connected.
 */
export class Core {
  readonly relay: Relay;
  readonly audit: Audit;
  readonly suggestions: SuggestionStore;
  readonly setup = new SetupLinks();
  readonly startedAt = new Date();
  /** MCP adapters (agent sessions) currently connected to the control channel. */
  agents = 0;
  private readonly cfg: Config;
  private readonly phones = new Map<string, Phone>();
  private reverseTimer: NodeJS.Timeout | undefined;
  /** Phones with an ADB pairing in progress (one at a time each). */
  private readonly pairing = new Set<string>();
  private readonly fixingInstaller = new Set<string>();

  constructor(cfg: Config) {
    this.cfg = cfg;
    const devices = cfg.devices?.length ? cfg.devices : [legacyDevice(cfg.token, cfg.adbHost, cfg.adbSerial)];
    this.relay = new Relay(cfg.relayHost, cfg.relayPort, new Map(devices.map((d) => [d.token, d.id])), cfg.cmdTimeoutMs);
    this.audit = new Audit(cfg.auditDir);
    this.suggestions = new SuggestionStore(cfg.suggestionsDir);
    for (const d of devices) this.addPhone(d);
  }

  private addPhone(d: DeviceConfig): void {
    const phone: Phone = {
      cfg: d,
      adbForWindow: false,
      adb: new AdbSessions(this.cfg.adbPath, d.adb.host, (tool, args) => this.relay.dispatch(d.id, tool, args),
        (why) => { phone.adbForWindow = false; this.audit.record({ kind: "safety", device: d.id, tool: "adb_session_end", why }); },
        undefined, d.adb.mode === "always" ? d.adb.serial : ""),
    };
    this.phones.set(d.id, phone);
  }

  /** The configured phones, in configuration order. */
  get devices(): readonly DeviceConfig[] {
    return [...this.phones.values()].map((p) => p.cfg);
  }

  /** ADB sessions of [device] (tests, diagnostics). */
  adbOf(device: string): AdbSessions | undefined {
    return this.phones.get(device)?.adb;
  }

  /** Start the phone-facing relay, audit every device event and answer phone requests. */
  async start(): Promise<void> {
    await this.relay.start();
    this.relay.onEvent((device, event, payload) => this.audit.record({ kind: "event", device, event, payload: redact(payload) }));
    this.relay.onRequest((device, op, args) => this.onPhoneRequest(device, op, args));
    this.relay.onConnected((device) => this.setup.spend(device));
    this.relay.onHttp((req, res) => this.serveSetup(req, res));
    if ([...this.phones.values()].some((p) => p.cfg.adb.reverse)) {
      void this.keepReverse();
      this.reverseTimer = setInterval(() => void this.keepReverse(), REVERSE_EVERY_MS);
      this.reverseTimer.unref?.();
    }
  }

  async stop(): Promise<void> {
    if (this.reverseTimer) clearInterval(this.reverseTimer);
    for (const p of this.phones.values()) await p.adb.end("relay_stopped");
    await this.relay.stop();
  }

  /** Every configured phone with its connection state. */
  status(): DeviceStatus[] {
    return [...this.phones.values()].map(({ cfg, adb }) => {
      const s = this.relay.session(cfg.id);
      const connected = this.relay.isConnected(cfg.id);
      const seen = this.relay.lastSeen(cfg.id);
      return {
        id: cfg.id,
        name: cfg.name,
        role: cfg.role,
        connected,
        connectedSince: connected && s ? s.connectedSince.toISOString() : null,
        lastSeen: seen ? seen.toISOString() : null,
        ...(s?.info.model ? { model: s.info.model } : {}),
        ...(s?.info.androidSdk ? { androidSdk: s.info.androidSdk } : {}),
        ...(s?.info.appVersion ? { appVersion: s.info.appVersion } : {}),
        adb: { mode: cfg.adb.mode, sessionOpen: adb.active },
        ...(s ? { rules: deviceRules(s.info.model, s.info.androidSdk) } : {}),
      };
    });
  }

  /** Run a catalog tool. Never throws: failures come back as `{ ok: false, error }`. */
  async callTool(name: string, rawArgs: ToolArgs): Promise<ToolResult> {
    const def = CATALOG_BY_NAME.get(name);
    if (!def) return { ok: false, error: `unknown tool '${name}'` };
    const { device: wanted, ...args } = rawArgs;

    // Host-wide tools.
    switch (name) {
      case "list_devices":
        return { ok: true, data: { devices: this.status() } };
      case "get_audit_log":
        return { ok: true, data: { path: this.audit.path() } };
      case "list_suggestions":
        return { ok: true, data: this.suggestions.list() };
      case "export_suggestions":
        return { ok: true, data: this.suggestions.exportMarkdown() };
      case "panic_disconnect":
        if (wanted === undefined) return this.panicAll();
    }
    if (HOST_TOOLS.has(name)) return { ok: false, error: `unhandled host tool '${name}'` };

    const picked = this.pickDevice(wanted);
    if (typeof picked === "string") return { ok: false, error: picked };
    const phone = picked;
    const id = phone.cfg.id;
    // Any agent activity keeps that phone's open ADB session alive (idle fallback, see AdbSessions).
    if (name !== "adb_session_end" && name !== "panic_disconnect") phone.adb.touch();

    // Local handlers (never leave the host).
    switch (name) {
      case "suggest_feature":
        return this.suggest(phone, args);
      case "adb_session_begin":
        return this.beginAdb(phone, args);
      case "adb_shell":
        return this.adbShell(phone, args);
      case "adb_session_end":
        await phone.adb.end("agent", true);
        return { ok: true, data: { device: id, debugging: phone.cfg.adb.mode === "always" ? "on (always)" : "off" } };
      case "own_helper_start":
        return this.startHelper(phone);
      case "own_helper_stop":
        return this.stopHelper(phone);
      case "panic_disconnect":
        return { ok: true, data: { disconnected: [await this.panic(phone)] } };
      case "bg_open":
        await this.prepareOwnWindow(phone, args);
        break;
    }

    // Everything else is forwarded to the phone.
    this.audit.record({
      kind: "cmd",
      device: id,
      tool: name,
      tier: def.tier,
      confirmRequired: def.confirm,
      args: redact(args),
      phase: "request",
    });
    try {
      const data = name === "unlock_device" ? await this.unlockDevice(phone) : await this.relay.dispatch(id, name, args);
      this.audit.record({ kind: "cmd", device: id, tool: name, ok: true });
      if (name === "bg_close" && phone.adbForWindow) {
        // The ADB session was opened only for the own window: debugging goes off with it.
        phone.adbForWindow = false;
        await phone.adb.end("window_closed");
      }
      if (name === "get_device_info" && data && typeof data === "object") {
        const d = data as { model?: unknown; androidSdk?: unknown };
        const rules = deviceRules(typeof d.model === "string" ? d.model : undefined, typeof d.androidSdk === "number" ? d.androidSdk : undefined);
        return { ok: true, data: { ...d, rules } };
      }
      return { ok: true, data };
    } catch (err) {
      const error = errorMessage(err);
      this.audit.record({ kind: "cmd", device: id, tool: name, ok: false, error });
      return { ok: false, error };
    }
  }

  private async unlockDevice(phone: Phone): Promise<unknown> {
    const id = phone.cfg.id;
    try {
      return await this.relay.dispatch(id, "unlock_device", {});
    } catch (err) {
      if (errorMessage(err) !== "accessibility service not enabled") throw err;
    }
    const state = (await this.relay.dispatch(id, "get_lock_state", {})) as { locked?: boolean; adbPinUnlockAvailable?: boolean };
    if (state.locked === false) return { unlocked: true, alreadyUnlocked: true };
    if (state.adbPinUnlockAvailable !== true) throw new Error("accessibility service not enabled; ADB PIN unlock is unavailable or has not been accepted on the phone");
    if (phone.unlockInProgress) throw new Error("An ADB unlock is already in progress");
    phone.unlockInProgress = true;
    const hadSession = phone.adb.active;
    try {
      if (!hadSession) await phone.adb.begin();
      const prepared = (await this.relay.dispatch(id, "adb_pin_unlock_prepare", {})) as { challenge?: unknown };
      if (typeof prepared.challenge !== "string" || !/^[0-9a-f]{64}$/.test(prepared.challenge)) throw new Error("Could not prepare ADB PIN unlock");
      const result = await runPinUnlock(this.cfg.adbPath, await phone.adb.ready(), prepared.challenge);
      if (result === "unlocked" || result === "already_unlocked") return { unlocked: true, alreadyUnlocked: result === "already_unlocked", via: "adb" };
      if (result === "attempt_failed") throw new Error("ADB PIN unlock failed; automatic PIN unlock has been disabled on the phone");
      if (result === "numeric_pin_required") throw new Error("ADB unlock requires a numeric PIN saved on the phone");
      throw new Error("ADB unlock could not verify an empty, focused system PIN pad; unlock the phone manually");
    } finally {
      try {
        if (!hadSession) await phone.adb.end("unlock_finished");
      } finally {
        phone.unlockInProgress = false;
      }
    }
  }

  /**
   * The phone a call is for: the `device` argument, else the only configured phone,
   * else the only connected one. Returns an error message when it is ambiguous, so an
   * agent never acts on a phone it did not mean.
   */
  private pickDevice(wanted: unknown): Phone | string {
    if (wanted !== undefined && wanted !== null && wanted !== "") {
      const p = this.phones.get(String(wanted));
      return p ?? `unknown device '${String(wanted)}'; configured: ${[...this.phones.keys()].join(", ")}`;
    }
    if (this.phones.size === 1) return [...this.phones.values()][0]!;
    const connected = this.relay.connectedDevices();
    if (connected.length === 1) return this.phones.get(connected[0]!)!;
    if (connected.length === 0) return `no device connected (configured: ${[...this.phones.keys()].join(", ")})`;
    return `several phones are connected (${connected.join(", ")}): pass device=<id> (see list_devices)`;
  }

  /** A phone asked the relay something (`req` frame). */
  private async onPhoneRequest(device: string, op: string, args: Record<string, unknown> = {}): Promise<unknown> {
    if (op === "add_device") return this.addDeviceFromPhone(device, args);
    if (op === "adb_pair") return this.pairFromPhone(device, args);
    if (op === "fix_installer") return this.fixInstallerFromPhone(device);
    if (op !== "relay_status") throw new Error(`unsupported request '${op}'`);
    const phone = this.phones.get(device);
    if (phone?.cfg.role !== "admin") throw new Error("not_admin: only admin phones can see the relay status");
    const status: RelayStatus = {
      version: VERSION,
      startedAt: this.startedAt.toISOString(),
      agents: this.agents,
      self: device,
      devices: this.status(),
    };
    return status;
  }

  /**
   * An admin phone adds another phone: writes its token and devices.json entry and lets it
   * connect right away (no relay restart). The token goes back to that admin phone only,
   * once, to be handed to the new phone; the audit log never sees it.
   */
  private addDeviceFromPhone(device: string, args: Record<string, unknown>): { id: string; name: string; role: string; token: string } {
    if (this.phones.get(device)?.cfg.role !== "admin") throw new Error("not_admin: only admin phones can add phones");
    const file = this.cfg.devicesFile;
    if (!file || !this.cfg.devices?.length) throw new Error("no_devices_file: this relay runs without a devices file");
    try {
      const nd = validateNewDevice(args, this.phones.keys());
      const added = writeNewDevice(file, nd);
      this.addPhone(added);
      this.relay.addToken(added.token, added.id);
      this.audit.record({ kind: "safety", device, tool: "add_device", ok: true, args: { id: added.id, name: added.name, role: added.role } });
      return { id: added.id, name: added.name, role: added.role, token: added.token };
    } catch (err) {
      this.audit.record({ kind: "safety", device, tool: "add_device", ok: false, error: errorMessage(err) });
      throw err;
    }
  }

  /**
   * The phone pairs this computer's adb with itself (code read from Android's pairing
   * dialog), then gets the one-time grants. Any phone may pair itself; the code never
   * reaches the audit log.
   */
  private async pairFromPhone(device: string, args: Record<string, unknown>): Promise<PairResult> {
    if (this.phones.get(device)?.cfg.adb.mode === "always") throw new Error("not_needed: this phone's adb is always on (adb mode \"always\")");
    if (this.pairing.has(device)) throw new Error("busy: a pairing is already running for this phone");
    this.pairing.add(device);
    try {
      const req = validatePair(args);
      const r = await pairAndSetUp(this.cfg.adbPath, req);
      this.audit.record({ kind: "safety", device, tool: "adb_pair", ok: true, args: { host: r.host, connected: r.connected, granted: r.granted, tcpip: r.tcpip } });
      return r;
    } catch (err) {
      this.audit.record({ kind: "safety", device, tool: "adb_pair", ok: false, error: errorMessage(err) });
      throw err;
    } finally {
      this.pairing.delete(device);
    }
  }

  /**
   * The phone asks to have the Play Store recorded as DroidHand's installer ("compatibility with
   * banking apps", on by default in the app). Opens an ADB session if none is open, reinstalls the
   * APK in place when needed and closes the session again once the app is back.
   */
  private async fixInstallerFromPhone(device: string): Promise<InstallerResult> {
    const phone = this.phones.get(device);
    if (!phone) throw new Error("unknown device");
    if (this.fixingInstaller.has(device)) throw new Error("busy: an installer fix is already running for this phone");
    this.fixingInstaller.add(device);
    const hadSession = phone.adb.active;
    try {
      if (!hadSession) await phone.adb.begin();
      const r = await ensurePlayInstaller(this.cfg.adbPath, await phone.adb.ready());
      this.audit.record({ kind: "safety", device, tool: "fix_installer", ok: true, args: { reinstalled: r.reinstalled, installer: r.installer } });
      if (r.reinstalled) await this.waitConnected(device, 45_000);
      return r;
    } catch (err) {
      this.audit.record({ kind: "safety", device, tool: "fix_installer", ok: false, error: errorMessage(err) });
      throw err;
    } finally {
      this.fixingInstaller.delete(device);
      if (!hadSession) await phone.adb.end("installer_fixed");
    }
  }

  /** Waits (bounded) for a phone to be connected again, e.g. after its app was reinstalled. */
  private async waitConnected(device: string, ms: number): Promise<void> {
    const until = Date.now() + ms;
    while (Date.now() < until && !this.relay.isConnected(device)) await new Promise((r) => setTimeout(r, 500));
  }

  /**
   * A one-time setup link for a phone (control channel only, never an agent tool):
   * `existing` = a configured phone (e.g. the first one), else a new phone `id`/`name`
   * is added like add_device. [relayUrl] is the address the phone will dial.
   */
  createInvite(args: Record<string, unknown>): { device: string; name: string; relayUrl: string; token: string; setupPath: string; appLink: string; expiresAt: string } {
    const relayUrl = String(args.relayUrl ?? "");
    if (!/^wss?:\/\/[^\s"'<>&#]+$/.test(relayUrl)) throw new Error("bad_relay_url: pass the address phones dial, e.g. ws://100.x.y.z:8787");
    let cfg: DeviceConfig;
    if (args.id !== undefined && args.id !== "") {
      const file = this.cfg.devicesFile;
      if (!file || !this.cfg.devices?.length) throw new Error("no_devices_file: create devices.json first (see docs/setup.md, Several phones), or invite the existing phone");
      const added = writeNewDevice(file, validateNewDevice(args, this.phones.keys()));
      this.addPhone(added);
      this.relay.addToken(added.token, added.id);
      cfg = added;
    } else {
      const want = args.existing ? String(args.existing) : this.phones.size === 1 ? [...this.phones.keys()][0]! : "";
      const p = this.phones.get(want);
      if (!p) throw new Error(`which_phone: pass --existing <id> or --id <new-id> (configured: ${[...this.phones.keys()].join(", ")})`);
      cfg = p.cfg;
    }
    const { code, expiresAt } = this.setup.create({ device: cfg.id, name: cfg.name, token: cfg.token, relayUrl });
    this.audit.record({ kind: "safety", device: cfg.id, tool: "setup_link", ok: true, args: { expiresAt } });
    return { device: cfg.id, name: cfg.name, relayUrl, token: cfg.token, setupPath: `/setup/${code}`, appLink: appLink({ relayUrl, token: cfg.token, name: cfg.name }), expiresAt };
  }

  /** GET /setup/<code> (page) and /setup/<code>.json (what the app reads after scanning the QR). */
  private serveSetup(req: IncomingMessage, res: ServerResponse): boolean {
    const m = /^\/setup\/([A-Za-z0-9_-]+)(\.json)?$/.exec((req.url ?? "").split("?")[0] ?? "");
    if (!m) return false;
    const e = this.setup.get(m[1]!);
    const lang = pickLang(req.headers["accept-language"]);
    const head = { "cache-control": "no-store", "referrer-policy": "no-referrer", "x-content-type-options": "nosniff" };
    if (m[2]) {
      res.writeHead(e ? 200 : 410, { ...head, "content-type": "application/json" });
      res.end(JSON.stringify(e ? { relay: e.relayUrl, token: e.token, name: e.name } : { error: "gone" }));
    } else {
      res.writeHead(e ? 200 : 410, { ...head, "content-type": "text/html; charset=utf-8" });
      res.end(e ? renderSetupPage(e, lang) : renderGonePage(lang));
    }
    return true;
  }

  /** panic_disconnect without a device: every phone. */
  private async panicAll(): Promise<ToolResult> {
    const done: string[] = [];
    for (const p of this.phones.values()) done.push(await this.panic(p));
    return { ok: true, data: { disconnected: done } };
  }

  /**
   * Ask the phone to stop its agent service (it stays off until the operator
   * restarts it in the app), then sever the session regardless of the answer.
   */
  private async panic(phone: Phone): Promise<string> {
    const id = phone.cfg.id;
    await phone.adb.end("panic", true);
    if (this.relay.isConnected(id)) await this.relay.dispatch(id, "panic", {}).catch(() => undefined);
    this.relay.disconnect(id);
    this.audit.record({ kind: "safety", device: id, tool: "panic_disconnect", ok: true });
    return id;
  }

  /** "always" phones on a LAN/USB without a route to the relay: keep `adb reverse` of the relay port. */
  private async keepReverse(): Promise<void> {
    const port = this.relay.address()?.port;
    if (!port) return;
    for (const { cfg } of this.phones.values()) {
      if (!cfg.adb.reverse || this.relay.isConnected(cfg.id)) continue;
      const serial = cfg.adb.serial;
      await run(this.cfg.adbPath, ["connect", serial], 6000).catch(() => undefined);
      await run(this.cfg.adbPath, ["-s", serial, "reverse", `tcp:${port}`, `tcp:${port}`], 6000).catch(() => undefined);
    }
  }

  /**
   * bg_open in "auto" mode prefers the own window (it never takes the focus from the
   * user's screen, so their keyboard stays). It needs the shell helper, so when ADB can
   * be turned on the relay opens a session just for the window and closes it at bg_close.
   * If ADB is not possible the phone falls back to the native window.
   */
  private async prepareOwnWindow(phone: Phone, args: ToolArgs): Promise<void> {
    const id = phone.cfg.id;
    if ((args.mode ?? "auto") !== "auto" || !this.relay.isConnected(id)) return;
    const st = (await this.relay.dispatch(id, "bg_status", {}).catch(() => null)) as { userChoice?: string } | null;
    if (!st || st.userChoice !== "agent" || ownHelperAvailable(st)) return;
    const hadSession = phone.adb.active;
    const res = await this.startHelper(phone);
    if (res.ok && !hadSession) phone.adbForWindow = true;
  }

  private async beginAdb(phone: Phone, args: ToolArgs): Promise<ToolResult> {
    const tool = "adb_session_begin";
    const reason = String(args.reason ?? "");
    const device = phone.cfg.id;
    try {
      const info = await phone.adb.begin();
      this.audit.record({ kind: "cmd", device, tool, ok: true, reason, serial: info.serial, expiresAt: info.expiresAt, armed: info.armed });
      return { ok: true, data: { device, ...info } };
    } catch (err) {
      const error = errorMessage(err);
      this.audit.record({ kind: "cmd", device, tool, ok: false, reason, error });
      return { ok: false, error };
    }
  }

  private async adbShell(phone: Phone, args: ToolArgs): Promise<ToolResult> {
    const tool = "adb_shell";
    const device = phone.cfg.id;
    const command = String(args.command ?? "");
    this.audit.record({ kind: "cmd", device, tool, tier: "privileged", args: redact({ command }), phase: "request" });
    const why = lockoutReason(command);
    if (why) {
      const error = lockoutError(why);
      this.audit.record({ kind: "safety", device, tool, ok: false, error });
      return { ok: false, error };
    }
    try {
      const secs = typeof args.timeoutSeconds === "number" ? args.timeoutSeconds : 30;
      const out = await phone.adb.shell(command, secs * 1000);
      const cap = (t: string) => (t.length > 65536 ? t.slice(0, 65536) + "\n…(truncated)" : t);
      this.audit.record({ kind: "cmd", device, tool, ok: out.exitCode === 0, exitCode: out.exitCode });
      return { ok: true, data: { stdout: cap(out.stdout), stderr: cap(out.stderr), exitCode: out.exitCode } };
    } catch (err) {
      const error = errorMessage(err);
      this.audit.record({ kind: "cmd", device, tool, ok: false, error });
      return { ok: false, error };
    }
  }

  /** Start the phone's shell helper over adb (opening an ADB session if needed), then wait until the app reports it. */
  private async startHelper(phone: Phone): Promise<ToolResult> {
    const tool = "own_helper_start";
    const id = phone.cfg.id;
    try {
      const fixed = phone.cfg.adb.mode === "on-demand" ? phone.cfg.adb.serial : "";
      const serial = fixed || (phone.adb.current ? await phone.adb.ready() : (await phone.adb.begin()).serial);
      await startShellHelper(this.cfg.adbPath, serial);
      let available = false;
      for (let i = 0; i < HELPER_POLL_ATTEMPTS && !available; i++) {
        await new Promise((r) => setTimeout(r, HELPER_POLL_INTERVAL_MS));
        if (!this.relay.isConnected(id)) continue;
        available = ownHelperAvailable(await this.relay.dispatch(id, "bg_status", {}).catch(() => null));
      }
      this.audit.record({ kind: "cmd", device: id, tool, ok: available, serial });
      return available
        ? { ok: true, data: { device: id, serial, ownAvailable: true } }
        : { ok: false, error: "helper started but the app did not report it (is the agent service connected?)" };
    } catch (err) {
      const error = errorMessage(err);
      this.audit.record({ kind: "cmd", device: id, tool, ok: false, error });
      return { ok: false, error };
    }
  }

  private async stopHelper(phone: Phone): Promise<ToolResult> {
    const tool = "own_helper_stop";
    const id = phone.cfg.id;
    try {
      const fixed = phone.cfg.adb.mode === "on-demand" ? phone.cfg.adb.serial : "";
      const serial = fixed || phone.adb.current?.serial || (phone.cfg.adb.mode === "always" ? phone.cfg.adb.serial : "");
      if (serial) await stopShellHelper(this.cfg.adbPath, serial);
      this.audit.record({ kind: "safety", device: id, tool, ok: true });
      return { ok: true, data: { device: id, stopped: true } };
    } catch (err) {
      const error = errorMessage(err);
      this.audit.record({ kind: "cmd", device: id, tool, ok: false, error });
      return { ok: false, error };
    }
  }

  /** Mirror a suggestion locally and forward it to the phone (the source of truth). */
  private suggest(phone: Phone, args: ToolArgs): ToolResult {
    const id = phone.cfg.id;
    if (!this.relay.isConnected(id)) {
      return { ok: false, error: `device '${id}' is not connected` };
    }
    if (!this.relay.deviceInfo(id)?.suggestionLogging) {
      return {
        ok: false,
        error:
          "Suggestion logging is OFF. The operator can enable it in the DroidHand app ('Allow the agent to log feature suggestions'); it is off by default.",
      };
    }
    const title = String(args.title ?? "").trim();
    const detail = String(args.detail ?? "").trim();
    if (!title || !detail) return { ok: false, error: "title and detail are required" };

    const entry = this.suggestions.add({
      title,
      detail,
      category: isSuggestionCategory(args.category) ? args.category : undefined,
      priority: typeof args.priority === "number" ? args.priority : undefined,
      context: args.context ? String(args.context) : undefined,
      source: "agent",
    });
    // Best-effort on-device persistence; ignore if the app lacks the handler.
    this.relay.dispatch(id, "store_suggestion", { ...entry }).catch(() => {});
    this.audit.record({ kind: "suggestion", device: id, id: entry.id, title: entry.title });
    return { ok: true, data: entry };
  }
}
