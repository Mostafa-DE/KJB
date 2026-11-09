package app.lyrify.ui

import app.lyrify.settings.NavSettings
import app.lyrify.settings.Settings
import app.lyrify.settings.SettingsJson
import org.junit.Assert.assertEquals
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

    // The first-run screen

    @Test
    fun `a new install keeps the setup screen after All files access until Continue, even with both grants`() {
        val none = Access(notifications = false, allFiles = false)
        assertTrue(setupShows(none, setupDone = false, was = null))
        assertTrue(setupShows(Access(notifications = false, allFiles = true), setupDone = false, was = true))
        assertTrue(setupShows(Access(notifications = true, allFiles = true), setupDone = false, was = true))
        // Continue needs All files access.
        assertFalse(none.complete)
    }

    @Test
    fun `a phone updated from 2_0 with both grants skips setup, and so does one that finished it`() {
        assertFalse(setupShows(Access(notifications = true, allFiles = true), setupDone = false, was = null))
        assertFalse(setupShows(Access(notifications = false, allFiles = true), setupDone = true, was = null))
        assertFalse(setupShows(Access(notifications = false, allFiles = true), setupDone = true, was = false))
    }

    @Test
    fun `without All files access setup comes back, however it ended`() {
        assertTrue(setupShows(Access(notifications = true, allFiles = false), setupDone = true, was = null))
        assertTrue(setupShows(Access(notifications = true, allFiles = false), setupDone = true, was = false))
    }

    @Test
    fun `whether setup was finished is kept in settings, and older settings read as not finished`() {
        val done = Settings(nav = NavSettings(setupDone = true))
        assertEquals(done, SettingsJson.decode(SettingsJson.encode(done)))
        assertFalse(SettingsJson.decode("""{"version":1,"nav":{"lastTab":"TOOLS"}}""")!!.nav.setupDone)
    }
}
