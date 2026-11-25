package work.alberton.droidhandlab

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

/**
 * A question above every app: "Did <app> open without blocks?". The answer goes to logcat (tag LabResult)
 * and to files/results.txt so a script can read it. Started with: --es ask "<label>" [--es app "<name>"].
 */
class AskOverlay : Service() {
    private var view: View? = null
    private var label: TextView? = null
    private var current = ""

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("lab", "Lab", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "lab").setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("DroidHand Lab").setContentText("Pergunta de teste ativa").build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        if (i?.getBooleanExtra("stop", false) == true) { remove(); stopSelf(); return START_NOT_STICKY }
        current = i?.getStringExtra("ask") ?: "?"
        val app = i?.getStringExtra("app") ?: "O app"
        if (view == null) show()
        label?.text = "$app abriu sem travas?  [teste $current]"
        return START_NOT_STICKY
    }

    private fun show() {
        val wm = getSystemService(WindowManager::class.java)
        val tv = TextView(this).apply { setTextColor(Color.WHITE); textSize = 15f; gravity = Gravity.CENTER; setPadding(0, 0, 0, 12) }
        label = tv
        fun btn(t: String, color: Int, res: String) = Button(this).apply {
            text = t; isAllCaps = false; setTextColor(Color.WHITE)
            background = GradientDrawable().apply { setColor(color); cornerRadius = 40f }
            setOnClickListener { answer(res) }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            addView(btn("Abriu normal", 0xFF2E7D32.toInt(), "ok"), LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(8, 0, 8, 0) })
            addView(btn("Travou / bloqueou", 0xFFC62828.toInt(), "blocked"), LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(8, 0, 8, 0) })
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(24, 20, 24, 20)
            background = GradientDrawable().apply { setColor(0xEE202124.toInt()); cornerRadius = 36f }
            addView(tv); addView(row)
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.BOTTOM; y = 220; x = 0; horizontalMargin = 0.03f }
        wm.addView(box, lp)
        view = box
    }

    private fun answer(res: String) {
        val line = "$current:$res"
        Log.i("LabResult", line)
        runCatching { File(filesDir, "results.txt").appendText(line + "\n") }
        label?.text = "Registrado: $line"
        view?.postDelayed({ remove(); stopSelf() }, 1200)
    }

    private fun remove() { runCatching { getSystemService(WindowManager::class.java).removeView(view) }; view = null }
    override fun onDestroy() { remove(); super.onDestroy() }
}
