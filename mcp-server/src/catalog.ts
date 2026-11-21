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

/** Add the `display` argument to tools forwarded to the phone (local tools have no display). */
function withDisplayParam(t: ToolDef): ToolDef {
  if (t.local || "display" in t.input) return t;
  return { ...t, input: { ...t.input, display: DISPLAY_PARAM } };
}

const RAW_AREAS: readonly ToolArea[] = [perception, interaction, apps, device, lock, adb, background, suggestions, safety];

/** Tool areas in catalog order, with the `display` argument already applied. */
export const CATALOG_AREAS: readonly ToolArea[] = RAW_AREAS.map((a) => ({ ...a, tools: a.tools.map(withDisplayParam) }));

/** Every tool, in the order they are registered with the MCP server. */
export const CATALOG: readonly ToolDef[] = CATALOG_AREAS.flatMap((a) => a.tools);

export const CATALOG_BY_NAME: ReadonlyMap<string, ToolDef> = new Map(CATALOG.map((t) => [t.name, t]));
