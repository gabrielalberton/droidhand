# Locked / screen-off capabilities (device-tested)

Galaxy S24 Ultra (SM-S928B), Android 17 / One UI, DroidHand `7a53fcb`. Tested 2026-11-21 05:00–05:08 (São Paulo),
battery 24 % (not charging), secure lock (PIN), Always On Display **on**, DroidHand battery-unrestricted
(standby bucket 5 = EXEMPTED). No PIN was entered; automatic unlock was off the whole time.

States observed (`dumpsys power` / `dumpsys window` / `deviceidle`):

| State | How | Observed |
|---|---|---|
| **AOD + deep doze** | phone left alone overnight | `Dozing`, keyguard showing, `deep=IDLE` |
| **Lock screen awake** | agent `key_event home` (or ADB `KEYCODE_WAKEUP`) | `Awake`, keyguard showing |
| Screen fully off | — | not reachable: AOD is on, so "off" = AOD (`Dozing`) |

## Matrix

| Capability | AOD + deep doze | Lock screen awake | Notes / evidence |
|---|---|---|---|
| Relay connection | ✅ 10–40 ms | ✅ | Phone reconnected alone after the NUC reboot (01:11); no drops seen in deep doze |
| `get_device_info`, `get_lock_state`, `list_apps` | ✅ | ✅ | |
| `get_setting` / `set_setting` | ✅ | ✅ | `window_animation_scale` 1.0→0.5→1.0 ok |
| `adb_wifi_enabled` 0→1 by the agent | ✅ | ✅ | ADB back via mDNS in ~6 s |
| `screen_snapshot` (display 0) | ⚠️ AOD only | ⚠️ lock screen only | AOD: clock/notification count; lock screen: notifications (WhatsApp "128 messages from 7 chats"), shade |
| `screen_capture` (display 0) | ⚠️ AOD image | ⚠️ lock screen | PIN bouncer captures **black** (secure window) |
| `get_notifications` | ❌ | ❌ | not implemented (any state) |
| tap / swipe / scroll (display 0) | ⚠️ "ok" but no effect | ⚠️ acts on the lock screen only | gestures on AOD are reported as completed but change nothing |
| `type_text` (display 0) | ❌ | ❌ | "no focused text field" |
| `launch_app` / `open_url` (display 0) | ❌ hidden | ❌ hidden | Activity resumes **behind** the keyguard; a11y sees only SystemUI |
| `key_event home` / `go_home` | ✅ **wakes the screen** | ✅ | stays on the lock screen; no unlock |
| `key_event back/recents` | no effect | ✅ (closes shade/bouncer) | |
| `global_action notifications` | ⚠️ | ✅ opens shade on lock screen | quick settings visible on the lock screen |
| `global_action lock_screen` | ✅ | ✅ puts it back to AOD | |
| MainScreenGate prompt | ✅ never shown | ✅ never shown | correct: gate only asks when unlocked |
| PIN bouncer via a11y | — | ✅ readable/actionable | after a swipe up: "Inserir PIN", keys `1`–`9`,`0` as ViewGroups with labels, "Caixa de edição de PIN" → `Unlocker` can find them |
| `unlock_device` (feature off) | ✅ refuses | ✅ refuses | `unlock_disabled`, no wake |
| `unlock_device` (feature on, PIN saved) | ✅ ~2.9 s | ✅ ~3.1 s | tested 2026-11-21 09:5x after fix 611e878 (USE_BIOMETRIC): unlocked from AOD and from the awake lock screen, app underneath resumed |
| **Native background window** | ❌ | ❌ | overlay display shows the **secondary lock-screen clock**; app behind it; snapshot "no active window" |
| **Own background window** (helper) | ✅ **works** | ✅ **works** | display `FLAG_ALWAYS_UNLOCKED` + `OWN_DISPLAY_GROUP`: Calculator, **Settings** and **Chrome** launched, snapshot, taps (1+2=… / C,7) and `type_text` into Chrome all worked while locked and in deep doze, window hidden or shown |
| Own window `screen_capture` | ⚠️ | ✅ | black while the display is `DOZE_SUSPEND` and hidden; real frame when awake |
| Own-window helper vs ADB Wi-Fi toggle | ✅ survives | ✅ | helper kept `ownAvailable=true` after `adb_wifi_enabled` 0→1 (only turning off *all* debugging kills it) |
| `open_url` with `display=<own>` | ⚠️ | ⚠️ | returned ok but Chrome did not open there (use `launch_app com.android.chrome` + type the URL) |
| Stop button, agent idle | — | ✅ | next call → `stopped_by_user`, following call ok, no re-enable needed |
| Stop button **during** a long gesture | — | ❌ | `adb input tap` on the button during a 6 s swipe was not delivered (no toast, call returned ok) — twice |
| ADB shell / `screencap` / `input` | ✅ | ✅ | screencap = what the panel shows (AOD/lock screen) |

## What this means

- **Main screen + secure lock = no apps.** Without unlocking, the agent only reaches the lock screen,
  notifications (summaries), quick settings and settings writes. Launching apps works but stays hidden.
- **The own background window bypasses the keyguard** (system "always unlocked" display, created by the
  shell helper): the agent can run and drive apps, including Settings and Chrome, while the phone is
  locked and asleep. This is the main workaround — and a security property to document for users: whoever
  controls the agent + ADB helper can use apps without the PIN.
- The own window's overlay is drawn **over the lock screen and AOD** unless hidden (visible to anyone holding the phone).
- The agent can **wake** the screen (home) and **re-lock** it (`lock_screen`) without ADB.

## Proposals (not implemented)

1. `bg_open` while locked → default to *hidden* (privacy on the lock screen) and prefer `own` when the helper runs.
2. Stop button: check a stop flag between gesture segments / split long gestures, so Stop works mid-gesture;
   re-test with a real finger (ADB `input` may not represent it).
3. `open_url` on a background display: launch with `FLAG_ACTIVITY_NEW_TASK | MULTIPLE_TASK` and `setLaunchDisplayId`, verify.
4. Implement `get_notifications` (NotificationListenerService) — useful while locked.
5. `get_lock_state` could expose AOD/doze (`Dozing`) to let the agent decide (wake vs. own window vs. ask user).
6. Relay: log phone connect/disconnect with timestamps (currently silent) for overnight diagnostics.
7. Gestures on AOD report success with no effect — return `screen_off` instead so the agent knows.
