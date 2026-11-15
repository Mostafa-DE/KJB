package app.lyrify.books

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import kotlin.random.Random
import androidx.media3.extractor.metadata.Chapter as Media3Chapter

/**
 * KJB's reader of MP4 QuickTime chapter tracks ([quickTimeChapters]), which Media3's MetadataRetriever leaves out: the
 * device fixtures as ffmpeg made them, and books built here box by box in the shape real m4b books have (moov first, one
 * chapter a chunk, the text track's own timescale, an encd atom after each title), then in every other shape the boxes
 * allow, and broken. Made-up names only.
 */
class QuickTimeChaptersTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun read(file: File): List<Media3Chapter> = Files.newByteChannel(file.toPath()).use(::quickTimeChapters)

    private fun read(bytes: ByteArray): List<Media3Chapter> = read(folder.newFile().apply { writeBytes(bytes) })

    /** Each chapter as (start ms, end ms, title). */
    private fun marks(chapters: List<Media3Chapter>): List<Triple<Long, Long, String?>> = chapters.map { Triple(it.startTimeMs, it.endTimeMs, it.title?.value) }

    private val books = File("src/androidTest/assets/books")
    private val three = listOf(Chapter(0, 4_000, "Chapter One"), Chapter(4_000, 8_000, "Chapter Two"), Chapter(8_000, 12_000, "Chapter Three"))

    // The device fixtures (tools/make_book_fixtures.py)

    @Test
    fun `an m4b with QuickTime chapters only has them, as the phone must read them`() {
        assertTrue("run from the app module: ${File("").absolutePath}", books.isDirectory)
        assertEquals(three, chaptersOf(read(File(books, "lantern-qt-only.m4b")), 12_000))
    }

    @Test
    fun `an m4b with both kinds has its QuickTime chapters, once`() {
        assertEquals(three, chaptersOf(read(File(books, "lantern-both.m4b")), 12_000))
    }

    @Test
    fun `a text track nothing names is no chapter track, and files without one or that are no MP4 have none`() {
        // lantern-chpl-only.m4b is lantern-both.m4b with its chap box renamed free: Media3 reads its chpl.
        for (name in listOf("lantern-chpl-only.m4b", "lantern-no-chapters.m4b", "lantern-chap.mp3", "lantern-chapters.opus", "lantern-chapters.ogg", "plain-book.mp3")) {
            assertEquals(name, emptyList<Media3Chapter>(), read(File(books, name)))
        }
    }

    // Books built box by box

    @Test
    fun `a book shaped like real m4b books, moov first and a chunk per chapter, reads in its own timescale`() {
        // 22.05 kHz ticks: 668.5 s, 1022 s, 600 s; the movie and the audio count in other units.
        val bytes = book(listOf(sample("Lantern One"), sample("Lantern Two"), sample("Lantern Three")), ticks = listOf(14_740_425, 22_535_100, 13_230_000))
        assertEquals(
            listOf(Chapter(0, 668_500, "Lantern One"), Chapter(668_500, 1_690_500, "Lantern Two"), Chapter(1_690_500, 2_290_500, "Lantern Three")),
            chaptersOf(read(bytes), 2_290_500),
        )
    }

    @Test
    fun `moov after the audio, 64-bit offsets and sizes, several chapters a chunk, version 1 headers`() {
        val titles = listOf("Part A", "Part B", "Part C", "Part D", "Part E", "Part F")
        val bytes = book(titles.map(::sample), ticks = List(6) { 1_200L }, timescale = 600, chunks = listOf(2, 1, 3), moovLast = true, wide = true, version = 1)
        assertEquals(titles.mapIndexed { at, title -> Triple(at * 2_000L, at * 2_000L + 2_000, title) }, marks(read(bytes)))
    }

    @Test
    fun `sample sizes from a fixed stsz and from stz2 of 16, 8 and 4 bits`() {
        val same = listOf(sample("Act 1", encd = false), sample("Act 2", encd = false), sample("Act 3", encd = false))
        val want = listOf("Act 1", "Act 2", "Act 3")
        for (sizes in listOf(::fixedStsz, stz2(16), stz2(8), stz2(4))) {
            assertEquals(want, read(book(same, sizes = sizes)).map { it.title?.value })
        }
        // Sizes that differ, in stz2 of 8 bits.
        val mixed = listOf(sample("Act 1"), sample("Second act"), sample("Act 3, the end"))
        assertEquals(listOf("Act 1", "Second act", "Act 3, the end"), read(book(mixed, sizes = stz2(8))).map { it.title?.value })
    }

    @Test
    fun `titles in UTF-8 or UTF-16 by their byte order mark, without NULs or blanks, an empty one none`() {
        val samples = listOf(
            text("Café Lantern".toByteArray()),
            text(byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + "灯台 Keeper".toByteArray(Charsets.UTF_16BE)),
            text(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "Little Endian".toByteArray(Charsets.UTF_16LE)),
            text(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "Marked Eight".toByteArray()),
            text("  Ends in a NUL\u0000".toByteArray()),
            text(ByteArray(0)),
            text("   ".toByteArray()),
        )
        assertEquals(
            listOf("Café Lantern", "灯台 Keeper", "Little Endian", "Marked Eight", "Ends in a NUL", null, null),
            read(book(samples)).map { it.title?.value },
        )
    }

    @Test
    fun `a title longer than its sample keeps what the sample holds, and a long title is read only so far`() {
        val clipped = u16(200) + "Short".toByteArray()
        val long = text("Lantern ".repeat(2_000).toByteArray())
        val read = read(book(listOf(clipped, long))).map { it.title?.value.orEmpty() }
        assertEquals("Short", read[0])
        assertTrue(read[1].length in 100..2_000)
        assertTrue(read[1].startsWith("Lantern Lantern"))
    }

    @Test
    fun `when an m4b has both and they disagree, its QuickTime chapters are the ones`() {
        val nero = chpl(0L to "Nero One", 1_000L to "Nero Two")
        val bytes = book(listOf(sample("Track One"), sample("Track Two")), chpl = nero)
        assertEquals(listOf(Triple(0L, 4_000L, "Track One"), Triple(4_000L, 8_000L, "Track Two")), marks(read(bytes)))
    }

    @Test
    fun `only a text track named by chap is read, the first of its references that has chapters`() {
        val titles = listOf(sample("Lantern One"), sample("Lantern Two"))
        // A video track (chapter pictures) named first, the text track second.
        assertEquals(listOf("Lantern One", "Lantern Two"), read(book(titles, references = listOf(3, 2), extraVideo = true)).map { it.title?.value })
        // The audio track naming itself, a track that is not there, or a chapter track that is no text.
        assertEquals(emptyList<Media3Chapter>(), read(book(titles, references = listOf(1))))
        assertEquals(emptyList<Media3Chapter>(), read(book(titles, references = listOf(7))))
        assertEquals(emptyList<Media3Chapter>(), read(book(titles, handler = "vide")))
        assertEquals(listOf("Lantern One", "Lantern Two"), read(book(titles, handler = "sbtl")).map { it.title?.value })
    }

    @Test
    fun `at most the chapters a book may keep are read`() {
        val many = List(BookState.MAX_CHAPTERS + 50) { sample("Part $it", encd = false) }
        val read = read(book(many, ticks = List(many.size) { TIMESCALE }))
        assertEquals(BookState.MAX_CHAPTERS, read.size)
        assertEquals("Part ${BookState.MAX_CHAPTERS - 1}", read.last().title?.value)
    }

    // Broken files

    @Test
    fun `counts far beyond the box are read only as far as the box goes`() {
        val titles = listOf(sample("Lantern One"), sample("Lantern Two"))
        val want = listOf("Lantern One", "Lantern Two")
        for ((type, at) in listOf("stts" to 4, "stsz" to 8, "stsc" to 4, "stco" to 4)) {
            val bytes = book(titles, chunks = listOf(2)).also { patch(it, type, at, 0xFFFF_FFFFL) }
            val read = read(bytes).map { it.title?.value }
            assertTrue("$type: $read", read.size <= 2 && want.containsAll(read))
        }
        // A chunk of four billion samples holds the two there are.
        assertEquals(want, read(book(titles, chunks = listOf(2)).also { patch(it, "stsc", 12, 0xFFFF_FFFFL) }).map { it.title?.value })
    }

    @Test
    fun `a sample outside the file, or too big for it, is left out and the others kept`() {
        val titles = listOf(sample("Lantern One"), sample("Lantern Two"), sample("Lantern Three"))
        // The second chunk's offset past the end of the file.
        val far = book(titles).also { patch(it, "stco", 12, 0x7FFF_0000L) }
        assertEquals(listOf("Lantern One", "Lantern Three"), read(far).map { it.title?.value })
        // The first sample four gigabytes long.
        val huge = book(titles).also { patch(it, "stsz", 12, 0xFFFF_FFFFL) }
        assertEquals(listOf("Lantern Two", "Lantern Three"), read(huge).map { it.title?.value })
    }

    @Test
    fun `no timescale, or no table, is no chapters`() {
        val titles = listOf(sample("Lantern One"))
        assertEquals(emptyList<Media3Chapter>(), read(book(titles).also { patch(it, "mdhd", 12, 0, last = true) }))
        for (type in listOf("stts", "stsz", "stsc", "stco")) {
            assertEquals(type, emptyList<Media3Chapter>(), read(book(titles).also { rename(it, type, "free") }))
        }
    }

    @Test
    fun `a file cut short anywhere reads only chapters that are whole, and never fails`() {
        val bytes = book(listOf(sample("Lantern One"), sample("Lantern Two"), sample("Lantern Three")))
        val file = folder.newFile()
        for (cut in 0..bytes.size) {
            file.writeBytes(bytes.copyOf(cut))
            val read = read(file).map { it.title?.value }
            assertEquals("cut at $cut", listOf("Lantern One", "Lantern Two", "Lantern Three").take(read.size), read)
        }
        // Box sizes of 0 (to the end), 1 with no room for the 64-bit size, and below a header.
        for (size in listOf(0L, 1L, 4L, 7L)) {
            read(book(listOf(sample("Lantern One"))).also { patch(it, "trak", -8, size) })
        }
    }

    @Test
    fun `a read that gives no bytes ends the read at once, and one that gives a byte at a time reads the whole book`() {
        val file = folder.newFile().apply { writeBytes(book(listOf(sample("Lantern One"), sample("Lantern Two")))) }
        // A channel may give 0 bytes a read; one that never gives more is not asked again and again.
        val stalled = Files.newByteChannel(file.toPath()).use { Trickle(it, most = 0).run { quickTimeChapters(this) to reads } }
        assertEquals(emptyList<Media3Chapter>() to 1, stalled)
        val trickled = Files.newByteChannel(file.toPath()).use { quickTimeChapters(Trickle(it, most = 1)) }
        assertEquals(listOf("Lantern One", "Lantern Two"), trickled.map { it.title?.value })
    }

    @Test
    fun `any byte of a book changed at random never fails the read, nor reads more than there is`() {
        val random = Random(20261115)
        val shapes = listOf(
            File(books, "lantern-qt-only.m4b").readBytes(),
            book(listOf(sample("Lantern One"), sample("Lantern Two"), sample("Lantern Three"))),
            book(List(6) { sample("Part $it") }, chunks = listOf(2, 1, 3), moovLast = true, wide = true, version = 1, sizes = stz2(8)),
        )
        val file = folder.newFile()
        repeat(3_000) { round ->
            val bytes = shapes[round % shapes.size].copyOf()
            repeat(1 + random.nextInt(4)) { bytes[random.nextInt(bytes.size)] = random.nextInt(256).toByte() }
            file.writeBytes(bytes)
            assertTrue(read(file).size <= 6)
        }
    }
}

/**
 * [file] as a channel that gives at most [most] bytes a read (none at all for 0), and after [GIVE_UP] reads its end, so
 * a reader that asks forever fails a test instead of hanging it.
 */
private class Trickle(private val file: SeekableByteChannel, private val most: Int) : SeekableByteChannel by file {
    var reads = 0
        private set

    override fun read(dst: ByteBuffer): Int {
        if (++reads >= GIVE_UP) return -1
        if (most == 0) return 0
        val part = ByteBuffer.allocate(minOf(most, dst.remaining()))
        val count = file.read(part)
        part.flip()
        dst.put(part)
        return count
    }

    companion object {
        const val GIVE_UP: Int = 100_000
    }
}

// Boxes, by hand

private const val TIMESCALE = 22_050L

private fun u8(value: Int) = byteArrayOf(value.toByte())
private fun u16(value: Int) = byteArrayOf((value shr 8).toByte(), value.toByte())
private fun u32(value: Long): ByteArray = ByteBuffer.allocate(4).putInt(value.toInt()).array()
private fun u64(value: Long): ByteArray = ByteBuffer.allocate(8).putLong(value).array()
private fun cat(parts: List<ByteArray>): ByteArray = parts.fold(ByteArray(0)) { all, part -> all + part }
private fun fourcc(type: String) = type.toByteArray(Charsets.ISO_8859_1)

private fun box(type: String, vararg body: ByteArray): ByteArray = cat(body.toList()).let { cat(listOf(u32(8L + it.size), fourcc(type), it)) }

/** A full box: its version, no flags, then [body]. */
private fun full(type: String, version: Int, vararg body: ByteArray): ByteArray = box(type, u32(version.toLong() shl 24), *body)

/** A chapter sample: the text's 16-bit length and [bytes]. */
private fun text(bytes: ByteArray): ByteArray = u16(bytes.size) + bytes

/** A chapter sample as Apple's and ffmpeg's are: [title] in UTF-8, then (with [encd]) the encd atom saying so. */
private fun sample(title: String, encd: Boolean = true): ByteArray = text(title.toByteArray()) + if (encd) box("encd", u32(0x100)) else ByteArray(0)

private fun stsz(sizes: List<Int>): ByteArray = full("stsz", 0, u32(0), u32(sizes.size.toLong()), *sizes.map { u32(it.toLong()) }.toTypedArray())

/** Every sample the same size, said once. */
private fun fixedStsz(sizes: List<Int>): ByteArray = full("stsz", 0, u32(sizes.first().toLong()), u32(sizes.size.toLong())).also { require(sizes.toSet().size == 1) }

/** stz2: each size in [bits], 4-bit ones two to a byte, the first in the high half. */
private fun stz2(bits: Int): (List<Int>) -> ByteArray = { sizes ->
    val packed = when (bits) {
        16 -> cat(sizes.map(::u16))
        8 -> cat(sizes.map(::u8))
        else -> cat(sizes.chunked(2).map { u8(it[0] shl 4 or (it.getOrNull(1) ?: 0)) })
    }
    full("stz2", 0, byteArrayOf(0, 0, 0, bits.toByte()), u32(sizes.size.toLong()), packed)
}

/** Nero chapters (moov/udta/chpl) as ffmpeg writes them: starts in 100 ns units, titles of a byte's length. */
private fun chpl(vararg marks: Pair<Long, String>): ByteArray =
    full("chpl", 1, u32(0), u8(marks.size), *marks.map { (ms, title) -> u64(ms * 10_000) + u8(title.length) + title.toByteArray() }.toTypedArray())

private fun tkhd(version: Int, id: Int): ByteArray =
    if (version == 1) full("tkhd", 1, u64(0), u64(0), u32(id.toLong()), ByteArray(72)) else full("tkhd", 0, u32(0), u32(0), u32(id.toLong()), ByteArray(68))

private fun mdhd(version: Int, timescale: Long, duration: Long): ByteArray =
    if (version == 1) full("mdhd", 1, u64(0), u64(0), u32(timescale), u64(duration), u32(0)) else full("mdhd", 0, u32(0), u32(0), u32(timescale), u32(duration), u32(0))

private fun hdlr(handler: String): ByteArray = full("hdlr", 0, u32(0), fourcc(handler), ByteArray(12), byteArrayOf(0))

/** stts in runs of equal durations, as ffmpeg writes it (a LibriVox file has a run per chapter, as most differ). */
private fun stts(ticks: List<Long>): ByteArray {
    val runs = ticks.fold(listOf<Pair<Long, Long>>()) { runs, tick -> if (runs.lastOrNull()?.second == tick) runs.dropLast(1) + (runs.last().first + 1 to tick) else runs + (1L to tick) }
    return full("stts", 0, u32(runs.size.toLong()), *runs.map { (count, tick) -> u32(count) + u32(tick) }.toTypedArray())
}

/** stsc: a run for each change in [chunks] (samples in each chunk), as a muxer writes it. */
private fun stsc(chunks: List<Int>): ByteArray {
    val runs = chunks.withIndex().filter { (at, count) -> at == 0 || chunks[at - 1] != count }
    return full("stsc", 0, u32(runs.size.toLong()), *runs.map { (at, count) -> u32(at + 1L) + u32(count.toLong()) + u32(1) }.toTypedArray())
}

/**
 * A silent book with a QuickTime chapter track, as the real files have it: ftyp, moov (track 1 the audio, whose tref/chap
 * names [references], track 2 the chapters' text track, with the [handler] given), then mdat; or mdat first ([moovLast],
 * as ffmpeg writes). The text track's [samples] last [ticks] each in its [timescale], stored after the audio [chunks]
 * of them at a time. [wide]: a 64-bit mdat size and co64 offsets. [version]: tkhd and mdhd of version 1. [extraVideo]: a
 * track 3 with a video handler, as chapter pictures have.
 */
private fun book(
    samples: List<ByteArray>,
    ticks: List<Long> = samples.map { 4 * TIMESCALE },
    timescale: Long = TIMESCALE,
    chunks: List<Int> = samples.map { 1 },
    moovLast: Boolean = false,
    wide: Boolean = false,
    version: Int = 0,
    sizes: (List<Int>) -> ByteArray = ::stsz,
    handler: String = "text",
    references: List<Int> = listOf(2),
    extraVideo: Boolean = false,
    chpl: ByteArray? = null,
): ByteArray {
    require(chunks.sum() == samples.size)
    val ftyp = box("ftyp", fourcc("M4B "), u32(0x200), fourcc("M4B "), fourcc("mp42"), fourcc("isom"))
    val audio = ByteArray(64)
    val mdat = cat(listOf(audio) + samples)
    val mdatHeader = if (wide) 16 else 8
    fun moov(mdatAt: Long): ByteArray {
        val firsts = chunks.runningFold(0) { at, count -> at + count }.dropLast(1)
        val offsets = firsts.map { first -> mdatAt + mdatHeader + audio.size + samples.take(first).sumOf { it.size } }
        val chunkOffsets = if (wide) full("co64", 0, u32(offsets.size.toLong()), *offsets.map(::u64).toTypedArray()) else full("stco", 0, u32(offsets.size.toLong()), *offsets.map(::u32).toTypedArray())
        val stbl = box("stbl", full("stsd", 0, u32(1), box("text", ByteArray(51))), stts(ticks), sizes(samples.map { it.size }), stsc(chunks), chunkOffsets)
        val audioTrack = box(
            "trak",
            tkhd(version, 1),
            box("tref", box("chap", *references.map { u32(it.toLong()) }.toTypedArray())),
            box("mdia", mdhd(version, 44_100, 0), hdlr("soun"), box("minf", box("smhd", ByteArray(8)))),
        )
        val textTrack = box("trak", tkhd(version, 2), box("mdia", mdhd(version, timescale, ticks.sum()), hdlr(handler), box("minf", box("gmhd", ByteArray(8)), stbl)))
        val videoTrack = if (extraVideo) box("trak", tkhd(version, 3), box("mdia", mdhd(version, 600, 0), hdlr("vide"), box("minf", box("stbl", stts(listOf(600)), stsz(listOf(4)), stsc(listOf(1)), full("stco", 0, u32(1), u32(mdatAt + mdatHeader)))))) else ByteArray(0)
        return box("moov", full("mvhd", 0, ByteArray(96)), audioTrack, textTrack, videoTrack, chpl?.let { box("udta", it) } ?: ByteArray(0))
    }
    val mdatBox = if (wide) cat(listOf(u32(1), fourcc("mdat"), u64(16L + mdat.size), mdat)) else box("mdat", mdat)
    if (moovLast) return cat(listOf(ftyp, mdatBox, moov(ftyp.size.toLong())))
    val size = moov(0).size.toLong()
    return cat(listOf(ftyp, moov(ftyp.size + size), mdatBox))
}

/** Writes [value] as 32 bits [at] bytes into the body of the first (or [last]) box of [type] in [bytes]. */
private fun patch(bytes: ByteArray, type: String, at: Int, value: Long, last: Boolean = false) {
    val found = find(bytes, type, last)
    u32(value).copyInto(bytes, found + 4 + at)
}

/** Renames the first box of [type] in [bytes] to [to], its size kept. */
private fun rename(bytes: ByteArray, type: String, to: String) {
    fourcc(to).copyInto(bytes, find(bytes, type, last = false))
}

/** Where the type of the first (or [last]) box of [type] is in [bytes]; the titles here never hold a box's name. */
private fun find(bytes: ByteArray, type: String, last: Boolean): Int {
    val name = fourcc(type)
    val all = (0..bytes.size - 4).filter { at -> name.indices.all { bytes[at + it] == name[it] } }
    return checkNotNull(if (last) all.lastOrNull() else all.firstOrNull()) { "no $type box" }
}
