package work.alberton.droidhand.a11y

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import work.alberton.droidhand.R
import work.alberton.droidhand.bg.BackgroundSession
import work.alberton.droidhand.data.Prefs
import work.alberton.droidhand.relay.RelayClient
import work.alberton.droidhand.safety.MainScreenGate
import work.alberton.droidhand.safety.StopButton
import work.alberton.droidhand.service.RelayService

/**
 * DroidHand's accessibility service: the live object the tool router talks to.
 * It owns the screen reader, gestures and screenshots, the safety overlays
 * (Stop button, main-screen confirmation) and the background display session.
 * A static reference lets the tool router reach the running instance.
 */
class DroidHandAccessibilityService : AccessibilityService() {

    private val reader = ScreenReader(this)
    private val gestures = Gestures(this)
    private val screenshots = ScreenCapture(this)

    /** Display the agent is working on (0 = the phone screen; >0 = background display). Set per tool call. */
    @Volatile
    var displayId: Int = Display.DEFAULT_DISPLAY

    /** When the user last tapped Stop (elapsedRealtime), 0 = not pending. Consumed by the tool router. */
    @Volatile
    var stopRequestedAt = 0L

    lateinit var stopButton: StopButton
        private set

    /** Background display session (native or own window). */
    lateinit var bg: BackgroundSession
        private set

    /** Asks before acting on the main screen while the user is using the phone. */
    lateinit var gate: MainScreenGate
        private set

    override fun onServiceConnected() {
        instance = this
        work.alberton.droidhand.adb.AdbSession.sweep(this)
        gate = MainScreenGate(this)
        stopButton = StopButton(this) { userStop() }
        // While a background session exists the Stop button stays on screen.
        bg = BackgroundSession(
            this,
            onActiveChanged = { active -> stopButton.setPinned(active) },
            ensureStopButton = { stopButton.ensureOnScreen() },
            raiseStopButton = { stopButton.raise() },
        )
        // The relay comes up with the accessibility service (also after reboots and
        // app updates), as long as the operator has not disconnected it.
        val prefs = Prefs(this)
        if (prefs.serviceEnabled && prefs.relayUrl.isNotBlank()) {
            runCatching { RelayService.start(this) }
        }
    }

    /**
     * Stop button: cancel the agent's current action and let it know. Unlike
     * [stopAgent] the session stays connected, so nothing has to be re-enabled.
     */
    fun userStop() {
        stopRequestedAt = SystemClock.elapsedRealtime()
        if (::gate.isInitialized) gate.reset() // leave the user's screen: ask again next time
        gestures.cancelInFlight() // an in-flight gesture reports as cancelled
        RelayClient.current?.sendEvent("user_stop", JSONObject())
        toast(R.string.float_action_cancelled)
    }

    /** Kill switch: stop the agent session now and keep it off until the operator restarts it. */
    fun stopAgent() {
        clearAgentUi()
        RelayService.stop(this)
        toast(R.string.float_agent_stopped)
    }

    /** Leaves nothing of the agent on screen: closes the background window, Stop button and pending prompt. */
    fun clearAgentUi() {
        if (::gate.isInitialized) gate.reset()
        if (::bg.isInitialized) runCatching { bg.close("panic") }
        if (::stopButton.isInitialized) { stopButton.setPinned(false); stopButton.hide() }
    }

    override fun onDestroy() {
        if (::gate.isInitialized) gate.release()
        if (::bg.isInitialized) runCatching { bg.close("service destroyed") }
        if (::stopButton.isInitialized) stopButton.hide()
        if (instance === this) instance = null
        screenshots.shutdown()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private fun toast(res: Int) = Handler(Looper.getMainLooper()).post {
        Toast.makeText(this, res, Toast.LENGTH_SHORT).show()
    }

    // ---- Perception ----
    fun listWindows(): JSONArray = reader.listWindows()
    fun foregroundPackage(): String = reader.foregroundPackage()
    fun snapshot(maxNodes: Int = 300): JSONObject = reader.snapshot(maxNodes)
    fun pointFor(ref: String): IntArray? = reader.pointFor(ref)
    fun clickRef(ref: String): Boolean = reader.clickRef(ref)
    fun scrollAction(direction: String): Boolean = reader.scrollAction(direction)
    fun capture(maxWidth: Int = 720, quality: Int = 70): JSONObject = screenshots.capture(maxWidth, quality)

    // ---- Interaction ----
    fun tapAt(x: Int, y: Int, durationMs: Long = 60): Boolean {
        avoidStop(listOf(intArrayOf(x, y)))
        return gestures.tap(x, y, durationMs)
    }
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean {
        avoidStop((0..20).map { intArrayOf(x1 + (x2 - x1) * it / 20, y1 + (y2 - y1) * it / 20) })
        return gestures.swipe(x1, y1, x2, y2, durationMs)
    }

    /** The Stop button lives on the main screen: keep the agent's own gestures off it. */
    private fun avoidStop(points: List<IntArray>) {
        if (displayId == 0 && ::stopButton.isInitialized) runCatching { stopButton.avoid(points) }
    }
    fun typeText(text: String): Boolean = reader.typeText(text)
    fun imeEnter(): Boolean = reader.imeEnter()

    fun globalKey(key: String): Boolean = when (key) {
        "enter" -> imeEnter()
        "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
        "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
        "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
        "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
        "quick_settings" -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
        "lock_screen" -> performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        "screenshot" -> performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
        "split_screen" -> performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
        else -> false
    }

    companion object {
        @Volatile
        var instance: DroidHandAccessibilityService? = null
            private set
    }
}
