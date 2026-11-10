package app.lyrify.tags

import kotlinx.serialization.Serializable
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.security.MessageDigest

/** The formats KJB changes: the allowlist. Any other file is "Couldn't change", even one TagLib could open. */
enum class AudioFormat {
    MP3,
    FLAC,
    M4A,
    OGG_VORBIS,
    OPUS,
    ;

    companion object {
        /** The file name extensions of the allowed formats; an `.ogg` or `.opus` file is Vorbis or Opus by its content. */
        val EXTENSIONS: Set<String> = setOf("mp3", "flac", "m4a", "ogg", "opus")
    }
}

/** The audio payload of a song file: what a tag write must never change. */
@Serializable
data class Payload(
    val format: AudioFormat,
    /** SHA-256 (hex) of the audio data alone, as [PayloadHash] defines it per format. */
    val sha256: String,
    /** M4A only: SHA-256 of where each chunk offset points inside the audio data; equal when the offsets stayed valid. */
    val layout: String? = null,
)

/** The start of a song's audio payload, hashed ([PayloadHash.slice]); [whole] when that was all of it. */
data class PayloadSlice(val format: AudioFormat, val sha256: String, val whole: Boolean)

/**
 * The one audio payload hash, used by the writer's checks, by Undo and by Duplicates. It covers the audio data only,
 * so a retag never changes it:
 * - MP3: the bytes between the ID3v2 tag (at the start, if any) and an APE tag (if any) or an ID3v1 tag (the last 128
 *   bytes, if any), whichever comes first: the tag writer updates an APE tag an MP3 already has;
 * - FLAC: the bytes after the metadata blocks (and after an ID3v2 tag before them), up to an ID3v1 tag;
 * - M4A: the contents of every `mdat` box, in order. [Payload.layout] records where each `stco`/`co64` chunk offset
 *   points inside them, so a write that left the offsets wrong is caught;
 * - Ogg Vorbis and Opus: each audio packet after the header packets (3 for Vorbis, 2 for Opus) and its length, with
 *   pages, sequence numbers and CRCs left out, since a bigger comment header renumbers the pages.
 *
 * A file that is not a well-formed file of its format (no MPEG frame near the start, a cut metadata block, a chunk
 * offset outside `mdat`, a fragmented M4A, a second Ogg stream) has no payload: KJB never changes it. Blocking; reads
 * only.
 */
object PayloadHash {
    /** [path]'s payload, or null when its format is not allowed or the file is not well formed. */
    fun of(path: Path): Payload? = read(path, Long.MAX_VALUE)?.payload

    /**
     * The first [bytes] of [path]'s audio payload (all of it when shorter), hashed as [of] hashes it, without M4A's
     * chunk layout: equal for two copies of one song whatever their tags, and cheap, since it reads only the start of
     * the audio. Duplicates' prefilter. Null as for [of].
     */
    fun slice(path: Path, bytes: Long): PayloadSlice? = read(path, bytes)?.let { PayloadSlice(it.payload.format, it.payload.sha256, it.whole) }

    /** A payload hashed up to a limit: [whole] when the limit left nothing out. */
    private class Read(val payload: Payload, val whole: Boolean)

    private fun read(path: Path, limit: Long): Read? {
        val extension = path.fileName?.toString()?.substringAfterLast('.', "")?.lowercase() ?: return null
        if (extension !in AudioFormat.EXTENSIONS) return null
        return try {
            FileInputStream(path.toFile()).use { input ->
                val file = Reader(input.channel)
                when (extension) {
                    "mp3" -> mp3(file, limit)
                    "flac" -> flac(file, limit)
                    "m4a" -> m4a(file, limit)
                    else -> ogg(file, limit)
                }
            }
        } catch (_: IOException) {
            null
        } catch (_: Malformed) {
            null
        }
    }

    /** [from] to [to] of [file], or its first [limit] bytes. */
    private fun range(file: Reader, format: AudioFormat, from: Long, to: Long, limit: Long): Read {
        val stop = if (to - from > limit) from + limit else to
        return Read(Payload(format, file.hash(from, stop)), stop == to)
    }

    /** SHA-256 (hex) of every byte of the file at [path]. */
    fun fileSha256(path: Path): String = FileInputStream(path.toFile()).use { input ->
        val file = Reader(input.channel)
        val digest = sha256()
        file.digest(digest, 0, file.size)
        digest.hex()
    }

    private fun mp3(file: Reader, limit: Long): Read {
        val start = id3v2End(file, 0)
        val end = apeStart(file, start, id3v1Start(file, start))
        // Real MPEG audio starts with a frame header within the first bytes after the tag.
        val head = file.read(start, minOf(SYNC_WINDOW, end - start).toInt())
        val synced = (0 until head.size - 3).any { mpegFrameHeader(head, it) }
        if (!synced) throw Malformed()
        return range(file, AudioFormat.MP3, start, end, limit)
    }

    private fun flac(file: Reader, limit: Long): Read {
        var at = id3v2End(file, 0)
        if (!file.read(at, 4).contentEquals(FLAC_MAGIC)) throw Malformed()
        at += 4
        var first = true
        while (true) {
            val header = file.read(at, 4)
            val type = header[0].toInt() and 0x7F
            if (first && type != STREAMINFO) throw Malformed()
            first = false
            at += 4 + ((header[1].toInt() and 0xFF) shl 16 or (header[2].toInt() and 0xFF shl 8) or (header[3].toInt() and 0xFF))
            if (at > file.size) throw Malformed()
            if (header[0].toInt() and 0x80 != 0) break
        }
        val end = id3v1Start(file, at)
        val sync = file.read(at, 2)
        if (sync[0] != 0xFF.toByte() || sync[1].toInt() and 0xFE != 0xF8) throw Malformed()
        return range(file, AudioFormat.FLAC, at, end, limit)
    }

    private class Box(val type: String, val start: Long, val bodyStart: Long, val end: Long)

    /** The boxes from [from] to [to], one level; a size that runs past [to] makes the file malformed. */
    private fun boxes(file: Reader, from: Long, to: Long): List<Box> = buildList {
        var at = from
        while (at < to) {
            if (to - at < 8) throw Malformed()
            val header = ByteBuffer.wrap(file.read(at, 8))
            val size32 = header.int.toLong() and 0xFFFFFFFFL
            val type = String(file.read(at + 4, 4), Charsets.ISO_8859_1)
            val (bodyStart, size) = when (size32) {
                0L -> at + 8 to to - at
                1L -> at + 16 to ByteBuffer.wrap(file.read(at + 8, 8)).long
                else -> at + 8 to size32
            }
            if (size < bodyStart - at || at + size > to) throw Malformed()
            add(Box(type, at, bodyStart, at + size))
            at += size
        }
    }

    private fun m4a(file: Reader, limit: Long): Read {
        val top = boxes(file, 0, file.size)
        if (top.firstOrNull()?.type != "ftyp" || top.any { it.type == "moof" }) throw Malformed()
        val moov = top.singleOrNull { it.type == "moov" } ?: throw Malformed()
        val mdats = top.filter { it.type == "mdat" }
        if (mdats.isEmpty()) throw Malformed()

        val audio = sha256()
        var left = limit
        for (mdat in mdats) {
            val take = minOf(mdat.end - mdat.bodyStart, left)
            file.digest(audio, mdat.bodyStart, mdat.bodyStart + take)
            left -= take
        }
        // A slice: the start of the audio, without the chunk layout.
        if (mdats.sumOf { it.end - it.bodyStart } > limit) return Read(Payload(AudioFormat.M4A, audio.hex()), whole = false)

        // Each chunk offset as a place in the audio data: the mdat bodies one after another.
        val layout = sha256()
        val tables = mutableListOf<Box>()
        fun walk(box: Box) {
            when (box.type) {
                "moov", "trak", "mdia", "minf", "stbl" -> boxes(file, box.bodyStart, box.end).forEach(::walk)
                "stco", "co64" -> tables += box
            }
        }
        walk(moov)
        if (tables.isEmpty()) throw Malformed()
        tables.forEach { table ->
            val wide = table.type == "co64"
            val count = ByteBuffer.wrap(file.read(table.bodyStart + 4, 4)).int.toLong() and 0xFFFFFFFFL
            val width = if (wide) 8 else 4
            if (table.bodyStart + 8 + count * width > table.end) throw Malformed()
            val entries = ByteBuffer.wrap(file.read(table.bodyStart + 8, (count * width).toInt()))
            layout.update(ByteBuffer.allocate(8).putLong(count).array())
            repeat(count.toInt()) {
                val offset = if (wide) entries.long else entries.int.toLong() and 0xFFFFFFFFL
                var before = 0L
                val place = mdats.firstNotNullOfOrNull { mdat ->
                    (if (offset >= mdat.bodyStart && offset < mdat.end) before + offset - mdat.bodyStart else null).also { before += mdat.end - mdat.bodyStart }
                } ?: throw Malformed()
                layout.update(ByteBuffer.allocate(8).putLong(place).array())
            }
        }
        return Read(Payload(AudioFormat.M4A, audio.hex(), layout.hex()), whole = true)
    }

    private fun ogg(file: Reader, limit: Long): Read {
        val digest = sha256()
        var digested = 0L
        var format: AudioFormat? = null
        var headers = Int.MAX_VALUE
        var serial: Int? = null
        var packet = 0
        var packetLength = 0L
        var at = 0L
        while (at < file.size) {
            val header = ByteBuffer.wrap(file.read(at, 27)).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4).also(header::get)
            if (!magic.contentEquals(OGG_MAGIC) || header.get().toInt() != 0) throw Malformed()
            header.position(14)
            val pageSerial = header.int
            if (serial != null && pageSerial != serial) throw Malformed()
            serial = pageSerial
            val lacing = file.read(at + 27, header.get(26).toInt() and 0xFF)
            val body = file.read(at + 27 + lacing.size, lacing.sumOf { it.toInt() and 0xFF })
            var offset = 0
            for (value in lacing) {
                val length = value.toInt() and 0xFF
                if (packet == 0 && packetLength == 0L) {
                    // The first packet names the codec, and so how many header packets come before the audio.
                    val start = body.copyOfRange(offset, minOf(body.size, offset + 8))
                    when {
                        start.size >= 7 && start.copyOf(7).contentEquals(VORBIS_ID) -> format = AudioFormat.OGG_VORBIS.also { headers = 3 }
                        start.contentEquals(OPUS_ID) -> format = AudioFormat.OPUS.also { headers = 2 }
                        else -> throw Malformed()
                    }
                }
                if (packet >= headers) {
                    // A slice ends inside the audio: what was read so far.
                    if (length > limit - digested) {
                        digest.update(body, offset, (limit - digested).toInt())
                        return Read(Payload(checkNotNull(format), digest.hex()), whole = false)
                    }
                    digest.update(body, offset, length)
                    digested += length
                }
                packetLength += length
                offset += length
                if (length < 255) {
                    if (packet >= headers) digest.update(ByteBuffer.allocate(8).putLong(packetLength).array())
                    packet++
                    packetLength = 0
                }
            }
            at += 27 + lacing.size + body.size
        }
        if (format == null || packet <= headers || packetLength != 0L) throw Malformed()
        return Read(Payload(format, digest.hex()), whole = true)
    }

    /** Where the ID3v2 tag at [at] ends (with its footer), or [at] when there is none. */
    private fun id3v2End(file: Reader, at: Long): Long {
        if (file.size - at < 10) return at
        val header = file.read(at, 10)
        if (!header.copyOf(3).contentEquals(ID3_MAGIC) || header[3] !in 2..4) return at
        val size = (6..9).fold(0L) { sum, i ->
            val byte = header[i].toInt()
            if (byte and 0x80 != 0) throw Malformed()
            (sum shl 7) or byte.toLong()
        }
        val end = at + 10 + size + if (header[5].toInt() and 0x10 != 0) 10 else 0
        if (end > file.size) throw Malformed()
        return end
    }

    /** Where an ID3v1 tag at the end starts, if there is one after [after]; else the file's size. */
    private fun id3v1Start(file: Reader, after: Long): Long {
        val start = file.size - ID3V1_BYTES
        return if (start >= after && file.read(start, 3).contentEquals(ID3V1_MAGIC)) start else file.size
    }

    /**
     * Where an APE tag ending at [end] starts (with its header, if it has one), or [end] when no whole tag ends there: the
     * footer, a header where the footer says one is (with the same version, size and count), and exactly the items the
     * footer counts, filling the space between. Anything less is audio: a footer alone never cuts the payload short.
     */
    private fun apeStart(file: Reader, after: Long, end: Long): Long {
        if (end - after < APE_FOOTER_BYTES) return end
        val footer = ByteBuffer.wrap(file.read(end - APE_FOOTER_BYTES, APE_FOOTER_BYTES.toInt())).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        if (!ByteArray(8).also { footer.get(it) }.contentEquals(APE_MAGIC)) return end
        val version = footer.getInt(8)
        val size = footer.getInt(12).toLong() and 0xFFFFFFFFL
        val count = footer.getInt(16).toLong() and 0xFFFFFFFFL
        val flags = footer.getInt(20)
        if (version != 1000 && version != 2000) return end
        // The footer's own flags never say "this is the header".
        if (flags and APE_IS_HEADER != 0) return end
        if (size < APE_FOOTER_BYTES || size > end - after || count > (size - APE_FOOTER_BYTES) / APE_MIN_ITEM_BYTES) return end
        val items = end - size
        val header = version == 2000 && flags and APE_HAS_HEADER != 0
        val start = items - if (header) APE_FOOTER_BYTES else 0
        if (start < after) return end
        if (header) {
            val head = ByteBuffer.wrap(file.read(start, APE_FOOTER_BYTES.toInt())).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val same = ByteArray(8).also { head.get(it) }.contentEquals(APE_MAGIC) && head.getInt(8) == version &&
                (head.getInt(12).toLong() and 0xFFFFFFFFL) == size && (head.getInt(16).toLong() and 0xFFFFFFFFL) == count &&
                head.getInt(20) and APE_IS_HEADER != 0
            if (!same) return end
        }
        // Each item: value size and flags (8 bytes), a key of 2 to 255 printable ASCII characters, a 0, the value.
        val itemsEnd = end - APE_FOOTER_BYTES
        var at = items
        repeat(count.toInt()) {
            if (at + APE_MIN_ITEM_BYTES > itemsEnd) return end
            val valueSize = ByteBuffer.wrap(file.read(at, 4)).order(java.nio.ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
            val key = file.read(at + 8, minOf(APE_MAX_KEY + 1L, itemsEnd - at - 8).toInt())
            val keyLength = key.indexOf(0.toByte())
            if (keyLength < 2 || key.take(keyLength).any { it < 0x20 || it > 0x7E }) return end
            at += 8 + keyLength + 1 + valueSize
            if (at > itemsEnd) return end
        }
        return if (at == itemsEnd) start else end
    }

    /** An MPEG audio frame header at [at]: the sync, and no reserved version, layer, bitrate or sample rate. */
    private fun mpegFrameHeader(bytes: ByteArray, at: Int): Boolean {
        val b1 = bytes[at + 1].toInt() and 0xFF
        val b2 = bytes[at + 2].toInt() and 0xFF
        return bytes[at] == 0xFF.toByte() && b1 and 0xE0 == 0xE0 && b1 and 0x18 != 0x08 && b1 and 0x06 != 0 &&
            b2 shr 4 != 0x0F && b2 and 0x0C != 0x0C
    }

    /** Positional reads of one file; reading past its end makes it malformed. */
    private class Reader(private val channel: FileChannel) {
        val size: Long = channel.size()

        fun read(at: Long, length: Int): ByteArray {
            if (length < 0 || at < 0 || at + length > size) throw Malformed()
            val buffer = ByteBuffer.allocate(length)
            while (buffer.hasRemaining()) {
                if (channel.read(buffer, at + buffer.position()) < 0) throw Malformed()
            }
            return buffer.array()
        }

        fun digest(digest: MessageDigest, from: Long, to: Long) {
            val buffer = ByteBuffer.allocate(BUFFER_BYTES)
            var at = from
            while (at < to) {
                buffer.clear().limit(minOf(BUFFER_BYTES.toLong(), to - at).toInt())
                val read = channel.read(buffer, at)
                if (read < 0) throw Malformed()
                buffer.flip()
                digest.update(buffer)
                at += read
            }
        }

        fun hash(from: Long, to: Long): String {
            if (from >= to) throw Malformed()
            return sha256().also { digest(it, from, to) }.hex()
        }
    }

    /** The file is not a well-formed file of its format. */
    private class Malformed : Exception()

    private fun sha256(): MessageDigest = MessageDigest.getInstance("SHA-256")

    private fun MessageDigest.hex(): String = digest().joinToString("") { "%02x".format(it) }

    private val ID3_MAGIC = "ID3".encodeToByteArray()
    private val ID3V1_MAGIC = "TAG".encodeToByteArray()
    private val FLAC_MAGIC = "fLaC".encodeToByteArray()
    private val OGG_MAGIC = "OggS".encodeToByteArray()
    private val VORBIS_ID = byteArrayOf(1) + "vorbis".encodeToByteArray()
    private val OPUS_ID = "OpusHead".encodeToByteArray()
    private val APE_MAGIC = "APETAGEX".encodeToByteArray()
    private const val APE_FOOTER_BYTES: Long = 32
    private const val APE_HAS_HEADER: Int = 1 shl 31
    private const val APE_IS_HEADER: Int = 1 shl 29

    /** The smallest item: value size and flags, a 2-character key, its 0, an empty value. */
    private const val APE_MIN_ITEM_BYTES: Long = 11
    private const val APE_MAX_KEY: Int = 255
    private const val ID3V1_BYTES: Long = 128
    private const val STREAMINFO: Int = 0

    /** How far after the tag the first MPEG frame may start (some encoders leave a little junk). */
    private const val SYNC_WINDOW: Long = 64 * 1024
    private const val BUFFER_BYTES: Int = 64 * 1024
}
