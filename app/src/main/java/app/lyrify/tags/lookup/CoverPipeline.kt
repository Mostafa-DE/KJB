package app.lyrify.tags.lookup

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import androidx.core.graphics.createBitmap
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/** The Cover Art Archive's ready-made sizes, larger first: Covers & tags never fetches the original (it can be 30 MB). */
enum class CoverSize(val path: String) {
    FRONT_1200("front-1200"),
    FRONT_500("front-500"),
}

data class ImageSize(val width: Int, val height: Int)

/** The image library behind [CoverPipeline]: Android's in the app ([AndroidImageCodec]), a fake in tests. */
interface ImageCodec {
    /** The size of [image] read from its header, without decoding it; null when it is not an image. */
    fun size(image: ByteArray): ImageSize?

    /** [image] scaled so its longer side is at most [maxSide] px, as a baseline JPEG at [quality]; null on failure. */
    fun jpeg(image: ByteArray, maxSide: Int, quality: Int): ByteArray?
}

/** A cover ready to embed: a baseline JPEG of at most [CoverPipeline.MAX_SIDE] px and about [CoverPipeline.TARGET_BYTES]. */
class Cover(val jpeg: ByteArray, val size: ImageSize)

/**
 * Turns a Cover Art Archive image into the cover written into songs: decoded only when its header is sane, re-encoded
 * as a baseline JPEG with its longer side at most [MAX_SIDE] px (never enlarged), at the best quality that keeps it
 * within [TARGET_BYTES].
 */
class CoverPipeline(private val codec: ImageCodec) {
    /** The cover made from [image], or null when it is not a usable image. */
    fun prepare(image: ByteArray): Cover? {
        if (image.size > Body.IMAGE.maxBytes || !Images.isImage(image)) return null
        val source = codec.size(image) ?: return null
        val longer = maxOf(source.width, source.height)
        if (minOf(source.width, source.height) <= 0 || longer > MAX_SOURCE_SIDE) return null
        for (side in SIDES.map { minOf(it, longer) }.distinct()) {
            for (quality in QUALITIES) {
                val jpeg = codec.jpeg(image, side, quality) ?: return null
                if (!Images.isBaselineJpeg(jpeg)) return null
                if (jpeg.size <= TARGET_BYTES) return Cover(jpeg, scaled(source, side))
            }
        }
        return null
    }

    private fun scaled(source: ImageSize, side: Int): ImageSize {
        val longer = maxOf(source.width, source.height)
        if (longer <= side) return source
        return ImageSize((source.width.toLong() * side / longer).toInt(), (source.height.toLong() * side / longer).toInt())
    }

    companion object {
        const val MAX_SIDE: Int = 1000
        const val TARGET_BYTES: Int = 300 * 1024

        /** Tried in order at each side; the first that fits wins. */
        val QUALITIES: List<Int> = listOf(90, 85, 80, 75, 70)

        /** Sides tried in order when even the lowest quality is too large. */
        val SIDES: List<Int> = listOf(MAX_SIDE, 800, 600)

        /** An image whose header claims more than this per side is not decoded. */
        const val MAX_SOURCE_SIDE: Int = 10_000
    }
}

/**
 * Android's decoder and JPEG encoder. Decodes at the nearest power-of-two step above the size wanted, draws onto white
 * (JPEG has no transparency, so a transparent PNG would turn black), and encodes baseline JPEG ([CoverPipeline] checks).
 */
object AndroidImageCodec : ImageCodec {
    override fun size(image: ByteArray): ImageSize? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(image, 0, image.size, bounds)
        return if (bounds.outWidth > 0 && bounds.outHeight > 0) ImageSize(bounds.outWidth, bounds.outHeight) else null
    }

    override fun jpeg(image: ByteArray, maxSide: Int, quality: Int): ByteArray? {
        val source = size(image) ?: return null
        val longer = maxOf(source.width, source.height)
        var sample = 1
        while (longer / (sample * 2) >= maxSide) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(image, 0, image.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val scale = minOf(1f, maxSide.toFloat() / maxOf(decoded.width, decoded.height))
        val width = (decoded.width * scale).roundToInt().coerceAtLeast(1)
        val height = (decoded.height * scale).roundToInt().coerceAtLeast(1)
        val opaque = createBitmap(width, height)
        return try {
            Canvas(opaque).apply {
                drawColor(Color.WHITE)
                drawBitmap(decoded, null, Rect(0, 0, width, height), Paint(Paint.FILTER_BITMAP_FLAG))
            }
            ByteArrayOutputStream().also { opaque.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
        } finally {
            decoded.recycle()
            opaque.recycle()
        }
    }
}

/**
 * Opus keeps the cover in its comment header as a base64 METADATA_BLOCK_PICTURE, and RFC 7845 lets readers ignore any
 * comment past the first 61,440 bytes of that header. So the cover is left out of an Opus song when the header would
 * pass that, and the album sheet says so.
 */
object OpusCover {
    const val COMMENT_LIMIT_BYTES: Int = 61_440

    private const val FIELD = "METADATA_BLOCK_PICTURE="
    private const val MIME = "image/jpeg"

    /** The FLAC picture block's fixed fields: type, MIME length, description length, width, height, depth, colours, data length. */
    private const val PICTURE_FIELDS_BYTES = 32
    private const val LENGTH_BYTES = 4

    /** Bytes the cover's comment adds to the header: its length field, the name and the base64 picture block. */
    fun commentBytes(jpegBytes: Int): Int {
        val block = PICTURE_FIELDS_BYTES + MIME.length + jpegBytes
        return LENGTH_BYTES + FIELD.length + 4 * ((block + 2) / 3)
    }

    /** The cover fits beside the header's other [otherHeaderBytes] (magic, vendor, count and every other comment). */
    fun fits(jpegBytes: Int, otherHeaderBytes: Int): Boolean = otherHeaderBytes.toLong() + commentBytes(jpegBytes) <= COMMENT_LIMIT_BYTES
}

/** Image checks that need no decoder. */
internal object Images {
    private val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    /** JPEG, PNG, GIF or WebP by its first bytes: what the Cover Art Archive serves. */
    fun isImage(bytes: ByteArray): Boolean = when {
        bytes.startsWith(0xFF, 0xD8, 0xFF) -> true
        bytes.size >= PNG.size && bytes.copyOf(PNG.size).contentEquals(PNG) -> true
        bytes.ascii(0, 6) == "GIF87a" || bytes.ascii(0, 6) == "GIF89a" -> true
        else -> bytes.ascii(0, 4) == "RIFF" && bytes.ascii(8, 4) == "WEBP"
    }

    /**
     * A JPEG whose frame is baseline (SOF0) or extended sequential (SOF1), never progressive (SOF2) or another kind: some
     * players show only those. Walks the segments up to the frame header.
     */
    fun isBaselineJpeg(bytes: ByteArray): Boolean {
        if (!bytes.startsWith(0xFF, 0xD8)) return false
        var at = 2
        while (at + 3 < bytes.size) {
            if (bytes.u(at) != 0xFF) return false
            val marker = bytes.u(at + 1)
            when (marker) {
                0xFF -> {
                    at++
                    continue
                }
                SOF0, SOF1 -> return true
                in OTHER_FRAMES, SOS, EOI -> return false
            }
            val length = (bytes.u(at + 2) shl 8) or bytes.u(at + 3)
            if (length < 2) return false
            at += 2 + length
        }
        return false
    }

    private const val SOF0 = 0xC0
    private const val SOF1 = 0xC1
    private const val SOS = 0xDA
    private const val EOI = 0xD9

    /** Every other start-of-frame marker: progressive, lossless, arithmetic-coded. 0xC4, 0xC8 and 0xCC are not frames. */
    private val OTHER_FRAMES = (0xC2..0xCF).toSet() - setOf(0xC4, 0xC8, 0xCC)

    private fun ByteArray.u(index: Int): Int = this[index].toInt() and 0xFF

    private fun ByteArray.startsWith(vararg values: Int): Boolean = size >= values.size && values.indices.all { u(it) == values[it] }

    private fun ByteArray.ascii(from: Int, count: Int): String? =
        if (size >= from + count) String(this, from, count, Charsets.ISO_8859_1) else null
}
