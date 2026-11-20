package work.alberton.droidhand.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * The hands: reads the foreground window tree and dispatches gestures. A static
 * reference lets the tool router reach the live service instance.
 *
 * NOTE: P1 skeleton — not yet built or device-tested. The snapshot/ref scheme is
 * intentionally simple (content-identity refs come in P1 hardening).
 */
class DroidHandAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* P1: push events to relay */ }
    override fun onInterrupt() {}

    /** Serialize the active window into a bounded tree with @refs. */
    fun snapshot(): JSONObject {
        val root = rootInActiveWindow ?: return JSONObject().put("error", "no active window")
        val nodes = JSONArray()
        val counter = intArrayOf(0)
        walk(root, nodes, counter, depth = 0, maxNodes = 300)
        return JSONObject()
            .put("package", root.packageName?.toString() ?: "")
            .put("nodes", nodes)
    }

    private fun walk(node: AccessibilityNodeInfo?, out: JSONArray, counter: IntArray, depth: Int, maxNodes: Int) {
        if (node == null || counter[0] >= maxNodes) return
        val text = node.text?.toString()
        val desc = node.contentDescription?.toString()
        val id = node.viewIdResourceName
        if (!text.isNullOrBlank() || !desc.isNullOrBlank() || node.isClickable) {
            counter[0] += 1
            val bounds = android.graphics.Rect().also { node.getBoundsInScreen(it) }
            out.put(
                JSONObject()
                    .put("ref", "@${counter[0]}")
                    .put("role", node.className?.toString() ?: "")
                    .put("text", text ?: "")
                    .put("desc", desc ?: "")
                    .put("id", id ?: "")
                    .put("clickable", node.isClickable)
                    .put("cx", bounds.centerX())
                    .put("cy", bounds.centerY()),
            )
        }
        for (i in 0 until node.childCount) walk(node.getChild(i), out, counter, depth + 1, maxNodes)
    }

    fun tapAt(x: Int, y: Int): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun typeText(text: String): Boolean {
        val focus = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return focus.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun globalKey(key: String): Boolean = when (key) {
        "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
        "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
        "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
        "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
        "quick_settings" -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
        "lock_screen" -> performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        else -> false
    }

    companion object {
        @Volatile
        var instance: DroidHandAccessibilityService? = null
            private set
    }
}
