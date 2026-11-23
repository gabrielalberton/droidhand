package work.alberton.droidhand.tools

import android.content.Context
import android.os.SystemClock
import org.json.JSONObject
import work.alberton.droidhand.a11y.DroidHandAccessibilityService
import work.alberton.droidhand.bg.BackgroundSession
import work.alberton.droidhand.data.Prefs

/**
 * Entry point for every tool call coming from the relay. Applies the safety
 * guards (Stop, background-display controls, main-screen confirmation) and then
 * hands the call to the [ToolGroup] that knows it. Runs on the relay socket
 * thread, never on the main thread.
 */
class ToolRouter(context: Context) {
    private val app = context.applicationContext
    private val prefs = Prefs(app)
    private val groups: List<ToolGroup> = listOf(SystemTools(app), ScreenTools(), AppTools(), BackgroundTools(), AdbTools())

    fun run(tool: String, args: JSONObject): ToolResult {
        val svc = DroidHandAccessibilityService.instance
        val started = SystemClock.elapsedRealtime()
        // The user tapped Stop since the last action: tell the agent once, on its next call.
        if (svc != null && svc.stopRequestedAt != 0L && tool != "panic") {
            val fresh = ToolPolicy.stopIsFresh(started, svc.stopRequestedAt)
            svc.stopRequestedAt = 0L
            if (fresh) return ToolResult.fail(ToolPolicy.STOPPED)
        }
        val res = try {
            guard(tool, args, svc) ?: dispatch(tool, args, svc)
        } catch (e: Exception) {
            ToolResult.fail("${e.javaClass.simpleName}: ${e.message}")
        }
        // Stop tapped while this action ran: report it as cancelled.
        if (svc != null && svc.stopRequestedAt != 0L && svc.stopRequestedAt >= started) {
            svc.stopRequestedAt = 0L
            return ToolResult.fail(ToolPolicy.STOPPED)
        }
        return res
    }

    /** Safety checks before any tool runs; returns an error to stop the call, or null to go on. */
    private fun guard(tool: String, args: JSONObject, svc: DroidHandAccessibilityService?): ToolResult? {
        if (svc == null) return null
        // Every tool may target a display: 0 = phone screen (default), N = background display.
        val display = args.optInt("display", 0)
        svc.displayId = display
        // Optional: one window on that display (id from list_windows, or a package), e.g. a pop-up.
        svc.windowTarget = args.opt("window")?.toString()?.trim()?.takeIf { it.isNotEmpty() && tool != "launch_app" }
        // Background windows only run unlocked: while the phone is locked Android pauses their
        // apps (seen on a Galaxy S24 FE: black window, empty tree, though launches "succeed").
        if ((display != 0 || tool == "bg_open") && tool in ToolPolicy.BG_NEEDS_UNLOCK &&
            svc.getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true) {
            return ToolResult.fail(ToolPolicy.BG_LOCKED)
        }
        // Background display: only ours, and only while the user can see the controls.
        if (display != 0) {
            val bg = runCatching { svc.bg }.getOrNull() ?: return ToolResult.fail("accessibility service not ready")
            if (!bg.active || bg.displayId != display) return ToolResult.fail(
                "no_background_session: display $display is not an open DroidHand background display; call bg_open first")
            bg.ensureIndicators()?.let { why ->
                return ToolResult.fail("${BackgroundSession.INDICATORS_ERROR} ($why). The background session was closed; " +
                    "do not continue there. Tell the user, or try bg_open again later.")
            }
            if (bg.mode == BackgroundSession.Mode.NATIVE && tool in ToolPolicy.NEEDS_A11Y_ON_DISPLAY &&
                android.os.Build.VERSION.SDK_INT < ToolPolicy.NATIVE_A11Y_MIN_SDK) return ToolResult.fail(ToolPolicy.NATIVE_NO_A11Y)
        }
        // Main screen while the user is using the phone: ask first (background windows never ask).
        if (ToolPolicy.needsMainScreenGate(tool, display, agentWindow(tool, args, svc))) {
            runCatching { svc.gate }.getOrNull()?.check()?.let { return ToolResult.fail(it) }
        }
        // Show the floating Stop button whenever the agent acts on the screen.
        if (tool in ToolPolicy.ACTING) runCatching { svc.stopButton.bump() }
        return null
    }

    /**
     * Opening a pop-up, or acting inside a pop-up/split window the agent itself opened (its
     * package was launched that way and the window is still not full screen): the user's own
     * screen is not touched, so no prompt. Opening a split window does ask (it shrinks the user's app).
     */
    private fun agentWindow(tool: String, args: JSONObject, svc: DroidHandAccessibilityService): Boolean {
        if (tool == "launch_app") return !args.optBoolean("split", false) &&
            (args.has("bounds") || args.optBoolean("popup", false))
        if (svc.windowTarget == null) return false
        val pkg = svc.targetWindowPackage() ?: return false
        val b = svc.targetWindowBounds() ?: return false
        val size = android.graphics.Point().also {
            svc.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(0)?.getRealSize(it)
        }
        return AppTools.isAgentWindow(pkg) &&
            ToolPolicy.windowLayout(b.left, b.top, b.right, b.bottom, size.x, size.y) != "full"
    }

    private fun dispatch(tool: String, args: JSONObject, svc: DroidHandAccessibilityService?): ToolResult {
        val ctx = ToolContext(app, prefs, svc)
        for (g in groups) g.handle(tool, args, ctx)?.let { return it }
        return ToolResult.fail("not_implemented: $tool")
    }
}
