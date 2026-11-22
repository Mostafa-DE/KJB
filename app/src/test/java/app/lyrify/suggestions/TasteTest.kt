package app.lyrify.suggestions

import app.lyrify.library.Library
import app.lyrify.library.Song
import app.lyrify.library.SongRef
import app.lyrify.library.VOLUME
import app.lyrify.library.albumKey
import app.lyrify.library.song
import app.lyrify.player.SourceKind
import app.lyrify.settings.Settings
import app.lyrify.stats.LoggedPlay
import app.lyrify.stats.PlayEnd
import app.lyrify.stats.PlayEnded
import app.lyrify.stats.PlayStart
import app.lyrify.stats.StartedBy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime

/** The taste model: what each play says, the power-law score, the roll-ups, the next-song table. Made-up names only. */
class TasteTest {
    private val amman = ZoneId.of("Asia/Amman")
    private val now = at("2026-11-22T12:00+03:00")

    private val copper = song("Music/Low Sun/Copper Sky.mp3", artist = "Kestrel", album = "Low Sun", genre = "Indie folk", id = 1)
    private val ember = song("Music/Low Sun/Ember Road.mp3", artist = "Kestrel", album = "Low Sun", genre = "Indie folk", id = 2)
    private val paper = song("Music/Tidelines/Paper Harbour.mp3", artist = "Mira Solen", album = "Tidelines", genre = "Dream pop", id = 3)
    private val fog = song("Music/Undertow/Fog on the Viaduct.mp3", artist = "The Quiet Fathoms", album = "Undertow Radio", id = 4)
    private val chapter = song("Audiobooks/Long Road/Part 1.mp3", artist = "Narrator", album = "Long Road", durationMs = 3_600_000, id = 5, isAudiobook = true)
    private val episode = song("Podcasts/Show/Episode 9.mp3", artist = "Host", durationMs = 1_800_000, id = 6, isPodcast = true)
    private val library = Library.of(listOf(copper, ember, paper, fog, chapter, episode), Settings())

    private fun at(text: String): Long = ZonedDateTime.parse(text).toInstant().toEpochMilli()

    private fun daysAgo(days: Double): Long = now - (days * DAY_MS).toLong()

    @Suppress("LongParameterList")
    private fun play(
        song: Song,
        startedAt: Long,
        by: StartedBy? = StartedBy.AUTO,
        how: PlayEnd? = PlayEnd.FINISHED,
        leftAtMs: Long = song.durationMs,
        shuffled: Boolean = false,
        heardMs: Long = leftAtMs,
        counted: Boolean = heardMs >= song.durationMs / 2,
        imported: Boolean = false,
        path: String = song.path,
    ) = LoggedPlay(
        SongRef(path, song.ref.mediaStoreId), startedAt, heardMs, counted,
        start = by?.let { PlayStart(it, SourceKind.ALBUM, shuffled, null) }, zoneOffsetMinutes = if (by == null) null else 180,
        end = how?.let { PlayEnded(it, leftAtMs) }, imported = imported,
    )

    // What one play says

    @Test
    fun `a song picked by hand and heard to its end counts twice one that came on, and a suggested one a quarter`() {
        assertEquals(2.0, playValue(play(copper, now, by = StartedBy.PICKED), copper.durationMs), 1e-9)
        assertEquals(1.0, playValue(play(copper, now, by = StartedBy.AUTO), copper.durationMs), 1e-9)
        assertEquals(1.0, playValue(play(copper, now, by = StartedBy.NEXT), copper.durationMs), 1e-9)
        assertEquals(0.25, playValue(play(copper, now, by = StartedBy.SUGGESTED), copper.durationMs), 1e-9)
    }

    @Test
    fun `heard 80 percent is a plus however it ended, and about half is neutral`() {
        assertEquals(1.0, playValue(play(copper, now, how = PlayEnd.NEXT, leftAtMs = 160_000), copper.durationMs), 1e-9)
        assertEquals(1.0, playValue(play(copper, now, how = PlayEnd.STOPPED, leftAtMs = 170_000), copper.durationMs), 1e-9)
        assertEquals(0.0, playValue(play(copper, now, how = PlayEnd.NEXT, leftAtMs = 100_000), copper.durationMs), 1e-9)
        assertEquals(0.0, playValue(play(copper, now, how = PlayEnd.STOPPED, leftAtMs = 60_000), copper.durationMs), 1e-9)
    }

    @Test
    fun `an early skip is a minus, half that under Shuffle, and a late skip is about zero`() {
        assertEquals(-1.0, playValue(play(copper, now, how = PlayEnd.NEXT, leftAtMs = 10_000), copper.durationMs), 1e-9)
        assertEquals(-0.5, playValue(play(copper, now, how = PlayEnd.NEXT, leftAtMs = 10_000, shuffled = true), copper.durationMs), 1e-9)
        assertEquals(0.0, playValue(play(copper, now, how = PlayEnd.NEXT, leftAtMs = 150_000), copper.durationMs), 1e-9)
        // Between the early skip's end (30 s, 15%) and half the song: from half an early skip down to nothing.
        assertEquals(-0.5 * (0.5 - 0.4) / (0.5 - 0.15), playValue(play(copper, now, how = PlayEnd.NEXT, leftAtMs = 80_000), copper.durationMs), 1e-9)
    }

    @Test
    fun `a jump away is half a skip, and Previous or a stop is none`() {
        assertEquals(-0.5, playValue(play(copper, now, how = PlayEnd.JUMP, leftAtMs = 5_000), copper.durationMs), 1e-9)
        assertEquals(0.0, playValue(play(copper, now, how = PlayEnd.BACK, leftAtMs = 5_000), copper.durationMs), 1e-9)
        assertEquals(0.0, playValue(play(copper, now, how = PlayEnd.STOPPED, leftAtMs = 5_000), copper.durationMs), 1e-9)
    }

    @Test
    fun `a play from before 3_1_1 or an import counts as a plain play when it counted`() {
        assertEquals(1.0, playValue(play(copper, now, by = null, how = null, heardMs = 150_000, counted = true), copper.durationMs), 1e-9)
        assertEquals(0.0, playValue(play(copper, now, by = null, how = null, heardMs = 20_000, counted = false), copper.durationMs), 1e-9)
        assertEquals(1.0, playValue(play(copper, now, by = null, how = null, heardMs = 0, counted = true, imported = true), copper.durationMs), 1e-9)
    }

    // A song's score

    @Test
    fun `a play fades as a power law of the days since, never to zero`() {
        val taste = Taste.of(listOf(play(copper, daysAgo(4.0))), library, emptyMap(), now, amman)
        assertEquals(0.5, taste.score(copper.path), 1e-6)
        val old = Taste.of(listOf(play(copper, daysAgo(1_000.0))), library, emptyMap(), now, amman)
        assertTrue(old.score(copper.path) > 0.03)
        assertEquals(0.0, old.score(paper.path), 1e-9)
    }

    @Test
    fun `a song played once yesterday outranks one played twice a year ago, and a favourite is a strong boost`() {
        val plays = listOf(play(paper, daysAgo(365.0)), play(paper, daysAgo(364.0)), play(copper, daysAgo(1.0)))
        val taste = Taste.of(plays, library, emptyMap(), now, amman)
        assertTrue(taste.score(copper.path) > taste.score(paper.path))
        val loved = Taste.of(plays, library, mapOf(paper.path to daysAgo(30.0)), now, amman)
        assertTrue(loved.score(paper.path) > loved.score(copper.path))
        assertEquals(taste.score(paper.path) + Weights().favourite, loved.score(paper.path), 1e-9)
    }

    @Test
    fun `Repeat one laps count normally up to 3 plays of the song a day`() {
        val day = at("2026-11-20T10:00+03:00")
        val laps = (0 until 5).map { play(copper, day + it * copper.durationMs) }
        val nextDay = play(copper, at("2026-11-21T10:00+03:00"))
        val taste = Taste.of(laps + nextDay, library, emptyMap(), now, amman)
        assertEquals(listOf(1.0, 1.0, 1.0, 0.0, 0.0, 1.0), taste.songs[copper.path]?.values?.toList())
    }

    @Test
    fun `the same song picked again the same day is no lap and counts`() {
        val day = at("2026-11-20T10:00+03:00")
        val plays = (0 until 5).map { play(copper, day + it * copper.durationMs, by = StartedBy.PICKED) }
        assertEquals(List(5) { 2.0 }, Taste.of(plays, library, emptyMap(), now, amman).songs[copper.path]?.values?.toList())
    }

    @Test
    fun `books and podcasts never count, and a moved file keeps its plays`() {
        val plays = listOf(play(chapter, daysAgo(1.0)), play(episode, daysAgo(1.0)), play(paper, daysAgo(1.0), path = VOLUME + "Old/Paper Harbour.mp3"))
        val taste = Taste.of(plays, library, emptyMap(), now, amman)
        assertEquals(setOf(paper.path), taste.songs.keys)
        assertTrue(taste.score(paper.path) > 0)
    }

    @Test
    fun `a song's counts say what the reasons need`() {
        val october = at("2026-10-10T20:00+03:00")
        val plays = List(3) { play(copper, october + it * DAY_MS.toLong(), by = StartedBy.PICKED) } +
            play(copper, daysAgo(3.0), how = PlayEnd.NEXT, leftAtMs = 5_000) +
            play(copper, daysAgo(2.0), by = StartedBy.SUGGESTED)
        val song = Taste.of(plays, library, emptyMap(), now, amman).songs[copper.path]
        assertEquals(3, song?.picks)
        assertEquals(mapOf(YearMonth.of(2026, 10) to 3), song?.picksByMonth)
        assertEquals(1, song?.earlySkips)
        assertEquals(1, song?.suggestedPlays)
        assertEquals(4, song?.heardFully)
        assertEquals(daysAgo(3.0), song?.lastSkipAt)
        assertEquals(daysAgo(2.0), song?.lastPlayedAt)
        assertEquals(october, song?.firstPlayedAt)
        assertNull(song?.favouriteAt)
    }

    // Roll-ups

    @Test
    fun `artists, albums and genres roll up from their songs, the most liked at 1`() {
        val plays = listOf(play(copper, daysAgo(1.0)), play(ember, daysAgo(2.0)), play(paper, daysAgo(2.0)))
        val taste = Taste.of(plays, library, emptyMap(), now, amman)
        assertEquals(1.0, taste.artist("Kestrel"), 1e-9)
        assertTrue(taste.artist("Mira Solen") in 0.2..0.6)
        assertEquals(0.0, taste.artist("The Quiet Fathoms"), 1e-9)
        assertEquals(1.0, taste.album(albumKey(copper)!!), 1e-9)
        assertEquals(1.0, taste.genre("Indie folk"), 1e-9)
        assertEquals(listOf("Kestrel", "Mira Solen"), taste.likedArtists.map { it.first })
    }

    @Test
    fun `one song skipped a lot pulls its artist down only a little`() {
        val skips = List(10) { play(ember, daysAgo(1.0 + it), how = PlayEnd.NEXT, leftAtMs = 3_000) }
        val taste = Taste.of(skips + play(copper, daysAgo(1.0)), library, emptyMap(), now, amman)
        assertTrue(taste.score(ember.path) < -1.0)
        assertTrue(taste.artist("Kestrel") > 0)
    }

    // The next-song table

    @Test
    fun `the next-song table learns picks most, songs in order some, Shuffle little, and its own suggestions not at all`() {
        val start = daysAgo(5.0)
        fun after(n: Int) = start + n * copper.durationMs
        val plays = listOf(
            play(copper, after(0)), play(paper, after(1), by = StartedBy.PICKED),
            play(copper, after(2)), play(paper, after(3), by = StartedBy.PICKED),
            play(copper, after(4)), play(ember, after(5), by = StartedBy.AUTO),
            play(copper, after(6)), play(fog, after(7), by = StartedBy.AUTO, shuffled = true),
            play(copper, after(8)), play(fog, after(9), by = StartedBy.SUGGESTED),
        )
        val follows = Taste.of(plays, library, emptyMap(), now, amman).follows[copper.path]
        assertEquals(listOf(Follow(paper.path, 2.0), Follow(ember.path, 0.5), Follow(fog.path, 0.25)), follows)
    }

    @Test
    fun `a song after a long pause, or skipped early, is no follow`() {
        val start = daysAgo(5.0)
        val plays = listOf(
            play(copper, start), play(paper, start + copper.durationMs + 31 * 60_000, by = StartedBy.PICKED),
            play(copper, start + DAY_MS.toLong()), play(ember, start + DAY_MS.toLong() + copper.durationMs, by = StartedBy.PICKED, how = PlayEnd.NEXT, leftAtMs = 4_000),
        )
        assertNull(Taste.of(plays, library, emptyMap(), now, amman).follows[copper.path])
    }

    // Friends, interactions, recent plays

    @Test
    fun `friends love a song they favourite or play 3 times, matched by title and artist`() {
        val friendPlays = listOf(
            FriendPlay("f1", "Paper Harbour", "Mira Solen", null, paper.durationMs, daysAgo(2.0), favourite = true),
            FriendPlay("f2", "paper harbour", "MIRA SOLEN", "Other", null, daysAgo(10.0)),
            FriendPlay("f2", "Paper Harbour", "Mira Solen", null, null, daysAgo(11.0)),
            FriendPlay("f2", "Paper Harbour", "Mira Solen", null, null, daysAgo(12.0)),
            FriendPlay("f3", "Fog on the Viaduct", "The Quiet Fathoms", null, fog.durationMs + 9_000, daysAgo(1.0), favourite = true),
            FriendPlay("f3", "Unknown Song", "Nobody", null, null, daysAgo(1.0), favourite = true),
        )
        val friends = Taste.of(emptyList(), library, emptyMap(), now, amman, friendPlays = friendPlays).friends
        assertEquals(mapOf(paper.path to FriendLove(friends = 2, playsThisWeek = 1)), friends)
    }

    @Test
    fun `looking at a song, its lyrics or its artist adds a little, capped`() {
        val seen = List(20) { Interaction(daysAgo(1.0), InteractionKind.LYRICS_OPENED, song = fog.ref) } +
            Interaction(daysAgo(1.0), InteractionKind.ARTIST_OPENED, artist = "Mira Solen") +
            Interaction(daysAgo(200.0), InteractionKind.SONG_OPENED, song = copper.ref)
        val taste = Taste.of(emptyList(), library, emptyMap(), now, amman, interactions = seen)
        assertEquals(0.5, taste.score(fog.path), 1e-9)
        assertEquals(0.0, taste.score(copper.path), 1e-9)
        assertTrue(taste.artist("Mira Solen") > 0)
    }

    @Test
    fun `songs played lately are the last 50 plays or the last day, whichever holds more`() {
        val old = List(60) { play(if (it == 0) fog else copper, daysAgo(10.0) + it * 1_000L) }
        val recent = listOf(play(paper, daysAgo(0.5)))
        assertEquals(setOf(copper.path, paper.path), Taste.of(old + recent, library, emptyMap(), now, amman).recentPaths)
        val few = listOf(play(fog, daysAgo(10.0)), play(paper, daysAgo(0.5)))
        assertEquals(setOf(fog.path, paper.path), Taste.of(few, library, emptyMap(), now, amman).recentPaths)
        assertFalse(Taste.of(few, library, emptyMap(), now, amman).recentPaths.contains(copper.path))
    }

    @Test
    fun `similar artists are kept to those the library has, by the library's name`() {
        val similar = mapOf("Kestrel" to listOf(SimilarArtist("MIRA SOLEN", 0.9f), SimilarArtist("Nobody Here", 0.8f)))
        val taste = Taste.of(emptyList(), library, emptyMap(), now, amman, similar = similar)
        assertEquals(mapOf("Kestrel" to listOf(SimilarArtist("Mira Solen", 0.9f))), taste.similar)
    }
}
