import { z } from "zod";
import type { ToolArea } from "./types.js";

export const perception: ToolArea = {
  id: "perception",
  title: "Perception",
  summary: "Read what is on screen and on the device.",
  tools: [
    { name: "screen_snapshot", tier: "standard", confirm: false,
      description: "Accessibility tree of the foreground window: up to 300 visible nodes, each with a @ref (for tap/type_text), " +
        "role, text or description, resource id, center x,y and clickable/editable/checked/scrollable flags. Passwords are masked.",
      input: {} },
    { name: "screen_capture", tier: "standard", confirm: false,
      description: "JPEG screenshot of the current screen (base64), at most 720 px wide. Secure screens (e.g. the PIN pad) come out black.",
      input: { quality: z.number().min(1).max(100).optional() } },
    { name: "get_foreground_app", tier: "standard", confirm: false,
      description: "Package name of the app in the foreground.", input: {} },
    { name: "list_windows", tier: "standard", confirm: false,
      description: "Windows on screen as the accessibility service sees them (type, layer, active/focused, package).", input: {} },
    { name: "get_notifications", tier: "standard", confirm: false, planned: true,
      description: "List active notifications with refs and actions.",
      input: { package: z.string().optional() } },
    { name: "list_apps", tier: "standard", confirm: false,
      description: "Launchable apps (package and label), sorted by label.",
      input: {} },
  ],
};
