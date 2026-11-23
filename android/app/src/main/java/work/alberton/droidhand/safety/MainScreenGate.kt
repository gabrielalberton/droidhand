package work.alberton.droidhand.safety

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import work.alberton.droidhand.R
import work.alberton.droidhand.data.Prefs
import work.alberton.droidhand.bg.dp
import work.alberton.droidhand.bg.overlayParams
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Asks the user before the agent acts on the main screen while they are using
 * the phone (screen on and unlocked). Background windows are never gated.
 *
 * One "Allow" covers the agent's work until the screen turns off or the agent
 * stays idle for [APPROVAL_IDLE_MS]. "Not now" blocks main-screen actions for
 * [DENY_COOLDOWN_MS] without asking again, so the agent cannot nag.
 */
class MainScreenGate(private val svc: AccessibilityService) {
    private val main = Handler(Looper.getMainLooper())
    private val prefs = Prefs(svc)
    private val wm = svc.getSystemService(WindowManager::class.java)

    @Volatile private var approved = false
    @Volatile private var lastAct = 0L
    @Volatile private var deniedAt = 0L
    @Volatile private var pending: CountDownLatch? = null
    @Volatile private var answer: Boolean? = null
    private var view: View? = null
    private val autoDismiss = Runnable { dismiss() }

    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = reset()
    }

    init {
        svc.registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF))
    }

    fun release() {
        runCatching { svc.unregisterReceiver(screenOff) }
        reset()
    }

    /** Forget any approval and close the prompt (screen off, Stop). */
    fun reset() {
        approved = false
        deniedAt = 0
        answer = false
        pending?.countDown()
        dismiss()
    }

    /**
     * The agent itself just unlocked the phone (unlock_device, PIN the user saved for
     * this): nobody else is using it, so do not ask. Lasts like an "Allow": until the
     * screen turns off or the agent stays idle for [APPROVAL_IDLE_MS].
     */
    fun agentUnlocked() {
        approved = true
        lastAct = SystemClock.elapsedRealtime()
    }

    private fun userIsUsingPhone(): Boolean {
        val interactive = svc.getSystemService(PowerManager::class.java)?.isInteractive == true
        val locked = svc.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        return interactive && !locked
    }

    /**
     * Called on the tool thread before a main-screen action. Returns null to
     * proceed, or an error for the agent. Blocks up to [WAIT_MS] for an answer.
     */
    fun check(): String? {
        if (!prefs.askMainScreen || !userIsUsingPhone()) return null
        val now = SystemClock.elapsedRealtime()
        if (approved && now - lastAct < APPROVAL_IDLE_MS) { lastAct = now; return null }
        approved = false
        if (deniedAt != 0L && now - deniedAt < DENY_COOLDOWN_MS) return DECLINED

        val latch = pending ?: CountDownLatch(1).also { l ->
            pending = l
            answer = null
            main.post { show() }
        }
        latch.await(WAIT_MS, TimeUnit.MILLISECONDS)
        return when (answer) {
            true -> { lastAct = SystemClock.elapsedRealtime(); null }
            // reset() (screen off, Stop, service restarted) also answers false, but the user said nothing.
            false -> if (deniedAt == 0L) CLOSED else DECLINED
            null -> WAITING // prompt stays up a little longer; a retry gets the answer
        }
    }

    private fun decide(allow: Boolean) {
        answer = allow
        if (allow) { approved = true; lastAct = SystemClock.elapsedRealtime() } else deniedAt = SystemClock.elapsedRealtime()
        pending?.countDown()
        pending = null
        dismiss()
    }

    private fun dismiss() {
        main.post {
            main.removeCallbacks(autoDismiss)
            view?.let { runCatching { wm.removeView(it) } }
            view = null
            if (answer == null) pending = null // expired unanswered: ask again next time
        }
    }

    // ---- The prompt: a card at the bottom, in the system's light/dark style ----

    private fun show() {
        if (view != null) return
        val dark = (svc.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val bg = if (dark) Color.parseColor("#1E2127") else Color.WHITE
        val fg = if (dark) Color.parseColor("#E7E8EB") else Color.parseColor("#14161A")
        val sub = if (dark) Color.parseColor("#A3A7B0") else Color.parseColor("#5D636E")
        val accent = if (dark) Color.parseColor("#A5B4FC") else Color.parseColor("#4F46E5")
        val onAccent = if (dark) Color.parseColor("#0E0F13") else Color.WHITE
        val c = svc

        fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(c).apply {
            text = s; setTextColor(color); setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        fun button(s: String, fill: Int, color: Int, onClick: () -> Unit) = text(s, 15f, color, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(c.dp(20f), 0, c.dp(20f), 0)
            background = GradientDrawable().apply { cornerRadius = c.dp(14f).toFloat(); setColor(fill) }
            setOnClickListener { onClick() }
        }

        val card = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(c.dp(20f), c.dp(18f), c.dp(20f), c.dp(16f))
            background = GradientDrawable().apply {
                cornerRadius = c.dp(24f).toFloat(); setColor(bg)
                setStroke(c.dp(1f), if (dark) Color.parseColor("#33FFFFFF") else Color.parseColor("#1F000000"))
            }
            elevation = c.dp(8f).toFloat()
            addView(text(c.getString(R.string.gate_title), 17f, fg, bold = true))
            addView(text(c.getString(R.string.gate_body), 14f, sub).apply { setPadding(0, c.dp(6f), 0, c.dp(14f)) })
            addView(LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                val h = c.dp(44f)
                addView(button(c.getString(R.string.gate_deny), Color.TRANSPARENT, accent) { decide(false) },
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, h))
                addView(button(c.getString(R.string.gate_allow), accent, onAccent) { decide(true) },
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, h).apply { marginStart = c.dp(8f) })
            })
        }
        val wrap = LinearLayout(c).apply {
            setPadding(c.dp(12f), 0, c.dp(12f), c.dp(12f))
            addView(card, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        val lp = overlayParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            // Stay above the navigation bar.
            flags = flags and WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS.inv()
        }
        runCatching { wm.addView(wrap, lp) }.onSuccess {
            view = wrap
            main.postDelayed(autoDismiss, PROMPT_MS)
        }
    }

    companion object {
        const val WAIT_MS = 12_000L
        const val PROMPT_MS = 60_000L
        const val APPROVAL_IDLE_MS = 3 * 60_000L
        const val DENY_COOLDOWN_MS = 2 * 60_000L
        const val DECLINED = "user_declined: the user is using the phone and said not now. Do not retry on the main " +
            "screen for a couple of minutes; use bg_open (background window) instead, or wait."
        const val CLOSED = "prompt_closed: the prompt closed without an answer (screen off, Stop, or the app " +
            "restarted). Check get_lock_state; retry if the phone is still in use, or use bg_open."
        const val WAITING = "awaiting_user: a prompt on the phone asks the user to allow main-screen control. " +
            "Retry in a few seconds, or use bg_open (background window) to work without interrupting."
    }
}
