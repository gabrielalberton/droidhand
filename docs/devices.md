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
| Own window (shell helper, needs ADB) | Yes | Yes since 2026-11-22. Before, every launch crashed Samsung's multi-window code: the display flags of Android 14 mean "hidden space display" on Samsung Android 13. |
| Debug text on the native window ("Overlay #1: …") | Always drawn; cannot be removed without root | Same |
| The native window closed by itself | Not seen | Seen once after moving an app out of it (2026-11-22); could not reproduce |

## Moving an app between the screens (`launch_app move=true`)

The background display has the phone screen's size and density by default, so the moved
app usually keeps running instead of being recreated.

| Feature | S24 Ultra | S20 |
|---|---|---|
| Main screen → background (same copy, not a new one) | Yes, not recreated (Calculator, 2026-11-21) | Yes. Native window: not recreated. Own window: recreated (touchscreen setting differs), Clock kept its running stopwatch |
| Background → main screen | Yes, not recreated (Calculator) | Yes |
| Background → main when the app is also open on the main screen | Untested | Without the helper Android picks the main-screen copy (wrong one). With the helper the exact task is moved (2026-11-22) |
| Background → main as a pop-up window (`bounds`) | Untested | With the helper: yes, not recreated. Without it: no (bounds ignored, app recreated) |
| Background → split screen | Untested | Untested |
| Closing the native window | Its apps move to the main screen, on top | Untested |

## Other

| Feature | S24 Ultra | S20 |
|---|---|---|
| `unlock_device` (saved PIN) | Yes (lock screen and always-on display) | Failed on 2026-11-22: the dismiss request did not open the PIN pad, and on the second try it stayed locked. Fix in testing: swipe up when the pad is missing, check each key in the PIN field and never press OK unless the count matches |
| Ask before acting on the main screen | Yes | Yes |
| App frozen by the system in the background | Not seen | Yes (Samsung "Freecess"). Fix: battery optimisation off for DroidHand. The relay now closes an unresponsive phone (code 4002) so it reconnects |
