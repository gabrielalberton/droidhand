import { z, type ZodRawShape } from "zod";
import type { Tier } from "../protocol.js";

/**
 * One MCP tool. Most tools are forwarded verbatim to the phone; `local` ones are
 * handled by the relay daemon itself (see `core/core.ts`).
 */
export interface ToolDef {
  /** MCP tool name; also the `tool` field of the wire `cmd` frame. Never rename. */
  readonly name: string;
  readonly tier: Tier;
  /** The app asks the user for an on-device confirmation tap before acting; the daemon audits the requirement. */
  readonly confirm: boolean;
  /** Handled on the host by the daemon (true) vs forwarded to the phone (false/undefined). */
  readonly local?: boolean;
  readonly description: string;
  /** zod shape of the tool arguments, exposed to agents as the MCP input schema. */
  readonly input: ZodRawShape;
}

/** A group of related tools; used for source layout and the generated docs. */
export interface ToolArea {
  readonly id: string;
  readonly title: string;
  readonly summary: string;
  readonly tools: readonly ToolDef[];
}

/** Stable element reference returned by `screen_snapshot`. */
export const ref = z.string().describe("stable element ref from screen_snapshot, e.g. '@4'");

/** Settings namespaces accepted by get_setting / set_setting. */
export const settingNamespace = z.enum(["system", "secure", "global"]);
