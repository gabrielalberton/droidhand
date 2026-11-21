package work.alberton.droidhand.tools

import android.app.ActivityOptions
import android.content.Intent
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.net.Uri
import org.json.JSONObject

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
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        when {
            display != 0 -> {
                intent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                // Background displays open in freeform windows on One UI: ask for the whole display.
                val size = Point().also { app.getSystemService(DisplayManager::class.java).getDisplay(display)?.getRealSize(it) }
                val opts = ActivityOptions.makeBasic().setLaunchDisplayId(display)
                if (size.x > 0) opts.setLaunchBounds(Rect(0, 0, size.x, size.y))
                app.startActivity(intent, opts.toBundle())
            }
            args.has("bounds") -> {
                // One UI: launch bounds on the main display open the app as a pop-up (freeform) window.
                val b = args.getJSONArray("bounds")
                intent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                val opts = ActivityOptions.makeBasic().setLaunchBounds(Rect(b.getInt(0), b.getInt(1), b.getInt(2), b.getInt(3)))
                app.startActivity(intent, opts.toBundle())
            }
            else -> app.startActivity(intent)
        }
        return ToolResult.ok(JSONObject().put("launched", pkg).put("display", display))
    }
}
