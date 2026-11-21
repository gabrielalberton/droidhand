import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import type { Config } from "../../src/config.js";

/** Config for tests: ephemeral ports, a throwaway state dir, and a fixed token. */
export function tmpConfig(overrides: Partial<Config> = {}): Config {
  const dir = mkdtempSync(join(tmpdir(), "droidhand-test-"));
  return {
    relayHost: "127.0.0.1",
    relayPort: 0,
    controlHost: "127.0.0.1",
    controlPort: 0,
    token: "test-token",
    auditDir: join(dir, "audit"),
    suggestionsDir: join(dir, "suggestions"),
    cmdTimeoutMs: 3000,
    adbPath: "adb",
    adbSerial: "",
    ...overrides,
  };
}

/** Poll until `predicate` holds or the timeout elapses. */
export async function waitFor(predicate: () => boolean, timeoutMs = 1000): Promise<void> {
  const start = Date.now();
  while (!predicate()) {
    if (Date.now() - start > timeoutMs) throw new Error("waitFor timed out");
    await new Promise((r) => setTimeout(r, 10));
  }
}
