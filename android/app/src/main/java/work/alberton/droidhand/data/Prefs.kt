package work.alberton.droidhand.data

import android.content.Context

/**
 * Simple synchronous settings store. The feature-suggestion log is OFF by default.
 */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("droidhand", Context.MODE_PRIVATE)

    var relayUrl: String
        get() = sp.getString(KEY_RELAY_URL, "") ?: ""
        set(v) = sp.edit().putString(KEY_RELAY_URL, v).apply()

    var token: String
        get() = sp.getString(KEY_TOKEN, "") ?: ""
        set(v) = sp.edit().putString(KEY_TOKEN, v).apply()

    /** Whether the agent may log feature suggestions on this device. Default: false. */
    var suggestionLogging: Boolean
        get() = sp.getBoolean(KEY_SUGGESTION_LOGGING, false)
        set(v) = sp.edit().putBoolean(KEY_SUGGESTION_LOGGING, v).apply()

    /** The operator started the agent service (and did not disconnect it since). */
    var serviceEnabled: Boolean
        get() = sp.getBoolean(KEY_SERVICE_ENABLED, false)
        set(v) = sp.edit().putBoolean(KEY_SERVICE_ENABLED, v).apply()

    /** Background-window method: "agent" (agent decides), "native" or "own". */
    var bgMode: String
        get() = sp.getString(KEY_BG_MODE, BG_AGENT) ?: BG_AGENT
        set(v) = sp.edit().putString(KEY_BG_MODE, v).apply()

    /** Opacity of our own background window: 1.0 = opaque (default). */
    var windowOpacity: Float
        get() = sp.getFloat(KEY_WINDOW_OPACITY, 1f)
        set(v) = sp.edit().putFloat(KEY_WINDOW_OPACITY, if (v >= 0.97f) 1f else v.coerceIn(0.3f, 1f)).apply() // snap to opaque

    /** Ask before the agent acts on the main screen while the user is using the phone. Default: on. */
    var askMainScreen: Boolean
        get() = sp.getBoolean(KEY_ASK_MAIN, true)
        set(v) = sp.edit().putBoolean(KEY_ASK_MAIN, v).apply()

    /** The agent may unlock the phone with the saved PIN (SecretStore). Default: off. */
    var pinUnlockEnabled: Boolean
        get() = sp.getBoolean(KEY_PIN_UNLOCK, false) && !sp.getBoolean(KEY_ADB_PIN_ATTEMPT, false)
        set(v) = sp.edit().putBoolean(KEY_PIN_UNLOCK, v).remove(KEY_ADB_PIN_ATTEMPT).apply()

    /** The ADB fallback requires an acknowledgement separate from the original PIN opt-in. */
    var adbPinUnlockEnabled: Boolean
        get() = sp.getBoolean("adb_pin_unlock_accepted", false)
        set(v) = sp.edit().putBoolean("adb_pin_unlock_accepted", v).apply()

    fun beginAdbPinAttempt(): Boolean = sp.edit().putBoolean(KEY_ADB_PIN_ATTEMPT, true).commit()

    fun finishAdbPinAttempt() { sp.edit().remove(KEY_ADB_PIN_ATTEMPT).apply() }

    /**
     * Compatibility with banking apps: keep the Play Store recorded as this app's installer (see
     * InstallerCompat). Default: ON, with a plain warning in Privacy & safety.
     */
    var playInstaller: Boolean
        get() = sp.getBoolean("play_installer", true)
        set(v) = sp.edit().putBoolean("play_installer", v).apply()

    /** When the app last asked the relay to fix the installer, and how many tries in a row (a try that sticks resets it). */
    var installerFixAt: Long
        get() = sp.getLong("installer_fix_at", 0L)
        set(v) = sp.edit().putLong("installer_fix_at", v).apply()
    var installerFixTries: Int
        get() = sp.getInt("installer_fix_tries", 0)
        set(v) = sp.edit().putInt("installer_fix_tries", v).apply()

    fun noteInstallerFix(@Suppress("UNUSED_PARAMETER") version: String) {
        sp.edit().putLong("installer_fix_at", System.currentTimeMillis()).putInt("installer_fix_tries", installerFixTries + 1).commit()
    }

    /** App theme: "system", "light" or "dark". */
    var themeMode: String
        get() = sp.getString(KEY_THEME, THEME_SYSTEM) ?: THEME_SYSTEM
        set(v) = sp.edit().putString(KEY_THEME, v).apply()

    /** Material You wallpaper colors (Android 12+). Off: our own palette + accent. */
    var dynamicColor: Boolean
        get() = sp.getBoolean(KEY_DYNAMIC, false)
        set(v) = sp.edit().putBoolean(KEY_DYNAMIC, v).apply()

    /** Accent key, see ui.theme.Accent. */
    var accent: String
        get() = sp.getString(KEY_ACCENT, "indigo") ?: "indigo"
        set(v) = sp.edit().putString(KEY_ACCENT, v).apply()

    /** "" = follow the system, else a language tag ("en", "pt-BR"). Android 13+ keeps its own copy. */
    var language: String
        get() = sp.getString(KEY_LANGUAGE, "") ?: ""
        set(v) = sp.edit().putString(KEY_LANGUAGE, v).apply()

    companion object {
        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"
        private const val KEY_ASK_MAIN = "ask_main_screen"
        private const val KEY_PIN_UNLOCK = "pin_unlock_enabled"
        private const val KEY_ADB_PIN_ATTEMPT = "adb_pin_attempt_pending"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_DYNAMIC = "dynamic_color"
        private const val KEY_ACCENT = "accent"
        private const val KEY_LANGUAGE = "language"
        const val BG_AGENT = "agent"
        const val BG_NATIVE = "native"
        const val BG_OWN = "own"
        private const val KEY_BG_MODE = "bg_mode"
        private const val KEY_WINDOW_OPACITY = "window_opacity"
        private const val KEY_SERVICE_ENABLED = "service_enabled"
        private const val KEY_RELAY_URL = "relay_url"
        private const val KEY_TOKEN = "token"
        private const val KEY_SUGGESTION_LOGGING = "suggestion_logging"
    }
}
