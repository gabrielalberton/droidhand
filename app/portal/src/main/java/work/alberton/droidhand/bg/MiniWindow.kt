package work.alberton.droidhand.bg

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * Our own floating window for the trusted ("own") background display: live video
 * of the display, title bar to move it (drag down onto the X to close), corner grip
 * to resize, "Esconder" to hide it while the agent keeps working, a back button,
 * and touch passthrough so the user can interact (e.g. solve a captcha).
 * Main thread only.
 */
class MiniWindow(private val ctx: Context, private val session: BackgroundSession) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private var root: LinearLayout? = null
    private var lp: WindowManager.LayoutParams? = null
    private var texture: TextureView? = null
    private var title: TextView? = null
    private var banner: TextView? = null
    private var surface: Surface? = null

    /** Remembered between hide/show. */
    private var winX = -1
    private var winY = -1
    private var winW = -1

    val visible: Boolean get() = root != null

    private val titleH get() = ctx.dp(34f)
    private fun contentH(w: Int) = w * session.height / session.width

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (root != null) return
        val screenW = ctx.resources.displayMetrics.widthPixels
        if (winW < 0) winW = (screenW * 0.45f).toInt()
        if (winX < 0) { winX = screenW - winW - ctx.dp(12f); winY = ctx.dp(90f) }
        // Keep the title bar reachable.
        val screenH = ctx.resources.displayMetrics.heightPixels
        winX = winX.coerceIn(-winW / 2, screenW - winW / 2)
        winY = winY.coerceIn(0, screenH - titleH * 3)

        val bar = DragBar(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#F0202124"))
        }
        val back = TextView(ctx).apply {
            text = "‹"; setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            gravity = Gravity.CENTER
            contentDescription = "Back (background window)"
            setOnClickListener { session.key(KeyEvent.KEYCODE_BACK) }
        }
        bar.addView(back, LinearLayout.LayoutParams(ctx.dp(34f), titleH))
        val label = TextView(ctx).apply {
            text = "DroidHand"; setTextColor(Color.parseColor("#DDFFFFFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f); maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
        }
        title = label
        bar.addView(label, LinearLayout.LayoutParams(0, titleH, 1f))
        val hide = TextView(ctx).apply {
            text = "Esconder"; setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            val p = ctx.dp(8f); setPadding(p, ctx.dp(3f), p, ctx.dp(3f))
            background = GradientDrawable().apply {
                cornerRadius = ctx.dp(12f).toFloat(); setColor(Color.parseColor("#3D5AFE"))
            }
            contentDescription = "Hide background window (the agent keeps working)"
            setOnClickListener { session.hide() }
        }
        bar.addView(hide, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { marginEnd = ctx.dp(6f) })

        val tv = TextureView(ctx)
        tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                st.setDefaultBufferSize(session.width, session.height)
                surface = Surface(st).also { session.attachSurface(it) }
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                st.setDefaultBufferSize(session.width, session.height)
            }
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                session.attachSurface(null)
                surface?.release(); surface = null
                return true
            }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        // Touch passthrough: map view coordinates to display coordinates and inject.
        tv.setOnTouchListener { v, e ->
            val m = Matrix().apply { setScale(session.width / v.width.toFloat(), session.height / v.height.toFloat()) }
            val copy = MotionEvent.obtain(e).also { it.transform(m) }
            session.injectTouch(copy)
            copy.recycle()
            true
        }
        texture = tv

        val content = FrameLayout(ctx).apply { setBackgroundColor(Color.BLACK) }
        content.addView(tv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val grip = TextView(ctx).apply {
            text = "◢"; setTextColor(Color.parseColor("#CCFFFFFF")); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.BOTTOM or Gravity.END
            contentDescription = "Resize background window"
        }
        content.addView(grip, FrameLayout.LayoutParams(ctx.dp(36f), ctx.dp(36f), Gravity.BOTTOM or Gravity.END))

        val r = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            clipToOutline = true
            background = GradientDrawable().apply { cornerRadius = ctx.dp(10f).toFloat(); setColor(Color.BLACK) }
            alpha = session.opacity
        }
        r.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, titleH))
        val help = TextView(ctx).apply {
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            val p = ctx.dp(8f); setPadding(p, ctx.dp(6f), p, ctx.dp(6f))
            setBackgroundColor(Color.parseColor("#E6EF6C00"))
            visibility = View.GONE
            setOnClickListener { setBanner(null); session.helpDone() }
        }
        banner = help
        r.addView(help, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        r.addView(content, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val params = overlayParams(winW, titleH + contentH(winW)).apply { x = winX; y = winY }
        lp = params
        dragToMoveOrClose(bar, params)
        dragToResize(grip, params)
        runCatching { wm.addView(r, params) }.onSuccess { root = r }
    }

    fun hide() {
        root?.let { runCatching { wm.removeView(it) } } // TextureView destroyed -> surface detached
        root = null; texture = null; title = null; banner = null
    }

    fun setOpacity(a: Float) { root?.alpha = a }

    /** Shows (or clears, with null) a request for the user under the title bar. */
    fun setBanner(msg: String?) {
        val b = banner ?: return
        if (msg.isNullOrBlank()) {
            b.visibility = View.GONE
        } else {
            b.text = "⚠ $msg\n(toque aqui quando terminar)"
            b.visibility = View.VISIBLE
        }
        // The window grows to fit the banner.
        val p = lp ?: return
        root?.post {
            p.height = titleH + contentH(p.width) + (if (b.visibility == View.VISIBLE) b.height.coerceAtLeast(ctx.dp(44f)) else 0)
            root?.let { runCatching { wm.updateViewLayout(it, p) } }
        }
    }

    private fun dragToMoveOrClose(bar: DragBar, p: WindowManager.LayoutParams) {
        var dx0 = 0f; var dy0 = 0f; var sx = 0; var sy = 0
        bar.onDrag = { e, phase ->
            when (phase) {
                DragBar.Phase.START -> { dx0 = e.rawX; dy0 = e.rawY; sx = p.x; sy = p.y; session.bubbles.showTarget() }
                DragBar.Phase.MOVE -> {
                    p.x = sx + (e.rawX - dx0).toInt(); p.y = sy + (e.rawY - dy0).toInt()
                    root?.let { runCatching { wm.updateViewLayout(it, p) } }
                    session.bubbles.hover(e.rawX, e.rawY)
                }
                DragBar.Phase.END -> {
                    val close = session.bubbles.hover(e.rawX, e.rawY)
                    session.bubbles.hideTarget()
                    winX = p.x; winY = p.y
                    if (close) { winX = -1; winY = -1; session.close("user") } // next window opens at the default spot
                }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun dragToResize(grip: View, p: WindowManager.LayoutParams) {
        var x0 = 0f; var w0 = 0
        val screenW = ctx.resources.displayMetrics.widthPixels
        grip.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { x0 = e.rawX; w0 = p.width }
                MotionEvent.ACTION_MOVE -> {
                    val minW = maxOf((screenW * 0.25f).toInt(), ctx.dp(150f))
                    val w = (w0 + (e.rawX - x0)).toInt().coerceIn(minW, screenW)
                    val extra = banner?.takeIf { it.visibility == View.VISIBLE }?.height ?: 0
                    p.width = w; p.height = titleH + contentH(w) + extra; winW = w
                    root?.let { runCatching { wm.updateViewLayout(it, p) } }
                }
            }
            true
        }
    }
}

/** Title bar: a drag that starts anywhere on it (even on a button) moves the window; taps still reach the buttons. */
internal class DragBar(ctx: Context) : LinearLayout(ctx) {
    enum class Phase { START, MOVE, END }
    var onDrag: ((MotionEvent, Phase) -> Unit)? = null
    private val slop = android.view.ViewConfiguration.get(ctx).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var dragging = false

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; dragging = false }
            MotionEvent.ACTION_MOVE -> if (!dragging && (abs(e.rawX - downX) > slop || abs(e.rawY - downY) > slop)) {
                dragging = true
                onDrag?.invoke(downEvent(downX, downY, e), Phase.START)
            }
        }
        return dragging
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; dragging = false }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && (abs(e.rawX - downX) > slop || abs(e.rawY - downY) > slop)) {
                    dragging = true
                    onDrag?.invoke(downEvent(downX, downY, e), Phase.START)
                }
                if (dragging) onDrag?.invoke(e, Phase.MOVE)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) onDrag?.invoke(e, Phase.END)
                dragging = false
            }
        }
        return true
    }

    /** Synthetic event carrying the drag start point (an obtained event's raw coords equal its x/y). */
    private fun downEvent(x: Float, y: Float, base: MotionEvent): MotionEvent =
        MotionEvent.obtain(base.downTime, base.eventTime, MotionEvent.ACTION_DOWN, x, y, 0)
}
