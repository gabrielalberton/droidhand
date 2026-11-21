# DroidHand — Android app

The phone side of DroidHand. It dials out to the relay, runs each tool through its accessibility service, and enforces the on-device safety rules (ask first, Stop, visible background work, kill switches). Architecture and package layout: [../docs/architecture.md](../docs/architecture.md#inside-the-app).

- Kotlin + Jetpack Compose (Material 3), min SDK 30 (Android 11), target SDK 35.
- Application id `work.alberton.droidhand`; accessibility service `work.alberton.droidhand.a11y.DroidHandAccessibilityService`.

## Build

Needs JDK 17+ and the Android SDK (`ANDROID_HOME` or `local.properties` with `sdk.dir`).

```bash
./gradlew :app:testDebugUnitTest   # JVM unit tests
./gradlew :app:assembleDebug       # app/build/outputs/apk/debug/droidhand-debug.apk
./gradlew :app:assembleRelease     # app/build/outputs/apk/release/droidhand-release.apk
```

The release build is minified with R8. It is **signed** only when a properties file with `storeFile`, `storePassword`, `keyAlias` and `keyPassword` is found at `android/keystore.properties` (git-ignored) or at `$DROIDHAND_KEYSTORE_PROPERTIES`; otherwise it is unsigned. Debug and release builds use different keys, so switching between them requires uninstalling the app.

Version: `versionName` / `versionCode` in `app/build.gradle.kts` (keep in sync with `mcp-server/package.json`).

## Install over adb

```bash
../deploy/install-phone.sh [serial] [apk]
```

Installs (release build if present, else debug), grants `WRITE_SECURE_SETTINGS` and notifications, and re-enables the accessibility service — Android turns it off on every update.

## Shell helper

`shell/ShellMain` is not part of the app process: the relay starts it with
`adb shell CLASSPATH=<apk> app_process /system/bin work.alberton.droidhand.shell.ShellMain`.
Running as the shell user, it creates the trusted virtual display for the own-window background method and injects input into it; it talks to the app over the `IShellHelper` binder. R8 keeps it unrenamed (`proguard-rules.pro`).
