package work.alberton.droidhand.unlock

import android.app.KeyguardManager
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import work.alberton.droidhand.a11y.DroidHandAccessibilityService
import work.alberton.droidhand.data.Prefs
import work.alberton.droidhand.data.SecretStore

/**
 * Unlocks the phone with the PIN the user saved in the app (opt-in, off by
 * default). Wakes the screen, opens the lock screen's PIN pad and presses its
 * keys through accessibility. The PIN never leaves the phone.
 *
 * A single wrong attempt disables the feature, so the agent can never run into
 * Android's lockout / wipe limits.
 */
class Unlocker(private val svc: DroidHandAccessibilityService) {
    private val prefs = Prefs(svc)
    private val km = svc.getSystemService(KeyguardManager::class.java)

    private fun locked() = km?.isKeyguardLocked == true

    fun unlock(): JSONObject {
        if (!locked()) return JSONObject().put("unlocked", true).put("alreadyUnlocked", true)
        if (!prefs.pinUnlockEnabled) throw IllegalStateException(
            "unlock_disabled: automatic unlock is off. Ask the user to unlock, or to enable it in DroidHand → Privacy & safety.",
        )
        val pin = SecretStore(svc).pin?.takeIf { it.isNotEmpty() }
            ?: throw IllegalStateException("unlock_no_pin: no PIN saved in the app.")
        if (!km!!.isDeviceSecure) {
            // Swipe-only lock screen: dismissing it is enough.
            openPinPad()
            return waitUnlocked(3000).let { ok -> JSONObject().put("unlocked", ok) }
        }

        openPinPad()
        val keys = waitForKeys(4000)
            ?: return fail("PIN pad not found on the lock screen", seen())
        for (ch in pin) {
            val node = keys[ch] ?: return fail("key '${if (ch.isDigit()) "digit" else "char"}' not found", seen())
            if (!press(node)) return fail("could not press a PIN key", null)
            SystemClock.sleep(90)
        }
        if (waitUnlocked(1200)) return ok()
        // Not auto-confirmed: press OK/Enter.
        findEnter()?.let { press(it) }
        return if (waitUnlocked(3000)) ok() else fail("still locked after entering the PIN", null, wrongPin = true)
    }

    private fun ok() = JSONObject().put("unlocked", true)

    private fun fail(why: String, seen: JSONArray?, wrongPin: Boolean = false): JSONObject {
        if (wrongPin) prefs.pinUnlockEnabled = false // never risk a second wrong attempt
        val msg = "unlock_failed: $why." + if (wrongPin) " Automatic unlock is now disabled until the user re-enables it in the app." else ""
        val err = IllegalStateException(msg)
        if (seen != null) throw IllegalStateException("$msg Lock screen elements: $seen")
        throw err
    }

    private fun openPinPad() {
        svc.startActivity(
            Intent(svc, WakeActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
            ),
        )
    }

    private fun waitUnlocked(ms: Long): Boolean {
        val end = SystemClock.elapsedRealtime() + ms
        while (SystemClock.elapsedRealtime() < end) {
            if (!locked()) return true
            SystemClock.sleep(100)
        }
        return !locked()
    }

    /** All nodes of SystemUI (lock screen) windows. */
    private fun keyguardNodes(): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        for (w in svc.windows) {
            val root = w.root ?: continue
            if (root.packageName?.toString() != "com.android.systemui") continue
            val stack = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
            while (stack.isNotEmpty() && out.size < 600) {
                val n = stack.removeLast()
                out.add(n)
                for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it) }
            }
        }
        return out
    }

    private fun label(n: AccessibilityNodeInfo): String =
        (n.text?.toString()?.takeIf { it.isNotBlank() } ?: n.contentDescription?.toString() ?: "").trim()

    /** Digit keys of the PIN pad, by their label ("1" … "0"). */
    private fun waitForKeys(ms: Long): Map<Char, AccessibilityNodeInfo>? {
        val end = SystemClock.elapsedRealtime() + ms
        while (SystemClock.elapsedRealtime() < end) {
            val keys = HashMap<Char, AccessibilityNodeInfo>()
            for (n in keyguardNodes()) {
                val d = PinPad.digitOf(label(n)) ?: continue
                if (n.isVisibleToUser) {
                    val target = if (n.isClickable) n else clickableAncestor(n) ?: n
                    keys.putIfAbsent(d, target)
                }
            }
            if (keys.size >= 10) return keys
            SystemClock.sleep(150)
        }
        return null
    }

    private fun clickableAncestor(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var p = n.parent
        var depth = 0
        while (p != null && depth < 3) { if (p.isClickable) return p; p = p.parent; depth++ }
        return null
    }

    private fun findEnter(): AccessibilityNodeInfo? = keyguardNodes().firstOrNull { n ->
        n.isVisibleToUser && PinPad.isEnter(label(n), n.viewIdResourceName.orEmpty())
    }

    private fun press(n: AccessibilityNodeInfo): Boolean {
        if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        val r = Rect().also { n.getBoundsInScreen(it) }
        if (r.isEmpty) return false
        val prev = svc.displayId
        svc.displayId = 0
        return svc.tapAt(r.centerX(), r.centerY()).also { svc.displayId = prev }
    }

    /** What the agent can be told about the lock screen (labels only, never typed digits). */
    private fun seen(): JSONArray = JSONArray().apply {
        keyguardNodes().map { label(it) }.filter { it.isNotEmpty() && it.length < 40 }.distinct().take(40).forEach { put(it) }
    }
}
