package work.alberton.droidhand.unlock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PinPadTest {

    @Test fun plainDigitKeys() {
        for (d in '0'..'9') assertEquals(d, PinPad.digitOf(d.toString()))
    }

    @Test fun keysWithLetters() {
        assertEquals('2', PinPad.digitOf("2 ABC"))
        assertEquals('7', PinPad.digitOf(" 7 PQRS "))
        assertEquals('9', PinPad.digitOf("9WXYZ"))
    }

    @Test fun notKeys() {
        assertNull(PinPad.digitOf("10:42")) // lock-screen clock
        assertNull(PinPad.digitOf("128 messages"))
        assertNull(PinPad.digitOf("Emergency call"))
        assertNull(PinPad.digitOf(""))
    }

    @Test fun enterKey() {
        assertTrue(PinPad.isEnter("OK", ""))
        assertTrue(PinPad.isEnter("Confirmar", ""))
        assertTrue(PinPad.isEnter("", "com.android.systemui:id/key_enter"))
        assertFalse(PinPad.isEnter("5", "com.android.systemui:id/key5"))
    }
}
