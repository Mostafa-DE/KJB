package app.lyrify.ui

import app.lyrify.R
import app.lyrify.data.LedgerEntry
import app.lyrify.data.LyricsStatus
import app.lyrify.lyrics.Candidate
import app.lyrify.lyrics.Checked
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val PATH = "/music/Paper Lanterns.mp3"

@OptIn(ExperimentalCoroutinesApi::class) // testScheduler: virtual time
class CheckScreenTest {
    @Test
    fun `picked plain lyrics say when they get synced, AI on, off or not on this phone`() {
        val plain = Checked.Done(LyricsStatus.PLAIN_ONLY)
        assertEquals(R.string.check_done_plain_ai_on, checkedMessage(plain, PlainTiming.AI_ON))
        assertEquals(R.string.check_done_plain_ai_off, checkedMessage(plain, PlainTiming.AI_OFF))
        assertEquals(R.string.check_done_plain_no_ai, checkedMessage(plain, PlainTiming.NO_AI))
    }

    @Test
    fun `other answers read the same whatever the AI does`() {
        PlainTiming.entries.forEach { timing ->
            assertEquals(R.string.check_done_synced, checkedMessage(Checked.Done(LyricsStatus.SYNCED), timing))
            assertEquals(R.string.check_done_none, checkedMessage(Checked.Done(LyricsStatus.NOT_FOUND), timing))
            assertEquals(R.string.check_done_existing, checkedMessage(Checked.Done(LyricsStatus.EXISTING), timing))
            assertEquals(R.string.check_changed, checkedMessage(Checked.Done(LyricsStatus.ERROR), timing))
            assertEquals(R.string.check_changed, checkedMessage(Checked.Changed, timing))
            assertEquals(R.string.check_gone, checkedMessage(Checked.Gone, timing))
            assertEquals(R.string.check_busy, checkedMessage(Checked.Unavailable("offline"), timing))
            assertEquals(R.string.check_not_saved, checkedMessage(Checked.NotSaved, timing))
        }
    }

    // Answers run in the app's scope, not the screen's.

    /** The app's scope on the test's clock; what fails in it lands in [failures]. */
    private fun TestScope.app(failures: MutableList<Throwable> = mutableListOf()): CoroutineScope =
        CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler) + CoroutineExceptionHandler { _, e -> failures += e })

    @Test
    fun `an answer finishes and is told after the screen that gave it has gone`() = runTest {
        val answers = CheckAnswers(app())
        val screen = CoroutineScope(StandardTestDispatcher(testScheduler))
        val reply = CompletableDeferred<Checked>()
        var recorded = false

        val answer = answers.answer(PATH, PlainTiming.AI_ON) { reply.await().also { recorded = true } }
        assertNotNull(answer)
        screen.launch { answer?.await() }
        advanceUntilIdle()
        // Back or a turn of the phone while the answer is out.
        screen.cancel()
        reply.complete(Checked.Done(LyricsStatus.PLAIN_ONLY))
        advanceUntilIdle()

        assertTrue(recorded)
        assertEquals(R.string.check_done_plain_ai_on, answers.messages.first())
        assertEquals(emptySet<String>(), answers.busy.value)
    }

    @Test
    fun `a song with an answer out takes no second one until it is in`() = runTest {
        val answers = CheckAnswers(app())
        val reply = CompletableDeferred<Checked>()

        val first = answers.answer(PATH, PlainTiming.AI_OFF) { reply.await() }
        assertEquals(setOf(PATH), answers.busy.value)
        assertNull(answers.answer(PATH, PlainTiming.AI_OFF) { Checked.Changed })

        reply.complete(Checked.Done(LyricsStatus.NOT_FOUND))
        assertEquals(Checked.Done(LyricsStatus.NOT_FOUND), first?.await())
        advanceUntilIdle()
        assertEquals(emptySet<String>(), answers.busy.value)
        assertNotNull(answers.answer(PATH, PlainTiming.AI_OFF) { Checked.Changed })
    }

    @Test
    fun `an answer that fails frees its song, is logged by the app and says nothing`() = runTest {
        val failures = mutableListOf<Throwable>()
        val answers = CheckAnswers(app(failures))
        val told = mutableListOf<Int>()
        backgroundScope.launch { answers.messages.collect { told += it } }

        val answer = answers.answer(PATH, PlainTiming.AI_ON) { error("disk gone") }
        advanceUntilIdle()

        assertEquals(1, failures.size)
        assertTrue(answer?.isCancelled == true)
        assertEquals(emptySet<String>(), answers.busy.value)
        assertEquals(emptyList<Int>(), told)
    }

    // KJB 3.0.2 crash: a song joining the list while Check these is open (CR4)

    @Test
    fun `a page is keyed by its song's path, and one past the songs by a key of its own, never a crash`() {
        val songs = listOf(waiting(PATH), waiting("/music/Low Water.mp3"))
        assertEquals("/music/Low Water.mp3", checkPageKey(songs, 1))
        // The pager counting three pages while the songs it reads are still the two: KJB 3.0.2's
        // ArrayIndexOutOfBoundsException.
        val third = checkPageKey(songs, 2)
        assertFalse(third in songs.map { it.path })
        assertEquals(third, checkPageKey(songs, 2))
        // One per page, and saveable on the phone as a page's key must be.
        assertTrue(third != checkPageKey(songs, 3))
        assertTrue(third is java.io.Serializable)
        assertTrue(checkPageKey(emptyList(), 0) is java.io.Serializable)
    }

    /** A song waiting on the check list, with one match to pick. */
    private fun waiting(path: String) = LedgerEntry(
        path = path,
        title = path.substringAfterLast('/').substringBeforeLast('.'),
        durationMs = 200_000,
        status = LyricsStatus.NEEDS_CHECK,
        updatedAt = 1_000,
        candidates = listOf(Candidate(id = 7, title = "Paper Lanterns", artist = "Harbour Glass", duration = 200.0, synced = true)),
    )
}
