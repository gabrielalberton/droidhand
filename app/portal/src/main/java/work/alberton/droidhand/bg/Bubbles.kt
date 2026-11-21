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
import android.widget.Toast
import work.alberton.droidhand.R
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The small floating controls around a background session:
 * - eye bubble: the mini window is hidden but the agent keeps working; tap = show again;
 * - close target: the "X" bubble that appears at the bottom while dragging; drop = close;
 * - native handle: for the native (system-drawn) window, a pill to drag onto the X.
 * Main thread only.
 */
class Bubbles(private val ctx: Context, private val session: BackgroundSession) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private var eye: View? = null
    private var target: TextView? = null
    private var handle: View? = null

    // ---- Eye bubble (sits under the stop button) ----
    fun showEye() {
        if (eye != null) return
        val size = ctx.dp(32f)
        val v = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_eye)
            setColorFilter(Color.WHITE)
            val pad = ctx.dp(7f); setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#1E88E5")); setStroke(ctx.dp(1f), Color.WHITE)
            }
            alpha = 0.7f
            contentDescription = ctx.getString(work.alberton.droidhand.R.string.cd_show_bg)
            setOnClickListener { session.show() }
        }
        val lp = overlayParams(size, size, Gravity.TOP or Gravity.END).apply {
            x = ctx.dp(6f); y = ctx.resources.displayMetrics.heightPixels / 3 + ctx.dp(40f)
        }
        runCatching { wm.addView(v, lp) }.onSuccess { eye = v }
    }

    fun hideEye() { eye?.let { runCatching { wm.removeView(it) } }; eye = null }

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

    // ---- Native-window handle: drag it onto the X to remove the native window ----
    @SuppressLint("ClickableViewAccessibility")
    fun showNativeHandle() {
        if (handle != null) return
        val v = TextView(ctx).apply {
            text = ctx.getString(work.alberton.droidhand.R.string.float_handle)
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            val p = ctx.dp(8f); setPadding(p, p / 2, p, p / 2)
            background = GradientDrawable().apply {
                cornerRadius = ctx.dp(14f).toFloat(); setColor(Color.parseColor("#CC202124"))
            }
            contentDescription = ctx.getString(work.alberton.droidhand.R.string.cd_handle_bg)
        }
        val lp = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT).apply {
            x = ctx.dp(12f); y = ctx.dp(80f)
        }
        var downX = 0f; var downY = 0f; var sx = 0; var sy = 0; var moved = false
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; sx = lp.x; sy = lp.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!moved && (abs(dx) > ctx.dp(6f) || abs(dy) > ctx.dp(6f))) { moved = true; showTarget() }
                    if (moved) {
                        lp.x = sx + dx.toInt(); lp.y = sy + dy.toInt()
                        runCatching { wm.updateViewLayout(view, lp) }
                        hover(e.rawX, e.rawY)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val close = moved && hover(e.rawX, e.rawY)
                    hideTarget()
                    if (close) session.close("user")
                    else if (!moved) Toast.makeText(ctx, work.alberton.droidhand.R.string.float_handle_hint, Toast.LENGTH_SHORT).show()
                }
            }
            true
        }
        runCatching { wm.addView(v, lp) }.onSuccess { handle = v }
    }

    fun hideNativeHandle() { handle?.let { runCatching { wm.removeView(it) } }; handle = null }

    fun hideAll() { hideEye(); hideTarget(); hideNativeHandle() }
}
