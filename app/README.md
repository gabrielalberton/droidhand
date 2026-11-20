# DroidHand — Portal app (Android)

Kotlin + Jetpack Compose app that runs on the phone and performs actions. **Scaffold stage — no code yet.** See [../docs/PRD.md](../docs/PRD.md) §6.1.

## Planned modules
- `a11y/` — AccessibilityService: snapshot tree (stable refs), gestures, global actions, text set/clear.
- `capture/` — screenshots (`takeScreenshot`) + screen recording (MediaProjection).
- `settings/` — typed wrappers over Secure/Global/System (`WRITE_SECURE_SETTINGS`).
- `adb/` — ADB-on-demand engine (clean-room ADB client, mDNS, pairing). PRD §8.
- `notif/` — NotificationListenerService.
- `relay/` — reconnecting WSS client (outbound-only).
- `security/` — confirm gates, kill-switch tile, persistent notification, local audit.
- `ui/` — Compose setup wizard, status, log viewer, panic button.

## Requirements
- Min SDK 30 (Android 11), target latest.
- One-time `WRITE_SECURE_SETTINGS` grant via ADB; self-pairing for future ADB-on-demand sessions.

## Build (once scaffolded)
```
# Android Studio, or:
./gradlew :app:assembleRelease
```
