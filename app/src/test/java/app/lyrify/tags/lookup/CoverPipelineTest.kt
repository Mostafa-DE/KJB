package app.lyrify.tags.lookup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverPipelineTest {
    /** Says [source] for any image, and makes "JPEGs" of the size [bytesAt] gives, baseline unless [progressive]. */
    private class FakeCodec(
        private val source: ImageSize?,
        private val progressive: Boolean = false,
        private val bytesAt: (side: Int, quality: Int) -> Int,
    ) : ImageCodec {
        val calls = mutableListOf<Pair<Int, Int>>()
        var sizeCalls = 0

        override fun size(image: ByteArray): ImageSize? {
            sizeCalls++
            return source
        }

        override fun jpeg(image: ByteArray, maxSide: Int, quality: Int): ByteArray {
            calls += maxSide to quality
            return fakeJpeg(bytesAt(maxSide, quality), progressive)
        }
    }

    private val kib = 1024

    @Test
    fun `a 1200 px cover becomes a 1000 px baseline JPEG at the best quality within about 300 KB`() {
        val codec = FakeCodec(ImageSize(1200, 900)) { _, quality -> if (quality >= 90) 350 * kib else 290 * kib }

        val cover = CoverPipeline(codec).prepare(JPEG_BYTES)!!

        assertEquals(listOf(1000 to 90, 1000 to 85), codec.calls)
        assertEquals(290 * kib, cover.jpeg.size)
        assertEquals(ImageSize(1000, 750), cover.size)
        assertTrue(Images.isBaselineJpeg(cover.jpeg))
        assertEquals(1000, CoverPipeline.MAX_SIDE)
        assertEquals(300 * kib, CoverPipeline.TARGET_BYTES)
    }

    @Test
    fun `a smaller cover is never enlarged`() {
        val codec = FakeCodec(ImageSize(500, 500)) { _, _ -> 80 * kib }
        val cover = CoverPipeline(codec).prepare(JPEG_BYTES)!!
        assertEquals(listOf(500 to 90), codec.calls)
        assertEquals(ImageSize(500, 500), cover.size)
    }

    @Test
    fun `too large at every quality, the side steps down`() {
        val codec = FakeCodec(ImageSize(1200, 1200)) { side, _ -> if (side > 800) 400 * kib else 200 * kib }
        val cover = CoverPipeline(codec).prepare(JPEG_BYTES)!!
        assertEquals(CoverPipeline.QUALITIES.map { 1000 to it } + (800 to 90), codec.calls)
        assertEquals(ImageSize(800, 800), cover.size)
    }

    @Test
    fun `a cover that never fits is left out`() {
        val codec = FakeCodec(ImageSize(1200, 1200)) { _, _ -> 400 * kib }
        assertNull(CoverPipeline(codec).prepare(JPEG_BYTES))
        assertEquals(CoverPipeline.SIDES.size * CoverPipeline.QUALITIES.size, codec.calls.size)
    }

    @Test
    fun `what is not a sane image is never decoded`() {
        val unreadable = FakeCodec(null) { _, _ -> 1 }
        assertNull(CoverPipeline(unreadable).prepare(JPEG_BYTES))
        assertEquals(emptyList<Pair<Int, Int>>(), unreadable.calls)

        val huge = FakeCodec(ImageSize(20_000, 600)) { _, _ -> 1 }
        assertNull(CoverPipeline(huge).prepare(JPEG_BYTES))
        assertEquals(emptyList<Pair<Int, Int>>(), huge.calls)

        val html = FakeCodec(ImageSize(500, 500)) { _, _ -> 1 }
        assertNull(CoverPipeline(html).prepare("<html>".encodeToByteArray()))
        assertEquals(0, html.sizeCalls)
    }

    @Test
    fun `an image over 2 MiB is not decoded`() {
        val codec = FakeCodec(ImageSize(1200, 1200)) { _, _ -> 1 }
        val big = JPEG_BYTES + ByteArray(Body.IMAGE.maxBytes)
        assertNull(CoverPipeline(codec).prepare(big))
        assertEquals(0, codec.sizeCalls)
    }

    @Test
    fun `a progressive JPEG from the encoder is refused`() {
        val codec = FakeCodec(ImageSize(1200, 1200), progressive = true) { _, _ -> 100 * kib }
        assertNull(CoverPipeline(codec).prepare(JPEG_BYTES))
    }

    // Opus

    @Test
    fun `an Opus comment header stays within 61,440 bytes or the cover is left out`() {
        // 4 (length) + 23 ("METADATA_BLOCK_PICTURE=") + base64 of the picture block (32 + "image/jpeg" + the JPEG).
        assertEquals(4 + 23 + 4 * ((32 + 10 + 45_000 + 2) / 3), OpusCover.commentBytes(45_000))
        assertEquals(60_083, OpusCover.commentBytes(45_000))
        assertTrue(OpusCover.fits(45_000, otherHeaderBytes = 1_357))
        assertFalse(OpusCover.fits(45_000, otherHeaderBytes = 1_358))
        assertFalse(OpusCover.fits(300 * kib, otherHeaderBytes = 100))
        assertEquals(61_440, OpusCover.COMMENT_LIMIT_BYTES)
    }

    // Image checks

    @Test
    fun `images are told by their first bytes`() {
        assertTrue(Images.isImage(JPEG_BYTES))
        assertTrue(Images.isImage(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0)))
        assertTrue(Images.isImage("GIF89a....".encodeToByteArray()))
        assertTrue(Images.isImage("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".encodeToByteArray()))
        assertFalse(Images.isImage("<html><body>moved</body></html>".encodeToByteArray()))
        assertFalse(Images.isImage(ByteArray(0)))
        assertFalse(Images.isImage(byteArrayOf(0xFF.toByte(), 0xD8.toByte())))
    }

    @Test
    fun `a baseline JPEG is told from a progressive one by its frame marker`() {
        assertTrue(Images.isBaselineJpeg(fakeJpeg(64, progressive = false)))
        assertFalse(Images.isBaselineJpeg(fakeJpeg(64, progressive = true)))
        // SOF1 (extended sequential) is not progressive either.
        assertTrue(Images.isBaselineJpeg(jpegSegments(0xC1)))
        // Scan data before any frame, a cut-off file, or not a JPEG at all.
        assertFalse(Images.isBaselineJpeg(jpegSegments(0xDA)))
        assertFalse(Images.isBaselineJpeg(fakeJpeg(64, progressive = false).copyOf(5)))
        assertFalse(Images.isBaselineJpeg("<html>".encodeToByteArray()))
    }
}

/** SOI, an APP0 segment, a DQT segment, then a segment with [marker]. */
private fun jpegSegments(marker: Int): ByteArray {
    val app0 = byteArrayOf(0xFF.toByte(), 0xE0.toByte(), 0, 16) + "JFIF\u0000".encodeToByteArray() + ByteArray(9)
    val dqt = byteArrayOf(0xFF.toByte(), 0xDB.toByte(), 0, 4, 0, 0)
    val frame = byteArrayOf(0xFF.toByte(), marker.toByte(), 0, 11, 8, 0, 1, 0, 1, 1, 1, 0x11, 0)
    return byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + app0 + dqt + frame
}

/** A "JPEG" of [size] bytes: real headers, a baseline or progressive frame, then zeros. */
private fun fakeJpeg(size: Int, progressive: Boolean): ByteArray {
    val head = jpegSegments(if (progressive) 0xC2 else 0xC0)
    return head + ByteArray((size - head.size).coerceAtLeast(0))
}
