package work.alberton.droidhand.suggestions

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * On-device feature-suggestion log (the source of truth). Appends one JSON object
 * per line in the app's private storage. The agent writes here only when the
 * operator enabled suggestion logging (OFF by default); see [work.alberton.droidhand.data.Prefs].
 */
class SuggestionStore(context: Context) {
    private val file = File(context.filesDir, "suggestions.jsonl")

    fun add(obj: JSONObject): JSONObject {
        val entry = JSONObject(obj.toString())
        if (!entry.has("id")) entry.put("id", UUID.randomUUID().toString())
        if (!entry.has("ts")) entry.put("ts", System.currentTimeMillis())
        file.appendText(entry.toString() + "\n")
        return entry
    }

    fun list(): List<JSONObject> {
        if (!file.exists()) return emptyList()
        return file.readLines().filter { it.isNotBlank() }.map { JSONObject(it) }
    }

    fun exportMarkdown(): String {
        val items = list()
        if (items.isEmpty()) return "# DroidHand — Feature suggestions\n\n_No suggestions logged yet._"
        val sb = StringBuilder("# DroidHand — Feature suggestions\n\n")
        for (s in items) {
            val p = s.optInt("priority", 3)
            sb.append("- **[P$p] ${s.optString("title")}** — ${s.optString("detail")}")
            val ctx = s.optString("context", "")
            if (ctx.isNotBlank()) sb.append("  _(context: $ctx)_")
            sb.append("\n")
        }
        return sb.toString()
    }
}
