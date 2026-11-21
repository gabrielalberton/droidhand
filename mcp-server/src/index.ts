#!/usr/bin/env node
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { loadConfig } from "./config.js";
import { ControlClient } from "./control/client.js";
import { createMcpServer } from "./mcp/server.js";

/**
 * Entry point `droidhand-mcp` (dist/index.js): the stdio MCP adapter, spawned
 * per agent session (Claude Code, Codex, …). It holds no phone connection and
 * binds no port — it forwards every tool call to the relay daemon over the
 * loopback control channel. If the daemon is down, tools still list and calls
 * return a clear, non-fatal error.
 */
async function main(): Promise<void> {
  const cfg = loadConfig();
  const control = new ControlClient(`ws://${cfg.controlHost}:${cfg.controlPort}`, cfg.token);
  const server = createMcpServer({
    // ADB sessions may arm adb over the network (tens of seconds); everything else answers fast.
    call: (tool, args) => control.call(tool, args, /^(adb_|own_helper_)/.test(tool) ? 120_000 : undefined),
    unreachableMessage: `relay daemon unreachable at ${cfg.controlHost}:${cfg.controlPort} (is droidhand-relay running?)`,
  });
  await server.connect(new StdioServerTransport());
}

main().catch((err) => {
  process.stderr.write(`[droidhand] fatal: ${err instanceof Error ? err.stack : String(err)}\n`);
  process.exit(1);
});
