import { z } from "zod";
import { ref, type ToolArea } from "./types.js";

export const interaction: ToolArea = {
  id: "interaction",
  title: "Interaction",
  summary: "Touch, type and press keys through the accessibility service.",
  tools: [
    { name: "tap", tier: "standard", confirm: false,
      description: "Tap an element by ref, or raw x,y.",
      input: { ref: ref.optional(), x: z.number().optional(), y: z.number().optional() } },
    { name: "long_press", tier: "standard", confirm: false,
      description: "Long-press an element or point.",
      input: { ref: ref.optional(), x: z.number().optional(), y: z.number().optional(), durationMs: z.number().optional() } },
    { name: "swipe", tier: "standard", confirm: false,
      description: "Swipe from one point to another.",
      input: { fromX: z.number(), fromY: z.number(), toX: z.number(), toY: z.number(), durationMs: z.number().optional() } },
    { name: "scroll", tier: "standard", confirm: false,
      description: "Scroll the screen in a direction (a swipe across its middle; 'down' reveals what is below).",
      input: { direction: z.enum(["up", "down", "left", "right"]) } },
    { name: "type_text", tier: "standard", confirm: false,
      description: "Type text into the focused field (or a ref), optionally pressing enter.",
      input: { text: z.string(), ref: ref.optional(), submit: z.boolean().optional() } },
    { name: "key_event", tier: "standard", confirm: false,
      description: "Press a navigation key. On an own-window background display only back, home, recents and enter are available.",
      input: { key: z.enum(["back", "home", "recents", "notifications", "quick_settings", "enter"]) } },
    { name: "global_action", tier: "standard", confirm: false,
      description: "Perform a global accessibility action.",
      input: { action: z.enum(["notifications", "quick_settings", "screenshot", "lock_screen"]) } },
  ],
};
