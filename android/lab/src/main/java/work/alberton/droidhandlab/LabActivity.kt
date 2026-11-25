package work.alberton.droidhandlab

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * One service on at a time (or none). Needs the secure-settings grant to flip them here:
 *   adb shell pm grant work.alberton.droidhandlab android.permission.WRITE_SECURE_SETTINGS
 * The text box shows what a banking app would see through AccessibilityManager.
 */
class LabActivity : Activity() {
    private val variants = listOf(
        Probe.A::class.java to "A  sem capacidades (só avisa troca de janela)",
        Probe.B::class.java to "B  só ler conteúdo da tela",
        Probe.C::class.java to "C  só gestos (tocar/deslizar)",
        Probe.D::class.java to "D  só captura de tela",
        Probe.E::class.java to "E  completo, como o DroidHand (isAccessibilityTool = true)",
        Probe.F::class.java to "F  completo, SEM isAccessibilityTool",
    )
    private lateinit var info: TextView
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 48, 32, 32) }
        col.addView(TextView(this).apply {
            text = "DroidHand Lab\nCada botão liga UM serviço do lab (os seus outros serviços não são mexidos). Abra o Nubank do zero (feche e abra) e anote se ele bloqueia."
            textSize = 16f
        })
        col.addView(button("Nenhum (linha de base)") { select(null) })
                variants.forEach { (cls, label) -> col.addView(button(label) { select(cls) }) }
        info = TextView(this).apply { textSize = 12f; typeface = android.graphics.Typeface.MONOSPACE; setPadding(0, 24, 0, 0) }
        col.addView(info)
        setContentView(ScrollView(this).apply { addView(col) })
        handle(intent)
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun button(t: String, click: () -> Unit) = Button(this).apply { text = t; isAllCaps = false; setOnClickListener { click() } }

    private fun canWrite() = checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED

    /** Only our own lab service changes; every other accessibility service the user has on stays as it is. */
    private fun select(cls: Class<*>?) {
        if (!canWrite()) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); return
        }
        val others = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            .split(':').filter { it.isNotBlank() && !it.startsWith("$packageName/") }
        apply(if (cls == null) others else others + ComponentName(this, cls).flattenToString())
    }

    private fun restore() = select(null)

    private fun apply(list: List<String>) {
        Settings.Secure.putString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, list.joinToString(":").ifEmpty { null })
        Settings.Secure.putInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, if (list.isEmpty()) 0 else 1)
        main.postDelayed({ refresh() }, 600)
    }

    /** `am start -n work.alberton.droidhandlab/.LabActivity --es variant A|B|C|D|E|F|none|restore` for scripted runs. */
    private fun handle(i: Intent?) {
        i?.getStringExtra("ask")?.let { a ->
            startForegroundService(Intent(this, AskOverlay::class.java).putExtra("ask", a).putExtra("app", i.getStringExtra("app") ?: "O Nubank"))
            i.removeExtra("ask")
        }
        if (i?.getBooleanExtra("stopask", false) == true) startForegroundService(Intent(this, AskOverlay::class.java).putExtra("stop", true))
        val v = i?.getStringExtra("variant") ?: return
        when (v) {
            "restore" -> restore()
            "none" -> select(null)
            else -> variants.firstOrNull { it.second.startsWith("$v ") }?.let { select(it.first) }
        }
        i.removeExtra("variant")
    }

    override fun onNewIntent(intent: Intent?) { super.onNewIntent(intent); setIntent(intent); handle(intent) }

    private fun refresh() {
        val am = getSystemService(AccessibilityManager::class.java)
        val sb = StringBuilder(if (canWrite()) "Permissão de configurações seguras: OK\n\n" else "SEM permissão de configurações seguras (rode o pm grant).\n\n")
        sb.append("Visto por um app via AccessibilityManager:\n")
        val list = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        if (list.isEmpty()) sb.append("  (nenhum serviço)\n")
        list.forEach { s ->
            val c = s.capabilities
            sb.append("  ${s.resolveInfo.serviceInfo.packageName}\n    ${s.resolveInfo.serviceInfo.name.substringAfterLast('.')}\n")
            sb.append("    conteúdo=${c and AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT != 0}")
            sb.append(" gestos=${c and AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES != 0}")
            sb.append(" tela=${c and AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT != 0}\n")
            sb.append("    ferramenta=${s.isAccessibilityTool} flags=0x${Integer.toHexString(s.flags)} eventos=0x${Integer.toHexString(s.eventTypes)}\n")
        }
        sb.append("\ntouchExploration=${am.isTouchExplorationEnabled}")
        info.text = sb
    }
}
