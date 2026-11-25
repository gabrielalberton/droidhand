package work.alberton.droidhand.a11y

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * Turns DroidHand's own accessibility service on or off from the app, when the secure-settings
 * grant is there (the same write the update receiver does). Other enabled services are kept.
 */
object A11yToggle {
    fun canWrite(c: Context) =
        c.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED

    /** True when the setting was written; false when the grant is missing (the caller opens Android's screen instead). */
    fun set(c: Context, on: Boolean): Boolean {
        if (!canWrite(c)) return false
        val cr = c.contentResolver
        val key = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        val ours = ComponentName(c, DroidHandAccessibilityService::class.java).flattenToString()
        val others = Settings.Secure.getString(cr, key).orEmpty().split(':')
            .filter { it.isNotBlank() && !it.contains("DroidHandAccessibilityService") }
        return runCatching {
            val list = if (on) others + ours else others
            Settings.Secure.putString(cr, key, list.joinToString(":").ifEmpty { null })
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, if (list.isEmpty()) 0 else 1)
            true
        }.getOrDefault(false)
    }
}
