package app.lyrify.backup

import app.lyrify.data.SharedWrites
import app.lyrify.library.SongRef
import app.lyrify.library.SongResolver
import app.lyrify.library.VOLUME
import app.lyrify.library.song
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.nio.charset.Charset
import java.nio.file.Files

/** Playlists out to m3u files and back: writing, reading, finding the songs again, and naming. Made-up names only. */
class M3uTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val lantern = song("Music/Harbour Glass/01 Paper Lantern.mp3", title = "Paper Lantern", artist = "Kit Calloway", durationMs = 201_400, id = 11)
    private val bell = song("Music/Harbour Glass/02 Tide Bell.mp3", title = "Tide Bell", artist = null, durationMs = 179_500, id = 12)
    private val ferry = song("Music/Night Ferry/01 Low Water.mp3", title = "Low Water", artist = "Mira Solen", durationMs = 240_000, id = 13)
    private val resolver = SongResolver(listOf(lantern, bell, ferry))

    // Writing

    @Test
    fun `export writes extended M3U in order, a song twice too, each with its length in seconds, artist, title and path`() {
        assertEquals(
            "#EXTM3U\n" +
                "#EXTINF:201,Kit Calloway - Paper Lantern\n" +
                "/storage/emulated/0/Music/Harbour Glass/01 Paper Lantern.mp3\n" +
                "#EXTINF:180,Tide Bell\n" +
                "/storage/emulated/0/Music/Harbour Glass/02 Tide Bell.mp3\n" +
                "#EXTINF:201,Kit Calloway - Paper Lantern\n" +
                "/storage/emulated/0/Music/Harbour Glass/01 Paper Lantern.mp3\n",
            M3u.write(listOf(lantern.ref, bell.ref, lantern.ref), resolver),
        )
    }

    @Test
    fun `a song no longer on the phone keeps its stored path, and one found by its id hint is written where it is now`() {
        val gone = SongRef(VOLUME + "Music/Gone/03 Salt Road.mp3")
        val renamed = SongRef(VOLUME + "Music/Harbour Glass/old name.mp3", 11)

        assertEquals(
            "#EXTM3U\n" +
                "/storage/emulated/0/Music/Gone/03 Salt Road.mp3\n" +
                "#EXTINF:201,Kit Calloway - Paper Lantern\n" +
                "/storage/emulated/0/Music/Harbour Glass/01 Paper Lantern.mp3\n",
            M3u.write(listOf(gone, renamed), resolver),
        )
        assertEquals("#EXTM3U\n", M3u.write(emptyList(), resolver))
    }

    @Test
    fun `an unknown length is -1, and a line break in a tag never breaks the file`() {
        val odd = song("Music/Night Ferry/02 Grey Pier.mp3", title = "Grey\nPier", artist = "Mira\r\nSolen", durationMs = 0)

        assertEquals(
            "#EXTM3U\n#EXTINF:-1,Mira Solen - Grey Pier\n/storage/emulated/0/Music/Night Ferry/02 Grey Pier.mp3\n",
            M3u.write(listOf(odd.ref), SongResolver(listOf(odd))),
        )
    }

    @Test
    fun `paths are written on one line too, without line or paragraph breaks or other controls`() {
        val odd = song("Music/Night Ferry/02 Grey\u2028Pier\u0085.mp3", title = "Grey\u2029Pier", artist = null, durationMs = 90_000)
        val gone = SongRef(VOLUME + "Music/Gone/03 Salt\r\nRoad\u0007.mp3")

        assertEquals(
            "#EXTM3U\n#EXTINF:90,Grey Pier\n/storage/emulated/0/Music/Night Ferry/02 Grey Pier .mp3\n/storage/emulated/0/Music/Gone/03 Salt Road .mp3\n",
            M3u.write(listOf(odd.ref, gone), SongResolver(listOf(odd))),
        )
    }

    @Test
    fun `the file is named after the playlist, without characters a folder cannot hold`() {
        assertEquals("Road Trip.m3u8", M3u.fileName("Road Trip", fallback = "Playlist"))
        assertEquals("Night_Drive_ Part 2_.m3u8", M3u.fileName("Night/Drive: Part 2?", fallback = "Playlist"))
        assertEquals("a_b_c_d_e_f_g_h.m3u8", M3u.fileName("a\\b*c\"d<e>f|g\u0007h", fallback = "Playlist"))
        assertEquals("Playlist.m3u8", M3u.fileName("   ", fallback = "Playlist"))
    }

    @Test
    fun `export all names each file apart within the folder, case aside`() {
        assertEquals(
            listOf("Night_Drive.m3u8", "Night_Drive (2).m3u8", "night_drive (3).m3u8", "Road Trip.m3u8", "Playlist.m3u8", "Playlist (2).m3u8"),
            M3u.fileNames(listOf("Night/Drive", "Night:Drive", "night?drive", "Road Trip", " ", ""), fallback = "Playlist"),
        )
    }

    // Reading

    @Test
    fun `reads M3U8 with a BOM and CRLF line ends, each path with the #EXTINF before it`() {
        val text = "\uFEFF#EXTM3U\r\n" +
            "#EXTINF:201,Kit Calloway - Paper Lantern\r\n" +
            "/storage/emulated/0/Music/Harbour Glass/01 Paper Lantern.mp3\r\n" +
            "\r\n" +
            "#EXTINF:-1,Mira Solen - Low Water\r\n" +
            "Night Ferry/01 Low Water.mp3\r\n"

        assertEquals(
            listOf(
                M3uEntry("/storage/emulated/0/Music/Harbour Glass/01 Paper Lantern.mp3", "Kit Calloway - Paper Lantern", 201_000),
                M3uEntry("Night Ferry/01 Low Water.mp3", "Mira Solen - Low Water", null),
            ),
            M3u.read(text.toByteArray(Charsets.UTF_8), "Road Trip.m3u8"),
        )
    }

    @Test
    fun `reads plain M3U without a header, with old Mac line ends, and skips blank lines and other comments`() {
        val text = "# made by hand\r/storage/emulated/0/Music/Harbour Glass/01 Paper Lantern.mp3\r\r  Harbour Glass\\02 Tide Bell.mp3  \r#EXTALB:Harbour Glass\r"

        assertEquals(
            listOf(M3uEntry("/storage/emulated/0/Music/Harbour Glass/01 Paper Lantern.mp3"), M3uEntry("Harbour Glass\\02 Tide Bell.mp3")),
            M3u.read(text.toByteArray(Charsets.UTF_8), "Mix.m3u"),
        )
    }

    @Test
    fun `an #EXTINF names only the next path, through comments between them`() {
        val text = "#EXTM3U\n#EXTINF:201,Kit Calloway - Paper Lantern\n#EXTALB:Harbour Glass\n/a/Paper Lantern.mp3\n/a/Tide Bell.mp3\n"

        assertEquals(
            listOf(M3uEntry("/a/Paper Lantern.mp3", "Kit Calloway - Paper Lantern", 201_000), M3uEntry("/a/Tide Bell.mp3")),
            M3u.read(text.toByteArray(Charsets.UTF_8), "Mix.m3u8"),
        )
    }

    @Test
    fun `in an #EXTINF, 0 and -1 are no length, decimals and attributes are read, and a missing name is none`() {
        val text = "#EXTINF:0,Kit Calloway - Paper Lantern\n/a/1.mp3\n" +
            "#EXTINF:201.6,Kit Calloway - Paper Lantern\n/a/2.mp3\n" +
            "#EXTINF:240 tvg-id=\"x,y\" group-title=\"Night Ferry\",Mira Solen - Low Water\n/a/3.mp3\n" +
            "#EXTINF:180,\n/a/4.mp3\n" +
            "#EXTINF:soon,Tide Bell\n/a/5.mp3\n"

        assertEquals(
            listOf(
                M3uEntry("/a/1.mp3", "Kit Calloway - Paper Lantern", null),
                M3uEntry("/a/2.mp3", "Kit Calloway - Paper Lantern", 201_600),
                M3uEntry("/a/3.mp3", "Mira Solen - Low Water", 240_000),
                M3uEntry("/a/4.mp3", null, 180_000),
                M3uEntry("/a/5.mp3", "Tide Bell", null),
            ),
            M3u.read(text.toByteArray(Charsets.UTF_8), "Mix.m3u8"),
        )
    }

    @Test
    fun `an #EXTINF length that is not a number of milliseconds a Long holds is none, and its name stays`() {
        val text = listOf("Infinity", "-Infinity", "NaN", "1e300", "9223372036854775807").joinToString("") { "#EXTINF:$it,Kit Calloway - Paper Lantern\n/a/$it.mp3\n" }

        assertEquals(
            listOf("Infinity", "-Infinity", "NaN", "1e300", "9223372036854775807").map { M3uEntry("/a/$it.mp3", "Kit Calloway - Paper Lantern", null) },
            M3u.read(text.toByteArray(Charsets.UTF_8), "Mix.m3u8"),
        )
    }

    @Test
    fun `a file with more than 10,000 entries or a line over 4 KB is too big`() {
        val lines = { count: Int -> (1..count).joinToString("\n") { "/a/$it.mp3" }.toByteArray(Charsets.UTF_8) }

        assertEquals(M3u.MAX_ENTRIES, M3u.read(lines(M3u.MAX_ENTRIES), "Mix.m3u8")?.size)
        assertNull(M3u.read(lines(M3u.MAX_ENTRIES + 1), "Mix.m3u8"))
        val longest = "/" + "a".repeat(M3u.MAX_LINE_CHARS - 1)
        assertEquals(listOf(M3uEntry(longest)), M3u.read(longest.toByteArray(Charsets.UTF_8), "Mix.m3u8"))
        assertNull(M3u.read("$longest\n#EXTINF:1,${"b".repeat(M3u.MAX_LINE_CHARS)}\n".toByteArray(Charsets.UTF_8), "Mix.m3u8"))
    }

    @Test
    fun `an m3u without a BOM but with NUL bytes is UTF-16, big-endian when the NULs come first`() {
        val text = "#EXTM3U\r\n#EXTINF:201,Kit Calloway - Paper Lantern\r\n/a/Café.mp3\r\n"
        val expected = listOf(M3uEntry("/a/Café.mp3", "Kit Calloway - Paper Lantern", 201_000))

        assertEquals(expected, M3u.read(text.toByteArray(Charsets.UTF_16LE), "Mix.m3u"))
        assertEquals(expected, M3u.read(text.toByteArray(Charsets.UTF_16BE), "Mix.m3u"))
    }

    @Test
    fun `an m3u in UTF-8 reads as UTF-8, one that is not reads as Latin-1 as Windows writes it`() {
        val text = "#EXTINF:201,Kit Calloway - Café Lights’ End\n/a/Café.mp3\n"
        val expected = listOf(M3uEntry("/a/Café.mp3", "Kit Calloway - Café Lights’ End", 201_000))

        assertEquals(expected, M3u.read(text.toByteArray(Charsets.UTF_8), "Mix.m3u"))
        assertEquals(expected, M3u.read(text.toByteArray(Charset.forName("windows-1252")), "Mix.m3u"))
        assertEquals(listOf(M3uEntry("/a/Café.mp3")), M3u.read("/a/Café.mp3".toByteArray(Charsets.ISO_8859_1), "Mix.M3U"))
    }

    @Test
    fun `an m3u8 is always UTF-8, never guessed`() {
        assertEquals(listOf(M3uEntry("/a/Caf\uFFFD.mp3")), M3u.read("/a/Café.mp3".toByteArray(Charsets.ISO_8859_1), "Mix.m3u8"))
    }

    @Test
    fun `UTF-16 files with their BOM read too`() {
        val text = "\uFEFF#EXTM3U\r\n#EXTINF:201,Kit Calloway - Paper Lantern\r\n/a/Café.mp3\r\n"
        val expected = listOf(M3uEntry("/a/Café.mp3", "Kit Calloway - Paper Lantern", 201_000))

        assertEquals(expected, M3u.read(text.toByteArray(Charsets.UTF_16LE), "Mix.m3u"))
        assertEquals(expected, M3u.read(text.toByteArray(Charsets.UTF_16BE), "Mix.m3u8"))
    }

    @Test
    fun `a file over 5 MB is not read`() {
        val limit = ByteArray(M3u.MAX_BYTES) { 'a'.code.toByte() }

        assertArrayEquals(limit, M3u.readBounded(ByteArrayInputStream(limit)))
        assertNull(M3u.readBounded(ByteArrayInputStream(limit + 'a'.code.toByte())))
    }

    @Test
    fun `the open dialog offers text files too, but only names ending in m3u or m3u8 are read`() {
        assertTrue("text/plain" in M3u.MIME_TYPES && "application/octet-stream" in M3u.MIME_TYPES && "audio/x-mpegurl" in M3u.MIME_TYPES)
        assertTrue(M3u.isM3u("Road Trip.m3u"))
        assertTrue(M3u.isM3u("Road Trip.M3U8 "))
        assertFalse(M3u.isM3u("Road Trip.txt"))
        assertFalse(M3u.isM3u("Road Trip.m3u.bak"))
        assertFalse(M3u.isM3u(null))
    }

    @Test
    fun `the playlist is named after the file`() {
        assertEquals("Road Trip", M3u.playlistName("Road Trip.m3u8"))
        assertEquals("Road Trip", M3u.playlistName(" Road Trip .M3U"))
        assertEquals("Road.Trip.txt", M3u.playlistName("Road.Trip.txt"))
        assertNull(M3u.playlistName(".m3u"))
        assertNull(M3u.playlistName(null))
    }

    // Paths

    @Test
    fun `absolute paths are taken with dot folders resolved`() {
        assertEquals(ferry.path, M3u.localPath("/storage/emulated/0/Music/Harbour Glass/../Night Ferry/./01 Low Water.mp3", folder = null))
        assertEquals("/storage/emulated/0/Music/100% Tin Roof.mp3", M3u.localPath("/storage/emulated/0/Music/100% Tin Roof.mp3", folder = null))
    }

    @Test
    fun `relative paths are resolved against the playlist's folder, backslashes too`() {
        val music = "/storage/emulated/0/Music"

        assertEquals(lantern.path, M3u.localPath("01 Paper Lantern.mp3", "$music/Harbour Glass"))
        assertEquals(ferry.path, M3u.localPath("../Night Ferry/01 Low Water.mp3", "$music/Playlists"))
        assertEquals(bell.path, M3u.localPath(".\\Harbour Glass\\02 Tide Bell.mp3", music))
        // Without the playlist's folder (a file from a provider without paths), the line is kept as written.
        assertEquals("Harbour Glass/01 Paper Lantern.mp3", M3u.localPath("Harbour Glass/01 Paper Lantern.mp3", folder = null))
    }

    @Test
    fun `file URLs are paths, other URLs are not, and Windows paths are never resolved against the folder`() {
        assertEquals(lantern.path, M3u.localPath("file:///storage/emulated/0/Music/Harbour%20Glass/01%20Paper%20Lantern.mp3", folder = null))
        assertEquals(lantern.path, M3u.localPath("file:///storage/emulated/0/Music/Harbour Glass/01 Paper Lantern.mp3", folder = null))
        // Everything after file:// is the path, ? and # too, percent-decoded; a % that starts no escape stays.
        assertEquals("/storage/emulated/0/Music/Is it #1? Yes.mp3", M3u.localPath("file:///storage/emulated/0/Music/Is it #1? Yes.mp3", folder = null))
        assertEquals("/storage/emulated/0/Music/Is it #1?.mp3", M3u.localPath("file:///storage/emulated/0/Music/Is%20it%20%231%3F.mp3", folder = null))
        assertEquals("/storage/emulated/0/Music/100% Café 50%.mp3", M3u.localPath("file:///storage/emulated/0/Music/100%25%20Caf%C3%A9%2050%.mp3", folder = null))
        assertNull(M3u.localPath("https://radio.example/stream.mp3", folder = "/storage/emulated/0/Music"))
        assertEquals("C:\\Music\\Harbour Glass\\01 Paper Lantern.mp3", M3u.localPath("C:\\Music\\Harbour Glass\\01 Paper Lantern.mp3", "/storage/emulated/0/Music"))
        assertEquals("\\\\nas\\Music\\01 Paper Lantern.mp3", M3u.localPath("\\\\nas\\Music\\01 Paper Lantern.mp3", "/storage/emulated/0/Music"))
    }

    // Matching

    @Test
    fun `rule 1, the absolute path`() {
        assertEquals(lantern, M3u.find(M3uEntry(lantern.path), resolver, folder = null))
        assertEquals(lantern, M3u.find(M3uEntry("01 Paper Lantern.mp3"), resolver, folder = "/storage/emulated/0/Music/Harbour Glass"))
    }

    @Test
    fun `rule 2, the file name with its parent folder, from another phone, a card or a computer`() {
        assertEquals(lantern, M3u.find(M3uEntry("/storage/emulated/0/Old Phone/Harbour Glass/01 Paper Lantern.mp3"), resolver, folder = null))
        assertEquals(lantern, M3u.find(M3uEntry("D:\\Rips\\harbour glass\\01 PAPER LANTERN.MP3"), resolver, folder = null))
        assertEquals(bell, M3u.find(M3uEntry("Harbour Glass/02 Tide Bell.mp3"), resolver, folder = null))
        // The same name in another folder is not the song.
        assertNull(M3u.find(M3uEntry("/storage/emulated/0/Music/Elsewhere/01 Paper Lantern.mp3"), resolver, folder = null))
    }

    @Test
    fun `rule 3, the #EXTINF title, artist and length, within 2 s`() {
        val at = { seconds: Long -> M3uEntry("/elsewhere/track07.flac", "Mira Solen - Low Water", seconds * 1000) }

        assertEquals(ferry, M3u.find(at(240), resolver, folder = null))
        assertEquals(ferry, M3u.find(at(238), resolver, folder = null))
        assertEquals(ferry, M3u.find(at(242), resolver, folder = null))
        assertNull(M3u.find(at(243), resolver, folder = null))
        assertNull(M3u.find(at(237), resolver, folder = null))
        // Case, accents and punctuation aside; the artist must agree, and a length is needed.
        assertEquals(ferry, M3u.find(M3uEntry("x.mp3", "MIRA SOLEN - Low Water!", 240_000), resolver, folder = null))
        assertNull(M3u.find(M3uEntry("x.mp3", "Kit Calloway - Low Water", 240_000), resolver, folder = null))
        assertNull(M3u.find(M3uEntry("x.mp3", "Mira Solen - Low Water", null), resolver, folder = null))
        // A song without an artist: the title alone.
        assertEquals(bell, M3u.find(M3uEntry("x.mp3", "Tide Bell", 180_000), resolver, folder = null))
        // A stream's URL is never a path, but its #EXTINF still counts.
        assertEquals(ferry, M3u.find(M3uEntry("https://radio.example/Night Ferry/01 Low Water.mp3", "Mira Solen - Low Water", 240_000), resolver, folder = null))
    }

    @Test
    fun `the rules run in order, the path before the name in its folder before the #EXTINF`() {
        // The path names one song, the #EXTINF another: the path wins.
        assertEquals(lantern, M3u.find(M3uEntry(lantern.path, "Mira Solen - Low Water", 240_000), resolver, folder = null))
        // The name in its folder names one song, the #EXTINF another: the name wins.
        assertEquals(bell, M3u.find(M3uEntry("/old/Harbour Glass/02 Tide Bell.mp3", "Mira Solen - Low Water", 240_000), resolver, folder = null))
        // The name is on the phone and on the card, so it names no one song: the #EXTINF decides.
        val cardCopy = song("Music/Harbour Glass/01 Paper Lantern.mp3", title = "Paper Lantern", artist = "Kit Calloway", durationMs = 150_000)
            .let { it.copy(ref = SongRef("/storage/1A2B-3C4D/Music/Harbour Glass/01 Paper Lantern.mp3", 21)) }
        val both = SongResolver(listOf(lantern, cardCopy, ferry))
        assertEquals(lantern, M3u.find(M3uEntry("/old/Harbour Glass/01 Paper Lantern.mp3", "Kit Calloway - Paper Lantern", 201_000), both, folder = null))
        assertNull(M3u.find(M3uEntry("/old/Harbour Glass/01 Paper Lantern.mp3"), both, folder = null))
    }

    @Test
    fun `an #EXTINF is read two ways only, the artist before the first dash, or a title without an artist`() {
        val live = song("Music/Harbour Glass/03 Paper Lantern - Live.mp3", title = "Paper Lantern - Live", artist = "Kit Calloway", durationMs = 200_000)
        val untagged = song("Music/Tapes/Side A - Tin Roof.mp3", title = "Side A - Tin Roof", artist = null, durationMs = 100_000)
        val resolver = SongResolver(listOf(live, untagged))

        assertEquals(live, M3u.find(M3uEntry("x.mp3", "Kit Calloway - Paper Lantern - Live", 200_000), resolver, folder = null))
        assertEquals(untagged, M3u.find(M3uEntry("x.mp3", "Side A - Tin Roof", 100_000), resolver, folder = null))

        // Only a split at a later dash would find this one: never taken.
        val other = song("Music/Odd/Live.mp3", title = "Live", artist = "Kit Calloway - Paper Lantern", durationMs = 200_000)
        assertNull(M3u.find(M3uEntry("x.mp3", "Kit Calloway - Paper Lantern - Live", 200_000), SongResolver(listOf(other)), folder = null))
        assertEquals(live, M3u.find(M3uEntry("x.mp3", "Kit Calloway - Paper Lantern - Live", 200_000), SongResolver(listOf(live, other)), folder = null))
        // The two readings naming two songs name none.
        val titled = song("Music/Odd/Side A.mp3", title = "Tin Roof", artist = "Side A", durationMs = 100_000)
        assertNull(M3u.find(M3uEntry("x.mp3", "Side A - Tin Roof", 100_000), SongResolver(listOf(untagged, titled)), folder = null))
    }

    @Test
    fun `the result lists the songs found in the file's order, a song twice too, and the missing ones`() {
        val saltRoad = M3uEntry("/storage/emulated/0/Music/Gone/03 Salt Road.mp3", "Mira Solen - Salt Road", 199_000)
        val greyPier = M3uEntry("C:\\Rips\\Night Ferry\\04 Grey Pier.mp3")
        val entries = listOf(M3uEntry(lantern.path), saltRoad, M3uEntry(lantern.path), greyPier, M3uEntry("x.mp3", "Mira Solen - Low Water", 240_000))

        val result = M3u.match("Road Trip", entries, resolver, folder = null)

        assertEquals(
            M3uImport("Road Trip", found = listOf(M3uFound(lantern, byName = false), M3uFound(lantern, byName = false), M3uFound(ferry, byName = true)), missing = listOf(saltRoad, greyPier)),
            result,
        )
        assertEquals(5, result.total)
        // Found by its #EXTINF alone: listed apart, and left out when the user says so.
        assertEquals(listOf(ferry), result.byName.map { it.song })
        assertEquals(listOf(lantern, lantern, ferry), result.songs(includeByName = true))
        assertEquals(listOf(lantern, lantern), result.songs(includeByName = false))
        assertEquals(listOf("Mira Solen - Salt Road", "04 Grey Pier.mp3"), result.missing.map { it.label })
        assertEquals(0, M3u.match("Empty", emptyList(), resolver, folder = null).total)
    }

    // Names

    @Test
    fun `an imported playlist keeps its name, and a clash adds (2), then (3), case aside`() {
        assertEquals("Road Trip", M3u.uniqueName("Road Trip", listOf("Night Drive")))
        assertEquals("Road Trip", M3u.uniqueName("  Road Trip ", emptyList()))
        assertEquals("Road Trip (2)", M3u.uniqueName("Road Trip", listOf("Road Trip")))
        assertEquals("Road Trip (3)", M3u.uniqueName("Road Trip", listOf("road trip", "ROAD TRIP (2)")))
        assertEquals("Road Trip (2) (2)", M3u.uniqueName("Road Trip (2)", listOf("Road Trip (2)")))
    }

    // Where the system's dialogs put the files

    @Test
    fun `documents of the phone's own storage have a path, others none`() {
        val storage = "com.android.externalstorage.documents"
        val downloads = "com.android.providers.downloads.documents"
        val primary = "/storage/emulated/0"

        assertEquals("$primary/Music/KJB test/Mix.m3u8", M3u.documentPath(storage, "primary:Music/KJB test/Mix.m3u8", primary))
        assertEquals("$primary/Music", M3u.documentPath(storage, "primary:Music", primary))
        assertEquals(primary, M3u.documentPath(storage, "primary:", primary))
        assertEquals("/storage/1A2B-3C4D/Music/Mix.m3u8", M3u.documentPath(storage, "1A2B-3C4D:Music/Mix.m3u8", primary))
        assertEquals("$primary/Documents/Mix.m3u8", M3u.documentPath(storage, "home:Mix.m3u8", primary))
        assertEquals("$primary/Download/Mix.m3u8", M3u.documentPath(downloads, "raw:$primary/Download/Mix.m3u8", primary))
        assertNull(M3u.documentPath(downloads, "msf:1234", primary))
        assertNull(M3u.documentPath("com.example.cloud.documents", "primary:Music/Mix.m3u8", primary))
        assertNull(M3u.documentPath(storage, "no-colon", primary))
        assertNull(M3u.documentPath(null, "primary:Music/Mix.m3u8", primary))
    }

    @Test
    fun `a debug build exports through a folder it checks before any file exists, a release build through the save dialog`() {
        assertTrue(M3u.exportsByFolder(SharedWrites(testFolderOnly = true)))
        assertFalse(M3u.exportsByFolder(SharedWrites.ANYWHERE))
    }

    @Test
    fun `a debug build exports only into its test folder, and nothing where the document has no path`() {
        Files.createDirectories(folder.root.toPath().resolve("storage/emulated/0/Music/KJB test"))
        val logs = mutableListOf<String>()
        val debug = SharedWrites(testFolderOnly = true, root = folder.root.toPath()) { logs += it }

        assertTrue(M3u.exportAllowed(debug, "/storage/emulated/0/Music/KJB test/Mix.m3u8"))
        assertFalse(M3u.exportAllowed(debug, "/storage/emulated/0/Download/Mix.m3u8"))
        assertFalse(M3u.exportAllowed(debug, null))
        assertTrue(M3u.exportAllowed(SharedWrites.ANYWHERE, "/storage/emulated/0/Download/Mix.m3u8"))
        assertTrue(M3u.exportAllowed(SharedWrites.ANYWHERE, null))
        assertEquals(2, logs.size)
        assertFalse(logs.toString(), logs.any { "Mix" in it || "/storage" in it })
    }
}
