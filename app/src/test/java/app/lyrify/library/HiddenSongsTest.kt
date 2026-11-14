package app.lyrify.library

import app.lyrify.library.search.SearchIndex
import app.lyrify.settings.LibrarySettings
import app.lyrify.settings.Settings
import app.lyrify.stats.FakePlayLog
import app.lyrify.stats.LoggedPlay
import app.lyrify.stats.PlayStats
import app.lyrify.stats.listeningOf
import app.lyrify.ui.songsTab
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hide a song (features 2026-11-15, item 6): a hidden song leaves every list, search, shuffle and stats view, while its
 * file stays on the phone and its plays stay counted; unhidden, it is back everywhere. "Hide folder" adds to Folders to
 * skip. Made-up names only.
 */
class HiddenSongsTest {
    private val lantern = song("Music/Harbour Glass/01 Paper Lantern.mp3", title = "Paper Lantern", artist = "Kit Calloway", album = "Harbour Glass", track = 1, genre = "Folk")
    private val bell = song("Music/Harbour Glass/02 Tide Bell.mp3", title = "Tide Bell", artist = "Kit Calloway", album = "Harbour Glass", track = 2, genre = "Folk")
    private val steps = song("Music/Odds/Glass Steps.mp3", title = "Glass Steps", artist = "Odo Brannick", album = "Odds", genre = "Ambient")
    private val water = song("Music/Night Ferry/01 Low Water.mp3", title = "Low Water", artist = "Mira Solen", album = "Night Ferry")
    private val all = listOf(lantern, bell, steps, water)

    private fun library(hidden: List<String>): Library = Library.of(all, Settings(library = LibrarySettings(hiddenSongs = hidden)))

    @Test
    fun `a hidden song is in no list, album, artist, folder or genre, yet the phone still has it`() {
        val library = library(listOf(steps.path, bell.path))

        assertEquals(listOf(lantern, water), library.songs)
        assertEquals(listOf(listOf(lantern), listOf(water)), library.albums.map { it.songs })
        assertEquals(listOf("Kit Calloway", "Mira Solen"), library.artists.map { it.name })
        assertEquals(listOf("Folk"), library.genres.map { it.name })
        assertNull(library.folder("Music/Odds"))
        assertEquals(listOf(lantern, water), library.root.allSongs)
        assertNull(library.song(steps.path))
        assertEquals(all, library.all)
        // Found again for a queue made before it was hidden, and for Hidden songs, but never listed.
        assertSame(steps, library.resolver.find(steps.ref))
        assertEquals(listOf(lantern), library.listed(listOf(steps.ref, lantern.ref, bell.ref)))
    }

    @Test
    fun `search finds no hidden song, nor its album or artist when it was their only song`() {
        val shown = SearchIndex.of(library(emptyList()), LibrarySettings())
        val hidden = SearchIndex.of(library(listOf(steps.path)), LibrarySettings())

        assertEquals(listOf(steps), shown.find("glass").songs.map { it.item })
        assertEquals(emptyList<Song>(), hidden.find("glass").songs.map { it.item })
        assertEquals(emptyList<Album>(), hidden.find("odds").albums.map { it.item })
        assertEquals(emptyList<Artist>(), hidden.find("brannick").artists.map { it.item })
    }

    @Test
    fun `shuffle and every queue built from a list leave a hidden song out`() {
        val library = library(listOf(bell.path))
        val settings = LibrarySettings(hiddenSongs = listOf(bell.path))

        // The Songs tab's Shuffle and Play, an album's, a folder's: each plays the songs its list shows.
        assertTrue(bell !in songsTab(library.songs, { null }, settings))
        assertEquals(listOf(lantern), library.album(albumKey(lantern)!!)!!.songs)
        assertEquals(listOf(lantern), library.folder("Music/Harbour Glass")!!.allSongs)
        assertTrue(bell !in library.artist("Kit Calloway")!!.songs)
    }

    @Test
    fun `stats views leave a hidden song out but keep its plays counted, and Song info still has them`() = runBlocking {
        val log = FakePlayLog(
            listOf(
                LoggedPlay(steps.ref, 1_000, 175_000, counted = true),
                LoggedPlay(steps.ref, 2_000, 175_000, counted = true),
                LoggedPlay(lantern.ref, 3_000, 200_000, counted = true),
            ),
        )
        val library = library(listOf(steps.path))
        val stats = PlayStats(log, { library })

        val listening = listeningOf(log.totals(), library)
        assertEquals(listOf(lantern), listening.songs.map { it.item })
        assertEquals(listOf("Kit Calloway"), listening.artists.map { it.item.name })
        // The time and plays it was heard for stay in the totals.
        assertEquals(3, listening.plays)
        assertEquals(550_000L, listening.heardMs)
        assertEquals(listOf(lantern.path), stats.mostPlayed(50).map { it.song.path })
        assertEquals(listOf(lantern.path), stats.recentlyPlayed(50).map { it.song.path })
        assertEquals(2, stats.plays(steps.ref)?.count)
        // The lists sorted by plays know every song's.
        assertEquals(mapOf(steps.path to 2, lantern.path to 1), stats.everySong().mapValues { it.value.count })
    }

    @Test
    fun `unhidden, a song is back in every list and in search`() {
        val settings = LibrarySettings().hiding(listOf(steps.path)).unhiding(listOf(steps.path))
        val library = Library.of(all, Settings(library = settings))

        assertEquals(emptyList<String>(), settings.hiddenSongs)
        assertEquals(all.toSet(), library.songs.toSet())
        assertEquals(listOf(steps), SearchIndex.of(library, settings).find("glass").songs.map { it.item })
    }

    @Test
    fun `hiding keeps each song once after those hidden before, and unhiding takes out only the songs named`() {
        val once = LibrarySettings(hiddenSongs = listOf(bell.path)).hiding(listOf(steps.path, bell.path, steps.path, water.path))

        assertEquals(listOf(bell.path, steps.path, water.path), once.hiddenSongs)
        assertEquals(listOf(bell.path), once.unhiding(listOf(steps.path, water.path, lantern.path)).hiddenSongs)
    }

    @Test
    fun `Hidden songs lists the hidden songs on the phone, the latest hidden first, without those gone from it`() {
        val gone = VOLUME + "Music/Old/Ash Road.mp3"

        assertEquals(listOf(water, steps), hiddenSongs(all, listOf(steps.path, gone, water.path)))
        assertEquals(emptyList<Song>(), hiddenSongs(all, emptyList()))
    }

    @Test
    fun `Hide folder adds the folder to Folders to skip once, and not when it or a folder above it is skipped already`() {
        val skipped = LibrarySettings().skipping("Music/Odds")

        assertEquals(listOf("Music/Odds"), skipped.foldersToSkip)
        assertSame(skipped, skipped.skipping("music/odds"))
        assertEquals(listOf("Music"), LibrarySettings(foldersToSkip = listOf("Music")).skipping("Music/Odds").foldersToSkip)
        assertEquals(listOf("Music/Odds", "Music/Night Ferry"), skipped.skipping("Music/Night Ferry").foldersToSkip)
        assertNull(Library.of(all, Settings(library = skipped)).folder("Music/Odds"))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `the library is regrouped when a song is hidden or unhidden, without reading the phone again`() = runTest {
        val source = FakeLibrarySource(SongsRead.Songs(all))
        val settings = MutableStateFlow(Settings())
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = LibraryRepository(source, settings, backgroundScope, io = dispatcher, compute = dispatcher)
        fun songs() = (repository.state.value as LibraryState.Ready).library.songs
        runCurrent()

        settings.value = Settings(library = LibrarySettings(hiddenSongs = listOf(water.path)))
        runCurrent()
        assertTrue(water !in songs())

        settings.value = Settings()
        runCurrent()
        assertTrue(water in songs())
        assertEquals(1, source.reads)
    }
}

