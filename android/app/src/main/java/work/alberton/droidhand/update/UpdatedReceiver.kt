package work.alberton.droidhand.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings

/**
 * After an update Android unbinds the accessibility service and does not bind it again
 * (it counts as crashed), so the agent would be gone until the user switches it off and on.
 * With the secure-settings grant the app does that itself: only when the user had it on.
 */
class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (ctx.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") != PackageManager.PERMISSION_GRANTED) return
        val cr = ctx.contentResolver
        val key = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        val cur = Settings.Secure.getString(cr, key).orEmpty()
        val ours = cur.split(':').firstOrNull { it.contains("DroidHandAccessibilityService") } ?: return
        val others = cur.split(':').filter { it.isNotBlank() && it != ours }.joinToString(":")
        val pending = goAsync()
        runCatching { Settings.Secure.putString(cr, key, others.ifEmpty { null }) }
        Handler(Looper.getMainLooper()).postDelayed({
            runCatching {
                Settings.Secure.putString(cr, key, if (others.isEmpty()) ours else "$others:$ours")
                Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            }
            pending.finish()
        }, 1000)
    }
}
