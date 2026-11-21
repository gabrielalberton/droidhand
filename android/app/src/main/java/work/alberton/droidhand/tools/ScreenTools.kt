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
            val p = point(s, args) ?: return@withService ToolResult.fail("give a ref from screen_snapshot, or x and y")
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
        "global_action" -> ctx.withService {
            val action = args.getString("action")
            ToolResult(it.globalKey(if (action == "clear_all_notifications") "notifications" else action))
        }
        "go_home" -> ctx.withService { ToolResult(it.globalKey("home")) }
        else -> null
    }

    private fun keyEvent(s: DroidHandAccessibilityService, key: String): ToolResult {
        if (s.displayId == 0 || key == "enter") return ToolResult(s.globalKey(key))
        // Global actions would hit the user's screen: inject into the background display instead.
        val code = ToolPolicy.KEYCODES[key] ?: return ToolResult.fail("key $key not supported on a background display")
        return if (s.bg.mode == BackgroundSession.Mode.OWN && s.displayId == s.bg.displayId) ToolResult(s.bg.key(code))
        else ToolResult.fail("keys other than enter need the own-window method on a background display; tap the app's own back button instead")
    }

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
