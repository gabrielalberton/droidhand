import { legacyDevice, type Config, type DeviceConfig } from "../config.js";
import { Relay } from "../relay/server.js";
import { CATALOG_BY_NAME } from "../catalog.js";
import type { DeviceStatus, RelayStatus } from "../protocol.js";
import { VERSION } from "../version.js";
import { Audit, redact } from "./audit.js";
import { SuggestionStore, isSuggestionCategory } from "./suggestions.js";
import { run, startShellHelper, stopShellHelper } from "./adb.js";
import { AdbSessions } from "./adbSession.js";

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
  readonly startedAt = new Date();
  /** MCP adapters (agent sessions) currently connected to the control channel. */
  agents = 0;
  private readonly cfg: Config;
  private readonly phones = new Map<string, Phone>();
  private reverseTimer: NodeJS.Timeout | undefined;

  constructor(cfg: Config) {
    this.cfg = cfg;
    const devices = cfg.devices?.length ? cfg.devices : [legacyDevice(cfg.token, cfg.adbHost, cfg.adbSerial)];
    this.relay = new Relay(cfg.relayHost, cfg.relayPort, new Map(devices.map((d) => [d.token, d.id])), cfg.cmdTimeoutMs);
    this.audit = new Audit(cfg.auditDir);
    this.suggestions = new SuggestionStore(cfg.suggestionsDir);
    for (const d of devices) {
      const phone: Phone = {
        cfg: d,
        adbForWindow: false,
        adb: new AdbSessions(cfg.adbPath, d.adb.host, (tool, args) => this.relay.dispatch(d.id, tool, args),
          (why) => { phone.adbForWindow = false; this.audit.record({ kind: "safety", device: d.id, tool: "adb_session_end", why }); },
          undefined, d.adb.mode === "always" ? d.adb.serial : ""),
      };
      this.phones.set(d.id, phone);
    }
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
    this.relay.onRequest((device, op) => this.onPhoneRequest(device, op));
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
      const data = await this.relay.dispatch(id, name, args);
      this.audit.record({ kind: "cmd", device: id, tool: name, ok: true });
      if (name === "bg_close" && phone.adbForWindow) {
        // The ADB session was opened only for the own window: debugging goes off with it.
        phone.adbForWindow = false;
        await phone.adb.end("window_closed");
      }
      return { ok: true, data };
    } catch (err) {
      const error = errorMessage(err);
      this.audit.record({ kind: "cmd", device: id, tool: name, ok: false, error });
      return { ok: false, error };
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
  private async onPhoneRequest(device: string, op: string): Promise<unknown> {
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
