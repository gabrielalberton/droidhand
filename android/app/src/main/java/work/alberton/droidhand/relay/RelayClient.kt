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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import work.alberton.droidhand.BuildConfig

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
    @Volatile private var unauthorized = false

    fun start() {
        running = true
        current = this
        connect()
    }

    /** Unsolicited event to the relay (e.g. the user tapped Stop). */
    fun sendEvent(event: String, payload: JSONObject) {
        ws?.send(JSONObject().put("type", "event").put("event", event).put("payload", payload).toString())
    }

    /** Tell the relay about a device-side change (e.g. the suggestion toggle) without reconnecting. */
    fun sendDeviceInfo() {
        ws?.send(JSONObject().put("type", "event").put("event", "device_info").put("payload", deviceJson()).toString())
    }

    private fun deviceJson(): JSONObject = JSONObject()
        .put("model", Build.MODEL)
        .put("androidSdk", Build.VERSION.SDK_INT)
        .put("appVersion", BuildConfig.VERSION_NAME)
        .put("suggestionLogging", prefs.suggestionLogging)

    fun stop() {
        running = false
        if (current === this) { current = null; _state.value = State.OFF }
        ws?.close(1000, "stop")
        ws = null
    }

    private fun connect() {
        val url = prefs.relayUrl
        if (url.isBlank()) { _state.value = State.NOT_CONFIGURED; return }
        _state.value = State.CONNECTING
        val req = runCatching { Request.Builder().url(url).build() }.getOrNull()
            ?: run { _state.value = State.NOT_CONFIGURED; return }
        ws = client.newWebSocket(req, listener)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            val device = deviceJson()
            val hello = JSONObject()
                .put("type", "hello")
                .put("token", prefs.token)
                .put("device", device)
            webSocket.send(hello.toString())
            // Older relays send no welcome: assume accepted if still open after 2 s.
            Handler(Looper.getMainLooper()).postDelayed({
                if (ws === webSocket && running && _state.value == State.CONNECTING) _state.value = State.CONNECTED
            }, 2000)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val frame = try {
                JSONObject(text)
            } catch (e: Exception) {
                return
            }
            if (frame.optString("type") == "event" && frame.optString("event") == "welcome") {
                _state.value = State.CONNECTED; return
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
            if (code == 4003 && running) { _state.value = State.UNAUTHORIZED; unauthorized = true }
            webSocket.close(1000, null)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = scheduleReconnect()
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = scheduleReconnect()
    }

    private fun scheduleReconnect() {
        if (!running) return
        if (unauthorized) { _state.value = State.UNAUTHORIZED; unauthorized = false; return } // wait for new settings
        if (_state.value == State.CONNECTED) _state.value = State.CONNECTING
        Handler(Looper.getMainLooper()).postDelayed({ if (running) connect() }, 3000)
    }

    /** Connection state, observed by the UI. */
    enum class State { OFF, NOT_CONFIGURED, CONNECTING, CONNECTED, UNAUTHORIZED }

    companion object {
        private val _state = MutableStateFlow(State.OFF)
        val state: StateFlow<State> = _state

        @Volatile
        var current: RelayClient? = null
            private set
    }
}
