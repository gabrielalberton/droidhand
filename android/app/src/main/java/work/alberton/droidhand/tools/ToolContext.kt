package work.alberton.droidhand.tools

import android.content.Context
import org.json.JSONObject
import work.alberton.droidhand.a11y.DroidHandAccessibilityService
import work.alberton.droidhand.data.Prefs

/** What a tool handler gets: the app context, settings and the accessibility service (null if disabled). */
class ToolContext(val app: Context, val prefs: Prefs, val svc: DroidHandAccessibilityService?) {
    /** Runs [block] with the accessibility service, or fails if it is not enabled. */
    inline fun withService(block: (DroidHandAccessibilityService) -> ToolResult): ToolResult =
        svc?.let(block) ?: ToolResult.fail(ToolPolicy.NO_SERVICE)
}

/** A group of related tools. Returns null for tools it does not know. */
interface ToolGroup {
    fun handle(tool: String, args: JSONObject, ctx: ToolContext): ToolResult?
}
