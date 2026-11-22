import { z } from "zod";
import type { ToolArea, ToolDef } from "./catalog/types.js";
import { perception } from "./catalog/perception.js";
import { interaction } from "./catalog/interaction.js";
import { apps } from "./catalog/apps.js";
import { device, lock } from "./catalog/device.js";
import { adb } from "./catalog/adb.js";
import { background } from "./catalog/background.js";
import { suggestions } from "./catalog/suggestions.js";
import { safety } from "./catalog/safety.js";

/**
 * Declarative tool catalog: the single list of MCP tools DroidHand exposes.
 *
 * Tools are grouped by area in `src/catalog/*.ts`. Tool names and input schemas
 * are part of the contract with the Android app — renaming a tool or changing an
 * argument breaks the phone side. `docs/tools.md` is generated from this list
 * (`npm run docs:tools`).
 */
export type { ToolArea, ToolDef } from "./catalog/types.js";

/** Argument every phone tool accepts to target a background display (0 = the user's screen, default). */
export const DISPLAY_PARAM = z.number().int().min(0).optional().describe(
  "display id (0 = phone screen; bg_open id = background). On 0, if the user is using the phone the app may ask them first: " +
    "errors 'awaiting_user' (retry shortly) or 'user_declined' (use bg_open instead)",
);

/** Argument every tool that acts on one phone accepts (several phones may share one relay). */
export const DEVICE_PARAM = z.string().optional().describe(
  "phone id (see list_devices); may be left out when only one phone is connected",
);

/** Tools that never act on one phone, so they take no `device`. */
const HOST_TOOLS = new Set(["list_devices", "get_audit_log", "list_suggestions", "export_suggestions"]);

/** Add `display` to tools forwarded to the phone (local tools have no display), and `device` to every per-phone tool. */
function withDisplayParam(t: ToolDef): ToolDef {
  let input = t.input;
  if (!t.local && !("display" in input)) input = { ...input, display: DISPLAY_PARAM };
  if (!HOST_TOOLS.has(t.name) && !("device" in input)) input = { ...input, device: DEVICE_PARAM };
  return input === t.input ? t : { ...t, input };
}

const RAW_AREAS: readonly ToolArea[] = [perception, interaction, apps, device, lock, adb, background, suggestions, safety];

/** Tool areas in catalog order with only the available tools (`display` applied); empty areas are dropped. */
export const CATALOG_AREAS: readonly ToolArea[] = RAW_AREAS
  .map((a) => ({ ...a, tools: a.tools.filter((t) => !t.planned).map(withDisplayParam) }))
  .filter((a) => a.tools.length > 0);

/** Every available tool, in the order they are registered with the MCP server. */
export const CATALOG: readonly ToolDef[] = CATALOG_AREAS.flatMap((a) => a.tools);

/** Designed but not implemented yet; never offered to agents (see the roadmap in docs/tools.md). */
export const PLANNED_TOOLS: readonly ToolDef[] = RAW_AREAS.flatMap((a) => a.tools.filter((t) => t.planned));

export const CATALOG_BY_NAME: ReadonlyMap<string, ToolDef> = new Map(CATALOG.map((t) => [t.name, t]));
