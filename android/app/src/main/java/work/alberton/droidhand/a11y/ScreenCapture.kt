package work.alberton.droidhand.a11y

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** JPEG screenshots of the display the agent works on (accessibility takeScreenshot). */
internal class ScreenCapture(private val svc: DroidHandAccessibilityService) {
    private val executor = Executors.newSingleThreadExecutor()

    /** JPEG screenshot (base64), scaled to at most [maxWidth] px wide. Blocks the caller (not the main thread). */
    fun capture(maxWidth: Int = 720, quality: Int = 70): JSONObject {
        val latch = CountDownLatch(1)
        var result: JSONObject = JSONObject().put("error", "screenshot timed out")
        svc.takeScreenshot(svc.displayId, executor, object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(shot: AccessibilityService.ScreenshotResult) {
                result = try {
                    encode(shot, maxWidth, quality)
                } catch (e: Exception) {
                    JSONObject().put("error", "screenshot failed: ${e.message}")
                }
                latch.countDown()
            }

            override fun onFailure(errorCode: Int) {
                result = JSONObject().put("error", "screenshot failed (code $errorCode; secure screen or rate limit)")
                latch.countDown()
            }
        })
        latch.await(5, TimeUnit.SECONDS)
        return result
    }

    private fun encode(shot: AccessibilityService.ScreenshotResult, maxWidth: Int, quality: Int): JSONObject {
        val hw = Bitmap.wrapHardwareBuffer(shot.hardwareBuffer, shot.colorSpace)
        val bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
        shot.hardwareBuffer.close()
        if (bmp == null) return JSONObject().put("error", "could not decode screenshot")
        val scaled = if (bmp.width > maxWidth) Bitmap.createScaledBitmap(bmp, maxWidth, bmp.height * maxWidth / bmp.width, true) else bmp
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return JSONObject()
            .put("mimeType", "image/jpeg")
            .put("width", scaled.width)
            .put("height", scaled.height)
            .put("data", Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
    }

    fun shutdown() = executor.shutdown()
}
