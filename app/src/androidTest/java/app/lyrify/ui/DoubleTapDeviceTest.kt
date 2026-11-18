package app.lyrify.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.lyrify.library.SongRef
import app.lyrify.player.PlayerUi
import app.lyrify.player.QueueBook
import app.lyrify.player.QueueSource
import app.lyrify.player.Repeat
import app.lyrify.player.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Double-tap to favourite on a real window: a tap acts at once (nothing waits for a second), and a second tap soon after
 * has its lift taken by the host, so neither the row again nor the page the first tap opened acts on it; a long press
 * soon after a tap is still the row's. The player's cover of a book's part, which has no tap of its own, double-taps
 * too. Made-up names only.
 */
@RunWith(AndroidJUnit4::class)
class DoubleTapDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val album = FavouriteTarget.album("""["Kit Calloway","Harbour Glass"]""")
    private val favourited = mutableListOf<FavouriteTarget>()
    private val taps = DoubleTaps(TapTiming(windowMs = 300, minGapMs = 40, touchSlopPx = 24f, holdMs = 400)) { favourited += it }

    @Test
    fun aTapActsAtOnceWithoutWaitingForASecond() {
        var plays = 0
        compose.setContent {
            Box(Modifier.fillMaxSize().doubleTapHost(taps)) {
                Box(Modifier.size(200.dp).testTag(ROW).doubleTapFavourite(album).clickable { plays++ })
            }
        }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag(ROW).performTouchInput { click() }
        assertEquals(1, plays)
        assertEquals(emptyList<FavouriteTarget>(), favourited)
    }

    @Test
    fun aDoubleTapOnARowPlaysOnceAndFavourites() {
        var plays = 0
        compose.setContent {
            Box(Modifier.fillMaxSize().doubleTapHost(taps)) {
                Box(Modifier.size(200.dp).testTag(ROW).doubleTapFavourite(album).clickable { plays++ })
                HeartPops(taps)
            }
        }
        compose.onNodeWithTag(ROW).performTouchInput { doubleClick() }
        compose.runOnIdle {
            assertEquals(1, plays)
            assertEquals(listOf(album), favourited)
        }
    }

    @Test
    fun theSecondTapAfterATapThatOpenedAPageNeverReachesThePage() {
        var opened by mutableStateOf(false)
        var pageTaps = 0
        compose.setContent {
            Box(Modifier.fillMaxSize().doubleTapHost(taps)) {
                if (opened) {
                    Box(Modifier.size(200.dp).testTag(PAGE).clickable { pageTaps++ })
                } else {
                    Box(Modifier.size(200.dp).testTag(ROW).doubleTapFavourite(album).clickable { opened = true })
                }
            }
        }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag(ROW).performTouchInput { click() }
        // The page takes the card's place before the second tap lands, on the same spot.
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag(PAGE).performTouchInput {
            advanceEventTime(80)
            click()
        }
        compose.mainClock.autoAdvance = true
        compose.runOnIdle {
            assertTrue(opened)
            assertEquals(0, pageTaps)
            assertEquals(listOf(album), favourited)
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Test
    fun aLongPressSoonAfterATapStillSelects() {
        var plays = 0
        var selects = 0
        compose.setContent {
            Box(Modifier.fillMaxSize().doubleTapHost(taps)) {
                Box(Modifier.size(200.dp).testTag(ROW).doubleTapFavourite(album).combinedClickable(onLongClick = { selects++ }) { plays++ })
            }
        }
        compose.onNodeWithTag(ROW).performTouchInput {
            click()
            advanceEventTime(100)
            longClick()
        }
        compose.runOnIdle {
            assertEquals(1, plays)
            assertEquals(1, selects)
            assertEquals(emptyList<FavouriteTarget>(), favourited)
        }
    }

    @Test
    fun aBookPartsCoverDoubleTappedFavouritesIt() {
        val ui = book()
        compose.setContent {
            LyrifyTheme {
                CompositionLocalProvider(LocalToasts provides remember { ToastState() }) {
                    Box(Modifier.fillMaxSize().doubleTapHost(taps)) {
                        Box(Modifier.size(COVER).testTag(ROW)) {
                            CoverFace(ui, side = COVER, area = COVER, enabled = true, onFlip = null, onNext = {}, onPrevious = {})
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag(ROW).performTouchInput { doubleClick() }
        compose.runOnIdle { assertEquals(listOf(FavouriteTarget.Song(ui.item.song)), favourited) }
    }

    @Test
    fun withTalkBackOnNothingIsTakenAndEveryTapActs() {
        var plays = 0
        taps.enabled = false
        compose.setContent {
            Box(Modifier.fillMaxSize().doubleTapHost(taps)) {
                Box(Modifier.size(200.dp).testTag(ROW).doubleTapFavourite(album).clickable { plays++ })
            }
        }
        compose.onNodeWithTag(ROW).performTouchInput { doubleClick() }
        compose.runOnIdle {
            assertEquals(2, plays)
            assertEquals(emptyList<FavouriteTarget>(), favourited)
        }
    }

    /** A part of a made-up book playing: its cover has no tap of its own. */
    private fun book(): PlayerUi {
        val parts = listOf("Chapter One", "Chapter Two").map { SongRef("/Audiobooks/Night Ferry/$it.mp3") }
        val queues = QueueBook().play(parts, 0, QueueSource(SourceKind.BOOK, "book:x", "Night Ferry"), now = 1).book
        val queue = queues.current!!
        return PlayerUi(queues, queue, queue.current!!, song = null, playing = false, positionMs = 0, at = 0, speed = 1f, durationMs = 200_000, repeat = Repeat.OFF, library = null)
    }

    private companion object {
        const val ROW = "row"
        const val PAGE = "page"
        val COVER = 200.dp
    }
}
