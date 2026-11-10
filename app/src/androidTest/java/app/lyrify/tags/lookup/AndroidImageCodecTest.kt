package app.lyrify.tags.lookup

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/** Android's decoder and encoder behind the cover pipeline, on images made here: nothing leaves the app. */
@RunWith(AndroidJUnit4::class)
class AndroidImageCodecTest {
    private fun png(width: Int, height: Int, color: (Int, Int) -> Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (x in 0 until width) for (y in 0 until height) bitmap.setPixel(x, y, color(x, y))
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray().also { bitmap.recycle() }
    }

    @Test
    fun aLargeCoverBecomesA1000PxBaselineJpegWithinTarget() {
        // A picture with smooth areas, as covers are: it fits at the largest side.
        val image = png(1200, 960) { x, y -> Color.rgb(x * 255 / 1200, y * 255 / 960, (x + y) / 8 % 256) }

        val cover = runBlocking { CoverPipeline(AndroidImageCodec).prepare(image) }

        assertNotNull(cover)
        assertTrue(Images.isBaselineJpeg(cover!!.jpeg))
        assertTrue("${cover.jpeg.size} bytes", cover.jpeg.size <= CoverPipeline.TARGET_BYTES)
        val decoded = BitmapFactory.decodeByteArray(cover.jpeg, 0, cover.jpeg.size)
        assertEquals(cover.size, ImageSize(decoded.width, decoded.height))
        assertEquals(CoverPipeline.MAX_SIDE, maxOf(decoded.width, decoded.height))
    }

    @Test
    fun aCoverStillTooLargeAt1000PxStepsDownToASmallerSideWithinTarget() {
        // Noise compresses badly: on the phone even the lowest quality at 1000 px is over the target (about 288 KB with
        // desktop libjpeg, over 300 KB with Android's encoder), so the pipeline takes the next side, as designed.
        val image = png(1200, 960) { x, y -> Color.rgb((x * 7 + y * 13) % 256, (x * y) % 256, (x xor y) % 256) }
        val lowestAtMax = AndroidImageCodec.jpeg(image, CoverPipeline.MAX_SIDE, CoverPipeline.QUALITIES.last())!!

        val cover = runBlocking { CoverPipeline(AndroidImageCodec).prepare(image) }!!

        assertTrue(Images.isBaselineJpeg(cover.jpeg))
        assertTrue("${cover.jpeg.size} bytes", cover.jpeg.size <= CoverPipeline.TARGET_BYTES)
        val side = maxOf(cover.size.width, cover.size.height)
        assertTrue("$side px", side in CoverPipeline.SIDES)
        if (lowestAtMax.size > CoverPipeline.TARGET_BYTES) assertTrue("$side px", side < CoverPipeline.MAX_SIDE) else assertEquals(CoverPipeline.MAX_SIDE, side)
    }

    @Test
    fun aTransparentCoverTurnsWhiteNotBlack() {
        val image = png(600, 600) { _, _ -> Color.TRANSPARENT }

        val cover = runBlocking { CoverPipeline(AndroidImageCodec).prepare(image) }!!

        val pixel = BitmapFactory.decodeByteArray(cover.jpeg, 0, cover.jpeg.size).getPixel(300, 300)
        assertTrue(Integer.toHexString(pixel), Color.red(pixel) > 240 && Color.green(pixel) > 240 && Color.blue(pixel) > 240)
        assertEquals(ImageSize(600, 600), cover.size)
    }

    @Test
    fun whatIsNotAnImageIsRefused() {
        assertNull(AndroidImageCodec.size("<html>moved</html>".encodeToByteArray()))
        assertNull(runBlocking { CoverPipeline(AndroidImageCodec).prepare("<html>moved</html>".encodeToByteArray()) })
    }
}
