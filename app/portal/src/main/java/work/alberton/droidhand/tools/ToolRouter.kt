package work.alberton.droidhand.tools

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import work.alberton.droidhand.a11y.DroidHandAccessibilityService
import work.alberton.droidhand.data.Prefs
import work.alberton.droidhand.suggestions.SuggestionStore

/** Result of running a tool: ok + data, or an error. */
data class ToolResult(val ok: Boolean, val data: JSONObject? = null, val error: String? = null)

/**
 * Maps a tool name + args to an action on the device. Runs on a background
 * thread (the relay socket thread), never on the main thread.
 */
class ToolRouter(context: Context) {
    private val app = context.applicationContext
    private val prefs = Prefs(app)
    private val suggestions = SuggestionStore(app)

    fun run(tool: String, args: JSONObject): ToolResult = try {
        dispatch(tool, args)
    } catch (e: Exception) {
        ToolResult(false, error = "${e.javaClass.simpleName}: ${e.message}")
    }

    private fun dispatch(tool: String, args: JSONObject): ToolResult {
        val svc = DroidHandAccessibilityService.instance
        // Every tool may target a display: 0 = phone screen (default), N = background display.
        if (svc != null) svc.displayId = args.optInt("display", 0)
        // Show the floating panic button whenever the agent acts on the screen.
        if (tool in ACTING && svc != null && runCatching { svc.panic }.isSuccess) svc.panic.bump()
        return when (tool) {
            // ---- Kill switch (from the NUC: panic_disconnect) ----
            "panic" -> {
                if (svc != null) svc.stopAgent() else work.alberton.droidhand.service.RelayService.stop(app)
                ToolResult(true, JSONObject().put("stopped", true))
            }

            // ---- Device & app info ----
            "get_device_info" -> ToolResult(true, deviceInfo(svc))
            "list_windows" -> withService(svc) { ToolResult(true, JSONObject().put("windows", it.listWindows())) }
            "get_foreground_app" -> withService(svc) { ToolResult(true, JSONObject().put("package", it.foregroundPackage())) }
            "list_apps" -> ToolResult(true, JSONObject().put("apps", launchableApps()))

            // ---- Perception ----
            "screen_snapshot" -> withService(svc) {
                val snap = it.snapshot()
                if (snap.has("error")) ToolResult(false, error = snap.getString("error")) else ToolResult(true, snap)
            }
            "screen_capture" -> withService(svc) {
                val q = args.optInt("quality", 70).coerceIn(1, 100)
                val shot = it.capture(quality = q)
                if (shot.has("error")) ToolResult(false, error = shot.getString("error")) else ToolResult(true, shot)
            }

            // ---- Interaction ----
            "tap" -> withService(svc) { s ->
                val p = point(s, args) ?: return@withService ToolResult(false, error = "give a ref from screen_snapshot, or x and y")
                val ok = s.tapAt(p[0], p[1])
                ToolResult(ok, JSONObject().put("x", p[0]).put("y", p[1]), if (ok) null else "gesture not completed")
            }
            "long_press" -> withService(svc) { s ->
                val p = point(s, args) ?: return@withService ToolResult(false, error = "give a ref or x and y")
                val ok = s.tapAt(p[0], p[1], args.optLong("durationMs", 700))
                ToolResult(ok, JSONObject().put("x", p[0]).put("y", p[1]), if (ok) null else "gesture not completed")
            }
            "swipe" -> withService(svc) {
                val ok = it.swipe(
                    args.getInt("fromX"), args.getInt("fromY"), args.getInt("toX"), args.getInt("toY"),
                    args.optLong("durationMs", 300),
                )
                ToolResult(ok, JSONObject().put("swiped", ok), if (ok) null else "gesture not completed")
            }
            "scroll" -> withService(svc) { s ->
                val size = android.graphics.Point().also {
                    app.getSystemService(android.hardware.display.DisplayManager::class.java)
                        .getDisplay(s.displayId)?.getRealSize(it)
                }
                if (size.x == 0) { size.x = app.resources.displayMetrics.widthPixels; size.y = app.resources.displayMetrics.heightPixels }
                val cx = size.x / 2
                val cy = size.y / 2
                val dy = size.y / 3
                val dx = size.x / 3
                val ok = when (args.getString("direction")) {
                    "down" -> s.swipe(cx, cy + dy, cx, cy - dy, 350)
                    "up" -> s.swipe(cx, cy - dy, cx, cy + dy, 350)
                    "left" -> s.swipe(cx + dx, cy, cx - dx, cy, 350)
                    "right" -> s.swipe(cx - dx, cy, cx + dx, cy, 350)
                    else -> false
                }
                ToolResult(ok, JSONObject().put("scrolled", ok))
            }
            "type_text" -> withService(svc) { s ->
                if (args.has("ref") || args.has("x")) point(s, args)?.let { s.tapAt(it[0], it[1]); Thread.sleep(250) }
                val ok = s.typeText(args.getString("text"))
                if (ok && args.optBoolean("submit", false)) { Thread.sleep(100); s.imeEnter() }
                ToolResult(ok, JSONObject().put("typed", ok), if (ok) null else "no focused text field")
            }
            "key_event" -> withService(svc) { s ->
                val key = args.getString("key")
                if (s.displayId != 0 && key != "enter") {
                    // Global actions would hit the user's screen: inject into the background display instead.
                    val code = KEYCODES[key] ?: return@withService ToolResult(false, error = "key $key not supported on a background display")
                    if (s.bg.mode == work.alberton.droidhand.bg.BackgroundSession.Mode.OWN && s.displayId == s.bg.displayId)
                        ToolResult(s.bg.key(code))
                    else ToolResult(false, error = "keys other than enter need the own-window method on a background display; tap the app's own back button instead")
                } else ToolResult(s.globalKey(key))
            }

            // ---- Background display (native or own window) ----
            "bg_open" -> withService(svc) { s ->
                ToolResult(true, s.bg.open(args.optString("mode", "auto"), args.optInt("width", 720),
                    args.optInt("height", 1560), args.optInt("dpi", 320)))
            }
            "bg_close" -> withService(svc) { s -> s.bg.close("agent"); ToolResult(true, s.bg.status()) }
            "bg_hide" -> withService(svc) { s ->
                if (s.bg.hide()) ToolResult(true, s.bg.status()) else ToolResult(false, error = "only the own-window method can be hidden")
            }
            "bg_show" -> withService(svc) { s -> s.bg.show(); ToolResult(true, s.bg.status()) }
            "bg_status" -> withService(svc) { s -> ToolResult(true, s.bg.status()) }
            "request_user_help" -> withService(svc) { s -> ToolResult(true, s.bg.requestHelp(args.getString("message"))) }
            "global_action" -> withService(svc) {
                val action = args.getString("action")
                val key = if (action == "clear_all_notifications") "notifications" else action
                ToolResult(it.globalKey(key))
            }
            "go_home" -> withService(svc) { ToolResult(it.globalKey("home")) }

            // ---- App & navigation ----
            "launch_app" -> {
                val pkg = args.getString("package")
                val intent = app.packageManager.getLaunchIntentForPackage(pkg)
                    ?: return ToolResult(false, error = "no launchable activity for $pkg")
                val display = args.optInt("display", 0)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (display != 0) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                    // Background displays open in freeform windows on One UI: ask for the whole display.
                    val dm = app.getSystemService(android.hardware.display.DisplayManager::class.java)
                    val size = android.graphics.Point().also { dm.getDisplay(display)?.getRealSize(it) }
                    val opts = android.app.ActivityOptions.makeBasic().setLaunchDisplayId(display)
                    if (size.x > 0) opts.setLaunchBounds(android.graphics.Rect(0, 0, size.x, size.y))
                    app.startActivity(intent, opts.toBundle())
                } else if (args.has("bounds")) {
                    // One UI: launch bounds on the main display open the app as a pop-up (freeform) window.
                    val b = args.getJSONArray("bounds")
                    intent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                    val opts = android.app.ActivityOptions.makeBasic()
                        .setLaunchBounds(android.graphics.Rect(b.getInt(0), b.getInt(1), b.getInt(2), b.getInt(3)))
                    app.startActivity(intent, opts.toBundle())
                } else {
                    app.startActivity(intent)
                }
                ToolResult(true, JSONObject().put("launched", pkg).put("display", display))
            }
            "open_url" -> {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(args.getString("url"))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                app.startActivity(intent)
                ToolResult(true, JSONObject().put("opened", args.getString("url")))
            }

            // ---- Settings ----
            "get_setting" -> {
                val v = readSetting(args.getString("namespace"), args.getString("key"))
                ToolResult(true, JSONObject().put("value", v ?: JSONObject.NULL))
            }
            "set_setting" -> setSetting(args.getString("namespace"), args.getString("key"), args.getString("value"))

            // ---- Feature suggestions (on-device source of truth) ----
            "store_suggestion" -> {
                if (!prefs.suggestionLogging) return ToolResult(false, error = "suggestion logging disabled on device")
                val saved = suggestions.add(args)
                ToolResult(true, JSONObject().put("stored", true).put("id", saved.optString("id")))
            }

            else -> ToolResult(false, error = "not_implemented: $tool")
        }
    }

    private fun deviceInfo(svc: DroidHandAccessibilityService?): JSONObject {
        val bm = app.getSystemService(BatteryManager::class.java)
        return JSONObject()
            .put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL)
            .put("androidRelease", Build.VERSION.RELEASE)
            .put("androidSdk", Build.VERSION.SDK_INT)
            .put("batteryPercent", bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1)
            .put("accessibilityEnabled", svc != null)
            .put("writeSecureSettings", canWriteSecure())
            .put("suggestionLogging", prefs.suggestionLogging)
            .put("debugging", JSONObject()
                .put("developerOptions", readSetting("global", Settings.Global.DEVELOPMENT_SETTINGS_ENABLED))
                .put("usb", readSetting("global", Settings.Global.ADB_ENABLED))
                .put("wifi", readSetting("global", "adb_wifi_enabled")))
    }

    private fun launchableApps(): JSONArray {
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

    private fun point(s: DroidHandAccessibilityService, args: JSONObject): IntArray? {
        val ref = args.optString("ref", "")
        if (ref.isNotBlank()) return s.pointFor(if (ref.startsWith("@")) ref else "@$ref")
        if (args.has("x") && args.has("y")) return intArrayOf(args.getInt("x"), args.getInt("y"))
        return null
    }

    private fun readSetting(ns: String, key: String): String? = when (ns) {
        "global" -> Settings.Global.getString(app.contentResolver, key)
        "secure" -> Settings.Secure.getString(app.contentResolver, key)
        "system" -> Settings.System.getString(app.contentResolver, key)
        else -> null
    }

    private fun canWriteSecure(): Boolean =
        app.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED

    /**
     * Until on-device confirmation dialogs land (P2), writes are limited to an
     * allowlist of debugging/display keys the operator explicitly wants agents to flip.
     */
    private fun setSetting(ns: String, key: String, value: String): ToolResult {
        if ("$ns/$key" !in WRITABLE) {
            return ToolResult(false, error = "set_setting allowlist (P1): ${WRITABLE.joinToString()}")
        }
        if (!canWriteSecure()) {
            return ToolResult(false, error = "WRITE_SECURE_SETTINGS not granted (adb shell pm grant ${app.packageName} android.permission.WRITE_SECURE_SETTINGS)")
        }
        val before = readSetting(ns, key)
        val ok = when (ns) {
            "global" -> Settings.Global.putString(app.contentResolver, key, value)
            "secure" -> Settings.Secure.putString(app.contentResolver, key, value)
            else -> false
        }
        return ToolResult(ok, JSONObject().put("key", "$ns/$key").put("before", before ?: JSONObject.NULL).put("after", readSetting(ns, key) ?: JSONObject.NULL))
    }

    private inline fun withService(
        svc: DroidHandAccessibilityService?,
        block: (DroidHandAccessibilityService) -> ToolResult,
    ): ToolResult = svc?.let(block) ?: ToolResult(false, error = "accessibility service not enabled")

    companion object {

        /** Tools that act on the screen or device (they show the panic button). */
        val ACTING = setOf(
            "tap", "long_press", "swipe", "scroll", "type_text", "key_event", "global_action",
            "go_home", "launch_app", "open_url", "set_setting", "bg_open", "request_user_help",
        )

        val KEYCODES = mapOf(
            "back" to android.view.KeyEvent.KEYCODE_BACK,
            "home" to android.view.KeyEvent.KEYCODE_HOME,
            "recents" to android.view.KeyEvent.KEYCODE_APP_SWITCH,
            "enter" to android.view.KeyEvent.KEYCODE_ENTER,
        )

        val WRITABLE = setOf(
            "global/adb_enabled",
            "global/adb_wifi_enabled",
            "global/development_settings_enabled",
            "global/stay_on_while_plugged_in",
            "global/window_animation_scale",
            "global/transition_animation_scale",
            "global/animator_duration_scale",
        )
    }
}
