package work.alberton.droidhand.tools

import android.app.ActivityOptions
import android.content.Intent
import android.graphics.Point
import android.util.Log
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.net.Uri
import org.json.JSONObject
import work.alberton.droidhand.shell.ShellBridge

/** Launching apps and opening links, on the phone screen or a background display. */
class AppTools : ToolGroup {
    override fun handle(tool: String, args: JSONObject, ctx: ToolContext): ToolResult? = when (tool) {
        "launch_app" -> launch(ctx, args)
        "open_url" -> {
            val url = args.getString("url")
            ctx.app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            ToolResult.ok(JSONObject().put("opened", url))
        }
        else -> null
    }

    private fun launch(ctx: ToolContext, args: JSONObject): ToolResult {
        val app = ctx.app
        val pkg = args.getString("package")
        val intent = app.packageManager.getLaunchIntentForPackage(pkg)
            ?: return ToolResult.fail("no launchable activity for $pkg")
        val display = args.optInt("display", 0)
        // move=false (default): a separate copy, so the user's own task of this app stays where it is.
        // move=true: reuse the app's existing task; Android moves it to the requested display
        // (with the background display the size of the phone screen it is not recreated).
        val move = args.optBoolean("move", false)
        val bounds = args.optJSONArray("bounds")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // A plain launch on the main screen brings the app up as the launcher would.
        if (!move && (display != 0 || bounds != null)) intent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        val opts = ActivityOptions.makeBasic()
        when {
            display != 0 -> {
                // Background displays open in freeform windows on One UI: ask for the whole display.
                val size = Point().also { app.getSystemService(DisplayManager::class.java).getDisplay(display)?.getRealSize(it) }
                opts.setLaunchDisplayId(display)
                if (size.x > 0) opts.setLaunchBounds(Rect(0, 0, size.x, size.y))
                // Own window: the shell helper launches without moving the display to the front,
                // so the user's app keeps the focus (and the keyboard stays). Else a normal launch.
                val viaHelper = ShellBridge.helper?.takeIf { ctx.svc?.let { s -> runCatching { s.bg.active && s.bg.displayId == display }.getOrDefault(false) } == true }
                    ?.let { h -> runCatching { h.launchApp(display, pkg, move) }.onFailure { Log.w("DroidHand", "helper launch failed", it) }.getOrNull() }
                if (viaHelper != null) return ToolResult.ok(JSONObject().put("launched", pkg).put("display", display).put("moved", move).put("via", viaHelper))
                app.startActivity(intent, opts.toBundle())
            }
            else -> {
                // Main screen. With move, explicitly: the task may live on a background display.
                if (move) opts.setLaunchDisplayId(0)
                // One UI: launch bounds on the main display open the app as a pop-up (freeform) window.
                bounds?.let { b -> opts.setLaunchBounds(Rect(b.getInt(0), b.getInt(1), b.getInt(2), b.getInt(3))) }
                app.startActivity(intent, opts.toBundle())
            }
        }
        return ToolResult.ok(JSONObject().put("launched", pkg).put("display", display).put("moved", move))
    }
}
