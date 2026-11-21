package work.alberton.droidhand.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import work.alberton.droidhand.a11y.DroidHandAccessibilityService

/** Snapshot of what DroidHand is allowed to do; recomputed on every resume. */
data class PermissionState(
    val accessibility: Boolean,
    val notifications: Boolean,
    val battery: Boolean,
    val secureSettings: Boolean,
) {
    val granted get() = listOf(accessibility, notifications, battery, secureSettings).count { it }
    val total get() = 4

    companion object {
        const val GRANT_COMMAND =
            "adb shell pm grant work.alberton.droidhand android.permission.WRITE_SECURE_SETTINGS"

        fun read(ctx: Context): PermissionState {
            val cn = ComponentName(ctx, DroidHandAccessibilityService::class.java)
            val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            val a11y = DroidHandAccessibilityService.instance != null || enabled.split(':').any {
                it.equals(cn.flattenToString(), true) || it.equals(cn.flattenToShortString(), true)
            }
            val notif = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            val battery = ctx.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(ctx.packageName) == true
            val secure = ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
            return PermissionState(a11y, notif, battery, secure)
        }
    }
}
