package app.lyrify.tags

import android.content.ComponentName
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.ResultReceiver
import android.provider.MediaStore
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lyrify.LOG_TAG
import app.lyrify.Lyrify
import app.lyrify.data.PathLocks
import app.lyrify.data.SharedWrites
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.ReadableByteChannel
import java.nio.channels.WritableByteChannel
import java.nio.file.StandardOpenOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The tag engine on the phone, with the real TagLib (`:taglib`): a property-map round trip per format keeps every other
 * field and picture and the audio payload; a forced failure and a kill mid-write put the song back byte for byte; Undo
 * puts it back byte for byte; the MediaStore "rw" path keeps the song's id (on a probe file KJB makes and deletes in its
 * test folder); and `libtaglib.so` is aligned to 16 KB. Fixtures: app/src/androidTest/assets/tags, made by
 * tools/make_tag_fixtures.py, made-up names only. Everything else stays in the debug app's private storage.
 */
@RunWith(AndroidJUnit4::class)
class TagEngineDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets
    private val root = File(context.filesDir, "tag-device-test")
    private val songsDir = File(root, "songs")
    private val tags = TagLibFile(context.filesDir)

    private val fixtures = listOf(
        "paper-comet-v23.mp3",
        "lanterns-over-kelp-v24.mp3",
        "salt-clock.flac",
        "northbound-tide.m4a",
        "paper-comet-messy.ogg",
        "quiet-breakwater.opus",
        "harbour-lights-ape.mp3",
    )

    /** What Covers & tags writes (covers-tags-spec "What is written"), with made-up names and ids. */
    private val edit by lazy {
        TagEdit(
            fields = mapOf(
                "TITLE" to listOf("Tin Roof 𝄞"),
                "ARTIST" to listOf("Kit Calloway feat. Mira Solen"),
                "ARTISTS" to listOf("Kit Calloway", "Mira Solen"),
                "ALBUM" to listOf("Harbour Glass"),
                "ALBUMARTIST" to listOf("Kit Calloway"),
                "TRACKNUMBER" to listOf("3/12"),
                "DISCNUMBER" to listOf("1/2"),
                "MUSICBRAINZ_ALBUMID" to listOf("00000000-0000-4000-8000-000000000001"),
                "MUSICBRAINZ_TRACKID" to listOf("00000000-0000-4000-8000-000000000002"),
            ),
            cover = CoverEdit.Replace(TagPicture(jpeg(), "", FRONT_COVER, "image/jpeg")),
        )
    }

    @Before
    fun setUp() {
        root.deleteRecursively()
        songsDir.mkdirs()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun fixture(name: String, into: File = songsDir): File =
        File(into, name).apply { assets.open("tags/$name").use { input -> outputStream().use { input.copyTo(it) } } }

    private fun jpeg(): ByteArray {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF2E7D6B.toInt()) }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
    }

    // The round trip, per format

    /** How a fixture is named in messages and logs: its number and format, never its file name. */
    private fun label(index: Int, file: File): String = "fixture ${index + 1} (${PayloadHash.of(file.toPath())?.format})"

    @Test
    fun aRoundTripChangesOnlyTheFieldsAskedForInEveryFormat() {
        for ((index, fixtureName) in fixtures.withIndex()) {
            val file = fixture(fixtureName)
            val name = label(index, file)
            val mp3 = fixtureName.endsWith(".mp3")
            val original = file.readBytes()
            val before = checkNotNull(tags.read(file)) { "$name: TagLib can't read it" }
            val payload = checkNotNull(PayloadHash.of(file.toPath())) { "$name: no payload" }
            assertTrue("$name: no audio length", before.durationMs > 0)
            for (key in listOf("LYRICS", "REPLAYGAIN_TRACK_GAIN", "REPLAYGAIN_TRACK_PEAK")) {
                assertTrue("$name: the fixture has no $key: ${before.fields.keys}", key in before.fields)
            }
            assertTrue("$name: the fixture has no comment: ${before.fields.keys}", "COMMENT" in before.fields || "DESCRIPTION" in before.fields)
            assertTrue("$name: the fixture has no second cover", before.pictures.isNotEmpty())

            val applied = edit.applyTo(before)
            assertTrue("$name: TagLib could not write", tags.write(file, applied.fields, applied.pictures, Id3Version.V2_3))
            val after = checkNotNull(tags.read(file))

            applied.changes.forEach { assertEquals("$name ${it.key}", it.new, after.fields[it.key]) }
            val untouched = before.fields.keys - applied.changes.map { it.key }.toSet()
            untouched.forEach { key -> assertEquals("$name $key", before.fields[key], after.fields[key]) }
            assertEquals("$name: the pictures", applied.pictures.map { it.sha256 }, after.pictures.map { it.sha256 })
            // The back cover (in M4A, which has no picture types, the second cover) is still there, byte for byte.
            val back = before.pictures.last()
            assertTrue("$name: the back cover", after.pictures.any { it.data.contentEquals(back.data) })
            assertEquals("$name: the audio length", before.durationMs, after.durationMs)
            assertEquals("$name: the payload", payload, PayloadHash.of(file.toPath()))

            val written = file.readBytes()
            if (mp3) {
                // ID3v2.3, except a v2.4 tag with frames v2.3 would drop or convert (the year's TDRC) stays v2.4.
                assertEquals("$name: the ID3v2 version", original[3].toInt(), written[3].toInt())
                val v1 = original.takeLast(128).take(3) == "TAG".toByteArray().toList()
                if (v1) {
                    assertArrayEquals("$name: the ID3v1 tag is left as it is", original.copyOfRange(original.size - 128, original.size), written.copyOfRange(written.size - 128, written.size))
                } else {
                    assertFalse("$name: no ID3v1 tag is added", written.takeLast(128).take(3) == "TAG".toByteArray().toList())
                }
            }
            Log.i(LOG_TAG, "tags device test: ${payload.format} ${original.size} -> ${written.size} bytes, ${before.fields.size} fields")
        }
    }

    // An MP3's APE tag

    /** The items of the APE tag before an MP3's ID3v1 tag (or at its end), by upper-case key: each value's bytes. */
    private fun apeItems(bytes: ByteArray): Map<String, ByteArray> {
        val end = if (bytes.size >= 128 && String(bytes, bytes.size - 128, 3, Charsets.ISO_8859_1) == "TAG") bytes.size - 128 else bytes.size
        val footer = ByteBuffer.wrap(bytes, end - 32, 32).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("APETAGEX", String(bytes, end - 32, 8, Charsets.ISO_8859_1))
        val size = footer.getInt(end - 32 + 12)
        val count = footer.getInt(end - 32 + 16)
        var at = end - size
        return (0 until count).associate {
            val item = ByteBuffer.wrap(bytes, at, 8).order(ByteOrder.LITTLE_ENDIAN)
            val length = item.getInt(at)
            var keyEnd = at + 8
            while (bytes[keyEnd] != 0.toByte()) keyEnd++
            val key = String(bytes, at + 8, keyEnd - at - 8, Charsets.ISO_8859_1).uppercase()
            val value = bytes.copyOfRange(keyEnd + 1, keyEnd + 1 + length)
            at = keyEnd + 1 + length
            key to value
        }
    }

    @Test
    fun anMp3sApeTagGetsTheChangedKeysAndCoversAndKeepsTheRest() {
        val file = fixture("harbour-lights-ape.mp3")
        val before = apeItems(file.readBytes())
        val v1 = file.readBytes().takeLast(128)

        // Fields only: the APE covers stay byte for byte.
        val fieldsOnly = TagEdit(fields = mapOf("TITLE" to listOf("Tin Roof 𝄞")))
        val tagsBefore = checkNotNull(tags.read(file))
        val applied = fieldsOnly.applyTo(tagsBefore)
        assertTrue(tags.write(file, applied.fields, null, Id3Version.V2_3))
        val afterFields = apeItems(file.readBytes())
        assertEquals("Tin Roof 𝄞", String(afterFields.getValue("TITLE"), Charsets.UTF_8))
        assertArrayEquals(before.getValue("KJB ONLY"), afterFields.getValue("KJB ONLY"))
        assertArrayEquals(before.getValue("COVER ART (FRONT)"), afterFields.getValue("COVER ART (FRONT)"))
        assertArrayEquals(before.getValue("COVER ART (BACK)"), afterFields.getValue("COVER ART (BACK)"))

        // A new front cover: the APE front cover follows, the back cover and the item of its own stay.
        val newFront = (edit.cover as CoverEdit.Replace).picture
        val withCover = TagEdit(cover = edit.cover).applyTo(checkNotNull(tags.read(file)))
        assertTrue(tags.write(file, withCover.fields, withCover.pictures, Id3Version.V2_3))
        val afterCover = apeItems(file.readBytes())
        assertArrayEquals(byteArrayOf(0) + newFront.data, afterCover.getValue("COVER ART (FRONT)"))
        assertArrayEquals(before.getValue("COVER ART (BACK)"), afterCover.getValue("COVER ART (BACK)"))
        assertArrayEquals(before.getValue("KJB ONLY"), afterCover.getValue("KJB ONLY"))
        assertEquals(v1, file.readBytes().takeLast(128))
    }

    // The writer, on private copies standing in for songs

    /** The next in-place write fails with an IOException after so many bytes. */
    private var failAfter: Long? = null
    private var scanned: Long? = 41

    private inner class PrivateSongs : SongFiles {
        override fun mounted(path: String): Boolean = true

        override fun sameFile(mediaStoreId: Long, path: String): Boolean = mediaStoreId == 41L

        override fun idOf(path: String): Long = 41

        override fun openInPlace(song: SongTarget): FileChannel {
            val channel = FileChannel.open(File(song.path).toPath(), StandardOpenOption.WRITE)
            val fail = failAfter.also { failAfter = null } ?: return channel
            return CutShort(channel, fail)
        }

        override suspend fun scan(path: String): Long? = scanned
    }

    private val dirs = TagDirs(root, ::fsyncDirectory)
    private val locks = PathLocks()
    private val free = object : BusyFiles {
        override fun check(path: String): Busy = Busy.FREE
    }

    private fun writer(songs: SongFiles = PrivateSongs(), writes: SharedWrites = SharedWrites.ANYWHERE): Pair<SafeWriter, JournalRecovery> {
        val journal = WriteJournal(dirs.journal, dirs.syncDir)
        val undo = UndoStore(dirs)
        val writer = SafeWriter(dirs, tags, songs, free, locks, writes, journal, undo, freeBytes = { Long.MAX_VALUE / 4 }, clock = System::currentTimeMillis)
        return writer to JournalRecovery(dirs, journal, undo, songs, locks, writes)
    }

    @Test
    fun aForcedFailurePutsTheSongBackByteForByte() {
        for ((index, fixtureName) in fixtures.withIndex()) {
            val file = fixture(fixtureName)
            val name = label(index, file)
            val original = file.readBytes()
            val song = SongTarget(file.path, 41)

            // A failure after the write began, past the tag where every format's new bytes differ from the old (M4A
            // keeps its tags in moov, at the end of these files): the backup goes back at once.
            failAfter = original.size - 200L
            val failed = runBlocking { writer().first.write(listOf(WriteJob(song, edit)), EditKind.SONG, "Tin Roof") } as BatchResult.Done
            assertEquals(name, SongOutcome.LEFT_AS_IT_WAS, failed.songs.single().outcome)
            assertArrayEquals(name, original, file.readBytes())

            scanned = null
            val unscanned = runBlocking { writer().first.write(listOf(WriteJob(song, edit)), EditKind.SONG, "Tin Roof") } as BatchResult.Done
            assertEquals(name, SongOutcome.LEFT_AS_IT_WAS, unscanned.songs.single().outcome)
            assertArrayEquals(name, original, file.readBytes())
            scanned = 41
        }
    }

    @Test
    fun aWriteKeepsThePayloadAndUndoPutsTheSongBackByteForByte() {
        for ((index, fixtureName) in fixtures.withIndex()) {
            root.deleteRecursively()
            songsDir.mkdirs()
            val file = fixture(fixtureName)
            val name = label(index, file)
            val original = file.readBytes()
            val payload = PayloadHash.of(file.toPath())
            val (writer, _) = writer()

            val result = runBlocking { writer.write(listOf(WriteJob(SongTarget(file.path, 41), edit)), EditKind.SONG, "Tin Roof") } as BatchResult.Done
            assertEquals(name, SongOutcome.WRITTEN, result.songs.single().outcome)
            assertEquals(name, payload, PayloadHash.of(file.toPath()))
            assertEquals(name, "Tin Roof 𝄞", tags.read(file)?.fields?.get("TITLE")?.single())

            val undone = runBlocking { writer.undo(checkNotNull(result.entryId)) } as BatchResult.Done
            assertEquals(name, SongOutcome.WRITTEN, undone.songs.single().outcome)
            assertArrayEquals(name, original, file.readBytes())
        }
    }

    // MediaStore's "rw" path, on a probe file KJB makes in the test folder and deletes

    /** A probe song in the test folder holding [bytes], with its MediaStore id and path; deleted after [block]. */
    private fun withProbe(bytes: ByteArray, block: (id: Long, path: String) -> Unit) {
        assumeTrue("shared storage is not mounted", Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED)
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "kjb-t1-probe-${System.currentTimeMillis()}.mp3")
            put(MediaStore.MediaColumns.RELATIVE_PATH, SharedWrites.TEST_FOLDER)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = checkNotNull(resolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)) { "MediaStore would not make the probe" }
        try {
            resolver.openOutputStream(uri)!!.use { it.write(bytes) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            val path = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)!!.use { it.moveToFirst(); it.getString(0) }
            assertTrue(SharedWrites.inTestFolder(path))
            block(ContentUris.parseId(uri), path)
        } finally {
            resolver.delete(uri, null, null)
        }
    }

    @Test
    fun theInPlaceWriteThroughMediaStoreKeepsTheSongsIdAndDateAdded() {
        val original = assets.open("tags/paper-comet-v23.mp3").use { it.readBytes() }
        withProbe(original) { id, path ->
            val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), id)
            fun row(): Triple<String, Long, String> = context.contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.TITLE),
                null, null, null,
            )!!.use { cursor ->
                assertTrue(cursor.moveToFirst())
                Triple(cursor.getString(0), cursor.getLong(1), cursor.getString(2))
            }
            val added = row().second
            val (writer, _) = writer(PhoneSongFiles(context), Lyrify.writes)

            val result = runBlocking { writer.write(listOf(WriteJob(SongTarget(path, id), edit)), EditKind.SONG, "Tin Roof") } as BatchResult.Done

            assertEquals(SongOutcome.WRITTEN, result.songs.single().outcome)
            val (pathAfter, addedAfter, title) = row()
            assertEquals(path, pathAfter)
            assertEquals(added, addedAfter)
            assertEquals("Tin Roof 𝄞", title)

            val undone = runBlocking { writer.undo(checkNotNull(result.entryId)) } as BatchResult.Done
            assertEquals(SongOutcome.WRITTEN, undone.songs.single().outcome)
            assertArrayEquals(original, File(path).readBytes())
            assertEquals(added, row().second)
        }
    }

    // A kill from another process, mid-write

    /**
     * The tag writer runs in a process of its own (TagKillService, debug builds only) and is stopped halfway through
     * writing the probe through MediaStore's "rw" descriptor, with nothing fsynced; this process kills it with SIGKILL.
     * Then the recovery, here, as at the next start, puts the probe back byte for byte.
     */
    @Test
    fun aSigkillMidWriteInAnotherProcessIsPutBackByTheRecovery() {
        val original = assets.open("tags/paper-comet-v23.mp3").use { it.readBytes() }
        val killRoot = "tag-kill-test"
        File(context.filesDir, killRoot).deleteRecursively()
        withProbe(original) { id, path ->
            val replied = CountDownLatch(1)
            var code = -1
            var pid = -1
            val reply = object : ResultReceiver(Handler(Looper.getMainLooper())) {
                override fun onReceiveResult(resultCode: Int, resultData: Bundle) {
                    code = resultCode
                    pid = resultData.getInt(TagKillService.PID, -1)
                    replied.countDown()
                }
            }
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) = Unit

                override fun onServiceDisconnected(name: ComponentName) = Unit
            }
            val intent = Intent(context, TagKillService::class.java)
                .putExtra(TagKillService.EXTRA_PATH, path)
                .putExtra(TagKillService.EXTRA_ID, id)
                .putExtra(TagKillService.EXTRA_ROOT, killRoot)
                .putExtra(TagKillService.EXTRA_BYTES, original.size / 2L)
                .putExtra(TagKillService.EXTRA_REPLY, reply)
            assertTrue(context.bindService(intent, connection, Context.BIND_AUTO_CREATE))
            try {
                assertTrue("the helper did not reach the middle of the write", replied.await(60, TimeUnit.SECONDS))
                assertEquals(TagKillService.STOPPED, code)
                assertTrue(pid > 0 && pid != Process.myPid())
                Process.killProcess(pid)
                val dead = System.currentTimeMillis() + 10_000
                while (File("/proc/$pid").exists() && System.currentTimeMillis() < dead) Thread.sleep(50)
                assertFalse("the helper is still alive", File("/proc/$pid").exists())
            } finally {
                runCatching { context.unbindService(connection) }
            }
            assertFalse("the probe is not torn", original.contentEquals(File(path).readBytes()))

            val dirs = TagDirs(File(context.filesDir, killRoot), ::fsyncDirectory)
            val journal = WriteJournal(dirs.journal, dirs.syncDir)
            val recovery = JournalRecovery(dirs, journal, UndoStore(dirs), PhoneSongFiles(context), PathLocks(), Lyrify.writes)
            val recovered = runBlocking { recovery.recover(recovery.holdPending()) }

            assertEquals(Recovered(settled = 1, restored = 1), recovered)
            assertArrayEquals(original, File(path).readBytes())
            assertEquals(0, journal.pending().size)
        }
        File(context.filesDir, killRoot).deleteRecursively()
    }

    // 16 KB pages

    @Test
    fun libtaglibIsAlignedTo16KbPages() {
        // Loads it, as the writer would.
        assertNotNull(tags.read(fixture("salt-clock.flac")))
        val dir = File(context.applicationInfo.nativeLibraryDir)
        val aligns = dir.listFiles().orEmpty().filter { it.name.endsWith(".so") }.sortedBy { it.name }.associate { it.name to loadAligns(it) }
        Log.i(LOG_TAG, "tags device test: page size ${Os.sysconf(OsConstants._SC_PAGESIZE)}, LOAD alignments $aligns")
        val taglib = checkNotNull(aligns["libtaglib.so"]) { "libtaglib.so is not in ${aligns.keys}" }
        assertTrue("libtaglib.so: $taglib", taglib.isNotEmpty() && taglib.all { it >= 0x4000 })
    }

    /** The p_align of each PT_LOAD segment of a 64-bit little-endian ELF file. */
    private fun loadAligns(file: File): List<Long> {
        val bytes = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        val headers = bytes.getLong(0x20).toInt()
        val size = bytes.getShort(0x36).toInt()
        val count = bytes.getShort(0x38).toInt()
        return (0 until count).map { headers + it * size }.filter { bytes.getInt(it) == 1 }.map { bytes.getLong(it + 0x30) }
    }

    /** A file channel that fails with an IOException after [limit] bytes. */
    private class CutShort(private val inner: FileChannel, private val limit: Long) : FileChannel() {
        private var written = 0L

        override fun write(src: ByteBuffer): Int {
            if (written + src.remaining() > limit) {
                inner.write(src.duplicate().limit(src.position() + (limit - written).toInt()) as ByteBuffer)
                inner.force(true)
                written = limit
                throw IOException("cut short")
            }
            return inner.write(src).also { written += it }
        }

        override fun read(dst: ByteBuffer): Int = inner.read(dst)
        override fun read(dsts: Array<out ByteBuffer>, offset: Int, length: Int): Long = inner.read(dsts, offset, length)
        override fun write(srcs: Array<out ByteBuffer>, offset: Int, length: Int): Long = srcs.drop(offset).take(length).sumOf { write(it).toLong() }
        override fun position(): Long = inner.position()
        override fun position(newPosition: Long): FileChannel = apply { inner.position(newPosition) }
        override fun size(): Long = inner.size()
        override fun truncate(size: Long): FileChannel = apply { inner.truncate(size) }
        override fun force(metaData: Boolean) = inner.force(metaData)
        override fun transferTo(position: Long, count: Long, target: WritableByteChannel): Long = inner.transferTo(position, count, target)
        override fun transferFrom(src: ReadableByteChannel, position: Long, count: Long): Long = inner.transferFrom(src, position, count)
        override fun read(dst: ByteBuffer, position: Long): Int = inner.read(dst, position)
        override fun write(src: ByteBuffer, position: Long): Int = inner.write(src, position)
        override fun map(mode: MapMode, position: Long, size: Long): MappedByteBuffer = inner.map(mode, position, size)
        override fun lock(position: Long, size: Long, shared: Boolean): FileLock = inner.lock(position, size, shared)
        override fun tryLock(position: Long, size: Long, shared: Boolean): FileLock? = inner.tryLock(position, size, shared)
        override fun implCloseChannel() = inner.close()
    }
}
