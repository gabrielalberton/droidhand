package work.alberton.droidhand.tools

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolPolicyTest {

    @Test fun everyMainGatedToolAlsoShowsTheStopButton() {
        assertTrue(ToolPolicy.ACTING.containsAll(ToolPolicy.MAIN_GATED))
    }

    @Test fun readOnlyToolsNeverAskOrShowStop() {
        for (t in listOf("screen_snapshot", "screen_capture", "get_device_info", "get_lock_state", "bg_status")) {
            assertFalse(t, t in ToolPolicy.ACTING)
            assertFalse(t, ToolPolicy.needsMainScreenGate(t, 0, false))
        }
    }

    @Test fun gateOnlyOnTheMainScreenWithoutPopupBounds() {
        assertTrue(ToolPolicy.needsMainScreenGate("tap", 0, false))
        assertFalse(ToolPolicy.needsMainScreenGate("tap", 7, false))
        assertFalse(ToolPolicy.needsMainScreenGate("launch_app", 0, true))
    }

    @Test fun settingsAllowlistIsDebuggingAndAnimationsOnly() {
        assertTrue("global/window_animation_scale" in ToolPolicy.WRITABLE)
        // Debugging only through time-limited ADB sessions, never set_setting.
        assertFalse("global/adb_enabled" in ToolPolicy.WRITABLE)
        assertFalse("global/adb_wifi_enabled" in ToolPolicy.WRITABLE)
        assertFalse("secure/enabled_accessibility_services" in ToolPolicy.WRITABLE)
        assertFalse("secure/lock_screen_lock_after_timeout" in ToolPolicy.WRITABLE)
        assertTrue(ToolPolicy.WRITABLE.all { it.startsWith("global/") })
    }

    @Test fun stopNoticeExpires() {
        assertFalse(ToolPolicy.stopIsFresh(now = 1_000, stopAt = 0))
        assertTrue(ToolPolicy.stopIsFresh(now = 10_000, stopAt = 9_000))
        assertFalse(ToolPolicy.stopIsFresh(now = 9_000 + ToolPolicy.STOP_NOTICE_MS, stopAt = 9_000))
    }

    @Test fun scrollDirectionsMoveTheContentThatWay() {
        // "down" = see what is below = finger moves up.
        assertArrayEquals(intArrayOf(500, 1666, 500, 334), ToolPolicy.scrollSwipe("down", 1000, 2000))
        assertArrayEquals(intArrayOf(500, 334, 500, 1666), ToolPolicy.scrollSwipe("up", 1000, 2000))
        assertArrayEquals(intArrayOf(833, 1000, 167, 1000), ToolPolicy.scrollSwipe("left", 1000, 2000))
        assertNull(ToolPolicy.scrollSwipe("diagonal", 1000, 2000))
    }

    @Test fun refsAreNormalized() {
        assertEquals("@3", ToolPolicy.normalizeRef("3"))
        assertEquals("@3", ToolPolicy.normalizeRef("@3"))
        assertNull(ToolPolicy.normalizeRef("  "))
    }

    @Test fun stoppedMessageIsStable() {
        // The MCP server and agents match on this prefix.
        assertTrue(ToolPolicy.STOPPED.startsWith("stopped_by_user:"))
    }

    @Test fun windowLayoutOnAPhoneScreen() {
        val w = 1080; val h = 2340
        assertEquals("full", ToolPolicy.windowLayout(0, 0, w, h, w, h))
        assertEquals("full", ToolPolicy.windowLayout(0, 80, w, 2210, w, h)) // minus status and nav bars
        assertEquals("split", ToolPolicy.windowLayout(0, 0, w, 1150, w, h)) // top half
        assertEquals("split", ToolPolicy.windowLayout(0, 1190, w, h, w, h)) // bottom half
        assertEquals("split", ToolPolicy.windowLayout(0, 0, 1150, 1080, 2340, 1080)) // landscape left half
        val p = ToolPolicy.defaultPopupBounds(w, h)
        assertEquals("popup", ToolPolicy.windowLayout(p[0], p[1], p[2], p[3], w, h))
        assertEquals("popup", ToolPolicy.windowLayout(0, 600, w, 1700, w, h)) // full width, middle
    }

    @Test fun defaultPopupLeavesTheTopForTheUser() {
        val b = ToolPolicy.defaultPopupBounds(1080, 2340)
        assertTrue(b[1] > 2340 / 3)
        assertTrue(b[0] > 0 && b[2] < 1080 && b[3] < 2340)
    }
}
