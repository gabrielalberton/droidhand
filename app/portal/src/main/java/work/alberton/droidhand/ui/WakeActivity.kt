package work.alberton.droidhand.ui

import android.app.Activity
import android.app.KeyguardManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper

/**
 * Invisible activity that turns the screen on and asks the system to show the
 * PIN entry of the lock screen. It cannot unlock anything by itself.
 */
class WakeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        val km = getSystemService(KeyguardManager::class.java)
        if (km?.isKeyguardLocked == true) {
            km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = finish()
                override fun onDismissCancelled() = finish()
                override fun onDismissError() = finish()
            })
        }
        // Never linger: the lock screen keeps its own PIN entry open.
        Handler(Looper.getMainLooper()).postDelayed({ if (!isFinishing) finish() }, 15_000)
    }
}
