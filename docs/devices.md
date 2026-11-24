# What works on which phone

Tested on real phones. Android versions and Samsung's One UI differ in what they let an
app do, so a feature that works on one phone can fail on another. Each row says what
was seen, when, and how. "Untested" means nobody tried it yet, not that it fails.

| | Galaxy S24 Ultra | Galaxy S20 |
|---|---|---|
| Model / Android | SM-S928B, SDK 37 | SM-G980F, Android 13 (SDK 33), rooted (root is only used by the developer for tests) |
| Relay ADB mode | `on-demand` (debugging off between sessions) | `always` (test phone) |

## Background window

| Feature | S24 Ultra | S20 |
|---|---|---|
| Native window opens (no ADB) | Yes | Yes |
| Apps on the native window | Full screen (`own_content_only`) | Pop-up (freeform) windows even with `own_content_only` |
| Agent reads/touches the native window (accessibility) | Yes | **No.** Android 13 leaves overlay displays out of accessibility (`dumpsys accessibility` lists display 0 only). Only `screen_capture` works; the tools answer `native_window_not_accessible`. Use the own window. |
| Own window (shell helper, needs ADB) | Yes (re-checked 2026-11-22 with the new build: launch + snapshot) | Yes since 2026-11-22. Before, every launch crashed Samsung's multi-window code: the display flags of Android 14 mean "hidden space display" on Samsung Android 13. |
| Debug text on the native window ("Overlay #1: …") | Always drawn; cannot be removed without root | Same |
| The native window closed by itself | Not seen | Seen once after moving an app out of it (2026-11-22); not reproduced in 2 more tries |

## Moving an app between the screens (`launch_app move=true`)

Whether the moved app keeps running depends on the Android version, not on the window size.
Background displays (native and own) tell apps they have no touchscreen; the main screen has one.
Android 13 restarts the activity when that changes, newer Android does not. A taller display that
gives the app exactly the phone's size (tested 2026-11-23) does not change this.

The relay turns this table into `rules` in `list_devices` and `get_device_info`
(`mcp-server/src/core/deviceRules.ts`). Versions nobody tested get the cautious rule.

| Feature | S24 Ultra | S20 |
|---|---|---|
| Main screen → background (same copy, not a new one) | Yes, not recreated (Calculator, 2026-11-21) | Yes. Native window: not recreated. Own window: recreated (touchscreen setting differs), Clock kept its running stopwatch |
| Background → main screen | Yes, not recreated (Calculator; Settings and Calendar with and without a band, 2026-11-23) | Yes, but **recreated**: 6 of 6 apps (Settings, Clock, Calendar, My Files, Messages, Contacts), native and own window, with and without a band (2026-11-23). Text in a plain field survived; web forms would not |
| Background → main when the app is also open on the main screen | Untested | Without the helper Android picks the main-screen copy (wrong one). With the helper the exact task is moved (2026-11-22) |
| Background → main as a pop-up window (`bounds`) | Untested | With the helper: yes, not recreated. Without it: no (bounds ignored, app recreated) |
| Background → split screen (`split=true`, experimental, needs the helper) | Untested | Yes: the exact task goes to the top half, recreated (own window) but the stopwatch kept its state. The accessibility "toggle split screen" action fails on One UI 5, and an intent with LAUNCH_ADJACENT always opens a new copy. One UI pulls older copies of the same app into the bottom half; the helper puts the user's app back on top (fixed 2026-11-22). Known issue: the bottom half may get the wrong app when the user's top task is translucent |
| Moved back without `bounds` | Full screen | Full screen (the helper turns the freeform task full screen) |
| Closing the native window | Its apps move to the main screen, on top | Same: on top, full screen |

## Every tool on the S20 (2026-11-22)

All 37 tools were run on the S20 on the main screen, the native window and the own window.

| Area | Result |
|---|---|
| Perception, interaction, settings, apps, lock, ADB, suggestions, audit | Work. `set_setting` refuses keys outside the allowlist |
| Main screen | Work after the prompt. A tap under the Stop button cancelled the agent's own action (fixed: the button moves away first) |
| Native window | `launch_app`, `screen_capture`, `open_url`, `bg_status`/`bg_show` work; screen tools answer `native_window_not_accessible` (Android 13). `bg_hide` is own-window only |
| Own window | Everything works, taps as mouse clicks. Keys: back and enter work; home does nothing on Android 13 (now refused); recents opened on the main screen (now refused). Apps open with a caption bar (freeform). After moving the only app out, the window keeps showing its last frame |
| Fixed during the run | `global_action`/`go_home`/`open_url` with a background display acted on the main screen without asking; a native window left over after an app update made `bg_open` fail; Stop button drawn under the own window; second screenshot within 1 s failed |
| `panic_disconnect` | In this run it stopped the app and turned debugging off even on the `always` test phone, so the phone had to be reopened by hand. Fixed: the agent's panic no longer switches the app off and leaves debugging to the phone's adb mode; the phone reconnects by itself |

## Other

| Feature | S24 Ultra | S20 |
|---|---|---|
| `unlock_device` (saved PIN) | Yes (lock screen and always-on display) | Yes since 2026-11-22 (3/3 from screen off, ~5.5 s). Before: the dismiss request did not open the PIN pad. Now it swipes up when the pad is missing, checks each key in the PIN field and never presses OK unless the count matches |
| Ask before acting on the main screen | Yes | Yes |
| App frozen by the system in the background | Not seen | Yes (Samsung "Freecess"). Fix: battery optimisation off for DroidHand. The relay now closes an unresponsive phone (code 4002) so it reconnects |
