package app.lyrify.stats

import app.lyrify.library.SongRef
import app.lyrify.settings.PlaybackSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Count a play after" and the time heard, from the player's raw events: what is heard, when a play counts, and what
 * the log gets. Made-up songs only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayRulesTest {
    private val lantern = SongRef("/storage/emulated/0/Music/KJB test/Weathervane Hum.mp3", mediaStoreId = 7)
    private val kettle = SongRef("/storage/emulated/0/Music/KJB test/Copper Kettle Waltz.flac")
    private val store = FakePlayLog()
    private val logs = mutableListOf<String>()
    private var share = 50
    private val clock = Clock()

    private class Clock(var now: Long = T0)

    private fun TestScope.counter(): PlayCounter = PlayCounter(store, { share }, backgroundScope, log = { logs += it })

    /** One play of [song] on the counter: started and playing at 0, then driven like the player drives it. */
    private inner class Lap(private val counter: PlayEvents, val id: Long, val song: SongRef = lantern, durationMs: Long = SONG_MS) {
        var position: Long = 0
            private set
        private var playing = true

        init {
            counter.onEvent(PlayEvent.Started(id, song, clock.now, durationMs))
            counter.onEvent(PlayEvent.Position(id, song, clock.now, 0, playing = true))
        }

        /** Plays for [ms] (paused: only time passes), a position every 5 s, as the player's ticker sends them. */
        fun play(ms: Long) {
            var left = ms
            while (left > 0) {
                val step = minOf(TICK_MS, left)
                clock.now += step
                if (playing) position += step
                left -= step
                counter.onEvent(PlayEvent.Position(id, song, clock.now, position, playing))
            }
        }

        fun pause() {
            playing = false
            counter.onEvent(PlayEvent.Position(id, song, clock.now, position, playing = false))
        }

        fun resume() {
            playing = true
            counter.onEvent(PlayEvent.Position(id, song, clock.now, position, playing = true))
        }

        fun seek(toMs: Long) {
            counter.onEvent(PlayEvent.Seeked(id, song, clock.now, position, toMs))
            position = toMs
        }

        fun end(atMs: Long = position) = counter.onEvent(PlayEvent.Ended(id, song, clock.now, atMs))
    }

    // The rule

    @Test
    fun `each choice from 10 to 100 percent counts at its share of the song and not a millisecond before`() {
        val needed = mapOf(
            10 to 20_000L, 20 to 40_000L, 30 to 60_000L, 40 to 80_000L, 50 to 100_000L,
            60 to 120_000L, 70 to 140_000L, 80 to 160_000L, 90 to 180_000L,
            // The player's last position can fall a little short of the end: 100% is the end, give or take a second.
            100 to 199_000L,
        )
        assertEquals(PlaybackSettings.PLAY_SHARES, needed.keys.toList())
        needed.forEach { (share, ms) ->
            assertTrue("$share%", PlayRules.counts(SONG_MS, ms, share))
            assertFalse("$share%", PlayRules.counts(SONG_MS, ms - 1, share))
        }
    }

    @Test
    fun `a song under 30 s never counts, whatever the choice`() {
        PlaybackSettings.PLAY_SHARES.forEach { share ->
            assertFalse("$share%", PlayRules.counts(29_999, 29_999, share))
            assertTrue("$share%", PlayRules.counts(30_000, 30_000, share))
        }
        assertFalse("no length known", PlayRules.counts(0, 10_000, 10))
    }

    @Test
    fun `each choice counts a play through the counter once that share was heard`() = runTest {
        val counter = counter()
        PlaybackSettings.PLAY_SHARES.forEachIndexed { i, choice ->
            share = choice
            val short = Lap(counter, id = 2L * i + 1)
            short.play(SONG_MS * choice / 100 - 5_000)
            short.end()
            val enough = Lap(counter, id = 2L * i + 2)
            enough.play(minOf(SONG_MS * choice / 100, SONG_MS))
            enough.end()
        }
        runCurrent()

        // Each choice: a play 5 s short of its share, then one that reached it.
        assertEquals(PlaybackSettings.PLAY_SHARES.flatMap { listOf(false, true) }, store.plays.sortedBy { it.startedAt }.map { it.counted })
    }

    @Test
    fun `a short clip played to the end is heard but never counts`() = runTest {
        val counter = counter()
        share = 10
        Lap(counter, id = 1, durationMs = 20_000).apply { play(20_000); end() }
        runCurrent()

        assertEquals(listOf(LoggedPlay(lantern, T0, 20_000, counted = false)), store.plays)
    }

    // Heard time

    @Test
    fun `seeking forward is not hearing`() = runTest {
        val counter = counter()
        Lap(counter, id = 1).apply {
            play(20_000)
            seek(150_000)
            play(50_000)
            end()
        }
        runCurrent()

        assertEquals(listOf(LoggedPlay(lantern, T0, 70_000, counted = false)), store.plays)
    }

    @Test
    fun `hearing a part again after seeking back adds listening time but no share, so it never counts twice`() = runTest {
        val counter = counter()
        Lap(counter, id = 1).apply {
            repeat(3) {
                play(60_000)
                seek(0)
            }
            end()
        }
        runCurrent()

        assertEquals(listOf(LoggedPlay(lantern, T0, 180_000, counted = false)), store.plays)
    }

    @Test
    fun `seeking back after the share was heard keeps one play`() = runTest {
        val counter = counter()
        Lap(counter, id = 1).apply {
            play(120_000)
            seek(0)
            play(SONG_MS)
            end()
        }
        runCurrent()

        assertEquals(listOf(LoggedPlay(lantern, T0, 320_000, counted = true)), store.plays)
    }

    @Test
    fun `paused time is not heard`() = runTest {
        val counter = counter()
        Lap(counter, id = 1).apply {
            play(40_000)
            pause()
            play(600_000)
            resume()
            play(50_000)
            end()
        }
        runCurrent()

        assertEquals(listOf(LoggedPlay(lantern, T0, 90_000, counted = false)), store.plays)
    }

    @Test
    fun `a jump the player did not report is not heard, only the time it took`() {
        val heard = HeardTime(SONG_MS)
        heard.position(0, playing = true, at = T0)
        heard.position(150_000, playing = true, at = T0 + 10_000)
        assertEquals(10_000, heard.heardMs)

        // At double speed, 20 s of the song in 10 s is plain playing.
        heard.position(170_000, playing = true, at = T0 + 20_000)
        assertEquals(30_000, heard.heardMs)

        // Going back without a seek is not heard either.
        heard.position(10_000, playing = true, at = T0 + 25_000)
        assertEquals(30_000, heard.heardMs)
    }

    @Test
    fun `the end of the song counts as heard to its length`() {
        val heard = HeardTime(SONG_MS)
        heard.position(0, playing = true, at = T0)
        heard.position(195_000, playing = true, at = T0 + 195_000)
        heard.ended(SONG_MS, at = T0 + 200_000)

        assertEquals(SONG_MS, heard.heardMs)
        assertEquals(SONG_MS, heard.coveredMs)
    }

    // Plays

    @Test
    fun `a song counts once per play, as soon as its share is heard`() = runTest {
        val counter = counter()
        share = 10
        val lap = Lap(counter, id = 1)
        lap.play(25_000)
        runCurrent()
        // Written when its share was heard, at 20 s, so Recently played has it at once.
        assertEquals(listOf(LoggedPlay(lantern, T0, 20_000, counted = true)), store.plays)

        lap.play(175_000)
        lap.end()
        runCurrent()
        assertEquals(listOf(LoggedPlay(lantern, T0, SONG_MS, counted = true)), store.plays)
        assertEquals(2, store.puts)
    }

    @Test
    fun `each lap of Repeat one is a play of its own`() = runTest {
        val counter = counter()
        repeat(3) { lap ->
            Lap(counter, id = lap + 1L).apply {
                play(SONG_MS)
                end()
            }
        }
        Lap(counter, id = 4).apply {
            play(40_000)
            end()
        }
        runCurrent()

        assertEquals(listOf(true, true, true, false), store.plays.sortedBy { it.startedAt }.map { it.counted })
        assertEquals(listOf(T0, T0 + SONG_MS, T0 + 2 * SONG_MS, T0 + 3 * SONG_MS), store.plays.map { it.startedAt }.sorted())
    }

    @Test
    fun `changing the setting mid-song applies from then on, and a counted play stays counted`() = runTest {
        val counter = counter()
        val lap = Lap(counter, id = 1)
        lap.play(80_000)
        runCurrent()
        assertEquals(emptyList<LoggedPlay>(), store.plays)

        share = 30
        lap.play(5_000)
        runCurrent()
        assertEquals(listOf(true), store.plays.map { it.counted })

        share = 100
        lap.play(5_000)
        lap.end()
        runCurrent()
        assertEquals(listOf(LoggedPlay(lantern, T0, 90_000, counted = true)), store.plays)
    }

    @Test
    fun `a play skipped before a second was heard leaves nothing in the log`() = runTest {
        val counter = counter()
        Lap(counter, id = 1).apply {
            play(900)
            end()
        }
        runCurrent()

        assertEquals(emptyList<LoggedPlay>(), store.plays)
    }

    @Test
    fun `events of a play that never started or already ended are ignored`() = runTest {
        val counter = counter()
        counter.onEvent(PlayEvent.Position(9, kettle, T0, 0, playing = true))
        counter.onEvent(PlayEvent.Ended(9, kettle, T0 + SONG_MS, SONG_MS))
        val lap = Lap(counter, id = 1)
        lap.play(10_000)
        lap.end()
        lap.play(SONG_MS)
        lap.end()
        runCurrent()

        assertEquals(listOf(LoggedPlay(lantern, T0, 10_000, counted = false)), store.plays)
    }

    @Test
    fun `two plays at once are kept apart`() = runTest {
        val counter = counter()
        val fading = Lap(counter, id = 1)
        fading.play(30_000)
        val next = Lap(counter, id = 2, song = kettle)
        next.play(5_000)
        fading.play(5_000)
        fading.end()
        next.play(5_000)
        next.end()
        runCurrent()

        assertEquals(listOf(LoggedPlay(lantern, T0, 35_000, counted = false), LoggedPlay(kettle, T0 + 30_000, 10_000, counted = false)), store.plays)
    }

    @Test
    fun `a play that never ended is closed with what it heard once two newer ones started`() = runTest {
        val counter = counter()
        Lap(counter, id = 1).play(30_000)
        Lap(counter, id = 2, song = kettle).play(5_000)
        runCurrent()
        assertEquals(emptyList<LoggedPlay>(), store.plays)

        Lap(counter, id = 3)
        runCurrent()
        assertEquals(listOf(LoggedPlay(lantern, T0, 30_000, counted = false)), store.plays)
    }

    @Test
    fun `a write that fails is logged without the song, and counting goes on`() = runTest {
        val counter = counter()
        store.failing = true
        Lap(counter, id = 1).apply {
            play(SONG_MS)
            end()
        }
        runCurrent()
        assertEquals(0, counter.written.value)
        assertTrue(logs.isNotEmpty())
        assertTrue(logs.none { "Weathervane" in it || "KJB test" in it || "/storage" in it })

        store.failing = false
        Lap(counter, id = 2).apply {
            play(SONG_MS)
            end()
        }
        runCurrent()
        assertEquals(1, store.plays.size)
        assertEquals(2, counter.written.value)
    }

    private companion object {
        const val T0: Long = 1_800_000_000_000L
        const val SONG_MS: Long = 200_000
        const val TICK_MS: Long = 5_000
    }
}
