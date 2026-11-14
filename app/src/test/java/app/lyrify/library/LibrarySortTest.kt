package app.lyrify.library

import app.lyrify.settings.LibrarySettings
import app.lyrify.settings.Settings
import app.lyrify.settings.SortAlbums
import app.lyrify.settings.SortArtists
import app.lyrify.settings.SortSongs
import app.lyrify.stats.SongPlays
import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.Collator

/**
 * The sort keys of every song list, the Albums and the Artists tabs (features 2026-11-15, item 5): each in its first
 * direction and reversed, its ties, songs without a value last either way, and names in natural number order. Made-up
 * names only.
 */
class LibrarySortTest {
    private val settings = LibrarySettings()

    private fun titles(songs: List<Song>): List<String> = songs.map { it.title }

    private fun plays(song: Song, count: Int, last: Long?): Pair<String, SongPlays> = song.path to SongPlays(song.ref, count, last, count * 60_000L)

    // Natural number order

    @Test
    fun `titles and file names go in natural number order, Track 2 before Track 10, either way round`() {
        val songs = listOf("Track 10", "Track 2", "Track 1", "Track 9", "Track 02b").map { song("Music/Mix/$it.mp3", title = it) }

        assertEquals(listOf("Track 1", "Track 2", "Track 02b", "Track 9", "Track 10"), titles(sortSongs(songs, SortSongs.TITLE, settings)))
        assertEquals(listOf("Track 10", "Track 9", "Track 02b", "Track 2", "Track 1"), titles(sortSongs(songs, SortSongs.TITLE, settings, reversed = true)))
        assertEquals(listOf("Track 1", "Track 2", "Track 02b", "Track 9", "Track 10"), titles(sortSongs(songs, SortSongs.FILE_NAME, settings)))
        assertEquals(listOf("Track 10", "Track 9", "Track 02b", "Track 2", "Track 1"), titles(sortSongs(songs, SortSongs.FILE_NAME, settings, reversed = true)))
    }

    @Test
    fun `numbers count by their value in every name order, a folder's files and names with digits first too`() {
        val order = nameOrder(Collator.getInstance())
        assertEquals(listOf("2 Rivers", "10 Rivers", "100 Rivers"), listOf("100 Rivers", "10 Rivers", "2 Rivers").sortedWith(order))
        assertEquals(listOf("Side A 9", "Side A 10", "Side B 1"), listOf("Side B 1", "Side A 10", "Side A 9").sortedWith(order))
        assertEquals(listOf("CD 2", "CD 10"), listOf("CD 10", "CD 2").sortedWith(order))
        val folder = Library.of(listOf(song("Music/Rips/Part 10.mp3"), song("Music/Rips/Part 9.mp3"), song("Music/Rips/Part 1.mp3")), Settings()).folder("Music/Rips")!!
        assertEquals(listOf("Part 1", "Part 9", "Part 10"), titles(folder.songs))
    }

    @Test
    fun `an album's songs without track numbers and an artist's albums of one year go in natural order too`() {
        val ten = song("Music/Vols/b.mp3", title = "Vol. 10", album = "Volumes", artist = "Kit Calloway", year = 2020)
        val two = song("Music/Vols/a.mp3", title = "Vol. 2", album = "Volumes", artist = "Kit Calloway", year = 2020)
        val later = song("Music/Vols 10/a.mp3", title = "Opening", album = "Part 10", artist = "Mira Solen", year = 2021)
        val earlier = song("Music/Vols 2/a.mp3", title = "Closing", album = "Part 2", artist = "Mira Solen", year = 2021)
        val library = Library.of(listOf(ten, two, later, earlier), Settings())

        assertEquals(listOf("Vol. 2", "Vol. 10"), titles(library.album(albumKey(ten)!!)!!.songs))
        assertEquals(listOf("Part 2", "Part 10"), library.artist("Mira Solen")!!.albums.map { it.title })
        assertEquals(listOf("Closing", "Opening"), titles(library.artist("Mira Solen")!!.songs))
    }

    // Each key: first direction, reversed, ties, and no value last

    @Test
    fun `by year the newest first, an album's songs in track order, songs without a year last either way`() {
        val older = song("Music/b/1.mp3", title = "Low Water", album = "Night Ferry", year = 2019, track = 1)
        val olderA = song("Music/a/2.mp3", title = "Tide Bell", album = "Harbour Glass", year = 2019, track = 2)
        val olderB = song("Music/a/1.mp3", title = "Paper Lantern", album = "Harbour Glass", year = 2019, track = 1)
        val newer = song("Music/c/1.mp3", title = "Copper Moon", album = "Singles", year = 2021)
        val none = song("Music/d/1.mp3", title = "Glass Steps", year = null)
        val songs = listOf(none, older, olderA, newer, olderB)

        assertEquals(listOf("Copper Moon", "Paper Lantern", "Tide Bell", "Low Water", "Glass Steps"), titles(sortSongs(songs, SortSongs.YEAR, settings)))
        assertEquals(listOf("Paper Lantern", "Tide Bell", "Low Water", "Copper Moon", "Glass Steps"), titles(sortSongs(songs, SortSongs.YEAR, settings, reversed = true)))
    }

    @Test
    fun `by date added the newest first, songs added together by title, songs without a date last either way`() {
        val songs = listOf(
            song("Music/a.mp3", title = "Tide Bell", addedAt = 300),
            song("Music/b.mp3", title = "Low Water", addedAt = 100),
            song("Music/c.mp3", title = "Glass Steps", addedAt = null),
            song("Music/d.mp3", title = "Copper Moon", addedAt = 300),
        )

        assertEquals(listOf("Copper Moon", "Tide Bell", "Low Water", "Glass Steps"), titles(sortSongs(songs, SortSongs.RECENTLY_ADDED, settings)))
        assertEquals(listOf("Low Water", "Copper Moon", "Tide Bell", "Glass Steps"), titles(sortSongs(songs, SortSongs.RECENTLY_ADDED, settings, reversed = true)))
    }

    @Test
    fun `by play count the most first, equal counts the latest played first, songs never played count none`() {
        val a = song("Music/a.mp3", title = "Tide Bell")
        val b = song("Music/b.mp3", title = "Low Water")
        val c = song("Music/c.mp3", title = "Glass Steps")
        val d = song("Music/d.mp3", title = "Copper Moon")
        val counts = mapOf(plays(a, 5, 10), plays(b, 5, 20), plays(c, 1, 30))
        val songs = listOf(a, b, c, d)

        assertEquals(listOf("Low Water", "Tide Bell", "Glass Steps", "Copper Moon"), titles(sortSongs(songs, SortSongs.PLAY_COUNT, settings, plays = counts)))
        assertEquals(listOf("Copper Moon", "Glass Steps", "Low Water", "Tide Bell"), titles(sortSongs(songs, SortSongs.PLAY_COUNT, settings, reversed = true, plays = counts)))
    }

    @Test
    fun `by last played the latest first, equal times by title, songs never played last either way`() {
        val a = song("Music/a.mp3", title = "Tide Bell")
        val b = song("Music/b.mp3", title = "Low Water")
        val c = song("Music/c.mp3", title = "Glass Steps")
        val d = song("Music/d.mp3", title = "Copper Moon")
        val counts = mapOf(plays(a, 2, 30), plays(b, 1, 10), plays(d, 4, 30))
        val songs = listOf(a, b, c, d)

        assertEquals(listOf("Copper Moon", "Tide Bell", "Low Water", "Glass Steps"), titles(sortSongs(songs, SortSongs.LAST_PLAYED, settings, plays = counts)))
        assertEquals(listOf("Low Water", "Copper Moon", "Tide Bell", "Glass Steps"), titles(sortSongs(songs, SortSongs.LAST_PLAYED, settings, reversed = true, plays = counts)))
    }

    @Test
    fun `by length the longest first, equal lengths by title, a length MediaStore could not read last either way`() {
        val songs = listOf(
            song("Music/a.mp3", title = "Tide Bell", durationMs = 300_000),
            song("Music/b.mp3", title = "Low Water", durationMs = 100_000),
            song("Music/c.mp3", title = "Glass Steps", durationMs = 0),
            song("Music/d.mp3", title = "Copper Moon", durationMs = 300_000),
        )

        assertEquals(listOf("Copper Moon", "Tide Bell", "Low Water", "Glass Steps"), titles(sortSongs(songs, SortSongs.DURATION, settings)))
        assertEquals(listOf("Low Water", "Copper Moon", "Tide Bell", "Glass Steps"), titles(sortSongs(songs, SortSongs.DURATION, settings, reversed = true)))
    }

    @Test
    fun `reversed, artist and album go Z to A with each one's songs still in their order and songs without the tag last`() {
        val lantern = song("Music/a/1.mp3", title = "Paper Lantern", artist = "Kit Calloway", album = "Harbour Glass", year = 2020, track = 1)
        val bell = song("Music/a/2.mp3", title = "Tide Bell", artist = "Kit Calloway", album = "Harbour Glass", year = 2020, track = 2)
        val water = song("Music/b/1.mp3", title = "Low Water", artist = "Mira Solen", album = "Night Ferry", year = 2018, track = 1)
        val steps = song("Music/c/1.mp3", title = "Glass Steps", artist = null, album = null)
        val songs = listOf(steps, bell, water, lantern)

        assertEquals(listOf("Low Water", "Paper Lantern", "Tide Bell", "Glass Steps"), titles(sortSongs(songs, SortSongs.ARTIST, settings, reversed = true)))
        assertEquals(listOf("Low Water", "Paper Lantern", "Tide Bell", "Glass Steps"), titles(sortSongs(songs, SortSongs.ALBUM, settings, reversed = true)))
        assertEquals(listOf("Paper Lantern", "Tide Bell", "Low Water", "Glass Steps"), titles(sortSongs(songs, SortSongs.ALBUM, settings)))
    }

    @Test
    fun `songs with one title keep their path order, either way round`() {
        val first = song("Music/a/Echo.mp3", title = "Echo")
        val second = song("Music/b/Echo.mp3", title = "Echo")
        val other = song("Music/c/Ash.mp3", title = "Ash")

        assertEquals(listOf(other, first, second), sortSongs(listOf(first, other, second), SortSongs.TITLE, settings))
        assertEquals(listOf(first, second, other), sortSongs(listOf(first, other, second), SortSongs.TITLE, settings, reversed = true))
    }

    // Albums and artists

    @Test
    fun `albums sort by when their last song came, their plays, their last play and their length, each either way`() {
        val lantern = song("Music/a/1.mp3", album = "Harbour Glass", artist = "Kit Calloway", addedAt = 100, durationMs = 200_000)
        val bell = song("Music/a/2.mp3", album = "Harbour Glass", artist = "Kit Calloway", addedAt = 500, durationMs = 200_000)
        val water = song("Music/b/1.mp3", album = "Night Ferry", artist = "Mira Solen", addedAt = 300, durationMs = 500_000)
        val moon = song("Music/c/1.mp3", album = "Singles", artist = "Odo Brannick", addedAt = null, durationMs = 0)
        val albums = Library.of(listOf(lantern, bell, water, moon), Settings()).albums
        val counts = mapOf(plays(lantern, 2, 40), plays(bell, 2, 10), plays(water, 3, 20))
        fun names(sort: SortAlbums, reversed: Boolean = false) = sortAlbums(albums, sort, reversed, counts).map { it.title }

        assertEquals(listOf("Harbour Glass", "Night Ferry", "Singles"), names(SortAlbums.RECENTLY_ADDED))
        assertEquals(listOf("Night Ferry", "Harbour Glass", "Singles"), names(SortAlbums.RECENTLY_ADDED, reversed = true))
        assertEquals(listOf("Harbour Glass", "Night Ferry", "Singles"), names(SortAlbums.PLAY_COUNT))
        assertEquals(listOf("Singles", "Night Ferry", "Harbour Glass"), names(SortAlbums.PLAY_COUNT, reversed = true))
        assertEquals(listOf("Harbour Glass", "Night Ferry", "Singles"), names(SortAlbums.LAST_PLAYED))
        assertEquals(listOf("Night Ferry", "Harbour Glass", "Singles"), names(SortAlbums.LAST_PLAYED, reversed = true))
        assertEquals(listOf("Night Ferry", "Harbour Glass", "Singles"), names(SortAlbums.DURATION))
        assertEquals(listOf("Harbour Glass", "Night Ferry", "Singles"), names(SortAlbums.DURATION, reversed = true))
        assertEquals(listOf("Singles", "Night Ferry", "Harbour Glass"), names(SortAlbums.TITLE, reversed = true))
    }

    @Test
    fun `artists sort by when their last song came, their plays and their last play, and the fewest songs first reversed`() {
        val lantern = song("Music/a/1.mp3", artist = "Kit Calloway", addedAt = 100)
        val bell = song("Music/a/2.mp3", artist = "Kit Calloway", addedAt = 500)
        val water = song("Music/b/1.mp3", artist = "Mira Solen", addedAt = 300)
        val moon = song("Music/c/1.mp3", artist = "Odo Brannick", addedAt = null)
        val artists = Library.of(listOf(lantern, bell, water, moon), Settings()).artists
        val counts = mapOf(plays(lantern, 1, 10), plays(bell, 1, 15), plays(water, 3, 20))
        fun names(sort: SortArtists, reversed: Boolean = false) = sortArtists(artists, sort, reversed, counts).map { it.name }

        assertEquals(listOf("Kit Calloway", "Mira Solen", "Odo Brannick"), names(SortArtists.RECENTLY_ADDED))
        assertEquals(listOf("Mira Solen", "Kit Calloway", "Odo Brannick"), names(SortArtists.PLAY_COUNT))
        assertEquals(listOf("Odo Brannick", "Kit Calloway", "Mira Solen"), names(SortArtists.PLAY_COUNT, reversed = true))
        assertEquals(listOf("Mira Solen", "Kit Calloway", "Odo Brannick"), names(SortArtists.LAST_PLAYED))
        assertEquals(listOf("Mira Solen", "Odo Brannick", "Kit Calloway"), names(SortArtists.MOST_SONGS, reversed = true))
        assertEquals(listOf("Odo Brannick", "Mira Solen", "Kit Calloway"), names(SortArtists.NAME, reversed = true))
    }
}
