package app.lyrify.library

import app.lyrify.books.BookPlace
import app.lyrify.books.BookState
import app.lyrify.books.continueBook
import app.lyrify.books.isBookPart
import app.lyrify.books.looksLikeBook
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
import org.junit.Assert.assertFalse
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
    private val lantern = song("Music/Harbour Glass/01 Paper Lantern.mp3", title = "Paper Lantern", artist = "Kit Calloway", album = "Harbour Glass", track = 1, genre = "Folk", id = 11)
    private val bell = song("Music/Harbour Glass/02 Tide Bell.mp3", title = "Tide Bell", artist = "Kit Calloway", album = "Harbour Glass", track = 2, genre = "Folk", id = 12)
    private val steps = song("Music/Odds/Glass Steps.mp3", title = "Glass Steps", artist = "Odo Brannick", album = "Odds", genre = "Ambient", id = 13)
    private val water = song("Music/Night Ferry/01 Low Water.mp3", title = "Low Water", artist = "Mira Solen", album = "Night Ferry", id = 14)
    private val all = listOf(lantern, bell, steps, water)

    private fun library(hidden: List<SongRef>, songs: List<Song> = all): Library = Library.of(songs, Settings(library = LibrarySettings(hiddenSongs = hidden)))

    @Test
    fun `a hidden song is in no list, album, artist, folder or genre, yet the phone still has it`() {
        val library = library(listOf(steps.ref, bell.ref))

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
        val hidden = SearchIndex.of(library(listOf(steps.ref)), LibrarySettings())

        assertEquals(listOf(steps), shown.find("glass").songs.map { it.item })
        assertEquals(emptyList<Song>(), hidden.find("glass").songs.map { it.item })
        assertEquals(emptyList<Album>(), hidden.find("odds").albums.map { it.item })
        assertEquals(emptyList<Artist>(), hidden.find("brannick").artists.map { it.item })
    }

    @Test
    fun `shuffle and every queue built from a list leave a hidden song out`() {
        val library = library(listOf(bell.ref))
        val settings = LibrarySettings(hiddenSongs = listOf(bell.ref))

        // The Songs tab's Shuffle and Play, an album's, a folder's: each plays the songs its list shows.
        assertTrue(bell !in songsTab(library.songs, { null }, settings))
        assertEquals(listOf(lantern), library.album(albumKey(lantern)!!)!!.songs)
        assertEquals(listOf(lantern), library.folder("Music/Harbour Glass")!!.allSongs)
        assertTrue(bell !in library.artist("Kit Calloway")!!.songs)
    }

    @Test
    fun `stats views leave a hidden song out, Your listening's totals too, but its plays stay counted and Song info has them`() = runBlocking {
        val log = FakePlayLog(
            listOf(
                LoggedPlay(steps.ref, 1_000, 175_000, counted = true),
                LoggedPlay(steps.ref, 2_000, 175_000, counted = true),
                LoggedPlay(lantern.ref, 3_000, 200_000, counted = true),
            ),
        )
        val library = library(listOf(steps.ref))
        val stats = PlayStats(log, { library })

        val listening = listeningOf(log.totals(), library)
        assertEquals(listOf(lantern), listening.songs.map { it.item })
        assertEquals(listOf("Kit Calloway"), listening.artists.map { it.item.name })
        // The headline counts the songs listed under it (review F2, finding 8); the plays stay in the log.
        assertEquals(1, listening.plays)
        assertEquals(200_000L, listening.heardMs)
        assertEquals(3, log.plays.size)
        assertEquals(listOf(lantern.path), stats.mostPlayed(50).map { it.song.path })
        assertEquals(listOf(lantern.path), stats.recentlyPlayed(50).map { it.song.path })
        assertEquals(2, stats.plays(steps.ref)?.count)
        // The lists sorted by plays know every song's.
        assertEquals(mapOf(steps.path to 2, lantern.path to 1), stats.everySong().mapValues { it.value.count })
    }

    @Test
    fun `unhidden, a song is back in every list and in search`() {
        val settings = LibrarySettings().hiding(listOf(steps.ref)).unhiding(listOf(steps.ref))
        val library = Library.of(all, Settings(library = settings))

        assertEquals(emptyList<SongRef>(), settings.hiddenSongs)
        assertEquals(all.toSet(), library.songs.toSet())
        assertEquals(listOf(steps), SearchIndex.of(library, settings).find("glass").songs.map { it.item })
    }

    @Test
    fun `hiding keeps each song once after those hidden before, and unhiding takes out only the songs named`() {
        val once = LibrarySettings(hiddenSongs = listOf(bell.ref)).hiding(listOf(steps.ref, SongRef(bell.path), steps.ref, water.ref))

        assertEquals(listOf(bell.ref, steps.ref, water.ref), once.hiddenSongs)
        assertEquals(listOf(bell.ref), once.unhiding(listOf(steps.ref, SongRef(water.path), lantern.ref)).hiddenSongs)
    }

    @Test
    fun `Hidden songs lists the hidden songs on the phone, the latest hidden first, each with how it was kept, without those gone from it`() {
        val gone = SongRef(VOLUME + "Music/Old/Ash Road.mp3", 99)
        val hidden = listOf(steps.ref, gone, water.ref)

        assertEquals(listOf(water.ref to water, steps.ref to steps), hiddenSongs(library(hidden), hidden))
        assertEquals(emptyList<Pair<SongRef, Song>>(), hiddenSongs(library(emptyList()), emptyList()))
    }

    // A stable identity (review F2, finding 1): the path, then MediaStore's id on the same volume, as playlists find songs.

    @Test
    fun `a hidden song renamed in its folder or moved with its name stays hidden by its id, and Hidden songs finds it there`() {
        val renamed = steps.copy(ref = SongRef(VOLUME + "Music/Odds/Glass Steps (edit).mp3", 13), folder = "Music/Odds/")
        val moved = bell.copy(ref = SongRef(VOLUME + "Music/Kept/02 Tide Bell.mp3", 12), folder = "Music/Kept/")
        val library = library(listOf(steps.ref, bell.ref), listOf(lantern, renamed, moved, water))

        assertEquals(listOf(lantern, water), library.songs)
        assertEquals(listOf(bell.ref to moved, steps.ref to renamed), hiddenSongs(library, listOf(steps.ref, bell.ref)))
        // Unhide takes out the entry kept for it, whatever path it had then.
        assertEquals(emptyList<SongRef>(), LibrarySettings(hiddenSongs = listOf(steps.ref, bell.ref)).unhiding(listOf(steps.ref, bell.ref)).hiddenSongs)
    }

    @Test
    fun `an id is never taken across volumes, nor for a file renamed and moved at once, but a new file on a hidden path stays hidden`() {
        val card = steps.copy(ref = SongRef("/storage/7F3A-11C2/Music/Odds/Glass Steps.mp3", 13), folder = "Music/Odds/")
        val elsewhere = bell.copy(ref = SongRef(VOLUME + "Music/Kept/Bell Again.mp3", 12), folder = "Music/Kept/")
        val replaced = water.copy(ref = SongRef(water.path, 77))

        assertEquals(listOf(elsewhere, card), library(listOf(steps.ref, bell.ref), listOf(card, elsewhere)).songs)
        assertEquals(emptyList<Song>(), library(listOf(water.ref), listOf(replaced)).songs)
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

        settings.value = Settings(library = LibrarySettings(hiddenSongs = listOf(water.ref)))
        runCurrent()
        assertTrue(water !in songs())

        settings.value = Settings()
        runCurrent()
        assertTrue(water in songs())
        assertEquals(1, source.reads)
    }

    // Books (AB1): a hidden file is in no book

    @Test
    fun `a hidden file is in no book, the Continue row or a suggestion, and the rest of its book keeps its order and place`() {
        val parts = (1..4).map { n -> song("Audiobooks/Lantern Road/0$n Part $n.mp3", title = "Part $n", album = "Lantern Road", track = n, id = 40L + n) }
        val tideChart = song("Audiobooks/Tide Chart/Tide Chart.mp3", album = "Tide Chart", id = 50)
        val episode = song("Music/Talks/Episode 7.mp3", isPodcast = true, id = 60)
        val before = library(emptyList(), parts + tideChart + episode)
        val lanternRoad = before.books.single { it.parts.size == 4 }
        // Part 3 played at 61 s, then Tide Chart, later.
        val state = BookState()
            .withPlace(lanternRoad, BookPlace(2, 61_000), now = 1)
            .withPlace(before.books.single { it.id == tideChart.path }, BookPlace(0, 5_000), now = 2)
        // Part 2 hidden, then renamed: hidden by its id still (F2), and out of its book too.
        val renamed = parts[1].copy(ref = SongRef(VOLUME + "Audiobooks/Lantern Road/02 Part Two.mp3", 42))

        val library = library(listOf(parts[1].ref, tideChart.ref, episode.ref), listOf(parts[0], renamed, parts[2], parts[3], tideChart, episode))

        val book = library.books.single()
        assertEquals(lanternRoad.id, book.id)
        assertEquals(listOf(parts[0], parts[2], parts[3]), book.parts)
        assertNull(library.bookOf(renamed.path))
        assertEquals(BookPlace(1, 61_000), state.placeOf(book))
        assertEquals(book, continueBook(library.books, state))
        assertEquals(emptyList<Song>(), library.songs)
        // A hidden podcast opened from a playlist suggests nothing.
        assertFalse(looksLikeBook(episode, library))
        assertTrue(looksLikeBook(episode, before))
    }

    @Test
    fun `a hidden book file played from a playlist still never gets lyrics or the AI, by what its path says, while hidden music does`() {
        val orchard = song("Music/Talks/Quiet Orchard.m4b", id = 70)
        val chapter = song("Audiobooks/Lantern Road/01 Part 1.mp3", album = "Lantern Road", track = 1, id = 71)
        val library = library(listOf(orchard.ref, chapter.ref, steps.ref), all + orchard + chapter)

        assertTrue(isBookPart(orchard.path, library))
        assertTrue(isBookPart(chapter.path, library))
        assertFalse(isBookPart(steps.path, library))
        assertFalse(isBookPart(lantern.path, library))
    }
}

