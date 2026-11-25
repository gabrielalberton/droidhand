# DroidHand Lab

A throwaway APK (`work.alberton.droidhandlab`), not part of any release, used to find out what a banking
app reacts to. It has six accessibility services that do nothing (A no capabilities, B content, C gestures,
D screenshot, E full + `isAccessibilityTool`, F full without it) and a floating "did it open?" question.

Result (Nubank, Galaxy S24, 2026-11-25): the **installer of record** decides, not the capabilities — see CHANGELOG 1.3.0.

    adb install -r [-i com.android.vending] lab-debug.apk
    adb shell pm grant work.alberton.droidhandlab android.permission.WRITE_SECURE_SETTINGS
    adb shell appops set work.alberton.droidhandlab SYSTEM_ALERT_WINDOW allow
    adb shell am start -n work.alberton.droidhandlab/.LabActivity --es variant A --es ask A

Build: `./gradlew :lab:assembleDebug` (from `android/`).
