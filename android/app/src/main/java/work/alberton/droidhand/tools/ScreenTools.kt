package work.alberton.droidhand.tools

import android.graphics.Point
import android.hardware.display.DisplayManager
import org.json.JSONObject
import work.alberton.droidhand.a11y.DroidHandAccessibilityService
import work.alberton.droidhand.bg.BackgroundSession

/** Perception (snapshot, capture, windows) and interaction (gestures, typing, keys). */
class ScreenTools : ToolGroup {
    override fun handle(tool: String, args: JSONObject, ctx: ToolContext): ToolResult? = when (tool) {
        // ---- Perception ----
        "list_windows" -> ctx.withService { ToolResult.ok(JSONObject().put("windows", it.listWindows())) }
        "get_foreground_app" -> ctx.withService { ToolResult.ok(JSONObject().put("package", it.foregroundPackage())) }
        "screen_snapshot" -> ctx.withService { orError(it.snapshot()) }
        "screen_capture" -> ctx.withService { orError(it.capture(quality = args.optInt("quality", 70).coerceIn(1, 100))) }

        // ---- Interaction ----
        "tap" -> ctx.withService { s ->
            // A ref on a background display: click without touching (keeps the user's focus/keyboard).
            val ref = ToolPolicy.normalizeRef(args.optString("ref", ""))
            if (ref != null && args.optInt("display", 0) != 0 && s.clickRef(ref)) {
                return@withService ToolResult.ok(JSONObject().put("ref", ref).put("method", "click"))
            }
            val p = point(s, args) ?: return@withService ToolResult.fail("give a ref from screen_snapshot, or x and y")
            // Own window: a mouse click (through the shell helper) instead of a touch, same reason.
            val own = s.displayId != 0 && runCatching { s.bg.mode == BackgroundSession.Mode.OWN && s.bg.displayId == s.displayId }.getOrDefault(false)
            if (own && s.bg.mouseClick(p[0], p[1])) {
                return@withService ToolResult.ok(JSONObject().put("x", p[0]).put("y", p[1]).put("method", "mouse"))
            }
            gesture(s.tapAt(p[0], p[1]), JSONObject().put("x", p[0]).put("y", p[1]))
        }
        "long_press" -> ctx.withService { s ->
            val p = point(s, args) ?: return@withService ToolResult.fail("give a ref or x and y")
            gesture(s.tapAt(p[0], p[1], args.optLong("durationMs", 700)), JSONObject().put("x", p[0]).put("y", p[1]))
        }
        "swipe" -> ctx.withService { s ->
            val ok = s.swipe(
                args.getInt("fromX"), args.getInt("fromY"), args.getInt("toX"), args.getInt("toY"),
                args.optLong("durationMs", 300),
            )
            gesture(ok, JSONObject().put("swiped", ok))
        }
        "scroll" -> ctx.withService { s ->
            // Background display: an accessibility scroll first (a swipe is a touch, and a touch
            // there takes the focus from the user's screen and closes their keyboard).
            if (s.displayId != 0 && s.scrollAction(args.getString("direction")))
                return@withService ToolResult.ok(JSONObject().put("scrolled", true).put("method", "action"))
            val size = displaySize(ctx, s.displayId)
            val v = ToolPolicy.scrollSwipe(args.getString("direction"), size.x, size.y)
            val ok = v != null && s.swipe(v[0], v[1], v[2], v[3], 350)
            ToolResult(ok, JSONObject().put("scrolled", ok))
        }
        "type_text" -> ctx.withService { s ->
            if (args.has("ref") || args.has("x")) point(s, args)?.let { s.tapAt(it[0], it[1]); Thread.sleep(250) }
            val ok = s.typeText(args.getString("text"))
            if (ok && args.optBoolean("submit", false)) { Thread.sleep(100); s.imeEnter() }
            ToolResult(ok, JSONObject().put("typed", ok), if (ok) null else "no focused text field")
        }
        "key_event" -> ctx.withService { s -> keyEvent(s, args.getString("key")) }
        // Global actions always hit the main screen: never on behalf of a background display
        // (the main-screen prompt only guards display 0).
        "global_action" -> ctx.withService {
            if (it.displayId != 0) ToolResult.fail(ToolPolicy.GLOBAL_ON_BG)
            else done(it.globalKey(args.getString("action")), "action", args.getString("action"))
        }
        "go_home" -> ctx.withService { if (it.displayId != 0) keyEvent(it, "home") else done(it.globalKey("home"), "action", "home") }
        else -> null
    }

    private fun keyEvent(s: DroidHandAccessibilityService, key: String): ToolResult {
        fun pressed(ok: Boolean) = done(ok, "key", key)
        if (s.displayId == 0 || key == "enter") return pressed(s.globalKey(key))
        // Global actions would hit the user's screen: inject into the background display instead.
        // Recents always opens on the main screen (seen on the S20, Android 13): never from here.
        if (key == "recents") return ToolResult.fail("recents opens on the user's main screen; not available on a background display")
        // Android 13 (S20): home does nothing on a background display; say so instead of a fake success.
        if (key == "home" && android.os.Build.VERSION.SDK_INT < ToolPolicy.NATIVE_A11Y_MIN_SDK)
            return ToolResult.fail("home has no effect on a background display on this Android version: use back, or launch_app")
        val code = ToolPolicy.KEYCODES[key] ?: return ToolResult.fail("key $key not supported on a background display")
        return if (s.bg.mode == BackgroundSession.Mode.OWN && s.displayId == s.bg.displayId) pressed(s.bg.key(code))
        else ToolResult.fail("keys other than enter need the own-window method on a background display; tap the app's own back button instead")
    }

    private fun done(ok: Boolean, k: String, v: String) =
        ToolResult(ok, JSONObject().put(k, v), if (ok) null else "$v was not accepted by the system")

    private fun orError(o: JSONObject): ToolResult =
        if (o.has("error")) ToolResult.fail(o.getString("error")) else ToolResult.ok(o)

    private fun gesture(ok: Boolean, data: JSONObject) = ToolResult(ok, data, if (ok) null else "gesture not completed")

    private fun point(s: DroidHandAccessibilityService, args: JSONObject): IntArray? {
        ToolPolicy.normalizeRef(args.optString("ref", ""))?.let { return s.pointFor(it) }
        if (args.has("x") && args.has("y")) return intArrayOf(args.getInt("x"), args.getInt("y"))
        return null
    }

    private fun displaySize(ctx: ToolContext, displayId: Int): Point {
        val size = Point()
        ctx.app.getSystemService(DisplayManager::class.java).getDisplay(displayId)?.getRealSize(size)
        if (size.x == 0) {
            val m = ctx.app.resources.displayMetrics
            size.set(m.widthPixels, m.heightPixels)
        }
        return size
    }
}
