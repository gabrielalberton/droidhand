package work.alberton.droidhand.tools

import android.view.KeyEvent

/**
 * Pure rules shared by the tool router (no Android state, unit-tested):
 * which tools act on the screen, which ones must ask first on the main screen,
 * which settings an agent may write, and the user-facing error texts.
 */
object ToolPolicy {

    /** Tools that act on the screen or device (they show the Stop button). */
    val ACTING = setOf(
        "tap", "long_press", "swipe", "scroll", "type_text", "key_event", "global_action",
        "go_home", "launch_app", "open_url", "set_setting", "bg_open", "request_user_help",
    )

    /** Tools that read or touch a display through accessibility. */
    val NEEDS_A11Y_ON_DISPLAY = setOf(
        "screen_snapshot", "tap", "long_press", "swipe", "scroll", "type_text", "list_windows", "get_foreground_app",
        "key_event", "go_home",
    )

    /**
     * Before this Android version accessibility leaves out overlay displays (the native
     * background window): seen on Android 13 (Galaxy S20); works on SDK 37 (S24); 34–36 untested.
     */
    const val NATIVE_A11Y_MIN_SDK = 34

    const val NATIVE_NO_A11Y = "native_window_not_accessible: on this phone's Android version accessibility does not " +
        "cover the native background window, so it can only be seen (screen_capture), not read or touched. " +
        "Close it and use bg_open mode=own (needs an ADB session) instead."

    /** Actions that change what the user sees on the main screen (asked first while the user is using the phone). */
    val MAIN_GATED = setOf(
        "tap", "long_press", "swipe", "scroll", "type_text", "key_event", "global_action",
        "go_home", "launch_app", "open_url",
    )

    /**
     * Settings an agent may write (stay-awake and animation keys). Debugging is not
     * here on purpose: it is only turned on through a time-limited ADB session (AdbSession).
     */
    val WRITABLE = setOf(
        "global/stay_on_while_plugged_in",
        "global/window_animation_scale",
        "global/transition_animation_scale",
        "global/animator_duration_scale",
    )

    /** Keys that can be injected into an own-window background display. */
    val KEYCODES = mapOf(
        "back" to KeyEvent.KEYCODE_BACK,
        "home" to KeyEvent.KEYCODE_HOME,
        "recents" to KeyEvent.KEYCODE_APP_SWITCH,
        "enter" to KeyEvent.KEYCODE_ENTER,
    )

    /** A Stop tapped this long ago is still reported to the agent on its next call. */
    const val STOP_NOTICE_MS = 2 * 60_000L

    const val GLOBAL_ON_BG = "global_action acts on the user's main screen, not on a background display: " +
        "call it without display (the user is asked first), or use key_event on the background display."

    const val STOPPED = "stopped_by_user: the user tapped Stop on the phone and your current action was cancelled. " +
        "Stop the current task and check with the user before continuing (the connection is still up)."

    const val NO_SERVICE = "accessibility service not enabled"

    const val NO_WSS = "no_secure_settings: WRITE_SECURE_SETTINGS is not granted. On the phone: DroidHand → " +
        "Permissions → Secure settings → Pair from the app (or once from a computer: adb shell pm grant " +
        "work.alberton.droidhand android.permission.WRITE_SECURE_SETTINGS)."

    /** True when a Stop tapped at [stopAt] (elapsedRealtime, 0 = none) must still be reported at [now]. */
    fun stopIsFresh(now: Long, stopAt: Long): Boolean = stopAt != 0L && now - stopAt < STOP_NOTICE_MS

    /** Should [tool] on [display] ask the user first? Pop-up windows (launch bounds) never ask. */
    fun needsMainScreenGate(tool: String, display: Int, hasBounds: Boolean): Boolean =
        tool in MAIN_GATED && display == 0 && !hasBounds

    /** Swipe (x1, y1, x2, y2) that scrolls a [w]×[h] screen in [direction] (content moves that way), or null. */
    fun scrollSwipe(direction: String, w: Int, h: Int): IntArray? {
        val cx = w / 2
        val cy = h / 2
        val dx = w / 3
        val dy = h / 3
        return when (direction) {
            "down" -> intArrayOf(cx, cy + dy, cx, cy - dy)
            "up" -> intArrayOf(cx, cy - dy, cx, cy + dy)
            "left" -> intArrayOf(cx + dx, cy, cx - dx, cy)
            "right" -> intArrayOf(cx - dx, cy, cx + dx, cy)
            else -> null
        }
    }

    /** "@3" for "3" or "@3"; null for a blank ref. */
    fun normalizeRef(ref: String): String? = ref.trim().takeIf { it.isNotEmpty() }?.let { if (it.startsWith("@")) it else "@$it" }
}
