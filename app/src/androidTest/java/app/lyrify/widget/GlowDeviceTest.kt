package app.lyrify.widget

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lyrify.library.SongRef
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.pow

/**
 * The Glow widget's pictures made from real bitmaps on the phone (debug build): each layout's background at its size,
 * dark enough for white words on a white cover, round at its corners only on Android 11; the sharp cover square at its
 * size; KJB's default cover's glow made once; and a cover's pictures kept for the song until its cover changes. Drawn
 * covers only: nothing on the phone is read.
 */
@RunWith(AndroidJUnit4::class)
class GlowDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun cover(width: Int, height: Int, color: Int): Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    /** WCAG 2's contrast of white over [pixel]. */
    private fun whiteOn(pixel: Int): Double {
        fun linear(channel: Int): Double = (channel / 255.0).let { if (it <= 0.04045) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
        val light = 0.2126 * linear(Color.red(pixel)) + 0.7152 * linear(Color.green(pixel)) + 0.0722 * linear(Color.blue(pixel))
        return 1.05 / (light + 0.05)
    }

    @Test
    fun eachLayoutsGlowHasItsSizeAndKeepsWhiteWordsReadable() {
        val white = cover(512, 512, Color.WHITE)
        for (layout in GlowLayout.entries) {
            val glow = glowBitmap(white, layout)
            val size = glowSize(layout)
            assertEquals(size.width, glow.width)
            assertEquals(size.height, glow.height)
            val middle = glow.getPixel(glow.width / 2, glow.height / 2)
            assertEquals(255, Color.alpha(middle))
            assertTrue("$layout: ${whiteOn(middle)}", whiteOn(middle) >= 4.5)
            // Android 11 draws no widget corners: the glow carries them. Later versions clip it themselves.
            assertEquals(if (homeRoundsCorners) 255 else 0, Color.alpha(glow.getPixel(0, 0)))
        }
    }

    @Test
    fun theSharpCoverIsSquareFromItsMiddleAtItsSize() {
        val wide = cover(600, 400, Color.rgb(40, 90, 160))
        val sharp = sharpCover(wide, 256)
        assertEquals(256, sharp.width)
        assertEquals(256, sharp.height)
        assertEquals(Color.rgb(40, 90, 160), sharp.getPixel(128, 128))
        // A small cover is not scaled up.
        assertEquals(100, sharpCover(cover(100, 100, Color.BLACK), 256).width)
    }

    @Test
    fun theDefaultCoversGlowIsMadeOnceForEveryLayout() {
        val first = defaultGlow(context)
        assertEquals(GlowLayout.entries.toSet(), first.keys)
        assertSame(first, defaultGlow(context))
    }

    @Test
    fun aCoversPicturesStayForTheSongUntilItsCoverChanges() = runBlocking {
        val art = cover(300, 300, Color.rgb(200, 60, 40))
        val own = CoverRead.Own(sharpCover(art, 128), GlowLayout.entries.associateWith { glowBitmap(art, it) })
        val song = WidgetState.Loaded(
            song = SongRef("/storage/emulated/0/Music/Harbour Glass/Paper Lantern.flac", mediaStoreId = 41),
            title = "Paper Lantern",
            artist = "Kit Calloway",
            cover = "41",
            playing = true,
            favourite = false,
        )
        var reads = 0
        val read: suspend (String) -> CoverRead? = { reads++; own }
        val shown = Widgets.withCover(song, Shown(WidgetState.Empty), now = 1_000, read)
        assertSame(own.cover, shown.cover)
        assertSame(own.glow, shown.glow)
        // Pause: the same pictures, nothing read.
        val paused = Widgets.withCover(song.copy(playing = false), shown, now = 2_000, read)
        assertSame(own.glow, paused.glow)
        assertEquals(1, reads)
        // Covers & tags changed the cover: read again.
        Widgets.withCover(song.copy(coverVersion = 1), paused, now = 3_000, read)
        assertEquals(2, reads)
    }
}
