package app.lyrify.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lyrify.R
import app.lyrify.library.SongRef
import app.lyrify.player.PlayerUi
import app.lyrify.player.QueueBook
import app.lyrify.player.QueueSource
import app.lyrify.player.Repeat
import app.lyrify.player.SourceKind
import app.lyrify.player.queueLabels
import app.lyrify.settings.LibrarySettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Queues page's list (QF3) on the phone, at a width and font scale set here rather than the phone's, light and dark:
 * "Playing now" with the queue playing first and only there, Recent under it holding a queue kept with Keep in 3.0.0,
 * no Saved part, and one empty state when there is no queue. The book alone: nothing plays, but for the card told
 * playing or paused. Also the Queue sheet's Recent chips, many queues of them. Made-up names only.
 */
@RunWith(AndroidJUnit4::class)
class QueuesListDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val playingNow = context.getString(R.string.queues_playing)
    private val recent = context.getString(R.string.queues_recent)
    private val opened = mutableListOf<String>()

    private fun song(name: String) = SongRef("/storage/emulated/0/Music/$name.mp3")

    /** Harbour Glass playing at its third of four songs, Rips left before it, and Sunday chores kept with Keep before that. */
    private fun book(): QueueBook {
        var book = QueueBook().play((1..2).map { song("Sunday $it") }, 0, QueueSource(SourceKind.SEARCH, "search", "sunday"), now = 1).book
        val sunday = book.currentId!!
        book = book.rename(sunday, "Sunday chores").keep(sunday)
        book = book.play((1..3).map { song("Rips $it") }, 0, QueueSource(SourceKind.FOLDER, "folder:Music/Rips", "Rips"), now = 2).book
        return book.play((1..4).map { song("Harbour Glass $it") }, 2, QueueSource(SourceKind.ALBUM, "album:harbour", "Harbour Glass"), now = 3).book
    }

    @Test
    fun theQueuePlayingShowsFirstUnderPlayingNowWithItsSongAndPlaceAndOnlyThere() {
        val book = book()
        show(book)
        compose.onAllNodesWithText(playingNow).assertCountEquals(1)
        // The card: the queue's name, its song (its file name, without a library) and "3 of 4", one node.
        compose.onAllNodesWithText("Harbour Glass", substring = true).assertCountEquals(1)
        compose.onNodeWithText("3 of 4", useUnmergedTree = true).assertExists()
        val card = compose.onNodeWithText("3 of 4", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val heading = compose.onNodeWithText(recent).getUnclippedBoundsInRoot()
        val rips = compose.onNodeWithText("Rips", substring = true).getUnclippedBoundsInRoot()
        val sunday = compose.onNodeWithText("Sunday chores", substring = true).getUnclippedBoundsInRoot()
        assertTrue("the card ends at ${card.bottom}, Recent starts at ${heading.top}", card.bottom <= heading.top)
        // Rips was left last, so it comes before the queue kept earlier.
        assertTrue(heading.bottom <= rips.top && rips.bottom <= sunday.top)
        compose.onNodeWithText("Harbour Glass", substring = true).performClick()
        assertEquals(listOf(book.currentId), opened)
    }

    @Test
    fun noPartReadsSavedAndNoneOffersKeep() {
        show(book())
        compose.onAllNodesWithText("Saved", substring = true, ignoreCase = true).assertCountEquals(0)
        compose.onAllNodesWithText("Keep", substring = true, ignoreCase = true).assertCountEquals(0)
    }

    @Test
    fun withNothingPlayingThereIsNoPlayingNowCardOnlyRecent() {
        val playing = book()
        val id = playing.currentId!!
        val stopped = playing.queue(id)!!.items.fold(playing) { left, item -> left.remove(id, item.uid)!!.book }
        show(stopped)
        compose.onAllNodesWithText(playingNow).assertCountEquals(0)
        compose.onNodeWithText(recent).assertExists()
        compose.onNodeWithText("Sunday chores", substring = true).assertExists()
    }

    @Test
    fun withNoQueueAtAllThePageShowsOneCalmEmptyStateAndNoEmptyPart() {
        show(QueueBook())
        compose.onNodeWithText(context.getString(R.string.queues_none)).assertExists()
        compose.onAllNodesWithText(playingNow).assertCountEquals(0)
        compose.onAllNodesWithText(recent).assertCountEquals(0)
    }

    @Test
    fun atFontScaleTwoOnANarrowScreenInTheDarkTheCardsPlaceIsNeverCut() {
        show(book(), width = 320.dp, fontScale = 2f, dark = true)
        val place = layoutOf("3 of 4")
        assertFalse("3 of 4 cut", place.hasVisualOverflow || place.isLineEllipsized(place.lineCount - 1))
        compose.onAllNodesWithText(playingNow).assertCountEquals(1)
    }

    @Test
    fun theCardTellsTalkBackWhetherItsQueueIsPlayingOrPaused() {
        val book = book()
        val queue = book.current!!
        fun ui(playing: Boolean) = PlayerUi(book, queue, queue.current!!, song = null, playing = playing, positionMs = 0, at = 0, speed = 1f, durationMs = 0, repeat = Repeat.OFF, library = null)
        val live = mutableStateOf(ui(playing = true))
        show { QueuesList(book, live.value, library = null, names = LibrarySettings(), padding = PaddingValues(0.dp)) { opened += it } }
        compose.onNodeWithContentDescription("3 of 4, $playingNow", substring = true).assertExists()
        live.value = ui(playing = false)
        compose.onNodeWithContentDescription("3 of 4", substring = true).assertExists()
        compose.onAllNodesWithContentDescription(playingNow, substring = true).assertCountEquals(0)
    }

    @Test
    fun theSheetsRecentChipsComposeOnlyThoseInReachAndCutALongName() {
        // 40 queues kept with Keep in 3.0.0, so 39 chips beside the one playing; the queue left last has a long name.
        val many = (1..40).fold(QueueBook()) { book, n ->
            val played = book.play(listOf(song("Chip $n")), 0, QueueSource(SourceKind.FOLDER, "folder:Music/Chip $n", "Chip $n"), now = n.toLong()).book
            val id = played.currentId!!
            played.rename(id, "Chip $n").keep(id)
        }
        val long = "Chip of a long evening walk along the harbour wall and back again"
        val target = many.others.first().id
        val book = many.rename(target, long)
        val others = book.others
        show { RecentChips(others, queueLabels(context, book)) { opened += it } }
        val shown = compose.onAllNodesWithText("Chip", substring = true).fetchSemanticsNodes().size
        assertTrue("$shown of ${others.size} chips composed", shown in 1 until others.size)
        val name = layoutOf(long)
        assertTrue("the long name is not cut", name.isLineEllipsized(0))
        val width = compose.onNodeWithText(long, useUnmergedTree = true).getUnclippedBoundsInRoot().let { it.right - it.left }
        assertTrue("the long name is $width wide", width <= 220.dp)
        compose.onNodeWithText(long).performClick()
        assertEquals(listOf(target), opened)
    }

    /** The list alone, [width] wide (beyond the phone's own if need be), at [fontScale], light or [dark]. */
    private fun show(book: QueueBook, width: Dp = 360.dp, fontScale: Float = 1f, dark: Boolean = false) = show(width, fontScale, dark) {
        QueuesList(book, ui = null, library = null, names = LibrarySettings(), padding = PaddingValues(0.dp)) { opened += it }
    }

    /** [content] alone, [width] wide (beyond the phone's own if need be), at [fontScale], light or [dark]. */
    private fun show(width: Dp = 360.dp, fontScale: Float = 1f, dark: Boolean = false, content: @Composable () -> Unit) = compose.setContent {
        val night = if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        val config = Configuration(LocalConfiguration.current).apply { uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night }
        CompositionLocalProvider(LocalConfiguration provides config, LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
            LyrifyTheme {
                Box(Modifier.requiredWidth(width)) { content() }
            }
        }
    }

    private fun layoutOf(words: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(words, useUnmergedTree = true).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.single()
    }
}
