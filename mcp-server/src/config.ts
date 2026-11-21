import { readFileSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";

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
  /** Shared bearer token the phone and adapters must present. Empty = reject all (fail closed). */
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
}

function stateDir(sub: string): string {
  const base = process.env.XDG_STATE_HOME ?? join(homedir(), ".local", "state");
  return join(base, "droidhand", sub);
}

/**
 * Build the config from the environment. `DROIDHAND_TOKEN` wins over
 * `DROIDHAND_TOKEN_FILE`; state dirs default to `$XDG_STATE_HOME/droidhand/*`.
 */
export function loadConfig(env: NodeJS.ProcessEnv = process.env): Config {
  let token = env.DROIDHAND_TOKEN ?? "";
  if (!token && env.DROIDHAND_TOKEN_FILE) {
    token = readFileSync(env.DROIDHAND_TOKEN_FILE, "utf8").trim();
  }
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
    adbSerial: env.DROIDHAND_ADB_SERIAL ?? "",
    adbHost: env.DROIDHAND_ADB_HOST ?? "",
  };
}
