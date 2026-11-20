package work.alberton.droidhand.relay

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import work.alberton.droidhand.data.Prefs
import work.alberton.droidhand.tools.ToolRouter
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Outbound-only WebSocket client to the relay. Sends a hello (with device info +
 * the suggestion-logging flag), then answers cmd frames by running the tool and
 * returning a result. Reconnects with a fixed backoff.
 */
class RelayClient(context: Context) {
    private val app = context.applicationContext
    private val prefs = Prefs(app)
    private val router = ToolRouter(app)
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    /** Tools run one at a time, off the socket reader thread (gestures block until done). */
    private val worker = Executors.newSingleThreadExecutor()
    private var ws: WebSocket? = null
    @Volatile private var running = false

    fun start() {
        running = true
        current = this
        connect()
    }

    /** Tell the relay about a device-side change (e.g. the suggestion toggle) without reconnecting. */
    fun sendDeviceInfo() {
        ws?.send(JSONObject().put("type", "event").put("event", "device_info").put("payload", deviceJson()).toString())
    }

    private fun deviceJson(): JSONObject = JSONObject()
        .put("model", Build.MODEL)
        .put("androidSdk", Build.VERSION.SDK_INT)
        .put("appVersion", "0.1.0")
        .put("suggestionLogging", prefs.suggestionLogging)

    fun stop() {
        running = false
        if (current === this) current = null
        ws?.close(1000, "stop")
        ws = null
    }

    private fun connect() {
        val url = prefs.relayUrl
        if (url.isBlank()) return
        ws = client.newWebSocket(Request.Builder().url(url).build(), listener)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            val device = deviceJson()
            val hello = JSONObject()
                .put("type", "hello")
                .put("token", prefs.token)
                .put("device", device)
            webSocket.send(hello.toString())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val frame = try {
                JSONObject(text)
            } catch (e: Exception) {
                return
            }
            if (frame.optString("type") != "cmd") return
            val id = frame.optString("id")
            val tool = frame.optString("tool")
            val args = frame.optJSONObject("args") ?: JSONObject()
            worker.execute {
                val res = router.run(tool, args)
                val out = JSONObject().put("type", "result").put("id", id).put("ok", res.ok)
                res.data?.let { out.put("data", it) }
                res.error?.let { out.put("error", it) }
                webSocket.send(out.toString())
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = scheduleReconnect()
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = scheduleReconnect()
    }

    private fun scheduleReconnect() {
        if (!running) return
        Handler(Looper.getMainLooper()).postDelayed({ if (running) connect() }, 3000)
    }

    companion object {
        @Volatile
        var current: RelayClient? = null
            private set
    }
}
