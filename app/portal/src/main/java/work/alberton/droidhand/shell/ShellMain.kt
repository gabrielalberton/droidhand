package work.alberton.droidhand.shell

import android.annotation.SuppressLint
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.exitProcess

/**
 * Shell-user helper, started over ADB:
 *
 *   adb shell CLASSPATH=<apk> app_process /system/bin work.alberton.droidhand.shell.ShellMain
 *
 * It hands a binder to the DroidHand app (through the app's ShellBridgeProvider) and
 * then creates TRUSTED virtual displays that render into surfaces owned by the app.
 * Trusted displays keep third-party apps (and every activity they open) on the
 * background display. Exits when the app dies or on exit().
 *
 * Android kills shell-user processes when USB debugging is turned off, so this
 * helper only lives while debugging is on.
 */
object ShellMain {
    private const val TAG = "DroidHandShell"
    private const val AUTHORITY = "work.alberton.droidhand.shell"
    private const val SHELL_PKG = "com.android.shell"

    // DisplayManager.VIRTUAL_DISPLAY_FLAG_* (some are @hide).
    private const val PUBLIC = 1
    private const val OWN_CONTENT_ONLY = 1 shl 3
    private const val SUPPORTS_TOUCH = 1 shl 6
    private const val ROTATES_WITH_CONTENT = 1 shl 7
    private const val DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
    private const val TRUSTED = 1 shl 10
    private const val OWN_DISPLAY_GROUP = 1 shl 11
    private const val ALWAYS_UNLOCKED = 1 shl 12
    private const val TOUCH_FEEDBACK_DISABLED = 1 shl 13
    private const val OWN_FOCUS = 1 shl 14

    const val FLAGS = PUBLIC or OWN_CONTENT_ONLY or SUPPORTS_TOUCH or ROTATES_WITH_CONTENT or
        DESTROY_CONTENT_ON_REMOVAL or TRUSTED or OWN_DISPLAY_GROUP or ALWAYS_UNLOCKED or
        TOUCH_FEEDBACK_DISABLED or OWN_FOCUS

    private val displays = ConcurrentHashMap<Int, VirtualDisplay>()
    private lateinit var dm: DisplayManager

    @JvmStatic
    fun main(args: Array<String>) {
        Looper.prepareMainLooper()
        dm = displayManager()
        val appToken = attachToApp()
        appToken.linkToDeath({
            Log.i(TAG, "app died; releasing displays")
            shutdown()
        }, 0)
        Log.i(TAG, "helper ready (pid ${Process.myPid()})")
        Looper.loop()
    }

    private fun shutdown() {
        displays.values.forEach { runCatching { it.release() } }
        displays.clear()
        exitProcess(0)
    }

    private val helper = object : IShellHelper.Stub() {
        override fun createDisplay(name: String, width: Int, height: Int, dpi: Int, surface: Surface?): Int {
            val vd = dm.createVirtualDisplay(name, width, height, dpi, surface, FLAGS)
                ?: throw IllegalStateException("createVirtualDisplay returned null")
            val id = vd.display.displayId
            displays[id] = vd
            return id
        }

        override fun setSurface(displayId: Int, surface: Surface?) {
            displays[displayId]?.surface = surface
        }

        override fun resizeDisplay(displayId: Int, width: Int, height: Int, dpi: Int) {
            displays[displayId]?.resize(width, height, dpi)
        }

        override fun injectMotion(displayId: Int, event: MotionEvent): Boolean {
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            return inject(event, displayId)
        }

        override fun injectKey(displayId: Int, keyCode: Int): Boolean {
            val now = SystemClock.uptimeMillis()
            val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, 0, -1, 0, 0, InputDevice.SOURCE_KEYBOARD)
            val up = KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, 0, -1, 0, 0, InputDevice.SOURCE_KEYBOARD)
            return inject(down, displayId) && inject(up, displayId)
        }

        override fun releaseDisplay(displayId: Int) {
            displays.remove(displayId)?.release()
        }

        override fun pid(): Int = Process.myPid()

        override fun exit() {
            Thread { Thread.sleep(100); shutdown() }.start()
        }
    }

    // ---- Input injection (shell holds INJECT_EVENTS) ----
    private val injector: (InputEvent) -> Boolean by lazy {
        val cls = runCatching { Class.forName("android.hardware.input.InputManagerGlobal") }
            .getOrElse { Class.forName("android.hardware.input.InputManager") }
        val im = cls.getMethod("getInstance").invoke(null)
        val m = cls.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
        return@lazy { e: InputEvent -> m.invoke(im, e, 0) as Boolean }
    }
    private val setDisplayId by lazy { InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType) }

    private fun inject(e: InputEvent, displayId: Int): Boolean {
        setDisplayId.invoke(e, displayId)
        return injector(e)
    }

    // ---- Context & display manager without an app process (scrcpy-style) ----
    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    private fun systemContext(): Context {
        val at = Class.forName("android.app.ActivityThread")
        val thread = at.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        at.getDeclaredField("sCurrentActivityThread").apply { isAccessible = true }.set(null, thread)
        at.getDeclaredField("mSystemThread").apply { isAccessible = true }.setBoolean(thread, true)
        // Samsung's CompatSandbox reads the configuration while building the system context.
        runCatching {
            val cc = Class.forName("android.app.ConfigurationController")
            val internal = Class.forName("android.app.ActivityThreadInternal")
            val controller = cc.getDeclaredConstructor(internal).apply { isAccessible = true }.newInstance(thread)
            at.getDeclaredField("mConfigurationController").apply { isAccessible = true }.set(thread, controller)
        }
        return at.getDeclaredMethod("getSystemContext").invoke(thread) as Context
    }

    private class ShellContext(base: Context) : ContextWrapper(base) {
        override fun getPackageName() = SHELL_PKG
        override fun getOpPackageName() = SHELL_PKG
        override fun getAttributionSource(): AttributionSource =
            AttributionSource.Builder(Process.SHELL_UID).setPackageName(SHELL_PKG).build()
        override fun getApplicationContext(): Context = this
    }

    private fun displayManager(): DisplayManager {
        val ctx = ShellContext(systemContext())
        return DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }.newInstance(ctx)
    }

    // ---- Hand our binder to the app through its content provider ----
    @SuppressLint("PrivateApi")
    private fun attachToApp(): IBinder {
        val am = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)!!
        val token = Binder()
        val getExt = am.javaClass.methods.first { it.name == "getContentProviderExternal" && it.parameterTypes.size == 4 }
        val holder = getExt.invoke(am, AUTHORITY, 0, token, TAG)
            ?: error("DroidHand app provider not found (is the app installed?)")
        val provider = holder.javaClass.getField("provider").get(holder)!!
        val call = provider.javaClass.methods.first {
            it.name == "call" && it.parameterTypes.size == 5 && it.parameterTypes[0] == AttributionSource::class.java
        }
        val attribution = AttributionSource.Builder(Process.SHELL_UID).setPackageName(SHELL_PKG).build()
        val reply = call.invoke(provider, attribution, AUTHORITY, "attach", null,
            Bundle().apply { putBinder("helper", helper.asBinder()) }) as Bundle?
        runCatching {
            am.javaClass.methods.first { it.name == "removeContentProviderExternalAsUser" }
                .invoke(am, AUTHORITY, token, 0)
        }
        return reply?.getBinder("app") ?: error("app did not return a token")
    }
}
