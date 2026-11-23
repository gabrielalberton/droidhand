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

/**
 * Sent to agents at connect time (MCP `instructions`): where on the phone to work, so the
 * agent picks the background window first and the pop-up/split windows only when they help.
 */
export const SERVER_INSTRUCTIONS = [
  "DroidHand controls an Android phone that its owner keeps using. Where to work, in this order:",
  "1. Background window (bg_open): the default for any task of more than a step or two. mode=auto picks the own window when ADB can be " +
    "turned on (best: it never takes the user's focus or keyboard, and taps by x,y are safe), else the native window (no ADB, needs the " +
    "secure-settings permission; use refs there). Pass display=<id> to every call.",
  "2. Pop-up or split window on the main screen (launch_app popup=true / split=true, no ADB): only when the background window does not fit: " +
    "(a) there is none (no ADB and no secure-settings permission, or native_window_not_accessible) — if ADB can be turned on, use bg_open mode=own instead; " +
    "(b) the app refuses, goes black or jumps to the main screen on the background display (banks, protected video); " +
    "(c) the flow needs a system dialog (biometrics, payment confirmation, permission prompt): those appear on the main screen; " +
    "(d) the user must do the last step (buy, pay, send): prepare it in the background window, then show it to them in a pop-up. " +
    "Pop-up covers part of the screen and the user's app keeps the rest (no prompt); split gives each app half (the user is asked first). " +
    "Both need a phone with multi-window (Samsung One UI); check layout in the result ('full' = not supported, the app covers the user's screen).",
  "3. In a pop-up or split window pass window=<id or package> to every call and act by ref: a ref tap is an accessibility click and " +
    "type_text with a ref writes into that field, so the user's focus and keyboard stay where they are. x,y taps, swipes and long presses " +
    "there are real touches: they take the focus from the user's app and close their keyboard. back only works while that window is focused.",
  "4. window= also reads what the user has open side by side (list_windows, then screen_snapshot window=<id>), without touching anything.",
  "5. The main screen in full: only when the user asked; the phone asks them first.",
].join("\n");

/** Build an MCP server exposing the whole catalog, each tool forwarded through `opts.call`. */
export function createMcpServer(opts: McpServerOptions): McpServer {
  const server = new McpServer({ name: "droidhand", version: VERSION }, { instructions: SERVER_INSTRUCTIONS });
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
