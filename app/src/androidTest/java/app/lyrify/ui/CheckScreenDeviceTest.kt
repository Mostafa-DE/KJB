package app.lyrify.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lyrify.R
import app.lyrify.data.LedgerEntry
import app.lyrify.data.LedgerState
import app.lyrify.data.LyricsStatus
import app.lyrify.lyrics.Candidate
import app.lyrify.lyrics.Checked
import app.lyrify.lyrics.LyricsPreview
import app.lyrify.lyrics.Searched
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Check these on the phone while its list changes under it (CR4): a song joining the list while it is open (a sweep, or
 * the song playing, found lyrics KJB is not sure about) never crashes it, as KJB 3.0.2 did, and the song shown stays
 * shown wherever the list moves it; a song answered gives way to its neighbour, never the first; the last one answered
 * leaves All checked. Made-up names only.
 */
@RunWith(AndroidJUnit4::class)
class CheckScreenDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /** The ledger as the shell hands it to Check these: written by the test as a sweep or an answer would. */
    private var ledger by mutableStateOf(LedgerState())

    private val actions = CheckActions(
        choose = { _, _ -> Checked.Done(LyricsStatus.SYNCED) },
        decline = { Checked.Done(LyricsStatus.NOT_FOUND) },
        preview = { LyricsPreview.None },
        search = { _, _ -> Searched.Found(emptyList()) },
    )

    @Test
    fun aSongJoiningTheListKeepsTheSongShownAndNeverCrashes() {
        ledger = THREE.fold(LedgerState()) { list, (title, since) -> list.put(waiting(title, since)) }
        compose.setContent { Check(initial = WATER) }
        assertShows(WATER, at = 2, of = 3)
        // A song found meanwhile joins at the top (the newest first). KJB 3.0.2 crashed here: the pager counted four
        // pages while the keys it read were still the three songs'.
        ledger = ledger.put(waiting(GLASS, since = 5_000))
        compose.waitForIdle()
        assertShows(WATER, at = 3, of = 4)
        // And one at the end.
        ledger = ledger.put(waiting(BELLS, since = 1_000))
        compose.waitForIdle()
        assertShows(WATER, at = 3, of = 5)
    }

    @Test
    fun aSongAnsweredGivesWayToItsNeighbourAndTheLastLeavesAllChecked() {
        ledger = THREE.fold(LedgerState()) { list, (title, since) -> list.put(waiting(title, since)) }
        compose.setContent { Check(initial = WATER) }
        assertShows(WATER, at = 2, of = 3)
        // Low Water answered: the song after it in its place, not the first.
        ledger = ledger.answered(WATER)
        compose.waitForIdle()
        assertShows(ROAD, at = 2, of = 2)
        // The last song answered: the one before it.
        ledger = ledger.answered(ROAD)
        compose.waitForIdle()
        assertShows(LANTERN, at = 1, of = 1)
        ledger = ledger.answered(LANTERN)
        compose.waitForIdle()
        compose.onNodeWithText(context.getString(R.string.check_empty_title)).assertIsDisplayed()
    }

    /** The page on screen is [title]'s, the [at]th of [of] as the top bar says. A page beside it may be composed, off screen. */
    private fun assertShows(title: String, at: Int, of: Int) {
        compose.onNodeWithText(context.getString(R.string.check_position, at, of)).assertIsDisplayed()
        val pages = compose.onAllNodesWithText(title)
        assertTrue("$title not on screen", pages.fetchSemanticsNodes().indices.any { pages[it].isDisplayed() })
    }

    /** Check these as the shell shows it, over [ledger] as it changes, opened at [initial]'s song. */
    @Composable
    private fun Check(initial: String) {
        LyrifyTheme {
            val toasts = remember { ToastState() }
            val scope = rememberCoroutineScope()
            val answers = remember { CheckAnswers(scope) }
            CompositionLocalProvider(LocalToasts provides toasts) {
                CheckScreen(ledger, PlainTiming.NO_AI, actions, answers, path(initial)) {}
            }
        }
    }

    private fun path(title: String) = "/storage/emulated/0/Music/Made Up/$title.mp3"

    /** [title] waiting for a check since [since] (the list shows the newest first), with one match to pick. */
    private fun waiting(title: String, since: Long) = LedgerEntry(
        path = path(title),
        title = title,
        durationMs = 200_000,
        status = LyricsStatus.NEEDS_CHECK,
        updatedAt = since,
        candidates = listOf(Candidate(id = since, title = "A match", artist = "Someone", duration = 200.0, synced = true)),
    )

    /** [title]'s lyrics picked: off the list. */
    private fun LedgerState.answered(title: String): LedgerState =
        put(entries.getValue(path(title)).copy(status = LyricsStatus.SYNCED, candidates = emptyList()))

    private companion object {
        const val LANTERN = "Paper Lanterns"
        const val WATER = "Low Water"
        const val ROAD = "Salt Road"
        const val GLASS = "Harbour Glass"
        const val BELLS = "Tin Bells"

        /** On the list newest first: Paper Lanterns, Low Water, Salt Road. */
        val THREE = listOf(LANTERN to 4_000L, WATER to 3_000L, ROAD to 2_000L)
    }
}
