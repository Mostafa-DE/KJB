package app.lyrify.books

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.metadata.id3.ChapterFrame
import androidx.media3.extractor.metadata.id3.ChapterTocFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import androidx.media3.inspector.MetadataRetriever
import app.lyrify.library.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import androidx.media3.extractor.metadata.Chapter as Media3Chapter

// A book's chapters (research section 1): Media3's MetadataRetriever reads them from the file, MP4's Nero chpl and
// QuickTime chapter tracks and ID3 CHAP frames ordered by CTOC; Ogg Vorbis and Opus CHAPTERxxx comments are KJB's own
// parser here; a folder book's chapters are its files ([partChapters]). A book without marks has none: no made-up parts.

/**
 * Ogg Vorbis and Opus chapters from the file's comments ([comments], key and value as written): `CHAPTER001` is a start
 * as `HH:MM:SS.mmm` and `CHAPTER001NAME` its title. The number only orders them, from 000 (ffmpeg) or 001 (the Vorbis
 * convention); a chapter whose time cannot be read is left out, the rest kept. Each ends where the next starts, the last
 * at [durationMs]. At most [BookState.MAX_CHAPTERS].
 */
fun oggChapters(comments: List<Pair<String, String>>, durationMs: Long): List<Chapter> {
    val starts = HashMap<Int, Long>()
    val names = HashMap<Int, String>()
    comments.forEach { (key, value) ->
        val match = OGG_CHAPTER.matchEntire(key.trim()) ?: return@forEach
        val number = match.groupValues[1].toIntOrNull() ?: return@forEach
        if (match.groupValues[2].isEmpty()) chapterTime(value)?.let { starts[number] = it } else names[number] = value
    }
    val ordered = starts.entries.sortedWith(compareBy<Map.Entry<Int, Long>> { it.value }.thenBy { it.key }).take(BookState.MAX_CHAPTERS)
    return ordered.mapIndexed { at, (number, start) ->
        val end = ordered.getOrNull(at + 1)?.value ?: durationMs
        Chapter(start, end.coerceAtLeast(start), names[number]?.trim()?.takeIf(String::isNotEmpty))
    }
}

/** `CHAPTER` and its number, then `NAME` or nothing, in any case. */
private val OGG_CHAPTER = Regex("""CHAPTER(\d{1,6})(NAME)?""", RegexOption.IGNORE_CASE)

/** A chapter time as Vorbis comments write it: `HH:MM:SS.mmm` (hours of any length, the fraction optional); null when it is not one. */
fun chapterTime(text: String): Long? {
    val match = CHAPTER_TIME.matchEntire(text.trim()) ?: return null
    val (hours, minutes, seconds, fraction) = match.destructured
    val minute = minutes.toInt()
    val second = seconds.toInt()
    if (minute >= 60 || second >= 60) return null
    val ms = fraction.take(3).padEnd(3, '0').ifEmpty { "000" }.toLong()
    return ((hours.toLong() * 60 + minute) * 60 + second) * 1_000 + ms
}

/** `H:MM:SS` with an optional `.fraction`. */
private val CHAPTER_TIME = Regex("""(\d{1,4}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?""")

/**
 * The chapters Media3 found in a file, from its tracks' metadata [entries]: its `Chapter` entries (MP4, ID3 `CHAP` with
 * its `TIT2` title), ID3 ones in the root `CTOC`'s order when there is one, else by start; hidden ones left out. Without
 * any, the Ogg comments ([oggChapters]). An end Media3 does not know is the next chapter's start, the last one's
 * [durationMs].
 */
@OptIn(UnstableApi::class)
fun chaptersOf(entries: List<Metadata.Entry>, durationMs: Long): List<Chapter> {
    val marks = entries.filterIsInstance<Media3Chapter>().filterNot { it.isHidden }
    if (marks.isEmpty()) return oggChapters(entries.filterIsInstance<VorbisComment>().map { it.key to it.value }, durationMs)
    val toc = entries.filterIsInstance<ChapterTocFrame>().firstOrNull { it.isRoot }?.children?.withIndex()?.associate { (at, id) -> id to at }
    val ordered = if (toc != null) {
        marks.sortedWith(compareBy<Media3Chapter> { (it as? ChapterFrame)?.chapterId?.let(toc::get) ?: Int.MAX_VALUE }.thenBy { it.startTimeMs })
    } else {
        marks.sortedBy { it.startTimeMs }
    }.distinctBy { it.startTimeMs to titleOf(it) }.take(BookState.MAX_CHAPTERS)
    return ordered.mapIndexed { at, mark ->
        val start = mark.startTimeMs.coerceAtLeast(0)
        val end = mark.endTimeMs.takeIf { it != C.TIME_UNSET && it > start } ?: ordered.getOrNull(at + 1)?.startTimeMs ?: durationMs
        Chapter(start, end.coerceAtLeast(start), titleOf(mark))
    }
}

/** A chapter's title: Media3's, else an ID3 chapter's TIT2 sub-frame; null when blank. */
@OptIn(UnstableApi::class)
private fun titleOf(mark: Media3Chapter): String? {
    mark.title?.value?.trim()?.takeIf(String::isNotEmpty)?.let { return it }
    val frame = mark as? ChapterFrame ?: return null
    return (0 until frame.subFrameCount).map(frame::getSubFrame).filterIsInstance<TextInformationFrame>().firstOrNull { it.id == "TIT2" }?.values?.firstOrNull()?.trim()?.takeIf(String::isNotEmpty)
}

/** What makes a single file's kept chapters still its own: its size and change time ([BookEntry.chaptersStamp]). */
fun chaptersStamp(song: Song): String = "${song.sizeBytes}:${song.modifiedAt ?: 0}"

/** [book]'s chapters: a folder book's parts, or a single file's as read ([BookEntry.chapters]); none when not read yet. */
fun bookChapters(book: Book, state: BookState): List<Chapter> = when (book.kind) {
    BookKind.FOLDER -> partChapters(book)
    BookKind.SINGLE -> state.entryOf(book)?.chapters.orEmpty()
}

/** Reads a file's chapters: Media3 in the app ([Media3Chapters]), made up in tests. */
fun interface ChapterReader {
    /** The chapters of the file at [path], [durationMs] long; null when it could not be read. */
    suspend fun read(path: String, durationMs: Long): List<Chapter>?
}

/**
 * Keeps the single files' chapters in `books.json`: [ensure] reads a book's when they are not kept for its file as it is
 * now, once at a time, on [scope] ([reader] works off the main thread). A file that cannot be read keeps no chapters
 * until it changes. Main thread.
 */
class BookChapters(private val store: BookStore, private val reader: ChapterReader, private val scope: CoroutineScope, private val log: (String) -> Unit = {}) {
    private val reading = HashSet<String>()

    fun ensure(book: Book) {
        if (book.kind != BookKind.SINGLE) return
        val file = book.parts.single()
        val stamp = chaptersStamp(file)
        if (store.state.value.entryOf(book)?.chaptersStamp == stamp || !reading.add(book.id)) return
        scope.launch {
            try {
                store.ready.first { it }
                if (store.state.value.entryOf(book)?.chaptersStamp == stamp) return@launch
                val read = reader.read(file.path, file.durationMs)
                if (read == null) log("books: chapters not read")
                store.change { it.withChapters(book, stamp, read.orEmpty()) }
            } finally {
                reading.remove(book.id)
            }
        }
    }
}

/** [ChapterReader] with Media3's MetadataRetriever, on its own thread. */
@OptIn(UnstableApi::class)
class Media3Chapters(private val context: Context, private val log: (String) -> Unit = {}) : ChapterReader {
    override suspend fun read(path: String, durationMs: Long): List<Chapter>? = withContext(Dispatchers.IO) {
        try {
            MetadataRetriever.Builder(context, MediaItem.fromUri(Uri.fromFile(File(path)))).build().use { retriever ->
                val groups = retriever.retrieveTrackGroups().get(READ_WAIT_S, TimeUnit.SECONDS)
                val entries = (0 until groups.length).flatMap { g ->
                    val group = groups[g]
                    (0 until group.length).flatMap { f -> group.getFormat(f).metadata?.let { metadata -> (0 until metadata.length()).map(metadata::get) }.orEmpty() }
                }
                chaptersOf(entries, durationMs)
            }
        } catch (e: ExecutionException) {
            log("books: chapters not read (${(e.cause ?: e).javaClass.simpleName})")
            null
        } catch (e: TimeoutException) {
            log("books: chapters not read (${e.javaClass.simpleName})")
            null
        } catch (e: IOException) {
            log("books: chapters not read (${e.javaClass.simpleName})")
            null
        }
    }

    private companion object {
        /** A file of hundreds of chapters is read in well under this. */
        const val READ_WAIT_S: Long = 30
    }
}
