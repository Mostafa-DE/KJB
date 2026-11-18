package app.lyrify.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.click
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.lyrify.R
import app.lyrify.library.SongRef
import app.lyrify.lyrics.LrcLine
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
import kotlin.math.abs

/**
 * The player dragged down from anywhere on it (DR1, user request 2026-11-17), under a real finger, on the player's own
 * frame ([PlayerFrame]) around its own parts: a drag down from the title, from a button of the controls row, or on the
 * lyrics already at their top closes it, as the chevron does, and TalkBack's dismiss on the chevron too. A drag along
 * the seek bar seeks, a sideways swipe on the cover skips, a drag on the lyrics that scrolled them first scrolls only,
 * and a seek button held still repeats: none of them closes it. A drag down from a seek button, or mostly down on the
 * seek bar, closes it with no jump and no seek (review 2026-11-17). Made-up names and lines only.
 */
@RunWith(AndroidJUnit4::class)
class PlayerDragDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val lines = (0 until 60).map { LrcLine(10_000L + it * 5_000L, "Paper lantern verse ${it + 1}") }
    private var closed = 0
    private var skips = 0
    private var taps = 0
    private var jumps = 0
    private val seeks = mutableListOf<Long>()

    private fun player() {
        val ui = ui()
        compose.setContent {
            LyrifyTheme {
                CompositionLocalProvider(LocalToasts provides remember { ToastState() }) {
                    PlayerFrame(onClose = { closed++ }) {
                        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
                            PlayerCloseButton { closed++ }
                            Text(TITLE, Modifier.fillMaxWidth().padding(vertical = 16.dp))
                            Box(Modifier.size(COVER).align(Alignment.CenterHorizontally).testTag(COVER_TAG)) {
                                CoverFace(ui, side = COVER, area = AREA, enabled = true, onFlip = null, onNext = { skips++ }, onPrevious = { skips++ })
                            }
                            Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                                SeekJump(R.drawable.ic_replay_10, BACK) { jumps++ }
                                RoundButton(R.drawable.ic_skip_previous_fill, PREVIOUS, size = 56.dp) { taps++ }
                                RoundButton(R.drawable.ic_skip_next_fill, NEXT, size = 56.dp) { taps++ }
                                SeekJump(R.drawable.ic_forward_10, FORWARD) { jumps++ }
                            }
                            ProgressBar(ui, onSeek = { seeks += it })
                            Box(Modifier.fillMaxWidth().weight(1f).testTag(LYRICS)) {
                                // Before the first line: the list rests at its top.
                                SyncedLyrics(lines, positionMs = 0, enabled = true, style = PanelLine, frame = PanelFrame) {}
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun aDragDownFromTheTitleClosesThePlayer() {
        player()
        val pull = pull()
        compose.onNodeWithText(TITLE).performTouchInput { drag(center, center + Offset(0f, pull)) }
        compose.runOnIdle { assertEquals(1, closed) }
    }

    @Test
    fun aDragDownFromAButtonOfTheControlsClosesThePlayerAndNeverPressesIt() {
        player()
        val pull = pull()
        compose.onNodeWithContentDescription(PREVIOUS).performTouchInput { drag(center, center + Offset(0f, pull)) }
        compose.runOnIdle {
            assertEquals(1, closed)
            assertEquals(0, taps)
        }
    }

    @Test
    fun aTapOnAButtonStillPressesItAndNeverClosesThePlayer() {
        player()
        compose.onNodeWithContentDescription(NEXT).performClick()
        compose.runOnIdle {
            assertEquals(1, taps)
            assertEquals(0, closed)
        }
    }

    @Test
    fun aSeekButtonHeldStillRepeatsItsJumpAndNeverClosesThePlayer() {
        player()
        compose.onNodeWithContentDescription(BACK).performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(HOLD_DELAY_MS + HOLD_EVERY_MS * 2)
        compose.onNodeWithContentDescription(BACK).performTouchInput { up() }
        compose.runOnIdle {
            assertTrue("jumps $jumps", jumps >= 2)
            assertEquals(0, closed)
        }
    }

    @Test
    fun aDragDownOnTheLyricsAtTheirTopClosesThePlayer() {
        player()
        val pull = pull()
        compose.onNodeWithTag(LYRICS).performTouchInput {
            val from = Offset(centerX, height * 0.1f)
            drag(from, from + Offset(0f, pull))
        }
        compose.runOnIdle { assertEquals(1, closed) }
    }

    @Test
    fun aDragOnTheLyricsThatScrolledThemFirstOnlyScrollsAndNeverClosesThePlayer() {
        player()
        val list = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and hasAnyAncestor(hasTestTag(LYRICS)))
        fun scrolled(): Float = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertEquals(0f, scrolled())
        // One finger: up half the lyrics' height (they scroll on), then down well past where it started (they scroll back
        // to their top, and what is left over is no pull on the player).
        compose.onNodeWithTag(LYRICS).performTouchInput {
            val start = Offset(centerX, height * 0.7f)
            down(start)
            steps(start, Offset(centerX, height * 0.2f))
            steps(Offset(centerX, height * 0.2f), Offset(centerX, height * 0.98f))
            up()
        }
        compose.waitForIdle()
        assertEquals(0f, scrolled())
        compose.runOnIdle { assertEquals(0, closed) }
    }

    @Test
    fun aDragAlongTheSeekBarSeeksAndNeverClosesThePlayer() {
        player()
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)).performTouchInput {
            swipe(Offset(width * 0.1f, centerY), Offset(width * 0.8f, centerY), durationMillis = 400)
        }
        compose.runOnIdle {
            assertEquals(1, seeks.size)
            assertEquals(0, closed)
        }
    }

    @Test
    fun aDragAlongTheSeekBarFollowsTheFingerPastTheSlopToWhereItLifts() {
        player()
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)).performTouchInput {
            swipe(Offset(width * 0.1f, centerY), Offset(width * 0.8f, centerY), durationMillis = 400)
        }
        compose.runOnIdle {
            // Where the finger lifted, 80% of the song in: the bar followed it all the way, not only past the slop.
            val seek = seeks.single()
            assertTrue("seeked to $seek", abs(seek - DURATION_MS * 8 / 10) <= DURATION_MS / 50)
            assertEquals(0, closed)
        }
    }

    @Test
    fun anABHandleDraggedSidewaysFollowsTheFingerAllTheWay() {
        // Review 2026-11-18: the handle stopped once the finger had passed the touch slop (its move was read after the
        // change was consumed). Dragged 40% of the bar to the right, the part's start moves 40% of the song.
        var moved = -1L
        var done = 0
        compose.setContent {
            LyrifyTheme {
                Box(Modifier.width(BAR)) {
                    val width = with(LocalDensity.current) { BAR.toPx() }
                    PartHandle(start = true, ms = PART_START_MS, durationMs = DURATION_MS, width = width, onDrag = { moved = it }, onDone = { done++ }, onSet = {})
                }
            }
        }
        compose.onNodeWithContentDescription(PART_START).performTouchInput {
            val by = with(compose.density) { BAR.toPx() } * 0.4f
            swipe(center, center + Offset(by, 0f), durationMillis = 400)
        }
        compose.runOnIdle {
            assertTrue("moved to $moved", abs(moved - (PART_START_MS + DURATION_MS * 4 / 10)) <= DURATION_MS / 50)
            assertEquals(1, done)
        }
    }

    @Test
    fun aDragDownFromASeekButtonClosesThePlayerWithNoJump() {
        player()
        val pull = pull()
        compose.onNodeWithContentDescription(BACK).performTouchInput { drag(center, center + Offset(0f, pull)) }
        compose.runOnIdle {
            assertEquals(1, closed)
            // Review 2026-11-17: it used to jump once at the press before the player took the drag.
            assertEquals(0, jumps)
        }
    }

    @Test
    fun aTapOnASeekButtonJumpsOnceAtTheLift() {
        player()
        compose.onNodeWithContentDescription(FORWARD).performTouchInput { click(center) }
        compose.runOnIdle {
            assertEquals(1, jumps)
            assertEquals(0, closed)
        }
    }

    @Test
    fun aMostlyDownDragOnTheSeekBarWithSomeWobbleClosesThePlayerWithNoSeek() {
        player()
        val pull = pull()
        // Down, and sideways at 0.7 of that: the wobble alone passes the touch slop before the player's 12 dp lock.
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)).performTouchInput {
            val from = Offset(width * 0.1f, centerY)
            drag(from, from + Offset(pull * 0.7f, pull))
        }
        compose.runOnIdle {
            assertEquals(1, closed)
            assertTrue("seeks $seeks", seeks.isEmpty())
        }
    }

    @Test
    fun aSidewaysSwipeOnTheCoverSkipsAndNeverClosesThePlayer() {
        player()
        compose.onNodeWithTag(COVER_TAG).performTouchInput { swipe(Offset(width * 0.9f, centerY), Offset(width * 0.1f, centerY + 8f), durationMillis = 300) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, skips)
            assertEquals(0, closed)
        }
    }

    @Test
    fun talkBacksDismissIsOnTheDownButtonItFocusesAndClosesThePlayerANamedPane() {
        player()
        // Review 2026-11-17: it sat on the player's unnamed frame, which TalkBack never focuses. Now on the one node that
        // has it, the down button: labelled and clickable, so TalkBack lands on it.
        val dismiss = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss))
        dismiss.assert(hasClickAction()).assert(hasContentDescription(CLOSE))
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.PaneTitle)).assert(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, PANE))
        dismiss.performSemanticsAction(SemanticsActions.Dismiss)
        compose.runOnIdle { assertEquals(1, closed) }
    }

    /** A pull past a quarter of the player's height, at a fling's pace: either alone closes it. */
    private fun pull(): Float = compose.onRoot().fetchSemanticsNode().size.height * 0.32f

    /** A finger's drag from [from] to [to], at a finger's pace. */
    private fun TouchInjectionScope.drag(from: Offset, to: Offset) {
        down(from)
        steps(from, to)
        up()
    }

    /** The finger moved from [from] to [to] in [STEPS] moves, as a real finger reports them. */
    private fun TouchInjectionScope.steps(from: Offset, to: Offset) {
        for (step in 1..STEPS) moveTo(lerp(from, to, step / STEPS.toFloat()))
    }

    private fun ui(): PlayerUi {
        val songs = listOf("Paper Lantern", "Harbour Glass").map { SongRef("/Music/KJB test/$it.mp3") }
        val book = QueueBook().play(songs, 0, QueueSource(SourceKind.ALBUM, "album:x", "Harbour Demo"), now = 1).book
        val queue = book.current!!
        return PlayerUi(book, queue, queue.current!!, song = null, playing = false, positionMs = 0, at = 0, speed = 1f, durationMs = DURATION_MS, repeat = Repeat.OFF, library = null)
    }

    private companion object {
        const val TITLE = "Paper Lantern"
        const val BACK = "Back 10 seconds"
        const val FORWARD = "Forward 10 seconds"
        const val PREVIOUS = "Previous"
        const val NEXT = "Next"
        const val CLOSE = "Close the player"
        const val PANE = "Player"
        const val PART_START = "Part start, A"
        const val DURATION_MS = 200_000L
        const val PART_START_MS = 50_000L
        val BAR = 320.dp
        const val COVER_TAG = "cover"
        const val LYRICS = "lyrics"
        const val STEPS = 12
        val COVER = 160.dp
        val AREA = 312.dp
    }
}
