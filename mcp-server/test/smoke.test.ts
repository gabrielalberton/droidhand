import { test } from "node:test";
import assert from "node:assert/strict";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";

/**
 * Boots the built server over stdio exactly as Claude Code / Codex would, lists
 * its tools, and calls one with no phone connected to confirm the error path.
 * Requires `npm run build` first (uses dist/index.js).
 */
test("MCP server boots over stdio and exposes the tool catalog", async () => {
  const transport = new StdioClientTransport({
    command: "node",
    args: ["dist/index.js"],
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
    assert.ok(names.includes("adb_session_begin"), "adb_session_begin tool present");
    assert.ok(tools.length >= 20, `expected a full catalog, got ${tools.length}`);

    // With no relay daemon running, a forwarded tool degrades gracefully (non-fatal error).
    const res = await client.callTool({ name: "tap", arguments: { x: 1, y: 1 } });
    assert.equal(res.isError, true);
    const text = (res.content as Array<{ type: string; text?: string }>)[0]?.text ?? "";
    assert.match(text, /unreachable/);
  } finally {
    await client.close();
  }
});
