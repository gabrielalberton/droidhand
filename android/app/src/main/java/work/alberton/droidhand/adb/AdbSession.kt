package work.alberton.droidhand.adb

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import work.alberton.droidhand.DroidHandApp
import work.alberton.droidhand.R
import work.alberton.droidhand.service.RelayService
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * ADB on demand. Debugging stays OFF; the agent turns it on for a session it ends itself
 * (USB debugging only — adbd then listens on the TCP port armed earlier with
 * `adb tcpip`, reachable over the tailnet), and it is turned off again when the
 * session ends, 10 min after the agent's last call (relay renews it; enforced here even if the relay is gone), from the
 * notification, or from any kill switch. Developer options are restored to what
 * they were before the session.
 */
object AdbSession {
    private const val SP = "droidhand_adb"
    private const val KEY_DEADLINE = "deadline"
    private const val KEY_PREV_DEV = "prev_dev"
    private const val NOTIF_ID = 1002
    const val MAX_TTL_S = 3600
    const val DEFAULT_TTL_S = 600

    private val main = Handler(Looper.getMainLooper())
    private val expire = Runnable { appCtx?.let { disable(it, "expired") } }
    @Volatile private var appCtx: Context? = null

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences(SP, Context.MODE_PRIVATE)

    fun canWrite(c: Context) =
        c.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED

    /** Wall-clock deadline of the open session, 0 = none. */
    fun deadline(c: Context): Long = sp(c).getLong(KEY_DEADLINE, 0L)

    private fun put(c: Context, key: String, v: String) = Settings.Global.putString(c.contentResolver, key, v)
    private fun get(c: Context, key: String) = Settings.Global.getString(c.contentResolver, key)

    /** Turn USB debugging on for [ttlSeconds]; [wifi] also turns wireless debugging on (needs Wi-Fi, used to arm the TCP port). */
    fun enable(c: Context, ttlSeconds: Int, wifi: Boolean): JSONObject {
        val app = c.applicationContext
        appCtx = app
        val ttl = ttlSeconds.coerceIn(30, MAX_TTL_S)
        val s = sp(app)
        if (s.getLong(KEY_DEADLINE, 0L) == 0L) {
            s.edit().putString(KEY_PREV_DEV, get(app, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED) ?: "0").apply()
        }
        val until = System.currentTimeMillis() + ttl * 1000L
        s.edit().putLong(KEY_DEADLINE, until).commit()
        put(app, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, "1")
        put(app, Settings.Global.ADB_ENABLED, "1")
        if (wifi) put(app, "adb_wifi_enabled", "1")
        main.removeCallbacks(expire)
        main.postDelayed(expire, ttl * 1000L)
        notify(app, until)
        return status(app)
    }

    /** Turn debugging (USB and wireless) off and restore developer options. Idempotent. */
    fun disable(c: Context, why: String): JSONObject {
        val app = c.applicationContext
        main.removeCallbacks(expire)
        val s = sp(app)
        val hadSession = s.getLong(KEY_DEADLINE, 0L) != 0L
        if (canWrite(app)) {
            put(app, "adb_wifi_enabled", "0")
            put(app, Settings.Global.ADB_ENABLED, "0")
            if (hadSession) s.getString(KEY_PREV_DEV, null)?.let { put(app, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, it) }
        }
        s.edit().remove(KEY_DEADLINE).remove(KEY_PREV_DEV).commit()
        app.getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID)
        return status(app).put("closed", why)
    }

    /** Wireless debugging off again (after arming the TCP port); USB debugging stays on. */
    fun wifiOff(c: Context): JSONObject {
        put(c.applicationContext, "adb_wifi_enabled", "0")
        return status(c)
    }

    /** Called on app/service start: a session whose deadline passed (process killed, reboot) is closed now. */
    fun sweep(c: Context) {
        val d = deadline(c)
        if (d == 0L) return
        appCtx = c.applicationContext
        val left = d - System.currentTimeMillis()
        if (left <= 0) disable(c, "expired") else { main.removeCallbacks(expire); main.postDelayed(expire, left) }
    }

    fun status(c: Context): JSONObject {
        val app = c.applicationContext
        return JSONObject()
            .put("usb", get(app, Settings.Global.ADB_ENABLED) == "1")
            .put("wifi", get(app, "adb_wifi_enabled") == "1")
            .put("onWifi", onWifi(app))
            .put("deadline", deadline(app).takeIf { it > 0 } ?: JSONObject.NULL)
            .put("addresses", addresses())
    }

    private fun onWifi(c: Context): Boolean {
        val cm = c.getSystemService(ConnectivityManager::class.java) ?: return false
        return runCatching {
            cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
        }.getOrDefault(false)
    }

    /** IPv4 addresses by kind: "tailnet" (100.64.0.0/10) and "lan" (Wi-Fi). */
    private fun addresses(): JSONObject {
        val out = JSONObject()
        runCatching {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (a in nif.inetAddresses) {
                    if (a !is Inet4Address) continue
                    val b = a.address
                    val ip = a.hostAddress ?: continue
                    val tailnet = (b[0].toInt() and 0xff) == 100 && (b[1].toInt() and 0xc0) == 64
                    if (tailnet) out.put("tailnet", ip)
                    else if (nif.name.startsWith("wlan")) out.put("lan", ip)
                }
            }
        }
        return out
    }

    /**
     * TCP ports listening on this phone in the wireless-debugging range, found by
     * connecting to loopback (refused connections fail instantly). The relay tries
     * each with `adb connect` to find the wireless-debugging port.
     */
    fun localPorts(from: Int = 30000, to: Int = 49999): JSONArray {
        val pool = Executors.newFixedThreadPool(64)
        val open = java.util.concurrent.ConcurrentLinkedQueue<Int>()
        for (p in from..to) pool.execute {
            runCatching { Socket().use { s -> s.connect(InetSocketAddress("127.0.0.1", p), 200); open.add(p) } }
        }
        pool.shutdown()
        pool.awaitTermination(30, TimeUnit.SECONDS)
        return JSONArray(open.sorted())
    }

    private fun notify(c: Context, until: Long) {
        val off = PendingIntent.getService(
            c, 3, Intent(c, RelayService::class.java).setAction(RelayService.ACTION_ADB_OFF),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(until))
        val n = NotificationCompat.Builder(c, DroidHandApp.CHANNEL_ID)
            .setContentTitle(c.getString(R.string.adb_notif_title))
            .setContentText(c.getString(R.string.adb_notif_text, time))
            .setSmallIcon(R.drawable.ic_stat_droidhand)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, c.getString(R.string.adb_notif_off), off)
            .build()
        runCatching { c.getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, n) }
    }
}
