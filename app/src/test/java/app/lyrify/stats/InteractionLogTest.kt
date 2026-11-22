package app.lyrify.stats

import app.lyrify.library.SongRef
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import app.lyrify.suggestions.Interaction as SuggestionsInteraction
import app.lyrify.suggestions.InteractionKind as SuggestionsKind

/**
 * The interaction log as the screens note into it (IL1): searches with their words and the result tapped, pages and
 * lyrics opened, each with its time and the time zone then; Recent searches' "×" forgetting the words; and logcat never
 * holding them. Made-up names only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InteractionLogTest {
    private val store = FakePlayLog()
    private val logs = mutableListOf<String>()
    private var now = T0
    private var zone: ZoneId = AMMAN

    private fun TestScope.log(): InteractionLog = InteractionLog(store, backgroundScope, clock = { now }, zone = { zone }, log = { logs += it })

    private val song = Target(TargetKind.SONG, "/storage/emulated/0/Music/KJB test/Copper Kettle Waltz.flac")
    private val album = Target(TargetKind.ALBUM, """["KJB Test Band","Harbour Glass"]""")
    private val artist = Target(TargetKind.ARTIST, "Kit Calloway")

    @Test
    fun `each page and each lyrics opened is noted with its time and the time zone then`() = runTest {
        val log = log()
        log.opened(album)
        now += 60_000
        zone = NEW_YORK
        log.lyrics(song.key)
        runCurrent()

        assertEquals(
            listOf(
                Interaction(T0, InteractionKind.OPEN, 180, album),
                Interaction(T0 + 60_000, InteractionKind.LYRICS, -300, song),
            ),
            store.interactions,
        )
    }

    @Test
    fun `a search is noted once, and a result tapped soon after on its words is its result`() = runTest {
        val log = log()
        log.searched("  Copper Kettle ")
        now += 20_000
        // Case and accents aside, the same words.
        log.searched("copper kettlé", result = song)
        runCurrent()

        assertEquals(listOf(Interaction(T0, InteractionKind.SEARCH, 180, song, "Copper Kettle")), store.interactions)
    }

    @Test
    fun `another result tapped on the same words is a search of its own, and the search key again adds nothing`() = runTest {
        val log = log()
        log.searched("Kit", result = artist)
        now += 5_000
        log.searched("Kit")
        now += 5_000
        log.searched("Kit", result = album)
        runCurrent()

        assertEquals(
            listOf(
                Interaction(T0, InteractionKind.SEARCH, 180, artist, "Kit"),
                Interaction(T0 + 10_000, InteractionKind.SEARCH, 180, album, "Kit"),
            ),
            store.interactions,
        )
    }

    @Test
    fun `other words, or the same words a while later, are a new search`() = runTest {
        val log = log()
        log.searched("Harbour")
        now += 1_000
        log.searched("Harbour Glass")
        now += InteractionLog.SEARCH_GOES_ON_MS + 1
        log.searched("harbour glass", result = album)
        runCurrent()

        assertEquals(
            listOf(
                Interaction(T0, InteractionKind.SEARCH, 180, words = "Harbour"),
                Interaction(T0 + 1_000, InteractionKind.SEARCH, 180, words = "Harbour Glass"),
                Interaction(T0 + 1_000 + InteractionLog.SEARCH_GOES_ON_MS + 1, InteractionKind.SEARCH, 180, album, "harbour glass"),
            ),
            store.interactions,
        )
    }

    @Test
    fun `blank words are no search`() = runTest {
        val log = log()
        log.searched("   ")
        log.searched("", result = song)
        runCurrent()

        assertEquals(emptyList<Interaction>(), store.interactions)
    }

    @Test
    fun `two interactions never share a time, so neither takes the other's place`() = runTest {
        val log = log()
        log.opened(album)
        log.opened(artist)
        log.searched("Kit", result = artist)
        log.lyrics(song.key)
        runCurrent()

        assertEquals(listOf(T0, T0 + 1, T0 + 2, T0 + 3), store.interactions.map { it.at })
        assertEquals(listOf(album, artist, artist, song), store.interactions.map { it.target })
    }

    @Test
    fun `removing a search from Recent forgets the words of every search of those words, the searches kept`() = runTest {
        val log = log()
        val later = T0 + InteractionLog.SEARCH_GOES_ON_MS
        log.searched("Harbour Glass live")
        now += 1_000
        log.searched("Harbour Glass", result = album)
        now += InteractionLog.SEARCH_GOES_ON_MS + 1
        log.searched("HARBOUR GLASS")
        now += 1_000
        log.opened(album)
        now += 1_000

        log.forget("harbour glass")
        // The search going on is forgotten too: a result tapped on its words now is a search of its own, its words not
        // written back over the forgotten one.
        log.searched("Harbour Glass", result = song)
        runCurrent()

        assertEquals(
            listOf(
                Interaction(T0, InteractionKind.SEARCH, 180, words = "Harbour Glass live"),
                Interaction(T0 + 1_000, InteractionKind.SEARCH, 180, album),
                Interaction(later + 1_001, InteractionKind.SEARCH, 180),
                Interaction(later + 2_001, InteractionKind.OPEN, 180, album),
                Interaction(later + 3_001, InteractionKind.SEARCH, 180, song, "Harbour Glass"),
            ),
            store.interactions.sortedBy { it.at },
        )
    }

    @Test
    fun `Suggestions reads the interactions between two times, and none when the log cannot be read`() = runTest {
        val log = log()
        log.opened(album)
        now += 1_000
        log.lyrics(song.key)
        runCurrent()

        assertEquals(listOf(Interaction(T0 + 1_000, InteractionKind.LYRICS, 180, song)), log.interactions(from = T0 + 1))
        assertEquals(2, log.interactions().size)
        store.failing = true
        assertEquals(emptyList<Interaction>(), log.interactions())
    }

    @Test
    fun `Suggestions get the songs, albums and artists looked at, never a search's words, a folder or a playlist`() = runTest {
        val log = log()
        log.searched("Copper Kettle", result = song)
        now += 1_000
        log.searched("Harbour")
        now += 1_000
        log.opened(album)
        now += 1_000
        log.opened(artist)
        now += 1_000
        log.opened(Target(TargetKind.PLAYLIST, "7"))
        now += 1_000
        log.searched("Old Pier", result = Target(TargetKind.FOLDER, "Music/Old Pier"))
        now += 1_000
        log.opened(song)
        now += 1_000
        log.lyrics(song.key)
        runCurrent()

        assertEquals(
            listOf(
                SuggestionsInteraction(T0, SuggestionsKind.SEARCH_PICK, song = SongRef(song.key)),
                SuggestionsInteraction(T0 + 2_000, SuggestionsKind.ALBUM_OPENED, album = album.key),
                SuggestionsInteraction(T0 + 3_000, SuggestionsKind.ARTIST_OPENED, artist = artist.key),
                SuggestionsInteraction(T0 + 6_000, SuggestionsKind.SONG_OPENED, song = SongRef(song.key)),
                SuggestionsInteraction(T0 + 7_000, SuggestionsKind.LYRICS_OPENED, song = SongRef(song.key)),
            ),
            log.since(T0),
        )
        // From a time on, that time included; none when the log cannot be read.
        assertEquals(listOf(T0 + 6_000, T0 + 7_000), log.since(T0 + 6_000).map { it.at })
        store.failing = true
        assertEquals(emptyList<SuggestionsInteraction>(), log.since(T0))
    }

    @Test
    fun `logcat never holds a search's words, a song or a name`() = runTest {
        val log = log()
        store.failing = true
        log.searched("Copper Kettle", result = song)
        log.opened(artist)
        log.lyrics(song.key)
        log.forget("Copper Kettle")
        runCurrent()

        assertTrue(logs.isNotEmpty())
        logs.forEach { line ->
            listOf("Copper", "Kettle", "Kit", "Calloway", "storage", "KJB test").forEach { assertTrue(line, it !in line) }
        }
    }

    private companion object {
        const val T0: Long = 1_800_000_000_000L
        val AMMAN: ZoneId = ZoneId.of("Asia/Amman")
        val NEW_YORK: ZoneId = ZoneId.of("America/New_York")
    }
}
