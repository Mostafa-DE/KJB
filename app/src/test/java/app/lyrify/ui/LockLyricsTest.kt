package app.lyrify.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lyrics on the lock screen: when they open, from the notification and by themselves, and the switch's permission. */
class LockLyricsTest {
    @Test
    fun `a tap on the music notification shows the lyrics over the lock screen, and the app once unlocked`() {
        assertEquals(LockEntry.LYRICS, lockEntry(LockOrigin.NOTIFICATION, locked = true, loaded = true))
        assertEquals(LockEntry.APP, lockEntry(LockOrigin.NOTIFICATION, locked = false, loaded = true))
        assertEquals(LockEntry.APP, lockEntry(LockOrigin.NOTIFICATION, locked = false, loaded = false))
        // Never when nothing plays: the lock screen stays as it is.
        assertEquals(LockEntry.NOTHING, lockEntry(LockOrigin.NOTIFICATION, locked = true, loaded = false))
    }

    @Test
    fun `opened by the screen turning on, it shows over the lock screen only, and never opens the app`() {
        assertEquals(LockEntry.LYRICS, lockEntry(LockOrigin.SCREEN_ON, locked = true, loaded = true))
        assertEquals(LockEntry.NOTHING, lockEntry(LockOrigin.SCREEN_ON, locked = false, loaded = true))
        assertEquals(LockEntry.NOTHING, lockEntry(LockOrigin.SCREEN_ON, locked = true, loaded = false))
    }

    @Test
    fun `Show when locked opens the lyrics on screen-on only while KJB plays, the switch on and Display over other apps allowed`() {
        assertTrue(opensOnScreenOn(setting = true, overlayAllowed = true, playing = true, locked = true))
        assertFalse(opensOnScreenOn(setting = false, overlayAllowed = true, playing = true, locked = true))
        assertFalse(opensOnScreenOn(setting = true, overlayAllowed = false, playing = true, locked = true))
        assertFalse(opensOnScreenOn(setting = true, overlayAllowed = true, playing = false, locked = true))
        assertFalse(opensOnScreenOn(setting = true, overlayAllowed = true, playing = true, locked = false))
    }

    @Test
    fun `the switch asks for Display over other apps only when it is turned on without it`() {
        assertEquals(LockSwitch.ASK, lockSwitch(on = true, overlayAllowed = false))
        assertEquals(LockSwitch.STORE_ON, lockSwitch(on = true, overlayAllowed = true))
        assertEquals(LockSwitch.STORE_OFF, lockSwitch(on = false, overlayAllowed = false))
        assertEquals(LockSwitch.STORE_OFF, lockSwitch(on = false, overlayAllowed = true))
    }

    @Test
    fun `the lock screen shows the line before the one playing, it, and the next three`() {
        assertEquals(0..4, lockWindow(size = 20, current = -1))
        assertEquals(0..4, lockWindow(size = 20, current = 0))
        assertEquals(4..8, lockWindow(size = 20, current = 5))
        assertEquals(18..19, lockWindow(size = 20, current = 19))
        assertEquals(0..1, lockWindow(size = 2, current = 1))
        assertTrue(lockWindow(size = 0, current = -1).isEmpty())
    }
}
