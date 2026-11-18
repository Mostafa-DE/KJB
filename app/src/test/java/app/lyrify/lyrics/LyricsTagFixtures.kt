package app.lyrify.lyrics

import app.lyrify.tags.TagFixtures
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Song files holding lyrics in their own tags, built in code: ID3v2 USLT and SYLT frames, Vorbis comments (FLAC, Ogg)
 * and an M4A `©lyr` item, around [TagFixtures]' made-up audio. Made-up words only.
 */
object LyricsTagFixtures {
    /** ID3v2 text encodings. */
    const val LATIN1: Int = 0
    const val UTF16: Int = 1
    const val UTF16BE: Int = 2
    const val UTF8: Int = 3

    /** One ID3v2 frame: its id, its size (syncsafe in 2.4, a plain number in 2.3), [flags] and [body]. */
    fun frame(id: String, body: ByteArray, version: Int = 4, flags: Int = 0): ByteArray {
        val size = if (version == 4) syncsafe(body.size) else ByteBuffer.allocate(4).putInt(body.size).array()
        return id.toByteArray(Charsets.ISO_8859_1) + size + byteArrayOf((flags shr 8).toByte(), flags.toByte()) + body
    }

    /** An ID3v2.2 frame: a three-letter id and a three-byte size. */
    fun frame22(id: String, body: ByteArray): ByteArray =
        id.toByteArray(Charsets.ISO_8859_1) + byteArrayOf((body.size shr 16).toByte(), (body.size shr 8).toByte(), body.size.toByte()) + body

    /** A USLT body: [encoding], a language, an empty description, then [text]. */
    fun uslt(text: String, encoding: Int = UTF8, description: String = ""): ByteArray =
        byteArrayOf(encoding.toByte()) + "eng".encodeToByteArray() + string(description, encoding, terminated = true) + string(text, encoding, terminated = false)

    /**
     * A SYLT body: [encoding], a language, the time format (2 for milliseconds, 1 for MPEG frames), content type 1
     * (lyrics), an empty description, then each line's text and time.
     */
    fun sylt(lines: List<Pair<Long, String>>, encoding: Int = UTF8, format: Int = 2, contentType: Int = 1): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(encoding.toByte()) + "eng".encodeToByteArray() + byteArrayOf(format.toByte(), contentType.toByte()))
        out.write(string("", encoding, terminated = true))
        lines.forEach { (ms, text) ->
            out.write(string(text, encoding, terminated = true))
            out.write(ByteBuffer.allocate(4).putInt(ms.toInt()).array())
        }
        return out.toByteArray()
    }

    /** An MP3: an ID3v2 tag of [version] holding [frames], with [padding], then made-up audio. */
    fun mp3(vararg frames: ByteArray, version: Int = 4, padding: Int = 32): ByteArray =
        TagFixtures.mp3(tag = TagFixtures.id3v2(frames.fold(ByteArray(0)) { all, frame -> all + frame }, version, padding))

    /** A Vorbis comment: a vendor and `NAME=value` [fields], lengths little-endian. */
    fun vorbisComment(vararg fields: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        fun field(bytes: ByteArray) {
            out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(bytes.size).array())
            out.write(bytes)
        }
        field("made-up encoder".encodeToByteArray())
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(fields.size).array())
        fields.forEach { (name, value) -> field("$name=$value".encodeToByteArray()) }
        return out.toByteArray()
    }

    fun flac(vararg fields: Pair<String, String>): ByteArray = TagFixtures.flac(comment = vorbisComment(*fields))

    /** An Ogg Vorbis file whose comment header holds [fields] (and its framing bit). */
    fun oggVorbis(vararg fields: Pair<String, String>): ByteArray =
        TagFixtures.ogg(TagFixtures.vorbisPackets(comment = vorbisComment(*fields) + byteArrayOf(1)), headers = 3)

    /** An Ogg Opus file whose OpusTags hold [fields]. */
    fun opus(vararg fields: Pair<String, String>): ByteArray =
        TagFixtures.ogg(TagFixtures.opusPackets(comment = vorbisComment(*fields)), headers = 2)

    /**
     * An M4A file: ftyp, mdat, then moov (as many encoders leave it at the end) whose `ilst` holds a `©nam` item and a
     * `©lyr` item of [lyrics] (type 1, UTF-8).
     */
    fun m4a(lyrics: String): ByteArray {
        fun data(text: String) = TagFixtures.box("data", ByteBuffer.allocate(8).putInt(1).putInt(0).array(), text.encodeToByteArray())
        val ilst = TagFixtures.box("ilst", TagFixtures.box("©nam", data("Made-up Title")), TagFixtures.box("©lyr", data(lyrics)))
        val udta = TagFixtures.box("udta", TagFixtures.box("meta", ByteArray(4), TagFixtures.box("hdlr", ByteArray(25)), ilst))
        val ftyp = TagFixtures.box("ftyp", "M4A ".encodeToByteArray(), ByteArray(4), "isomM4A ".encodeToByteArray())
        return ftyp + TagFixtures.box("mdat", ByteArray(2_000) { 3 }) + TagFixtures.box("moov", TagFixtures.box("mvhd", ByteArray(100)), udta)
    }

    /** [text] in ID3v2 [encoding] (UTF-16 with a byte order mark), with its terminator when [terminated]. */
    fun string(text: String, encoding: Int, terminated: Boolean): ByteArray {
        val bytes = when (encoding) {
            LATIN1 -> text.toByteArray(Charsets.ISO_8859_1)
            UTF16 -> byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)
            UTF16BE -> text.toByteArray(Charsets.UTF_16BE)
            else -> text.toByteArray(Charsets.UTF_8)
        }
        val wide = encoding == UTF16 || encoding == UTF16BE
        return if (terminated) bytes + ByteArray(if (wide) 2 else 1) else bytes
    }

    fun syncsafe(size: Int): ByteArray =
        byteArrayOf((size shr 21 and 0x7F).toByte(), (size shr 14 and 0x7F).toByte(), (size shr 7 and 0x7F).toByte(), (size and 0x7F).toByte())
}
