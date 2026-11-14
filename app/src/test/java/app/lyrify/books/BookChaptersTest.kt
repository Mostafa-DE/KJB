package app.lyrify.books

import androidx.media3.common.C
import androidx.media3.common.Label
import androidx.media3.common.Metadata
import androidx.media3.extractor.metadata.id3.ChapterFrame
import androidx.media3.extractor.metadata.id3.ChapterTocFrame
import androidx.media3.extractor.metadata.id3.Id3Frame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import app.lyrify.library.song
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.media3.extractor.metadata.Chapter as Media3Chapter

/** KJB's own chapter code: the Ogg comments parser, the mapping of Media3's entries, and the kept chapters. Made-up names only. */
@OptIn(ExperimentalCoroutinesApi::class)
class BookChaptersTest {
    // Ogg Vorbis and Opus comments

    @Test
    fun `Ogg chapters number from 000 or 001, each ending where the next starts`() {
        val three = listOf(
            Chapter(0, 4_000, "Chapter One"),
            Chapter(4_000, 8_000, "Chapter Two"),
            Chapter(8_000, 12_000, "Chapter Three"),
        )
        val ffmpeg = listOf(
            "CHAPTER000" to "00:00:00.000", "CHAPTER000NAME" to "Chapter One",
            "CHAPTER001" to "00:00:04.000", "CHAPTER001NAME" to "Chapter Two",
            "CHAPTER002" to "00:00:08.000", "CHAPTER002NAME" to "Chapter Three",
            "TITLE" to "Lantern Keeper",
        )
        assertEquals(three, oggChapters(ffmpeg, 12_000))
        val vorbis = listOf(
            "CHAPTER003NAME" to "Chapter Three", "CHAPTER001" to "00:00:00.000", "CHAPTER002" to "00:00:04.000",
            "CHAPTER003" to "00:00:08.000", "CHAPTER001NAME" to "Chapter One", "CHAPTER002NAME" to "Chapter Two",
        )
        assertEquals(three, oggChapters(vorbis, 12_000))
    }

    @Test
    fun `an Ogg chapter without a name has none, a bad time leaves only that chapter out`() {
        val comments = listOf(
            "CHAPTER01" to "0:00:00.000",
            "CHAPTER02" to "later", "CHAPTER02NAME" to "Lost",
            "chapter03" to "1:02:03.5", "CHAPTER03NAME" to "  ",
            "CHAPTERX" to "00:00:09.000",
        )
        assertEquals(listOf(Chapter(0, 3_723_500, null), Chapter(3_723_500, 4_000_000, null)), oggChapters(comments, 4_000_000))
        assertEquals(emptyList<Chapter>(), oggChapters(listOf("TITLE" to "Quiet Orchard"), 1_000))
    }

    @Test
    fun `Ogg chapters are capped`() {
        val many = (1..BookState.MAX_CHAPTERS + 20).flatMap { n -> listOf("CHAPTER%04d".format(n) to "00:00:%02d.000".format(n % 60)) }
        assertEquals(BookState.MAX_CHAPTERS, oggChapters(many, 60_000).size)
    }

    @Test
    fun `chapter times read hours, minutes, seconds and a fraction`() {
        assertEquals(4_000L, chapterTime("00:00:04.000"))
        assertEquals(3_723_500L, chapterTime("1:02:03.5"))
        assertEquals(36_000_000L + 59_999L, chapterTime("10:00:59.999"))
        assertEquals(1_000L, chapterTime("00:00:01"))
        assertNull(chapterTime("00:61:00.000"))
        assertNull(chapterTime("4"))
        assertNull(chapterTime("-1:00:00.000"))
    }

    // Media3's entries

    private fun frame(id: String, start: Int, end: Int, title: String?): ChapterFrame =
        ChapterFrame(id, start, end, C.INDEX_UNSET.toLong(), C.INDEX_UNSET.toLong(), title?.let { arrayOf<Id3Frame>(TextInformationFrame("TIT2", null, listOf(it))) } ?: emptyArray())

    @Test
    fun `MP4 chapters map as they are, an unknown end taken from the next start or the length`() {
        val entries = listOf<Metadata.Entry>(
            Media3Chapter.Builder().setStartTimeMs(0).setEndTimeMs(C.TIME_UNSET).setTitle(Label(null, "Chapter One")).build(),
            Media3Chapter.Builder().setStartTimeMs(4_000).setEndTimeMs(C.TIME_UNSET).setTitle(Label(null, "Chapter Two")).build(),
            Media3Chapter.Builder().setStartTimeMs(8_000).setEndTimeMs(C.TIME_UNSET).build(),
        )
        assertEquals(listOf(Chapter(0, 4_000, "Chapter One"), Chapter(4_000, 8_000, "Chapter Two"), Chapter(8_000, 12_000, null)), chaptersOf(entries, 12_000))
    }

    @Test
    fun `ID3 chapters follow the root CTOC's order, titled by their TIT2`() {
        val entries = listOf<Metadata.Entry>(
            frame("ch2", 8_000, 12_000, "Chapter Three"),
            frame("ch0", 0, 4_000, "Chapter One"),
            frame("ch1", 4_000, 8_000, null),
            ChapterTocFrame("toc", true, true, arrayOf("ch0", "ch1", "ch2"), emptyArray()),
            TextInformationFrame("TIT2", null, listOf("Lantern Keeper")),
        )
        assertEquals(listOf(Chapter(0, 4_000, "Chapter One"), Chapter(4_000, 8_000, null), Chapter(8_000, 12_000, "Chapter Three")), chaptersOf(entries, 12_000))
        // Without a CTOC: by start.
        assertEquals(listOf(0L, 4_000L, 8_000L), chaptersOf(entries.take(3), 12_000).map { it.startMs })
    }

    @Test
    fun `hidden chapters are left out, and without Chapter entries the Ogg comments are read`() {
        val hidden = Media3Chapter.Builder().setStartTimeMs(1_000).setEndTimeMs(2_000).setHidden(true).build()
        assertEquals(emptyList<Chapter>(), chaptersOf(listOf(hidden), 5_000))
        val ogg = listOf<Metadata.Entry>(VorbisComment("CHAPTER001", "00:00:00.000"), VorbisComment("CHAPTER001NAME", "Chapter One"), VorbisComment("TITLE", "Lantern Keeper"))
        assertEquals(listOf(Chapter(0, 5_000, "Chapter One")), chaptersOf(ogg, 5_000))
        assertEquals(emptyList<Chapter>(), chaptersOf(listOf(VorbisComment("TITLE", "Quiet Orchard")), 5_000))
    }

    // Kept chapters

    private val single = booksOf(listOf(song("Audiobooks/Lantern Keeper.m4b", durationMs = 12_000, addedAt = 1_000)), BookMarks()).single()

    @Test
    fun `a single file's chapters are its kept ones, a folder book's its parts`() {
        val state = BookState().withChapters(single, chaptersStamp(single.parts.single()), listOf(Chapter(0, 12_000, "Chapter One")))
        assertEquals(listOf(Chapter(0, 12_000, "Chapter One")), bookChapters(single, state))
        assertEquals(emptyList<Chapter>(), bookChapters(single, BookState()))
        val folder = booksOf(listOf("1", "2").map { song("Audiobooks/Folder Book/$it.mp3", album = "Folder Book", durationMs = 4_000) }, BookMarks()).single()
        assertEquals(partChapters(folder), bookChapters(folder, state))
    }

    @Test
    fun `chapters are read once per file as it is, again when it changes, never for a folder book`() = runTest {
        val reads = mutableListOf<String>()
        val reader = ChapterReader { path, durationMs -> reads += path; listOf(Chapter(0, durationMs, "Chapter One")) }
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val store = BookStore(object : BookStorage {
            override suspend fun load(): BookState = BookState()
            override suspend fun save(state: BookState) = Unit
        }, scope)
        val chapters = BookChapters(store, reader, scope)
        chapters.ensure(single)
        chapters.ensure(single)
        runCurrent()
        chapters.ensure(single)
        runCurrent()
        assertEquals(listOf(single.id), reads)
        assertEquals(listOf(Chapter(0, 12_000, "Chapter One")), bookChapters(single, store.state.value))

        val changed = booksOf(listOf(song("Audiobooks/Lantern Keeper.m4b", durationMs = 12_000, addedAt = 2_000)), BookMarks()).single()
        chapters.ensure(changed)
        runCurrent()
        assertEquals(2, reads.size)
        val folder = booksOf(listOf("1", "2").map { song("Audiobooks/Folder Book/$it.mp3", album = "Folder Book") }, BookMarks()).single()
        chapters.ensure(folder)
        runCurrent()
        assertEquals(2, reads.size)
    }

    @Test
    fun `a file that cannot be read keeps no chapters and is not read again until it changes`() = runTest {
        var reads = 0
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val store = BookStore(object : BookStorage {
            override suspend fun load(): BookState = BookState()
            override suspend fun save(state: BookState) = Unit
        }, scope)
        val chapters = BookChapters(store, { _, _ -> reads++; null }, scope)
        chapters.ensure(single)
        runCurrent()
        chapters.ensure(single)
        runCurrent()
        assertEquals(1, reads)
        assertTrue(bookChapters(single, store.state.value).isEmpty())
    }
}
