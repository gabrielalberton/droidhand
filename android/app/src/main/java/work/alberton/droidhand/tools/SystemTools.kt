package work.alberton.droidhand.tools

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import work.alberton.droidhand.data.SecretStore
import work.alberton.droidhand.suggestions.SuggestionStore
import work.alberton.droidhand.unlock.Unlocker

/** Device info, lock state and unlock, apps list, settings, kill switch and the suggestion log. */
class SystemTools(context: Context) : ToolGroup {
    private val suggestions = SuggestionStore(context.applicationContext)

    override fun handle(tool: String, args: JSONObject, ctx: ToolContext): ToolResult? = when (tool) {
        // Kill switch (from the server: panic_disconnect).
        // The agent's own panic: stop what it is doing and clear its UI, but never switch the app
        // off or touch debugging. The agent must not be able to cut itself off for good: only
        // the user's kill switches (notification, tile) turn the app off. The relay ends the ADB
        // session per the phone's adb mode and drops the connection; the app reconnects.
        "panic" -> {
            ctx.svc?.agentPanic()
            ToolResult.ok(JSONObject().put("cleared", true))
        }
        // Unlock with the user's saved PIN (opt-in in the app).
        "unlock_device" -> ctx.withService { s ->
            val r = Unlocker(s).unlock()
            // Unlocked by the agent (not already unlocked by the user): main-screen work may follow without asking.
            if (r.optBoolean("unlocked") && !r.optBoolean("alreadyUnlocked")) s.gate.agentUnlocked()
            ToolResult.ok(r)
        }
        "get_lock_state" -> ToolResult.ok(lockState(ctx))
        "get_device_info" -> ToolResult.ok(deviceInfo(ctx))
        "list_apps" -> ToolResult.ok(JSONObject().put("apps", launchableApps(ctx.app)))
        "get_setting" -> {
            val v = readSetting(ctx.app, args.getString("namespace"), args.getString("key"))
            ToolResult.ok(JSONObject().put("value", v ?: JSONObject.NULL))
        }
        "set_setting" -> setSetting(ctx.app, args.getString("namespace"), args.getString("key"), args.getString("value"))
        // Feature suggestions (on-device copy).
        "store_suggestion" -> {
            if (!ctx.prefs.suggestionLogging) ToolResult.fail("suggestion logging disabled on device")
            else ToolResult.ok(JSONObject().put("stored", true).put("id", suggestions.add(args).optString("id")))
        }
        else -> null
    }

    private fun deviceInfo(ctx: ToolContext): JSONObject {
        val app = ctx.app
        val bm = app.getSystemService(BatteryManager::class.java)
        return JSONObject()
            .put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL)
            .put("androidRelease", Build.VERSION.RELEASE)
            .put("androidSdk", Build.VERSION.SDK_INT)
            .put("batteryPercent", bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1)
            .put("accessibilityEnabled", ctx.svc != null)
            .put("writeSecureSettings", canWriteSecure(app))
            .put("suggestionLogging", ctx.prefs.suggestionLogging)
            .put("debugging", JSONObject()
                .put("developerOptions", readSetting(app, "global", Settings.Global.DEVELOPMENT_SETTINGS_ENABLED))
                .put("usb", readSetting(app, "global", Settings.Global.ADB_ENABLED))
                .put("wifi", readSetting(app, "global", "adb_wifi_enabled")))
    }

    private fun lockState(ctx: ToolContext): JSONObject {
        val km = ctx.app.getSystemService(KeyguardManager::class.java)
        val pm = ctx.app.getSystemService(PowerManager::class.java)
        return JSONObject()
            .put("screenOn", pm?.isInteractive == true)
            .put("locked", km?.isKeyguardLocked == true)
            .put("secureLock", km?.isDeviceSecure == true)
            .put("pinUnlockAvailable", ctx.prefs.pinUnlockEnabled && SecretStore(ctx.app).hasPin)
    }

    private fun launchableApps(app: Context): JSONArray {
        val pm = app.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val out = JSONArray()
        pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
            .forEach { (pkg, label) -> out.put(JSONObject().put("package", pkg).put("label", label)) }
        return out
    }

    private fun readSetting(app: Context, ns: String, key: String): String? = when (ns) {
        "global" -> Settings.Global.getString(app.contentResolver, key)
        "secure" -> Settings.Secure.getString(app.contentResolver, key)
        "system" -> Settings.System.getString(app.contentResolver, key)
        else -> null
    }

    private fun canWriteSecure(app: Context): Boolean =
        app.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED

    /** Writes are limited to [ToolPolicy.WRITABLE] (stay-awake and animation keys; debugging only via ADB sessions). */
    private fun setSetting(app: Context, ns: String, key: String, value: String): ToolResult {
        if ("$ns/$key" !in ToolPolicy.WRITABLE) {
            return ToolResult.fail("set_setting allowlist: ${ToolPolicy.WRITABLE.joinToString()} (debugging: use adb_session_begin)")
        }
        if (!canWriteSecure(app)) {
            return ToolResult.fail(ToolPolicy.NO_WSS)
        }
        val before = readSetting(app, ns, key)
        val ok = when (ns) {
            "global" -> Settings.Global.putString(app.contentResolver, key, value)
            "secure" -> Settings.Secure.putString(app.contentResolver, key, value)
            else -> false
        }
        return ToolResult(ok, JSONObject().put("key", "$ns/$key").put("before", before ?: JSONObject.NULL)
            .put("after", readSetting(app, ns, key) ?: JSONObject.NULL))
    }
}
