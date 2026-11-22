import { existsSync, readFileSync } from "node:fs";
import { homedir } from "node:os";
import { dirname, isAbsolute, join, resolve } from "node:path";

/** How the relay reaches a phone over adb. */
export interface DeviceAdb {
  /**
   * "on-demand" (default): debugging stays off; adb_session_begin turns it on for a while
   * and adb_session_end turns it off. "always": a test phone whose adbd is always reachable
   * at [serial]; sessions only connect, never toggle debugging.
   */
  mode: "on-demand" | "always";
  /** Fixed phone address for on-demand sessions; empty = the tailnet/Wi-Fi address the phone reports. */
  host: string;
  /** "always": adb serial (host:port). "on-demand": optional fixed serial for own_helper_* (e.g. USB). */
  serial: string;
  /** "always" only: keep `adb reverse` of the relay port, so the phone reaches the relay at ws://127.0.0.1:<port>. */
  reverse: boolean;
}

/** One phone the relay accepts, each with its own token and settings. */
export interface DeviceConfig {
  /** Short id agents pass as `device` (lowercase letters, digits, - and _). */
  id: string;
  name: string;
  /** The phone authenticates with this token; it also tells the relay which phone it is. */
  token: string;
  /** "admin" phones may read the relay status in the app (other phones, connections). */
  role: "admin" | "device";
  adb: DeviceAdb;
}

/**
 * Runtime configuration, read from `DROIDHAND_*` environment variables (see
 * `loadConfig` for names and defaults). Shared by the daemon and the adapter.
 */
export interface Config {
  /** Interface the phone-facing relay binds to. Default loopback; expose it via a private network (e.g. Tailscale). */
  relayHost: string;
  relayPort: number;
  /** Loopback interface the per-session MCP adapters connect to. */
  controlHost: string;
  controlPort: number;
  /** Bearer token of the control channel (agents); also the phone's token without a devices file. Empty = reject all (fail closed). */
  token: string;
  /** Directory of the append-only `audit.jsonl`. */
  auditDir: string;
  /** Directory of the host-side `suggestions.jsonl` mirror. */
  suggestionsDir: string;
  /** How long the relay waits for the phone to answer a command. */
  cmdTimeoutMs: number;
  /** adb binary; optional fixed serial for own_helper_* (bypasses ADB sessions, e.g. USB). */
  adbPath: string;
  adbSerial: string;
  /** Phone address for ADB sessions; empty = the tailnet/Wi-Fi address the phone reports. */
  adbHost: string;
  /**
   * Phones, from DROIDHAND_DEVICES_FILE (default ~/.config/droidhand/devices.json when it exists).
   * Without that file: one phone, id "phone", authenticated with `token` and using adbHost/adbSerial.
   */
  devices: DeviceConfig[];
}

const DEVICE_ID = /^[a-z0-9][a-z0-9_-]{0,31}$/;

function expand(p: string, base: string): string {
  const home = p.startsWith("~/") ? join(homedir(), p.slice(2)) : p;
  return isAbsolute(home) ? home : resolve(base, home);
}

/** The single phone of a setup without a devices file (the 1.0 behaviour). */
export function legacyDevice(token: string, adbHost: string, adbSerial: string): DeviceConfig {
  return { id: "phone", name: "Phone", token, role: "admin", adb: { mode: "on-demand", host: adbHost, serial: adbSerial, reverse: false } };
}

/**
 * Parse a devices file:
 * `{ "devices": [ { "id", "name", "tokenFile" | "token", "role", "adb": { "mode", "host", "serial", "reverse" } } ] }`.
 * Relative token files resolve against the file's directory. Throws on invalid content.
 */
export function parseDevices(json: string, baseDir: string): DeviceConfig[] {
  const raw = JSON.parse(json) as { devices?: unknown };
  if (!Array.isArray(raw.devices) || raw.devices.length === 0) throw new Error("devices file: 'devices' must be a non-empty array");
  const out: DeviceConfig[] = [];
  for (const d of raw.devices as Record<string, unknown>[]) {
    const id = String(d.id ?? "");
    if (!DEVICE_ID.test(id)) throw new Error(`devices file: invalid id '${id}' (lowercase letters, digits, - and _)`);
    if (out.some((o) => o.id === id)) throw new Error(`devices file: duplicate id '${id}'`);
    const token = typeof d.token === "string" ? d.token.trim()
      : typeof d.tokenFile === "string" ? readFileSync(expand(d.tokenFile, baseDir), "utf8").trim() : "";
    if (!token) throw new Error(`devices file: '${id}' has no token (set token or tokenFile)`);
    if (out.some((o) => o.token === token)) throw new Error(`devices file: '${id}' reuses another phone's token`);
    const adb = (typeof d.adb === "object" && d.adb !== null ? d.adb : {}) as Record<string, unknown>;
    const mode = adb.mode === "always" ? "always" : "on-demand";
    const serial = typeof adb.serial === "string" ? adb.serial : "";
    if (mode === "always" && !serial) throw new Error(`devices file: '${id}' uses adb mode "always" without a serial`);
    out.push({
      id,
      name: typeof d.name === "string" && d.name.trim() ? d.name.trim() : id,
      token,
      role: d.role === "admin" ? "admin" : "device",
      adb: { mode, host: typeof adb.host === "string" ? adb.host : "", serial, reverse: mode === "always" && adb.reverse === true },
    });
  }
  return out;
}

function stateDir(sub: string): string {
  const base = process.env.XDG_STATE_HOME ?? join(homedir(), ".local", "state");
  return join(base, "droidhand", sub);
}

/**
 * Build the config from the environment. `DROIDHAND_TOKEN` wins over
 * `DROIDHAND_TOKEN_FILE`; state dirs default to `$XDG_STATE_HOME/droidhand/*`.
 * The MCP adapter passes withDevices=false: only the relay daemon reads the phones.
 */
export function loadConfig(env: NodeJS.ProcessEnv = process.env, withDevices = true): Config {
  let token = env.DROIDHAND_TOKEN ?? "";
  if (!token && env.DROIDHAND_TOKEN_FILE) {
    token = readFileSync(env.DROIDHAND_TOKEN_FILE, "utf8").trim();
  }
  const devicesFile = env.DROIDHAND_DEVICES_FILE ?? join(homedir(), ".config", "droidhand", "devices.json");
  const adbHost = env.DROIDHAND_ADB_HOST ?? "";
  const adbSerial = env.DROIDHAND_ADB_SERIAL ?? "";
  const devices = !withDevices ? [] : existsSync(devicesFile)
    ? parseDevices(readFileSync(devicesFile, "utf8"), dirname(devicesFile))
    : [legacyDevice(token, adbHost, adbSerial)];
  return {
    relayHost: env.DROIDHAND_RELAY_HOST ?? "127.0.0.1",
    relayPort: Number(env.DROIDHAND_RELAY_PORT ?? "8787"),
    controlHost: env.DROIDHAND_CONTROL_HOST ?? "127.0.0.1",
    controlPort: Number(env.DROIDHAND_CONTROL_PORT ?? "8788"),
    token,
    auditDir: env.DROIDHAND_AUDIT_DIR ?? stateDir("audit"),
    suggestionsDir: env.DROIDHAND_SUGGESTIONS_DIR ?? stateDir("suggestions"),
    cmdTimeoutMs: Number(env.DROIDHAND_CMD_TIMEOUT_MS ?? "15000"),
    adbPath: env.DROIDHAND_ADB ?? "adb",
    adbSerial,
    adbHost,
    devices,
  };
}
