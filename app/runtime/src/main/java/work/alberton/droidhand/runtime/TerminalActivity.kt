package work.alberton.droidhand.runtime

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import kotlin.concurrent.thread

/** Terminal + recipe buttons + a row of extra keys. */
class TerminalActivity : Activity(), TerminalViewClient, TerminalSessionClient {
    private lateinit var terminal: TerminalView
    private lateinit var status: TextView
    private lateinit var tabButton: Button
    private lateinit var ctrlButton: TextView
    private val linux by lazy { Linux(this) }
    private var ctrlArmed = false
    private var fontPx = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }

        val recipes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        recipes.addView(smallButton("Instalar Alpine") { installBase() })
        tabButton = smallButton("Aba 1") { nextSession() }.also { recipes.addView(it) }
        recipes.addView(smallButton("+ Aba") { newSession() })
        Recipes.all.forEach { r -> recipes.addView(smallButton(r.label) { runRecipe(r) }) }
        root.addView(HorizontalScrollView(this).apply { addView(recipes) })

        status = TextView(this).apply {
            setTextColor(Color.parseColor("#80CBC4")); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(8), 0, dp(8), 0)
        }
        root.addView(status)

        fontPx = dp(11)
        terminal = TerminalView(this, null).apply {
            setTerminalViewClient(this@TerminalActivity)
            setTextSize(fontPx)
            setTypeface(Typeface.MONOSPACE)
            isFocusable = true; isFocusableInTouchMode = true
        }
        root.addView(terminal, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val keys = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        keys.addView(keyButton("ESC") { sendKey(KeyEvent.KEYCODE_ESCAPE) })
        keys.addView(keyButton("TAB") { sendKey(KeyEvent.KEYCODE_TAB) })
        ctrlButton = keyButton("CTRL") { ctrlArmed = !ctrlArmed; paintCtrl() }.also { keys.addView(it) }
        keys.addView(keyButton("↑") { sendKey(KeyEvent.KEYCODE_DPAD_UP) })
        keys.addView(keyButton("↓") { sendKey(KeyEvent.KEYCODE_DPAD_DOWN) })
        keys.addView(keyButton("←") { sendKey(KeyEvent.KEYCODE_DPAD_LEFT) })
        keys.addView(keyButton("→") { sendKey(KeyEvent.KEYCODE_DPAD_RIGHT) })
        keys.addView(keyButton("⇧TAB") { sendKey(KeyEvent.KEYCODE_TAB, KeyEvent.META_SHIFT_ON) })
        root.addView(keys, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40)))

        setContentView(root)
        if (linux.installed) attach(Sessions.active() ?: Sessions.create(this, this))
        else status.text = "Toque em “Instalar Alpine” (≈4 MB) para criar o Linux mínimo."
    }

    override fun onResume() {
        super.onResume()
        Sessions.active()?.let { attach(it) }
    }

    // ---- sessions ----

    private fun attach(s: TerminalSession) {
        s.updateTerminalSessionClient(this)
        terminal.attachSession(s)
        tabButton.text = "Aba ${Sessions.current + 1}/${Sessions.list.size}"
        terminal.requestFocus()
    }

    private fun newSession() {
        if (!linux.installed) return toast("Instale o Alpine primeiro")
        attach(Sessions.create(this, this))
    }

    private fun nextSession() {
        if (Sessions.list.isEmpty()) return newSession()
        Sessions.current = (Sessions.current + 1) % Sessions.list.size
        attach(Sessions.list[Sessions.current])
    }

    private fun installBase() {
        if (linux.installed) { linux.configure(); return toast("Alpine já instalado (DNS atualizado)") }
        status.text = "Instalando…"
        thread {
            try {
                linux.install { msg -> runOnUiThread { status.text = msg } }
                runOnUiThread { attach(Sessions.create(this, this)); runRecipe(Recipes.all.first()) }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Falhou: ${e.message}" }
            }
        }
    }

    private fun runRecipe(r: Recipe) {
        val s = Sessions.active() ?: return toast("Instale o Alpine primeiro")
        status.text = "Receita: ${r.label}"
        s.write(r.script + "\n")
    }

    // ---- keys ----

    private fun sendKey(code: Int, meta: Int = 0) {
        val now = System.currentTimeMillis()
        terminal.onKeyDown(code, KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, meta))
        terminal.onKeyUp(code, KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, meta))
    }

    private fun paintCtrl() = ctrlButton.setTextColor(if (ctrlArmed) Color.parseColor("#FFD54F") else Color.WHITE)

    // ---- small UI helpers ----

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    private fun smallButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        minHeight = 0; minimumHeight = 0; setPadding(dp(10), dp(4), dp(10), dp(4))
        setOnClickListener { onClick() }
    }

    private fun keyButton(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        setOnClickListener { onClick() }
    }

    // ---- TerminalViewClient ----

    override fun onScale(scale: Float): Float {
        if (scale < 0.9f || scale > 1.1f) {
            fontPx = (fontPx * if (scale > 1f) 1.1f else 0.9f).toInt().coerceIn(dp(6), dp(24))
            terminal.setTextSize(fontPx)
            return 1f
        }
        return scale
    }

    override fun onSingleTapUp(e: MotionEvent) {
        terminal.requestFocus()
        getSystemService(InputMethodManager::class.java).showSoftInput(terminal, 0)
    }

    override fun shouldBackButtonBeMappedToEscape() = false
    override fun shouldEnforceCharBasedInput() = true
    override fun shouldUseCtrlSpaceWorkaround() = false
    override fun isTerminalViewSelected() = true
    override fun copyModeChanged(copyMode: Boolean) {}
    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession) = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent) = false
    override fun onLongPress(event: MotionEvent) = false
    override fun readControlKey(): Boolean = ctrlArmed
    override fun readAltKey() = false
    override fun readShiftKey() = false
    override fun readFnKey() = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean {
        if (ctrlArmed) { ctrlArmed = false; runOnUiThread { paintCtrl() } }
        return false
    }

    override fun onEmulatorSet() {}

    // ---- TerminalSessionClient ----

    override fun onTextChanged(changedSession: TerminalSession) = terminal.onScreenUpdated()
    override fun onTitleChanged(changedSession: TerminalSession) {}

    override fun onSessionFinished(finishedSession: TerminalSession) {
        Sessions.remove(finishedSession)
        if (Sessions.list.isEmpty()) {
            RuntimeService.stop(this)
            status.text = "Sessão encerrada. Toque em “+ Aba” para abrir outra."
        } else attach(Sessions.active()!!)
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("terminal", text))
        toast("Copiado")
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val clip = getSystemService(ClipboardManager::class.java).primaryClip ?: return
        val text = clip.getItemAt(0).coerceToText(this).toString()
        session?.emulator?.paste(text)
    }

    override fun onBell(session: TerminalSession) {}
    override fun onColorsChanged(session: TerminalSession) {}
    override fun onTerminalCursorStateChange(state: Boolean) {}
    override fun getTerminalCursorStyle(): Int? = null

    override fun logError(tag: String?, message: String?) { android.util.Log.e(tag, message ?: "") }
    override fun logWarn(tag: String?, message: String?) { android.util.Log.w(tag, message ?: "") }
    override fun logInfo(tag: String?, message: String?) {}
    override fun logDebug(tag: String?, message: String?) {}
    override fun logVerbose(tag: String?, message: String?) {}
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) { android.util.Log.e(tag, message, e) }
    override fun logStackTrace(tag: String?, e: Exception?) { android.util.Log.e(tag, "error", e) }
}
