# DroidHand — Portal app (Android)

Kotlin app that runs on the phone and performs actions for the agent: it dials out
to the relay, snapshots the UI and dispatches gestures through the
AccessibilityService, and owns the on-device feature-suggestion log.

> **Status: P1 skeleton — written, not yet built or device-tested on this machine.**
> Builds need a JDK 17 + Android SDK; installing/testing needs a phone with
> wireless debugging on. The NUC-side relay + MCP server are built and tested.

## Layout
```
app/                      # Gradle root (this dir)
  settings.gradle.kts
  build.gradle.kts        # plugin versions
  gradle.properties
  portal/                 # :portal application module
    build.gradle.kts
    src/main/AndroidManifest.xml
    src/main/res/xml/accessibility_service_config.xml
    src/main/java/work/alberton/droidhand/
      DroidHandApp.kt
      data/Prefs.kt                     # settings; suggestionLogging = false by default
      suggestions/SuggestionStore.kt    # on-device suggestion log (source of truth)
      relay/RelayClient.kt              # outbound WebSocket to the relay
      a11y/DroidHandAccessibilityService.kt  # snapshot + gestures
      tools/ToolRouter.kt              # tool name -> device action
      service/RelayService.kt          # foreground service + kill-switch notification
      ui/MainActivity.kt               # setup/status UI
```

## Build (needs JDK 17 + Android SDK)
```bash
cd app
# first time: generate the Gradle wrapper with a Gradle 8.9+ install, or open in Android Studio
gradle wrapper --gradle-version 8.9
./gradlew :portal:assembleDebug
# APK at portal/build/outputs/apk/debug/portal-debug.apk
```

## Install & grant (phone with wireless debugging on)
```bash
adb install -r portal/build/outputs/apk/debug/portal-debug.apk
# one-time privileged grant (enables set_setting + the ADB-on-demand engine):
adb shell pm grant work.alberton.droidhand android.permission.WRITE_SECURE_SETTINGS
```
Then in the app: enter the relay URL (`ws://<nuc-tailscale-ip>:8787`) + token, open
Accessibility settings and enable DroidHand, and tap **Start agent service**. The
feature-suggestion switch stays **OFF** unless you turn it on.

## Not yet in this skeleton (later phases)
- ADB-on-demand engine (clean-room ADB client) — PRD §8
- screenshots/screen-capture tool, notifications listener, content-identity refs
- PII redaction mode, confirmation dialogs, local encrypted audit, Quick Settings tile
