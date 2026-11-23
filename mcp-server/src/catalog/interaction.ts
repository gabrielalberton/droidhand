import { z } from "zod";
import { ref, windowParam, type ToolArea } from "./types.js";

export const interaction: ToolArea = {
  id: "interaction",
  title: "Interaction",
  summary: "Touch, type and press keys through the accessibility service.",
  tools: [
    { name: "tap", tier: "standard", confirm: false,
      description: "Tap an element by ref, or raw x,y. On a background display or with window=, a ref is an accessibility click (no touch: the user keeps the focus); " +
        "x,y is a real touch, which on the main screen takes the focus from the user's app and closes their keyboard.",
      input: { ref: ref.optional(), x: z.number().optional(), y: z.number().optional(), window: windowParam } },
    { name: "long_press", tier: "standard", confirm: false,
      description: "Long-press an element or point.",
      input: { ref: ref.optional(), x: z.number().optional(), y: z.number().optional(), durationMs: z.number().optional(), window: windowParam } },
    { name: "swipe", tier: "standard", confirm: false,
      description: "Swipe from one point to another.",
      input: { fromX: z.number(), fromY: z.number(), toX: z.number(), toY: z.number(), durationMs: z.number().optional(), window: windowParam } },
    { name: "scroll", tier: "standard", confirm: false,
      description: "Scroll in a direction ('down' reveals what is below). On a background display or with window= it scrolls through accessibility first " +
        "(no touch), else a swipe across the middle of the screen or of that window.",
      input: { direction: z.enum(["up", "down", "left", "right"]), window: windowParam } },
    { name: "type_text", tier: "standard", confirm: false,
      description: "Type text into the focused field (or a ref), optionally pressing enter. On a background display or with window= " +
        "give the field's ref: the text goes straight into it, without touching, and never into the user's own field.",
      input: { text: z.string(), ref: ref.optional(), submit: z.boolean().optional(), window: windowParam } },
    { name: "key_event", tier: "standard", confirm: false,
      description: "Press a navigation key. On an own-window background display only back, enter and (Android 14+) home; recents is refused there because it opens on the user's main screen.",
      input: { key: z.enum(["back", "home", "recents", "notifications", "quick_settings", "enter"]), window: windowParam } },
    { name: "global_action", tier: "standard", confirm: false,
      description: "Perform a global accessibility action on the main screen (refused with display=<background id>). split_screen toggles split screen with the app on top of the main screen " +
        "(e.g. after launch_app move=true display=0, so the user continues it next to their own app).",
      input: { action: z.enum(["notifications", "quick_settings", "screenshot", "lock_screen", "split_screen"]) } },
  ],
};
