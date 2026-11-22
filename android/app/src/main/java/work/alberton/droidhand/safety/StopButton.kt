package work.alberton.droidhand.safety

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.abs

/**
 * Small, translucent floating "stop" button shown while the agent is acting on
 * the screen. Tapping it cancels the agent's current action and tells the agent
 * (the connection stays up; nothing has to be re-enabled). It is an accessibility
 * overlay (TYPE_ACCESSIBILITY_OVERLAY), so it needs no "draw over other apps"
 * permission. Draggable; hides itself after [IDLE_MS] without agent actions.
 */
class StopButton(private val service: AccessibilityService, private val onStop: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val wm = service.getSystemService(WindowManager::class.java)
    private var view: View? = null
    private var params: WindowManager.LayoutParams? = null
    private val hideRunnable = Runnable { hide() }

    /** The agent just did something: show the button and restart the idle timer. */
    fun bump() {
        main.post {
            if (view == null) show()
            main.removeCallbacks(hideRunnable)
            if (!pinned) main.postDelayed(hideRunnable, IDLE_MS)
        }
    }

    @Volatile
    private var pinned = false

    /** Pinned = always visible (a background session is running), no idle auto-hide. */
    fun setPinned(on: Boolean) {
        pinned = on
        main.post {
            if (on) { main.removeCallbacks(hideRunnable); if (view == null) show() }
            else main.postDelayed(hideRunnable, IDLE_MS)
        }
    }

    /** Really on screen (added to the window manager and not removed by the system). */
    val isOnScreen: Boolean get() = view?.parent != null

    /** Main thread only: put the button on screen if it is missing; true if it is visible. */
    fun ensureOnScreen(): Boolean {
        if (!isOnScreen) { view?.let { runCatching { wm.removeView(it) } }; view = null; show() }
        return isOnScreen
    }

    fun hide() {
        main.post {
            main.removeCallbacks(hideRunnable)
            view?.let { runCatching { wm.removeView(it) } }
            view = null
        }
    }

    private fun dp(v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, service.resources.displayMetrics).toInt()

    @SuppressLint("ClickableViewAccessibility")
    private fun show() {
        val size = dp(32f)
        // Red circle with a white square drawn as layers: exactly centered (a "■" glyph sits off-center).
        val square = dp(12f)
        val inset = (size - square) / 2
        val button = android.view.View(service).apply {
            background = android.graphics.drawable.LayerDrawable(arrayOf(
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#D32F2F"))
                    setStroke(dp(1f), Color.WHITE)
                },
                GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(2f).toFloat() },
            )).apply { setLayerInset(1, inset, inset, inset, inset) }
            alpha = 0.45f
            contentDescription = service.getString(work.alberton.droidhand.R.string.cd_stop)
        }
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(6f)
            y = service.resources.displayMetrics.heightPixels / 3
        }

        // Drag to move; a short touch without movement is a tap (= stop).
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        button.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; moved = false
                    v.alpha = 0.9f
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (abs(dx) > dp(6f) || abs(dy) > dp(6f)) moved = true
                    if (moved) {
                        lp.x = startX - dx.toInt() // gravity END: x grows to the left
                        lp.y = startY + dy.toInt()
                        runCatching { wm.updateViewLayout(v, lp) }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    v.alpha = 0.45f
                    if (!moved) {
                        if (!pinned) hide()
                        onStop()
                    }
                }
                MotionEvent.ACTION_CANCEL -> v.alpha = 0.45f
            }
            true
        }
        runCatching { wm.addView(button, lp) }.onSuccess {
            view = button
            params = lp
        }
    }

    companion object {
        const val IDLE_MS = 10_000L
    }
}
