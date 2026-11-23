package work.alberton.droidhand.tools

import org.json.JSONObject
import work.alberton.droidhand.adb.AdbSession

/**
 * Phone side of ADB on demand (called by the relay, not offered to agents as-is):
 * the relay's `adb_session_*` tools turn debugging on for a short session and off again.
 */
class AdbTools : ToolGroup {
    override fun handle(tool: String, args: JSONObject, ctx: ToolContext): ToolResult? {
        if (!tool.startsWith("adb_")) return null
        if (!AdbSession.canWrite(ctx.app)) return ToolResult.fail(
            ToolPolicy.NO_WSS)
        return when (tool) {
            "adb_enable" -> {
                val wifi = args.optBoolean("wifi", false)
                val s = AdbSession.status(ctx.app)
                if (wifi && !s.optBoolean("onWifi")) ToolResult.fail(
                    "not_on_wifi: wireless debugging needs a Wi-Fi network (any Wi-Fi, not only home)")
                else ToolResult.ok(AdbSession.enable(ctx.app, args.optInt("ttlSeconds", AdbSession.DEFAULT_TTL_S), wifi))
            }
            "adb_wifi_off" -> ToolResult.ok(AdbSession.wifiOff(ctx.app))
            "adb_disable" -> ToolResult.ok(AdbSession.disable(ctx.app, args.optString("why", "session_end")))
            "adb_status" -> ToolResult.ok(AdbSession.status(ctx.app))
            "adb_local_ports" -> ToolResult.ok(JSONObject().put("ports", AdbSession.localPorts()))
            else -> null
        }
    }
}
