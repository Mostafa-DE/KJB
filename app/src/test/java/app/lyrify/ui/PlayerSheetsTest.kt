package app.lyrify.ui

import app.lyrify.player.AbPart
import app.lyrify.player.SLEEP_CUSTOM_MINUTES
import app.lyrify.player.SLEEP_CUSTOM_SONGS
import app.lyrify.player.SleepTimer
import app.lyrify.player.Speed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The player's extras as they read: the sleep chip, speeds and semitones, the Custom steppers, the part's chip. */
class PlayerSheetsTest {
    @Test
    fun `the sleep chip shows minutes left, the songs to go, End of song, and the seconds of the fade`() {
        val time = SleepTimer.ByTime(endsAt = 0, minutes = 30)
        assertEquals(SleepShown.Minutes(23), sleepShown(time, leftMs = 22 * 60_000L + 1, playing = true))
        assertEquals(SleepShown.Minutes(1), sleepShown(time, leftMs = 60_000, playing = true))
        assertEquals(SleepShown.Clock(42_000), sleepShown(time, leftMs = 42_000, playing = true))
        // The fade, while playing: seconds, and it says so.
        assertEquals(SleepShown.Clock(8_000, fading = true), sleepShown(time, leftMs = 8_000, playing = true))
        assertEquals(SleepShown.Clock(8_000), sleepShown(time, leftMs = 8_000, playing = false))
        assertEquals(SleepShown.Songs(3), sleepShown(SleepTimer.BySongs(3, 3), leftMs = null, playing = true))
        assertEquals(SleepShown.EndOfSong, sleepShown(SleepTimer.BySongs(3, 1), leftMs = 120_000, playing = true))
        assertEquals(SleepShown.Clock(4_000, fading = true), sleepShown(SleepTimer.BySongs(1, 1), leftMs = 4_000, playing = true))
    }

    @Test
    fun `speeds read as short numbers and semitones with their sign`() {
        assertEquals("1.25", speedNumber(1.25f))
        assertEquals("1", speedNumber(1f))
        assertEquals("0.5", speedNumber(0.5f))
        assertEquals("2", speedNumber(2f))
        assertEquals("1.05", speedNumber(1.05f))
        assertEquals("+2", semitonesText(2))
        assertEquals("−3", semitonesText(-3))
        assertEquals("0", semitonesText(0))
        assertNull(chipPitch(Speed(1.25f)))
        assertNull(chipPitch(Speed(1.25f, keepPitch = false)))
        assertEquals("+2", chipPitch(Speed(1f, keepPitch = false, semitones = 2)))
    }

    @Test
    fun `Custom steps minutes by 1 up to 10 and by 5 above, songs by 1, within their ranges`() {
        assertEquals(10, nextCustom(9, up = true, songs = false))
        assertEquals(15, nextCustom(10, up = true, songs = false))
        assertEquals(9, nextCustom(10, up = false, songs = false))
        assertEquals(20, nextCustom(25, up = false, songs = false))
        assertEquals(SLEEP_CUSTOM_MINUTES.first, nextCustom(1, up = false, songs = false))
        assertEquals(SLEEP_CUSTOM_MINUTES.last, nextCustom(180, up = true, songs = false))
        assertEquals(3, nextCustom(2, up = true, songs = true))
        assertEquals(SLEEP_CUSTOM_SONGS.last, nextCustom(30, up = true, songs = true))
        assertEquals(SLEEP_CUSTOM_SONGS.first, nextCustom(1, up = false, songs = true))
    }

    @Test
    fun `the part's chip and handles show only for the song it belongs to`() {
        val part = AbPart("/storage/emulated/0/Music/Harbour Glass/02 Paper Lantern.flac", 30_000, 40_000)
        assertTrue(partShown(part, "/storage/emulated/0/Music/Harbour Glass/02 Paper Lantern.flac"))
        assertFalse(partShown(part, "/storage/emulated/0/Music/Rips/Loose Thread.mp3"))
        assertFalse(partShown(null, "/storage/emulated/0/Music/Rips/Loose Thread.mp3"))
    }
}
