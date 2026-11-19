package app.lyrify.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
 * Double-tap to favourite on a real window: a card's tap acts at once (nothing waits for a second), and a second tap
 * soon after has its lift taken by the host, so neither the card again nor the page the first tap opened acts on it; a
 * long press soon after a tap is still the item's. A song row's tap waits a moment and plays only if no second tap
 * comes, its press showing at once: a double tap there only favourites, a long press soon after only selects, and a
 * tap on another row plays the first at once. The player's cover of a book's part, which has no tap of its own, double-taps too. Made-up names only.
 */
@RunWith(AndroidJUnit4::class)
class DoubleTapDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val album = FavouriteTarget.album("""["Kit Calloway","Harbour Glass"]""")
    private val lantern = FavouriteTarget.Song(SongRef("/Music/Harbour Glass/01 Paper Lantern.mp3"))
    private val ferry = FavouriteTarget.Song(SongRef("/Music/Harbour Glass/02 Night Ferry.mp3"))
    private val favourited = mutableListOf<FavouriteTarget>()
    private val timing = TapTiming(windowMs = 300, minGapMs = 40, touchSlopPx = 24f, holdMs = 400)

    /** TalkBack exploring: the host's double taps are off. */
    private var talkBack = false
    private lateinit var taps: DoubleTaps

    /** The host of [content]'s double taps, its waits on the test's clock. */
    @Composable
    private fun Host(content: @Composable BoxScope.() -> Unit) {
        val scope = rememberCoroutineScope()
        val host = remember { DoubleTaps(timing, scope) { favourited += it }.also { it.enabled = !talkBack } }
        taps = host
        Box(Modifier.fillMaxSize().doubleTapHost(host), content = content)
    }

    /** A song's row, as [SongRow] wires it: its tap waits, and its click is the waiting tap's. */
    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun SongRowBox(tag: String, target: FavouriteTarget, onLongClick: (() -> Unit)? = null, onPlay: () -> Unit) {
        val tap = rememberWaitingTap(onPlay)
        Box(Modifier.size(200.dp).testTag(tag).doubleTapFavourite(target, waiting = tap).combinedClickable(onLongClick = onLongClick, onClick = tap::click))
    }

    @Test
    fun aCardsTapActsAtOnceWithoutWaitingForASecond() {
        var opens = 0
        compose.setContent {
            Host {
                Box(Modifier.size(200.dp).testTag(ROW).doubleTapFavourite(album).clickable { opens++ })
            }
        }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag(ROW).performTouchInput { click() }
        assertEquals(1, opens)
        assertEquals(emptyList<FavouriteTarget>(), favourited)
    }

    // Was aDoubleTapOnARowPlaysOnceAndFavourites: a song row's tap no longer acts at once (dt2), so a card's tap
    // opening once is what it shows now; a song row's double tap is aDoubleTapOnASongRowFavouritesItAndNeverPlaysIt.
    @Test
    fun aDoubleTapOnACardOpensItOnceAndFavourites() {
        var opens = 0
        compose.setContent {
            Host {
                Box(Modifier.size(200.dp).testTag(ROW).doubleTapFavourite(album).clickable { opens++ })
                HeartPops(taps)
            }
        }
        compose.onNodeWithTag(ROW).performTouchInput { doubleClick() }
        compose.runOnIdle {
            assertEquals(1, opens)
            assertEquals(listOf(album), favourited)
        }
    }

    @Test
    fun aTapOnASongRowPlaysOnceTheWaitIsOverAndNotBefore() {
        var plays = 0
        compose.setContent { Host { SongRowBox(ROW, lantern) { plays++ } } }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag(ROW).performTouchInput { click() }
        compose.mainClock.advanceTimeBy(TAP_WAIT_MS - 100)
        assertEquals(0, plays)
        compose.mainClock.advanceTimeBy(200)
        assertEquals(1, plays)
        assertEquals(emptyList<FavouriteTarget>(), favourited)
    }

    @Test
    fun aSongRowsPressShowsAtOnceWhileItsTapWaits() {
        var plays = 0
        val seen = mutableListOf<Interaction>()
        compose.setContent {
            val press = remember { MutableInteractionSource() }
            LaunchedEffect(press) { press.interactions.collect { seen += it } }
            Host {
                val tap = rememberWaitingTap { plays++ }
                Box(Modifier.size(200.dp).testTag(ROW).doubleTapFavourite(lantern, waiting = tap).clickable(interactionSource = press, indication = null, onClick = tap::click))
            }
        }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag(ROW).performTouchInput { click() }
        compose.mainClock.advanceTimeBy(TAP_WAIT_MS - 100)
        assertTrue(seen.firstOrNull() is PressInteraction.Press)
        assertEquals(0, plays)
        compose.mainClock.advanceTimeBy(200)
        assertEquals(1, plays)
    }

    @Test
    fun aDoubleTapOnASongRowFavouritesItAndNeverPlaysIt() {
        var plays = 0
        compose.setContent { Host { SongRowBox(ROW, lantern) { plays++ } } }
        compose.onNodeWithTag(ROW).performTouchInput { doubleClick() }
        compose.mainClock.advanceTimeBy(1_000)
        compose.runOnIdle {
            assertEquals(0, plays)
            assertEquals(listOf(lantern), favourited)
        }
    }

    @Test
    fun aTapOnAnotherSongRowInsideTheWaitPlaysTheFirstAtOnceThenWaits() {
        val plays = mutableListOf<FavouriteTarget>()
        compose.setContent {
            Host {
                Column {
                    SongRowBox(ROW, lantern) { plays += lantern }
                    SongRowBox(NEXT, ferry) { plays += ferry }
                }
            }
        }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag(ROW).performTouchInput { click() }
        compose.onNodeWithTag(NEXT).performTouchInput {
            advanceEventTime(80)
            click()
        }
        assertEquals(listOf(lantern), plays)
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals(listOf(lantern, ferry), plays)
        assertEquals(emptyList<FavouriteTarget>(), favourited)
    }

    @Test
    fun aLongPressSoonAfterATapOnASongRowSelectsAndNeverPlays() {
        var plays = 0
        var selects = 0
        compose.setContent { Host { SongRowBox(ROW, lantern, onLongClick = { selects++ }) { plays++ } } }
        compose.onNodeWithTag(ROW).performTouchInput {
            click()
            advanceEventTime(100)
            longClick()
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.runOnIdle {
            assertEquals(0, plays)
            assertEquals(1, selects)
            assertEquals(emptyList<FavouriteTarget>(), favourited)
        }
    }

    @Test
    fun theSecondTapAfterATapThatOpenedAPageNeverReachesThePage() {
        var opened by mutableStateOf(false)
        var pageTaps = 0
        compose.setContent {
            Host {
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
            Host {
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
                    Host {
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
        var opens = 0
        var plays = 0
        talkBack = true
        compose.setContent {
            Host {
                Column {
                    Box(Modifier.size(200.dp).testTag(ROW).doubleTapFavourite(album).clickable { opens++ })
                    SongRowBox(NEXT, lantern) { plays++ }
                }
            }
        }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag(ROW).performTouchInput { doubleClick() }
        // A song's row never waits either: each tap plays at once.
        compose.onNodeWithTag(NEXT).performTouchInput { doubleClick() }
        assertEquals(2, opens)
        assertEquals(2, plays)
        assertEquals(emptyList<FavouriteTarget>(), favourited)
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
        const val NEXT = "next"
        const val PAGE = "page"
        val COVER = 200.dp
    }
}
