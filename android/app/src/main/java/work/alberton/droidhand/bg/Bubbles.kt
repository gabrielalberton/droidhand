package work.alberton.droidhand.bg

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import work.alberton.droidhand.R
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The small floating controls around a background session. One round bubble sits
 * under the Stop button and changes with the window:
 * - MINIMIZE: own window shown; tap = hide it (the agent keeps working);
 * - EYE: own window hidden; tap = show it again;
 * - NATIVE: native (system-drawn) window, which Android does not let us hide
 *   (hiding it = removing the display), so the bubble is a close button: tap = close.
 * For EYE/MINIMIZE, dragging the bubble onto the "X" target (shown at the bottom
 * while dragging) closes the background session. Main thread only.
 */
class Bubbles(private val ctx: Context, private val session: BackgroundSession) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private enum class Kind { EYE, MINIMIZE, NATIVE }
    private var bubble: View? = null
    private var kind: Kind? = null
    private var target: TextView? = null
    /** Where the user last dropped the bubble (kept across kind changes). */
    private var posX = -1
    private var posY = -1

    fun showEye() = showBubble(Kind.EYE)
    fun showMinimize() = showBubble(Kind.MINIMIZE)
    fun showNativeHandle() = showBubble(Kind.NATIVE)

    fun hideEye() = hideBubble(Kind.EYE)
    fun hideMinimize() = hideBubble(Kind.MINIMIZE)
    fun hideNativeHandle() = hideBubble(Kind.NATIVE)

    /** (Re)adds the bubble of that kind if it is not on screen; true if it is visible. */
    fun ensureEye(): Boolean = ensure(Kind.EYE)
    fun ensureMinimize(): Boolean = ensure(Kind.MINIMIZE)
    fun ensureNativeHandle(): Boolean = ensure(Kind.NATIVE)

    private fun ensure(k: Kind): Boolean {
        if (kind != k || bubble?.parent == null) { removeBubble(); showBubble(k) }
        return bubble?.parent != null
    }

    private fun hideBubble(k: Kind) { if (kind == k) removeBubble() }

    private fun removeBubble() { bubble?.let { runCatching { wm.removeView(it) } }; bubble = null; kind = null }

    @SuppressLint("ClickableViewAccessibility")
    private fun showBubble(k: Kind) {
        if (kind == k && bubble != null) return
        removeBubble()
        val size = ctx.dp(32f)
        val (icon, color, desc) = when (k) {
            Kind.EYE -> Triple(R.drawable.ic_eye, "#1E88E5", R.string.cd_show_bg)
            Kind.MINIMIZE -> Triple(R.drawable.ic_minimize, "#5E6472", R.string.cd_hide_bg)
            Kind.NATIVE -> Triple(R.drawable.ic_close_window, "#5E6472", R.string.cd_handle_bg)
        }
        val v = ImageView(ctx).apply {
            setImageResource(icon)
            setColorFilter(Color.WHITE)
            val pad = ctx.dp(7f); setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor(color)); setStroke(ctx.dp(1f), Color.WHITE)
            }
            alpha = 0.75f
            contentDescription = ctx.getString(desc)
        }
        val lp = overlayParams(size, size, Gravity.TOP or Gravity.END).apply {
            x = if (posX >= 0) posX else ctx.dp(6f)
            y = if (posY >= 0) posY else ctx.resources.displayMetrics.heightPixels / 3 + ctx.dp(40f)
        }
        var downX = 0f; var downY = 0f; var sx = 0; var sy = 0; var moved = false
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; sx = lp.x; sy = lp.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!moved && (abs(dx) > ctx.dp(6f) || abs(dy) > ctx.dp(6f))) { moved = true; if (k != Kind.NATIVE) showTarget() }
                    if (moved) {
                        // Gravity END: x grows to the left.
                        lp.x = sx - dx.toInt(); lp.y = sy + dy.toInt()
                        runCatching { wm.updateViewLayout(view, lp) }
                        hover(e.rawX, e.rawY)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val close = moved && hover(e.rawX, e.rawY)
                    hideTarget()
                    if (moved && !close) { posX = lp.x; posY = lp.y }
                    when {
                        close -> session.close("user")
                        moved -> Unit
                        k == Kind.EYE -> session.show()
                        k == Kind.MINIMIZE -> session.hide()
                        else -> session.close("user")
                    }
                }
            }
            true
        }
        runCatching { wm.addView(v, lp) }.onSuccess { bubble = v; kind = k }
    }

    // ---- Close target ("X" bubble at the bottom while dragging) ----
    fun showTarget() {
        if (target != null) return
        val size = ctx.dp(56f)
        val v = TextView(ctx).apply {
            text = "✕"; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            background = targetBg(false)
        }
        val lp = overlayParams(size, size, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { y = ctx.dp(72f) }
        runCatching { wm.addView(v, lp) }.onSuccess { target = v }
    }

    /** Highlights the target when the finger is over it; returns whether it is. */
    fun hover(rawX: Float, rawY: Float): Boolean {
        val t = target ?: return false
        val loc = IntArray(2).also { t.getLocationOnScreen(it) }
        val cx = loc[0] + t.width / 2f
        val cy = loc[1] + t.height / 2f
        val over = t.width > 0 && hypot(rawX - cx, rawY - cy) < t.width * 1.1f
        t.background = targetBg(over)
        return over
    }

    fun hideTarget() { target?.let { runCatching { wm.removeView(it) } }; target = null }

    private fun targetBg(active: Boolean) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(if (active) Color.parseColor("#E53935") else Color.parseColor("#B3202124"))
        setStroke(ctx.dp(2f), Color.WHITE)
    }

    fun hideAll() { removeBubble(); hideTarget() }
}
