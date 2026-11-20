package work.alberton.droidhand.bg

import android.content.Context
import android.graphics.PixelFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager

/** Small helpers shared by the floating overlays (all TYPE_ACCESSIBILITY_OVERLAY). */
internal fun Context.dp(v: Float): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

internal fun overlayParams(w: Int, h: Int, gravity: Int = Gravity.TOP or Gravity.START) = WindowManager.LayoutParams(
    w, h,
    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
    PixelFormat.TRANSLUCENT,
).apply { this.gravity = gravity }
