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

    companion object {
        private const val KEY_SERVICE_ENABLED = "service_enabled"
        private const val KEY_RELAY_URL = "relay_url"
        private const val KEY_TOKEN = "token"
        private const val KEY_SUGGESTION_LOGGING = "suggestion_logging"
    }
}
