package app.lyrify.ui

import android.content.ContextWrapper
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.VectorConverter
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import coil3.memory.MemoryCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The player closing into the Now bar (phone test 2026-11-17: let go, it reached the bar in 3 to 6 frames, a cut, and
 * its cover turned round in one frame while still large): one spring from wherever it was let go, the finger's speed
 * carried in; what fades over which part of the way; the cover rounding with its size; the bar's bitmap under the cover
 * that grows out of it. Made-up names only.
 */
class PlayerCloseTest {
    // The spring, as the shared transition runs it: the card's bounds from rest, plus the finger's momentum on the same
    // spring (SharedContentState.prepareTransitionWithInitialVelocity, which takes the spring of the bar's end).

    private val spring = Motion.close<Float>().vectorize(Float.VectorConverter)

    private fun spring(ms: Int, from: Float, to: Float, velocity: Float): Float =
        spring.getValueFromNanos(ms * 1_000_000L, AnimationVector1D(from), AnimationVector1D(to), AnimationVector1D(velocity)).value

    /** How far the card has come [ms] after it was let go [travel] px from the bar at [velocity] px/s toward it. */
    private fun come(ms: Int, travel: Float, velocity: Float): Float = spring(ms, 0f, travel, 0f) + spring(ms, 0f, 0f, velocity)

    /** When the card is within 1% of the way of the bar for good, in ms. */
    private fun settledMs(travel: Float, velocity: Float): Int = (0..SPAN_MS).last { abs(travel - come(it, travel, velocity)) > travel * SETTLED } + 1

    @Test
    fun `from rest, as the chevron or Back let it go, it moves over many frames and settles within 450 ms`() {
        val settled = settledMs(TRAVEL, 0f)
        assertTrue("settled at $settled ms", settled in 250..450)
        // Half way no sooner than five frames at 60 Hz: a move, never a cut (all of it took 3 to 6 frames).
        val half = (0..SPAN_MS).first { come(it, TRAVEL, 0f) >= TRAVEL / 2 }
        assertTrue("half way at $half ms", half >= 5 * FRAME_MS)
    }

    @Test
    fun `a typical release settles in 250 to 350 ms, and the hardest fling handed over no sooner than 220 ms`() {
        for (velocity in listOf(1000f, 2000f, 3000f)) {
            val settled = settledMs(TRAVEL, velocity)
            assertTrue("at $velocity px/s settled at $settled ms", settled in 250..350)
        }
        val fling = closeVelocity(30_000f, CLOSE_MOMENTUM_DP * DENSITY)
        for (travel in listOf(600f, TRAVEL)) {
            val settled = settledMs(travel, fling)
            assertTrue("$travel px at the hardest fling settled at $settled ms", settled >= 220)
            // Never past the bar: critically damped, and no faster than the bar can take.
            assertTrue((0..SPAN_MS).all { come(it, travel, fling) <= travel + 1f })
        }
    }

    @Test
    fun `let go, it carries the finger's speed, further along after a frame for a faster finger and never slower than it`() {
        var last = 0f
        for (velocity in listOf(0f, 800f, 2400f, 6000f, CLOSE_MOMENTUM_DP * DENSITY)) {
            val frame = come(FRAME_MS, TRAVEL, velocity)
            assertTrue("$velocity px/s: $frame px after a frame, $last slower", frame > last)
            assertTrue("$velocity px/s: $frame px after a frame", frame >= velocity * FRAME_MS / 1000f)
            // And never a cut: no frame takes it more than a small step of the way.
            assertTrue("$velocity px/s: $frame px after a frame", frame <= TRAVEL * 0.14f)
            last = frame
        }
    }

    @Test
    fun `the finger's speed is handed over down only, and no faster than a hard fling`() {
        val max = CLOSE_MOMENTUM_DP * DENSITY
        assertEquals(0f, closeVelocity(-800f, max), 0f)
        assertEquals(2400f, closeVelocity(2400f, max), 0f)
        assertEquals(max, closeVelocity(30_000f, max), 0f)
    }

    // One progress, 0 in the player and 1 in the bar, drives the rest

    @Test
    fun `the player's own content goes in the first half, the dim under it by 60%, and the card clears over the last 40%`() {
        assertEquals(1f, closeContentAlpha(0f), 0f)
        assertEquals(0.5f, closeContentAlpha(0.25f), 0.0001f)
        assertEquals(0f, closeContentAlpha(0.5f), 0f)
        assertEquals(1f, closeDimShare(0f), 0f)
        assertEquals(0f, closeDimShare(0.6f), 0f)
        // The card stays whole until 60%, then clears, so the bar's text and controls show through as it lands.
        assertEquals(1f, closeFillAlpha(0.6f), 0f)
        assertEquals(0.5f, closeFillAlpha(0.8f), 0.0001f)
        assertEquals(0f, closeFillAlpha(1f), 0f)
        // Each only ever goes one way as the progress does, with no step anywhere.
        val way = (0..1000).map { it / 1000f }
        for (share in listOf(::closeContentAlpha, ::closeDimShare, ::closeFillAlpha)) {
            val values = way.map(share)
            assertEquals(values.sortedDescending(), values)
            assertTrue(values.zipWithNext().all { (a, b) -> a - b <= 0.003f })
        }
    }

    @Test
    fun `the finger's drag goes as the card closes, and the cover grows back from the drag's shrink by the orb`() {
        assertEquals(300f, closeHeld(300f, 0f), 0f)
        assertEquals(150f, closeHeld(300f, 0.5f), 0.0001f)
        assertEquals(0f, closeHeld(300f, 1f), 0f)
        // The cover, drawn above everything on the way, takes the frame's scale: the drag's shrink at the lift, its own
        // size by the orb. (It took a scale of its own from the lift before review 2026-11-18; the same values.)
        val height = 2400f
        val at = height * 0.3f
        fun cover(p: Float) = overlayCoverScale(EnterExitState.Visible, EnterExitState.PostExit, running = true, dragPeek(closeHeld(at, p), height).scale)
        assertEquals(dragPeek(at, height).scale, cover(0f), 0f)
        assertEquals((dragPeek(at, height).scale + 1f) / 2, cover(0.5f), 0.0001f)
        assertEquals(1f, cover(1f), 0f)
        // Closed by the chevron or Back, nothing was shrunk.
        assertEquals(1f, overlayCoverScale(EnterExitState.Visible, EnterExitState.PostExit, running = true, dragPeek(0f, height).scale), 0f)
    }

    @Test
    fun `the cover drawn above everything keeps the frame's scale while the transform runs, shown again on the way too`() {
        val frame = 0.96f
        // Closing, from its first frame on.
        assertEquals(frame, overlayCoverScale(EnterExitState.Visible, EnterExitState.PostExit, running = false, frame), 0f)
        assertEquals(frame, overlayCoverScale(EnterExitState.Visible, EnterExitState.PostExit, running = true, frame), 0f)
        // Review 2026-11-18: shown again mid-close, the transition runs back with the same current and target state, and
        // the cover was drawn at its full size from that frame on.
        assertEquals(frame, overlayCoverScale(EnterExitState.Visible, EnterExitState.Visible, running = true, frame), 0f)
        // Growing out of the bar, nothing dragged.
        assertEquals(1f, overlayCoverScale(EnterExitState.PreEnter, EnterExitState.Visible, running = true, 1f), 0f)
        // At rest it is back in the frame, which scales it itself.
        assertEquals(1f, overlayCoverScale(EnterExitState.Visible, EnterExitState.Visible, running = false, frame), 0f)
    }

    @Test
    fun `shown again mid-close, the cover's drawn size goes home with the drag, a little each frame`() {
        val height = 2400f
        val at = height * 0.3f
        // Closing for three frames: the drag held where the finger left it, the progress from rest. Then shown again:
        // the progress goes back from there at its speed, the drag goes home from rest, and the transform runs 450 ms.
        val closedMs = 3 * FRAME_MS
        val p0 = spring(closedMs, 0f, 1f, 0f)
        val v0 = spring.getVelocityFromNanos(closedMs * 1_000_000L, AnimationVector1D(0f), AnimationVector1D(1f), AnimationVector1D(0f)).value
        val closing = (0..closedMs step FRAME_MS).map { ms ->
            overlayCoverScale(EnterExitState.Visible, EnterExitState.PostExit, running = true, dragPeek(closeHeld(at, spring(ms, 0f, 1f, 0f)), height).scale)
        }
        val back = (FRAME_MS..900 step FRAME_MS).map { ms ->
            val frame = dragPeek(closeHeld(spring(ms, at, 0f, 0f), spring(ms, p0, 0f, v0)), height).scale
            val running = ms < Motion.CONTAINER_MS
            // Above everything while it runs; back in the frame after, scaled by the frame.
            if (running) overlayCoverScale(EnterExitState.Visible, EnterExitState.Visible, running = true, frame) else frame * overlayCoverScale(EnterExitState.Visible, EnterExitState.Visible, running = false, frame)
        }
        val drawn = closing + back
        // The cover grows back as it closes, under 0.6% a frame; it used to pop by about 4% as it was shown again.
        drawn.zipWithNext().forEachIndexed { i, (a, b) -> assertTrue("frame $i: $a to $b", abs(b - a) <= 0.008f) }
        assertEquals(1f, drawn.last(), 0.0005f)
    }

    @Test
    fun `after a fling the fades keep pace with the card, as if the progress itself had taken the finger's speed`() {
        for (velocity in listOf(1000f, 3000f, CLOSE_MOMENTUM_DP * DENSITY)) {
            for (ms in 0..600 step 4) {
                // The card's own spring from rest, and Compose's momentum on the same spring (the lead, mirrored).
                val along = closeAlong(spring(ms, 0f, 1f, 0f), spring(ms, 0f, 0f, velocity), TRAVEL)
                // Where the card is drawn, as a share of its way ...
                assertEquals("$velocity px/s at $ms ms", come(ms, TRAVEL, velocity) / TRAVEL, along, 0.0005f)
                // ... is the progress driven from the finger's speed, normalised to the way (review 2026-11-18).
                assertEquals("$velocity px/s at $ms ms", spring(ms, 0f, 1f, velocity / TRAVEL), along, 0.0005f)
            }
            // A frame after the lift, the content has gone further than from rest.
            val frame = closeAlong(spring(FRAME_MS, 0f, 1f, 0f), spring(FRAME_MS, 0f, 0f, velocity), TRAVEL)
            assertTrue("$velocity px/s", closeContentAlpha(frame) < closeContentAlpha(spring(FRAME_MS, 0f, 1f, 0f)))
        }
        // The chevron and Back carry nothing: the card's own progress.
        assertEquals(0.3f, closeAlong(0.3f, 0f, TRAVEL), 0f)
        // Never past the bar; with no way known, nothing is added.
        assertEquals(1f, closeAlong(0.98f, 200f, 1000f), 0f)
        assertEquals(0.3f, closeAlong(0.3f, 40f, 0f), 0f)
    }

    // The cover's shape

    @Test
    fun `the cover rounds with its size, from the player's corner to the orb's circle, never at a threshold`() {
        val full = 300f
        val small = 38f
        val corner = 28f
        assertEquals(corner, coverCorner(full, full, small, corner), 0.0001f)
        assertEquals(small / 2, coverCorner(small, full, small, corner), 0.0001f)
        var side = full
        var last = coverCorner(side, full, small, corner)
        while (side > small) {
            side -= 0.5f
            val radius = coverCorner(side, full, small, corner)
            assertTrue(radius <= side / 2 + 0.0001f)
            // Half a px of size never moves the corner by more than half a px.
            assertTrue("at $side px: $last to $radius", abs(radius - last) <= 0.5f)
            last = radius
        }
        // Past either end, the end holds.
        assertEquals(corner, coverCorner(320f, full, small, corner), 0.0001f)
        assertEquals(15f, coverCorner(30f, full, small, corner), 0.0001f)
    }

    @Test
    fun `the cover's shape is that corner at whatever size it is drawn`() {
        val shape = CoverShape(corner = 28.dp, full = 300.dp)
        val density = Density(1f)
        fun radius(side: Float) = (shape.createOutline(Size(side, side), LayoutDirection.Ltr, density) as Outline.Rounded).roundRect.topLeftCornerRadius.x
        assertEquals(28f, radius(300f), 0.0001f)
        assertEquals(ORB_COVER.value / 2, radius(ORB_COVER.value), 0.0001f)
        assertTrue(radius(150f) > 28f && radius(150f) < 75f)
    }

    // Opening

    @Test
    fun `the bar keeps its cover under a key of its own, the one the player's cover starts on while its own size loads`() {
        // Review 2026-11-18: one key for every size let the bar's small bitmap and the player's full one take each other's
        // place. The bar stores its bitmap under a key of its own (Coil stores it under the request's key and extras,
        // MemoryCacheService.newCacheKey) ...
        val context = object : ContextWrapper(null) {}
        val art = SongArt(PATH, mediaStoreId = 7L)
        val bar = songCoverRequest(context, art, CoverAt.BAR)
        val stored = MemoryCache.Key(bar.memoryCacheKey!!, bar.memoryCacheKeyExtras)
        assertEquals(barCoverKey(art), stored)
        // ... never the key Coil makes itself for the song (its path, no extras: SongCover's requests have no
        // transformations), where every other size is kept.
        val own = MemoryCache.Key(PATH)
        assertNotEquals(own, stored)
        // The player's cover keeps Coil's own key, so it loads and keeps its own size (and finds the one the bar prewarmed),
        // and starts on the bar's bitmap meanwhile.
        val player = songCoverRequest(context, art, CoverAt.PLAYER)
        assertEquals(null, player.memoryCacheKey)
        assertEquals(emptyMap<String, String>(), player.memoryCacheKeyExtras)
        assertEquals(stored, player.placeholderMemoryCacheKey)
        // The bar, and any other cover, start on whatever size of the song is already there.
        assertEquals(own, bar.placeholderMemoryCacheKey)
        val row = songCoverRequest(context, art)
        assertEquals(null, row.memoryCacheKey)
        assertEquals(own, row.placeholderMemoryCacheKey)
        // The song's key is its path (not the library's id), and a cover changed in Covers & tags is a new key, so the old
        // bitmap never stands in for it.
        val changed = art.copy(version = 2)
        assertEquals("$PATH#2", barCoverKey(changed).key)
        assertEquals(barCoverKey(changed), songCoverRequest(context, changed, CoverAt.PLAYER).placeholderMemoryCacheKey)
        assertEquals(barCoverKey(SongArt(PATH)), stored)
        // Anything but a song's cover (a picture another app shows) has no key of KJB's.
        assertEquals(null, songCoverRequest(context, Any(), CoverAt.PLAYER).placeholderMemoryCacheKey)
    }

    private companion object {
        const val PATH = "/Music/Paper Lanterns/Harbour Glass.flac"

        /** About where a player let go a quarter down still has to go to reach the bar, in px. */
        const val TRAVEL = 1500f
        const val DENSITY = 2.75f
        const val SETTLED = 0.01f
        const val SPAN_MS = 2000
        const val FRAME_MS = 16
    }
}
