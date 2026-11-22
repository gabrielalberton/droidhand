import { z } from "zod";
import type { ToolArea } from "./types.js";

export const apps: ToolArea = {
  id: "apps",
  title: "Apps & navigation",
  summary: "Launch apps, open links and go home.",
  tools: [
    { name: "launch_app", tier: "standard", confirm: false,
      description: "Launch an app by package name (display=<id> for a background display; bounds=[l,t,r,b] px opens a One UI pop-up window on the main screen). " +
        "On a background display it opens a separate copy, so the user's own copy stays on their screen. " +
        "move=true reuses the app's existing task instead and moves it: with display=<id> it takes the user's copy to the background display " +
        "(only when the user asked: it leaves their screen); with display=0 it brings the copy from the background display to the main screen " +
        "('continue on the main screen', e.g. for the user to take over; full screen: bounds are ignored for a moved app). " +
        "The background display has the phone screen's size by default, so a moved app keeps its state instead of being recreated. " +
        "If the app has several tasks, Android picks the one used last.",
      input: { package: z.string(), bounds: z.array(z.number()).length(4).optional(), move: z.boolean().optional() } },
    { name: "open_url", tier: "standard", confirm: false,
      description: "Open a URL in the default app for it, on the phone screen. For a background display, launch the browser there and type the URL.",
      input: { url: z.string().url() } },
    { name: "go_home", tier: "standard", confirm: false, description: "Go to the home screen.", input: {} },
  ],
};
