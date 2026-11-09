package app.lyrify.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessTest {
    @Test
    fun `setup is complete with All files access alone`() {
        assertTrue(Access(notifications = false, allFiles = true).complete)
        assertTrue(Access(notifications = true, allFiles = true).complete)
    }

    @Test
    fun `without All files access setup stays open, whatever else is on`() {
        assertFalse(Access(notifications = true, allFiles = false).complete)
        assertFalse(Access(notifications = false, allFiles = false).complete)
    }
}
