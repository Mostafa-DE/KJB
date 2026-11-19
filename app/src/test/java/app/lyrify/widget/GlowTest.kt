package app.lyrify.widget

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.pow

/**
 * The Glow widget's background, made from the song's cover: cut to the layout's shape, blurred, darkened under a
 * brightness white text stays readable on, and its corners drawn in for Android 11; and the bitmaps' sizes, small enough
 * for what a widget update may carry. Pixels are ARGB ints, as Bitmap.getPixels gives them.
 */
class GlowTest {
    private fun argb(a: Int, r: Int, g: Int, b: Int): Int = (a shl 24) or (r shl 16) or (g shl 8) or b
    private fun rgb(r: Int, g: Int, b: Int): Int = argb(255, r, g, b)
    private fun alpha(pixel: Int): Int = pixel ushr 24
    private fun red(pixel: Int): Int = (pixel shr 16) and 0xFF
    private fun green(pixel: Int): Int = (pixel shr 8) and 0xFF
    private fun blue(pixel: Int): Int = pixel and 0xFF

    /** WCAG 2's relative luminance of an sRGB colour. */
    private fun luminance(pixel: Int): Double {
        fun linear(channel: Int): Double = (channel / 255.0).let { if (it <= 0.04045) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * linear(red(pixel)) + 0.7152 * linear(green(pixel)) + 0.0722 * linear(blue(pixel))
    }

    /** WCAG 2's contrast of white over [pixel]. */
    private fun whiteOn(pixel: Int): Double = 1.05 / (luminance(pixel) + 0.05)

    // The shape

    @Test
    fun `the cover is cut to the layout's shape from its middle`() {
        assertEquals(CropRect(0, 128, 512, 256), centerCrop(512, 512, aspect = 2f))
        assertEquals(CropRect(128, 0, 256, 512), centerCrop(512, 512, aspect = 0.5f))
        assertEquals(CropRect(0, 0, 300, 300), centerCrop(300, 300, aspect = 1f))
        // A wide cover for a tall layout keeps its full height.
        assertEquals(CropRect(150, 0, 300, 400), centerCrop(600, 400, aspect = 0.75f))
        // Never empty, however thin.
        val thin = centerCrop(512, 2, aspect = 0.01f)
        assertTrue(thin.width >= 1 && thin.height >= 1)
    }

    @Test
    fun `each layout's background is a small bitmap of its shape`() {
        for (layout in GlowLayout.entries) {
            val size = glowSize(layout)
            assertEquals(GLOW_WIDTH_PX, size.width)
            val shape = GLOW_SHAPE_WIDTH_DP.toFloat() / layout.shapeHeightDp
            assertTrue("$layout ${size.width}x${size.height}", abs(size.width.toFloat() / size.height - shape) < 0.05f)
        }
        assertTrue(glowSize(GlowLayout.SMALL).height < glowSize(GlowLayout.MEDIUM).height)
        assertTrue(glowSize(GlowLayout.MEDIUM).height < glowSize(GlowLayout.LARGE).height)
    }

    @Test
    fun `the sharp cover is read at the large slot's size on the phone's screen, at most MAX_COVER_PX`() {
        assertEquals(128, coverPx(density = 1f))
        assertEquals(256, coverPx(density = 2f))
        assertEquals(MAX_COVER_PX, coverPx(density = 3.5f))
        assertEquals(MAX_COVER_PX, coverPx(density = 4.5f))
    }

    @Test
    fun `what one widget update carries stays under a megabyte on any phone`() {
        // The three layouts' backgrounds and the one sharp cover they share, ARGB_8888.
        for (density in listOf(1f, 2f, 2.625f, 3f, 3.5f, 4f, 4.5f)) {
            val bytes = widgetBitmapBytes(density)
            assertTrue("$density: $bytes bytes", bytes <= 1_000_000)
        }
        assertEquals(GlowLayout.entries.sumOf { glowSize(it).let { size -> size.width * size.height } } * 4L + 128L * 128 * 4, widgetBitmapBytes(density = 1f))
    }

    // The blur

    @Test
    fun `an even colour stays as it is, and the cover's pixels are not changed`() {
        val even = IntArray(30 * 12) { rgb(90, 140, 200) }
        val before = even.copyOf()
        val blurred = boxBlur(even, 30, 12, radius = 4)
        assertArrayEquals(before, even)
        assertTrue(blurred.all { it == rgb(90, 140, 200) })
    }

    @Test
    fun `a bright point spreads evenly around itself, its light kept`() {
        val size = 31
        val point = IntArray(size * size) { rgb(0, 0, 0) }
        point[15 * size + 15] = rgb(255, 255, 255)
        val blurred = boxBlur(point, size, size, radius = 2)
        fun at(x: Int, y: Int) = red(blurred[y * size + x])
        assertTrue(at(15, 15) in 1..254)
        for (d in 1..5) {
            assertEquals(at(15 - d, 15), at(15 + d, 15))
            assertEquals(at(15, 15 - d), at(15, 15 + d))
            assertTrue(abs(at(15 - d, 15) - at(15, 15 - d)) <= 1)
        }
        // Three passes of 5 pixels reach 6 pixels each way, and no further.
        assertEquals(0, at(15 + 7, 15))
        assertEquals(0, at(0, 0))
        // Rounded once, at the end: the point's light is all there, spread out.
        val light = blurred.sumOf { red(it) }
        assertTrue("$light", abs(light - 255) <= 30)
        assertTrue(blurred.all { alpha(it) == 255 })
    }

    @Test
    fun `the edges blur with their own colour, not with black`() {
        // Left half red, right half blue: the far left column stays red, nothing darker comes in from outside.
        val width = 40
        val height = 10
        val halves = IntArray(width * height) { if (it % width < width / 2) rgb(220, 0, 0) else rgb(0, 0, 220) }
        val blurred = boxBlur(halves, width, height, radius = 3)
        for (y in 0 until height) assertEquals(rgb(220, 0, 0), blurred[y * width])
        for (y in 0 until height) assertEquals(rgb(0, 0, 220), blurred[y * width + width - 1])
    }

    @Test
    fun `a radius of 0 is a copy`() {
        val pixels = IntArray(6) { rgb(it * 40, 10, 20) }
        val copy = boxBlur(pixels, 3, 2, radius = 0)
        assertArrayEquals(pixels, copy)
        assertTrue(copy !== pixels)
    }

    // The colour

    @Test
    fun `a mid grey loses about 45 percent of its brightness, its alpha kept`() {
        assertEquals(rgb(55, 55, 55), glowColor(rgb(100, 100, 100)))
        assertEquals(argb(0x80, 55, 55, 55), glowColor(argb(0x80, 100, 100, 100)))
        assertEquals(rgb(0, 0, 0), glowColor(rgb(0, 0, 0)))
    }

    @Test
    fun `no channel gets brighter, and colours come out a little more vivid than darkening alone leaves them`() {
        val muted = rgb(160, 120, 100)
        val out = glowColor(muted)
        assertTrue(red(out) <= 160 && green(out) <= 120 && blue(out) <= 100)
        val spread = maxOf(red(out), green(out), blue(out)) - minOf(red(out), green(out), blue(out))
        assertTrue("$spread", spread > (160 - 100) * GLOW_BRIGHTNESS)
    }

    @Test
    fun `white text reads on the glow of any cover, white and yellow ones too`() {
        for (r in 0..255 step 15) for (g in 0..255 step 15) for (b in 0..255 step 15) {
            val pixel = glowColor(rgb(r, g, b))
            assertTrue("($r, $g, $b): ${whiteOn(pixel)}", whiteOn(pixel) >= 4.5)
        }
        for (bright in listOf(rgb(255, 255, 255), rgb(255, 255, 0), rgb(0, 255, 0), rgb(0, 255, 255), rgb(255, 200, 220))) {
            assertTrue(whiteOn(glowColor(bright)) >= 4.5)
            // Still a colour of its own, not black.
            assertTrue(luminance(glowColor(bright)) > 0.05)
        }
    }

    // The corners (Android 11, where the widget's own corner radius is not drawn)

    @Test
    fun `the corners are cut round and see-through, the rest kept`() {
        val width = 40
        val height = 20
        val pixels = IntArray(width * height) { rgb(30, 60, 90) }
        val round = roundCorners(pixels, width, height, radius = 8f)
        fun at(x: Int, y: Int) = round[y * width + x]
        for ((x, y) in listOf(0 to 0, width - 1 to 0, 0 to height - 1, width - 1 to height - 1)) assertEquals(0, alpha(at(x, y)))
        assertEquals(rgb(30, 60, 90), at(20, 10))
        assertEquals(rgb(30, 60, 90), at(8, 0))
        assertEquals(rgb(30, 60, 90), at(0, 8))
        // The arc fades over about a pixel, the same at every corner.
        assertTrue(alpha(at(2, 2)) in 1..254)
        assertEquals(alpha(at(2, 2)), alpha(at(width - 3, height - 3)))
        assertEquals(alpha(at(2, 2)), alpha(at(width - 3, 2)))
        // The colour stays, only its alpha changes.
        assertEquals(rgb(30, 60, 90) and 0xFFFFFF, at(2, 2) and 0xFFFFFF)
        assertArrayEquals(pixels, roundCorners(pixels, width, height, radius = 0f))
    }

    @Test
    fun `the whole glow is blurred and darkened, and round only when asked`() {
        val width = 24
        val height = 12
        val pixels = IntArray(width * height) { rgb(250, 250, 250) }
        val flat = glowPixels(pixels, width, height, cornerPx = 0f)
        assertTrue(flat.all { alpha(it) == 255 && whiteOn(it) >= 4.5 })
        val round = glowPixels(pixels, width, height, cornerPx = 5f)
        assertEquals(0, alpha(round[0]))
        assertEquals(flat[height / 2 * width + width / 2], round[height / 2 * width + width / 2])
    }
}
