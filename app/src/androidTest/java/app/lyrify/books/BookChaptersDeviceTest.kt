package app.lyrify.books

import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.inspector.MetadataRetriever
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lyrify.LOG_TAG
import app.lyrify.Lyrify
import app.lyrify.data.SharedWrites
import app.lyrify.library.Library
import app.lyrify.library.LibraryReader
import app.lyrify.library.SongsRead
import app.lyrify.settings.Settings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Chapter reading on the phone, through KJB's reader ([Media3Chapters]: its QuickTime chapter track reader, then Media3
 * 1.11's MetadataRetriever), on the fixtures in app/src/androidTest/assets/books (silent, made-up names, made by
 * tools/make_book_fixtures.py): an m4b with Nero chpl only, with QuickTime chapters only (the shape LibriVox files have),
 * with both (agreeing, and made to disagree here), with none; an mp3 with ID3 CHAP and
 * CTOC; Ogg Vorbis and Opus with CHAPTER comments; a single mp3 without chapters; and a folder book read back through
 * MediaStore and KJB's library. Each fixture is 12 s with chapters at 0, 4 and 8 s.
 */
@RunWith(AndroidJUnit4::class)
class BookChaptersDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets

    private val three = listOf("Chapter One", "Chapter Two", "Chapter Three")

    /** The fixture [name] copied to the app's cache, where KJB can read it as a file. */
    private fun fixture(name: String): File =
        File(context.cacheDir, "books/$name").apply {
            parentFile?.mkdirs()
            assets.open("books/$name").use { input -> outputStream().use { input.copyTo(it) } }
        }

    private fun chapters(name: String): List<Chapter> = chapters(fixture(name))

    private fun chapters(file: File): List<Chapter> {
        val started = System.nanoTime()
        val read = runBlocking { Media3Chapters(context).read(file.path, FIXTURE_MS) }
        Log.i(LOG_TAG, "books device test: ${read?.size} chapters read in ${TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)} ms")
        return checkNotNull(read) { "not read" }
    }

    /** Three chapters at 0, 4 and 8 s with their titles, the last ending at the end. */
    private fun assertThree(chapters: List<Chapter>) {
        assertEquals(three, chapters.map { it.title })
        assertEquals(listOf(0L, 4_000L, 8_000L), chapters.map { it.startMs })
        // Ends read from the file or taken from the next start; the last at the length (the file's own, or ours).
        assertEquals(listOf(4_000L, 8_000L), chapters.take(2).map { it.endMs })
        assertTrue(chapters.last().endMs in 11_900L..12_100L)
    }

    @Test
    fun anM4bWithNeroChaptersOnlyHasThem() = assertThree(chapters("lantern-chpl-only.m4b"))

    @Test
    fun anM4bWithQuickTimeChaptersOnlyHasThem() = assertThree(chapters("lantern-qt-only.m4b"))

    @Test
    fun anM4bWithBothKindsHasThemOnce() = assertThree(chapters("lantern-both.m4b"))

    /**
     * lantern-both.m4b with its chpl's titles in capitals (same length, so every box keeps its size): Media3 reads the
     * capitals, and KJB the QuickTime track's own titles, which win when the two kinds disagree.
     */
    @androidx.annotation.OptIn(UnstableApi::class)
    @Test
    fun anM4bWhoseTwoKindsDisagreeHasItsQuickTimeChapters() {
        val file = fixture("lantern-both.m4b")
        val bytes = file.readBytes()
        val chpl = (0..bytes.size - 4).single { at -> "chpl".indices.all { bytes[at + it] == "chpl"[it].code.toByte() } }
        // After the type: version and flags, 4 reserved bytes, the count; then each chapter's 8-byte start, length, title.
        var at = chpl + 4 + 8
        repeat(bytes[at++].toInt()) {
            at += 8
            val length = bytes[at++].toInt() and 0xFF
            String(bytes, at, length).uppercase().toByteArray().copyInto(bytes, at)
            at += length
        }
        file.writeBytes(bytes)
        val media3 = MetadataRetriever.Builder(context, MediaItem.fromUri(Uri.fromFile(file))).build().use { retriever ->
            val groups = retriever.retrieveTrackGroups().get(30, TimeUnit.SECONDS)
            (0 until groups.length).flatMap { g -> (0 until groups[g].length).mapNotNull { groups[g].getFormat(it).metadata } }
                .flatMap { metadata -> (0 until metadata.length()).map(metadata::get) }
                .filterIsInstance<androidx.media3.extractor.metadata.Chapter>()
                .map { it.title?.value }
        }
        assertEquals(three.map { it.uppercase() }, media3)
        assertThree(chapters(file))
    }

    @Test
    fun anM4bWithoutChaptersHasNone() = assertEquals(emptyList<Chapter>(), chapters("lantern-no-chapters.m4b"))

    @Test
    fun anMp3WithChapAndCtocHasThemInOrder() = assertThree(chapters("lantern-chap.mp3"))

    @Test
    fun anOpusFileWithChapterCommentsHasThem() = assertThree(chapters("lantern-chapters.opus"))

    @Test
    fun anOggVorbisFileWithChapterCommentsHasThem() = assertThree(chapters("lantern-chapters.ogg"))

    @Test
    fun aSingleMp3WithoutChaptersHasNone() = assertEquals(emptyList<Chapter>(), chapters("plain-book.mp3"))

    /** The research's open point: an m4b's QuickTime chapter text track is the chapters' source, not a track to pick. */
    @androidx.annotation.OptIn(UnstableApi::class)
    @Test
    fun theQuickTimeChapterTrackIsNotATrackToPlay() {
        val file = fixture("lantern-qt-only.m4b")
        val groups = MetadataRetriever.Builder(context, MediaItem.fromUri(Uri.fromFile(file))).build().use { it.retrieveTrackGroups().get(30, TimeUnit.SECONDS) }
        assertEquals(listOf(C.TRACK_TYPE_AUDIO), (0 until groups.length).map { groups[it].type })
    }

    /**
     * A folder of parts through MediaStore and KJB's library, as on the phone: put in the test folder (the only place a
     * debug build may write), read back by the library reader, and grouped as one book of three chapters in order,
     * by track numbers 1, 2, 10; a second folder without track numbers by its file names 1, 2, 10. Deleted after.
     */
    @Test
    fun aFolderOfPartsIsOneBookInOrder() {
        assumeTrue("shared storage is not mounted", Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED)
        val stamp = System.currentTimeMillis()
        val tracked = "${SharedWrites.TEST_FOLDER}Folder Book $stamp/"
        val untracked = "${SharedWrites.TEST_FOLDER}No Track Book $stamp/"
        val made = listOf("01.mp3", "02.mp3", "10.mp3").map { put("folder-book/$it", tracked) } +
            listOf("2.mp3", "10.mp3", "1.mp3").map { put("no-track-book/$it", untracked) }
        try {
            val read = LibraryReader(context, Lyrify.writes).read()
            assertTrue(read.toString(), read is SongsRead.Songs)
            val mine = (read as SongsRead.Songs).songs.filter { it.folder == tracked || it.folder == untracked }
            assertEquals(6, mine.size)
            val books = Library.of(mine, Settings()).books.associateBy { it.parts.first().folder }

            val book = books.getValue(tracked)
            assertEquals(BookKind.FOLDER, book.kind)
            assertEquals("Folder Book", book.title)
            assertEquals(listOf("01.mp3", "02.mp3", "10.mp3"), book.parts.map { it.fileName })
            val chapters = partChapters(book)
            assertEquals(listOf("Part One", "Part Two", "Part Ten"), chapters.map { it.title })
            assertEquals(0L, chapters.first().startMs)
            assertTrue(chapters.zipWithNext().all { (a, b) -> a.endMs == b.startMs && b.startMs > a.startMs })

            assertEquals(listOf("1.mp3", "2.mp3", "10.mp3"), books.getValue(untracked).parts.map { it.fileName })
        } finally {
            made.forEach { context.contentResolver.delete(it, null, null) }
        }
    }

    /** The fixture [asset] as a new file in MediaStore's [folder] (the test folder); the caller deletes it. */
    private fun put(asset: String, folder: String): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, asset.substringAfterLast('/'))
            put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = checkNotNull(resolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)) { "MediaStore would not make the part" }
        try {
            resolver.openOutputStream(uri)!!.use { out -> assets.open("books/$asset").use { it.copyTo(out) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }

    private companion object {
        /** Every single-file fixture is 12 s. */
        const val FIXTURE_MS: Long = 12_000
    }
}
