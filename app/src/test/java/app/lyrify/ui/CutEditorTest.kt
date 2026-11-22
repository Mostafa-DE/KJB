package app.lyrify.ui

import androidx.compose.runtime.saveable.SaverScope
import app.lyrify.R
import app.lyrify.cut.CutDraft
import app.lyrify.cut.CutEnd
import app.lyrify.cut.CutFailure
import app.lyrify.cut.CutQuality
import app.lyrify.cut.CutResult
import app.lyrify.cut.Fade
import app.lyrify.tones.FadeCurve
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/** The cutter screen's logic: which end a finger takes, the fade labels, the messages, a turn of the phone. */
class CutEditorTest {
    private val slop = 30f

    @Test
    fun `a finger near an end takes that end, the nearer one when both are near`() {
        assertEquals(CutGrab.START, cutGrab(x = 110f, startX = 100f, endX = 400f, slop = slop))
        assertEquals(CutGrab.END, cutGrab(x = 385f, startX = 100f, endX = 400f, slop = slop))
        // Ends that meet: each side takes its own end.
        assertEquals(CutGrab.START, cutGrab(x = 95f, startX = 100f, endX = 100f, slop = slop))
        assertEquals(CutGrab.END, cutGrab(x = 105f, startX = 100f, endX = 100f, slop = slop))
    }

    @Test
    fun `a cut too narrow for a middle still slides from inside, and its ends are taken from outside`() {
        // 20 px wide, under two slops: a finger between the ends moves the whole cut.
        assertEquals(CutGrab.WHOLE, cutGrab(x = 104f, startX = 100f, endX = 120f, slop = slop))
        assertEquals(CutGrab.WHOLE, cutGrab(x = 117f, startX = 100f, endX = 120f, slop = slop))
        assertEquals(CutGrab.START, cutGrab(x = 90f, startX = 100f, endX = 120f, slop = slop))
        assertEquals(CutGrab.START, cutGrab(x = 100f, startX = 100f, endX = 120f, slop = slop))
        assertEquals(CutGrab.END, cutGrab(x = 130f, startX = 100f, endX = 120f, slop = slop))
        assertEquals(CutGrab.END, cutGrab(x = 120f, startX = 100f, endX = 120f, slop = slop))
    }

    // Saving

    private val failed = CutResult.Failed(CutFailure.NOT_SAVED)

    @Test
    fun `a cancelled save's late progress and result change nothing`() = runBlocking {
        val saver = SaveRunner<CutResult>(CoroutineScope(Dispatchers.Unconfined))
        val cleanup = CompletableDeferred<Unit>()
        saver.start { progress ->
            progress(0.2f)
            try {
                awaitCancellation()
            } finally {
                // The save's own cleanup still reports, as ToneAudio.render reports a piece before it sees the cancel.
                withContext(NonCancellable) {
                    cleanup.await()
                    progress(0.9f)
                }
            }
        }
        assertEquals(SaveState.Saving(0.2f, 1), saver.state.value)

        saver.cancel()
        assertEquals(SaveState.Idle, saver.state.value)
        cleanup.complete(Unit)
        assertEquals(SaveState.Idle, saver.state.value)
    }

    @Test
    fun `a new save waits until a cancelled one has cleaned up, so two never share the work folder`() = runBlocking {
        val saver = SaveRunner<CutResult>(CoroutineScope(Dispatchers.Unconfined))
        val events = mutableListOf<String>()
        val cleanup = CompletableDeferred<Unit>()
        saver.start {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    cleanup.await()
                    events += "first cleaned up"
                }
            }
        }
        saver.cancel()
        saver.start { progress ->
            events += "second began"
            progress(0.5f)
            failed
        }
        assertEquals(emptyList<String>(), events)
        assertEquals(SaveState.Saving(0f, 2), saver.state.value)

        cleanup.complete(Unit)
        assertEquals(listOf("first cleaned up", "second began"), events)
        assertEquals(SaveState.Finished(failed), saver.state.value)
        saver.consume()
        assertEquals(SaveState.Idle, saver.state.value)
    }

    @Test
    fun `a Cancel after the save passed its point of no return does not stop it, and its result shows`() = runBlocking {
        val saver = SaveRunner<CutResult>(CoroutineScope(Dispatchers.Unconfined))
        val naming = CompletableDeferred<Unit>()
        val saved = CutResult.Saved(File("/made/up/Harbour Glass/Paper Lanterns (cut).m4a"), tagged = true)
        var committed: Boolean? = null
        saver.start { progress ->
            progress(0.96f)
            // The cut is whole in its part file: from here it takes its name and is scanned.
            committed = commit()
            naming.await()
            saved
        }
        assertEquals(true, committed)

        assertFalse(saver.cancel())
        assertEquals(SaveState.Saving(0.96f, 1), saver.state.value)
        naming.complete(Unit)
        assertEquals(SaveState.Finished(saved), saver.state.value)
    }

    @Test
    fun `a Cancel before that point wins, and the save hears it may not keep its file`() = runBlocking {
        val saver = SaveRunner<CutResult>(CoroutineScope(Dispatchers.Unconfined))
        val copied = CompletableDeferred<Unit>()
        var committed: Boolean? = null
        saver.start {
            // Copying into the part file is not cancellable: the Cancel comes while it runs, and is heard after.
            withContext(NonCancellable) {
                copied.await()
                committed = commit()
            }
            awaitCancellation()
        }
        assertTrue(saver.cancel())
        assertEquals(SaveState.Idle, saver.state.value)

        copied.complete(Unit)
        assertEquals(false, committed)
        assertEquals(SaveState.Idle, saver.state.value)
    }

    @Test
    fun `while a save runs another is not started`() = runBlocking {
        val saver = SaveRunner<CutResult>(CoroutineScope(Dispatchers.Unconfined))
        val first = CompletableDeferred<CutResult>()
        var second = false
        saver.start { first.await() }
        saver.start { second = true; failed }
        assertFalse(second)
        first.complete(failed)
        assertEquals(SaveState.Finished(failed), saver.state.value)
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
