package app.lyrify.books

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Label
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
import java.io.RandomAccessFile
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import androidx.media3.extractor.metadata.Chapter as Media3Chapter

// A book's chapters (research section 1): MP4 QuickTime chapter tracks are KJB's own reader here ([quickTimeChapters]),
// as Media3's MetadataRetriever leaves them out; it reads the rest, MP4's Nero chpl and ID3 CHAP frames ordered by CTOC;
// Ogg Vorbis and Opus CHAPTERxxx comments are KJB's own parser here; a folder book's chapters are its files
// ([partChapters]). A book without marks has none: no made-up parts.

/**
 * Ogg Vorbis and Opus chapters from the file's comments ([comments], key and value as written): `CHAPTER001` is a start
 * as `HH:MM:SS.mmm` and `CHAPTER001NAME` its title. The number only orders them, from 000 (ffmpeg) or 001 (the Vorbis
 * convention); a chapter whose time cannot be read, or that starts past the end of the file, is left out, the rest kept.
 * Each ends where the next starts, the last at [durationMs] (0 when unknown: then nothing is left out by time). At most
 * [BookState.MAX_CHAPTERS].
 */
fun oggChapters(comments: List<Pair<String, String>>, durationMs: Long): List<Chapter> {
    val starts = HashMap<Int, Long>()
    val names = HashMap<Int, String>()
    comments.forEach { (key, value) ->
        val match = OGG_CHAPTER.matchEntire(key.trim()) ?: return@forEach
        val number = match.groupValues[1].toIntOrNull() ?: return@forEach
        if (match.groupValues[2].isEmpty()) chapterTime(value)?.let { starts[number] = it } else names[number] = value
    }
    val ordered = starts.entries.sortedWith(compareBy<Map.Entry<Int, Long>> { it.value }.thenBy { it.key }).filter { inFile(it.value, durationMs) }.take(BookState.MAX_CHAPTERS)
    return ordered.mapIndexed { at, (number, start) ->
        val end = ordered.getOrNull(at + 1)?.value ?: durationMs
        Chapter(start, endIn(end, start, durationMs), names[number]?.trim()?.takeIf(String::isNotEmpty))
    }
}

/** Whether a chapter starting at [startMs] is in a file [durationMs] long (0: not known, so it is). */
private fun inFile(startMs: Long, durationMs: Long): Boolean = durationMs <= 0 || startMs < durationMs

/** A chapter's [endMs] within the file and never before its [startMs]. */
private fun endIn(endMs: Long, startMs: Long, durationMs: Long): Long = (if (durationMs > 0) endMs.coerceAtMost(durationMs) else endMs).coerceAtLeast(startMs)

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
 * its `TIT2` title) in time order (the root `CTOC`'s order only between equal starts, as [chapterAt] searches by start);
 * hidden ones and those starting past the end of the file left out. Without any, the Ogg comments ([oggChapters]). An
 * end Media3 does not know is the next later chapter's start, the last one's [durationMs]; every end is within the file.
 */
@OptIn(UnstableApi::class)
fun chaptersOf(entries: List<Metadata.Entry>, durationMs: Long): List<Chapter> {
    val marks = entries.filterIsInstance<Media3Chapter>().filterNot { it.isHidden }
    if (marks.isEmpty()) return oggChapters(entries.filterIsInstance<VorbisComment>().map { it.key to it.value }, durationMs)
    val toc = entries.filterIsInstance<ChapterTocFrame>().firstOrNull { it.isRoot }?.children?.withIndex()?.associate { (at, id) -> id to at }
    val ordered = marks.sortedWith(compareBy<Media3Chapter> { it.startTimeMs.coerceAtLeast(0) }.thenBy { (it as? ChapterFrame)?.chapterId?.let { id -> toc?.get(id) } ?: Int.MAX_VALUE })
        .filter { inFile(it.startTimeMs, durationMs) }
        .distinctBy { it.startTimeMs.coerceAtLeast(0) to titleOf(it) }
        .take(BookState.MAX_CHAPTERS)
    return ordered.mapIndexed { at, mark ->
        val start = mark.startTimeMs.coerceAtLeast(0)
        val next = ordered.subList(at + 1, ordered.size).firstOrNull { it.startTimeMs > start }?.startTimeMs
        val end = mark.endTimeMs.takeIf { it != C.TIME_UNSET && it > start } ?: next ?: durationMs
        Chapter(start, endIn(end, start, durationMs), titleOf(mark))
    }
}

/** A chapter's title: Media3's, else an ID3 chapter's TIT2 sub-frame; null when blank. */
@OptIn(UnstableApi::class)
private fun titleOf(mark: Media3Chapter): String? {
    mark.title?.value?.trim()?.takeIf(String::isNotEmpty)?.let { return it }
    val frame = mark as? ChapterFrame ?: return null
    return (0 until frame.subFrameCount).map(frame::getSubFrame).filterIsInstance<TextInformationFrame>().firstOrNull { it.id == "TIT2" }?.values?.firstOrNull()?.trim()?.takeIf(String::isNotEmpty)
}

/**
 * The chapters of an MP4's QuickTime chapter track, the shape most m4b books have (LibriVox's among them): the text track a
 * `moov/trak/tref/chap` box names, one chapter a sample, starting at its `stts` time in that track's own `mdhd` timescale
 * and lasting its duration; the sample's place from `stsz` or `stz2`, `stsc` and `stco` or `co64`; its text a 16-bit
 * length, then UTF-8, or UTF-16 after its byte order mark. As Media3 chapters, so [chaptersOf] orders and limits them as
 * any other. Media3's MetadataRetriever never gives these: it reads an MP4 without sample tables, and Media3 reads a
 * chapter track only with every track's table built (a 6 h book's half million audio samples) and its text as UTF-8 only.
 * Empty for a file that is no MP4 or has no such track. Bounded for any file: at most [BookState.MAX_CHAPTERS] samples and
 * [Mp4Boxes.MAX_BOXES] box headers, every table read only as far as its box and those samples, nothing past the file's end.
 */
@OptIn(UnstableApi::class)
fun quickTimeChapters(file: RandomAccessFile): List<Media3Chapter> {
    val boxes = Mp4Boxes(file)
    val moov = boxes.children(0, file.length()).firstOrNull { it.type == MOOV } ?: return emptyList()
    val tracks = boxes.children(moov).filter { it.type == TRAK }.map { trak ->
        val inside = boxes.children(trak)
        val chapters = inside.firstOrNull { it.type == TREF }?.let(boxes::children)?.firstOrNull { it.type == CHAP }?.let(boxes::trackIds).orEmpty()
        Mp4Track(inside.firstOrNull { it.type == TKHD }?.let(boxes::trackId), chapters, inside.firstOrNull { it.type == MDIA })
    }
    return tracks.flatMap { it.chapters }.distinct().asSequence()
        .mapNotNull { id -> tracks.firstOrNull { it.id == id }?.media }
        .map(boxes::chapterTrack)
        .firstOrNull { it.isNotEmpty() }
        .orEmpty()
}

/** A track as [quickTimeChapters] needs it: its id, the ids its `tref/chap` names, and its `mdia` box. */
private class Mp4Track(val id: Long?, val chapters: List<Long>, val media: Mp4Box?)

/** A box's [type] and its body, from [start] to [end] in the file. */
private class Mp4Box(val type: Int, val start: Long, val end: Long) {
    val size: Long get() = end - start
}

/** [quickTimeChapters]'s reads: box headers inside their parent and the file, at most [MAX_BOXES] of them; tables capped. */
@OptIn(UnstableApi::class)
private class Mp4Boxes(private val file: RandomAccessFile) {
    private val length = file.length()
    private var headersLeft = MAX_BOXES

    /** The boxes in [start] to [end], in order, until one that does not fit there (a broken or cut file) or the cap. */
    fun children(start: Long, end: Long): List<Mp4Box> {
        val found = ArrayList<Mp4Box>()
        var at = start
        while (end - at >= 8 && headersLeft-- > 0) {
            val head = bytes(at, minOf(16L, end - at).toInt()) ?: break
            val type = head.int(4)
            val (header, size) = when (val small = head.u32(0)) {
                0L -> 8 to end - at
                1L -> if (head.size < 16) break else 16 to head.long(8)
                else -> 8 to small
            }
            if (size < header || size > end - at) break
            found += Mp4Box(type, at + header, at + size)
            at += size
        }
        return found
    }

    fun children(box: Mp4Box): List<Mp4Box> = children(box.start, box.end)

    /** A `tkhd`'s track id, after the creation and change times of its version. */
    fun trackId(tkhd: Mp4Box): Long? = body(tkhd, 0, 1)?.let { version -> body(tkhd, if (version[0].toInt() == 1) 20 else 12, 4)?.u32(0) }

    /** The track ids a `chap` box names, at most [MAX_REFERENCES]. */
    fun trackIds(chap: Mp4Box): List<Long> {
        val count = minOf(chap.size / 4, MAX_REFERENCES.toLong()).toInt()
        val ids = body(chap, 0, count * 4) ?: return emptyList()
        return List(count) { ids.u32(it * 4) }
    }

    /** The chapters of the track whose `mdia` is [media]: none unless it is a text track with every table it needs. */
    fun chapterTrack(media: Mp4Box): List<Media3Chapter> {
        val inside = children(media)
        val handler = inside.firstOrNull { it.type == HDLR }?.let { body(it, 8, 4) }?.int(0)
        if (handler !in TEXT_HANDLERS) return emptyList()
        val timescale = inside.firstOrNull { it.type == MDHD }?.let(::timescale)?.takeIf { it > 0 } ?: return emptyList()
        val tables = inside.firstOrNull { it.type == MINF }?.let(::children)?.firstOrNull { it.type == STBL }?.let(::children) ?: return emptyList()
        fun table(type: Int) = tables.firstOrNull { it.type == type }
        val times = table(STTS)?.let(::times) ?: return emptyList()
        val sizes = table(STSZ)?.let(::sizes) ?: table(STZ2)?.let(::compactSizes) ?: return emptyList()
        val runs = table(STSC)?.let { entries(it, 0, 12) } ?: return emptyList()
        val chunks = table(STCO)?.let { offsets(it, 4) } ?: table(CO64)?.let { offsets(it, 8) } ?: return emptyList()
        val places = places(runs, chunks, sizes, minOf(times.size, sizes.size))
        return places.indices.mapNotNull { at ->
            val text = text(places[at], sizes[at]) ?: return@mapNotNull null
            val (start, duration) = times[at]
            // At most MAX_CHAPTERS samples of at most 2^32 ticks each: in milliseconds, far inside a Long.
            Media3Chapter.Builder()
                .setStartTimeMs(start * 1_000 / timescale)
                .setEndTimeMs((start + duration) * 1_000 / timescale)
                .setTitle(text.takeIf(String::isNotEmpty)?.let { Label(null, it) })
                .build()
        }
    }

    /** An `mdhd`'s timescale, after the creation and change times of its version. */
    private fun timescale(mdhd: Mp4Box): Long? = body(mdhd, 0, 1)?.let { version -> body(mdhd, if (version[0].toInt() == 1) 20 else 12, 4)?.u32(0) }

    /** Each sample's start and duration in ticks, from `stts`'s runs of equal durations, at most [BookState.MAX_CHAPTERS]. */
    private fun times(stts: Mp4Box): List<Pair<Long, Long>> {
        val runs = entries(stts, 0, 8) ?: return emptyList()
        val times = ArrayList<Pair<Long, Long>>()
        var start = 0L
        for (run in runs.indices step 2) {
            val duration = runs[run + 1]
            repeat(minOf(runs[run], (BookState.MAX_CHAPTERS - times.size).toLong()).toInt()) {
                times += start to duration
                start += duration
            }
        }
        return times
    }

    /** `stsz`: one size for every sample, or each sample's; at most [BookState.MAX_CHAPTERS]. */
    private fun sizes(stsz: Mp4Box): LongArray? {
        val fixed = body(stsz, 4, 8) ?: return null
        val same = fixed.u32(0)
        if (same != 0L) return LongArray(minOf(fixed.u32(4), BookState.MAX_CHAPTERS.toLong()).toInt()) { same }
        return entries(stsz, 4, 4)
    }

    /** `stz2`: each sample's size in 4, 8 or 16 bits (4-bit ones two to a byte, the first in the high half). */
    private fun compactSizes(stz2: Mp4Box): LongArray? {
        val head = body(stz2, 4, 8) ?: return null
        val bits = head[3].toInt()
        if (bits != 4 && bits != 8 && bits != 16) return null
        val count = minOf(head.u32(4), (stz2.size - 12) * 8 / bits, BookState.MAX_CHAPTERS.toLong()).toInt()
        val packed = body(stz2, 12, (count * bits + 7) / 8) ?: return null
        return LongArray(count) { at ->
            when (bits) {
                16 -> packed.u16(at * 2).toLong()
                8 -> packed[at].toLong() and 0xFF
                else -> (packed[at / 2].toInt() shr (if (at % 2 == 0) 4 else 0) and 0xF).toLong()
            }
        }
    }

    /** `stco` or `co64`: each chunk's place in the file, [width] bytes each. */
    private fun offsets(box: Mp4Box, width: Int): LongArray? {
        val table = entries(box, 0, width) ?: return null
        return if (width == 4) table else LongArray(table.size / 2) { table[it * 2] shl 32 or table[it * 2 + 1] }
    }

    /**
     * A full box's table, its count after the version, flags and [skip] more bytes: each entry's 32-bit fields in a row
     * ([width] bytes an entry), only as many entries as the count says, the box holds and [BookState.MAX_CHAPTERS].
     */
    private fun entries(box: Mp4Box, skip: Int, width: Int): LongArray? {
        val count = body(box, 4 + skip, 4)?.u32(0) ?: return null
        val entries = minOf(count, (box.size - 8 - skip) / width, BookState.MAX_CHAPTERS.toLong()).toInt()
        val table = body(box, 8 + skip, entries * width) ?: return null
        return LongArray(table.size / 4) { table.u32(it * 4) }
    }

    /**
     * Where each of the first [count] samples starts: chunk after chunk, the `stsc` [runs] (first chunk, samples in each,
     * description) saying how many samples each holds, one after another inside it. Fewer when the chunks run out.
     */
    private fun places(runs: LongArray, chunks: LongArray, sizes: LongArray, count: Int): LongArray {
        val places = LongArray(count)
        var sample = 0
        var run = -1
        for (chunk in chunks.indices) {
            while (run + 1 < runs.size / 3 && runs[(run + 1) * 3] <= chunk + 1L) run++
            var left = if (run >= 0) runs[run * 3 + 1] else 0L
            var at = chunks[chunk]
            while (left-- > 0 && sample < count) {
                places[sample] = at
                at += sizes[sample++]
            }
            if (sample == count) break
        }
        return places.copyOf(sample)
    }

    /** The text of the sample of [size] bytes at [offset], "" when it has none; null when the sample is not inside the file. */
    private fun text(offset: Long, size: Long): String? {
        if (size < 2 || offset < 0 || offset > length - size) return null
        val sample = bytes(offset, minOf(size, 2L + MAX_TEXT_BYTES).toInt()) ?: return null
        return chapterText(sample.copyOfRange(2, 2 + minOf(sample.u16(0), sample.size - 2)))
    }

    /** [count] bytes from [offset] into [box]'s body; null when they are not all inside it. */
    private fun body(box: Mp4Box, offset: Int, count: Int): ByteArray? = if (count < 0 || offset + count > box.size) null else bytes(box.start + offset, count)

    /** [count] bytes of the file from [at]; null when they are not all inside it. */
    private fun bytes(at: Long, count: Int): ByteArray? {
        if (at < 0 || count < 0 || at > length - count) return null
        file.seek(at)
        return ByteArray(count).also(file::readFully)
    }

    companion object {
        /** Box headers read in one file: a book has a few dozen. */
        const val MAX_BOXES: Int = 1_024

        /** Tracks one `chap` box may name: a text track, and pictures. */
        const val MAX_REFERENCES: Int = 16

        /** A title's bytes read, past [BookState.MAX_TITLE] characters in any encoding. */
        const val MAX_TEXT_BYTES: Int = 1_024
    }
}

/** A chapter sample's text: UTF-16 after its byte order mark, else UTF-8 (its own mark dropped); up to a NUL, trimmed. */
private fun chapterText(bytes: ByteArray): String {
    fun marked(vararg mark: Int) = bytes.size >= mark.size && mark.indices.all { bytes[it].toInt() and 0xFF == mark[it] }
    val text = when {
        marked(0xFE, 0xFF) -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        marked(0xFF, 0xFE) -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        marked(0xEF, 0xBB, 0xBF) -> String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        else -> String(bytes, Charsets.UTF_8)
    }
    return text.substringBefore('\u0000').trim()
}

private fun ByteArray.u16(at: Int): Int = (this[at].toInt() and 0xFF shl 8) or (this[at + 1].toInt() and 0xFF)

private fun ByteArray.int(at: Int): Int = (u16(at) shl 16) or u16(at + 2)

private fun ByteArray.u32(at: Int): Long = int(at).toLong() and 0xFFFF_FFFFL

private fun ByteArray.long(at: Int): Long = u32(at) shl 32 or u32(at + 4)

private fun fourcc(type: String): Int = type.fold(0) { code, char -> code shl 8 or char.code }

private val MOOV = fourcc("moov")
private val TRAK = fourcc("trak")
private val TKHD = fourcc("tkhd")
private val TREF = fourcc("tref")
private val CHAP = fourcc("chap")
private val MDIA = fourcc("mdia")
private val MDHD = fourcc("mdhd")
private val HDLR = fourcc("hdlr")
private val MINF = fourcc("minf")
private val STBL = fourcc("stbl")
private val STTS = fourcc("stts")
private val STSZ = fourcc("stsz")
private val STZ2 = fourcc("stz2")
private val STSC = fourcc("stsc")
private val STCO = fourcc("stco")
private val CO64 = fourcc("co64")

/** The handlers of a track that holds chapter titles: QuickTime text, and Apple's subtitle tracks. */
private val TEXT_HANDLERS = setOf(fourcc("text"), fourcc("sbtl"), fourcc("subt"))

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
 * now, once at a time, on [scope] ([reader] works off the main thread). A file that cannot be read keeps nothing and is
 * read again at the next [ensure]. Main thread.
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
                if (read == null) {
                    // Nothing kept: read again the next time it is asked for.
                    log("books: chapters not read")
                    return@launch
                }
                store.change { it.withChapters(book, stamp, read) }
            } finally {
                reading.remove(book.id)
            }
        }
    }
}

/**
 * [ChapterReader] on its own thread: an MP4's QuickTime chapter track ([quickTimeChapters]), else what Media3's
 * MetadataRetriever reads (an MP4's Nero chpl, ID3 CHAP, Ogg comments). An m4b with both has its QuickTime chapters,
 * even when its chpl says otherwise: they are the ones Apple's players show and its tools write, with no 255-byte titles,
 * and a chpl is most often a copy written beside them.
 */
@OptIn(UnstableApi::class)
class Media3Chapters(private val context: Context, private val log: (String) -> Unit = {}) : ChapterReader {
    override suspend fun read(path: String, durationMs: Long): List<Chapter>? = withContext(Dispatchers.IO) {
        try {
            val quickTime = RandomAccessFile(path, "r").use(::quickTimeChapters)
            chaptersOf(quickTime.ifEmpty { media3Entries(path) }, durationMs)
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

    /** Every track's metadata entries, as Media3's MetadataRetriever reads them. */
    private fun media3Entries(path: String): List<Metadata.Entry> =
        MetadataRetriever.Builder(context, MediaItem.fromUri(Uri.fromFile(File(path)))).build().use { retriever ->
            val groups = retriever.retrieveTrackGroups().get(READ_WAIT_S, TimeUnit.SECONDS)
            (0 until groups.length).flatMap { g ->
                val group = groups[g]
                (0 until group.length).flatMap { f -> group.getFormat(f).metadata?.let { metadata -> (0 until metadata.length()).map(metadata::get) }.orEmpty() }
            }
        }

    private companion object {
        /** A file of hundreds of chapters is read in well under this. */
        const val READ_WAIT_S: Long = 30
    }
}
