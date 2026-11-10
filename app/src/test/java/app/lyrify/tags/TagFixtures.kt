package app.lyrify.tags

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.random.Random

/**
 * Small song files built in code, in the shape of each format KJB changes: a tag block in the format's own place and
 * made-up audio data (random bytes after a valid frame start), so the payload hash and the writer can be tested on the
 * JVM. Not playable; the device tests use real encoded fixtures.
 */
object TagFixtures {
    /** MPEG-1 Layer III, 128 kbps, 44.1 kHz, no padding: a 417-byte frame. */
    private val MP3_FRAME_HEADER = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00)
    const val MP3_FRAME_BYTES: Int = 417

    /** [frames] MP3 frames of made-up audio, the same for the same [seed]. */
    fun mp3Audio(frames: Int = 12, seed: Int = 1): ByteArray {
        val random = Random(seed)
        val out = ByteArrayOutputStream()
        repeat(frames) {
            out.write(MP3_FRAME_HEADER)
            out.write(random.nextBytes(MP3_FRAME_BYTES - MP3_FRAME_HEADER.size))
        }
        return out.toByteArray()
    }

    /** An ID3v2 tag ([version] 2.3 or 2.4) holding [body] (any bytes) and [padding] zeros. */
    fun id3v2(body: ByteArray, version: Int = 3, padding: Int = 0, footer: Boolean = false): ByteArray {
        val size = body.size + padding
        val out = ByteArrayOutputStream()
        out.write("ID3".encodeToByteArray())
        out.write(byteArrayOf(version.toByte(), 0, if (footer) 0x10 else 0))
        out.write(syncsafe(size))
        out.write(body)
        out.write(ByteArray(padding))
        if (footer) {
            out.write("3DI".encodeToByteArray())
            out.write(byteArrayOf(version.toByte(), 0, 0x10))
            out.write(syncsafe(size))
        }
        return out.toByteArray()
    }

    /** A 128-byte ID3v1 tag with [title]. */
    fun id3v1(title: String = "Paper Lantern"): ByteArray =
        ("TAG" + title.padEnd(30, '\u0000')).encodeToByteArray().copyOf(128)

    /** An APEv2 tag (items, then a footer; with [header], a header first) holding text [items] and a binary cover. */
    fun ape(items: Map<String, String>, cover: ByteArray? = null, header: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream()
        fun item(key: String, value: ByteArray, binary: Boolean) {
            out.write(ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(value.size).putInt(if (binary) 2 else 0).array())
            out.write(key.encodeToByteArray() + byteArrayOf(0) + value)
        }
        items.forEach { (key, value) -> item(key, value.encodeToByteArray(), binary = false) }
        cover?.let { item("Cover Art (Front)", byteArrayOf(0) + it, binary = true) }
        val count = items.size + if (cover != null) 1 else 0
        val body = out.toByteArray()
        fun frame(isHeader: Boolean): ByteArray = "APETAGEX".encodeToByteArray() +
            ByteBuffer.allocate(24).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .putInt(2000).putInt(body.size + 32).putInt(count)
                .putInt((if (header) 1 shl 31 else 0) or (if (isHeader) 1 shl 29 else 0)).putLong(0).array()
        return (if (header) frame(true) else ByteArray(0)) + body + frame(false)
    }

    fun mp3(tag: ByteArray? = id3v2("TIT2 Paper Lantern".encodeToByteArray()), audio: ByteArray = mp3Audio(), v1: ByteArray? = null): ByteArray =
        (tag ?: ByteArray(0)) + audio + (v1 ?: ByteArray(0))

    /** FLAC audio frames: a frame sync, then made-up data. */
    fun flacAudio(bytes: Int = 4_000, seed: Int = 2): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xF8.toByte()) + Random(seed).nextBytes(bytes - 2)

    /** A FLAC file: STREAMINFO, a comment block holding [comment], [picture] bytes if any, [padding] zeros, then [audio]. */
    fun flac(comment: ByteArray = "TITLE=Paper Lantern".encodeToByteArray(), picture: ByteArray? = null, padding: Int = 64, audio: ByteArray = flacAudio(), id3Prefix: ByteArray? = null, v1: ByteArray? = null): ByteArray {
        val blocks = buildList {
            add(0 to ByteArray(34) { 7 })
            add(4 to comment)
            picture?.let { add(6 to it) }
            add(1 to ByteArray(padding))
        }
        val out = ByteArrayOutputStream()
        id3Prefix?.let(out::write)
        out.write("fLaC".encodeToByteArray())
        blocks.forEachIndexed { index, (type, body) ->
            val last = if (index == blocks.lastIndex) 0x80 else 0
            out.write(type or last)
            out.write(byteArrayOf((body.size shr 16).toByte(), (body.size shr 8).toByte(), body.size.toByte()))
            out.write(body)
        }
        out.write(audio)
        v1?.let(out::write)
        return out.toByteArray()
    }

    /** A box: its size, its type and [body]. */
    fun box(type: String, vararg body: ByteArray): ByteArray {
        val content = body.fold(ByteArray(0)) { all, part -> all + part }
        return ByteBuffer.allocate(8 + content.size).putInt(8 + content.size).put(type.toByteArray(Charsets.ISO_8859_1)).put(content).array()
    }

    private fun fullBox(type: String, body: ByteArray): ByteArray = box(type, ByteArray(4), body)

    /**
     * An M4A file: ftyp, then moov (one track whose chunks are [chunks] bytes each, and an `ilst` holding [tags]) and
     * mdat in [moovFirst] order. With [co64], the chunk offsets are 64-bit. [offsetShift] moves every chunk offset by
     * that many bytes, as a writer that forgot to update them would leave it.
     */
    fun m4a(tags: ByteArray = "Paper Lantern".encodeToByteArray(), audio: ByteArray = Random(3).nextBytes(3_000), chunks: Int = 3, moovFirst: Boolean = true, co64: Boolean = false, offsetShift: Long = 0): ByteArray {
        val ftyp = box("ftyp", "M4A ".encodeToByteArray(), ByteArray(4), "isomM4A ".encodeToByteArray())
        val chunkSize = audio.size / chunks
        fun moov(mdatPayloadAt: Long): ByteArray {
            val offsets = (0 until chunks).map { mdatPayloadAt + it * chunkSize + offsetShift }
            val table = if (co64) {
                fullBox("co64", ByteBuffer.allocate(4 + 8 * chunks).putInt(chunks).apply { offsets.forEach { putLong(it) } }.array())
            } else {
                fullBox("stco", ByteBuffer.allocate(4 + 4 * chunks).putInt(chunks).apply { offsets.forEach { putInt(it.toInt()) } }.array())
            }
            val trak = box("trak", box("mdia", box("minf", box("stbl", table))))
            val udta = box("udta", fullBox("meta", box("ilst", box("©nam", tags))))
            return box("moov", box("mvhd", ByteArray(100)), trak, udta)
        }
        val mdatHeader = 8
        return if (moovFirst) {
            val moovSize = moov(0).size
            ftyp + moov((ftyp.size + moovSize + mdatHeader).toLong()) + box("mdat", audio)
        } else {
            ftyp + box("mdat", audio) + moov((ftyp.size + mdatHeader).toLong())
        }
    }

    /** Ogg packets: Vorbis's three header packets or Opus's two, then audio packets of made-up data. */
    fun vorbisPackets(comment: ByteArray = "Paper Lantern".encodeToByteArray(), audio: List<ByteArray> = audioPackets()): List<ByteArray> =
        listOf(
            byteArrayOf(1) + "vorbis".encodeToByteArray() + ByteArray(23),
            byteArrayOf(3) + "vorbis".encodeToByteArray() + comment,
            byteArrayOf(5) + "vorbis".encodeToByteArray() + Random(4).nextBytes(300),
        ) + audio

    fun opusPackets(comment: ByteArray = "Paper Lantern".encodeToByteArray(), audio: List<ByteArray> = audioPackets()): List<ByteArray> =
        listOf("OpusHead".encodeToByteArray() + ByteArray(11), "OpusTags".encodeToByteArray() + comment) + audio

    fun audioPackets(count: Int = 40, seed: Int = 5): List<ByteArray> {
        val random = Random(seed)
        return List(count) { index -> random.nextBytes(if (index % 7 == 0) 600 else 90) }
    }

    /**
     * An Ogg stream of [packets] in pages: the first packet alone on the first page, the other [headers] header packets
     * from the second page on, then audio from a fresh page, a few packets a page. Sequence numbers count up and each
     * page carries its CRC, as a real stream does, so a bigger header renumbers every page after it.
     */
    fun ogg(packets: List<ByteArray>, headers: Int, serial: Int = 0x1A2B3C4D, secondSerialAt: Int? = null): ByteArray {
        val out = ByteArrayOutputStream()
        val groups = listOf(listOf(packets[0]), packets.subList(1, headers)) + packets.drop(headers).chunked(4)
        var sequence = 0
        groups.forEachIndexed { group, contents ->
            // Lacing: each packet as 255s and a last value under 255; more than 255 segments go on to the next page.
            val segments = contents.flatMap { packet ->
                (0..packet.size / 255).map { at -> packet.copyOfRange(at * 255, minOf(packet.size, at * 255 + 255)) }
            }
            val pages = segments.chunked(255)
            pages.forEachIndexed { index, page ->
                val flags = (if (group == 0) 2 else 0) or (if (index > 0) 1 else 0) or (if (group == groups.lastIndex && index == pages.lastIndex) 4 else 0)
                val granule = when {
                    page.last().size == 255 -> -1L
                    group < 2 -> 0L
                    else -> (group - 1) * 3_840L
                }
                val header = ByteBuffer.allocate(27 + page.size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                header.put("OggS".encodeToByteArray()).put(0).put(flags.toByte()).putLong(granule)
                header.putInt(if (secondSerialAt == group - 2) serial + 1 else serial).putInt(sequence++).putInt(0).put(page.size.toByte())
                page.forEach { header.put(it.size.toByte()) }
                val bytes = header.array() + page.fold(ByteArray(0)) { all, segment -> all + segment }
                val crc = crc(bytes)
                for (i in 0 until 4) bytes[22 + i] = (crc shr (8 * i)).toByte()
                out.write(bytes)
            }
        }
        return out.toByteArray()
    }

    /** The number of pages in Ogg [bytes]. */
    fun oggPages(bytes: ByteArray): Int = (0..bytes.size - 4).count { bytes[it] == 'O'.code.toByte() && bytes.copyOfRange(it, it + 4).contentEquals("OggS".encodeToByteArray()) }

    private fun syncsafe(size: Int): ByteArray =
        byteArrayOf((size shr 21 and 0x7F).toByte(), (size shr 14 and 0x7F).toByte(), (size shr 7 and 0x7F).toByte(), (size and 0x7F).toByte())

    /** Ogg's CRC-32 (polynomial 0x04C11DB7, no reflection), over a page with its CRC field zero. */
    private fun crc(bytes: ByteArray): Int {
        var crc = 0
        for (byte in bytes) {
            crc = crc xor ((byte.toInt() and 0xFF) shl 24)
            repeat(8) { crc = if (crc and 0x80000000.toInt() != 0) (crc shl 1) xor 0x04C11DB7 else crc shl 1 }
        }
        return crc
    }
}
