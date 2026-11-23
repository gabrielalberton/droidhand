package work.alberton.droidhand.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatesTest {
    @Test fun comparesVersions() {
        assertTrue(Updates.isNewer("1.1.0", "1.0.0"))
        assertTrue(Updates.isNewer("v1.10.0", "1.9.2"))
        assertTrue(Updates.isNewer("2.0", "1.9.9"))
        assertFalse(Updates.isNewer("1.0.0", "1.0.0"))
        assertFalse(Updates.isNewer("1.0.0", "1.1.0"))
        assertFalse(Updates.isNewer("1.1.0-rc1", "1.1.0"))
    }
}
