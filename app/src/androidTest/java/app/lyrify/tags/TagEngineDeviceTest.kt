package app.lyrify.tags

import android.content.ComponentName
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.ResultReceiver
import android.provider.MediaStore
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lyrify.LOG_TAG
import app.lyrify.Lyrify
import app.lyrify.data.PathLocks
import app.lyrify.data.SharedWrites
import com.kyant.taglib.TagLib
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

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
        "kelp-lighthouse-v24-ape.mp3",
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
            // The writer's rule: ID3v2.3, except a v2.4 tag with frames v2.3 would drop or convert (the year's TDRC).
            val version = SafeWriter.id3Version(before, Id3Version.V2_3)
            assertTrue("$name: TagLib could not write", tags.write(file, applied.fields, applied.pictures, version))
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
                assertEquals("$name: the ID3v2 version", version.number, written[3].toInt())
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

    // Native error paths: a clean answer to Kotlin, never a crash, and the descriptor closed

    /** What `/proc/self/fd/[fd]` names now, or null when the descriptor is closed. */
    private fun named(fd: Int): String? = try {
        Os.readlink("/proc/self/fd/$fd")
    } catch (_: ErrnoException) {
        null
    }

    /** A descriptor of [file] for TagLib, which owns it from then on; what it named, to check it was closed. */
    private fun descriptor(file: File, mode: Int = ParcelFileDescriptor.MODE_READ_ONLY): Pair<Int, String?> {
        val fd = ParcelFileDescriptor.open(file, mode).detachFd()
        return fd to named(fd)
    }

    @Test
    fun nativeErrorPathsAnswerCleanlyAndCloseTheirDescriptor() {
        // A descriptor /proc/self/fd can't resolve (readlink fails): null or false, nothing read past its buffer.
        val nowhere = 1_000_000
        assertNull(TagLib.getMetadata(nowhere, readPictures = true))
        assertNull(TagLib.getAudioProperties(nowhere))
        assertNull(TagLib.getId3v2Frames(nowhere))
        assertFalse(TagLib.saveTags(nowhere, HashMap(), null, 3))

        // Files TagLib refuses: garbage and an empty file under allowed names. Null and false, the file untouched, and
        // each call's descriptor closed.
        val garbage = File(songsDir, "garbage.flac").apply { writeBytes(ByteArray(4_000) { (it * 7).toByte() }) }
        val empty = File(songsDir, "empty.m4a").apply { writeBytes(ByteArray(0)) }
        for (file in listOf(garbage, empty)) {
            val before = file.readBytes()
            assertNull(tags.read(file))
            assertFalse(tags.write(file, mapOf("TITLE" to listOf("Tin Roof")), null, Id3Version.V2_3))
            assertArrayEquals(before, file.readBytes())
            val (read, readName) = descriptor(file)
            assertNull(TagLib.getMetadata(read, readPictures = true))
            assertTrue("a read left its descriptor open", named(read) != readName)
            val (write, writeName) = descriptor(file, ParcelFileDescriptor.MODE_READ_WRITE)
            assertFalse(TagLib.saveTags(write, HashMap(), null, 3))
            assertTrue("a save left its descriptor open", named(write) != writeName)
        }

        // A descriptor that is not a regular file (a pipe): refused before anything is read, null or false at once, and
        // the descriptor closed. The pipe's write end stays open while TagLib has it, so a read would wait for good: each
        // call runs on a thread of its own under a time limit, and closing the write end afterwards ends any read still
        // waiting, so this test fails rather than hangs.
        val calls = listOf<Pair<String, (Int) -> Any?>>(
            "getMetadata" to { fd -> TagLib.getMetadata(fd, readPictures = true) },
            "getAudioProperties" to { fd -> TagLib.getAudioProperties(fd) },
            "getId3v2Frames" to { fd -> TagLib.getId3v2Frames(fd) },
            "saveTags" to { fd -> TagLib.saveTags(fd, HashMap(), null, 3) },
        )
        for ((name, call) in calls) {
            val pipe = ParcelFileDescriptor.createPipe()
            val readEnd = pipe[0].detachFd()
            val pipeName = named(readEnd)
            val worker = Executors.newSingleThreadExecutor()
            try {
                val answer = worker.submit<Any?> { call(readEnd) }
                val result = try {
                    answer.get(10, TimeUnit.SECONDS)
                } catch (_: TimeoutException) {
                    throw AssertionError("$name read the pipe instead of refusing it")
                }
                assertTrue("$name answered $result", result == null || result == false)
                assertTrue("$name left the pipe's descriptor open", named(readEnd) != pipeName)
            } finally {
                pipe[1].close()
                worker.shutdown()
            }
        }

        // An ID3v2 version TagLib can't write: refused, the descriptor closed all the same.
        val song = fixture("paper-comet-v23.mp3")
        val (bad, badName) = descriptor(song, ParcelFileDescriptor.MODE_READ_WRITE)
        assertFalse(TagLib.saveTags(bad, HashMap(), null, 2))
        assertTrue(named(bad) != badName)
    }

    @Test
    fun malformedUtf16AndU0000CrossJniWithoutBreakingAnything() {
        // A lone surrogate: TagLib writes it, or refuses the save; no crash either way, and the song still reads.
        val mp3 = fixture("paper-comet-v23.mp3")
        val mp3Before = mp3.readBytes()
        val tagsBefore = checkNotNull(tags.read(mp3))
        val lone = tagsBefore.fields + ("TITLE" to listOf("Tin \uD800Roof"))
        if (!tags.write(mp3, lone, null, Id3Version.V2_3)) assertArrayEquals(mp3Before, mp3.readBytes())
        assertNotNull(tags.read(mp3))

        // U+0000 in a value or a key, which TagLib would cut a value short at or read as a separator: the native save
        // refuses it (KJB's writer refuses it before TagLib, too), and nothing is written.
        val flac = fixture("salt-clock.flac")
        val flacBefore = flac.readBytes()
        val fields = checkNotNull(tags.read(flac)).fields
        assertFalse(tags.write(flac, fields + ("TITLE" to listOf("Tin\u0000Roof")), null, Id3Version.V2_3))
        assertFalse(tags.write(flac, fields + ("TITLE\u0000X" to listOf("Tin Roof")), null, Id3Version.V2_3))
        assertArrayEquals(flacBefore, flac.readBytes())

        // A value's length is its UTF-16 length: a long value outside the BMP comes back whole.
        val long = "𝄞".repeat(300) + " Tin Roof"
        assertTrue(tags.write(flac, fields + ("TITLE" to listOf(long)), null, Id3Version.V2_3))
        assertEquals(listOf(long), tags.read(flac)?.fields?.get("TITLE"))
    }

    // Real TagLib under the writer's rules (the JVM tests check the rules with a fake TagFile)

    @Test
    fun theWriterOnRealTagLibKeepsEveryOtherFieldTheV24FramesAndTheApeTagAndWritesTheCover() {
        val cases = listOf("paper-comet-v23.mp3", "kelp-lighthouse-v24-ape.mp3", "salt-clock.flac", "northbound-tide.m4a", "quiet-breakwater.opus")
        val front = (edit.cover as CoverEdit.Replace).picture
        for ((index, fixtureName) in cases.withIndex()) {
            root.deleteRecursively()
            songsDir.mkdirs()
            val file = fixture(fixtureName)
            val name = label(index, file)
            val before = checkNotNull(tags.read(file))
            // The fixtures with an APE tag ("paper" holds "ape" too).
            val apeBefore = if (fixtureName.endsWith("-ape.mp3")) apeItems(file.readBytes()) else null
            val (writer, _) = writer()

            val result = runBlocking { writer.write(listOf(WriteJob(SongTarget(file.path, 41), edit)), EditKind.SONG, "Tin Roof") } as BatchResult.Done

            // WRITTEN: the writer's own verify passed on TagLib's real output.
            assertEquals(name, SongOutcome.WRITTEN, result.songs.single().outcome)
            val after = checkNotNull(tags.read(file))
            val changed = edit.fields.keys
            (before.fields.keys - changed).forEach { key -> assertEquals("$name $key", before.fields[key], after.fields[key]) }
            assertEquals("$name: new keys", emptySet<String>(), after.fields.keys - before.fields.keys - changed)
            edit.fields.forEach { (key, values) -> assertEquals("$name $key", values, after.fields[key]) }
            // The cover written, every other picture kept (an M4A's first cover is its front cover).
            assertTrue("$name: the cover", after.pictures.any { it.data.contentEquals(front.data) })
            val replaced = if (fixtureName.endsWith(".m4a")) before.pictures.take(1) else before.pictures.filter { it.type == FRONT_COVER }
            (before.pictures - replaced.toSet()).forEach { kept -> assertTrue("$name: a picture lost", after.pictures.any { it.data.contentEquals(kept.data) }) }
            // ID3v2: the version kept; v2.4 frames v2.3 can't hold kept.
            assertEquals(name, before.id3v2Version, after.id3v2Version)
            if (before.id3v2Version == 4) assertTrue("$name: ${after.frames}", after.frames.containsAll(listOf("RVA2", "TMOO")))
            // The APE tag: the changed key and the front cover updated, the back cover and its own item kept.
            if (apeBefore != null) {
                val ape = apeItems(file.readBytes())
                assertEquals(name, "Tin Roof 𝄞", String(ape.getValue("TITLE"), Charsets.UTF_8))
                assertArrayEquals(name, byteArrayOf(0) + front.data, ape.getValue("COVER ART (FRONT)"))
                assertArrayEquals(name, apeBefore.getValue("COVER ART (BACK)"), ape.getValue("COVER ART (BACK)"))
                assertArrayEquals(name, apeBefore.getValue("KJB ONLY"), ape.getValue("KJB ONLY"))
            }
            Log.i(LOG_TAG, "tags device test: ${PayloadHash.of(file.toPath())?.format} written by the writer on TagLib")
        }
    }

    @Test
    fun removingTheFrontCoverRemovesTheApeTagsFrontCoverAndKeepsItsBackCover() {
        val file = fixture("kelp-lighthouse-v24-ape.mp3")
        val apeBefore = apeItems(file.readBytes())
        val before = checkNotNull(tags.read(file))
        val (writer, _) = writer()

        val result = runBlocking { writer.write(listOf(WriteJob(SongTarget(file.path, 41), TagEdit(cover = CoverEdit.Remove))), EditKind.SONG, "Tin Roof") } as BatchResult.Done

        assertEquals(SongOutcome.WRITTEN, result.songs.single().outcome)
        val after = checkNotNull(tags.read(file))
        assertTrue(after.pictures.none { it.type == FRONT_COVER })
        assertEquals(before.pictures.filter { it.type != FRONT_COVER }, after.pictures)
        val ape = apeItems(file.readBytes())
        assertFalse("COVER ART (FRONT)" in ape)
        assertArrayEquals(apeBefore.getValue("COVER ART (BACK)"), ape.getValue("COVER ART (BACK)"))
        assertEquals(before.fields, after.fields)
        assertTrue(after.frames.containsAll(listOf("RVA2", "TMOO")))
    }

    // The writer, on private copies standing in for songs

    /** The next in-place write fails with an IOException after so many bytes. */
    private var failAfter: Long? = null
    private var scanned: Long? = 41

    private inner class PrivateSongs : SongFiles {
        override fun mounted(path: String): Boolean = true

        override fun sameFile(mediaStoreId: Long, path: String): Boolean = mediaStoreId == 41L

        override fun idOf(path: String): Long = 41

        override fun openInPlace(song: SongTarget): OpenSong {
            val opened = fileId(song.path)
            val channel = FileChannel.open(File(song.path).toPath(), StandardOpenOption.READ, StandardOpenOption.WRITE)
            val fail = failAfter.also { failAfter = null }
            return OpenSong(if (fail == null) channel else CutShort(channel, fail), { buffer, at -> channel.read(buffer, at) }, { channel.size() }) { opened }
        }

        override fun openToRead(path: String): OpenSong = PhoneSongFiles(context).openToRead(path)

        override fun fileId(path: String): FileId = PhoneSongFiles(context).fileId(path)

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

            // A rescan that names nothing: the song read back as written, so nothing is put back. It stays as written, and the
            // start-up recovery settles it (keeps it, saves its History, lets its backup go).
            scanned = null
            val (unscannedWriter, recovery) = writer()
            val unscanned = runBlocking { unscannedWriter.write(listOf(WriteJob(song, edit)), EditKind.SONG, "Tin Roof") } as BatchResult.Done
            assertEquals(name, SongOutcome.RECOVERY_PENDING, unscanned.songs.single().outcome)
            val written = file.readBytes()
            assertEquals(name, "Tin Roof 𝄞", tags.read(file)?.fields?.get("TITLE")?.single())
            scanned = 41
            assertEquals(name, Recovered(settled = 1), runBlocking { recovery.recover() })
            assertArrayEquals(name, written, file.readBytes())
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

    /** A probe song [name]d in the test folder holding [bytes], with its MediaStore id and path; deleted after [block]. */
    private fun withProbe(bytes: ByteArray, name: String = "kjb-t1-probe-${System.currentTimeMillis()}.mp3", block: (id: Long, path: String) -> Unit) {
        assumeTrue("shared storage is not mounted", Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED)
        val uri = makeProbe(bytes, name)
        try {
            val path = pathOf(uri)
            assertTrue(SharedWrites.inTestFolder(path))
            block(ContentUris.parseId(uri), path)
        } finally {
            context.contentResolver.delete(uri, null, null)
        }
    }

    /** Makes a probe song [name]d in the test folder holding [bytes], through MediaStore; the caller deletes it. */
    private fun makeProbe(bytes: ByteArray, name: String): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.RELATIVE_PATH, SharedWrites.TEST_FOLDER)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = checkNotNull(resolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)) { "MediaStore would not make the probe" }
        try {
            resolver.openOutputStream(uri)!!.use { it.write(bytes) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }

    private fun pathOf(uri: Uri): String =
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)!!.use { it.moveToFirst(); it.getString(0) }

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
    /**
     * Has the tag writer, in a process of its own (TagKillService, debug builds only), write probe [id] at [path] over
     * the folders in [killRoot], stops it after [bytes] bytes went through MediaStore's "rw" descriptor with nothing
     * fsynced, and kills it with SIGKILL.
     */
    private fun killMidWrite(id: Long, path: String, bytes: Long, killRoot: String) {
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
            .putExtra(TagKillService.EXTRA_BYTES, bytes)
            .putExtra(TagKillService.EXTRA_REPLY, plain(reply))
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
    }

    /**
     * [receiver] as a plain ResultReceiver, its calls still reaching [receiver]: the helper process loads only the app's
     * classes, so a subclass from this test can't be unparcelled there (ClassNotFoundException, and the helper dies).
     */
    private fun plain(receiver: ResultReceiver): ResultReceiver {
        val parcel = Parcel.obtain()
        try {
            receiver.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            return ResultReceiver.CREATOR.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
    }

    /** The start-up recovery over the folders in [killRoot], as the next process runs it, on [songs]. */
    private fun recoverAfterKill(killRoot: String, songs: SongFiles = PhoneSongFiles(context)): Pair<Recovered, WriteJournal> {
        val dirs = TagDirs(File(context.filesDir, killRoot), ::fsyncDirectory)
        val journal = WriteJournal(dirs.journal, dirs.syncDir)
        val recovery = JournalRecovery(dirs, journal, UndoStore(dirs), songs, PathLocks(), Lyrify.writes)
        return runBlocking { recovery.recover(recovery.holdPending()) } to journal
    }

    /**
     * The tag writer runs in a process of its own and is stopped halfway through writing the probe through MediaStore's
     * "rw" descriptor, with nothing fsynced; this process kills it with SIGKILL. Then the recovery, here, as at the next
     * start, reads the probe through the descriptor it holds, finds it torn by the write, and puts it back byte for byte.
     */
    @Test
    fun aSigkillMidWriteInAnotherProcessIsPutBackByTheRecovery() {
        val original = assets.open("tags/paper-comet-v23.mp3").use { it.readBytes() }
        val killRoot = "tag-kill-test"
        File(context.filesDir, killRoot).deleteRecursively()
        withProbe(original) { id, path ->
            killMidWrite(id, path, original.size / 2L, killRoot)
            assertFalse("the probe is not torn", original.contentEquals(File(path).readBytes()))

            val (recovered, journal) = recoverAfterKill(killRoot)

            assertEquals(Recovered(settled = 1, restored = 1), recovered)
            assertArrayEquals(original, File(path).readBytes())
            assertEquals(0, journal.pending().size)
        }
        File(context.filesDir, killRoot).deleteRecursively()
    }

    /**
     * After the kill, the probe is deleted and another file made under its name, through MediaStore, as another app
     * would. Whether the new file reuses the probe's inode is up to the file system (it is logged; a test can't force
     * it): either way the recovery reads the new file through the descriptor it holds, finds it is not the song torn by
     * the write, and never writes over it.
     */
    @Test
    fun aFileMadeAtAKilledWritesPathIsNeverWrittenOverByTheRecovery() {
        val original = assets.open("tags/paper-comet-v23.mp3").use { it.readBytes() }
        val foreign = assets.open("tags/lanterns-over-kelp-v24.mp3").use { it.readBytes() }
        val killRoot = "tag-kill-test"
        val name = "kjb-t1-probe-${System.currentTimeMillis()}.mp3"
        File(context.filesDir, killRoot).deleteRecursively()
        withProbe(original, name) { id, path ->
            killMidWrite(id, path, original.size / 2L, killRoot)
            val songInode = Os.lstat(path).st_ino
            context.contentResolver.delete(ContentUris.withAppendedId(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), id), null, null)
            withProbe(foreign, name) { _, newPath ->
                assumeTrue("MediaStore put the new file under another name", newPath == path)
                Log.i(LOG_TAG, "tags device test: the new file ${if (Os.lstat(path).st_ino == songInode) "reused" else "did not reuse"} the song's inode")

                val (recovered, _) = recoverAfterKill(killRoot)

                assertEquals(Recovered(setAside = 1), recovered)
                assertArrayEquals(foreign, File(path).readBytes())
            }
        }
        File(context.filesDir, killRoot).deleteRecursively()
    }

    /**
     * After the kill, the recovery opens the torn probe and reads it; right after its first read, the probe is deleted
     * and another file made under its name, through MediaStore, as another app would. The recovery's descriptor still
     * reads the torn song, but the path names another file now: nothing is written, the new file is left as it is, and
     * the backup, the only whole copy of the song, is kept with the intent set aside.
     */
    @Test
    fun aSongReplacedWhileTheRecoveryHoldsItIsNotWrittenAndItsBackupIsKept() {
        val original = assets.open("tags/paper-comet-v23.mp3").use { it.readBytes() }
        val foreign = assets.open("tags/lanterns-over-kelp-v24.mp3").use { it.readBytes() }
        val killRoot = "tag-kill-test"
        val name = "kjb-t1-probe-${System.currentTimeMillis()}.mp3"
        File(context.filesDir, killRoot).deleteRecursively()
        withProbe(original, name) { id, path ->
            killMidWrite(id, path, original.size / 2L, killRoot)
            val dirs = TagDirs(File(context.filesDir, killRoot), ::fsyncDirectory)
            val backup = File(dirs.backups, WriteJournal(dirs.journal, dirs.syncDir).pending().single().backup)
            val phone = PhoneSongFiles(context)
            var replacement: Uri? = null
            val replacing = object : SongFiles by phone {
                override fun openInPlace(song: SongTarget): OpenSong? = phone.openInPlace(song)?.let { open ->
                    OpenSong(open.channel, reader = { buffer, position ->
                        open.read(buffer, position).also {
                            if (replacement == null) {
                                context.contentResolver.delete(ContentUris.withAppendedId(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), id), null, null)
                                replacement = makeProbe(foreign, name)
                            }
                        }
                    }, sizer = open::size, identify = open::fileId)
                }
            }
            try {
                val (recovered, _) = recoverAfterKill(killRoot, replacing)

                val made = checkNotNull(replacement) { "the recovery never read the probe" }
                assumeTrue("MediaStore put the new file under another name", pathOf(made) == path)
                assertEquals(Recovered(setAside = 1), recovered)
                assertArrayEquals(foreign, File(path).readBytes())
                assertArrayEquals(original, backup.readBytes())
            } finally {
                replacement?.let { context.contentResolver.delete(it, null, null) }
            }
        }
        File(context.filesDir, killRoot).deleteRecursively()
    }

    /**
     * The writer writes a probe through MediaStore twice. On the second write, the release of its backup opens the probe
     * anew and reads it; right after that read, the probe is deleted and another file made under its name, through
     * MediaStore, as another app would. The release read the written song, but the path names another file now: the
     * backup, the only copy of the song as it was before, is kept, and the write is set aside.
     */
    @Test
    fun aSongReplacedWhileItsBackupIsReleasedKeepsItsBackup() {
        val original = assets.open("tags/paper-comet-v23.mp3").use { it.readBytes() }
        val foreign = assets.open("tags/lanterns-over-kelp-v24.mp3").use { it.readBytes() }
        val name = "kjb-t1-probe-${System.currentTimeMillis()}.mp3"
        withProbe(original, name) { id, path ->
            val phone = PhoneSongFiles(context)
            var replacing = false
            var replacement: Uri? = null
            val replaced = object : SongFiles by phone {
                override fun openToRead(path: String): OpenSong {
                    val open = phone.openToRead(path)
                    if (!replacing) return open
                    return OpenSong(open.channel, reader = { buffer, position ->
                        open.read(buffer, position).also {
                            if (replacement == null) {
                                val held = open.fileId()
                                context.contentResolver.delete(ContentUris.withAppendedId(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), id), null, null)
                                replacement = makeProbe(foreign, name)
                                // What this process sees right after MediaStore replaced the song: numbers only.
                                val atPath = Os.lstat(path)
                                Log.i(
                                    LOG_TAG,
                                    "tags device test: after the replacement, the path shows the held file: ${phone.fileId(path) == held}, " +
                                        "its size ${atPath.st_size} (new file ${foreign.size}), the descriptor's size ${open.size()}, " +
                                        "MediaStore's row changed: ${phone.idOf(path) != id}",
                                )
                            }
                        }
                    }, sizer = open::size, identify = open::fileId)
                }
            }
            val (writer, _) = writer(replaced, Lyrify.writes)
            val song = SongTarget(path, id)
            try {
                val first = runBlocking { writer.write(listOf(WriteJob(song, edit)), EditKind.SONG, "Tin Roof") } as BatchResult.Done
                assertEquals(SongOutcome.WRITTEN, first.songs.single().outcome)
                val before = File(path).readBytes()
                replacing = true
                val again = TagEdit(fields = mapOf("TITLE" to listOf("Salt Clock")))
                val second = runBlocking { writer.write(listOf(WriteJob(song, again)), EditKind.SONG, "Salt Clock") } as BatchResult.Done

                val made = checkNotNull(replacement) { "the release never read the probe" }
                assumeTrue("MediaStore put the new file under another name", pathOf(made) == path)
                assertEquals(SongOutcome.SET_ASIDE, second.songs.single().outcome)
                // Read through MediaStore: this process's view of the path may still be the old file's for a while.
                assertArrayEquals(foreign, context.contentResolver.openInputStream(made)!!.use { it.readBytes() })
                assertTrue("the backup is gone", dirs.backups.listFiles().orEmpty().any { it.readBytes().contentEquals(before) })
            } finally {
                replacement?.let { context.contentResolver.delete(it, null, null) }
            }
        }
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

    /** A file channel whose first pass fails with an IOException after [limit] bytes; a put-back through it then works. */
    private class CutShort(private val inner: FileChannel, private val limit: Long) : FileChannel() {
        private var written = 0L
        private var failed = false

        override fun write(src: ByteBuffer): Int {
            if (!failed && written + src.remaining() > limit) {
                failed = true
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
