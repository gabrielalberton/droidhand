package work.alberton.droidhand.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The hands: reads the foreground window tree and dispatches gestures. A static
 * reference lets the tool router reach the live service instance.
 *
 * Refs (@1, @2, …) point at the element centers of the latest snapshot, so the
 * agent can `tap {ref}` right after `screen_snapshot`.
 */
class DroidHandAccessibilityService : AccessibilityService() {

    private val shotExecutor = Executors.newSingleThreadExecutor()

    /** ref -> center point from the latest snapshot. */
    @Volatile
    private var refPoints: Map<String, IntArray> = emptyMap()

    lateinit var stopButton: StopButton
        private set

    /** Background display session (native or own window). */
    lateinit var bg: work.alberton.droidhand.bg.BackgroundSession
        private set

    /** Asks before acting on the main screen while the user is using the phone. */
    lateinit var gate: MainScreenGate
        private set

    override fun onServiceConnected() {
        instance = this
        gate = MainScreenGate(this)
        stopButton = StopButton(this) { userStop() }
        // While a background session exists the stop button stays on screen.
        bg = work.alberton.droidhand.bg.BackgroundSession(this) { active -> stopButton.setPinned(active) }
        // The relay comes up with the accessibility service (also after reboots and
        // app updates), as long as the operator has not disconnected it.
        val prefs = work.alberton.droidhand.data.Prefs(this)
        if (prefs.serviceEnabled && prefs.relayUrl.isNotBlank()) {
            runCatching { work.alberton.droidhand.service.RelayService.start(this) }
        }
    }

    /** When the user last tapped Stop (elapsedRealtime), 0 = not pending. Consumed by the tool router. */
    @Volatile var stopRequestedAt = 0L
    @Volatile private var gestureLatch: CountDownLatch? = null

    /**
     * Stop button: cancel the agent's current action and let it know. Unlike
     * [stopAgent] the session stays connected, so nothing has to be re-enabled.
     */
    fun userStop() {
        stopRequestedAt = android.os.SystemClock.elapsedRealtime()
        if (::gate.isInitialized) gate.reset() // leave the user's screen: ask again next time
        gestureLatch?.countDown() // unblock an in-flight gesture; it reports as cancelled
        work.alberton.droidhand.relay.RelayClient.current?.sendEvent("user_stop", org.json.JSONObject())
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(this, work.alberton.droidhand.R.string.float_action_cancelled, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /** Kill switch: stop the agent session now and keep it off until the operator restarts it. */
    fun stopAgent() {
        if (::gate.isInitialized) gate.reset()
        if (::bg.isInitialized) runCatching { bg.close("panic") }
        if (::stopButton.isInitialized) { stopButton.setPinned(false); stopButton.hide() }
        work.alberton.droidhand.service.RelayService.stop(this)
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(this, work.alberton.droidhand.R.string.float_agent_stopped, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        if (::gate.isInitialized) gate.release()
        if (::bg.isInitialized) runCatching { bg.close("service destroyed") }
        if (::stopButton.isInitialized) stopButton.hide()
        if (instance === this) instance = null
        shotExecutor.shutdown()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    /**
     * Root of the window the user is looking at. rootInActiveWindow is unreliable
     * (null or our own panic overlay), so pick from the window list: the active or
     * focused non-overlay window, else the top-most application window.
     */
    /** Display the agent is working on (0 = the phone screen; >0 = background display). */
    @Volatile
    var displayId: Int = Display.DEFAULT_DISPLAY

    private fun displayWindows(): List<android.view.accessibility.AccessibilityWindowInfo> =
        if (displayId == Display.DEFAULT_DISPLAY) windows
        else windowsOnAllDisplays.get(displayId) ?: emptyList()

    private fun targetRoot(): AccessibilityNodeInfo? {
        val ws = displayWindows().filter { it.type != android.view.accessibility.AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }
        val pick = ws.firstOrNull { it.isActive && it.root != null }
            ?: ws.firstOrNull { it.isFocused && it.root != null }
            ?: ws.filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }
                .maxByOrNull { it.layer }
            ?: ws.maxByOrNull { it.layer }
        return pick?.root ?: if (displayId == Display.DEFAULT_DISPLAY) rootInActiveWindow else null
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
        walk(root, nodes, points, maxNodes)
        refPoints = points
        return JSONObject()
            .put("package", root.packageName?.toString() ?: "")
            .put("count", nodes.length())
            .put("nodes", nodes)
    }

    private fun walk(node: AccessibilityNodeInfo?, out: JSONArray, points: HashMap<String, IntArray>, maxNodes: Int) {
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
        }
        for (i in 0 until node.childCount) walk(node.getChild(i), out, points, maxNodes)
    }

    fun pointFor(ref: String): IntArray? = refPoints[ref]

    fun tapAt(x: Int, y: Int, durationMs: Long = 60): Boolean = stroke(Path().apply { moveTo(x.toFloat(), y.toFloat()) }, durationMs)

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean =
        stroke(Path().apply { moveTo(x1.toFloat(), y1.toFloat()); lineTo(x2.toFloat(), y2.toFloat()) }, durationMs)

    private fun stroke(path: Path, durationMs: Long): Boolean {
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .setDisplayId(displayId)
            .build()
        val latch = CountDownLatch(1).also { gestureLatch = it }
        var done = false
        val ok = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { done = true; latch.countDown() }
            override fun onCancelled(g: GestureDescription?) { latch.countDown() }
        }, null)
        if (!ok) return false
        latch.await(durationMs + 2000, TimeUnit.MILLISECONDS)
        return done
    }

    fun typeText(text: String): Boolean {
        // On a background display never use the global focus: it may be the user's field.
        val target = (if (displayId == Display.DEFAULT_DISPLAY) findFocus(AccessibilityNodeInfo.FOCUS_INPUT) else null)
            ?: targetRoot()?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
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
        val node = (if (displayId == Display.DEFAULT_DISPLAY) findFocus(AccessibilityNodeInfo.FOCUS_INPUT) else null)
            ?: targetRoot()?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: return false
        return node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
    }

    fun globalKey(key: String): Boolean = when (key) {
        "enter" -> imeEnter()
        "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
        "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
        "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
        "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
        "quick_settings" -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
        "lock_screen" -> performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        "screenshot" -> performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
        else -> false
    }

    /** JPEG screenshot (base64), scaled to at most [maxWidth] px wide. Blocks the caller (not the main thread). */
    fun capture(maxWidth: Int = 720, quality: Int = 70): JSONObject {
        val latch = CountDownLatch(1)
        var result: JSONObject = JSONObject().put("error", "screenshot timed out")
        takeScreenshot(displayId, shotExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(shot: ScreenshotResult) {
                try {
                    val hw = Bitmap.wrapHardwareBuffer(shot.hardwareBuffer, shot.colorSpace)
                    val bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    shot.hardwareBuffer.close()
                    if (bmp == null) {
                        result = JSONObject().put("error", "could not decode screenshot")
                    } else {
                        val scaled = if (bmp.width > maxWidth) {
                            Bitmap.createScaledBitmap(bmp, maxWidth, bmp.height * maxWidth / bmp.width, true)
                        } else bmp
                        val out = ByteArrayOutputStream()
                        scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
                        result = JSONObject()
                            .put("mimeType", "image/jpeg")
                            .put("width", scaled.width)
                            .put("height", scaled.height)
                            .put("data", Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
                    }
                } catch (e: Exception) {
                    result = JSONObject().put("error", "screenshot failed: ${e.message}")
                }
                latch.countDown()
            }

            override fun onFailure(errorCode: Int) {
                result = JSONObject().put("error", "screenshot failed (code $errorCode; secure screen or rate limit)")
                latch.countDown()
            }
        })
        latch.await(5, TimeUnit.SECONDS)
        return result
    }

    companion object {
        @Volatile
        var instance: DroidHandAccessibilityService? = null
            private set
    }
}
