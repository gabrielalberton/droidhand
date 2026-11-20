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

    companion object {
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
