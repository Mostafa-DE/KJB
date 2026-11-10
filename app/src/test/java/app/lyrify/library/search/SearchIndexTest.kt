package app.lyrify.library.search

import app.lyrify.library.Library
import app.lyrify.library.Song
import app.lyrify.library.song
import app.lyrify.player.QueueSource
import app.lyrify.player.SourceKind
import app.lyrify.settings.LibrarySettings
import app.lyrify.settings.Settings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Search over the library's names: folding, matching and ranking, and the queue a tapped song plays. Made-up names only. */
class SearchIndexTest {
    private val lantern = song("Music/Harbour Glass/01 Paper Lantern.mp3", title = "Paper Lantern", album = "Harbour Glass")
    private val lanterns = song("Music/Harbour Glass/02 Lanterns at Dusk.mp3", title = "Lanterns at Dusk", album = "Harbour Glass")
    private val glow = song("Music/Night Ferry/Glowlantern.mp3", title = "Glowlantern", artist = "Mira Okafor", album = "Night Ferry")
    private val keeper = song("Music/Night Ferry/The Lantern Keeper.mp3", title = "The Lantern Keeper", artist = "Mira Okafor", album = "Night Ferry")
    private val cafe = song("Music/Loose/Café del Mar Azul.flac", title = "Café del Mar Azul", artist = "Søren Vale")

    private fun library(vararg songs: Song, settings: Settings = Settings()): Library = Library.of(songs.toList(), settings)

    private fun index(library: Library, settings: LibrarySettings = LibrarySettings()): SearchIndex = SearchIndex.of(library, settings)

    private fun <T> Hit<T>.shown(): String = text.substring(match.start, match.end)

    // Folding

    @Test
    fun `case, accents and punctuation are folded away`() {
        assertEquals("cafe del mar", fold("  Café  DEL-Már! "))
        assertEquals("strasse 9", fold("Straße, 9"))
        assertEquals("soren lodz", fold("Søren Łódź"))
        assertEquals("salt and ember", fold("Salt & Ember"))
        assertEquals("fiord", fold("ﬁord"))
        assertEquals("istanbul", fold("İstanbul"))
        assertEquals("", fold(" ¿…!! "))
    }

    @Test
    fun `letters of other scripts fold the same way`() {
        assertEquals(fold("σοφός"), fold("ΣΟΦΟΣ"))
        // Arabic: the vowel marks and the hamza on the alef are marks, so they go.
        assertEquals(fold("ابن"), fold("إبْنٌ"))
        assertEquals(fold("ДОМ"), fold("дом"))
    }

    // Matching

    @Test
    fun `a match maps back to the text as shown`() {
        assertEquals(Match(MatchKind.WORD_START, 0, 4), match("Café del Mar", fold("CAFE")))
        assertEquals(Match(MatchKind.WORD_START, 0, 6), match("Straße Nine", fold("strasse")))
        assertEquals(Match(MatchKind.WORD_START, 0, 8), match("Salt & Ember", fold("salt and e")))
        assertEquals(Match(MatchKind.INSIDE_WORD, 2, 6), match("Mañana", fold("nana")))
        assertNull(match("Mañana", fold("banana")))
        assertNull(match("Mañana", ""))
    }

    @Test
    fun `a word start anywhere beats an earlier match inside a word`() {
        assertEquals(Match(MatchKind.WORD_START, 7, 12), match("Aferry ferry", "ferry"))
        assertEquals(Match(MatchKind.WORD_START, 10, 16), match("Weather, (Window) Song", "window"))
    }

    // Ranking and groups

    @Test
    fun `a match at a word start ranks above one inside a word, then by title`() {
        val found = index(library(glow, keeper, lantern, lanterns)).find("lantern")

        assertEquals(listOf(lanterns, lantern, keeper, glow), found.songs.map { it.item })
        assertEquals(listOf(MatchKind.WORD_START, MatchKind.WORD_START, MatchKind.WORD_START, MatchKind.INSIDE_WORD), found.songs.map { it.match.kind })
        assertEquals(listOf("Lantern", "Lantern", "Lantern", "lantern"), found.songs.map { it.shown() })
    }

    @Test
    fun `each group matches its own names`() {
        val library = library(lantern, lanterns, glow, keeper, cafe)
        val index = index(library)

        val byArtist = index.find("okafor")
        assertEquals(listOf("Mira Okafor"), byArtist.artists.map { it.item.name })
        assertTrue(byArtist.songs.isEmpty())

        val byAlbum = index.find("harbour")
        assertEquals(listOf("Harbour Glass"), byAlbum.albums.map { it.item.title })
        assertEquals(listOf("Harbour Glass"), byAlbum.folders.map { it.item.name })
        assertEquals(listOf("Music/Harbour Glass"), byAlbum.folders.map { it.item.path })
        assertTrue(byAlbum.songs.isEmpty())

        val byFolder = index.find("loose")
        assertEquals(listOf("Music/Loose"), byFolder.folders.map { it.item.path })

        val accents = index.find("soren")
        assertEquals(listOf("Søren Vale"), accents.artists.map { it.item.name })
        assertEquals("Søren", accents.artists.single().shown())
    }

    @Test
    fun `titles are matched as they are shown`() {
        val library = library(lantern)
        assertTrue(index(library).find("01 paper").songs.isEmpty())

        val names = index(library, LibrarySettings(showFileNames = true)).find("01 paper")
        assertEquals(listOf(lantern), names.songs.map { it.item })
        assertEquals("01 Paper Lantern.mp3", names.songs.single().text)
    }

    @Test
    fun `songs the library hides are not found`() {
        val hidden = Settings().let { it.copy(library = it.library.copy(foldersToSkip = listOf("Music/Night Ferry"))) }
        val found = index(library(lantern, glow, keeper, settings = hidden)).find("lantern")

        assertEquals(listOf(lantern), found.songs.map { it.item })
        assertTrue(found.artists.isEmpty())
    }

    @Test
    fun `words without letters or digits find nothing`() {
        val index = index(library(lantern, glow))
        assertEquals(LibraryHits(), index.find(""))
        assertEquals(LibraryHits(), index.find(" ?! "))
    }

    @Test
    fun `lists are ranked by their names`() {
        val found = ranked(listOf("Daytrip", "Trip Hop Nights", "Road Trip", "Harbour Mix"), "TRIP") { it }

        assertEquals(listOf("Road Trip", "Trip Hop Nights", "Daytrip"), found.map { it.item })
        assertEquals(listOf(MatchKind.WORD_START, MatchKind.WORD_START, MatchKind.INSIDE_WORD), found.map { it.match.kind })
    }

    @Test
    fun `the name index is built without SearchGraph's lock, which Compose takes on the main thread`() {
        val library = library(lantern, glow, cafe)
        val built = CountDownLatch(1)
        // What lyrics() and recent() wait on while the main thread asks for them.
        synchronized(SearchGraph) {
            thread { SearchGraph.names(library, LibrarySettings()).also { built.countDown() } }
            assertTrue(built.await(5, TimeUnit.SECONDS))
        }
        assertEquals(listOf(lantern), SearchGraph.names(library, LibrarySettings()).find("paper").songs.map { it.item })
    }

    // The queue

    @Test
    fun `a song tapped in the results plays the song results, then the songs found only in lyrics`() {
        assertEquals(listOf(lantern, glow, cafe), searchQueue(listOf(lantern, glow), listOf(glow, cafe)))
        assertEquals(listOf(cafe), searchQueue(emptyList(), listOf(cafe)))
    }

    @Test
    fun `the queue is named after the words, the same list whatever their case or accents`() {
        assertEquals(QueueSource(SourceKind.SEARCH, "search:ferry bell", "Ferry Bell"), searchSource("  Ferry   Bell "))
        assertEquals(searchSource("ferry bell").key, searchSource("FÉRRY Bell").key)
        assertEquals("FÉRRY Bell", searchSource("FÉRRY Bell").name)
    }

    @Test
    fun `the car's search is Search's, the songs by name as Search ranks them, then those found only by their lyrics`() = runBlocking {
        val library = library(keeper, glow, lantern, lanterns, cafe)
        val search = SearchSongs { query, _, _ -> if (query == "lantern") listOf(glow, cafe) else emptyList() }
        // Word starts first, each by name, then inside a word; then the lyrics' songs not found by name.
        assertEquals(listOf(lanterns, lantern, keeper, glow, cafe), search.songs("lantern", library, LibrarySettings()))
        assertEquals(searchSource("Lantern"), search.source("Lantern"))
    }
}
