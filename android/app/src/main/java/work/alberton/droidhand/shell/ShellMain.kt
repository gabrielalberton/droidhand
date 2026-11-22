package work.alberton.droidhand.shell

import android.annotation.SuppressLint
import android.content.AttributionSource
import android.content.Context
import android.content.Intent
import android.app.ActivityOptions
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
    /** Android 14+: launches/touches on this display never take the top focus (the user's keyboard stays). */
    private const val STEAL_TOP_FOCUS_DISABLED = 1 shl 16

    const val FLAGS = PUBLIC or OWN_CONTENT_ONLY or SUPPORTS_TOUCH or ROTATES_WITH_CONTENT or
        DESTROY_CONTENT_ON_REMOVAL or TRUSTED or OWN_DISPLAY_GROUP or ALWAYS_UNLOCKED or
        TOUCH_FEEDBACK_DISABLED or OWN_FOCUS or STEAL_TOP_FOCUS_DISABLED

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

        override fun mouseClick(displayId: Int, x: Int, y: Int): Boolean {
            val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE })
            val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x.toFloat(); this.y = y.toFloat(); pressure = 1f; size = 1f })
            val down = SystemClock.uptimeMillis()
            fun ev(action: Int, buttons: Int, actionButton: Int = 0): MotionEvent =
                MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, 1, props, coords, 0, buttons, 1f, 1f, 0, 0,
                    InputDevice.SOURCE_MOUSE, 0).also { e ->
                    if (actionButton != 0) runCatching {
                        MotionEvent::class.java.getMethod("setActionButton", Int::class.javaPrimitiveType).invoke(e, actionButton)
                    }
                }
            val primary = MotionEvent.BUTTON_PRIMARY
            return inject(ev(MotionEvent.ACTION_DOWN, primary), displayId) &&
                inject(ev(MotionEvent.ACTION_BUTTON_PRESS, primary, primary), displayId) &&
                inject(ev(MotionEvent.ACTION_BUTTON_RELEASE, 0, primary), displayId) &&
                inject(ev(MotionEvent.ACTION_UP, 0), displayId)
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

        override fun launchApp(displayId: Int, packageName: String): String = onMainLooper { launchNow(displayId, packageName) }

        private fun launchNow(displayId: Int, packageName: String): String {
            val ctx = systemContext()
            val intent = ctx.packageManager.getLaunchIntentForPackage(packageName)
                ?: throw IllegalArgumentException("no launchable activity for $packageName")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            val opts = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
            displays[displayId]?.display?.let { d ->
                val size = android.graphics.Point().also { d.getRealSize(it) }
                if (size.x > 0) opts.setLaunchBounds(android.graphics.Rect(0, 0, size.x, size.y))
            }
            // Straight to the activity manager as com.android.shell (the system context's
            // package, "android", does not belong to the shell uid).
            val iam = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)
            val start = Class.forName("android.app.IActivityManager").getMethod(
                "startActivityAsUserWithFeature", Class.forName("android.app.IApplicationThread"), String::class.java,
                String::class.java, Intent::class.java, String::class.java, IBinder::class.java, String::class.java,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Class.forName("android.app.ProfilerInfo"),
                Bundle::class.java, Int::class.javaPrimitiveType,
            )
            val code = start.invoke(iam, null, SHELL_PKG, null, intent, null, null, null, 0, 0, null, opts.toBundle(), -2) as Int
            if (code < 0) throw IllegalStateException("startActivity returned $code")
            return "shell"
        }

        override fun pid(): Int = Process.myPid()

        override fun exit() {
            Thread { Thread.sleep(100); shutdown() }.start()
        }
    }

    /** Run [block] on the helper's main looper (startActivity needs a Looper thread) and return its result. */
    private fun <T> onMainLooper(block: () -> T): T {
        val result = java.util.concurrent.atomic.AtomicReference<Result<T>>()
        val done = java.util.concurrent.CountDownLatch(1)
        android.os.Handler(Looper.getMainLooper()).post { result.set(runCatching(block)); done.countDown() }
        if (!done.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw IllegalStateException("helper main thread busy")
        return result.get().getOrThrow()
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
    // Runs as the shell user via app_process, where hidden-API restrictions do not apply.
    @SuppressLint("PrivateApi", "DiscouragedPrivateApi", "BlockedPrivateApi")
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
