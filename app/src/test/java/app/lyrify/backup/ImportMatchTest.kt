package app.lyrify.backup

import app.lyrify.library.Song
import app.lyrify.library.SongRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * An import's songs found on this phone: the path quirks of files other players and computers write ([M3u.matchPath],
 * [SongMatcher.relativeToRoot]), then [SongMatcher.find]'s passes, never a guess. Made-up names and paths only.
 */
class ImportMatchTest {
    private val lantern = at("/storage/emulated/0/Music/Harbour Glass/01 Paper Lantern.mp3", "Paper Lantern", "Kit Calloway", "Harbour Glass", 201_000, 4_100_000)
    private val bell = at("/storage/emulated/0/Music/Harbour Glass/02 Tide Bell.mp3", "Tide Bell", "Kit Calloway", "Harbour Glass", 185_000, 3_900_000)
    private val water = at("/storage/1A2B-3C4D/Music/Night Ferry/01 Low Water.mp3", "Low Water", "Mira Solen", "Night Ferry", 240_000, 5_000_000)
    private val road = at("/storage/emulated/0/Music/Rips/Night Ferry/02 Salt Road.mp3", "Salt Road", "Mira Solen", "Night Ferry", 199_000, 4_400_000)
    private val matcher = SongMatcher(listOf(lantern, bell, water, road))

    // Where a line points

    @Test
    fun `absolute paths, file URLs with one slash or two, and relative paths against the file's folder`() {
        assertEquals(lantern.path, M3u.matchPath("/storage/emulated/0/Music/Harbour Glass/./01 Paper Lantern.mp3", null))
        assertEquals("/sdcard/Music/a.mp3", M3u.matchPath("/sdcard/Music/a.mp3", null))
        assertEquals(lantern.path, M3u.matchPath("file:///storage/emulated/0/Music/Harbour%20Glass/01%20Paper%20Lantern.mp3", null))
        assertEquals(lantern.path, M3u.matchPath("file:/storage/emulated/0/Music/Harbour%20Glass/01%20Paper%20Lantern.mp3", null))
        assertEquals(lantern.path, M3u.matchPath("../Harbour Glass/01 Paper Lantern.mp3", "/storage/emulated/0/Music/Playlists"))
        assertEquals(lantern.path, M3u.matchPath("Harbour Glass\\01 Paper Lantern.mp3", "/storage/emulated/0/Music"))
    }

    @Test
    fun `Windows paths, servers' shares and relative paths without a folder keep their folders below an unknown root`() {
        assertEquals("Users/Kit/Music/Harbour Glass/01 Paper Lantern.mp3", M3u.matchPath("C:\\Users\\Kit\\Music\\Harbour Glass\\01 Paper Lantern.mp3", "/storage/emulated/0/Music"))
        assertEquals("Music/Harbour Glass/01 Paper Lantern.mp3", M3u.matchPath("D:/Music/Harbour Glass/01 Paper Lantern.mp3", null))
        assertEquals("Music/Harbour Glass/01 Paper Lantern.mp3", M3u.matchPath("file:///D:/Music/Harbour%20Glass/01%20Paper%20Lantern.mp3", null))
        assertEquals("nas/Music/Harbour Glass/01 Paper Lantern.mp3", M3u.matchPath("\\\\nas\\Music\\Harbour Glass\\01 Paper Lantern.mp3", null))
        assertEquals("Music/01 Paper Lantern.mp3", M3u.matchPath("\\Music\\01 Paper Lantern.mp3", null))
        assertEquals("Harbour Glass/01 Paper Lantern.mp3", M3u.matchPath("../Harbour Glass/01 Paper Lantern.mp3", null))
    }

    @Test
    fun `streams and content ids point nowhere, so only their tags can find them`() {
        assertNull(M3u.matchPath("https://radio.example/stream.mp3", "/storage/emulated/0/Music"))
        assertNull(M3u.matchPath("content://media/external/audio/media/42", null))
        assertNull(M3u.matchPath("   ", null))
    }

    @Test
    fun `the storage root's other names and an SD card's volume all come below the root`() {
        listOf(
            "/storage/emulated/0/Music/a.mp3",
            "/storage/emulated/10/Music/a.mp3",
            "/sdcard/Music/a.mp3",
            "/mnt/sdcard/Music/a.mp3",
            "/storage/self/primary/Music/a.mp3",
            "/storage/emulated/legacy/Music/a.mp3",
            "/storage/1A2B-3C4D/Music/a.mp3",
        ).forEach { assertEquals(it, "Music/a.mp3", SongMatcher.relativeToRoot(it)) }
        listOf("/storage/emulated/Music/a.mp3", "/storage/self/a.mp3", "/home/kit/Music/a.mp3", "Music/a.mp3", "/sdcard").forEach { assertNull(it, SongMatcher.relativeToRoot(it)) }
    }

    // Finding the song

    @Test
    fun `pass 1, the same path, taken as it is when the file says nothing else`() {
        assertEquals(lantern, matcher.find(hint(lantern.path)))
        // A song at that path whose tags say it is another one is not taken.
        assertNull(matcher.find(hint(lantern.path, title = "Glass Steps", artist = "Odo Brannick", durationMs = 175_000)))
    }

    @Test
    fun `a path is not taken when the line's own text names another song (review panel-im1, finding 2)`() {
        // An m3u line at Paper Lantern's path whose #EXTINF names Salt Road: the tags find Salt Road.
        assertEquals(road, matcher.find(hint(lantern.path, info = "Mira Solen - Salt Road", durationMs = 199_000)))
        // A pls line whose title names a song this phone does not have: none, never the file at that path.
        assertNull(matcher.find(hint(lantern.path, info = "Odo Brannick - Glass Steps")))
        // A title that agrees but an artist that does not: not that file either.
        assertNull(matcher.find(hint(lantern.path, title = "Paper Lantern", artist = "Odo Brannick")))
        // The text in any order, or the title alone, still agrees.
        assertEquals(lantern, matcher.find(hint(lantern.path, info = "Paper Lantern - Kit Calloway")))
        assertEquals(lantern, matcher.find(hint(lantern.path, info = "Paper Lantern", durationMs = 201_000)))
    }

    @Test
    fun `pass 2, the same path below the storage root, from another phone's root name or its SD card`() {
        assertEquals(lantern, matcher.find(hint("/sdcard/Music/Harbour Glass/01 Paper Lantern.mp3")))
        assertEquals(water, matcher.find(hint("/storage/emulated/0/Music/Night Ferry/01 Low Water.mp3")))
        assertEquals(water, matcher.find(hint("/storage/9999-0000/Music/Night Ferry/01 Low Water.mp3")))
        // Case and Unicode form aside.
        assertEquals(lantern, matcher.find(hint("/storage/self/primary/music/harbour glass/01 paper lantern.mp3")))
    }

    @Test
    fun `pass 2 for a root this phone does not know, the longest end of the path that is a song's path below its root`() {
        assertEquals(lantern, matcher.find(hint("C:/Users/Kit/Music/Harbour Glass/01 Paper Lantern.mp3".let { M3u.matchPath(it, null) })))
        assertEquals(road, matcher.find(hint("/home/kit/Music/Rips/Night Ferry/02 Salt Road.mp3")))
    }

    @Test
    fun `pass 3, the file name in a folder of the same name, anywhere`() {
        assertEquals(road, matcher.find(hint("D:/Other/Night Ferry/02 Salt Road.mp3".let { M3u.matchPath(it, null) })))
        assertEquals(bell, matcher.find(hint("Harbour Glass/02 Tide Bell.mp3")))
        // A length the file gives must agree within 2 s.
        assertNull(matcher.find(hint("Harbour Glass/02 Tide Bell.mp3", durationMs = 120_000)))
    }

    @Test
    fun `pass 4, title, artist and album when known, and a length within 2 s`() {
        assertEquals(water, matcher.find(hint(null, title = "low water", artist = "Mira Solen", durationMs = 241_500)))
        assertEquals(water, matcher.find(hint(null, title = "Low Water", durationMs = 239_000)))
        assertNull(matcher.find(hint(null, title = "Low Water", artist = "Mira Solen", durationMs = 245_000)))
        assertNull(matcher.find(hint(null, title = "Low Water", artist = "Mira Solen", album = "Harbour Glass", durationMs = 240_000)))
        // Without a length the tags alone are too weak.
        assertNull(matcher.find(hint(null, title = "Low Water", artist = "Mira Solen")))
    }

    @Test
    fun `an #EXTINF text is read two ways, the artist before the first dash or a title alone`() {
        assertEquals(road, matcher.find(hint(null, info = "Mira Solen - Salt Road", durationMs = 199_000)))
        val dashed = at("/storage/emulated/0/Music/Odd/Up - Down.mp3", "Up - Down", "Odo Brannick", null, 150_000, 1)
        assertEquals(dashed, SongMatcher(listOf(dashed, lantern)).find(hint(null, info = "Up - Down", durationMs = 150_000)))
    }

    @Test
    fun `never a guess, several songs in one pass give way to the next, and the size settles a tie`() {
        val copy = water.copy(ref = SongRef("/storage/emulated/0/Music/Night Ferry/01 Low Water.mp3", 99), sizeBytes = 5_100_000)
        val twice = SongMatcher(listOf(water, copy))
        // Below the root on both volumes, the same name in the same folder, the same tags: no pass finds one.
        assertNull(twice.find(hint("/sdcard/Music/Night Ferry/01 Low Water.mp3")))
        assertNull(twice.find(hint(null, title = "Low Water", artist = "Mira Solen", durationMs = 240_000)))
        // The exact path still finds its own, and the size tells the tags apart.
        assertEquals(copy, twice.find(hint(copy.path)))
        assertEquals(copy, twice.find(hint(null, title = "Low Water", artist = "Mira Solen", durationMs = 240_000, sizeBytes = 5_100_000)))
    }

    @Test
    fun `two lines naming one song both find it, unlike a backup's songs`() {
        assertEquals(lantern, matcher.find(hint(lantern.path)))
        assertEquals(lantern, matcher.find(hint("/sdcard/Music/Harbour Glass/01 Paper Lantern.mp3", info = "Kit Calloway - Paper Lantern", durationMs = 201_000)))
    }

    @Test
    fun `pass 5, a song moved to another folder is found by a file name only one song has, case aside, and never by a shared one`() {
        // Moved since the file was made: another folder, another root, no pass 0 to 4 finds it.
        val moved = hint("/storage/emulated/0/Download/Old Rips/02 SALT ROAD.MP3")
        assertNull(matcher.find(moved))
        assertEquals(road, matcher.byUniqueName(moved))
        assertEquals(road, matcher.byUniqueName(hint("Old Rips/02 Salt Road.mp3")))

        // Two songs of the phone with that name: neither.
        val copy = water.copy(ref = SongRef("/storage/emulated/0/Music/Night Ferry/01 Low Water.mp3", 99))
        assertNull(SongMatcher(listOf(water, copy)).byUniqueName(hint("/storage/emulated/0/Download/01 Low Water.mp3")))
        // The name alone is not the extension's, and a line's own clues still have their say.
        assertNull(matcher.byUniqueName(hint("/storage/emulated/0/Download/02 Salt Road.flac")))
        assertNull(matcher.byUniqueName(hint("/storage/emulated/0/Download/02 Salt Road.mp3", info = "Odo Brannick - Glass Steps")))
        assertNull(matcher.byUniqueName(hint("/storage/emulated/0/Download/02 Salt Road.mp3", durationMs = 90_000)))
        assertNull(matcher.byUniqueName(hint(null, title = "Salt Road", durationMs = 199_000)))
    }

    @Test
    fun `a backup still finds its songs as before`() {
        val known = mapOf(lantern.path to BackupSong(lantern.path, "Paper Lantern", "Kit Calloway", "Harbour Glass", 201_000))
        assertEquals(mapOf(lantern.path to lantern), matcher.match(listOf(lantern.path), known))
    }

    private fun hint(path: String?, title: String? = null, artist: String? = null, album: String? = null, durationMs: Long? = null, sizeBytes: Long? = null, info: String? = null) =
        ImportSong(path ?: "", path, title, artist, album, durationMs, sizeBytes, info)

    private fun at(path: String, title: String, artist: String?, album: String?, durationMs: Long, size: Long): Song {
        val relative = SongMatcher.relativeToRoot(path) ?: path.trimStart('/')
        return Song(
            ref = SongRef(path, path.hashCode().toLong()),
            title = title,
            artist = artist,
            album = album,
            albumArtist = null,
            durationMs = durationMs,
            track = null,
            disc = null,
            year = null,
            genre = null,
            folder = relative.substringBeforeLast('/') + "/",
            addedAt = null,
            modifiedAt = null,
            sizeBytes = size,
            mimeType = "audio/mpeg",
        )
    }
}
