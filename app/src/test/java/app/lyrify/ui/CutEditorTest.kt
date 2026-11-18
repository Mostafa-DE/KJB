package app.lyrify.ui

import androidx.compose.runtime.saveable.SaverScope
import app.lyrify.R
import app.lyrify.cut.CutDraft
import app.lyrify.cut.CutEnd
import app.lyrify.cut.CutFailure
import app.lyrify.cut.CutQuality
import app.lyrify.cut.Fade
import app.lyrify.tones.FadeCurve
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/** The cutter screen's logic: which end a finger takes, the fade labels, the messages, a turn of the phone. */
class CutEditorTest {
    private val slop = 30f

    @Test
    fun `a finger near an end takes that end, the nearer one when both are near`() {
        assertEquals(CutGrab.START, cutGrab(x = 110f, startX = 100f, endX = 400f, slop = slop))
        assertEquals(CutGrab.END, cutGrab(x = 385f, startX = 100f, endX = 400f, slop = slop))
        // A short cut: both ends are near the finger.
        assertEquals(CutGrab.START, cutGrab(x = 104f, startX = 100f, endX = 120f, slop = slop))
        assertEquals(CutGrab.END, cutGrab(x = 117f, startX = 100f, endX = 120f, slop = slop))
        // Just outside a short cut, each side takes its own end.
        assertEquals(CutGrab.START, cutGrab(x = 95f, startX = 100f, endX = 100f, slop = slop))
        assertEquals(CutGrab.END, cutGrab(x = 105f, startX = 100f, endX = 100f, slop = slop))
    }

    @Test
    fun `a finger inside the cut away from its ends moves the whole cut, outside it takes the nearer end`() {
        assertEquals(CutGrab.WHOLE, cutGrab(x = 250f, startX = 100f, endX = 400f, slop = slop))
        assertEquals(CutGrab.START, cutGrab(x = 20f, startX = 100f, endX = 400f, slop = slop))
        assertEquals(CutGrab.END, cutGrab(x = 600f, startX = 100f, endX = 400f, slop = slop))
    }

    @Test
    fun `fade lengths read as seconds, with a half where there is one`() {
        assertEquals("0.5", fadeSeconds(500, Locale.US))
        assertEquals("1", fadeSeconds(1_000, Locale.US))
        assertEquals("10", fadeSeconds(10_000, Locale.US))
        assertEquals("0,5", fadeSeconds(500, Locale.GERMANY))
    }

    @Test
    fun `each curve and each failure has its words`() {
        assertEquals(listOf(R.string.cut_curve_linear, R.string.cut_curve_smooth, R.string.cut_curve_even), FadeCurve.entries.map(::curveLabel))
        assertEquals(R.string.tones_song_unreadable, cutFailureMessage(CutFailure.SONG_UNREADABLE))
        assertEquals(R.string.cut_nowhere, cutFailureMessage(CutFailure.NOWHERE))
        assertEquals(R.string.cut_not_saved, cutFailureMessage(CutFailure.NOT_SAVED))
    }

    @Test
    fun `a turn of the phone keeps the draft`() {
        val draft = CutDraft(songDurationMs = 201_000)
            .withStart(12_340)
            .withEnd(84_500)
            .withFadeIn(Fade(2_000, FadeCurve.EVEN))
            .withFadeOut(Fade(5_000, FadeCurve.LINEAR))
            .copy(quality = CutQuality.KBPS_320)
            .heard(CutEnd.END)
        val saved = requireNotNull(with(CutDraftSaver) { SaverScope { true }.save(draft) })
        assertEquals(draft, CutDraftSaver.restore(saved))
    }
}
