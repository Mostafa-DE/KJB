package app.lyrify.library

import app.lyrify.settings.LibrarySettings
import app.lyrify.settings.PlaybackSettings
import app.lyrify.settings.Settings
import app.lyrify.settings.SortAlbums
import app.lyrify.settings.SortArtists
import app.lyrify.settings.SortSongs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The library built from MediaStore's songs: albums, artists, folders, genres, sorts and the user's filters. Made-up names only. */
class LibraryTest {
    private fun library(vararg songs: Song, settings: Settings = Settings()): Library = Library.of(songs.toList(), settings)

    private fun titles(songs: List<Song>): List<String> = songs.map { it.title }

    // Albums

    @Test
    fun `songs with one album tag and album artist make one album across CD1 and CD2 folders, in disc and track order`() {
        val lib = library(
            song("Music/Harbour Glass/CD2/01 Tide Bell.mp3", album = "Harbour Glass", albumArtist = "Kit Calloway", disc = 2, track = 1),
            song("Music/Harbour Glass/CD1/02 Salt Road.mp3", album = "Harbour Glass", albumArtist = "Kit Calloway", disc = 1, track = 2),
            song("Music/Harbour Glass/CD1/01 Paper Lantern.mp3", album = "Harbour Glass", albumArtist = "Kit Calloway", disc = 1, track = 1),
        )

        val album = lib.albums.single()
        assertEquals("Harbour Glass", album.title)
        assertEquals("Kit Calloway", album.artist)
        assertEquals(listOf("01 Paper Lantern", "02 Salt Road", "01 Tide Bell"), titles(album.songs))
        assertSame(album, lib.album(album.key))
        assertEquals(album.key, albumKey(album.songs[2]))
    }

    @Test
    fun `without disc tags, the CD2 folder's songs follow CD1's`() {
        val lib = library(
            song("Music/Harbour Glass/CD2/01 Tide Bell.mp3", album = "Harbour Glass", track = 1),
            song("Music/Harbour Glass/CD1/02 Salt Road.mp3", album = "Harbour Glass", track = 2),
            song("Music/Harbour Glass/CD2/02 Low Water.mp3", album = "Harbour Glass", track = 2),
            song("Music/Harbour Glass/CD1/01 Paper Lantern.mp3", album = "Harbour Glass", track = 1),
        )

        assertEquals(listOf("01 Paper Lantern", "02 Salt Road", "01 Tide Bell", "02 Low Water"), titles(lib.albums.single().songs))
    }

    @Test
    fun `a song without an album artist joins the album by its artist`() {
        val lib = library(
            song("Music/A/01.mp3", album = "Harbour Glass", albumArtist = "Kit Calloway", artist = "Kit Calloway"),
            song("Music/A/02.mp3", album = "Harbour Glass", albumArtist = null, artist = "Kit Calloway"),
        )

        assertEquals(1, lib.albums.size)
        assertEquals(2, lib.albums.single().songs.size)
    }

    @Test
    fun `different album artists never merge, and without album artists each artist has its own album`() {
        val lib = library(
            song("Music/A/01.mp3", album = "Greatest Hits", albumArtist = "Kit Calloway"),
            song("Music/B/01.mp3", album = "Greatest Hits", albumArtist = "Mira Solen"),
            song("Music/C/01.mp3", album = "Night Ferry", albumArtist = null, artist = "Kit Calloway"),
            song("Music/C/02.mp3", album = "Night Ferry", albumArtist = null, artist = "Mira Solen"),
        )

        assertEquals(4, lib.albums.size)
        assertEquals(setOf("Kit Calloway", "Mira Solen"), lib.albums.filter { it.title == "Greatest Hits" }.map { it.artist }.toSet())
        assertEquals(4, lib.albums.map { it.key }.toSet().size)
    }

    @Test
    fun `a compilation by one album artist stays one album whatever its songs' artists`() {
        val lib = library(
            song("Music/Mix/01.mp3", album = "Switchyard Lullabies", albumArtist = "Various Artists", artist = "Kit Calloway", track = 1),
            song("Music/Mix/02.mp3", album = "Switchyard Lullabies", albumArtist = "Various Artists", artist = "Mira Solen", track = 2),
        )

        assertEquals("Various Artists", lib.albums.single().artist)
        assertEquals(2, lib.albums.single().songs.size)
    }

    @Test
    fun `MediaStore's album id is never trusted for grouping`() {
        val lib = library(
            // One id, two albums.
            song("Music/A/01.mp3", album = "Harbour Glass", albumArtist = "Kit Calloway", albumId = 7),
            song("Music/B/01.mp3", album = "Night Ferry", albumArtist = "Kit Calloway", albumId = 7),
            // Two ids (MediaStore splits by folder without an album artist), one album.
            song("Music/C/CD1/01.mp3", album = "Low Water", artist = "Mira Solen", albumId = 8),
            song("Music/C/CD2/01.mp3", album = "Low Water", artist = "Mira Solen", albumId = 9),
        )

        assertEquals(listOf("Harbour Glass", "Low Water", "Night Ferry"), lib.albums.map { it.title })
        assertEquals(2, lib.album(checkNotNull(albumKey(lib.songs[2])))?.songs?.size)
    }

    @Test
    fun `album, artist and song keys stay unique when copies share MediaStore's album id and two artists share an album name`() {
        val lib = library(
            // A copy in a test folder gets the original's album id; a catalogue number makes another album.
            song("Music/Harbour Glass/01 Tide Bell.mp3", album = "Harbour Glass", albumArtist = "Kit Calloway", id = 1, albumId = 7),
            song("Test/Harbour Glass/01 Tide Bell.mp3", album = "Harbour Glass", albumArtist = "Kit Calloway", id = 2, albumId = 7),
            song("Test/Harbour Glass XE/01 Tide Bell.mp3", album = "Harbour Glass [XE-0042]", albumArtist = "Kit Calloway", id = 3, albumId = 7),
            // One album name, two album artists.
            song("Music/Mira/01 Low Water.mp3", album = "Night Ferry", artist = "Mira Solen", id = 4, albumId = 8),
            song("Music/Oren/01 Low Water.mp3", album = "Night Ferry", artist = "Oren Vale", id = 5, albumId = 8),
        )

        assertEquals(4, lib.albums.size)
        assertEquals(lib.albums.size, lib.albums.map { it.key }.toSet().size)
        // The copy joins its original's album as a song of its own, keyed by its own path.
        val glass = lib.albums.single { it.title == "Harbour Glass" }
        assertEquals(2, glass.songs.size)
        assertEquals(2, glass.songs.map { it.path }.toSet().size)
        assertEquals(lib.songs.size, lib.songs.map { it.path }.toSet().size)
        assertEquals(lib.artists.size, lib.artists.map { it.name }.toSet().size)
        lib.artists.forEach { artist -> assertEquals(artist.albums.size, artist.albums.map { it.key }.toSet().size) }
    }

    @Test
    fun `songs without an album tag go to Unknown album, never to an album`() {
        val lib = library(
            song("Music/Loose/a.mp3", album = null),
            song("Music/Loose/b.mp3", album = "   "),
            song("Music/A/01.mp3", album = "Harbour Glass"),
        )

        assertEquals(listOf("a", "b"), titles(lib.unknownAlbum))
        assertEquals(listOf("Harbour Glass"), lib.albums.map { it.title })
        assertNull(albumKey(lib.unknownAlbum[0]))
        assertNull(albumKey(lib.unknownAlbum[1]))
    }

    @Test
    fun `an album without any artist is still an album, with no artist`() {
        val lib = library(song("Music/A/01.mp3", album = "Harbour Glass", artist = null, albumArtist = null))

        assertNull(lib.albums.single().artist)
    }

    @Test
    fun `an album's year is the one most of its songs have, the latest of equals, and its length is its songs'`() {
        val lib = library(
            song("Music/A/01.mp3", album = "Harbour Glass", year = 2019, durationMs = 100_000),
            song("Music/A/02.mp3", album = "Harbour Glass", year = 2021, durationMs = 120_000),
            song("Music/A/03.mp3", album = "Harbour Glass", year = 2021, durationMs = 130_000),
            song("Music/A/04.mp3", album = "Harbour Glass", year = null, durationMs = 150_000),
            song("Music/B/01.mp3", album = "Night Ferry", year = 2018),
            song("Music/B/02.mp3", album = "Night Ferry", year = 2020),
            song("Music/C/01.mp3", album = "Low Water", year = null),
        )

        assertEquals(2021, lib.albums.first { it.title == "Harbour Glass" }.year)
        assertEquals(500_000L, lib.albums.first { it.title == "Harbour Glass" }.durationMs)
        assertEquals(2020, lib.albums.first { it.title == "Night Ferry" }.year)
        assertNull(lib.albums.first { it.title == "Low Water" }.year)
    }

    // Artists and genres

    @Test
    fun `artists list song artists with their songs, newest album first, and the albums they are the album artist of`() {
        val lib = library(
            song("Music/Old/01 Paper Lantern.mp3", album = "Harbour Glass", year = 2019, track = 1),
            song("Music/New/02 Low Water.mp3", album = "Night Ferry", year = 2023, track = 2),
            song("Music/New/01 Tide Bell.mp3", album = "Night Ferry", year = 2023, track = 1),
            song("Music/Loose/Salt Road.mp3", album = null),
            song("Music/Duet/01 Two Lights.mp3", artist = "Mira Solen", album = "Harbour Glass Live", albumArtist = "Kit Calloway", year = 2021),
            song("Music/Loose/Nobody.mp3", artist = null),
        )

        assertEquals(listOf("Kit Calloway", "Mira Solen"), lib.artists.map { it.name })
        val kit = checkNotNull(lib.artist("Kit Calloway"))
        assertEquals(listOf("Night Ferry", "Harbour Glass Live", "Harbour Glass"), kit.albums.map { it.title })
        assertEquals(listOf("01 Tide Bell", "02 Low Water", "01 Paper Lantern", "Salt Road"), titles(kit.songs))
        assertEquals(listOf("01 Two Lights"), titles(checkNotNull(lib.artist("Mira Solen")).songs))
        assertEquals(emptyList<Album>(), checkNotNull(lib.artist("Mira Solen")).albums)
        assertNull(lib.artist("Nobody"))
    }

    @Test
    fun `genres hold their songs, songs without one are in none`() {
        val lib = library(
            song("Music/A/01.mp3", genre = "Folk"),
            song("Music/A/02.mp3", genre = "Ambient"),
            song("Music/A/03.mp3", genre = "Folk"),
            song("Music/A/04.mp3", genre = null),
        )

        assertEquals(listOf("Ambient", "Folk"), lib.genres.map { it.name })
        assertEquals(listOf("01", "03"), titles(checkNotNull(lib.genre("Folk")).songs))
    }

    // Folders

    @Test
    fun `the folder list starts at the top folders and a chain of single subfolders collapses into one row`() {
        val lib = library(
            song("Music/Rips/2024/Vinyl/01.mp3"),
            song("Music/Rips/2024/Vinyl/02.mp3"),
            song("Recordings/Live/a.mp3"),
            song("Recordings/Studio/b.mp3"),
        )

        val rows = lib.root.rows
        assertEquals(listOf(listOf("Music", "Rips", "2024", "Vinyl"), listOf("Recordings")), rows.map { it.names })
        assertEquals("Music/Rips/2024/Vinyl", rows[0].folder.path)
        assertEquals(2, rows[0].folder.songs.size)
        assertEquals(listOf(listOf("Live"), listOf("Studio")), rows[1].folder.rows.map { it.names })
    }

    @Test
    fun `a folder with songs of its own never collapses into its subfolder`() {
        val lib = library(song("Music/top.mp3"), song("Music/Rips/2024/01.mp3"))

        assertEquals(listOf(listOf("Music")), lib.root.rows.map { it.names })
        assertEquals(listOf(listOf("Rips", "2024")), checkNotNull(lib.folder("Music")).rows.map { it.names })
    }

    @Test
    fun `a folder plays its own songs by file name, then each subfolder's by name, in path order`() {
        val lib = library(
            song("Music/Rips/b.mp3"),
            song("Music/Rips/Zeta/c.mp3"),
            song("Music/Rips/Alpha/z.mp3"),
            song("Music/Rips/a.mp3"),
            song("Music/Rips/Alpha/y.mp3"),
        )

        val rips = checkNotNull(lib.folder("Music/Rips"))
        assertEquals(listOf("a", "b", "y", "z", "c"), titles(rips.allSongs))
        assertEquals("Rips", rips.name)
        assertEquals(5, lib.root.allSongs.size)
        assertNull(lib.folder("Music/Nowhere"))
    }

    // Filters

    @Test
    fun `short clips leave the library unless Skip short clips is off, but can still be found again`() {
        val clip = song("Music/A/clip.mp3", durationMs = 29_999)
        val songs = arrayOf(clip, song("Music/A/thirty.mp3", durationMs = 30_000), song("Music/A/unknown.mp3", durationMs = 0))

        val lib = library(*songs)
        assertEquals(listOf("thirty", "unknown"), titles(lib.songs))
        assertEquals(3, lib.all.size)
        assertFalse(lib.shows(clip))
        assertNull(lib.song(clip.path))
        assertEquals(clip, lib.resolver.find(clip.ref))

        val off = Settings(playback = PlaybackSettings(skipShortClips = false))
        assertEquals(3, library(*songs, settings = off).songs.size)
        assertTrue(library(*songs, settings = off).shows(clip))
        assertFalse(inLibrary(clip, Settings()))
        assertTrue(inLibrary(clip, off))
    }

    @Test
    fun `folders to skip take their subfolders too, whatever the case, but not a folder that only starts alike`() {
        val settings = Settings(library = LibrarySettings(foldersToSkip = listOf("Music/Voice Notes")))
        val lib = library(
            song("Music/Voice Notes/a.mp3"),
            song("Music/voice notes/Old/b.mp3"),
            song("Music/Voice Notes 2/c.mp3"),
            song("Music/d.mp3"),
            settings = settings,
        )

        // In path order: a folder's own songs before its subfolders'.
        assertEquals(listOf("d", "c"), titles(lib.songs))
        assertEquals(listOf(listOf("Music")), lib.root.rows.map { it.names })
        assertNull(lib.folder("Music/Voice Notes"))
    }

    @Test
    fun `hidden songs are in no album, artist, folder or genre`() {
        val lib = library(song("Music/A/clip.mp3", album = "Harbour Glass", genre = "Folk", durationMs = 5_000))

        assertEquals(emptyList<Album>(), lib.albums)
        assertEquals(emptyList<Artist>(), lib.artists)
        assertEquals(emptyList<Genre>(), lib.genres)
        assertEquals(emptyList<FolderRow>(), lib.root.rows)
    }

    @Test
    fun `listed songs are the refs found again in their order, without missing or hidden ones`() {
        val a = song("Music/A/a.mp3", id = 1)
        val b = song("Music/A/b.mp3", id = 2)
        val clip = song("Music/A/clip.mp3", id = 3, durationMs = 1_000)
        val lib = library(a, b, clip)

        val refs = listOf(b.ref, SongRef(VOLUME + "Music/Gone/x.mp3"), clip.ref, a.ref, b.ref)
        assertEquals(listOf(b, a, b), lib.listed(refs))
    }

    // Titles and sorts

    @Test
    fun `Show file names puts the file name in place of the title`() {
        val s = song("Music/A/01 paper_lantern.flac", title = "Paper Lantern")

        assertEquals("Paper Lantern", displayTitle(s, LibrarySettings()))
        assertEquals("01 paper_lantern.flac", displayTitle(s, LibrarySettings(showFileNames = true)))
    }

    @Test
    fun `songs sort by title, artist, album or recently added`() {
        val songs = listOf(
            song("Music/1.mp3", title = "Tide Bell", artist = "Mira Solen", album = "Night Ferry", track = 2, year = 2020, addedAt = 300),
            song("Music/2.mp3", title = "paper Lantern", artist = "Kit Calloway", album = "Harbour Glass", track = 1, year = 2019, addedAt = 100),
            song("Music/3.mp3", title = "Low Water", artist = "Kit Calloway", album = "Night Ferry", track = 1, year = 2020, addedAt = null),
            song("Music/4.mp3", title = "Ébb", artist = null, album = null, addedAt = 200),
        )
        val settings = LibrarySettings()

        assertEquals(listOf("Ébb", "Low Water", "paper Lantern", "Tide Bell"), titles(sortSongs(songs, SortSongs.TITLE, settings)))
        // By artist (no artist last), then the newest year, then album, then track.
        assertEquals(listOf("Low Water", "paper Lantern", "Tide Bell", "Ébb"), titles(sortSongs(songs, SortSongs.ARTIST, settings)))
        // By album (Unknown album last), then track.
        assertEquals(listOf("paper Lantern", "Low Water", "Tide Bell", "Ébb"), titles(sortSongs(songs, SortSongs.ALBUM, settings)))
        // Newest first; songs MediaStore gave no date last.
        assertEquals(listOf("Tide Bell", "Ébb", "paper Lantern", "Low Water"), titles(sortSongs(songs, SortSongs.RECENTLY_ADDED, settings)))
    }

    @Test
    fun `with file names shown, the title sort goes by file name`() {
        val songs = listOf(song("Music/b.mp3", title = "Alpha"), song("Music/a.mp3", title = "Beta"))

        assertEquals(listOf("Alpha", "Beta"), titles(sortSongs(songs, SortSongs.TITLE, LibrarySettings())))
        assertEquals(listOf("Beta", "Alpha"), titles(sortSongs(songs, SortSongs.TITLE, LibrarySettings(showFileNames = true))))
    }

    @Test
    fun `albums sort by title, artist or newest year`() {
        val lib = library(
            song("Music/1.mp3", album = "Night Ferry", albumArtist = "Mira Solen", year = 2018),
            song("Music/2.mp3", album = "harbour Glass", albumArtist = "Kit Calloway", year = 2023),
            song("Music/3.mp3", album = "Low Water", albumArtist = "Kit Calloway", year = null),
            song("Music/4.mp3", album = "Ash Tree", albumArtist = null, artist = null, year = 2020),
        )

        assertEquals(listOf("Ash Tree", "harbour Glass", "Low Water", "Night Ferry"), sortAlbums(lib.albums, SortAlbums.TITLE).map { it.title })
        assertEquals(listOf("harbour Glass", "Low Water", "Night Ferry", "Ash Tree"), sortAlbums(lib.albums, SortAlbums.ARTIST).map { it.title })
        assertEquals(listOf("harbour Glass", "Ash Tree", "Night Ferry", "Low Water"), sortAlbums(lib.albums, SortAlbums.YEAR).map { it.title })
        assertEquals(lib.albums, sortAlbums(lib.albums, SortAlbums.TITLE))
    }

    @Test
    fun `artists sort by name or most songs`() {
        val lib = library(
            song("Music/1.mp3", artist = "mira Solen"),
            song("Music/2.mp3", artist = "Kit Calloway"),
            song("Music/3.mp3", artist = "Ash Tree"),
            song("Music/4.mp3", artist = "mira Solen"),
            song("Music/5.mp3", artist = "Kit Calloway"),
        )

        assertEquals(listOf("Ash Tree", "Kit Calloway", "mira Solen"), sortArtists(lib.artists, SortArtists.NAME).map { it.name })
        assertEquals(listOf("Kit Calloway", "mira Solen", "Ash Tree"), sortArtists(lib.artists, SortArtists.MOST_SONGS).map { it.name })
        assertEquals(lib.artists, sortArtists(lib.artists, SortArtists.NAME))
    }

    @Test
    fun `album keys tell albums apart whatever their names hold`() {
        val one = albumKey(song("Music/1.mp3", album = "B", albumArtist = "A, C"))
        val two = albumKey(song("Music/2.mp3", album = "C, B", albumArtist = "A"))

        assertNotEquals(one, two)
        assertEquals(one, albumKey(song("Music/3.mp3", album = " B ", albumArtist = "A, C")))
    }
}
