package work.alberton.droidhand.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The hands: taps and swipes on the display the agent works on, blocking until done. */
internal class Gestures(private val svc: DroidHandAccessibilityService) {
    @Volatile
    private var inFlight: CountDownLatch? = null

    fun tap(x: Int, y: Int, durationMs: Long = 60): Boolean =
        stroke(Path().apply { moveTo(x.toFloat(), y.toFloat()) }, durationMs)

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean =
        stroke(Path().apply { moveTo(x1.toFloat(), y1.toFloat()); lineTo(x2.toFloat(), y2.toFloat()) }, durationMs)

    /** Unblocks a gesture that is still running; it reports as not completed. */
    fun cancelInFlight() { inFlight?.countDown() }

    private fun stroke(path: Path, durationMs: Long): Boolean {
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .setDisplayId(svc.displayId)
            .build()
        val latch = CountDownLatch(1).also { inFlight = it }
        var done = false
        val ok = svc.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { done = true; latch.countDown() }
            override fun onCancelled(g: GestureDescription?) { latch.countDown() }
        }, null)
        if (!ok) return false
        latch.await(durationMs + 2000, TimeUnit.MILLISECONDS)
        return done
    }
}
