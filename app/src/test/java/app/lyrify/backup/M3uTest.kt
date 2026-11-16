package app.lyrify.backup

import app.lyrify.data.SharedWrites
import app.lyrify.library.SongRef
import app.lyrify.library.SongResolver
import app.lyrify.library.VOLUME
import app.lyrify.library.song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.charset.Charset
import java.nio.file.Files

/** Playlists out to m3u files, the reading and paths Import from app takes from M3u, and naming. Made-up names only. */
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
            read(text.toByteArray(Charsets.UTF_8), "Road Trip.m3u8"),
        )
    }

    @Test
    fun `reads plain M3U without a header, with old Mac line ends, and skips blank lines and other comments`() {
        val text = "# made by hand\r/storage/emulated/0/Music/Harbour Glass/01 Paper Lantern.mp3\r\r  Harbour Glass\\02 Tide Bell.mp3  \r#EXTALB:Harbour Glass\r"

        assertEquals(
            listOf(M3uEntry("/storage/emulated/0/Music/Harbour Glass/01 Paper Lantern.mp3"), M3uEntry("Harbour Glass\\02 Tide Bell.mp3")),
            read(text.toByteArray(Charsets.UTF_8), "Mix.m3u"),
        )
    }

    @Test
    fun `an #EXTINF names only the next path, through comments between them`() {
        val text = "#EXTM3U\n#EXTINF:201,Kit Calloway - Paper Lantern\n#EXTALB:Harbour Glass\n/a/Paper Lantern.mp3\n/a/Tide Bell.mp3\n"

        assertEquals(
            listOf(M3uEntry("/a/Paper Lantern.mp3", "Kit Calloway - Paper Lantern", 201_000), M3uEntry("/a/Tide Bell.mp3")),
            read(text.toByteArray(Charsets.UTF_8), "Mix.m3u8"),
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
            read(text.toByteArray(Charsets.UTF_8), "Mix.m3u8"),
        )
    }

    @Test
    fun `an #EXTINF length that is not a number of milliseconds a Long holds is none, and its name stays`() {
        val text = listOf("Infinity", "-Infinity", "NaN", "1e300", "9223372036854775807").joinToString("") { "#EXTINF:$it,Kit Calloway - Paper Lantern\n/a/$it.mp3\n" }

        assertEquals(
            listOf("Infinity", "-Infinity", "NaN", "1e300", "9223372036854775807").map { M3uEntry("/a/$it.mp3", "Kit Calloway - Paper Lantern", null) },
            read(text.toByteArray(Charsets.UTF_8), "Mix.m3u8"),
        )
    }

    @Test
    fun `a file with more than 10,000 entries or a line over 4 KB is too big`() {
        val lines = { count: Int -> (1..count).joinToString("\n") { "/a/$it.mp3" }.toByteArray(Charsets.UTF_8) }

        assertEquals(M3u.MAX_ENTRIES, read(lines(M3u.MAX_ENTRIES), "Mix.m3u8")?.size)
        assertNull(read(lines(M3u.MAX_ENTRIES + 1), "Mix.m3u8"))
        val longest = "/" + "a".repeat(M3u.MAX_LINE_CHARS - 1)
        assertEquals(listOf(M3uEntry(longest)), read(longest.toByteArray(Charsets.UTF_8), "Mix.m3u8"))
        assertNull(read("$longest\n#EXTINF:1,${"b".repeat(M3u.MAX_LINE_CHARS)}\n".toByteArray(Charsets.UTF_8), "Mix.m3u8"))
    }

    @Test
    fun `an m3u without a BOM but with NUL bytes is UTF-16, big-endian when the NULs come first`() {
        val text = "#EXTM3U\r\n#EXTINF:201,Kit Calloway - Paper Lantern\r\n/a/Café.mp3\r\n"
        val expected = listOf(M3uEntry("/a/Café.mp3", "Kit Calloway - Paper Lantern", 201_000))

        assertEquals(expected, read(text.toByteArray(Charsets.UTF_16LE), "Mix.m3u"))
        assertEquals(expected, read(text.toByteArray(Charsets.UTF_16BE), "Mix.m3u"))
    }

    @Test
    fun `an m3u in UTF-8 reads as UTF-8, one that is not reads as Latin-1 as Windows writes it`() {
        val text = "#EXTINF:201,Kit Calloway - Café Lights’ End\n/a/Café.mp3\n"
        val expected = listOf(M3uEntry("/a/Café.mp3", "Kit Calloway - Café Lights’ End", 201_000))

        assertEquals(expected, read(text.toByteArray(Charsets.UTF_8), "Mix.m3u"))
        assertEquals(expected, read(text.toByteArray(Charset.forName("windows-1252")), "Mix.m3u"))
        assertEquals(listOf(M3uEntry("/a/Café.mp3")), read("/a/Café.mp3".toByteArray(Charsets.ISO_8859_1), "Mix.M3U"))
    }

    @Test
    fun `an m3u8 is always UTF-8, never guessed`() {
        assertEquals(listOf(M3uEntry("/a/Caf\uFFFD.mp3")), read("/a/Café.mp3".toByteArray(Charsets.ISO_8859_1), "Mix.m3u8"))
    }

    @Test
    fun `UTF-16 files with their BOM read too`() {
        val text = "\uFEFF#EXTM3U\r\n#EXTINF:201,Kit Calloway - Paper Lantern\r\n/a/Café.mp3\r\n"
        val expected = listOf(M3uEntry("/a/Café.mp3", "Kit Calloway - Paper Lantern", 201_000))

        assertEquals(expected, read(text.toByteArray(Charsets.UTF_16LE), "Mix.m3u"))
        assertEquals(expected, read(text.toByteArray(Charsets.UTF_16BE), "Mix.m3u8"))
    }

    @Test
    fun `the Playlists tab's Import offers m3u files, and the text and unknown types some providers give them`() {
        assertTrue("text/plain" in M3u.MIME_TYPES && "application/octet-stream" in M3u.MIME_TYPES && "audio/x-mpegurl" in M3u.MIME_TYPES)
    }

    // Paths

    @Test
    fun `a file URL's path is everything after its scheme, question marks and hashes too, percent-decoded, and a lone % stays`() {
        assertEquals("/storage/emulated/0/Music/Is it #1? Yes.mp3", M3u.matchPath("file:///storage/emulated/0/Music/Is it #1? Yes.mp3", folder = null))
        assertEquals("/storage/emulated/0/Music/Is it #1?.mp3", M3u.matchPath("file:///storage/emulated/0/Music/Is%20it%20%231%3F.mp3", folder = null))
        assertEquals("/storage/emulated/0/Music/100% Café 50%.mp3", M3u.matchPath("file:///storage/emulated/0/Music/100%25%20Caf%C3%A9%2050%.mp3", folder = null))
        assertEquals("/storage/emulated/0/Music/100% Tin Roof.mp3", M3u.matchPath("/storage/emulated/0/Music/100% Tin Roof.mp3", folder = null))
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

    /** The entries of the m3u file [fileName] holding [bytes], decoded and parsed as Import from app reads them ([ImportFormats.read]). */
    private fun read(bytes: ByteArray, fileName: String): List<M3uEntry>? = M3u.parse(M3u.decode(bytes, utf8 = fileName.endsWith(".m3u8", ignoreCase = true)))
}
