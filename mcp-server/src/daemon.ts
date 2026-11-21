#!/usr/bin/env node
import { loadConfig } from "./config.js";
import { Core } from "./core/core.js";
import { startControlServer } from "./control/server.js";

/**
 * Entry point `droidhand-relay` (dist/daemon.js): the relay daemon, one per host.
 * Owns the phone-facing relay and a loopback control server that per-session
 * MCP adapters connect to. Meant to run as a user systemd service
 * (see deploy/droidhand-relay.service).
 */
async function main(): Promise<void> {
  const cfg = loadConfig();
  if (!cfg.token) {
    process.stderr.write("[droidhand-relay] WARNING: no token set — relay and control will reject all connections (fail closed).\n");
  }
  const core = new Core(cfg);
  await core.start();
  const ctrl = await startControlServer(core, cfg.controlHost, cfg.controlPort, cfg.token);
  process.stderr.write(
    `[droidhand-relay] phone relay on ${cfg.relayHost}:${cfg.relayPort}; control on ${cfg.controlHost}:${cfg.controlPort}\n`,
  );

  const shutdown = async (): Promise<void> => {
    await ctrl.close();
    await core.stop();
    process.exit(0);
  };
  process.on("SIGINT", shutdown);
  process.on("SIGTERM", shutdown);
}

main().catch((err) => {
  process.stderr.write(`[droidhand-relay] fatal: ${err instanceof Error ? err.stack : String(err)}\n`);
  process.exit(1);
});
