package work.alberton.droidhand.shell

import android.os.Binder
import android.os.IBinder
import android.os.SystemClock
import android.util.Xml
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** Accepts numeric input only on the system PIN pad and never writes digits to a shell command. */
internal class ShellUnlockInput : IShellUnlockInput.Stub() {
    private var prepared = false
    private val systemUiUid by lazy {
        readCommand("cmd", "package", "list", "packages", "--user", "0", "-U", SYSTEM_UI)
            ?.let { Regex("(?m)^package:com\\.android\\.systemui uid:(\\d+)$").find(it.trim()) }
            ?.groupValues?.get(1)?.toIntOrNull() ?: error("System UI UID not available")
    }
    // Android 17's app-side KeyguardManager needs shared memory unavailable in app_process.
    private val trustType by lazy { Class.forName("android.app.trust.ITrustManager") }
    private val trust by lazy {
        val service = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
            .invoke(null, "trust") as IBinder
        Class.forName("android.app.trust.ITrustManager\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, service)
    }
    private val managerClass by lazy { Class.forName("android.hardware.input.InputManagerGlobal") }
    private val manager by lazy { managerClass.getMethod("getInstance").invoke(null) }
    private val inject by lazy { managerClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType) }
    private val setDisplay by lazy { InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType) }

    override fun prepareKeyguard(): Boolean = asShell {
        if (readCommand("am", "get-current-user")?.trim() != "0" || !locked()) return@asShell false
        // Resolve the targeted method before requesting any digits from the app.
        inject
        if (!runCommand(2, "input", "keyevent", "KEYCODE_WAKEUP")) return@asShell false
        SystemClock.sleep(500)
        if (!locked()) return@asShell false
        if (!runCommand(2, "input", "keyevent", "KEYCODE_MENU")) return@asShell false
        if (pinLength() == null) {
            val size = Regex("size: (\\d+)x(\\d+)").findAll(readCommand("wm", "size").orEmpty()).lastOrNull()
                ?: return@asShell false
            val width = size.groupValues[1].toInt()
            val height = size.groupValues[2].toInt()
            val x = (width / 2).toString()
            if (!locked()) return@asShell false
            runCommand(2, "input", "swipe", x, (height * 3 / 4).toString(), x, (height / 6).toString(), "250")
        }
        prepared = locked() && pinLength() == 0
        prepared
    }

    override fun submitPin(digits: IntArray): Boolean = asShell {
        try {
            if (!prepared || digits.size !in 4..16 || digits.any { it !in 0..9 }) return@asShell false
            prepared = false
            if (!locked() || pinLength() != 0) return@asShell false
            for (digit in digits) {
                if (!locked() || !press(KeyEvent.KEYCODE_0 + digit)) return@asShell false
            }
            // Some keyguards confirm automatically. For the others, verify the field before Enter.
            SystemClock.sleep(700)
            if (!locked()) return@asShell true
            val length = pinLength()
            if (!locked()) return@asShell true
            length == digits.size && press(KeyEvent.KEYCODE_ENTER)
        } finally {
            digits.fill(0)
        }
    }

    private fun locked(): Boolean = trustType.getMethod("isDeviceLocked", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        .invoke(trust, 0, 0) == true

    private fun readCommand(vararg command: String): String? {
        val process = ProcessBuilder(*command).redirectError(File("/dev/null")).start()
        if (!process.waitFor(3, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return null
        }
        return process.inputStream.bufferedReader().use { if (process.exitValue() == 0) it.readText() else null }
    }

    private fun press(code: Int): Boolean {
        val downTime = SystemClock.uptimeMillis()
        return listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP).all { action ->
            val event = KeyEvent(downTime, SystemClock.uptimeMillis(), action, code, 0, 0, -1, 0, 0, InputDevice.SOURCE_KEYBOARD)
            setDisplay.invoke(event, 0)
            inject.invoke(manager, event, 2, systemUiUid) == true // wait for finish, targeted UID only
        }
    }

    private fun pinLength(): Int? {
        val directory = Files.createTempDirectory(File("/data/local/tmp").toPath(), "droidhand-keyguard-").toFile()
        val tree = File(directory, "window.xml")
        try {
            if (!runCommand(5, "uiautomator", "dump", tree.path)) return null
            tree.inputStream().use { stream ->
                val parser = Xml.newPullParser().apply { setInput(stream, null) }
                while (parser.next() != XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType != XmlPullParser.START_TAG || parser.name != "node") continue
                    fun attr(name: String) = parser.getAttributeValue(null, name)
                    if (attr("resource-id") == "$SYSTEM_UI:id/pinEntry" && attr("package") == SYSTEM_UI &&
                        attr("focused") == "true" && attr("password") == "true" && attr("enabled") == "true") {
                        return attr("text")?.length
                    }
                }
            }
            return null
        } finally {
            tree.delete()
            directory.delete()
        }
    }

    private fun runCommand(seconds: Long, vararg command: String): Boolean {
        val process = ProcessBuilder(*command).redirectErrorStream(true).redirectOutput(File("/dev/null")).start()
        if (process.waitFor(seconds, TimeUnit.SECONDS)) return process.exitValue() == 0
        process.destroyForcibly()
        return false
    }

    private inline fun <T> asShell(block: () -> T): T {
        val identity = Binder.clearCallingIdentity()
        try {
            return block()
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    private companion object { const val SYSTEM_UI = "com.android.systemui" }
}
