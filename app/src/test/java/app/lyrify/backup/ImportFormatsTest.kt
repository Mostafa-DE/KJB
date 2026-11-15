package app.lyrify.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Import from another app: each format read by what the file holds into the one neutral model, and every file treated
 * as untrusted (caps, XML without a DTD or unknown entities, cleaned strings, fuzz). Made-up names and paths only.
 */
class ImportFormatsTest {
    // m3u and m3u8

    @Test
    fun `an extended m3u reads each line with its #EXTINF, its path tidied, named after the file`() {
        val text = "#EXTM3U\r\n#EXTINF:201,Kit Calloway - Paper Lantern\r\n/sdcard/Music/Harbour Glass/01 Paper Lantern.mp3\r\nNight Ferry\\01 Low Water.mp3\r\n"

        assertEquals(
            ImportRead.Read(
                "Road Trip.m3u8",
                ImportFormat.M3U,
                ImportBundle(
                    listOf(
                        ImportPlaylist(
                            "Road Trip",
                            listOf(
                                ImportSong("/sdcard/Music/Harbour Glass/01 Paper Lantern.mp3", "/sdcard/Music/Harbour Glass/01 Paper Lantern.mp3", durationMs = 201_000, info = "Kit Calloway - Paper Lantern"),
                                ImportSong("Night Ferry\\01 Low Water.mp3", "$MUSIC/Night Ferry/01 Low Water.mp3"),
                            ),
                        ),
                    ),
                ),
            ),
            ImportFormats.read(text.encodeToByteArray(), "Road Trip.m3u8", MUSIC),
        )
    }

    @Test
    fun `a plain m3u without a header is read by its name, and a text file of song paths by its lines`() {
        val lines = "Harbour Glass/01 Paper Lantern.mp3\nNight Ferry/01 Low Water.flac\n"

        assertEquals(ImportFormat.M3U, (ImportFormats.read(lines.encodeToByteArray(), "Mix.m3u", null) as ImportRead.Read).format)
        val text = ImportFormats.read(lines.encodeToByteArray(), "Mix.txt", null) as ImportRead.Read
        assertEquals(listOf("Harbour Glass/01 Paper Lantern.mp3", "Night Ferry/01 Low Water.flac"), text.bundle.playlists.single().songs.map { it.path })
        assertEquals("Mix", text.bundle.playlists.single().name)
    }

    @Test
    fun `a note that is not a list of songs is not recognised, nor an empty file`() {
        assertEquals(ImportFailure.NOT_RECOGNISED, failure("Buy milk\nCall Odo\n", "notes.txt"))
        assertEquals(ImportFailure.NOT_RECOGNISED, failure("", "empty.txt"))
        assertEquals(ImportFailure.EMPTY, failure("#EXTM3U\n# nothing here\n", "Empty.m3u8"))
    }

    // Samsung Music's favourites: Export writes them as one m3u of its own name, in the Music folder

    @Test
    fun `Samsung Music's favourites file is told by its name, in any case, m3u or m3u8`() {
        listOf("!#SamsungMusic_favorites#!.m3u", "!#samsungmusic_FAVORITES#!.M3U8", " !#SamsungMusic_favorites#!.m3u8 ").forEach { assertTrue(it, ImportFormats.isSamsungFavourites(it)) }
        listOf(
            "!#SamsungMusic_favorites#!.pls", "!#SamsungMusic_favorites#!.txt", "!#SamsungMusic_favorites#!", "SamsungMusic_favorites.m3u", "!#SamsungMusic_favorites#! (1).m3u",
            "Favorites.m3u", "!#SamsungMusic_Road Trip#!.m3u", null,
        ).forEach { assertFalse("$it", ImportFormats.isSamsungFavourites(it)) }
    }

    @Test
    fun `Samsung Music's favourites file is read as favourites in its order, paths found from its folder, and no playlist`() {
        // As Samsung Music writes it: a header, no #EXTINF, paths relative to the Music folder the file is in.
        val text = "#EXTM3U\n../Download/Night Ferry/01 Low Water.mp3\nHarbour Glass/01 Paper Lantern.mp3\n../Download/Night Ferry/01 Low Water.mp3\n"

        listOf("!#SamsungMusic_favorites#!.m3u", "!#SAMSUNGMUSIC_favorites#!.m3u8").forEach { name ->
            val read = ImportFormats.read(text.encodeToByteArray(), name, MUSIC) as ImportRead.Read

            assertEquals(name, ImportFormat.M3U, read.format)
            assertEquals(name, emptyList<ImportPlaylist>(), read.bundle.playlists)
            assertEquals(
                name,
                listOf("/storage/emulated/0/Download/Night Ferry/01 Low Water.mp3", "$MUSIC/Harbour Glass/01 Paper Lantern.mp3", "/storage/emulated/0/Download/Night Ferry/01 Low Water.mp3"),
                read.bundle.favourites.map { it.path },
            )
        }
        // The same lines in any other m3u are a playlist named after its file, as ever.
        val other = ImportFormats.read(text.encodeToByteArray(), "!#SamsungMusic_Road Trip#!.m3u", MUSIC) as ImportRead.Read
        assertEquals(listOf("!#SamsungMusic_Road Trip#!"), other.bundle.playlists.map { it.name })
        assertEquals(emptyList<ImportSong>(), other.bundle.favourites)
        assertEquals(ImportFailure.EMPTY, failure("#EXTM3U\n", "!#SamsungMusic_favorites#!.m3u"))
    }

    // pls

    @Test
    fun `a pls reads its entries in number order with their titles and lengths, keys in any case`() {
        val text = """
            |[playlist]
            |NumberOfEntries=3
            |File2=../Night Ferry/01 Low Water.mp3
            |Title2=Mira Solen - Low Water
            |Length2=240
            |file1=file:///storage/emulated/0/Music/Harbour%20Glass/01%20Paper%20Lantern.mp3
            |TITLE1=Kit Calloway - Paper Lantern
            |length1=-1
            |Title3=A title without its file
            |File10=https://radio.example/stream
            |Version=2
        """.trimMargin()

        val read = ImportFormats.read(text.encodeToByteArray(), "Evening.pls", "$MUSIC/Playlists") as ImportRead.Read

        assertEquals(ImportFormat.PLS, read.format)
        assertEquals(
            ImportPlaylist(
                "Evening",
                listOf(
                    ImportSong("file:///storage/emulated/0/Music/Harbour%20Glass/01%20Paper%20Lantern.mp3", "$MUSIC/Harbour Glass/01 Paper Lantern.mp3", info = "Kit Calloway - Paper Lantern"),
                    ImportSong("../Night Ferry/01 Low Water.mp3", "$MUSIC/Night Ferry/01 Low Water.mp3", durationMs = 240_000, info = "Mira Solen - Low Water"),
                    ImportSong("https://radio.example/stream", null),
                ),
            ),
            read.bundle.playlists.single(),
        )
    }

    @Test
    fun `a pls without its header still reads, a value keeps its own equals signs, and an index no Int holds is skipped`() {
        val text = "File1=/storage/emulated/0/Music/a=b.mp3\nFile99999999999=/storage/emulated/0/Music/x.mp3\n"

        val read = ImportFormats.read(text.encodeToByteArray(), "Odd.pls", null) as ImportRead.Read

        assertEquals(listOf("/storage/emulated/0/Music/a=b.mp3"), read.bundle.playlists.single().songs.map { it.path })
    }

    @Test
    fun `a pls with more than 10,000 entries or a line over 4 KB is too big`() {
        val many = (1..10_001).joinToString("\n", prefix = "[playlist]\n") { "File$it=/storage/emulated/0/Music/$it.mp3" }
        val long = "[playlist]\nFile1=/storage/emulated/0/Music/${"a".repeat(5_000)}.mp3\n"

        assertEquals(ImportFailure.TOO_BIG, failure(many, "Many.pls"))
        assertEquals(ImportFailure.TOO_BIG, failure(long, "Long.pls"))
    }

    // xspf

    @Test
    fun `an xspf reads its title and each track's location, title, creator, album and length`() {
        val text = """
            |<?xml version="1.0" encoding="UTF-8"?>
            |<!-- made by a desktop player -->
            |<playlist version="1" xmlns="http://xspf.org/ns/0/">
            |  <title>Quiet &amp; Slow</title>
            |  <trackList>
            |    <track>
            |      <location>file:///storage/emulated/0/Music/Harbour%20Glass/01%20Paper%20Lantern.mp3</location>
            |      <location>file:///storage/emulated/0/Music/other.mp3</location>
            |      <title>Paper Lantern</title>
            |      <creator>Kit Calloway</creator>
            |      <album>Harbour Glass</album>
            |      <duration>201000</duration>
            |      <extension application="http://example.com"><vendor:rating xmlns:vendor="urn:x">5</vendor:rating></extension>
            |    </track>
            |    <track>
            |      <location>Night%20Ferry/01%20Low%20Water.mp3</location>
            |      <title><![CDATA[Low <Water>]]></title>
            |      <creator>Mira Solen</creator>
            |      <duration>not a number</duration>
            |    </track>
            |    <track><title>Glass Steps</title><creator>Odo Brannick</creator><duration>175000</duration></track>
            |    <track><annotation>nothing to find it by</annotation></track>
            |  </trackList>
            |</playlist>
        """.trimMargin()

        val read = ImportFormats.read(text.encodeToByteArray(), "export.xspf", MUSIC) as ImportRead.Read

        assertEquals(ImportFormat.XSPF, read.format)
        assertEquals(
            ImportPlaylist(
                "Quiet & Slow",
                listOf(
                    ImportSong("file:///storage/emulated/0/Music/Harbour%20Glass/01%20Paper%20Lantern.mp3", "$MUSIC/Harbour Glass/01 Paper Lantern.mp3", "Paper Lantern", "Kit Calloway", "Harbour Glass", 201_000),
                    ImportSong("Night%20Ferry/01%20Low%20Water.mp3", "$MUSIC/Night Ferry/01 Low Water.mp3", "Low <Water>", "Mira Solen"),
                    ImportSong("", null, "Glass Steps", "Odo Brannick", durationMs = 175_000),
                ),
            ),
            read.bundle.playlists.single(),
        )
    }

    @Test
    fun `an xspf with prefixed names, character references and no title is named after its file`() {
        val text = """<x:playlist xmlns:x="http://xspf.org/ns/0/"><x:trackList><x:track><x:location>/storage/emulated/0/Music/Caf&#233; &#x2615;.mp3</x:location></x:track></x:trackList></x:playlist>"""

        val read = ImportFormats.read(text.encodeToByteArray(), "Morning.xspf", null) as ImportRead.Read

        assertEquals(ImportPlaylist("Morning", listOf(ImportSong("/storage/emulated/0/Music/Café ☕.mp3", "/storage/emulated/0/Music/Café ☕.mp3"))), read.bundle.playlists.single())
    }

    @Test
    fun `a DTD is never processed, so its entities stay unknown and the file is damaged, external ones too`() {
        val laughs = """
            |<?xml version="1.0"?>
            |<!DOCTYPE playlist [
            |  <!ENTITY lol "lol">
            |  <!ENTITY lol2 "&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;">
            |  <!ENTITY lol3 "&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;">
            |]>
            |<playlist xmlns="http://xspf.org/ns/0/"><title>&lol3;</title><trackList/></playlist>
        """.trimMargin()
        val external = """
            |<?xml version="1.0"?>
            |<!DOCTYPE playlist [ <!ENTITY xxe SYSTEM "file:///data/data/app.lyrify/files/settings.json"> ]>
            |<playlist xmlns="http://xspf.org/ns/0/"><trackList><track><location>&xxe;</location></track></trackList></playlist>
        """.trimMargin()

        assertEquals(ImportFailure.UNREADABLE, failure(laughs, "laughs.xspf"))
        assertEquals(ImportFailure.UNREADABLE, failure(external, "external.xspf"))
    }

    @Test
    fun `a DOCTYPE that names no entity is skipped, not processed`() {
        val text = """<?xml version="1.0"?><!DOCTYPE playlist SYSTEM "http://example.com/xspf.dtd" [ <!-- a ] and a > inside --> ]><playlist xmlns="http://xspf.org/ns/0/"><trackList><track><location>/storage/emulated/0/Music/a.mp3</location></track></trackList></playlist>"""

        assertEquals(listOf("/storage/emulated/0/Music/a.mp3"), (ImportFormats.read(text.encodeToByteArray(), "a.xspf", null) as ImportRead.Read).bundle.playlists.single().songs.map { it.path })
    }

    @Test
    fun `damaged xml is unreadable, deeply nested xml and more than 10,000 tracks too big`() {
        val open = """<playlist xmlns="http://xspf.org/ns/0/"><trackList><track><location>/a.mp3</location>"""
        val crossed = """<playlist><trackList><track></trackList></track></playlist>"""
        val after = """<playlist><trackList/></playlist>trailing words"""
        val twoRoots = """<playlist><trackList/></playlist><playlist/>"""
        val badEntity = """<playlist><title>a &nbsp; b</title><trackList/></playlist>"""
        val nested = "<playlist>" + "<extension>".repeat(100_000) + "</extension>".repeat(100_000) + "</playlist>"
        val many = (1..10_001).joinToString("", "<playlist><trackList>", "</trackList></playlist>") { "<track><location>/$it.mp3</location></track>" }

        listOf(open, crossed, after, twoRoots, badEntity).forEach { assertEquals(it, ImportFailure.UNREADABLE, failure(it, "x.xspf")) }
        assertEquals(ImportFailure.TOO_BIG, failure(nested, "nested.xspf"))
        assertEquals(ImportFailure.TOO_BIG, failure(many, "many.xspf"))
    }

    @Test
    fun `xml whose root is not a playlist is not recognised`() {
        assertEquals(ImportFailure.NOT_RECOGNISED, failure("""<smil><body><seq><media src="a.mp3"/></seq></body></smil>""", "old.wpl"))
        assertEquals(ImportFailure.NOT_RECOGNISED, failure("""<?xml version="1.0"?><svg/>""", "picture.svg"))
    }

    // Sniffing: the content says what a file is

    @Test
    fun `the content wins over the name`() {
        val pls = "[playlist]\nFile1=/storage/emulated/0/Music/a.mp3\n"
        val xspf = """<playlist xmlns="http://xspf.org/ns/0/"><trackList><track><location>/storage/emulated/0/Music/a.mp3</location></track></trackList></playlist>"""

        assertEquals(ImportFormat.PLS, (ImportFormats.read(pls.encodeToByteArray(), "Wrong.m3u", null) as ImportRead.Read).format)
        assertEquals(ImportFormat.XSPF, (ImportFormats.read(xspf.encodeToByteArray(), "Wrong.txt", null) as ImportRead.Read).format)
        assertEquals(ImportFormat.M3U, (ImportFormats.read("#EXTM3U\n/storage/emulated/0/Music/a.mp3\n".encodeToByteArray(), null, null) as ImportRead.Read).format)
    }

    @Test
    fun `a pdf, an audio file or a database named as a playlist is not recognised, and a zip waits for Musicolet's format`() {
        val pdf = "%PDF-1.7\n%âã\n1 0 obj\n".encodeToByteArray() + ByteArray(200) { (it * 7).toByte() }
        val mp3 = byteArrayOf(0x49, 0x44, 0x33, 3, 0, 0, 0, 0, 0, 0) + ByteArray(500) { (it * 13).toByte() }
        val sqlite = "SQLite format 3\u0000".encodeToByteArray() + ByteArray(100)
        val zip = byteArrayOf(0x50, 0x4B, 3, 4) + ByteArray(100)

        assertEquals(ImportFailure.NOT_RECOGNISED, ImportFormats.read(pdf, "Mix.m3u", null).let { (it as ImportRead.Failed).why })
        assertEquals(ImportFailure.NOT_RECOGNISED, ImportFormats.read(mp3, "Mix.m3u8", null).let { (it as ImportRead.Failed).why })
        assertEquals(ImportFailure.NOT_RECOGNISED, ImportFormats.read(sqlite, "backup.db", null).let { (it as ImportRead.Failed).why })
        assertEquals(ImportFailure.NOT_RECOGNISED, ImportFormats.read(zip, "2026-11-01.zip", null).let { (it as ImportRead.Failed).why })
    }

    @Test
    fun `utf-16 files with a BOM and without one are read`() {
        val text = "#EXTM3U\n/storage/emulated/0/Music/Café.mp3\n"
        val le = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)
        val be = text.toByteArray(Charsets.UTF_16BE)

        listOf(le, be).forEach { bytes ->
            assertEquals(listOf("/storage/emulated/0/Music/Café.mp3"), (ImportFormats.read(bytes, "Mix.m3u", null) as ImportRead.Read).bundle.playlists.single().songs.map { it.path })
        }
    }

    @Test
    fun `a picked file is read up to 5 MB, a zip up to 64 MB, and nothing past that`() {
        val text = ByteArray(ImportFormats.MAX_BYTES + 1) { 'a'.code.toByte() }
        val zip = byteArrayOf(0x50, 0x4B, 3, 4) + ByteArray(ImportFormats.MAX_BYTES + 10)

        assertNull(ImportFormats.readBounded(text.inputStream()))
        assertEquals(zip.size, ImportFormats.readBounded(zip.inputStream())?.size)
        assertEquals(3, ImportFormats.readBounded("abc".byteInputStream())?.size)
        assertNull(ImportFormats.readBounded(java.io.SequenceInputStream(zip.inputStream(), ByteArray(ImportFormats.MAX_ARCHIVE_BYTES).inputStream())))
    }

    @Test
    fun `a file over 5 MB is too big`() {
        assertEquals(ImportFailure.TOO_BIG, (ImportFormats.read(ByteArray(ImportFormats.MAX_BYTES + 1) { 'a'.code.toByte() }, "Huge.m3u", null) as ImportRead.Failed).why)
    }

    // Strings are data

    @Test
    fun `texts lose control and direction characters, stay on one line and are capped`() {
        assertEquals("Paper Lantern", ImportFormats.clean("  Paper\u0000 Lantern\u202E\u2066  "))
        assertEquals("Low Water Salt Road", ImportFormats.clean("Low Water\nSalt\u2028Road"))
        assertNull(ImportFormats.clean("\u200F\u0007 "))
        assertEquals("a".repeat(ImportFormats.MAX_NAME_CHARS), ImportFormats.cleanName("a".repeat(500)))
        // A cap never splits a character in two: 200 chars would end inside the 100th face.
        assertEquals("a" + "😀".repeat(99), ImportFormats.cleanName("a" + "😀".repeat(150)))
    }

    @Test
    fun `a playlist's name loses its bidi tricks and its extension, and every song's texts are cleaned`() {
        val text = "#EXTM3U\n#EXTINF:200,Kit\u202E Calloway - Paper\u0000 Lantern\n/storage/emulated/0/Music/a.mp3\n"

        val playlist = (ImportFormats.read(text.encodeToByteArray(), "\u202Egnos\u202C Mix.m3u8", null) as ImportRead.Read).bundle.playlists.single()

        assertEquals("gnos Mix", playlist.name)
        assertEquals("Kit Calloway - Paper Lantern", playlist.songs.single().info)
    }

    @Test
    fun `a folder's playlist files are told by their extensions, and names lose them`() {
        listOf("a.m3u", "B.M3U8", "c.pls", "d.XSPF").forEach { assertTrue(it, ImportFormats.isPlaylistFile(it)) }
        listOf("01 Song.mp3", "cover.jpg", "notes.txt", "noext", null).forEach { assertFalse("$it", ImportFormats.isPlaylistFile(it)) }
        assertEquals("Evening", ImportFormats.playlistName("Evening.pls"))
        assertEquals("Quiet", ImportFormats.playlistName("Quiet.xspf"))
        assertEquals("Road.Trip", ImportFormats.playlistName("Road.Trip.m3u8"))
        assertEquals("notes", ImportFormats.playlistName("notes.txt"))
        assertEquals("Mix.final", ImportFormats.playlistName("Mix.final"))
        assertNull(ImportFormats.playlistName(".m3u"))
    }

    // A pick

    @Test
    fun `a pick's files are read into one bundle, the files and songs over the caps left out as too big`() {
        fun file(name: String, songs: Int) = ImportRead.Read(name, ImportFormat.M3U, ImportBundle(listOf(ImportPlaylist(name, List(songs) { ImportSong("/$it.mp3", "/$it.mp3") }))))
        val failed = ImportRead.Failed("notes.txt", ImportFailure.NOT_RECOGNISED)

        val pick = ImportFormats.combine(listOf(file("a", 60_000), failed, file("b", 40_000), file("c", 1)))

        assertEquals(listOf("a", "b"), pick.bundle.playlists.map { it.name })
        assertEquals(2, pick.read)
        assertEquals(listOf(failed, ImportRead.Failed("c", ImportFailure.TOO_BIG)), pick.failed)
        val crowd = ImportFormats.combine(List(ImportFormats.MAX_FILES + 2) { file("f$it", 1) })
        assertEquals(ImportFormats.MAX_FILES, crowd.read)
        assertEquals(2, crowd.failed.count { it.why == ImportFailure.TOO_BIG })
    }

    // Fuzz: nothing a file holds makes the reader throw

    @Test
    fun `random bytes under any name never throw`() {
        val random = Random(20261113)
        val names = listOf("a.m3u", "a.m3u8", "a.pls", "a.xspf", "a.txt", null)
        repeat(2_000) {
            val bytes = random.nextBytes(random.nextInt(0, 2_000))
            ImportFormats.read(bytes, names[it % names.size], "/storage/emulated/0/Music")
        }
    }

    @Test
    fun `every cut of a valid file reads or fails, never throws`() {
        val files = mapOf(
            "a.xspf" to """<?xml version="1.0"?><playlist xmlns="http://xspf.org/ns/0/"><title>T &amp; U</title><trackList><track><location>file:///storage/emulated/0/Music/a%20b.mp3</location><title><![CDATA[x]]></title><duration>1000</duration></track></trackList></playlist>""",
            "a.pls" to "[playlist]\nFile1=/storage/emulated/0/Music/a.mp3\nTitle1=A - B\nLength1=10\nNumberOfEntries=1\n",
            "a.m3u8" to "\uFEFF#EXTM3U\n#EXTINF:10,A - B\nfile:///storage/emulated/0/Music/a%20b.mp3\n",
        )
        files.forEach { (name, text) ->
            val bytes = text.encodeToByteArray()
            (0..bytes.size).forEach { cut -> ImportFormats.read(bytes.copyOf(cut), name, null) }
        }
    }

    @Test
    fun `random xml-ish text never throws`() {
        val random = Random(7)
        val parts = listOf("<", ">", "/", "playlist", "trackList", "track", "location", "title", "&", ";", "&amp;", "&#", "x", "<!--", "-->", "<![CDATA[", "]]>", "<?", "?>", "<!DOCTYPE", "[", "]", "\"", "'", "=", " ", "a")
        repeat(3_000) {
            val text = List(random.nextInt(1, 80)) { parts[random.nextInt(parts.size)] }.joinToString("")
            ImportFormats.read(text.encodeToByteArray(), "f.xspf", null)
        }
    }

    @Test
    fun `a stray nul byte is dropped, and a million-entry m3u and a huge line fail safely`() {
        val nuls = "#EXTM3U\n/storage/emulated/0/Music/a\u0000b.mp3\n".encodeToByteArray()
        val million = buildString { append("#EXTM3U\n"); repeat(400_000) { append("/a.mp3\n") } }
        val line = "#EXTM3U\n/" + "a".repeat(10_000) + ".mp3\n"

        assertEquals(listOf("/storage/emulated/0/Music/ab.mp3"), (ImportFormats.read(nuls, "a.m3u8", null) as ImportRead.Read).bundle.playlists.single().songs.map { it.path })
        assertEquals(ImportFailure.TOO_BIG, failure(million, "million.m3u"))
        assertEquals(ImportFailure.TOO_BIG, failure(line, "line.m3u"))
    }

    private fun failure(text: String, name: String): ImportFailure? = (ImportFormats.read(text.encodeToByteArray(), name, null) as? ImportRead.Failed)?.why

    private companion object {
        const val MUSIC: String = "/storage/emulated/0/Music"
    }
}
