import { test } from "node:test";
import assert from "node:assert/strict";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { InMemoryTransport } from "@modelcontextprotocol/sdk/inMemory.js";
import { CATALOG } from "../src/catalog.js";
import { createMcpServer, toCallToolResult, type ToolCaller } from "../src/mcp/server.js";

test("toCallToolResult: screenshots become image content", () => {
  const res = toCallToolResult("screen_capture", { ok: true, data: { data: "AAAA", mimeType: "image/png", width: 10, height: 20 } });
  assert.deepEqual(res, {
    content: [
      { type: "image", data: "AAAA", mimeType: "image/png" },
      { type: "text", text: '{"width":10,"height":20}' },
    ],
  });
  // Missing mimeType defaults to JPEG.
  const jpeg = toCallToolResult("screen_capture", { ok: true, data: { data: "BB" } });
  assert.equal((jpeg.content[0] as { mimeType: string }).mimeType, "image/jpeg");
});

test("toCallToolResult: other results are pretty JSON; errors set isError", () => {
  assert.deepEqual(toCallToolResult("tap", { ok: true, data: { a: 1 } }), {
    content: [{ type: "text", text: '{\n  "a": 1\n}' }],
    isError: false,
  });
  assert.deepEqual(toCallToolResult("screen_capture", { ok: false, error: "nope" }), {
    content: [{ type: "text", text: '{\n  "error": "nope"\n}' }],
    isError: true,
  });
});

async function connect(call: ToolCaller): Promise<Client> {
  const server = createMcpServer({ call, unreachableMessage: "relay daemon unreachable" });
  const [clientT, serverT] = InMemoryTransport.createLinkedPair();
  await server.connect(serverT);
  const client = new Client({ name: "test", version: "0.0.0" });
  await client.connect(clientT);
  return client;
}

test("MCP server lists every catalog tool with its schema", async () => {
  const client = await connect(async () => ({ ok: true }));
  try {
    const { tools } = await client.listTools();
    assert.deepEqual(tools.map((t) => t.name), CATALOG.map((t) => t.name));
    for (const def of CATALOG) {
      const listed = tools.find((t) => t.name === def.name);
      assert.ok(listed);
      assert.equal(listed.description, def.description);
      const props = Object.keys(listed.inputSchema.properties ?? {});
      assert.deepEqual(props.sort(), Object.keys(def.input).sort(), `${def.name} input properties`);
      assert.equal(props.includes("display"), !def.local, `${def.name} display param`);
    }
  } finally {
    await client.close();
  }
});

test("MCP calls are forwarded with their args; a throwing caller yields a non-fatal error", async () => {
  const seen: Array<[string, Record<string, unknown>]> = [];
  let fail = false;
  const client = await connect(async (tool, args) => {
    if (fail) throw new Error("ECONNREFUSED");
    seen.push([tool, args]);
    return { ok: true, data: { echoed: tool } };
  });
  try {
    const ok = await client.callTool({ name: "tap", arguments: { x: 1, y: 2, display: 3 } });
    assert.equal(ok.isError, false);
    assert.deepEqual(seen, [["tap", { x: 1, y: 2, display: 3 }]]);

    fail = true;
    const bad = await client.callTool({ name: "go_home", arguments: {} });
    assert.equal(bad.isError, true);
    const text = (bad.content as Array<{ text?: string }>)[0]?.text ?? "";
    assert.deepEqual(JSON.parse(text), { error: "relay daemon unreachable: ECONNREFUSED" });

    // Invalid args are rejected by the input schema before reaching the caller.
    fail = false;
    const invalid = await client.callTool({ name: "scroll", arguments: { direction: "sideways" } });
    assert.equal(invalid.isError, true);
    assert.equal(seen.length, 1);
  } finally {
    await client.close();
  }
});
