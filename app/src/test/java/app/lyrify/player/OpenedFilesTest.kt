package app.lyrify.player

import android.database.Cursor
import android.provider.OpenableColumns
import app.lyrify.auto.DEFAULT_COVER
import app.lyrify.auto.coverPath
import app.lyrify.library.SongRef
import app.lyrify.library.song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.lang.reflect.Proxy

/**
 * A file another app opens in KJB: only MediaStore's own address of an audio file names a library song (by its id); any
 * other address is read through the opening app's grant, never as a path with KJB's own access. And the copies of opened
 * files stay while a queue holds them.
 */
class OpenedFilesTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `MediaStore's address of an audio file names its id`() {
        assertEquals(42L, mediaStoreAudioId("content://media/external/audio/media/42"))
        assertEquals(42L, mediaStoreAudioId("content://media/external_primary/audio/media/42?x=1"))
        assertEquals(7L, mediaStoreAudioId("content://media/1a2b-3c4d/audio/media/7"))
    }

    @Test
    fun `no other address names a song, a path or an id`() {
        assertNull("a file path", mediaStoreAudioId("file:///storage/emulated/0/Music/Paper%20Lantern.mp3"))
        assertNull("the storage documents' path", mediaStoreAudioId("content://com.android.externalstorage.documents/document/primary%3AMusic%2FTide.flac"))
        assertNull("the media documents", mediaStoreAudioId("content://com.android.providers.media.documents/document/audio%3A42"))
        assertNull("a raw download path", mediaStoreAudioId("content://com.android.providers.downloads.documents/document/raw%3A%2Fstorage%2Femulated%2F0%2FDownload%2FTide.mp3"))
        assertNull("any file in MediaStore", mediaStoreAudioId("content://media/external/file/7"))
        assertNull("an album", mediaStoreAudioId("content://media/external/audio/albums/3"))
        assertNull(mediaStoreAudioId("content://media/external/audio/media/notanumber"))
        assertNull("another app's provider", mediaStoreAudioId("content://com.example.mail.attachments/audio/media/42"))
        assertNull(mediaStoreAudioId("not an address"))
    }

    @Test
    fun `an opened file is the library's song only by its MediaStore id`() {
        val lantern = song("Music/Harbour Glass/01 Paper Lantern.mp3", id = 42)
        val tide = song("Music/Tide/Tide.flac", id = 43)

        assertEquals(tide, matchOpened(43, listOf(lantern, tide)))
        assertNull(matchOpened(99, listOf(lantern, tide)))
        assertNull(matchOpened(null, listOf(lantern, tide)))
        assertNull("two songs with one id: no guess", matchOpened(42, listOf(lantern, song("Music/Copies/01 Paper Lantern.mp3", id = 42))))
    }

    // Another app's own address (decision 1b): the library's song by its file name and size, when exactly one has both

    private val orchard = song("Audiobooks/Quiet Orchard.m4b", id = 50).copy(sizeBytes = 512_000_000)
    private val tide = song("Music/Tide/Tide.flac", id = 43).copy(sizeBytes = 31_000_000)
    private val library = listOf(song("Music/Harbour Glass/01 Paper Lantern.mp3", id = 42), tide, orchard)
    private val files = "content://com.example.files.provider/root/storage/emulated/0/Download/Quiet%20Orchard.m4b"

    @Test
    fun `another app's own address is the library's song when exactly one has its file name and size, a book or music`() {
        assertEquals(orchard, matchNamed(Openable("Quiet Orchard.m4b", 512_000_000), library))
        assertEquals(tide, matchNamed(Openable("Tide.flac", 31_000_000), library))
        assertEquals(orchard, openedSong(files, library) { Openable("Quiet Orchard.m4b", 512_000_000) })
    }

    @Test
    fun `several songs with that name and size, or a size or name that differs, are no answer`() {
        val twin = song("Music/Backup/Quiet Orchard.m4b", id = 51).copy(sizeBytes = 512_000_000)
        assertNull("two songs match: no guess", matchNamed(Openable("Quiet Orchard.m4b", 512_000_000), library + twin))
        assertNull("the size differs", matchNamed(Openable("Quiet Orchard.m4b", 512_000_001), library))
        assertNull("the name differs", matchNamed(Openable("Quiet Orchard (1).m4b", 512_000_000), library))
        assertNull("the name differs in case", matchNamed(Openable("quiet orchard.m4b", 512_000_000), library))
    }

    @Test
    fun `an unknown name or size is no answer`() {
        assertNull(matchNamed(Openable(null, 512_000_000), library))
        assertNull(matchNamed(Openable("", 512_000_000), library))
        assertNull(matchNamed(Openable("Quiet Orchard.m4b", null), library))
        assertNull(matchNamed(Openable("Quiet Orchard.m4b", 0), library))
        // A library song MediaStore could not measure never matches by size.
        assertNull(matchNamed(Openable("Tide.flac", 0), listOf(tide.copy(sizeBytes = 0))))
        assertNull(openedSong(files, library) { Openable(null, null) })
    }

    @Test
    fun `MediaStore's own address goes by its id only, and the opening app is never asked`() {
        val never: () -> Openable = { throw AssertionError("asked for a name") }
        assertEquals(tide, openedSong("content://media/external/audio/media/43", library, never))
        // An id the library lacks plays from a copy, as before, whatever the name says.
        assertNull(openedSong("content://media/external/audio/media/99", library, never))
    }

    @Test
    fun `an open plays the library's song when it matches, else copies a content address under the name the opening app gives`() {
        val asked = mutableListOf<String>()
        val copied = mutableListOf<Pair<String, String?>>()
        val tell = { at: String -> asked += at; Openable("Quiet Orchard.m4b", 512_000_000) }
        val copy = { at: String, name: String? -> copied += at to name; SongRef("/data/user/0/app.lyrify/cache/opened/1/0/$name") }
        // A file manager's own address that matches: the library's own file, asked once, no copy.
        assertEquals(orchard.ref, openedRef(files, library, tell, copy))
        assertEquals(listOf(files), asked)
        assertTrue(copied.isEmpty())
        // No match: copied under the name the app gave, still asked only once.
        asked.clear()
        assertEquals("/data/user/0/app.lyrify/cache/opened/1/0/Quiet Orchard.m4b", openedRef(files, listOf(tide), tell, copy)?.path)
        assertEquals(listOf(files), asked)
        assertEquals(listOf(files to "Quiet Orchard.m4b"), copied)
        // MediaStore's address: by its id, the app never asked; an id the library lacks is copied, its name asked then.
        asked.clear()
        copied.clear()
        assertEquals(tide.ref, openedRef("content://media/external/audio/media/43", library, tell, copy))
        assertTrue(asked.isEmpty())
        openedRef("content://media/external/audio/media/99", library, tell, copy)
        assertEquals(listOf("content://media/external/audio/media/99"), asked)
        assertEquals(listOf("content://media/external/audio/media/99" to "Quiet Orchard.m4b"), copied)
        // Anything but a content address: nothing asked, nothing copied, nothing plays.
        asked.clear()
        copied.clear()
        assertNull(openedRef("file:///storage/emulated/0/Download/Quiet%20Orchard.m4b", library, tell, copy))
        assertTrue(asked.isEmpty())
        assertTrue(copied.isEmpty())
    }

    @Test
    fun `a copy in KJB's cache is the library's song when exactly one has its name and size, nothing outside the copies`() {
        val root = temp.newFolder("opened")
        val copy = File(root, "1800000000000/0/Quiet Orchard.m4b").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(1_234)) }
        val book = orchard.copy(sizeBytes = 1_234)
        assertEquals(book, copyMatch(copy, root, listOf(tide, book)))
        assertNull("the size differs", copyMatch(copy, root, listOf(tide, orchard)))
        assertNull("two songs match", copyMatch(copy, root, listOf(book, song("Music/Backup/Quiet Orchard.m4b").copy(sizeBytes = 1_234))))
        val outside = File(temp.newFolder("elsewhere"), "Quiet Orchard.m4b").apply { writeBytes(ByteArray(1_234)) }
        assertNull("not a copy", copyMatch(outside, root, listOf(book)))
        assertNull("gone", copyMatch(File(root, "1800000000000/1/Quiet Orchard.m4b"), root, listOf(book)))
    }

    @Test
    fun `the name and size are read by their columns, whatever order the opening app gives them in`() {
        // A provider that hands back more columns than asked for, the size not first.
        val row = rows(listOf(OpenableColumns.DISPLAY_NAME, "_data_length", OpenableColumns.SIZE), listOf("Quiet Orchard.m4b", 7L, 512_000_000L))
        assertEquals(512_000_000L, firstLong(row, OpenableColumns.SIZE))
        assertEquals("Quiet Orchard.m4b", firstString(row, OpenableColumns.DISPLAY_NAME))
        // A column it leaves out, or leaves empty, or no row at all: unknown.
        assertNull(firstLong(rows(listOf(OpenableColumns.DISPLAY_NAME), listOf("Quiet Orchard.m4b")), OpenableColumns.SIZE))
        assertNull(firstLong(rows(listOf(OpenableColumns.SIZE), listOf(null)), OpenableColumns.SIZE))
        assertNull(firstString(rows(listOf(OpenableColumns.DISPLAY_NAME), null), OpenableColumns.DISPLAY_NAME))
    }

    @Test
    fun `an open never logs a file's name, address or path, only exception names and counts`() {
        assertEquals(listOf("uri", "file.path", "name"), unsafeLogged("""Log.w(LOG_TAG, "open: ${'$'}uri at ${'$'}{file.path} as ${'$'}name (${'$'}{e.javaClass.simpleName})")"""))
        // Unit tests run in the module's folder.
        val source = File("src/main/java/app/lyrify/player/OpenedFiles.kt").readText()
        assertEquals(emptyList<String>(), unsafeLogged(source))
    }

    /** What the log lines of [source] put in besides an exception's class name and a count. */
    private fun unsafeLogged(source: String): List<String> =
        source.lines().filter { Regex("""\bLog\.(v|d|i|w|e|wtf)\(""").containsMatchIn(it) }.flatMap { line ->
            Regex("""\$\{([^}]*)\}|\$([A-Za-z_]\w*)""").findAll(line).map { it.groupValues[1].ifEmpty { it.groupValues[2] } }.toList()
        }.filterNot { it == "e.javaClass.simpleName" || Regex("""[\w.]+\.size( - [\w.]+\.size)?""").matches(it) }

    /** A provider's answer: one row of [values] under [columns], or none when [values] is null. */
    private fun rows(columns: List<String>, values: List<Any?>?): Cursor =
        Proxy.newProxyInstance(Cursor::class.java.classLoader, arrayOf(Cursor::class.java)) { _, method, args ->
            when (method.name) {
                "getColumnIndex" -> columns.indexOf(args!![0])
                "moveToFirst" -> values != null
                "isNull" -> values!![args!![0] as Int] == null
                "getLong" -> values!![args!![0] as Int] as Long
                "getString" -> values!![args!![0] as Int] as String
                "getColumnCount" -> columns.size
                else -> null
            }
        } as Cursor

    @Test
    fun `a batch of copies stays while any queue holds one of its files, and the newest always stays`() {
        val root = "/data/user/0/app.lyrify/cache/opened"
        val batches = listOf("1000", "2000", "3000")

        assertEquals("nothing queued: all but the newest go", listOf("1000", "2000"), staleBatches(batches, emptyList(), root))
        assertEquals(
            "the first open still plays while the second copies",
            listOf("2000"),
            staleBatches(batches, listOf("$root/1000/0/Paper Lantern.mp3", "/storage/emulated/0/Music/Tide.flac"), root),
        )
        assertEquals(emptyList<String>(), staleBatches(listOf("1000"), emptyList(), root))
        assertEquals("another folder's path holds nothing", listOf("1000", "2000"), staleBatches(batches, listOf("/data/other/1000/0/a.mp3", "${root}x/1000/0/a.mp3"), root))
    }

    /**
     * The notification shows an opened copy's own cover: KJB's session asks its cover provider for it by a key of the
     * copy's open and place, never its name or path (phone session 10: the cover was KJB's default).
     */
    @Test
    fun `an opened copy's cover is asked for by its open and place, never its name or path`() {
        val root = "/data/user/0/app.lyrify/cache/opened"
        assertEquals("opened-1000-0", openedCoverKey("$root/1000/0/Paper Harbour.mp3"))
        assertEquals("1000/0", openedCoverDir("opened-1000-0"))
        assertEquals("opened-1000-3", coverPath(song("Music/x.mp3").copy(ref = SongRef("$root/1000/3/Paper Harbour.mp3"))))
        // A library song's stays its MediaStore id, any other song's the default.
        assertEquals("42", coverPath(song("Music/Paper Harbour.mp3", id = 42)))
        assertEquals(DEFAULT_COVER, coverPath(song("Music/Paper Harbour.mp3")))
        // Nothing else is a copy, or names one.
        listOf(
            "/storage/emulated/0/Music/Paper Harbour.mp3", "/storage/emulated/0/Music/opened/1000/0/Paper Harbour.mp3", "$root/1000/Paper Harbour.mp3",
            "$root/10a0/0/Paper Harbour.mp3", "$root/1000/x/Paper Harbour.mp3", "$root/1000/0/", "cache/opened/1000/0",
        ).forEach { assertNull(it, openedCoverKey(it)) }
        listOf("opened-1000", "opened-1000-0-1", "opened-..-0", "opened--0", "opened-1000-x", "opened-1000/0", "42", DEFAULT_COVER, "").forEach { assertNull(it, openedCoverDir(it)) }
    }

    // An opened copy's cover and tags, from a file another app gave (made-up bytes)

    private val mb = 1024 * 1024

    private fun bytes(write: DataOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().also { DataOutputStream(it).use(write) }.toByteArray()

    private fun file(write: DataOutputStream.() -> Unit): File = temp.newFile().apply { writeBytes(bytes(write)) }

    private fun DataOutputStream.ascii(text: String) = write(text.toByteArray(Charsets.ISO_8859_1))

    /** An ID3v2.3 tag's header: [size] bytes of frames follow it. */
    private fun DataOutputStream.id3(size: Int) {
        ascii("ID3")
        write(byteArrayOf(3, 0, 0))
        write(byteArrayOf((size shr 21 and 0x7f).toByte(), (size shr 14 and 0x7f).toByte(), (size shr 7 and 0x7f).toByte(), (size and 0x7f).toByte()))
    }

    /** A FLAC metadata block's header: its [type] and [length], and whether it is the [last]. */
    private fun DataOutputStream.block(type: Int, length: Int, last: Boolean = false) = writeInt((if (last) 1 shl 31 else 0) or (type shl 24) or length)

    /** A FLAC picture block whose picture says it holds [says] bytes, [data] written. */
    private fun DataOutputStream.picture(says: Int, data: Int, last: Boolean = true) {
        block(6, 4 + 4 + 10 + 4 + 16 + 4 + data, last)
        writeInt(3)
        writeInt(10)
        ascii("image/jpeg")
        writeInt(0)
        write(ByteArray(16))
        writeInt(says)
        write(ByteArray(data))
    }

    /** An MP4 atom of [type] holding [payload]. */
    private fun atom(type: String, vararg payload: ByteArray): ByteArray = bytes {
        writeInt(8 + payload.sumOf { it.size })
        ascii(type)
        payload.forEach(::write)
    }

    private fun mp4(vararg top: ByteArray): File = file {
        write(atom("ftyp", "M4A isom".toByteArray()))
        top.forEach(::write)
        write(atom("mdat", ByteArray(2_000)))
    }

    /** An Ogg page carrying [laces] (each a segment's length) of zeros. */
    private fun DataOutputStream.page(vararg laces: Int) {
        ascii("OggS")
        write(ByteArray(22))
        writeByte(laces.size)
        laces.forEach { writeByte(it) }
        write(ByteArray(laces.sum()))
    }

    /**
     * Android's retriever hands over an embedded picture whole, whatever its size (phone session 11 ended in an
     * OutOfMemoryError elsewhere): an opened copy's picture is bounded by its tags' own sizes before any is read.
     */
    @Test
    fun `an MP3's or a FLAC's picture is bounded by its ID3v2 tag, its picture and comment blocks and what its picture says`() {
        assertEquals("an ID3v2 tag whole", 1_010L, embeddedPictureBytes(file { id3(1_000); write(ByteArray(1_000)); write(ByteArray(400)) }))
        assertTrue("a tag that says 9 MB", embeddedPictureBytes(file { id3(9 * mb); write(ByteArray(100)) })!! > MAX_COVER_BYTES)
        val streamInfo = bytes { block(0, 34); write(ByteArray(34)) }
        assertEquals("a picture block", 4_042L, embeddedPictureBytes(file { ascii("fLaC"); write(streamInfo); picture(says = 4_000, data = 4_000) }))
        assertTrue("a small block whose picture says 9 MB", embeddedPictureBytes(file { ascii("fLaC"); write(streamInfo); picture(says = 9 * mb, data = 10) })!! > MAX_COVER_BYTES)
        assertEquals("a comment block, where a picture may be written as text", 5_000L, embeddedPictureBytes(file { ascii("fLaC"); write(streamInfo); block(4, 5_000, last = true); write(ByteArray(5_000)) }))
        assertEquals("no picture", 0L, embeddedPictureBytes(file { ascii("fLaC"); block(0, 34, last = true); write(ByteArray(34)) }))
        assertEquals("a FLAC after an ID3v2 tag: both", 4_042L, embeddedPictureBytes(file { id3(100); write(ByteArray(100)); ascii("fLaC"); write(streamInfo); picture(4_000, 4_000) }))
    }

    @Test
    fun `an MP4's picture is bounded by its metadata items, its sample tables never counted`() {
        val items = atom("ilst", atom("\u00a9nam", atom("data", ByteArray(40))), atom("covr", atom("data", ByteArray(3_000))))
        val iso = atom("meta", ByteArray(4), atom("hdlr", ByteArray(25)), items)
        val tables = atom("trak", atom("mdia", ByteArray(20_000)))
        assertEquals(3_016L, embeddedPictureBytes(mp4(atom("moov", tables, atom("udta", iso)))))
        // QuickTime's meta, with no version and flags.
        assertEquals(3_016L, embeddedPictureBytes(mp4(atom("moov", atom("meta", atom("hdlr", ByteArray(25)), items)))))
        assertEquals("no metadata", 0L, embeddedPictureBytes(mp4(atom("moov", tables))))
        // An ID32 atom's ID3v2 tag that says 9 MB, and an item that says it.
        val id32 = atom("ID32", ByteArray(6), bytes { id3(9 * mb) })
        assertTrue(embeddedPictureBytes(mp4(atom("moov", atom("udta", atom("meta", ByteArray(4), id32)))))!! > MAX_COVER_BYTES)
        val big = bytes { writeInt(9 * mb); ascii("covr") }
        assertTrue(embeddedPictureBytes(mp4(atom("moov", atom("udta", atom("meta", ByteArray(4), atom("ilst", big))))))!! > MAX_COVER_BYTES)
        assertNull("an atom shorter than its header", embeddedPictureBytes(mp4(atom("moov", bytes { writeInt(4); ascii("udta") }))))
    }

    @Test
    fun `an Ogg's picture is bounded by its comment header, and any other file is read for none`() {
        assertEquals(520L, embeddedPictureBytes(file { page(30); page(255, 255, 10); page(200) }))
        assertEquals("a comment header over two pages", 65_380L, embeddedPictureBytes(file { page(30); page(*IntArray(255) { 255 }); page(255, 100) }))
        assertNull("cut short", embeddedPictureBytes(file { page(30); page(255, 255) }))
        assertNull("a WAV", embeddedPictureBytes(file { ascii("RIFF"); writeInt(100); ascii("WAVE"); write(ByteArray(100)) }))
        assertNull("no format", embeddedPictureBytes(file { write(ByteArray(64) { it.toByte() }) }))
        assertNull("empty", embeddedPictureBytes(temp.newFile()))
        assertNull("gone", embeddedPictureBytes(File(temp.root, "gone.mp3")))
    }

    @Test
    fun `a cover is decoded at the largest halving still at least the size asked, and never for no size`() {
        assertEquals(8, coverSample(4_000, 3_000, 500))
        assertEquals(1, coverSample(300, 300, 500))
        assertNull(coverSample(300, 300, 0))
        assertNull(coverSample(300, 300, -1))
        assertNull("not a picture", coverSample(-1, -1, 500))
        assertEquals(1 shl 30, coverSample(Int.MAX_VALUE, 1, 1))
    }

    @Test
    fun `an opened copy's title, artist and album are kept to a few hundred characters, blank ones none`() {
        assertEquals("Harbour Lamp", tagText("Harbour Lamp"))
        assertEquals(MAX_TAG_TEXT, tagText("Lamp ".repeat(10_000))!!.length)
        assertNull(tagText("  "))
        assertNull(tagText(null))
    }
}
