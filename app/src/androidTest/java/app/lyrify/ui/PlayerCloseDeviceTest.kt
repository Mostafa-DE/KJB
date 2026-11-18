package app.lyrify.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.lyrify.data.LedgerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * The player closing into the Now bar on the phone, frame by frame with the test clock held (phone test 2026-11-17: let
 * go, it reached the bar in 3 to 6 frames, and its cover turned round in one frame while still large). Inside the
 * shell's two ends, the player's own frame ([PlayerFrame]) with a cover on the container's cover: a drag let go past
 * the threshold, or flung, carries on from the finger into the bar, its content and its cover moving a little each
 * frame, the cover rounding as it shrinks, settled in time; a fling's fades and content ahead of a close from rest; the
 * chevron closes the same way from rest; a short drag springs back the same way. The corner is the one each frame drew
 * (review 2026-11-18), never worked out again here. Made-up names only.
 */
@RunWith(AndroidJUnit4::class)
class PlayerCloseDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private var open by mutableStateOf(true)

    /**
     * One frame: where the player's content starts, the cover's side and the corner it is drawn with, in px; and the
     * shell's fades as that frame drew them: the content's alpha, the share of its dim the layer under keeps, the card's fill.
     */
    private data class Look(val top: Float, val side: Float, val corner: Float, val alpha: Float, val dim: Float, val fill: Float)

    // The fades as the last frame drew them ([Container.along]): written on the main thread, read from the test's.
    @Volatile private var drawnAlpha = 1f

    @Volatile private var drawnDim = 1f

    @Volatile private var drawnFill = 1f

    private var height = 0f

    /**
     * The cover's shape, telling each outline it gives: its corner radius by the width it was drawn at, in px. Drawn on
     * the main thread, read from the test's.
     */
    private class DrawnCorners(private val shape: Shape) : Shape {
        val radii = ConcurrentHashMap<Float, Float>()

        override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
            shape.createOutline(size, layoutDirection, density).also { radii[size.width] = (it as Outline.Rounded).roundRect.topLeftCornerRadius.x }
    }

    private val drawn = DrawnCorners(CoverShape(CORNER, SIDE))
    private var sidePx = 0f
    private var orbPx = 0f
    private var cornerPx = 0f

    private fun shell() {
        compose.setContent {
            LyrifyTheme {
                CompositionLocalProvider(LocalToasts provides remember { ToastState() }, LocalReducedMotion provides false) {
                    SharedTransitionLayout {
                        val barTop = remember { Kept(Float.NaN) }
                        Box(Modifier.fillMaxSize()) {
                            // The bar's end, over where the dock would be, only while the player is closed or closing.
                            AnimatedVisibility(!open, Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp), enter = EnterTransition.None, exit = ExitTransition.None) {
                                val bar = remember(this) { Container(this@SharedTransitionLayout, this, bar = true, barTop) }
                                CompositionLocalProvider(LocalContainer provides bar) {
                                    NowBar(NowBarShows.Own(OwnNow(TITLE, "The Lamplighters", cover = null, playing = false, progress = 0.25f)), LedgerState(), other = null, onOpen = { open = true }, onPlayPause = {})
                                }
                            }
                            // The player's end, as the shell's views draw it: the layer under it lights up, the card clears and
                            // the content fades as it closes (ViewLayers).
                            AnimatedVisibility(open, enter = EnterTransition.None, exit = ExitTransition.None) {
                                val closing = closeProgress(this)
                                val player = remember(this) { Container(this@SharedTransitionLayout, this, bar = false, barTop) { closing.value } }
                                Box(
                                    Modifier.fillMaxSize().drawBehind {
                                        drawnDim = closeDimShare(player.along())
                                        drawRect(Color.Black, alpha = DIM_UNDER * drawnDim)
                                    },
                                )
                                Box(
                                    Modifier.fillMaxSize().then(containerBounds(player)).drawBehind {
                                        drawnFill = closeFillAlpha(player.along())
                                        drawRect(Color.DarkGray, alpha = drawnFill)
                                    },
                                ) {
                                    Box(
                                        Modifier.graphicsLayer {
                                            drawnAlpha = closeContentAlpha(player.along())
                                            alpha = drawnAlpha
                                        },
                                    ) {
                                        CompositionLocalProvider(LocalContainer provides player) {
                                            PlayerFrame(onClose = { open = false }) {
                                                Column(Modifier.fillMaxSize().testTag(CONTENT).padding(24.dp)) {
                                                    Box(Modifier.size(48.dp).clickable { open = false }.semantics { contentDescription = CHEVRON })
                                                    Text(SONG, Modifier.fillMaxWidth().padding(vertical = 16.dp))
                                                    SongCover(null, containerCover(player).testTag(COVER), size = SIDE, shape = drawn)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        height = compose.onRoot().fetchSemanticsNode().size.height.toFloat()
        with(compose.density) {
            sidePx = SIDE.toPx()
            orbPx = ORB_COVER.toPx()
            cornerPx = CORNER.toPx()
        }
    }

    /**
     * The player as this frame draws it, or null once it has gone into the bar. The cover's corner is read from the
     * outline its clip was drawn with at this frame's size (the frame drawn first), scaled as the cover is drawn.
     */
    private fun look(): Look? {
        if (compose.onAllNodesWithTag(COVER).fetchSemanticsNodes().isEmpty()) return null
        compose.onRoot().captureToImage()
        val content = compose.onAllNodesWithTag(CONTENT).fetchSemanticsNodes().firstOrNull() ?: return null
        val cover = compose.onAllNodesWithTag(COVER).fetchSemanticsNodes().firstOrNull() ?: return null
        val laidOut = cover.size.width.toFloat()
        val radius = drawn.radii[laidOut]
        assertNotNull("the cover was not drawn at its size, $laidOut px", radius)
        val side = cover.boundsInRoot.width
        return Look(content.boundsInRoot.top, side, radius!! * side / laidOut, drawnAlpha, drawnDim, drawnFill)
    }

    /** Every frame from now until the player has gone (or [MAX_FRAMES]), the clock held. */
    private fun frames(): List<Look> {
        val looks = mutableListOf<Look>()
        repeat(MAX_FRAMES) {
            compose.mainClock.advanceTimeByFrame()
            looks += look() ?: return looks
        }
        return looks
    }

    /** No frame moves the content or the cover's corner by more than a small step: a motion, never a cut. */
    private fun assertNoJump(looks: List<Look>) {
        looks.zipWithNext().forEachIndexed { frame, (a, b) ->
            assertTrue("frame $frame: content ${a.top} to ${b.top}", abs(b.top - a.top) <= height * STEP)
            assertTrue("frame $frame: corner ${a.corner} to ${b.corner}", abs(b.corner - a.corner) <= sidePx / 2 * STEP)
            assertTrue("frame $frame: cover ${a.side} to ${b.side}", abs(b.side - a.side) <= sidePx * STEP)
        }
    }

    /** How many frames until the cover is the orb's size for good. */
    private fun settledFrames(looks: List<Look>): Int = looks.indexOfLast { abs(it.side - orbPx) > (sidePx - orbPx) * SETTLED } + 1

    @Test
    fun aDragLetGoCarriesOnFromTheFingerIntoTheBarWithNoCutAndTheCoverRoundingAsItShrinks() {
        shell()
        compose.mainClock.autoAdvance = false
        val song = compose.onNodeWithText(SONG)
        val pull = height * PULL
        // The finger down and across, then the last look before it lifts.
        song.performTouchInput {
            down(center)
            steps(center, center + Offset(0f, pull))
        }
        val held = look()!!
        song.performTouchInput { up() }
        val looks = listOf(held) + frames()
        assertEquals(false, open)
        assertNoJump(looks)
        // The cover only ever shrinks, from the size the drag left it to the orb's, rounding to its circle.
        looks.zipWithNext().forEach { (a, b) -> assertTrue("cover ${a.side} to ${b.side}", b.side <= a.side + 1f) }
        assertEquals(orbPx / 2, looks.last().corner, 2f)
        // Settled within 450 ms, and never in less than 220.
        val settled = settledFrames(looks) * FRAME_MS
        assertTrue("settled at $settled ms", settled in 220..450 + 2 * FRAME_MS)
    }

    @Test
    fun aFlingLetGoCarriesOnAtItsSpeedIntoTheBarAheadOfAChevronCloseWithNoCut() {
        shell()
        compose.mainClock.autoAdvance = false
        // The chevron's close from rest first, to hold the fling against; then the player opened again.
        val rest = look()!!
        compose.onNodeWithContentDescription(CHEVRON).performClick()
        val chevron = listOf(rest) + frames()
        reopen()
        val song = compose.onNodeWithText(SONG)
        val pull = height * PULL
        // The same pull in a few frames: as fast as the transform takes a finger (CLOSE_MOMENTUM_DP), or faster.
        song.performTouchInput {
            down(center)
            for (step in 1..FLING_STEPS) moveTo(lerp(center, center + Offset(0f, pull), step / FLING_STEPS.toFloat()))
        }
        val held = look()!!
        song.performTouchInput { up() }
        val looks = listOf(held) + frames()
        assertEquals(false, open)
        // Handed over at its speed, still a move: no frame takes anything more than a small step, nor past the orb.
        assertNoJump(looks)
        looks.zipWithNext().forEach { (a, b) -> assertTrue("cover ${a.side} to ${b.side}", b.side <= a.side + 1f) }
        assertTrue(looks.all { it.side >= orbPx - 1f })
        assertEquals(orbPx / 2, looks.last().corner, 2f)
        val settled = settledFrames(looks) * FRAME_MS
        assertTrue("settled at $settled ms", settled in 220..450 + 2 * FRAME_MS)
        // Review 2026-11-18: the finger's speed reaches the fades and the content, not only the card. Frame for frame
        // after the lift they are never behind the chevron's from rest: the content no less faded, the layer under no
        // less lit, the card no less clear. A few frames in, the content has faded and the layer lit further, and the
        // content has come further along its way to the bar (as a share: dragged, it starts nearer the bar).
        looks.zip(chevron).drop(1).forEachIndexed { i, (flung, still) ->
            assertTrue("frame ${i + 1}: alpha ${flung.alpha}, chevron ${still.alpha}", flung.alpha <= still.alpha + 0.001f)
            assertTrue("frame ${i + 1}: dim ${flung.dim}, chevron ${still.dim}", flung.dim <= still.dim + 0.001f)
            assertTrue("frame ${i + 1}: fill ${flung.fill}, chevron ${still.fill}", flung.fill <= still.fill + 0.001f)
        }
        for (frame in 1..AHEAD_FRAMES) assertTrue("frame $frame: content", along(looks, frame) >= along(chevron, frame) - 0.001f)
        assertTrue("alpha ${looks[AHEAD_FRAMES].alpha}, chevron ${chevron[AHEAD_FRAMES].alpha}", looks[AHEAD_FRAMES].alpha < chevron[AHEAD_FRAMES].alpha)
        assertTrue("dim ${looks[AHEAD_FRAMES].dim}, chevron ${chevron[AHEAD_FRAMES].dim}", looks[AHEAD_FRAMES].dim < chevron[AHEAD_FRAMES].dim)
        assertTrue("content", along(looks, AHEAD_FRAMES) > along(chevron, AHEAD_FRAMES))
    }

    /** The player open again after it went into the bar, the transform run to its end; the clock held again after. */
    private fun reopen() {
        compose.mainClock.autoAdvance = true
        compose.runOnIdle { open = true }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
    }

    /** How far along its way to the bar the content is at [frame] of [looks]: from where it started to where it ended. */
    private fun along(looks: List<Look>, frame: Int): Float = (looks[frame].top - looks.first().top) / (looks.last().top - looks.first().top)

    @Test
    fun theChevronClosesTheSameWayFromRest() {
        shell()
        compose.mainClock.autoAdvance = false
        val rest = look()!!
        compose.onNodeWithContentDescription(CHEVRON).performClick()
        val looks = listOf(rest) + frames()
        assertNoJump(looks)
        // From rest it starts gently: half way to the orb no sooner than five frames.
        val half = looks.indexOfFirst { it.side <= (sidePx + orbPx) / 2 }
        assertTrue("half way at frame $half", half >= 5)
        val settled = settledFrames(looks) * FRAME_MS
        assertTrue("settled at $settled ms", settled in 220..450 + 2 * FRAME_MS)
    }

    @Test
    fun aShortDragLetGoSpringsBackTheSameWayAndTheCoverStaysItsOwnShape() {
        shell()
        val rest = look()!!
        compose.mainClock.autoAdvance = false
        val song = compose.onNodeWithText(SONG)
        song.performTouchInput {
            down(center)
            steps(center, center + Offset(0f, height * SHORT_PULL))
        }
        val held = look()!!
        song.performTouchInput { up() }
        val looks = listOf(held) + (1..MAX_FRAMES).map {
            compose.mainClock.advanceTimeByFrame()
            look()!!
        }
        assertEquals(true, open)
        assertNoJump(looks)
        val home = looks.indexOfFirst { abs(it.top - rest.top) <= 1f }
        assertTrue("home at frame $home", home in 1..(450 / FRAME_MS + 2))
        assertEquals(cornerPx, looks.last().corner, 1f)
    }

    /** A finger's move from [from] to [to], at a finger's pace (a frame apart). */
    private fun TouchInjectionScope.steps(from: Offset, to: Offset) {
        for (step in 1..STEPS) moveTo(lerp(from, to, step / STEPS.toFloat()))
    }

    private companion object {
        const val TITLE = "Quiet Engines"
        const val SONG = "Harbour Glass"
        const val CHEVRON = "Close the player"
        const val CONTENT = "content"
        const val COVER = "cover"
        val SIDE = 220.dp
        val CORNER = 28.dp

        /** Past the quarter that closes it, at a finger's pace (12 moves a frame apart). */
        const val PULL = 0.32f
        const val SHORT_PULL = 0.1f
        const val STEPS = 12
        const val FLING_STEPS = 4

        /** The frame after the lift by which a fling has visibly gone further than a close from rest. */
        const val AHEAD_FRAMES = 3

        /** The most a frame may move anything, as a share of its whole way. */
        const val STEP = 0.14f
        const val SETTLED = 0.01f
        const val FRAME_MS = 16
        const val MAX_FRAMES = 60
    }
}
