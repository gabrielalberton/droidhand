import { z } from "zod";
import type { ToolArea } from "./types.js";

export const background: ToolArea = {
  id: "background",
  title: "Background display",
  summary: "Work on a separate display while the user keeps using the phone.",
  tools: [
    { name: "bg_open", tier: "standard", confirm: false,
      description:
        "Open a background display and return its displayId; then pass display=<id> to launch_app, screen_snapshot, " +
        "screen_capture, tap, swipe, scroll, type_text, key_event. mode: 'native' (system window, works with debugging OFF but needs the secure-settings permission, " +
        "cannot be hidden), 'own' (our window: hideable, resizable, user can interact; needs own_helper_start, i.e. an ADB session), " +
        "'auto' (default: the own window whenever ADB can be turned on — the relay opens an ADB session just for it and turns " +
        "debugging off at bg_close — else native. Prefer it: the own window never takes the focus from the user's screen, so " +
        "their keyboard stays; on the native window any touch or new screen closes it, so use refs there). The user's choice in the app overrides mode (see forcedByUser). " +
        "Only while the phone is unlocked (Android pauses background apps behind the lock screen: unlock_device first). " +
        "Safety: work in the background only happens while the user can see it (stop button + window, eye bubble or handle). " +
        "If those cannot be shown, the session is closed and calls fail with 'indicators_not_visible' — do not retry in a loop. " +
        "width/height/dpi default to the phone screen's, so apps can move between the screens (launch_app move=true) without being recreated.",
      input: { mode: z.enum(["auto", "native", "own"]).optional(), width: z.number().optional(),
        height: z.number().optional(), dpi: z.number().optional() } },
    { name: "bg_status", tier: "standard", confirm: false,
      description: "Background display state: mode, displayId, hidden, helpPending, user's method choice, own helper availability.", input: {} },
    { name: "bg_hide", tier: "standard", confirm: false,
      description: "Hide the own-window background display (the agent keeps working; the user sees an eye bubble).", input: {} },
    { name: "bg_show", tier: "standard", confirm: false, description: "Show the background window again.", input: {} },
    { name: "bg_close", tier: "standard", confirm: false,
      description: "Close the background display (own: its apps are closed; native: Android moves them to the main screen, on Samsung on top of the user's app — move or close them first).", input: {} },
    { name: "request_user_help", tier: "standard", confirm: false,
      description: "Ask the user to take over in the background window (e.g. captcha, login). Shows the window with the message; " +
        "poll bg_status until helpPending is null (the user taps the message when done).",
      input: { message: z.string() } },
    { name: "own_helper_start", tier: "privileged", confirm: false, local: true,
      description: "Start the shell helper over ADB (opens an ADB session if none is open; see adb_session_begin). " +
        "Enables the 'own' background-window method. The helper dies when the ADB session ends (debugging off): " +
        "call adb_session_end when the background task is done.",
      input: {} },
    { name: "own_helper_stop", tier: "standard", confirm: false, local: true,
      description: "Stop the shell helper (closes own-window background displays).", input: {} },
  ],
};
