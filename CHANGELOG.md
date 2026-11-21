# Changelog

All notable changes to this project are documented here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses [Semantic Versioning](https://semver.org/).

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
