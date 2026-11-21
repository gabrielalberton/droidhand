import { test } from "node:test";
import assert from "node:assert/strict";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";
import { CATALOG } from "../src/catalog.js";

/**
 * Boots the adapter entry point over stdio as Claude Code / Codex would (from
 * source via tsx, so no build is needed), lists its tools, and calls one with no
 * daemon running to confirm the error path.
 */
test("MCP server boots over stdio and exposes the tool catalog", async () => {
  const transport = new StdioClientTransport({
    command: "node",
    args: ["--import", "tsx", "src/index.ts"],
    env: {
      PATH: process.env.PATH ?? "",
      DROIDHAND_TOKEN: "smoke-token",
      DROIDHAND_CONTROL_HOST: "127.0.0.1",
      DROIDHAND_CONTROL_PORT: "18798", // no daemon here on purpose
    },
  });
  const client = new Client({ name: "smoke", version: "0.0.0" });
  await client.connect(transport);

  try {
    const { tools } = await client.listTools();
    const names = tools.map((t) => t.name);
    assert.ok(names.includes("tap"), "tap tool present");
    assert.ok(names.includes("suggest_feature"), "suggest_feature tool present");
    assert.ok(names.includes("bg_open"), "bg_open tool present");
    assert.ok(!names.includes("get_notifications"), "planned tools are not offered");
    assert.ok(names.includes("adb_session_begin"), "ADB sessions offered");
    assert.equal(tools.length, CATALOG.length, "every catalog tool is registered");

    // With no relay daemon running, a forwarded tool degrades gracefully (non-fatal error).
    const res = await client.callTool({ name: "tap", arguments: { x: 1, y: 1 } });
    assert.equal(res.isError, true);
    const text = (res.content as Array<{ type: string; text?: string }>)[0]?.text ?? "";
    assert.match(text, /unreachable/);
  } finally {
    await client.close();
  }
});
