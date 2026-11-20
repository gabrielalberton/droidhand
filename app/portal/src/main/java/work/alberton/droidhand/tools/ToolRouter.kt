package work.alberton.droidhand.tools

import android.content.Context
import android.os.Build
import work.alberton.droidhand.a11y.DroidHandAccessibilityService
import work.alberton.droidhand.data.Prefs
import work.alberton.droidhand.suggestions.SuggestionStore
import org.json.JSONObject

/** Result of running a tool: ok + data, or an error. */
data class ToolResult(val ok: Boolean, val data: JSONObject? = null, val error: String? = null)

/**
 * Maps a tool name + args to an action on the device. P1 implements the core
 * perception/interaction set, the on-device suggestion sink, and device info;
 * the rest return not_implemented until later phases.
 */
class ToolRouter(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = Prefs(appContext)
    private val suggestions = SuggestionStore(appContext)

    fun run(tool: String, args: JSONObject): ToolResult {
        val svc = DroidHandAccessibilityService.instance
        return when (tool) {
            "get_device_info" -> ToolResult(
                true,
                JSONObject()
                    .put("model", Build.MODEL)
                    .put("androidSdk", Build.VERSION.SDK_INT)
                    .put("suggestionLogging", prefs.suggestionLogging),
            )

            "store_suggestion" -> {
                if (!prefs.suggestionLogging) return ToolResult(false, error = "suggestion logging disabled on device")
                val saved = suggestions.add(args)
                ToolResult(true, JSONObject().put("stored", true).put("id", saved.optString("id")))
            }
            "list_suggestions" -> ToolResult(true, JSONObject().put("items", suggestions.list().toString()))

            "screen_snapshot" -> svc?.let { ToolResult(true, it.snapshot()) }
                ?: ToolResult(false, error = "accessibility service not enabled")

            "tap" -> withService(svc) {
                val ok = it.tapAt(args.getInt("x"), args.getInt("y"))
                ToolResult(ok, JSONObject().put("tapped", ok))
            }
            "swipe" -> withService(svc) {
                val ok = it.swipe(
                    args.getInt("fromX"), args.getInt("fromY"),
                    args.getInt("toX"), args.getInt("toY"),
                    args.optLong("durationMs", 200),
                )
                ToolResult(ok, JSONObject().put("swiped", ok))
            }
            "type_text" -> withService(svc) {
                val ok = it.typeText(args.getString("text"))
                ToolResult(ok, JSONObject().put("typed", ok))
            }
            "key_event" -> withService(svc) {
                val ok = it.globalKey(args.getString("key"))
                ToolResult(ok, JSONObject().put("key", ok))
            }
            "global_action" -> withService(svc) {
                val ok = it.globalKey(args.getString("action"))
                ToolResult(ok, JSONObject().put("done", ok))
            }
            "go_home" -> withService(svc) { ToolResult(it.globalKey("home")) }

            else -> ToolResult(false, error = "not_implemented: $tool")
        }
    }

    private inline fun withService(
        svc: DroidHandAccessibilityService?,
        block: (DroidHandAccessibilityService) -> ToolResult,
    ): ToolResult = svc?.let(block) ?: ToolResult(false, error = "accessibility service not enabled")
}
