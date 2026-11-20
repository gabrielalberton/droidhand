#!/usr/bin/env node
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { loadConfig } from "./config.js";
import { ControlClient } from "./control.js";
import { CATALOG } from "./catalog.js";

/**
 * The stdio MCP adapter: spawned per agent session (Claude Code, Codex, …). It
 * holds no phone connection and binds no port — it forwards every tool call to
 * the relay daemon over the loopback control channel. If the daemon is down,
 * tools still list and calls return a clear, non-fatal error.
 */
async function main(): Promise<void> {
  const cfg = loadConfig();
  const control = new ControlClient(`ws://${cfg.controlHost}:${cfg.controlPort}`, cfg.token);

  const server = new McpServer({ name: "droidhand", version: "0.1.0" });

  for (const tool of CATALOG) {
    server.tool(tool.name, tool.description, tool.input, async (args: Record<string, unknown>) => {
      let res: { ok: boolean; data?: unknown; error?: string };
      try {
        res = await control.call(tool.name, args);
      } catch (err) {
        res = {
          ok: false,
          error: `relay daemon unreachable at ${cfg.controlHost}:${cfg.controlPort} (is droidhand-relay running?): ${
            err instanceof Error ? err.message : String(err)
          }`,
        };
      }
      return {
        content: [{ type: "text" as const, text: JSON.stringify(res.ok ? res.data : { error: res.error }, null, 2) }],
        isError: !res.ok,
      };
    });
  }

  await server.connect(new StdioServerTransport());
}

main().catch((err) => {
  process.stderr.write(`[droidhand] fatal: ${err instanceof Error ? err.stack : String(err)}\n`);
  process.exit(1);
});
