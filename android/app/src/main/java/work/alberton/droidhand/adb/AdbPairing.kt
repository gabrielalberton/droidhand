package work.alberton.droidhand.adb

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import work.alberton.droidhand.R
import work.alberton.droidhand.relay.RelayClient

/**
 * Pair the computer's adb with this phone without touching the computer (Permissions →
 * Secure settings → Pair from the app). While armed, the accessibility service watches
 * Android's "Pair device with pairing code" dialog; when it shows the 6-digit code and
 * the IP:port, the app sends them to the relay (`adb_pair`). The relay runs `adb pair`,
 * connects over wireless debugging, grants the secure-settings permission and arms the
 * TCP port for ADB on demand; then the app turns debugging off again.
 *
 * Only Settings windows are read, only while armed (10 min), and the code goes only to
 * the relay this app is connected to.
 */
object AdbPairing {
    sealed interface State {
        data object Idle : State
        data object Waiting : State
        data class Sending(val code: String, val port: Int) : State
        data class Done(val granted: Boolean, val tcpip: Boolean, val note: String?) : State
        data class Failed(val message: String) : State
    }

    private const val ARM_MS = 10 * 60_000L
    private val SETTINGS_PKGS = setOf("com.android.settings", "com.samsung.android.settings")
    private val CODE = Regex("""^\d{6}$""")
    private val ADDR = Regex("""(\d{1,3}(?:\.\d{1,3}){3}):(\d{2,5})""")

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state
    @Volatile private var armedUntil = 0L
    @Volatile private var lastSent: String? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val armed get() = System.currentTimeMillis() < armedUntil && _state.value !is State.Sending

    fun arm() { armedUntil = System.currentTimeMillis() + ARM_MS; lastSent = null; _state.value = State.Waiting }
    fun disarm() { armedUntil = 0; if (_state.value is State.Waiting) _state.value = State.Idle }

    /** Called by the accessibility service on window events while [armed]. */
    fun onWindows(svc: AccessibilityService) {
        if (!armed) return
        val roots = runCatching { svc.windows.mapNotNull { it.root } }.getOrDefault(emptyList())
        for (root in roots) {
            if (root.packageName?.toString() !in SETTINGS_PKGS) continue
            val texts = ArrayList<String>()
            collect(root, texts, 0)
            val code = texts.firstOrNull { CODE.matches(it.trim()) }?.trim() ?: continue
            val addr = texts.firstNotNullOfOrNull { ADDR.find(it) } ?: continue
            val port = addr.groupValues[2].toIntOrNull() ?: continue
            val key = "$code:$port"
            if (key == lastSent) return
            lastSent = key
            send(svc.applicationContext, code, port, addr.groupValues[1])
            return
        }
    }

    private fun collect(n: AccessibilityNodeInfo, out: MutableList<String>, depth: Int) {
        if (depth > 30 || out.size > 400) return
        n.text?.toString()?.takeIf { it.isNotBlank() }?.let(out::add)
        for (i in 0 until n.childCount) n.getChild(i)?.let { collect(it, out, depth + 1) }
    }

    /** Send a pairing code (read from the dialog, or typed in the app) to the relay. */
    fun send(ctx: Context, code: String, port: Int, dialogIp: String? = null) {
        val app = ctx.applicationContext
        _state.value = State.Sending(code, port)
        toast(app, R.string.pair_toast_sending)
        scope.launch {
            val result = runCatching {
                val client = RelayClient.current ?: error(app.getString(R.string.pair_err_offline))
                val args = withContext(Dispatchers.IO) {
                    val a = AdbSession.addresses()
                    val hosts = JSONArray()
                    listOfNotNull(a.optString("tailnet").ifBlank { null }, dialogIp, a.optString("lan").ifBlank { null })
                        .distinct().forEach { hosts.put(it) }
                    JSONObject().put("code", code).put("port", port).put("hosts", hosts)
                        .put("ports", AdbSession.localPorts(30000, 60999))
                }
                val res = client.request("adb_pair", args, 90_000) ?: error(app.getString(R.string.pair_err_offline))
                if (!res.optBoolean("ok")) error(res.optString("error").ifBlank { "pair failed" })
                res.optJSONObject("data") ?: JSONObject()
            }
            result.onSuccess { d ->
                armedUntil = 0
                val granted = AdbSession.canWrite(app)
                // Done: debugging off again (the TCP port stays armed until the next reboot).
                if (granted) runCatching {
                    Settings.Global.putString(app.contentResolver, "adb_wifi_enabled", "0")
                    Settings.Global.putString(app.contentResolver, Settings.Global.ADB_ENABLED, "0")
                }
                _state.value = State.Done(granted, d.optBoolean("tcpip"), d.optString("note").ifBlank { null })
                toast(app, R.string.pair_toast_done)
            }.onFailure { e ->
                lastSent = null
                _state.value = State.Failed(e.message ?: e.toString())
                toast(app, R.string.pair_toast_failed)
            }
        }
    }

    private fun toast(c: Context, res: Int) = Handler(Looper.getMainLooper()).post {
        Toast.makeText(c, res, Toast.LENGTH_LONG).show()
    }
}
