package app.lyrify.audio

import androidx.media3.common.C
import app.lyrify.library.song
import app.lyrify.player.AbPart
import app.lyrify.player.QueueBook
import app.lyrify.player.QueueSource
import app.lyrify.player.Repeat
import app.lyrify.player.SleepTimer
import app.lyrify.player.SourceKind
import app.lyrify.player.sleepAfter
import app.lyrify.player.sleepFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Crossfade's rules as pure functions: the equal-power curves, when a fade is planned, and which changes never fade. */
class CrossfadeTest {
    // The curves

    @Test
    fun `equal power - the squares of the two gains add up to 1 all through a fade`() {
        for (step in 0..100) {
            val share = step / 100f
            val out = fadeOutGain(share)
            val incoming = fadeInGain(share)
            assertEquals("at $share", 1f, out * out + incoming * incoming, 0.0005f)
        }
        assertEquals(1f, fadeOutGain(0f), 0f)
        assertEquals(0f, fadeInGain(0f), 0f)
        assertEquals(0f, fadeOutGain(1f), 1e-6f)
        assertEquals(1f, fadeInGain(1f), 1e-6f)
        // Half way both are at -3 dB, not -6 dB as a straight line would give: no dip in loudness.
        assertEquals(0.7071f, fadeInGain(0.5f), 0.0005f)
        assertEquals(0.7071f, fadeOutGain(0.5f), 0.0005f)
        // Shares outside the fade are held at its ends.
        assertEquals(1f, fadeInGain(2f), 0f)
        assertEquals(1f, fadeOutGain(-1f), 0f)
        // Each only rises or only falls.
        val ups = (0..20).map { fadeInGain(it / 20f) }
        assertEquals(ups.sorted(), ups)
    }

    @Test
    fun `a ramp follows the curve over the time it plays, and a ramp started part way goes on from its gain`() {
        val up = Ramp(up = true, lengthMs = 6_000)
        assertEquals(0f, up.gain, 0f)
        assertEquals(fadeInGain(0.5f), up.played(3_000).gain, 1e-5f)
        assertFalse(up.played(5_999).done)
        assertTrue(up.played(6_000).done)
        assertEquals(1f, up.played(9_000).gain, 0f)
        val down = Ramp(up = false, lengthMs = 6_000)
        assertEquals(1f, down.gain, 0f)
        assertEquals(fadeOutGain(0.25f), down.played(1_000).played(500).gain, 1e-5f)
        assertEquals(0f, down.played(6_000).gain, 1e-6f)
        // A fade shortened part way (a seek, a skip) starts from where it stood, never jumping.
        val shortDown = Ramp(up = false, lengthMs = SHORT_FADE_MS, fromGain = 0.8f)
        assertEquals(0.8f, shortDown.gain, 1e-5f)
        assertTrue(shortDown.played(150).gain in 0.01f..0.79f)
        assertEquals(0f, shortDown.played(SHORT_FADE_MS).gain, 1e-6f)
        val shortUp = Ramp(up = true, lengthMs = SHORT_FADE_MS, fromGain = 0.5f)
        assertEquals(0.5f, shortUp.gain, 1e-5f)
        assertTrue(shortUp.played(150).gain in 0.51f..0.99f)
        assertEquals(1f, shortUp.played(SHORT_FADE_MS).gain, 1e-6f)
    }

    // When a fade is planned

    @Test
    fun `a natural fade lasts the chosen seconds, at most half of either song`() {
        assertEquals(0L, naturalFadeMs(0, 200_000, 200_000))
        assertEquals(6_000L, naturalFadeMs(6_000, 200_000, 200_000))
        assertEquals(12_000L, naturalFadeMs(12_000, 200_000, null))
        assertEquals(4_000L, naturalFadeMs(12_000, 8_000, 200_000))
        assertEquals(5_000L, naturalFadeMs(12_000, 200_000, 10_000))
        // A song of unknown length does not fade.
        assertEquals(0L, naturalFadeMs(6_000, C.TIME_UNSET, 200_000))
    }

    @Test
    fun `the next song is loaded 5 s before its fade, and the fade starts once it is ready`() {
        // Far from the end: look again at the load point, at least once a second.
        assertEquals(FadeStep(prepare = false, start = false, nextMs = LOOK_EVERY_MS), fadeStep(leftMs = 60_000, fadeMs = 6_000, ready = false))
        assertEquals(FadeStep(prepare = false, start = false, nextMs = 400), fadeStep(leftMs = 11_400, fadeMs = 6_000, ready = false))
        // Within the lead: load it, and look again at the fade's start.
        assertEquals(FadeStep(prepare = true, start = false, nextMs = LOOK_EVERY_MS), fadeStep(leftMs = 11_000, fadeMs = 6_000, ready = false))
        assertEquals(FadeStep(prepare = true, start = false, nextMs = 300), fadeStep(leftMs = 6_300, fadeMs = 6_000, ready = true))
        // At the fade: start once the next song is ready; until then look again soon.
        assertEquals(FadeStep(prepare = true, start = true, nextMs = FADE_STEP_MS), fadeStep(leftMs = 6_000, fadeMs = 6_000, ready = true))
        assertEquals(FadeStep(prepare = true, start = false, nextMs = FADE_STEP_MS), fadeStep(leftMs = 5_000, fadeMs = 6_000, ready = false))
        // Too late to fade: the song plays into the next one gapless.
        assertEquals(FadeStep(prepare = false, start = false, nextMs = LOOK_EVERY_MS), fadeStep(leftMs = 20, fadeMs = 6_000, ready = true))
    }

    @Test
    fun `the song after is the next one, the first again with Repeat all, and none with Repeat one or at the end`() {
        assertEquals(1, nextIndexFor(0, 4, Repeat.OFF))
        assertNull(nextIndexFor(3, 4, Repeat.OFF))
        assertEquals(0, nextIndexFor(3, 4, Repeat.ALL))
        assertEquals(2, nextIndexFor(1, 4, Repeat.ALL))
        assertNull(nextIndexFor(1, 4, Repeat.ONE))
        // One song with Repeat all is Repeat one.
        assertNull(nextIndexFor(0, 1, Repeat.ALL))
        assertNull(nextIndexFor(-1, 0, Repeat.OFF))
    }

    // Changes that never fade

    private val album = (1..4).map { song("Music/Harbour Glass/%02d Paper Lantern %d.flac".format(it, it), album = "Harbour Glass", track = it) }
    private val albumSource = QueueSource(SourceKind.ALBUM, "album:harbour", "Harbour Glass")
    private val listSource = QueueSource(SourceKind.PLAYLIST, "playlist:road", "Road trip")

    @Test
    fun `Keep albums gapless - songs of one album in track order never fade into each other`() {
        val book = QueueBook().play(album.map { it.ref }, 0, albumSource, now = 1).book
        val queue = book.current!!
        val (a, b, c) = queue.items.map { it.uid }
        assertFalse(mayCrossfade(queue, a, b, keepAlbumsGapless = true, part = null, sleep = null))
        assertFalse(mayCrossfade(queue, b, c, keepAlbumsGapless = true, part = null, sleep = null))
        // The switch off: they fade like any songs.
        assertTrue(mayCrossfade(queue, a, b, keepAlbumsGapless = false, part = null, sleep = null))
        // The same songs as a playlist, or the album shuffled, are not an album in order.
        val list = QueueBook().play(album.map { it.ref }, 0, listSource, now = 1).book.current!!
        assertTrue(mayCrossfade(list, list.items[0].uid, list.items[1].uid, keepAlbumsGapless = true, part = null, sleep = null))
        val shuffled = book.toggleShuffle(Random(7)).current!!
        assertTrue(mayCrossfade(shuffled, shuffled.items[0].uid, shuffled.items[1].uid, keepAlbumsGapless = true, part = null, sleep = null))
        // A Play next song breaks the order: it fades in, and the album fades back in after it.
        val extra = song("Music/Rips/Loose Thread.mp3", title = "Loose Thread")
        val withNext = book.playNext(listOf(extra.ref), listSource, now = 2)!!.book.current!!
        val (first, inserted, second) = withNext.items.map { it.uid }
        assertTrue(mayCrossfade(withNext, first, inserted, keepAlbumsGapless = true, part = null, sleep = null))
        assertTrue(mayCrossfade(withNext, inserted, second, keepAlbumsGapless = true, part = null, sleep = null))
    }

    @Test
    fun `no fade out of a song with a part repeating, nor past the sleep timer's last song`() {
        val queue = QueueBook().play(album.map { it.ref }, 0, listSource, now = 1).book.current!!
        val (a, b) = queue.items.map { it.uid }
        val loop = AbPart(album[0].path, 40_000, 55_000)
        assertFalse(mayCrossfade(queue, a, b, keepAlbumsGapless = true, part = loop, sleep = null))
        // A part on another song changes nothing here.
        assertTrue(mayCrossfade(queue, a, b, keepAlbumsGapless = true, part = loop.copy(path = album[2].path), sleep = null))
        // The sleep timer stopping after this song: the song ends as it would, then the music stops.
        assertFalse(mayCrossfade(queue, a, b, keepAlbumsGapless = true, part = null, sleep = sleepAfter(1)))
        assertFalse(mayCrossfade(queue, a, b, keepAlbumsGapless = true, part = null, sleep = SleepTimer.BySongs(songs = 3, left = 1)))
        assertTrue(mayCrossfade(queue, a, b, keepAlbumsGapless = true, part = null, sleep = sleepAfter(2)))
        // By time, its own fade runs over both songs.
        assertTrue(mayCrossfade(queue, a, b, keepAlbumsGapless = true, part = null, sleep = sleepFor(15, now = 0)))
        // A song the queue no longer holds.
        assertTrue(mayCrossfade(queue, a, 9_999, keepAlbumsGapless = true, part = null, sleep = null))
        assertFalse(mayCrossfade(null, a, b, keepAlbumsGapless = true, part = null, sleep = null))
    }
}
