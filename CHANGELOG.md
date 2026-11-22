# Changelog

All notable changes to this project are documented here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Relay + MCP server
- **Several phones on one relay**, each with its own settings in `~/.config/droidhand/devices.json`: token (which also identifies the phone), name, role and ADB mode. Without the file nothing changes (one phone, id `phone`).
- Every per-phone tool takes an optional `device`; new `list_devices`. With more than one phone connected, a call without `device` fails instead of guessing. `panic_disconnect` without `device` severs every phone. Audit lines and control-channel events carry the device.
- ADB mode `always` for test phones: sessions only connect to a fixed serial and never turn debugging off; optional `reverse` keeps `adb reverse` of the relay port for phones without Tailscale.
- Protocol: phones may ask the relay (`req`/`res`); `relay_status` answers admin phones only. The welcome now carries the phone's id.
- A phone that looks connected but stops answering (e.g. frozen by the system in the background) is closed with code 4002 after two commands in a row time out, so it reconnects instead of staying a dead session.
- `launch_app` takes `move`: reuse the app's existing task and move it between the background display and the main screen ("continue on the main screen"), instead of opening a separate copy. With the shell helper the exact task is moved back (never the user's own copy) and `bounds` makes it a pop-up window.
- New [docs/devices.md](docs/devices.md): what works on which phone (Galaxy S24 Ultra, Galaxy S20).

### App (Android)
- New **Relay** screen (read-only): relay version, agent sessions and every phone with its connection state, model, app version and ADB mode. Only phones marked admin on the relay can see it.
- Native background window opens with `own_content_only`: on Samsung One UI it no longer shows the DeX launcher and taskbar, and apps open full screen instead of as freeform windows.
- When the relay closes the session as unresponsive (4002), the app reconnects with a fresh tool worker, so a stuck tool no longer blocks the next commands.
- The background display has the phone screen's size and density by default (was 720x1560/320), so apps moved between the screens keep their state instead of being recreated (tested on a Galaxy S20: Clock moved both ways with no relaunch).
- Own window on Android 13: the Android 14 display flags are no longer set before 14 (on Samsung they marked a "hidden space" display and every launch crashed).
- Native window on Android 13 and older: accessibility does not cover it, so screen tools answer `native_window_not_accessible` instead of failing vaguely.
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
