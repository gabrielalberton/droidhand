package work.alberton.droidhand.unlock

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import org.json.JSONObject
import work.alberton.droidhand.data.Prefs
import work.alberton.droidhand.data.SecretStore
import work.alberton.droidhand.shell.IShellUnlockInput
import java.security.SecureRandom

/** Pairs one authenticated relay request with one short-lived, on-device shell helper. */
object AdbPinUnlock {
    private data class Request(val challenge: String, val expiresAt: Long)
    private var request: Request? = null
    private var inProgress = false

    fun available(context: Context): Boolean = Build.VERSION.SDK_INT >= 34 && Prefs(context).let {
        it.pinUnlockEnabled && it.adbPinUnlockEnabled && SecretStore(context).hasPin
    }

    @Synchronized
    fun prepare(context: Context): JSONObject {
        check(available(context)) { "ADB PIN unlock is unavailable or has not been accepted on the phone" }
        check(!inProgress) { "An ADB unlock is already in progress" }
        val challenge = java.util.HexFormat.of().formatHex(ByteArray(32).also(SecureRandom()::nextBytes))
        request = Request(challenge, SystemClock.elapsedRealtime() + 30_000)
        return JSONObject().put("challenge", challenge)
    }

    @Synchronized
    private fun claim(challenge: String): Boolean {
        val pending = request ?: return false
        if (inProgress || SystemClock.elapsedRealtime() >= pending.expiresAt || pending.challenge != challenge) return false
        request = null
        inProgress = true
        return true
    }

    fun complete(context: Context, challenge: String, input: IShellUnlockInput): String {
        if (!claim(challenge)) return "rejected"
        try {
            val keyguard = context.getSystemService(KeyguardManager::class.java)
            if (!keyguard.isKeyguardLocked) return "already_unlocked"
            if (!available(context) || !keyguard.isDeviceSecure || !input.prepareKeyguard()) return "keyguard_unavailable"
            val pin = SecretStore(context).pin?.takeIf { it.length in 4..16 && it.all { c -> c in '0'..'9' } }
                ?: return "numeric_pin_required"
            if (!available(context) || !keyguard.isKeyguardLocked) return "keyguard_changed"
            val digits = IntArray(pin.length) { pin[it] - '0' }
            val prefs = Prefs(context)
            try {
                if (!prefs.beginAdbPinAttempt()) return "attempt_failed"
                if (!input.submitPin(digits)) {
                    prefs.pinUnlockEnabled = false
                    return "attempt_failed"
                }
                val deadline = SystemClock.elapsedRealtime() + 5_000
                while (keyguard.isKeyguardLocked && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
                if (!keyguard.isKeyguardLocked) {
                    prefs.finishAdbPinAttempt()
                    return "unlocked"
                }
            } catch (_: Exception) {
                // An interrupted Binder call may already have delivered the attempt.
            } finally {
                digits.fill(0)
            }
            prefs.pinUnlockEnabled = false
            return "attempt_failed"
        } finally {
            synchronized(this) { inProgress = false }
        }
    }
}
