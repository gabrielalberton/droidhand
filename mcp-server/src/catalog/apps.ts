import { z } from "zod";
import type { ToolArea } from "./types.js";

export const apps: ToolArea = {
  id: "apps",
  title: "Apps & navigation",
  summary: "Launch apps, open links and go home.",
  tools: [
    { name: "launch_app", tier: "standard", confirm: false,
      description: "Launch an app by package name (display=<id> for a background display; bounds=[l,t,r,b] px opens a One UI pop-up window on the main screen).",
      input: { package: z.string(), bounds: z.array(z.number()).length(4).optional() } },
    { name: "open_url", tier: "standard", confirm: false,
      description: "Open a URL in the default browser.", input: { url: z.string().url() } },
    { name: "go_home", tier: "standard", confirm: false, description: "Go to the home screen.", input: {} },
  ],
};
