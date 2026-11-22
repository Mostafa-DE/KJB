package app.lyrify.stats

import app.lyrify.library.SongRef
import app.lyrify.library.song
import app.lyrify.player.SourceKind
import app.lyrify.settings.PlaybackSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

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
    /** "Count a play after" as the settings give it; null until they are read. */
    private val share = MutableStateFlow<Int?>(50)
    private val clock = Clock()

    private class Clock(var now: Long = T0)

    private fun TestScope.counter(zone: ZoneId = AMMAN): PlayCounter = PlayCounter(store, share, backgroundScope, log = { logs += it }, zone = { zone })

    /** One play of [song] on the counter: started and playing at [from], then driven like the player drives it. */
    private inner class Lap(private val counter: PlayEvents, val id: Long, val song: SongRef = lantern, durationMs: Long = SONG_MS, from: Long = 0, start: PlayStart? = null) {
        var position: Long = from
            private set
        private var playing = true

        init {
            counter.onEvent(PlayEvent.Started(id, song, clock.now, durationMs, start))
            counter.onEvent(PlayEvent.Position(id, song, clock.now, from, playing = true))
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

        fun end(atMs: Long = position, how: PlayEnd = PlayEnd.UNKNOWN) = counter.onEvent(PlayEvent.Ended(id, song, clock.now, atMs, how))
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
            // The plays before are counted under their own choice.
            runCurrent()
            share.value = choice
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
        share.value = 10
        val counter = counter()
        Lap(counter, id = 1, durationMs = 20_000).apply { play(20_000); end() }
        runCurrent()

        assertEquals(listOf(LoggedPlay(lantern, T0, 20_000, counted = false)), store.plays.as310())
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

        assertEquals(listOf(LoggedPlay(lantern, T0, 70_000, counted = false)), store.plays.as310())
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

        assertEquals(listOf(LoggedPlay(lantern, T0, 180_000, counted = false)), store.plays.as310())
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

        assertEquals(listOf(LoggedPlay(lantern, T0, 320_000, counted = true)), store.plays.as310())
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

        assertEquals(listOf(LoggedPlay(lantern, T0, 90_000, counted = false)), store.plays.as310())
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
        share.value = 10
        val counter = counter()
        val lap = Lap(counter, id = 1)
        lap.play(25_000)
        runCurrent()
        // Written when its share was heard, at 20 s, so Recently played has it at once.
        assertEquals(listOf(LoggedPlay(lantern, T0, 20_000, counted = true)), store.plays.as310())

        lap.play(175_000)
        lap.end()
        runCurrent()
        assertEquals(listOf(LoggedPlay(lantern, T0, SONG_MS, counted = true)), store.plays.as310())
        assertEquals(emptyList<StoredPlay>(), store.openPlays())
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
    fun `changing the setting mid-song applies from the play's next event, and a counted play stays counted`() = runTest {
        val counter = counter()
        val lap = Lap(counter, id = 1)
        lap.play(80_000)
        runCurrent()
        assertEquals(listOf(false), store.plays.map { it.counted })

        share.value = 30
        lap.play(5_000)
        runCurrent()
        assertEquals(listOf(true), store.plays.map { it.counted })

        share.value = 100
        lap.play(5_000)
        lap.end()
        runCurrent()
        assertEquals(listOf(LoggedPlay(lantern, T0, 90_000, counted = true)), store.plays.as310())
    }

    @Test
    fun `nothing counts against the default before the setting is read, then the setting read applies`() = runTest {
        share.value = null
        val counter = counter()
        Lap(counter, id = 1).play(150_000)
        runCurrent()
        assertEquals(emptyList<LoggedPlay>(), store.plays.as310())

        // Read as 100%: 150 s of 200 is no play yet, where the default 50% would have counted it.
        share.value = 100
        runCurrent()
        assertEquals(listOf(LoggedPlay(lantern, T0, 150_000, counted = false)), store.plays.as310())
    }

    @Test
    fun `the same play started twice is still one play, and its count is never taken back`() = runTest {
        val counter = counter()
        val lap = Lap(counter, id = 1)
        lap.play(120_000)
        counter.onEvent(PlayEvent.Started(1, lantern, clock.now, SONG_MS))
        lap.play(10_000)
        lap.end()
        runCurrent()

        assertEquals(listOf(LoggedPlay(lantern, T0, 130_000, counted = true)), store.plays.as310())
    }

    @Test
    fun `a play skipped before a second was heard is kept once, at its end, as a play that did not count`() = runTest {
        // Changed on purpose (LG1, the better listening log): such a skip used to leave nothing, so skips were invisible.
        val counter = counter()
        Lap(counter, id = 1).apply {
            play(900)
            runCurrent()
            assertEquals(emptyList<LoggedPlay>(), store.plays)
            end(how = PlayEnd.NEXT)
        }
        runCurrent()

        assertEquals(listOf(LoggedPlay(lantern, T0, 900, counted = false, zoneOffsetMinutes = 180, end = PlayEnded(PlayEnd.NEXT, 900))), store.plays)
        assertTrue(store.plays.single().isShort)
        assertEquals(1, counter.written.value)
        assertEquals(emptyList<StoredPlay>(), store.openPlays())
    }

    @Test
    fun `a play that never sounded leaves nothing, however it ended`() = runTest {
        val counter = counter()
        PlayEnd.entries.forEachIndexed { i, how ->
            val id = i + 1L
            counter.onEvent(PlayEvent.Started(id, lantern, clock.now, SONG_MS))
            counter.onEvent(PlayEvent.Position(id, lantern, clock.now, 0, playing = false))
            clock.now += 3_000
            counter.onEvent(PlayEvent.Ended(id, lantern, clock.now, 0, how))
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

        assertEquals(listOf(LoggedPlay(lantern, T0, 10_000, counted = false)), store.plays.as310())
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

        assertEquals(listOf(LoggedPlay(lantern, T0, 35_000, counted = false), LoggedPlay(kettle, T0 + 30_000, 10_000, counted = false)), store.plays.sortedBy { it.startedAt }.as310())
    }

    @Test
    fun `a play that never ended is closed with what it heard once two newer ones started`() = runTest {
        val counter = counter()
        Lap(counter, id = 1).play(30_000)
        Lap(counter, id = 2, song = kettle).play(5_000)
        runCurrent()
        assertEquals(listOf(lantern), store.openPlays().map { it.play.song })

        Lap(counter, id = 3)
        runCurrent()
        assertEquals(listOf(LoggedPlay(lantern, T0, 30_000, counted = false)), store.plays.as310())
        assertEquals(emptyList<StoredPlay>(), store.openPlays())
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
        assertEquals(listOf(LoggedPlay(lantern, T0 + SONG_MS, SONG_MS, counted = true)), store.plays.as310())
        assertTrue(counter.written.value > 0)
    }

    // The app stopping mid-play

    @Test
    fun `while it plays, a play is kept every 15 s and on pause, and let go when it ends`() = runTest {
        val counter = counter()
        val lap = Lap(counter, id = 1)
        lap.play(10_000)
        runCurrent()
        assertEquals(emptyList<StoredPlay>(), store.openPlays())

        lap.play(5_000)
        runCurrent()
        assertEquals(listOf(StoredPlay(LoggedPlay(lantern, T0, 15_000, counted = false), OpenState(SONG_MS, 15_000, listOf(HeardSpan(0, 15_000))))), store.openPlays().as310())

        lap.play(5_000)
        lap.pause()
        runCurrent()
        assertEquals(OpenState(SONG_MS, 20_000, listOf(HeardSpan(0, 20_000))), store.openPlays().single().state)

        lap.end()
        runCurrent()
        assertEquals(emptyList<StoredPlay>(), store.openPlays())
        assertEquals(listOf(LoggedPlay(lantern, T0, 20_000, counted = false)), store.plays.as310())
    }

    @Test
    fun `a play counted before the app stopped is not counted again when it goes on`() = runTest {
        Lap(counter(), id = 1).apply {
            play(120_000)
            pause()
        }
        runCurrent()
        assertEquals(listOf(LoggedPlay(lantern, T0, 120_000, counted = true)), store.plays.as310())

        // The app starts again; the player reopens the song where it was and plays on.
        val again = counter()
        Lap(again, id = 1, from = 120_000).apply {
            play(80_000)
            end()
        }
        runCurrent()

        assertEquals(listOf(LoggedPlay(lantern, T0, SONG_MS, counted = true)), store.plays.as310())
        assertEquals(emptyList<StoredPlay>(), store.openPlays())
    }

    @Test
    fun `a play cut short by the app stopping counts once the rest of its share is heard`() = runTest {
        Lap(counter(), id = 1).apply {
            play(80_000)
            pause()
        }
        runCurrent()
        assertEquals(listOf(LoggedPlay(lantern, T0, 80_000, counted = false)), store.plays.as310())

        // The player kept its place a few seconds earlier than the counter did: still the same play.
        Lap(counter(), id = 2, from = 75_000).play(25_000)
        runCurrent()
        assertEquals(listOf(true), store.plays.map { it.counted })
        assertEquals(listOf(T0), store.plays.map { it.startedAt })
    }

    @Test
    fun `after the app stopped, another song closes the kept play as it stood`() = runTest {
        Lap(counter(), id = 1).apply {
            play(40_000)
            pause()
        }
        runCurrent()

        Lap(counter(), id = 2, song = kettle).apply {
            play(10_000)
            end()
        }
        runCurrent()

        assertEquals(listOf(LoggedPlay(lantern, T0, 40_000, counted = false), LoggedPlay(kettle, T0 + 40_000, 10_000, counted = false)), store.plays.sortedBy { it.startedAt }.as310())
        assertEquals(emptyList<StoredPlay>(), store.openPlays())
    }

    @Test
    fun `after the app stopped, the same song from elsewhere is a new play`() = runTest {
        Lap(counter(), id = 1).apply {
            play(120_000)
            pause()
        }
        runCurrent()

        Lap(counter(), id = 2).apply {
            play(SONG_MS)
            end()
        }
        runCurrent()

        assertEquals(listOf(LoggedPlay(lantern, T0, 120_000, counted = true), LoggedPlay(lantern, T0 + 120_000, SONG_MS, counted = true)), store.plays.sortedBy { it.startedAt }.as310())
        assertEquals(emptyList<StoredPlay>(), store.openPlays())
    }

    // How a play began and ended (LG1, the better listening log)

    private val begun = PlayStart(StartedBy.PICKED, SourceKind.ALBUM, shuffled = false, songKey = "weathervane hum|kjb test band|harbour glass|100")

    @Test
    fun `a play keeps how it began, from which list, and how far the phone's clock was from UTC then`() = runTest {
        Lap(counter(), id = 1, start = begun).apply {
            play(SONG_MS)
            end(how = PlayEnd.FINISHED)
        }
        Lap(counter(ZoneId.of("America/New_York")), id = 2, song = kettle).apply {
            play(40_000)
            end(how = PlayEnd.NEXT)
        }
        runCurrent()

        assertEquals(
            listOf(
                LoggedPlay(lantern, T0, SONG_MS, counted = true, start = begun, zoneOffsetMinutes = 180, end = PlayEnded(PlayEnd.FINISHED, SONG_MS)),
                // January in New York; a play the player said nothing of how it began keeps none.
                LoggedPlay(kettle, T0 + SONG_MS, 40_000, counted = false, zoneOffsetMinutes = -300, end = PlayEnded(PlayEnd.NEXT, 40_000)),
            ),
            store.plays.sortedBy { it.startedAt },
        )
    }

    @Test
    fun `each way a play ends is kept, with where the song was left`() = runTest {
        val counter = counter()
        PlayEnd.entries.forEachIndexed { i, how ->
            Lap(counter, id = i + 1L, song = kettle).apply {
                play(40_000)
                end(how = how)
            }
        }
        runCurrent()

        assertEquals(PlayEnd.entries.map { PlayEnded(it, 40_000) }, store.plays.sortedBy { it.startedAt }.map { it.end })
    }

    @Test
    fun `a song crossfaded out is left where its fade began, and counts as heard to its end`() = runTest {
        val counter = counter()
        Lap(counter, id = 1).apply {
            play(185_000)
            // The fade began at 190 s; the player says so as the next song starts, a few seconds after its last word.
            clock.now += 5_000
            counter.onEvent(PlayEvent.Ended(id, song, clock.now, SONG_MS, PlayEnd.FINISHED, leftAtMs = 190_000))
        }
        runCurrent()

        assertEquals(listOf(LoggedPlay(lantern, T0, SONG_MS, counted = true, zoneOffsetMinutes = 180, end = PlayEnded(PlayEnd.FINISHED, 190_000))), store.plays)
    }

    @Test
    fun `a play still going has no end yet`() = runTest {
        Lap(counter(), id = 1, start = begun).apply {
            play(20_000)
            pause()
        }
        runCurrent()

        assertEquals(listOf(LoggedPlay(lantern, T0, 20_000, counted = false, start = begun, zoneOffsetMinutes = 180)), store.plays)
    }

    @Test
    fun `a skip closed by newer plays before its end is kept with its end unknown`() = runTest {
        val counter = counter()
        Lap(counter, id = 1).play(500)
        Lap(counter, id = 2, song = kettle).play(20_000)
        // A third play while two go on: the oldest is closed as it stands.
        Lap(counter, id = 3, song = kettle)
        runCurrent()

        assertEquals(LoggedPlay(lantern, T0, 500, counted = false, zoneOffsetMinutes = 180, end = PlayEnded(PlayEnd.UNKNOWN, 500)), store.plays.firstOrNull { it.song == lantern })
    }

    @Test
    fun `after the app stopped, a kept play that does not go on ends as stopped where it was kept`() = runTest {
        Lap(counter(), id = 1).apply {
            play(40_000)
            pause()
        }
        runCurrent()

        Lap(counter(), id = 2, song = kettle).apply {
            play(10_000)
            end(how = PlayEnd.NEXT)
        }
        runCurrent()

        assertEquals(listOf(PlayEnded(PlayEnd.STOPPED, 40_000), PlayEnded(PlayEnd.NEXT, 10_000)), store.plays.sortedBy { it.startedAt }.map { it.end })
    }

    @Test
    fun `a play that goes on after the app stopped keeps how it first began`() = runTest {
        Lap(counter(), id = 1, start = begun).apply {
            play(120_000)
            pause()
        }
        runCurrent()

        Lap(counter(), id = 1, from = 120_000, start = PlayStart(StartedBy.UNKNOWN, SourceKind.ALBUM, shuffled = false, songKey = begun.songKey)).apply {
            play(80_000)
            end(how = PlayEnd.FINISHED)
        }
        runCurrent()

        assertEquals(listOf(LoggedPlay(lantern, T0, SONG_MS, counted = true, start = begun, zoneOffsetMinutes = 180, end = PlayEnded(PlayEnd.FINISHED, SONG_MS))), store.plays)
    }

    @Test
    fun `a short play is one heard under a second that did not count`() {
        assertTrue(LoggedPlay(lantern, T0, 999, counted = false).isShort)
        assertTrue(LoggedPlay(lantern, T0, 0, counted = false, end = PlayEnded(PlayEnd.NEXT, 0)).isShort)
        assertFalse(LoggedPlay(lantern, T0, 1_000, counted = false).isShort)
        // An import's play: counted, nothing heard.
        assertFalse(LoggedPlay(lantern, T0, 0, counted = true, imported = true).isShort)
    }

    @Test
    fun `a song's key is its names as compared and its length to 2 s, the same after the file moves`() {
        val song = song("Music/KJB test/Weathervane Hum.mp3", title = "Weathervane Hum (Live)", artist = "\u00d6mer & the Kettles", album = "Harbour Glass", durationMs = 200_999)
        assertEquals("weathervane hum live|omer and the kettles|harbour glass|100", songKey(song))
        assertEquals(songKey(song), songKey(song.copy(ref = SongRef("/storage/emulated/0/Music/Elsewhere/renamed.mp3", 99))))
        assertEquals("weathervane hum live|||101", songKey(song.copy(artist = null, album = null, durationMs = 201_000)))
        assertEquals("weathervane hum live|omer and the kettles|harbour glass|0", songKey(song.copy(durationMs = -1)))
    }

    @Test
    fun `the time zone is kept as minutes ahead of UTC at the play's start`() {
        assertEquals(180, zoneOffsetMinutes(AMMAN, T0))
        assertEquals(-300, zoneOffsetMinutes(ZoneId.of("America/New_York"), T0))
        // Summer time: the offset of that moment.
        assertEquals(-240, zoneOffsetMinutes(ZoneId.of("America/New_York"), Instant.parse("2027-07-01T12:00:00Z").toEpochMilli()))
        assertEquals(330, zoneOffsetMinutes(ZoneId.of("Asia/Kolkata"), T0))
    }

    private companion object {
        val AMMAN: ZoneId = ZoneId.of("Asia/Amman")
        const val T0: Long = 1_800_000_000_000L
        const val SONG_MS: Long = 200_000
        const val TICK_MS: Long = 5_000
    }
}
