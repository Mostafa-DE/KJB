package app.lyrify.tones

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos

/** The fade curves (the cutter's choice; tones keep the straight line) and the envelope along them. */
class FadeCurveTest {
    @Test
    fun `every curve runs from silence to full volume, never backwards, clamped outside`() {
        FadeCurve.entries.forEach { curve ->
            assertEquals("$curve", 0.0, curve.gain(0.0), 0.0)
            assertEquals("$curve", 1.0, curve.gain(1.0), 1e-12)
            assertEquals("$curve", 0.0, curve.gain(-0.5), 0.0)
            assertEquals("$curve", 1.0, curve.gain(1.5), 1e-12)
            (1..100).forEach { i -> assertTrue("$curve $i", curve.gain(i / 100.0) >= curve.gain((i - 1) / 100.0)) }
        }
    }

    @Test
    fun `linear is a straight line, smooth an S of half a cosine, even a straight line squared`() {
        assertEquals(0.25, FadeCurve.LINEAR.gain(0.25), 1e-12)
        assertEquals((1 - cos(PI / 4)) / 2, FadeCurve.SMOOTH.gain(0.25), 1e-12)
        assertEquals(0.5, FadeCurve.SMOOTH.gain(0.5), 1e-12)
        assertEquals(0.0625, FadeCurve.EVEN.gain(0.25), 1e-12)
        assertEquals(0.25, FadeCurve.EVEN.gain(0.5), 1e-12)
        // Smooth leaves silence and arrives at full volume gently: no corner at either end.
        assertTrue(FadeCurve.SMOOTH.gain(0.01) < 0.001)
        assertTrue(FadeCurve.SMOOTH.gain(0.99) > 0.999)
    }

    @Test
    fun `a fade in follows its curve from the first frame`() {
        val fade = FadeEnvelope(totalFrames = 1_000, fadeInFrames = 100, fadeOutFrames = 0, inCurve = FadeCurve.SMOOTH)
        assertEquals(0f, fade.gain(0), 0f)
        assertEquals(FadeCurve.SMOOTH.gain(0.25).toFloat(), fade.gain(25), 1e-6f)
        assertEquals(0.5f, fade.gain(50), 1e-6f)
        assertEquals(1f, fade.gain(100), 0f)
        assertEquals(1f, fade.gain(999), 0f)
    }

    @Test
    fun `a fade out follows its own curve backwards from the last frame`() {
        val fade = FadeEnvelope(totalFrames = 1_000, fadeInFrames = 100, fadeOutFrames = 100, inCurve = FadeCurve.SMOOTH, outCurve = FadeCurve.EVEN)
        assertEquals(1f, fade.gain(899), 0f)
        assertEquals(0.25f, fade.gain(949), 1e-6f)
        assertEquals(0f, fade.gain(999), 0f)
        // The fade in kept its own curve.
        assertEquals(FadeCurve.SMOOTH.gain(0.25).toFloat(), fade.gain(25), 1e-6f)
    }

    @Test
    fun `curved fades longer than a short window still meet in the middle, never louder than either`() {
        val fade = FadeEnvelope(totalFrames = 101, fadeInFrames = 100, fadeOutFrames = 100, inCurve = FadeCurve.EVEN, outCurve = FadeCurve.SMOOTH)
        (0 until 101L).forEach { f ->
            val limit = minOf(FadeCurve.EVEN.gain(f / 100.0), FadeCurve.SMOOTH.gain((100 - f) / 100.0))
            assertEquals("$f", limit.toFloat(), fade.gain(f), 1e-6f)
        }
    }

    @Test
    fun `the envelope and the preview take the curves along with the milliseconds`() {
        val window = ToneWindow(10_000, 30_000)
        val fade = FadeEnvelope.of(window, fadeInMs = 1_000, fadeOutMs = 2_000, inCurve = FadeCurve.EVEN, outCurve = FadeCurve.SMOOTH)
        assertEquals(FadeCurve.EVEN, fade.inCurve)
        assertEquals(FadeCurve.SMOOTH, fade.outCurve)
        assertEquals(0.25f, previewGain(500, window, 1_000, 2_000, FadeCurve.EVEN, FadeCurve.SMOOTH), 1e-3f)
        assertEquals(0.5f, previewGain(29_000, window, 1_000, 2_000, FadeCurve.EVEN, FadeCurve.SMOOTH), 1e-3f)
        val preview = PreviewSource.Window("/m/Paper Lanterns.mp3", window, 1_000, 2_000, FadeCurve.EVEN, FadeCurve.SMOOTH)
        assertEquals(0.25f, preview.gainAt(500), 1e-3f)
        // Tones keep the straight line.
        assertEquals(0.5f, previewGain(500, window, 1_000, 0), 1e-3f)
    }
}
