package work.alberton.droidhand.tools

import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display
import org.json.JSONObject
import work.alberton.droidhand.bg.BackgroundSession

/** The background display (native or own window) where the agent works while the user uses the phone. */
class BackgroundTools : ToolGroup {
    override fun handle(tool: String, args: JSONObject, ctx: ToolContext): ToolResult? = when (tool) {
        "bg_open" -> ctx.withService { s ->
            // Default: the phone screen's own size and density (portrait), so apps moved
            // between the screens keep running instead of being recreated.
            val m = ctx.app.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
                .let { d -> DisplayMetrics().also { d.getRealMetrics(it) } }
            ToolResult.ok(s.bg.open(args.optString("mode", "auto"),
                args.optInt("width", minOf(m.widthPixels, m.heightPixels)),
                args.optInt("height", maxOf(m.widthPixels, m.heightPixels)),
                args.optInt("dpi", m.densityDpi)))
        }
        "bg_close" -> ctx.withService { s -> s.bg.close("agent"); ToolResult.ok(s.bg.status()) }
        "bg_hide" -> ctx.withService { s ->
            if (!s.bg.hide()) return@withService ToolResult.fail("only the own-window method can be hidden")
            // If the eye could not be shown the window stays visible (hidden=false in the status).
            indicatorsOr(s.bg) { ToolResult.ok(s.bg.status()) }
        }
        "bg_show" -> ctx.withService { s ->
            s.bg.show()
            indicatorsOr(s.bg) { ToolResult.ok(s.bg.status()) }
        }
        "bg_status" -> ctx.withService { s -> ToolResult.ok(s.bg.status()) }
        "request_user_help" -> ctx.withService { s -> ToolResult.ok(s.bg.requestHelp(args.getString("message"))) }
        else -> null
    }

    private inline fun indicatorsOr(bg: BackgroundSession, ok: () -> ToolResult): ToolResult =
        bg.ensureIndicators()?.let { why ->
            ToolResult.fail("${BackgroundSession.INDICATORS_ERROR} ($why). The background session was closed.")
        } ?: ok()
}
