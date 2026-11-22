package work.alberton.droidhand.a11y

import android.graphics.Rect
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * The eyes: reads the window tree of the display the agent works on, turns it
 * into a bounded list of @ref nodes and types into text fields.
 *
 * Refs (@1, @2, …) point at the element centers of the latest snapshot, so the
 * agent can `tap {ref}` right after `screen_snapshot`.
 */
internal class ScreenReader(private val svc: DroidHandAccessibilityService) {

    /** ref -> center point from the latest snapshot. */
    @Volatile
    private var refPoints: Map<String, IntArray> = emptyMap()

    /** ref -> node to click from the latest snapshot (the node or its nearest clickable ancestor). */
    @Volatile
    private var refNodes: Map<String, AccessibilityNodeInfo> = emptyMap()

    private val onMainDisplay get() = svc.displayId == Display.DEFAULT_DISPLAY

    private fun displayWindows(): List<AccessibilityWindowInfo> =
        if (onMainDisplay) svc.windows else svc.windowsOnAllDisplays.get(svc.displayId) ?: emptyList()

    /**
     * Root of the window the user (or agent) is looking at. rootInActiveWindow is
     * unreliable (null or one of our overlays), so pick from the window list: the
     * active or focused non-overlay window, else the top-most application window.
     */
    private fun targetRoot(): AccessibilityNodeInfo? {
        val ws = displayWindows().filter { it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }
        val pick = ws.firstOrNull { it.isActive && it.root != null }
            ?: ws.firstOrNull { it.isFocused && it.root != null }
            ?: ws.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }.maxByOrNull { it.layer }
            ?: ws.maxByOrNull { it.layer }
        return pick?.root ?: if (onMainDisplay) svc.rootInActiveWindow else null
    }

    fun listWindows(): JSONArray {
        val out = JSONArray()
        for (w in displayWindows()) {
            out.put(JSONObject()
                .put("type", w.type).put("layer", w.layer).put("active", w.isActive).put("focused", w.isFocused)
                .put("title", w.title?.toString() ?: "").put("package", w.root?.packageName?.toString() ?: JSONObject.NULL))
        }
        return out
    }

    fun foregroundPackage(): String = targetRoot()?.packageName?.toString() ?: ""

    /** Serialize the active window into a bounded list of @ref nodes. */
    fun snapshot(maxNodes: Int = 300): JSONObject {
        val root = targetRoot() ?: return JSONObject().put("error", "no active window")
        val nodes = JSONArray()
        val points = HashMap<String, IntArray>()
        val clickables = HashMap<String, AccessibilityNodeInfo>()
        walk(root, nodes, points, maxNodes, clickables)
        refPoints = points
        refNodes = clickables
        return JSONObject()
            .put("package", root.packageName?.toString() ?: "")
            .put("count", nodes.length())
            .put("nodes", nodes)
    }

    private fun walk(
        node: AccessibilityNodeInfo?, out: JSONArray, points: HashMap<String, IntArray>, maxNodes: Int,
        clickables: HashMap<String, AccessibilityNodeInfo>,
    ) {
        if (node == null || out.length() >= maxNodes) return
        if (!node.isVisibleToUser) return
        val text = node.text?.toString()
        val desc = node.contentDescription?.toString()
        val actionable = node.isClickable || node.isEditable || node.isCheckable || node.isScrollable
        if (!text.isNullOrBlank() || !desc.isNullOrBlank() || actionable) {
            val ref = "@${out.length() + 1}"
            val b = Rect().also { node.getBoundsInScreen(it) }
            val o = JSONObject()
                .put("ref", ref)
                .put("role", node.className?.toString()?.substringAfterLast('.') ?: "")
                .put("x", b.centerX())
                .put("y", b.centerY())
            if (!text.isNullOrBlank()) o.put("text", if (node.isPassword) "••••" else text.take(200))
            if (!desc.isNullOrBlank()) o.put("desc", desc.take(200))
            node.viewIdResourceName?.let { o.put("id", it.substringAfter(":id/")) }
            if (node.isClickable) o.put("clickable", true)
            if (node.isEditable) o.put("editable", true)
            if (node.isCheckable) o.put("checked", node.isChecked)
            if (node.isScrollable) o.put("scrollable", true)
            out.put(o)
            points[ref] = intArrayOf(b.centerX(), b.centerY())
            clickableSelfOrAncestor(node)?.let { clickables[ref] = it }
        }
        for (i in 0 until node.childCount) walk(node.getChild(i), out, points, maxNodes, clickables)
    }

    fun pointFor(ref: String): IntArray? = refPoints[ref]

    /**
     * Click [ref] through the accessibility action instead of a touch: nothing goes
     * through the input system, so the user's screen keeps the focus. False if the
     * element has no clickable node or the app ignores the action (then tap instead).
     */
    /**
     * Scroll the biggest scrollable view on screen with an accessibility action (no touch,
     * so a background display never takes the focus). False if nothing could scroll.
     */
    fun scrollAction(direction: String): Boolean = runCatching {
        val root = targetRoot() ?: return false
        val found = ArrayList<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 40) return
            if (n.isScrollable && n.isVisibleToUser) found.add(n)
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        val area = { n: AccessibilityNodeInfo -> android.graphics.Rect().also { n.getBoundsInScreen(it) }.let { it.width() * it.height() } }
        val action = when (direction) {
            "down" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN
            "up" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP
            "left" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT
            else -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT
        }
        val fallback = if (direction == "down" || direction == "right") AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        found.sortedByDescending(area).any { n ->
            (n.actionList.any { it.id == action.id } && n.performAction(action.id)) || n.performAction(fallback)
        }
    }.getOrDefault(false)

    fun clickRef(ref: String): Boolean = runCatching {
        refNodes[ref]?.let { it.refresh() && it.isVisibleToUser && it.performAction(AccessibilityNodeInfo.ACTION_CLICK) } == true
    }.getOrDefault(false)

    private fun clickableSelfOrAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var n: AccessibilityNodeInfo? = node
        repeat(4) {
            val cur = n ?: return null
            if (cur.isClickable && cur.isEnabled) return cur
            n = cur.parent
        }
        return null
    }

    /** The focused input field; on a background display never the global focus (it may be the user's field). */
    private fun focusedInput(): AccessibilityNodeInfo? =
        (if (onMainDisplay) svc.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) else null)
            ?: targetRoot()?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

    fun typeText(text: String): Boolean {
        val target = focusedInput()
            ?: targetRoot()?.let { firstEditable(it) }?.also {
                it.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                it.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /** Prefer a focused editable field, else the first visible editable one. */
    private fun firstEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var first: AccessibilityNodeInfo? = null
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            if (n.isEditable && n.isVisibleToUser) {
                if (n.isFocused) return n
                if (first == null) first = n
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return first
    }

    /** "Enter" on the focused field (IME action: search/go/send/next). */
    fun imeEnter(): Boolean =
        focusedInput()?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id) ?: false
}
