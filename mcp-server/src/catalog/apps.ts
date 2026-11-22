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
        "('continue on the main screen', e.g. for the user to take over). With the shell helper running (own_helper_start) the exact task from the background display is moved and bounds turn it into a pop-up window; without it Android may pick the user's own copy if the app is also open on the main screen, and bounds are ignored. " +
        "The background display has the phone screen's size by default, so a moved app keeps its state instead of being recreated. " +
        "What works differs per phone: see docs/devices.md.",
      input: { package: z.string(), bounds: z.array(z.number()).length(4).optional(), move: z.boolean().optional(),
        split: z.boolean().optional().describe("experimental, with move=true display=0 and the shell helper: put the moved app in split screen next to the user's app") } },
    { name: "open_url", tier: "standard", confirm: false,
      description: "Open a URL in the default app for it. With display=<id> it opens on that background display, in the agent's own copy of the browser (the user's browser on the main screen is left alone).",
      input: { url: z.string().url() } },
    { name: "go_home", tier: "standard", confirm: false, description: "Go to the home screen (on a background display: the home key; not available on Android 13 and older).", input: {} },
  ],
};
