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

    /** ref -> editable node from the latest snapshot (type_text writes into it without touching). */
    @Volatile
    private var refEditables: Map<String, AccessibilityNodeInfo> = emptyMap()

    private val onMainDisplay get() = svc.displayId == Display.DEFAULT_DISPLAY

    /** The call names one window (`window` argument): never fall back to the focused one. */
    private val windowChosen get() = svc.windowTarget != null

    private fun displayWindows(): List<AccessibilityWindowInfo> =
        if (onMainDisplay) svc.windows else svc.windowsOnAllDisplays.get(svc.displayId) ?: emptyList()

    /**
     * Root of the window the user (or agent) is looking at. rootInActiveWindow is
     * unreliable (null or one of our overlays), so pick from the window list: the
     * active or focused non-overlay window, else the top-most application window.
     */
    private fun targetRoot(): AccessibilityNodeInfo? {
        if (windowChosen) return targetWindow()?.root
        val ws = displayWindows().filter { it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }
        val pick = ws.firstOrNull { it.isActive && it.root != null }
            ?: ws.firstOrNull { it.isFocused && it.root != null }
            ?: ws.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }.maxByOrNull { it.layer }
            ?: ws.maxByOrNull { it.layer }
        return pick?.root ?: if (onMainDisplay) svc.rootInActiveWindow else null
    }

    /**
     * The window the call names with `window`: a window id from list_windows, or a package
     * (its top-most window). Null when none is named or it is not on this display.
     */
    fun targetWindow(): AccessibilityWindowInfo? {
        val t = svc.windowTarget ?: return null
        val ws = displayWindows().filter { it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }
        t.toIntOrNull()?.let { id -> return ws.firstOrNull { it.id == id } }
        return ws.filter { it.root?.packageName?.toString() == t }.maxByOrNull { it.layer }
    }

    /** Screen bounds of the named window (null: none named or not found). */
    fun targetBounds(): Rect? = targetWindow()?.let { w -> Rect().also { w.getBoundsInScreen(it) } }

    fun listWindows(): JSONArray {
        val out = JSONArray()
        for (w in displayWindows()) {
            val b = Rect().also { w.getBoundsInScreen(it) }
            out.put(JSONObject()
                .put("id", w.id)
                .put("type", w.type).put("layer", w.layer).put("active", w.isActive).put("focused", w.isFocused)
                .put("bounds", JSONArray().put(b.left).put(b.top).put(b.right).put(b.bottom))
                .put("title", w.title?.toString() ?: "").put("package", w.root?.packageName?.toString() ?: JSONObject.NULL))
        }
        return out
    }

    /** Windows of [pkg] on the main screen, as (id, bounds). */
    fun windowsOf(pkg: String): List<Pair<Int, Rect>> = svc.windows
        .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.root?.packageName?.toString() == pkg }
        .map { w -> w.id to Rect().also { w.getBoundsInScreen(it) } }

    fun foregroundPackage(): String = targetRoot()?.packageName?.toString() ?: ""

    /** Serialize the active window into a bounded list of @ref nodes. */
    fun snapshot(maxNodes: Int = 300): JSONObject {
        val root = targetRoot() ?: return JSONObject().put("error",
            if (windowChosen) windowNotFound() else "no active window")
        val nodes = JSONArray()
        val points = HashMap<String, IntArray>()
        val clickables = HashMap<String, AccessibilityNodeInfo>()
        val editables = HashMap<String, AccessibilityNodeInfo>()
        walk(root, nodes, points, maxNodes, clickables, editables)
        refPoints = points
        refNodes = clickables
        refEditables = editables
        val out = JSONObject()
        if (windowChosen) targetWindow()?.let { out.put("window", it.id) }
        return out
            .put("package", root.packageName?.toString() ?: "")
            .put("count", nodes.length())
            .put("nodes", nodes)
    }

    private fun walk(
        node: AccessibilityNodeInfo?, out: JSONArray, points: HashMap<String, IntArray>, maxNodes: Int,
        clickables: HashMap<String, AccessibilityNodeInfo>, editables: HashMap<String, AccessibilityNodeInfo>,
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
            if (node.isEditable) editables[ref] = node
        }
        for (i in 0 until node.childCount) walk(node.getChild(i), out, points, maxNodes, clickables, editables)
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

    fun windowNotFound(): String = "window_not_found: no window '${svc.windowTarget}' on this screen " +
        "(it was closed, or its id changed after a new screen): call list_windows"

    /**
     * The focused input field. Never the global focus on a background display or when the call
     * names a window: it may be the user's own field (their keyboard is open there).
     */
    private fun focusedInput(): AccessibilityNodeInfo? =
        (if (onMainDisplay && !windowChosen) svc.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) else null)
            ?: targetRoot()?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

    /** Write into the editable field [ref] directly (no touch, so the user's focus and keyboard stay). */
    fun typeIntoRef(ref: String, text: String): Boolean = runCatching {
        val n = refEditables[ref] ?: return false
        if (!n.refresh() || !n.isVisibleToUser) return false
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args).also { if (it) lastTyped = n }
    }.getOrDefault(false)

    /** Field last written by [typeIntoRef]: not focused, so "enter" goes to it explicitly. */
    @Volatile
    private var lastTyped: AccessibilityNodeInfo? = null

    /** Is the named window the focused one (where keys like back go)? */
    fun targetFocused(): Boolean = targetWindow()?.isFocused == true

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
    fun imeEnter(): Boolean {
        val enter = AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
        if (focusedInput()?.performAction(enter) == true) return true
        // A field written without focus (typeIntoRef): only if it is still in the window we work on.
        if (onMainDisplay && !windowChosen) return false
        val n = lastTyped ?: return false
        return runCatching { n.refresh() && n.performAction(enter) }.getOrDefault(false)
    }
}
