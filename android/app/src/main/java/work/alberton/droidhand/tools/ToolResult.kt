package work.alberton.droidhand.tools

import org.json.JSONObject

/** Result of running a tool: ok + data, or an error message for the agent. */
data class ToolResult(val ok: Boolean, val data: JSONObject? = null, val error: String? = null) {
    companion object {
        fun ok(data: JSONObject? = null) = ToolResult(true, data)
        fun fail(error: String) = ToolResult(false, error = error)
    }
}
