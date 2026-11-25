package work.alberton.droidhandlab

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/** Do-nothing services; only their declared traits (capabilities, flags, tool bit) differ. */
sealed class Probe : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    class A : Probe()
    class B : Probe()
    class C : Probe()
    class D : Probe()
    class E : Probe()
    class F : Probe()
}
