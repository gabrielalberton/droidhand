import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import type { CallToolResult } from "@modelcontextprotocol/sdk/types.js";
import { CATALOG } from "../catalog.js";
import type { ToolResult } from "../core/core.js";
import { VERSION } from "../version.js";

/** Runs one tool call somewhere (normally `ControlClient.call`); may throw if unreachable. */
export type ToolCaller = (tool: string, args: Record<string, unknown>) => Promise<ToolResult>;

export interface McpServerOptions {
  call: ToolCaller;
  /** Prefix of the error returned when `call` throws, e.g. which daemon address was tried. */
  unreachableMessage: string;
}

function isRecord(v: unknown): v is Record<string, unknown> {
  return typeof v === "object" && v !== null;
}

/**
 * Convert a tool result to MCP content. Screenshots become real MCP image
 * content (plus a small JSON text with the dimensions); everything else is
 * pretty-printed JSON, with `isError` set on failure.
 */
export function toCallToolResult(tool: string, res: ToolResult): CallToolResult {
  const img = isRecord(res.data) ? res.data : undefined;
  if (res.ok && tool === "screen_capture" && typeof img?.data === "string") {
    return {
      content: [
        { type: "image", data: img.data, mimeType: String(img.mimeType ?? "image/jpeg") },
        { type: "text", text: JSON.stringify({ width: img.width, height: img.height }) },
      ],
    };
  }
  return {
    // Tools that return no data (e.g. go_home) still need a text block: undefined is not JSON.
    content: [{ type: "text", text: JSON.stringify(res.ok ? (res.data ?? { ok: true }) : { error: res.error }, null, 2) }],
    isError: !res.ok,
  };
}

/** Build an MCP server exposing the whole catalog, each tool forwarded through `opts.call`. */
export function createMcpServer(opts: McpServerOptions): McpServer {
  const server = new McpServer({ name: "droidhand", version: VERSION });
  for (const tool of CATALOG) {
    server.registerTool(tool.name, { description: tool.description, inputSchema: tool.input }, async (args: Record<string, unknown>) => {
      let res: ToolResult;
      try {
        res = await opts.call(tool.name, args);
      } catch (err) {
        res = { ok: false, error: `${opts.unreachableMessage}: ${err instanceof Error ? err.message : String(err)}` };
      }
      return toCallToolResult(tool.name, res);
    });
  }
  return server;
}
