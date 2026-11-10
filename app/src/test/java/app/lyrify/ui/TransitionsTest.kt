package app.lyrify.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import app.lyrify.settings.DockTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How KJB's screens change, as pure rules (motion spec, sections 1 and 3): which way the page layer moves for each change
 * of the stack, the views kept while they leave, how the player arrives, the bottom chrome's slot, how far a back swipe
 * or a drag down has taken a layer, when the drag closes the player, a dialog's action after its exit, and what reduced
 * motion makes of every transition. Made-up names only.
 */
class TransitionsTest {
    private val album = Page.Album("harbour-glass")
    private val artist = Page.Artist("Paper Lanterns")

    private fun layer(state: NavState) = pageLayerOf(state)

    // The page layer: which change, which way

    @Test
    fun `opening a page pushes it, Back pops it, and the stack decides, not the tab`() {
        val root = NavState.start(DockTab.LIBRARY)
        val one = root.open(album)
        val two = one.open(artist)
        assertEquals(PageChange.Push, pageChange(layer(root), layer(one)))
        assertEquals(PageChange.Push, pageChange(layer(one), layer(two)))
        assertEquals(PageChange.Pop, pageChange(layer(two), layer(two.back()!!)))
        assertEquals(PageChange.Pop, pageChange(layer(one), layer(one.back()!!)))
        // Nothing moved: the same layer.
        assertEquals(PageChange.None, pageChange(layer(one), layer(one.show(FullScreen.Player))))
    }

    @Test
    fun `the dock tapped again on a deep stack fades back to the root, and another tab comes from its side`() {
        val deep = NavState.start(DockTab.LIBRARY).open(album).open(artist)
        assertEquals(PageChange.Reset, pageChange(layer(deep), layer(deep.select(DockTab.LIBRARY))))
        assertEquals(PageChange.Tab(1), pageChange(layer(deep), layer(deep.select(DockTab.TOOLS))))
        val tools = NavState.start(DockTab.TOOLS)
        assertEquals(PageChange.Tab(-1), pageChange(layer(tools), layer(tools.select(DockTab.LIBRARY))))
        // Back through the tab history comes from the side of the tab it lands on.
        val visited = NavState.start(DockTab.LIBRARY).select(DockTab.SEARCH)
        assertEquals(PageChange.Tab(-1), pageChange(layer(visited), layer(visited.back()!!)))
    }

    @Test
    fun `a page opened from the player goes back across tabs as a tab change`() {
        val player = NavState.start(DockTab.SEARCH).show(FullScreen.Player)
        val opened = player.open(album)
        val back = opened.back()!!
        assertEquals(listOf<FullScreen>(FullScreen.Player), back.views)
        assertEquals(PageChange.Tab(tabSlide(DockTab.LIBRARY, DockTab.SEARCH)), pageChange(layer(opened), layer(back)))
    }

    @Test
    fun `a push comes from the end and a pop from the start, mirrored right to left`() {
        assertEquals(1, enterSide(PageChange.Push, rtl = false))
        assertEquals(-1, enterSide(PageChange.Pop, rtl = false))
        assertEquals(-1, enterSide(PageChange.Push, rtl = true))
        assertEquals(1, enterSide(PageChange.Pop, rtl = true))
        assertEquals(1, enterSide(PageChange.Tab(1), rtl = false))
        assertEquals(-1, enterSide(PageChange.Tab(1), rtl = true))
        assertEquals(0, enterSide(PageChange.Reset, rtl = false))
        assertEquals(0, enterSide(PageChange.None, rtl = true))
    }

    @Test
    fun `a page layer's key is its saved-state key`() {
        val state = NavState.start(DockTab.LIBRARY).open(album)
        assertEquals(pageKey(DockTab.LIBRARY, listOf(album)), layer(state).key)
        assertNotEquals(layer(state).key, layer(state.open(artist)).key)
    }

    // The views

    @Test
    fun `a view that closes stays drawn where it was until it has left, and new views go on top`() {
        val player = FullScreen.Player
        val eq = FullScreen.Equalizer
        val lyrics = FullScreen.FullLyrics
        assertEquals(listOf(player, eq), drawnViews(drawn = listOf(player), shown = listOf(player, eq)))
        // The equalizer closes: it is still drawn, over the player, while it leaves.
        assertEquals(listOf(player, eq), drawnViews(drawn = listOf(player, eq), shown = listOf(player)))
        assertEquals(listOf(eq), leavingViews(drawn = listOf(player, eq), shown = listOf(player)))
        // Full lyrics opens while it leaves: on top of both.
        assertEquals(listOf(player, eq, lyrics), drawnViews(drawn = listOf(player, eq), shown = listOf(player, lyrics)))
        assertEquals(emptyList<FullScreen>(), leavingViews(drawn = listOf(player), shown = listOf(player)))
    }

    @Test
    fun `the player grows out of the Now bar when the bar shows under it, every other view rises`() {
        assertEquals(ViewMotion.CONTAINER, viewMotion(FullScreen.Player, barShown = true))
        assertEquals(ViewMotion.CONTAINER, viewMotion(FullScreen.CompanionNow, barShown = true))
        assertEquals(ViewMotion.RISE, viewMotion(FullScreen.Player, barShown = false))
        assertEquals(ViewMotion.RISE, viewMotion(FullScreen.Equalizer, barShown = true))
        assertEquals(ViewMotion.RISE, viewMotion(FullScreen.FullLyrics, barShown = true))
    }

    // The bottom chrome

    @Test
    fun `the selection bar takes the Now bar's and the dock's place, and nothing shows over the keyboard or a full screen`() {
        assertEquals(BottomSlot.BAR_AND_DOCK, bottomSlot(selecting = false, chrome = true, ime = false))
        assertEquals(BottomSlot.SELECTION, bottomSlot(selecting = true, chrome = true, ime = false))
        assertEquals(BottomSlot.NONE, bottomSlot(selecting = true, chrome = true, ime = true))
        assertEquals(BottomSlot.NONE, bottomSlot(selecting = false, chrome = false, ime = false))
    }

    // A back swipe and a drag down

    @Test
    fun `a back swipe shrinks the layer to 90 percent, rounds it, follows the finger and lights the layer under it`() {
        val rest = backPeek(0f, fromLeft = true, width = 1000f)
        assertEquals(1f, rest.scale, 0.0001f)
        assertEquals(0f, rest.shiftX, 0.0001f)
        assertEquals(0f, rest.corner, 0.0001f)
        assertEquals(DIM_UNDER, rest.dim, 0.0001f)
        val full = backPeek(1f, fromLeft = true, width = 1000f)
        assertEquals(BACK_SCALE, full.scale, 0.0001f)
        assertEquals(1f, full.corner, 0.0001f)
        assertEquals(0f, full.dim, 0.0001f)
        assertTrue(full.shiftX > 0f)
        // From the right edge it moves left, as far.
        assertEquals(-full.shiftX, backPeek(1f, fromLeft = false, width = 1000f).shiftX, 0.0001f)
        // Clamped past either end, and steady in between.
        assertEquals(full, backPeek(1.7f, fromLeft = true, width = 1000f))
        assertEquals(rest, backPeek(-0.3f, fromLeft = true, width = 1000f))
        val steps = (0..10).map { backPeek(it / 10f, fromLeft = true, width = 1000f) }
        assertEquals(steps.map { it.scale }.sortedDescending(), steps.map { it.scale })
        assertEquals(steps.map { it.shiftX }.sorted(), steps.map { it.shiftX })
        assertEquals(steps.map { it.dim }.sortedDescending(), steps.map { it.dim })
    }

    @Test
    fun `the player dragged down follows the finger, shrinks to 92 percent by half the height, and never goes up`() {
        val height = 2000f
        assertEquals(Peek(1f, 0f, 0f, 0f, DIM_UNDER), dragPeek(0f, height))
        assertEquals(Peek(1f, 0f, 0f, 0f, DIM_UNDER), dragPeek(-80f, height))
        val half = dragPeek(height / 2, height)
        assertEquals(height / 2, half.shiftY, 0.0001f)
        assertEquals(DRAG_SCALE, half.scale, 0.0001f)
        assertEquals(1f, half.corner, 0.0001f)
        assertEquals(DRAG_SCALE, dragPeek(height, height).scale, 0.0001f)
        assertTrue(dragPeek(200f, height).scale in DRAG_SCALE..1f)
    }

    @Test
    fun `letting go closes the player past a quarter of its height or on a quick fling down, else it springs back`() {
        val height = 2000f
        val fling = 2400f
        assertFalse(dragCloses(offset = 499f, velocity = 0f, height = height, fling = fling))
        assertTrue(dragCloses(offset = 501f, velocity = 0f, height = height, fling = fling))
        assertTrue(dragCloses(offset = 60f, velocity = 2500f, height = height, fling = fling))
        // A fling back up, or a fling that never went down, does not close it.
        assertFalse(dragCloses(offset = 300f, velocity = -3000f, height = height, fling = fling))
        assertFalse(dragCloses(offset = 0f, velocity = 3000f, height = height, fling = fling))
    }

    // Dialogs: the action after the exit (decision D5)

    @Test
    fun `a dialog's action runs once, only when its exit has ended, and a second tap while it leaves is ignored`() {
        val exit = DialogExit()
        val ran = mutableListOf<String>()
        assertFalse(exit.leaving)
        assertTrue(exit.leave { ran += "confirm" })
        assertTrue(exit.leaving)
        assertEquals(emptyList<String>(), ran)
        assertFalse(exit.leave { ran += "cancel" })
        exit.ended()
        assertEquals(listOf("confirm"), ran)
        exit.ended()
        assertEquals(listOf("confirm"), ran)
    }

    // Reduced motion (decision D4) and the tokens

    @Test
    fun `with reduced motion every page, tab, view and dialog change is a 100 ms fade`() {
        val fadeIn: EnterTransition = fadeIn(tween(Motion.REDUCED_MS))
        val fadeOut: ExitTransition = fadeOut(tween(Motion.REDUCED_MS))
        listOf(PageChange.Push, PageChange.Pop, PageChange.Reset, PageChange.Tab(1)).forEach { change ->
            val reduced = pageTransition(change, rtl = false, reduced = true)
            assertEquals(fadeIn, reduced.targetContentEnter)
            assertEquals(fadeOut, reduced.initialContentExit)
            // Without reduced motion, a push or a pop also moves.
            if (change == PageChange.Push || change == PageChange.Pop) assertNotEquals(fadeIn, pageTransition(change, rtl = false, reduced = false).targetContentEnter)
        }
        assertEquals(fadeIn, viewEnter(ViewMotion.RISE, reduced = true))
        assertEquals(fadeOut, viewExit(ViewMotion.RISE, reduced = true))
        assertEquals(fadeIn, viewEnter(ViewMotion.CONTAINER, reduced = true))
        assertEquals(fadeOut, viewExit(ViewMotion.CONTAINER, reduced = true))
        assertEquals(fadeIn, dialogEnter(reduced = true))
        assertEquals(fadeOut, dialogExit(reduced = true))
        assertNotEquals(fadeIn, viewEnter(ViewMotion.RISE, reduced = false))
    }

    @Test
    fun `a pop draws the page it goes back to under the one leaving, a push over it`() {
        assertTrue(pageTransition(PageChange.Pop, rtl = false, reduced = false).targetContentZIndex < 0f)
        assertEquals(0f, pageTransition(PageChange.Push, rtl = false, reduced = false).targetContentZIndex)
        assertTrue(backTransition(side = -1, reduced = false).targetContentZIndex < 0f)
    }

    @Test
    fun `the motion tokens keep One UI's bounds, 100 to 500 ms, apart from the container transform's larger area`() {
        val tokens = listOf(Motion.QUICK_MS, Motion.SHORT_MS, Motion.SETTLE_MS, Motion.DIALOG_MS, Motion.ARRIVE_MS, Motion.OPEN_MS, Motion.FLIP_MS, Motion.FADE_IN_MS, Motion.REDUCED_MS)
        tokens.forEach { assertTrue("$it ms", it in 100..500) }
        assertEquals(500, Motion.FLIP_MS)
        assertTrue(Motion.FADE_OUT_MS < Motion.FADE_IN_MS)
        assertTrue(Motion.CONTAINER_MS > Motion.OPEN_MS)
    }

    // The queue's handle: a held row takes its neighbours' places as it passes them

    @Test
    fun `a held row takes a neighbour's place past half of it, keeps the rest of its offset, and stops at the ends`() {
        val row = 100f
        assertEquals(2 to 40f, reorderStep(index = 2, size = 5, offset = 40f, row = row))
        assertEquals(3 to -40f, reorderStep(index = 2, size = 5, offset = 60f, row = row))
        assertEquals(4 to -40f, reorderStep(index = 2, size = 5, offset = 160f, row = row))
        assertEquals(1 to 40f, reorderStep(index = 2, size = 5, offset = -60f, row = row))
        // Nowhere further to go: it stays, the finger's offset with it.
        assertEquals(4 to 160f, reorderStep(index = 4, size = 5, offset = 160f, row = row))
        assertEquals(0 to -60f, reorderStep(index = 0, size = 5, offset = -60f, row = row))
        assertEquals(0 to 0f, reorderStep(index = 0, size = 1, offset = 0f, row = row))
    }

    // Review M2: who takes touches, the chrome under a container transform, a swipe's own cleanup, the drag's feedback

    @Test
    fun `only a layer arriving or resting takes touches, never one leaving, and a container only once it has landed`() {
        assertTrue(takesInput(headed = true, settled = true, container = false))
        assertTrue(takesInput(headed = true, settled = false, container = false))
        assertFalse(takesInput(headed = false, settled = false, container = false))
        assertFalse(takesInput(headed = false, settled = true, container = false))
        // Growing out of the Now bar or back into it, the player is drawn where its layout is not.
        assertFalse(takesInput(headed = true, settled = false, container = true))
        assertFalse(takesInput(headed = false, settled = false, container = true))
        assertTrue(takesInput(headed = true, settled = true, container = true))
    }

    @Test
    fun `the Now bar stays put while it grows into the player or the player shrinks into it, and slides only for a view that rises`() {
        assertTrue(chromeStays(FullScreen.Player, barShown = true))
        assertTrue(chromeStays(FullScreen.CompanionNow, barShown = true))
        assertFalse(chromeStays(FullScreen.Player, barShown = false))
        assertFalse(chromeStays(FullScreen.Equalizer, barShown = true))
        assertFalse(chromeStays(null, barShown = true))
    }

    @Test
    fun `a back swipe's cleanup clears only its own swipe, never the next one`() {
        val swipe = BackSwipe()
        val first = BackTaking.Page("LIBRARY/1")
        swipe.start(first)
        // Given up, then the same page swiped again: an equal taking, but a new swipe.
        val second = BackTaking.Page("LIBRARY/1")
        swipe.start(second)
        swipe.clear(first)
        assertSame(second, swipe.taking)
        assertFalse(swipe.owns(first))
        assertTrue(swipe.owns(second))
        swipe.clear(second)
        assertEquals(null, swipe.taking)
    }

    @Test
    fun `let go short of closing the player springs back, closing into the bar lets go, closing as it falls keeps going`() {
        assertEquals(0f, dragRelease(closes = false, intoBar = true))
        assertEquals(0f, dragRelease(closes = false, intoBar = false))
        assertEquals(0f, dragRelease(closes = true, intoBar = true))
        // Falling away, the player leaves from where the finger left it.
        assertEquals(null, dragRelease(closes = true, intoBar = false))
    }

    @Test
    fun `with reduced motion a drag down on the player fades it as it goes, so it never closes unseen`() {
        val height = 2000f
        assertEquals(1f, reducedDragAlpha(0f, height), 0.0001f)
        assertEquals(1f, reducedDragAlpha(-50f, height), 0.0001f)
        assertTrue(reducedDragAlpha(height * 0.25f, height) < 1f)
        assertEquals(REDUCED_DRAG_ALPHA, reducedDragAlpha(height, height), 0.0001f)
    }

    @Test
    fun `the container's corners are read when drawn, so one shape serves the whole transform`() {
        var corner = 32f
        val shape = CornerShape { corner }
        val density = Density(1f)
        fun radius() = (shape.createOutline(Size(400f, 800f), LayoutDirection.Ltr, density) as Outline.Rounded).roundRect.topLeftCornerRadius.x
        assertEquals(32f, radius(), 0.0001f)
        corner = 0f
        assertEquals(0f, radius(), 0.0001f)
    }

    // Phone test 2026-11-10: a back swipe given up, slow or fast, leaves the page as it was; only the platform commits

    private val albumPage = PageLayer(DockTab.LIBRARY, listOf(Page.Album("harbour-glass")))
    private val libraryRoot = PageLayer(DockTab.LIBRARY, emptyList())

    @Test
    fun `a back swipe given up slowly, held and brought back to the edge, leaves the page current and at rest`() {
        var swipe = PageSwipe(albumPage).started(libraryRoot)
        listOf(0.05f, 0.2f, 0.3f, 0.3f, 0.3f, 0.3f, 0.2f, 0.08f, 0.01f).forEach { swipe = swipe.moved(it) }
        val rest = swipe.cancelled()
        assertEquals(PageSwipe(albumPage), rest)
        assertTrue(rest.resting)
        assertEquals(albumPage, rest.current)
    }

    @Test
    fun `a back swipe given up fast leaves the page current and at rest too`() {
        val rest = PageSwipe(albumPage).started(libraryRoot).moved(0.3f).cancelled()
        assertEquals(PageSwipe(albumPage), rest)
        // Given up before any move at all.
        assertEquals(PageSwipe(albumPage), PageSwipe(albumPage).started(libraryRoot).cancelled())
    }

    @Test
    fun `only the platform's completion commits a back swipe, and a move after either end changes nothing`() {
        val done = PageSwipe(albumPage).started(libraryRoot).moved(0.4f).committed()
        assertEquals(PageSwipe(libraryRoot), done)
        assertEquals(done, done.moved(0.7f))
        val rest = PageSwipe(albumPage).started(libraryRoot).moved(0.4f).cancelled()
        assertEquals(rest, rest.moved(0.7f))
        // A move is clamped while it lasts.
        assertEquals(1f, PageSwipe(albumPage).started(libraryRoot).moved(1.6f).progress, 0f)
    }

    @Test
    fun `the Now bar stays in place under the growing player, which covers it and the dock from the first frame`() {
        assertFalse(containerInOverlay(bar = true))
        assertTrue(containerInOverlay(bar = false))
    }
}
