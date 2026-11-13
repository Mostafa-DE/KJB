package app.lyrify.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Lyrics on the lock screen: when they open, from the notification and by themselves, the switch's permission, and the
 * progress bar that seeks.
 */
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
    fun `over the lock screen it waits for the queues to be read, also after a restart of the app`() {
        assertEquals(LockEntry.WAIT, lockEntry(LockOrigin.NOTIFICATION, locked = true, loaded = null))
        assertEquals(LockEntry.WAIT, lockEntry(LockOrigin.SCREEN_ON, locked = true, loaded = null))
        assertEquals(LockEntry.APP, lockEntry(LockOrigin.NOTIFICATION, locked = false, loaded = null))
        assertEquals(LockEntry.NOTHING, lockEntry(LockOrigin.SCREEN_ON, locked = false, loaded = null))
    }

    @Test
    fun `a tap on the notification opens the player through its route, the lyrics on top, in KJB's own task`() {
        // Unit tests run in the module's folder.
        val service = File("src/main/java/app/lyrify/player/PlaybackService.kt").readText()
        assertTrue(Regex("""PendingIntent\.getActivities\(\s*this,\s*OPEN_PLAYER,\s*arrayOf\(openPlayer\(\), Intent\(this, LockLyricsActivity::class\.java\)\)""").containsMatchIn(service))
        assertTrue(service.contains(".putExtra(EXTRA_DESTINATION, DESTINATION_PLAYER)"))
        // No trampoline: the lock screen lyrics never start another screen.
        val lock = File("src/main/java/app/lyrify/ui/LockLyricsActivity.kt").readText()
        assertFalse(lock.contains("startActivity(Intent(this,"))
        // Its own document task when the screen turning on opens it, removed when it closes; no task of its own otherwise.
        assertTrue(lock.contains("Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK"))
        assertTrue(lock.contains("finishAndRemoveTask()"))
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val activity = Regex("""<activity\s+android:name="\.ui\.LockLyricsActivity"[^>]*>""").find(manifest)!!.value
        assertFalse(activity.contains("taskAffinity"))
        assertFalse(activity.contains("singleInstance"))
        assertTrue(activity.contains("android:showWhenLocked=\"true\""))
    }

    @Test
    fun `Show when locked turns itself off once Display over other apps is taken back`() {
        assertTrue(showWhenLockedNow(setting = true, overlayAllowed = true))
        assertFalse(showWhenLockedNow(setting = true, overlayAllowed = false))
        assertFalse(showWhenLockedNow(setting = false, overlayAllowed = true))
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

    // The progress bar (user feedback 2026-11-13, point 4)

    @Test
    fun `a finger on the bar seeks to its share of the song, within the song`() {
        assertEquals(0.25f, barShare(x = 250f, width = 1_000f))
        assertEquals(0f, barShare(x = -40f, width = 1_000f))
        assertEquals(1f, barShare(x = 1_200f, width = 1_000f))
        assertEquals(0f, barShare(x = 500f, width = 0f))

        assertEquals(60_000L, seekMsAt(0.25f, durationMs = 240_000))
        assertEquals(0L, seekMsAt(0f, 240_000))
        assertEquals(240_000L, seekMsAt(1f, 240_000))
        // TalkBack can ask past either end: the start or the end of the song.
        assertEquals(0L, seekMsAt(-0.5f, 240_000))
        assertEquals(240_000L, seekMsAt(1.5f, 240_000))
        // A recording of three hours lands on its second, a pixel of a 1080 px bar from the middle too.
        assertEquals(5_400_000L, seekMsAt(barShare(540f, 1_080f), 10_800_000))
        assertEquals(5_410_000L, seekMsAt(barShare(541f, 1_080f), 10_800_000))
        // Without a length there is nowhere to seek.
        assertNull(seekMsAt(0.5f, 0))
    }

    @Test
    fun `only a deliberate sideways drag on the bar seeks, never a touch in a pocket or the swipe that closes`() {
        val slop = 24f
        // A touch that stays within the slop, however it wobbles, is no drag.
        assertFalse(lockSeekStarts(dx = 0f, dy = 0f, slop))
        assertFalse(lockSeekStarts(dx = 24f, dy = 0f, slop))
        assertFalse(lockSeekStarts(dx = -20f, dy = 10f, slop))
        // Past the slop and sideways, either way: a seek.
        assertTrue(lockSeekStarts(dx = 25f, dy = 0f, slop))
        assertTrue(lockSeekStarts(dx = -60f, dy = 12f, slop))
        assertTrue(lockSeekStarts(dx = 60f, dy = -30f, slop))
        // Slanted more than twice as far sideways as up or down, or straight up and down: never (the swipe takes it).
        assertFalse(lockSeekStarts(dx = 60f, dy = 31f, slop))
        assertFalse(lockSeekStarts(dx = 30f, dy = 80f, slop))
        assertFalse(lockSeekStarts(dx = 0f, dy = -200f, slop))
    }

    @Test
    fun `the bar shows the time played and the time left, which add up to the song's length`() {
        assertEquals("−3:00", remainingClock(positionMs = 60_000, durationMs = 240_000))
        // 1:00 played of 4:00: 3:00 left, never 2:59.
        assertEquals("−3:00", remainingClock(60_900, 240_000))
        assertEquals("−0:00", remainingClock(240_000, 240_000))
        assertEquals("−1:00:01", remainingClock(1_000, 3_602_000))
        // Never less than nothing left; without a length, nothing to count down.
        assertEquals("−0:00", remainingClock(250_000, 240_000))
        assertEquals("0:00", remainingClock(5_000, 0))
    }

    @Test
    fun `the bar moves on about a pixel at a time while the song plays`() {
        // Four minutes over 1080 px: a pixel every 222 ms.
        assertEquals(222L, lockBarTickMs(durationMs = 240_000, widthPx = 1_080))
        // A short clip: no more often than every frame.
        assertEquals(16L, lockBarTickMs(10_000, 1_080))
        // A long recording, or no length or bar yet: still four times a second, for the time shown.
        assertEquals(250L, lockBarTickMs(3_600_000, 1_080))
        assertEquals(250L, lockBarTickMs(0, 1_080))
        assertEquals(250L, lockBarTickMs(240_000, 0))
    }
}
