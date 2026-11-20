import { readFileSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";

export interface Config {
  /** Interface the phone-facing relay binds to. Default loopback; set to the Tailscale IP in prod. */
  relayHost: string;
  relayPort: number;
  /** Loopback interface the per-session MCP adapters connect to. */
  controlHost: string;
  controlPort: number;
  /** Shared bearer token the phone and adapters must present. Empty = reject all (fail closed). */
  token: string;
  auditDir: string;
  suggestionsDir: string;
  cmdTimeoutMs: number;
  /** adb binary and (optional) device serial used by own_helper_start. */
  adbPath: string;
  adbSerial: string;
}

function stateDir(sub: string): string {
  const base = process.env.XDG_STATE_HOME ?? join(homedir(), ".local", "state");
  return join(base, "droidhand", sub);
}

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
  };
}
