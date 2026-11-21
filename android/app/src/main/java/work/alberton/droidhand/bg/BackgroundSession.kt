package work.alberton.droidhand.bg

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.MotionEvent
import android.view.Surface
import android.widget.Toast
import org.json.JSONObject
import work.alberton.droidhand.data.Prefs
import work.alberton.droidhand.shell.ShellBridge
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A background display where the agent works while the user keeps using the phone.
 *
 * Two methods (the user picks one in the app, or lets the agent decide):
 * - NATIVE: `overlay_display_devices` (needs only WRITE_SECURE_SETTINGS; works with
 *   debugging OFF). The window is drawn by the system: it cannot be hidden or made
 *   opaque; we add a handle that closes it when dragged onto the X.
 * - OWN: a TRUSTED virtual display created by the shell helper (needs debugging ON
 *   while it runs) rendering into our own [MiniWindow]: opaque/translucent, resizable,
 *   hideable while the agent keeps working, with touch passthrough.
 */
class BackgroundSession(
    private val ctx: Context,
    private val onActiveChanged: (Boolean) -> Unit,
    /** Main thread: makes sure the stop button is on screen; true if it is. */
    private val ensureStopButton: () -> Boolean,
) {
    enum class Mode { NATIVE, OWN }

    private val main = Handler(Looper.getMainLooper())
    private val prefs = Prefs(ctx)
    private val dm = ctx.getSystemService(DisplayManager::class.java)
    val bubbles = Bubbles(ctx, this)
    private val window = MiniWindow(ctx, this)

    @Volatile var mode: Mode? = null; private set
    @Volatile var displayId = -1; private set
    @Volatile var width = 720; private set
    @Volatile var height = 1560; private set
    @Volatile var dpi = 320; private set
    @Volatile var hidden = false; private set
    @Volatile private var helpPending: String? = null

    /**
     * While hidden the display still needs a surface: with none, Android turns the
     * display OFF, apps stop and accessibility no longer sees them. Frames go here
     * and are dropped.
     */
    private var sink: android.media.ImageReader? = null
    private val sinkThread by lazy { android.os.HandlerThread("droidhand-sink").apply { start() } }

    val active: Boolean get() = mode != null
    val opacity: Float get() = prefs.windowOpacity

    init {
        ShellBridge.onDetached = { if (mode == Mode.OWN) main.post { tearDown("helper gone (debugging turned off?)") } }
    }

    // ---- Agent-facing API (called from the tool thread) ----

    fun open(requested: String, w: Int, h: Int, density: Int): JSONObject {
        val pref = prefs.bgMode
        val forced = pref != Prefs.BG_AGENT
        val want = if (forced) pref else requested
        val chosen = when (want) {
            Prefs.BG_NATIVE -> Mode.NATIVE
            Prefs.BG_OWN -> Mode.OWN
            else -> if (ShellBridge.attached) Mode.OWN else Mode.NATIVE // "auto"
        }
        if (active) {
            if (mode == chosen) return status().put("reused", true)
            close("switching mode")
        }
        width = w; height = h; dpi = density
        when (chosen) {
            Mode.OWN -> {
                val helper = ShellBridge.helper ?: throw IllegalStateException(
                    "own-window method needs the shell helper: call own_helper_start (requires debugging ON)" +
                        if (forced) "" else ", or use mode=native")
                displayId = helper.createDisplay("DroidHand", w, h, density, null)
                mode = Mode.OWN; hidden = false
                onMain { attachSurface(null) } // keep the display on until our window has a surface
                onMain { window.show(); bubbles.showMinimize() }
            }
            Mode.NATIVE -> {
                val before = dm.displays.map { it.displayId }.toSet()
                Settings.Global.putString(ctx.contentResolver, OVERLAY_KEY, "${w}x$h/$density")
                val id = waitForNewDisplay(before)
                    ?: throw IllegalStateException("native overlay display did not appear")
                displayId = id; mode = Mode.NATIVE; hidden = false
                onMain { bubbles.showNativeHandle() }
            }
        }
        onActiveChanged(true)
        ensureIndicators()?.let { why ->
            throw IllegalStateException("$INDICATORS_ERROR ($why). The background session was closed.")
        }
        return status().put("forcedByUser", forced)
    }

    /**
     * The agent may only work in the background while the user can see it: the stop
     * button plus the window itself (own, shown), the eye bubble (own, hidden) or the
     * bubble (native). Missing ones are re-added; a hidden window whose eye cannot be
     * shown is shown instead. If they still cannot be shown, the session is CLOSED.
     * Returns null when everything is on screen, else why not.
     */
    fun ensureIndicators(): String? {
        val m = mode ?: return null
        val result = java.util.concurrent.atomic.AtomicReference<String?>("timed out checking the on-screen controls")
        onMain {
            result.set(when {
                !ensureStopButton() -> "the stop button could not be shown"
                m == Mode.NATIVE -> if (bubbles.ensureNativeHandle()) null else "the window handle could not be shown"
                hidden && bubbles.ensureEye() -> null
                hidden -> {
                    // No eye: never keep working invisibly; show the window instead.
                    bubbles.hideEye(); hidden = false
                    if (window.ensureShown()) { helpPending?.let { window.setBanner(it) }; bubbles.ensureMinimize(); null }
                    else "neither the eye bubble nor the window could be shown"
                }
                else -> if (window.ensureShown()) { bubbles.ensureMinimize(); null } else "the background window could not be shown"
            })
        }
        val why = result.get() ?: return null
        android.util.Log.w("DroidHand", "background indicators missing: $why")
        close("indicators not visible: $why")
        main.post { Toast.makeText(ctx, work.alberton.droidhand.R.string.bg_closed_no_controls, Toast.LENGTH_LONG).show() }
        return why
    }

    fun close(reason: String) {
        val m = mode ?: return
        when (m) {
            Mode.OWN -> runCatching { ShellBridge.helper?.releaseDisplay(displayId) }
            Mode.NATIVE -> Settings.Global.putString(ctx.contentResolver, OVERLAY_KEY, null)
        }
        tearDown(reason)
    }

    fun hide(): Boolean {
        if (mode != Mode.OWN) return false
        hidden = true
        onMain { window.hide(); bubbles.showEye() }
        return true
    }

    fun show(): Boolean {
        if (mode != Mode.OWN) return mode == Mode.NATIVE
        hidden = false
        onMain { window.show(); bubbles.showMinimize(); helpPending?.let { window.setBanner(it) } }
        return true
    }

    /** Ask the user to take over (e.g. captcha): show the window with a message. */
    fun requestHelp(message: String): JSONObject {
        helpPending = message
        if (mode == Mode.OWN) show() else onMain { Toast.makeText(ctx, "DroidHand: $message", Toast.LENGTH_LONG).show() }
        onMain { window.setBanner(message) }
        return JSONObject().put("shown", active).put("message", message)
    }

    fun helpDone() { helpPending = null }

    fun setOpacity(a: Float) = onMain { window.setOpacity(a) }

    fun status(): JSONObject = JSONObject()
        .put("active", active)
        .put("mode", mode?.name?.lowercase() ?: JSONObject.NULL)
        .put("displayId", if (active) displayId else JSONObject.NULL)
        .put("width", width).put("height", height).put("dpi", dpi)
        .put("hidden", hidden)
        .put("helpPending", helpPending ?: JSONObject.NULL)
        .put("userChoice", prefs.bgMode)
        .put("ownAvailable", ShellBridge.attached)

    // ---- Input into the OWN display ----

    fun key(code: Int): Boolean =
        mode == Mode.OWN && runCatching { ShellBridge.helper?.injectKey(displayId, code) == true }.getOrDefault(false)

    fun injectTouch(e: MotionEvent) {
        if (mode != Mode.OWN) return
        runCatching { ShellBridge.helper?.injectMotion(displayId, e) }
    }

    /** Our window's surface came (s) or went (null): fall back to the frame sink. */
    fun attachSurface(s: Surface?) {
        if (mode != Mode.OWN) return
        if (s != null) {
            runCatching { ShellBridge.helper?.setSurface(displayId, s) }
            closeSink()
        } else {
            val r = sink ?: android.media.ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, 2).also { r ->
                r.setOnImageAvailableListener({ runCatching { it.acquireLatestImage()?.close() } }, android.os.Handler(sinkThread.looper))
                sink = r
            }
            runCatching { ShellBridge.helper?.setSurface(displayId, r.surface) }
        }
    }

    private fun closeSink() { sink?.close(); sink = null }

    // ---- internals ----

    private fun tearDown(reason: String) {
        mode = null; displayId = -1; hidden = false; helpPending = null
        onMain { window.hide(); bubbles.hideAll(); closeSink() }
        onActiveChanged(false)
        android.util.Log.i("DroidHand", "background session closed: $reason")
    }

    private fun waitForNewDisplay(before: Set<Int>): Int? {
        repeat(40) {
            dm.displays.map { it.displayId }.firstOrNull { it !in before }?.let { return it }
            Thread.sleep(100)
        }
        return null
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) { block(); return }
        val latch = CountDownLatch(1)
        main.post { try { block() } finally { latch.countDown() } }
        latch.await(3, TimeUnit.SECONDS)
    }

    companion object {
        const val OVERLAY_KEY = "overlay_display_devices"
        const val INDICATORS_ERROR = "indicators_not_visible: the on-screen controls (stop button / eye / window) " +
            "could not be shown, so the agent must not work in the background"
    }
}
