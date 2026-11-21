import { z } from "zod";
import type { ToolArea } from "./types.js";

export const perception: ToolArea = {
  id: "perception",
  title: "Perception",
  summary: "Read what is on screen and on the device.",
  tools: [
    { name: "screen_snapshot", tier: "standard", confirm: false,
      description: "Accessibility tree of the foreground app with stable @refs, roles, text and bounds.",
      input: { detail: z.enum(["compact", "full"]).optional() } },
    { name: "screen_capture", tier: "standard", confirm: false,
      description: "PNG screenshot of the current screen (base64).",
      input: { quality: z.number().min(1).max(100).optional() } },
    { name: "get_foreground_app", tier: "standard", confirm: false,
      description: "Package and activity currently in the foreground.", input: {} },
    { name: "list_windows", tier: "standard", confirm: false,
      description: "Windows on screen as the accessibility service sees them (type, layer, active/focused, package).", input: {} },
    { name: "get_notifications", tier: "standard", confirm: false,
      description: "List active notifications with refs and actions.",
      input: { package: z.string().optional() } },
    { name: "list_apps", tier: "standard", confirm: false,
      description: "Installed launchable apps (personal profile).",
      input: { includeSystem: z.boolean().optional() } },
  ],
};
