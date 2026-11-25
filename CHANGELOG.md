# Changelog

All notable changes to this project are documented here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses [Semantic Versioning](https://semver.org/).

## [Unreleased]

## [1.2.5] - 2026-11-25

### App (Android)
- **Quick controls on the home screen**: two switches, one for the accessibility service and one for USB debugging. With the secure-settings grant the accessibility switch flips right there (without it, it opens Android's screen); the debugging switch turns ADB on for 10 minutes ("On until HH:mm") and off again by itself. Handy for apps that refuse to open while accessibility is on.
- **Check for updates button** in the header, with the outcome as a toast. The app also looks for a new version every time it comes to the front (at most once a minute) and remembers the last release it found, so the update card shows at once instead of after the network answers.
- **Optional ADB fallback for PIN unlock** (Android 14+): with accessibility off, `unlock_device` can type the saved PIN through a phone-side helper started over ADB. It needs its own acknowledgement in *Privacy & safety*; the relay never sees the PIN.
- **PIN unlock could spend wrong attempts** (seen on a Galaxy S24 FE, One UI 8): the PIN field was read from a stale accessibility cache, so a digit that had registered looked missing, the key was tapped again, and One UI checked the too-long PIN by itself. The field is now read fresh, a key gets at most one extra tap, and if the field empties by itself (the lock screen checked it) automatic unlock turns off at once. Leftover digits are cleared (with real taps, checked) before typing.
- Background windows refuse with `phone_locked` while the phone is locked: Android pauses their apps behind the lock screen, so they stayed black while the tools reported success.

## [1.2.3] - 2026-11-23

### App (Android)
- Pair from the app: a card at the top of the screen, above Settings, shows "pairing with the computer…" with a timer from the moment the code leaves until the result, then the result. Leaving the pairing screen too early is what made the first try fail.
- The pairing code leaves at once: wireless debugging's connect port comes from its own screen (behind the pairing dialog) or the system property. Before, the app scanned 30 000 local ports first, which took up to 30 s on a Galaxy S24 FE. The scan is now only the last resort and bounded to 10 s.

### Relay
- ADB sessions try the phone's tailnet and Wi-Fi addresses at once, so a phone on someone else's tailnet (unreachable on its tailnet IP) works on the same Wi-Fi. Before, the session failed after waiting on the tailnet IP.
- Wireless debugging's connect port is looked up with adb's mDNS first (pairing and the first session after a reboot); asking the phone is the fallback.

## [1.2.2] - 2026-11-23

Found testing a second phone (Galaxy S24 FE, not rooted, on another tailnet).

### App (Android)
- After `unlock_device` the agent no longer hits the main-screen prompt right away: an unlock done by the agent counts as approval until the screen turns off or the agent is idle for 3 minutes. Before, a phone unlocked by the agent asked whoever was not holding it, so nothing on the main screen could run.
- `bg_open` without the secure-settings permission says so (and how to grant it) instead of a raw `SecurityException`. `set_setting` and `adb_session_begin` now point to **Pair from the app** too.

### Relay
- ADB pairing tries every address at once. A phone on someone else's tailnet never answers on its tailnet IP (node sharing is one-way), so its Wi-Fi address no longer waits 12 s behind it.

## [1.2.1] - 2026-11-23

### App (Android)
- Pair ADB from the app: the Start button is no longer silently grey; it says what is missing (connection to the computer, accessibility, developer options) and offers the way there.
- Home: after 15 s of "Connecting…" the card says the phone is probably not reaching the computer (Tailscale off, another tailnet).

## [1.2.0] - 2026-11-23

### Setup without the computer at hand
- **Setup link + QR code in the terminal**: `deploy/setup.sh` ends by printing a one-time link (`http://<tailnet-ip>:8787/setup/<code>`) and its QR code; `npm run invite` (`droidhand-invite`) makes more, also for a new phone (`--id`, `--name`, `--admin`). The page, served by the relay on its own port, has the download link, an **Open in DroidHand** button (`droidhand://connect#…`, confirmed in the app before use) and the steps. The link works once: it is spent when that phone connects, on relay restart, or after 24 h. **Connection → Scan QR code** also reads it.
- `setup.sh` serves the relay port on the tailnet with `tailscale serve` when it can.
- **Pair ADB from the app** (Permissions → Secure settings → Pair from the app): the accessibility service reads the code and port from Android's pairing dialog and sends them to the relay (`adb_pair`), which pairs, grants the secure-settings permission and arms ADB on demand; the app turns debugging off afterwards. Typing the code by hand also works.

## [1.1.0] - 2026-11-22

### Relay + MCP server
- **Several phones on one relay**, each with its own settings in `~/.config/droidhand/devices.json`: token (which also identifies the phone), name, role and ADB mode. Without the file nothing changes (one phone, id `phone`).
- Every per-phone tool takes an optional `device`; new `list_devices`. With more than one phone connected, a call without `device` fails instead of guessing. `panic_disconnect` without `device` severs every phone. Audit lines and control-channel events carry the device.
- ADB mode `always` for test phones: sessions only connect to a fixed serial and never turn debugging off; optional `reverse` keeps `adb reverse` of the relay port for phones without Tailscale.
- Protocol: phones may ask the relay (`req`/`res`); `relay_status` answers admin phones only. The welcome now carries the phone's id.
- A phone that looks connected but stops answering (e.g. frozen by the system in the background) is closed with code 4002 after two commands in a row time out, so it reconnects instead of staying a dead session.
- `launch_app` takes `move`: reuse the app's existing task and move it between the background display and the main screen ("continue on the main screen"), instead of opening a separate copy. With the shell helper the exact task is moved back (never the user's own copy) and `bounds` makes it a pop-up window.
- `launch_app split=true` (experimental, with the shell helper): continue the moved app in split screen next to the user's app. `global_action` gains `split_screen`.
- New [docs/devices.md](docs/devices.md): what works on which phone (Galaxy S24 Ultra, Galaxy S20).
- **The agent can no longer lock itself out of a phone.** `panic_disconnect` no longer switches the app off on the phone (it did, and also turned debugging off on an `always` test phone, which then needed someone to reopen it by hand); the phone reconnects by itself. `adb_shell` refuses commands that would leave the agent no way back (`lockout_guard`): stopping, disabling, removing or revoking DroidHand or Tailscale, turning debugging, accessibility or the network off, rebooting. Only the user's kill switches on the phone turn the agent off for good.
- Tool descriptions: `open_url`, `key_event`, `go_home`, `global_action` and `panic_disconnect` say what they do on a background display and that a panic also stops the app until someone reopens it on the phone.

### App (Android)
- **New-version notice**: when the app opens it checks the latest GitHub release; if it is newer, the home screen offers **Download and install** (the APK is checked against the release's SHA256SUMS and handed to Android's installer). After an update the app turns its accessibility service back on by itself (with the secure-settings grant), since Android leaves it off.
- **Add a phone from the app**: on an admin phone, Relay → Add another phone → name it → Add phone. The relay creates its token and `devices.json` entry and accepts it right away (no restart); the app shows an invite (latest release link, relay address, token) as text and as a **QR code**, and the new phone's **Connection → Scan QR code** (Google's code scanner, no camera permission) or **Paste invite** fills everything in. The QR code is the release page with the connection in the URL fragment: a plain camera opens the download page. Relay request `add_device` (admin phones only; the token is never written to the audit log).
- **Relay → Add another phone**: step-by-step instructions to connect one more phone (token, devices.json, relay restart, app setup, one-time grant, the install-phone.sh shortcut), with this phone's relay address filled in and tap-to-copy commands.
- New **Relay** screen (read-only): relay version, agent sessions and every phone with its connection state, model, app version and ADB mode. Only phones marked admin on the relay can see it.
- Native background window opens with `own_content_only`: on Samsung One UI it no longer shows the DeX launcher and taskbar, and apps open full screen instead of as freeform windows.
- When the relay closes the session as unresponsive (4002), the app reconnects with a fresh tool worker, so a stuck tool no longer blocks the next commands.
- The background display has the phone screen's size and density by default (was 720x1560/320), so apps moved between the screens keep their state instead of being recreated (tested on a Galaxy S20: Clock moved both ways with no relaunch).
- Own window on Android 13: the Android 14 display flags are no longer set before 14 (on Samsung they marked a "hidden space" display and every launch crashed).
- Native window on Android 13 and older: accessibility does not cover it, so screen tools answer `native_window_not_accessible` instead of failing vaguely.
- Shell helper: errors now reach the app (some exception types were dropped by binder and looked like an empty success); a task moved back from a freeform display is made full screen.
- **The agent's own taps no longer hit the Stop button.** A tap on an element under the floating Stop button (the Settings search icon, seen on a Galaxy S20) cancelled the agent's own action as if the user had tapped Stop. The button now moves out of the way of every tap and swipe first.
- **Background display tools no longer touch the main screen.** `global_action` with a background `display` is refused (it opened the notification shade on the user's screen without asking); `go_home` and `key_event` there use the display's own keys; `recents` is refused there (it opened on the main screen); `open_url` opens on the background display in the agent's own copy of the browser (it opened on the main screen, and a browser like Chrome pulled the user's tab off it).
- The main-screen prompt closing without an answer (screen off, Stop, app restart) now answers `prompt_closed` instead of claiming the user said "not now".
- Native background window: a window left over after the app restarted (update, crash) no longer makes the next `bg_open` fail.
- The Stop button stays on top of the own background window (it was drawn under its edge).
- `screen_capture` waits and retries when Android refuses a second screenshot within a second, and names the reason instead of "secure screen or rate limit".
- `key_event`, `go_home` and `global_action` return what they did, and an error message when the system refuses (it was an empty error).
- Split screen: the user's app stays on top of the bottom half when One UI pulls another copy of the moved app into it.
- `unlock_device`: swipes up when the PIN pad does not open, checks each key in the PIN field and never presses OK unless the count matches (a key that did not register cannot cost a wrong attempt). Tested on a Galaxy S20: 3/3 from screen off.

## [1.0.0] - 2026-11-21

First public release.

### App (Android)
- Accessibility-based perception and interaction: UI snapshot with `@refs`, JPEG screenshots, tap, long-press, swipe, scroll, typing, keys, global actions, launching apps and links.
- Background display where the agent works while you use the phone: **native** (system overlay, debugging off) and **own window** (trusted virtual display via a shell helper; hide/show with an eye bubble, resize, opacity, touch passthrough, works while locked).
- Safety: asks before acting on the main screen while you use the phone; floating Stop button that cancels the current action; background work only while its controls are visible; kill switch in the notification and a new **Quick Settings tile** (both also close the background window).
- Lock awareness (`get_lock_state`) and opt-in unlock with a PIN encrypted in Android Keystore (`unlock_device`); biometric/credential confirmation to save or show it; disabled after one wrong attempt.
- Redesigned app: system/light/dark themes, wallpaper colors or five accents, English and Portuguese, live status and permissions panel.
- Release build: R8-minified and signed (2.4 MB).
- adb-only provisioning (`ProvisionReceiver`, `RELAY_URL=… deploy/install-phone.sh`): set the relay address and token without touching the screen.

### Relay + MCP server
- Relay daemon (systemd user service) + per-session stdio MCP adapter; shared token, fail closed; append-only audit log with secrets masked.
- 36 tools; generated reference in `docs/tools.md`. Designed-but-unbuilt tools (notifications) are listed as planned and not offered to agents.
- **ADB on demand** (`adb_session_begin` / `adb_shell` / `adb_session_end`): debugging stays off; a session turns USB debugging on, connects over the tailnet (`adb tcpip 5555`, armed automatically on the first session after a reboot while the phone is on any Wi-Fi) and turns it off when the agent ends it, 10 minutes after the agent's last call, from the phone's notification or any kill switch. `set_setting` can no longer turn debugging on.
- Own-window helper started/stopped from the host over adb; feature-suggestion log (off by default).

### Project
- Monorepo layout: `android/`, `mcp-server/`, `deploy/`, `docs/`.
- CI: typecheck, unit tests (app + server), debug and release builds, tools-doc check, secret hygiene.
- `scripts/release.sh` builds the signed APK, server tarball, checksums and the GitHub Release.
- Docs: setup guide, architecture & protocol, locked-screen capability matrix, illustrated README.

[1.0.0]: https://github.com/gabrielalberton/droidhand/releases/tag/v1.0.0
