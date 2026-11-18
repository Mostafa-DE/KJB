package app.lyrify.lyrics

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/** Lyrics a song file holds in its own tags ([EmbeddedLyrics]). Read when needed, never stored or logged. */
sealed interface TagLyrics {
    /** The words a line each, in sung order, without times: what the AI times, and what the views show as plain lyrics. */
    val words: String

    /** Timed lines, as LRC text. */
    data class Synced(val lrc: String) : TagLyrics {
        override val words: String get() = LrcFormat.parseLrclib(lrc).map { it.text }.filter { it.isNotBlank() }.joinToString("\n")
    }

    data class Plain(val text: String) : TagLyrics {
        override val words: String get() = text
    }
}

/**
 * The lyrics a song file holds in its own tags: an MP3's ID3v2 SYLT (synced) and USLT frames, the LYRICS and
 * UNSYNCEDLYRICS Vorbis comments of FLAC, Ogg Vorbis and Opus, and an M4A's `©lyr` item. Synced lyrics win (a SYLT
 * frame timed in milliseconds, or any of the others holding timed LRC lines), then plain ones, each in the order the
 * tag has them. Only the formats KJB knows, by the file's extension, as [app.lyrify.tags.PayloadHash] reads them.
 *
 * The file is opened read-only and never changed; only the tag's headers and the lyrics themselves are read, never a
 * cover. Null for no lyrics, a file that cannot be read, or one not well formed. Blocking.
 */
object EmbeddedLyrics {
    fun read(file: File): TagLyrics? = try {
        FileInputStream(file).use { input ->
            val reader = Reader(input.channel)
            val found = when (file.extension.lowercase()) {
                "mp3" -> id3(reader, 0)
                "flac" -> id3(reader, 0) + flac(reader)
                "m4a" -> m4a(reader)
                "ogg", "opus" -> ogg(reader)
                else -> emptyList()
            }
            found.firstOrNull { it is TagLyrics.Synced } ?: found.firstOrNull()
        }
    } catch (_: IOException) {
        null
    } catch (_: Malformed) {
        null
    }

    /** [text] as lyrics: synced when it has timed lines with words, plain otherwise; null when blank. */
    internal fun fromText(text: String): TagLyrics? {
        val tidy = text.replace("\r\n", "\n").replace('\r', '\n').trim { it.isWhitespace() || it == '\u0000' }
        return when {
            tidy.isEmpty() -> null
            LrcFormat.timed(tidy) -> TagLyrics.Synced(tidy)
            else -> TagLyrics.Plain(tidy)
        }
    }

    // ID3v2

    /** The lyrics frames of the ID3v2 tag at [at], in order; none without a tag there. */
    private fun id3(file: Reader, at: Long): List<TagLyrics> {
        if (file.size - at < ID3_HEADER) return emptyList()
        val header = file.read(at, ID3_HEADER)
        if (!header.startsWith(ID3_MAGIC)) return emptyList()
        val major = header[3].toInt()
        val size = syncsafe(header, 6)
        if (major !in 2..4 || size == null || at + ID3_HEADER + size > file.size) return emptyList()
        val flags = header[5].toInt()
        val wholeUnsync = flags and TAG_UNSYNC != 0
        // Before 2.4, unsynchronisation covers the whole tag, frame headers too: it is undone in memory first.
        if (wholeUnsync && major < 4) {
            if (size > MAX_UNSYNCED_TAG) return emptyList()
            val bytes = resync(file.read(at + ID3_HEADER, size.toInt()))
            return frames(ArraySource(bytes), 0, bytes.size.toLong(), major, flags)
        }
        return frames(file, at + ID3_HEADER, at + ID3_HEADER + size, major, flags)
    }

    /** The lyrics in the frames of [source] from [start] to [end]; reading stops at the padding or a frame that does not fit. */
    private fun frames(source: Source, start: Long, end: Long, major: Int, flags: Int): List<TagLyrics> {
        var at = start
        if (major >= 3 && flags and TAG_EXTENDED != 0 && end - at >= 4) {
            val extended = source.read(at, 4)
            at += if (major == 4) syncsafe(extended, 0) ?: return emptyList() else int32(extended, 0) + 4
        }
        val idLength = if (major == 2) 3 else 4
        val headerLength = if (major == 2) 6 else 10
        val found = ArrayList<TagLyrics>()
        while (at + headerLength <= end) {
            val head = source.read(at, headerLength)
            val id = String(head, 0, idLength, Charsets.ISO_8859_1)
            if (!id.all { it in 'A'..'Z' || it in '0'..'9' }) break
            val size = when (major) {
                2 -> int24(head, 3)
                // iTunes once wrote 2.4 sizes as plain numbers: a byte with its top bit set says so.
                4 -> syncsafe(head, 4) ?: int32(head, 4)
                else -> int32(head, 4)
            }
            val bodyAt = at + headerLength
            if (size < 0 || bodyAt + size > end) break
            val kind = LYRICS_FRAMES[id]
            if (kind != null && size <= MAX_FRAME_BYTES) {
                val format = if (major == 2) 0 else head[9].toInt() and 0xFF
                frameData(source.read(bodyAt, size.toInt()), major, format, wholeUnsync = flags and TAG_UNSYNC != 0)
                    ?.let { if (kind == USLT) uslt(it) else sylt(it) }
                    ?.let(found::add)
            }
            at = bodyAt + size
        }
        return found
    }

    /** A frame's data, its extra header bytes, unsynchronisation and compression undone; null when encrypted or broken. */
    private fun frameData(body: ByteArray, major: Int, format: Int, wholeUnsync: Boolean): ByteArray? {
        var offset = 0
        val compressed: Boolean
        var unsync = false
        when (major) {
            3 -> {
                compressed = format and 0x80 != 0
                if (compressed) offset += 4
                if (format and 0x40 != 0) return null
                if (format and 0x20 != 0) offset += 1
            }
            4 -> {
                compressed = format and 0x08 != 0
                if (format and 0x40 != 0) offset += 1
                if (format and 0x04 != 0) return null
                if (format and 0x01 != 0) offset += 4
                unsync = format and 0x02 != 0 || wholeUnsync
            }
            else -> compressed = false
        }
        if (offset > body.size) return null
        val data = body.copyOfRange(offset, body.size).let { if (unsync) resync(it) else it }
        return if (compressed) inflate(data) else data
    }

    /** USLT: encoding, language, a description, the text. */
    private fun uslt(data: ByteArray): TagLyrics? {
        if (data.size < 4) return null
        val strings = Strings(data[0].toInt(), data)
        strings.at = 4
        strings.next() ?: return null
        return fromText(strings.rest())
    }

    /**
     * SYLT: encoding, language, time format, content type, a description, then each piece of text with its time. Only
     * millisecond times are read (MPEG frame times need the stream's frame rate). Pieces are lines, unless some of them
     * start a new line ("\n" first): then they are words or syllables, joined into lines that start with such a piece.
     */
    private fun sylt(data: ByteArray): TagLyrics? {
        if (data.size < 6 || data[4].toInt() != SYLT_MILLISECONDS || data[5].toInt() !in SYLT_TEXT_TYPES) return null
        val strings = Strings(data[0].toInt(), data)
        strings.at = 6
        strings.next() ?: return null
        val pieces = ArrayList<Pair<String, Long>>()
        while (strings.at < data.size) {
            val text = strings.next() ?: break
            if (strings.at + 4 > data.size) break
            pieces += text to (int32(data, strings.at) and 0xFFFFFFFFL)
            strings.at += 4
        }
        val byWord = pieces.any { (text, _) -> text.startsWith('\n') || text.startsWith('\r') }
        val lines = ArrayList<LrcLine>()
        var line: StringBuilder? = null
        var lineAt = 0L
        for ((text, ms) in pieces) {
            if (!byWord || line == null || text.startsWith('\n') || text.startsWith('\r')) {
                line?.let { lines += LrcLine(lineAt, it.toString().trim()) }
                line = StringBuilder()
                lineAt = ms
            }
            line.append(if (byWord) text.trimStart('\n', '\r') else text.trim())
        }
        line?.let { lines += LrcLine(lineAt, it.toString().trim()) }
        if (lines.none { it.text.isNotBlank() }) return null
        return TagLyrics.Synced(LrcFormat.render(lines.sortedBy { it.timeMs }))
    }

    /** ID3v2 strings of one [encoding] read from [data] in turn: [next] up to its terminator, [rest] to the end. */
    private class Strings(private val encoding: Int, private val data: ByteArray) {
        var at: Int = 0

        /** UTF-16 without a byte order mark takes the last one seen. */
        private var littleEndian = true

        private val wide: Boolean get() = encoding == UTF16 || encoding == UTF16BE

        fun next(): String? {
            if (at > data.size) return null
            val end = terminator(at)
            val text = decode(data.copyOfRange(at, end))
            at = minOf(data.size, end + if (wide) 2 else 1)
            return text
        }

        fun rest(): String = decode(data.copyOfRange(minOf(at, data.size), data.size))

        private fun terminator(from: Int): Int {
            var i = from
            if (wide) {
                while (i + 1 < data.size) {
                    if (data[i].toInt() == 0 && data[i + 1].toInt() == 0) return i
                    i += 2
                }
                return data.size
            }
            while (i < data.size && data[i].toInt() != 0) i++
            return i
        }

        private fun decode(bytes: ByteArray): String = when (encoding) {
            LATIN1 -> String(bytes, Charsets.ISO_8859_1)
            UTF16 -> {
                val bom = bytes.size >= 2 && ((bytes[0] == FF && bytes[1] == FE) || (bytes[0] == FE && bytes[1] == FF))
                if (bom) littleEndian = bytes[0] == FF
                val text = if (bom) bytes.copyOfRange(2, bytes.size) else bytes
                String(text, if (littleEndian) Charsets.UTF_16LE else Charsets.UTF_16BE)
            }
            UTF16BE -> String(bytes, Charsets.UTF_16BE)
            else -> String(bytes, Charsets.UTF_8)
        }.trimEnd('\u0000')
    }

    // FLAC

    /** The lyrics in a FLAC file's comment block (after an ID3v2 tag before it, if any). */
    private fun flac(file: Reader): List<TagLyrics> {
        var at = id3End(file)
        if (file.size - at < 4 || !file.read(at, 4).contentEquals(FLAC_MAGIC)) return emptyList()
        at += 4
        while (file.size - at >= 4) {
            val head = file.read(at, 4)
            val length = int24(head, 1)
            if (head[0].toInt() and 0x7F == FLAC_COMMENT) {
                return if (length <= MAX_COMMENT_BYTES) vorbisComment(file.read(at + 4, length.toInt()), 0) else emptyList()
            }
            if (head[0].toInt() and 0x80 != 0) break
            at += 4 + length
        }
        return emptyList()
    }

    private fun id3End(file: Reader): Long {
        if (file.size < ID3_HEADER) return 0
        val header = file.read(0, ID3_HEADER)
        if (!header.startsWith(ID3_MAGIC)) return 0
        val size = syncsafe(header, 6) ?: return 0
        return ID3_HEADER + size + if (header[5].toInt() and ID3_FOOTER != 0) ID3_HEADER else 0
    }

    /** The lyrics fields ([LYRICS_FIELDS]) of the Vorbis comment at [from] in [data], in order. */
    private fun vorbisComment(data: ByteArray, from: Int): List<TagLyrics> {
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        /** A length, a count of bytes or of fields: each field is at least 4 bytes long. */
        fun number(unit: Int): Int {
            if (buffer.remaining() < 4) throw Malformed()
            val number = buffer.int
            if (number < 0 || number > buffer.remaining() / unit) throw Malformed()
            return number
        }
        buffer.position(from)
        val vendor = number(1)
        buffer.position(buffer.position() + vendor)
        val count = number(4)
        val found = ArrayList<TagLyrics>()
        repeat(count) {
            val field = ByteArray(number(1)).also(buffer::get).toString(Charsets.UTF_8)
            val name = field.substringBefore('=', missingDelimiterValue = "").uppercase()
            if (name in LYRICS_FIELDS) fromText(field.substringAfter('='))?.let(found::add)
        }
        return found
    }

    // Ogg

    /** The lyrics in an Ogg Vorbis or Opus stream's comment header, its second packet. */
    private fun ogg(file: Reader): List<TagLyrics> {
        var at = 0L
        var packet = 0
        var serial: Int? = null
        val current = ByteArrayOutputStream()
        while (file.size - at >= OGG_HEADER) {
            val head = ByteBuffer.wrap(file.read(at, OGG_HEADER)).order(ByteOrder.LITTLE_ENDIAN)
            if (!file.read(at, 4).contentEquals(OGG_MAGIC)) throw Malformed()
            val pageSerial = head.getInt(14)
            val lacing = file.read(at + OGG_HEADER, head.get(26).toInt() and 0xFF)
            var bodyAt = at + OGG_HEADER + lacing.size
            if (serial == null) serial = pageSerial
            for (value in lacing) {
                val length = value.toInt() and 0xFF
                if (pageSerial == serial) current.write(file.read(bodyAt, length))
                bodyAt += length
                if (pageSerial != serial || length == 255) continue
                if (packet == 1) return comments(current.toByteArray())
                packet++
                current.reset()
            }
            // Pictures kept as text make a comment header this big: not read further.
            if (current.size() > MAX_COMMENT_BYTES) return emptyList()
            at = bodyAt
        }
        return emptyList()
    }

    private fun comments(packet: ByteArray): List<TagLyrics> = when {
        packet.startsWith(VORBIS_COMMENT) -> vorbisComment(packet, VORBIS_COMMENT.size)
        packet.startsWith(OPUS_TAGS) -> vorbisComment(packet, OPUS_TAGS.size)
        else -> emptyList()
    }

    // M4A

    /** The `©lyr` item of an M4A's `moov/udta/meta/ilst`. */
    private fun m4a(file: Reader): List<TagLyrics> {
        val moov = child(file, 0, file.size, "moov") ?: return emptyList()
        val udta = child(file, moov.bodyStart, moov.end, "udta") ?: return emptyList()
        val meta = child(file, udta.bodyStart, udta.end, "meta") ?: return emptyList()
        // iTunes' meta is a full box (4 bytes of version and flags); QuickTime's is not.
        val children = if (meta.end - meta.bodyStart >= 4 && file.read(meta.bodyStart, 4).all { it.toInt() == 0 }) meta.bodyStart + 4 else meta.bodyStart
        val ilst = child(file, children, meta.end, "ilst") ?: return emptyList()
        val lyrics = child(file, ilst.bodyStart, ilst.end, LYRICS_ITEM) ?: return emptyList()
        val data = child(file, lyrics.bodyStart, lyrics.end, "data") ?: return emptyList()
        val length = data.end - data.bodyStart - 8
        if (length <= 0 || length > MAX_FRAME_BYTES) return emptyList()
        val type = int32(file.read(data.bodyStart, 4), 0) and 0xFFFFFFL
        val bytes = file.read(data.bodyStart + 8, length.toInt())
        val text = when (type) {
            MP4_UTF8 -> String(bytes, Charsets.UTF_8)
            MP4_UTF16 -> String(bytes, Charsets.UTF_16BE)
            else -> return emptyList()
        }
        return listOfNotNull(fromText(text))
    }

    private class Box(val bodyStart: Long, val end: Long)

    /** The first box of [type] from [from] to [to], one level; a size that runs past [to] makes the file malformed. */
    private fun child(file: Reader, from: Long, to: Long, type: String): Box? {
        var at = from
        while (to - at >= 8) {
            val header = file.read(at, 8)
            val size32 = int32(header, 0) and 0xFFFFFFFFL
            val (bodyStart, size) = when (size32) {
                0L -> at + 8 to to - at
                1L -> at + 16 to ByteBuffer.wrap(file.read(at + 8, 8)).long
                else -> at + 8 to size32
            }
            if (size < bodyStart - at || at + size > to) throw Malformed()
            if (String(header, 4, 4, Charsets.ISO_8859_1) == type) return Box(bodyStart, at + size)
            at += size
        }
        return null
    }

    // Bytes

    /** Positional reads, of a file or of bytes in memory; reading past the end makes the file malformed. */
    private interface Source {
        fun read(at: Long, length: Int): ByteArray
    }

    private class Reader(private val channel: FileChannel) : Source {
        val size: Long = channel.size()

        override fun read(at: Long, length: Int): ByteArray {
            if (length < 0 || at < 0 || at + length > size) throw Malformed()
            val buffer = ByteBuffer.allocate(length)
            while (buffer.hasRemaining()) {
                if (channel.read(buffer, at + buffer.position()) < 0) throw Malformed()
            }
            return buffer.array()
        }
    }

    private class ArraySource(private val bytes: ByteArray) : Source {
        override fun read(at: Long, length: Int): ByteArray {
            if (length < 0 || at < 0 || at + length > bytes.size) throw Malformed()
            return bytes.copyOfRange(at.toInt(), at.toInt() + length)
        }
    }

    /** The file is not a well-formed file of its format. */
    private class Malformed : Exception()

    /** [bytes] with ID3v2 unsynchronisation undone: each 0xFF 0x00 is 0xFF. */
    private fun resync(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(bytes.size)
        var i = 0
        while (i < bytes.size) {
            out.write(bytes[i].toInt())
            if (bytes[i] == FF && i + 1 < bytes.size && bytes[i + 1].toInt() == 0) i++
            i++
        }
        return out.toByteArray()
    }

    /** [data] inflated (zlib), at most [MAX_FRAME_BYTES]; null when broken or larger. */
    private fun inflate(data: ByteArray): ByteArray? {
        val inflater = Inflater()
        return try {
            inflater.setInput(data)
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(BUFFER_BYTES)
            while (!inflater.finished()) {
                val read = inflater.inflate(buffer)
                if (read == 0 && (inflater.needsInput() || inflater.needsDictionary())) return null
                out.write(buffer, 0, read)
                if (out.size() > MAX_FRAME_BYTES) return null
            }
            out.toByteArray()
        } catch (_: DataFormatException) {
            null
        } finally {
            inflater.end()
        }
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    /** A 28-bit syncsafe number at [at]; null when a byte has its top bit set (not syncsafe). */
    private fun syncsafe(bytes: ByteArray, at: Int): Long? =
        (at until at + 4).fold(0L) { sum, i -> if (bytes[i].toInt() and 0x80 != 0) return null else (sum shl 7) or bytes[i].toLong() }

    private fun int32(bytes: ByteArray, at: Int): Long = (at until at + 4).fold(0L) { sum, i -> (sum shl 8) or (bytes[i].toLong() and 0xFF) }

    private fun int24(bytes: ByteArray, at: Int): Long = (at until at + 3).fold(0L) { sum, i -> (sum shl 8) or (bytes[i].toLong() and 0xFF) }

    private const val ID3_HEADER: Int = 10
    private const val TAG_UNSYNC: Int = 0x80
    private const val TAG_EXTENDED: Int = 0x40
    private const val ID3_FOOTER: Int = 0x10
    private const val USLT: String = "USLT"
    private const val SYLT: String = "SYLT"

    /** The lyrics frames, 2.2's three-letter ids included, by the kind they are. */
    private val LYRICS_FRAMES: Map<String, String> = mapOf("USLT" to USLT, "ULT" to USLT, "SYLT" to SYLT, "SLT" to SYLT)

    private const val LATIN1: Int = 0
    private const val UTF16: Int = 1
    private const val UTF16BE: Int = 2
    private const val SYLT_MILLISECONDS: Int = 2

    /** SYLT content types that are words: other, lyrics, a transcription. */
    private val SYLT_TEXT_TYPES: IntRange = 0..2

    private val LYRICS_FIELDS: Set<String> = setOf("LYRICS", "UNSYNCEDLYRICS", "UNSYNCED LYRICS")
    private const val FLAC_COMMENT: Int = 4
    private const val OGG_HEADER: Int = 27
    private const val MP4_UTF8: Long = 1
    private const val MP4_UTF16: Long = 2
    private const val LYRICS_ITEM: String = "©lyr"

    /** Larger than any lyrics: a frame or item this big is skipped. */
    private const val MAX_FRAME_BYTES: Long = 512 * 1024

    /** A comment block or packet this big holds pictures as text: skipped. */
    private const val MAX_COMMENT_BYTES: Long = 4L * 1024 * 1024

    /** A tag unsynchronised as a whole is read into memory, up to this size. */
    private const val MAX_UNSYNCED_TAG: Long = 4L * 1024 * 1024
    private const val BUFFER_BYTES: Int = 8 * 1024
    private const val FF: Byte = 0xFF.toByte()
    private const val FE: Byte = 0xFE.toByte()
    private val ID3_MAGIC = "ID3".encodeToByteArray()
    private val FLAC_MAGIC = "fLaC".encodeToByteArray()
    private val OGG_MAGIC = "OggS".encodeToByteArray()
    private val VORBIS_COMMENT = byteArrayOf(3) + "vorbis".encodeToByteArray()
    private val OPUS_TAGS = "OpusTags".encodeToByteArray()
}
