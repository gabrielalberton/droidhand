package work.alberton.droidhand.adb

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import work.alberton.droidhand.R
import work.alberton.droidhand.a11y.DroidHandAccessibilityService
import work.alberton.droidhand.bg.dp
import work.alberton.droidhand.bg.overlayParams

/**
 * A card at the top of the screen, above Settings, from the moment the pairing code
 * leaves until the computer answers: pairing takes a while, and leaving the pairing
 * screen too early turns wireless debugging off and makes it fail. Then it shows the
 * result for a few seconds. Without the accessibility service it falls back to toasts.
 */
object PairBanner {
    private const val DONE_MS = 6_000L
    private const val FAILED_MS = 12_000L

    private val main = Handler(Looper.getMainLooper())
    private var view: View? = null
    private var wm: WindowManager? = null
    private var title: TextView? = null
    private var body: TextView? = null
    private var spinner: ProgressBar? = null
    private var startedAt = 0L
    private val hideLater = Runnable { hide() }
    private val tick = object : Runnable {
        override fun run() {
            val v = view ?: return
            val b = body ?: return
            if (spinner?.visibility != View.VISIBLE) return
            val s = (SystemClock.elapsedRealtime() - startedAt) / 1000
            b.text = v.context.getString(R.string.pair_banner_wait, s)
            main.postDelayed(this, 1000)
        }
    }

    fun sending(c: Context): Boolean = main.post {
        startedAt = SystemClock.elapsedRealtime()
        if (!ensure()) { Toast.makeText(c, R.string.pair_toast_sending, Toast.LENGTH_LONG).show(); return@post }
        main.removeCallbacks(hideLater)
        spinner?.visibility = View.VISIBLE
        title?.setText(R.string.pair_banner_title)
        main.removeCallbacks(tick); tick.run()
    }

    fun done(c: Context, granted: Boolean): Boolean = result(c, R.string.pair_toast_done,
        c.getString(R.string.pair_banner_done),
        c.getString(if (granted) R.string.pair_st_done else R.string.pair_st_done_partial), DONE_MS)

    fun failed(c: Context, why: String): Boolean = result(c, R.string.pair_toast_failed,
        c.getString(R.string.pair_banner_failed), why, FAILED_MS)

    private fun result(c: Context, toast: Int, head: String, text: String, ms: Long): Boolean = main.post {
        main.removeCallbacks(tick)
        if (!ensure()) { Toast.makeText(c, toast, Toast.LENGTH_LONG).show(); return@post }
        spinner?.visibility = View.GONE
        title?.text = head
        body?.text = text
        main.removeCallbacks(hideLater)
        main.postDelayed(hideLater, ms)
    }

    fun hide(): Boolean = main.post {
        main.removeCallbacks(tick); main.removeCallbacks(hideLater)
        view?.let { v -> runCatching { wm?.removeView(v) } }
        view = null; title = null; body = null; spinner = null
    }

    /** Adds the card if it is not on screen; false when there is no accessibility service. */
    private fun ensure(): Boolean {
        if (view != null) return true
        val svc = DroidHandAccessibilityService.instance ?: return false
        val w = svc.getSystemService(WindowManager::class.java) ?: return false
        val dark = (svc.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val bg = if (dark) Color.parseColor("#1E2127") else Color.WHITE
        val fg = if (dark) Color.parseColor("#E7E8EB") else Color.parseColor("#14161A")
        val sub = if (dark) Color.parseColor("#A3A7B0") else Color.parseColor("#5D636E")
        fun text(size: Float, color: Int, bold: Boolean) = TextView(svc).apply {
            setTextColor(color); setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val t = text(16f, fg, true)
        val b = text(14f, sub, false).apply { setPadding(0, svc.dp(4f), 0, 0) }
        val p = ProgressBar(svc).apply { isIndeterminate = true }
        val card = LinearLayout(svc).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(svc.dp(18f), svc.dp(14f), svc.dp(18f), svc.dp(14f))
            background = GradientDrawable().apply {
                cornerRadius = svc.dp(20f).toFloat(); setColor(bg)
                setStroke(svc.dp(1f), if (dark) Color.parseColor("#33FFFFFF") else Color.parseColor("#1F000000"))
            }
            elevation = svc.dp(8f).toFloat()
            addView(p, LinearLayout.LayoutParams(svc.dp(28f), svc.dp(28f)).apply { marginEnd = svc.dp(14f) })
            addView(LinearLayout(svc).apply {
                orientation = LinearLayout.VERTICAL
                addView(t); addView(b)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            setOnClickListener { if (p.visibility != View.VISIBLE) hide() } // results can be dismissed; progress cannot
        }
        val wrap = LinearLayout(svc).apply {
            setPadding(svc.dp(12f), svc.dp(36f), svc.dp(12f), 0)
            addView(card, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        val lp = overlayParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT, Gravity.TOP)
        if (runCatching { w.addView(wrap, lp) }.isFailure) return false
        view = wrap; wm = w; title = t; body = b; spinner = p
        return true
    }
}
