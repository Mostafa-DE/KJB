package app.lyrify.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * A queue's page opening at its song (the user's ask after 3.1's pinned buttons): a list laid out as OneQueuePage's (a
 * head, Resume and Play from start as its sticky row, then the songs) brought there by [openQueuePage]. The song before
 * the song here shows right under the pinned row; near the queue's end, or in a short window, the song here stays whole
 * above the bars; in a window too short for even that, or a song before taller than the window, the song here goes
 * right under the pinned row.
 */
@RunWith(AndroidJUnit4::class)
class QueuePageOpenDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aQueueFarInOpensWithTheSongBeforeRightUnderThePinnedButtons() {
        show(songs = 40, here = 20, window = 640.dp, bar = 120.dp)

        assertNear(0.dp, bounds(BUTTONS).top)
        assertNear(bounds(BUTTONS).bottom, bounds(song(19)).top)
        assertNear(bounds(song(19)).bottom, bounds(song(20)).top)
    }

    @Test
    fun nearTheQueuesEndTheSongHereStaysAboveTheBars() {
        show(songs = 40, here = 39, window = 640.dp, bar = 120.dp)

        assertTrue("${bounds(song(39))}", bounds(song(39)).bottom <= 640.dp - 120.dp + 1.dp)
        assertTrue("${bounds(song(38))}", bounds(song(38)).top >= bounds(BUTTONS).bottom - 1.dp)
    }

    @Test
    fun aShortWindowKeepsTheSongHereWholeAboveTheBarsAndAsMuchOfTheSongBeforeAsFits() {
        // 180 dp above the bars: the buttons (60) and two songs (72 each) do not fit.
        show(songs = 40, here = 20, window = 300.dp, bar = 120.dp)

        assertNear(180.dp, bounds(song(20)).bottom)
        assertNear(bounds(song(19)).bottom, bounds(song(20)).top)
        assertTrue("${bounds(song(20))}", bounds(song(20)).top >= bounds(BUTTONS).bottom)
    }

    @Test
    fun aWindowTooShortForEvenOneSongPutsTheSongHereRightUnderTheButtons() {
        // 100 dp above the bars: not even the buttons and one song.
        show(songs = 40, here = 20, window = 220.dp, bar = 120.dp)

        assertNear(bounds(BUTTONS).bottom, bounds(song(20)).top)
    }

    @Test
    fun aSongBeforeTallerThanTheWindowStillBringsTheSongHereUnderTheButtons() {
        show(songs = 40, here = 20, window = 300.dp, bar = 0.dp) { if (it == 19) 600.dp else ROW }

        assertNear(bounds(BUTTONS).bottom, bounds(song(20)).top)
    }

    /** The page's list with the song at [here] (of [songs]) the queue's, in a [window] over bars [bar] tall. */
    private fun show(songs: Int, here: Int, window: Dp, bar: Dp, row: (Int) -> Dp = { ROW }) {
        val opening = firstShown(here, head = 2)
        compose.setContent {
            val list = rememberLazyListState(opening)
            var buttons by remember { mutableIntStateOf(0) }
            LaunchedEffect(list) { openQueuePage(list, opening) { buttons } }
            LazyColumn(Modifier.fillMaxWidth().height(window), state = list, contentPadding = PaddingValues(bottom = bar)) {
                item(key = "head") { Box(Modifier.fillMaxWidth().height(160.dp)) }
                stickyHeader(key = "buttons") {
                    Box(Modifier.fillMaxWidth().height(60.dp).background(Color.White).onSizeChanged { buttons = it.height }.testTag(BUTTONS))
                }
                items(songs, key = { it }) { Box(Modifier.fillMaxWidth().height(row(it)).testTag(song(it))) }
            }
        }
        compose.waitForIdle()
    }

    private fun bounds(tag: String): DpRect = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()

    private fun assertNear(expected: Dp, actual: Dp) = assertTrue("$actual, not $expected", abs((actual - expected).value) <= 1f)

    private fun song(index: Int) = "song $index"

    private companion object {
        const val BUTTONS = "buttons"
        val ROW = 72.dp
    }
}
