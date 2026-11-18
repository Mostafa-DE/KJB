package app.lyrify.ui

import android.content.ContextWrapper
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.VectorConverter
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import coil3.memory.MemoryCache
import org.junit.Assert.assertEquals
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
        assertEquals(0.96f, closeCoverScale(0.96f, 0f), 0f)
        assertEquals(0.98f, closeCoverScale(0.96f, 0.5f), 0.0001f)
        assertEquals(1f, closeCoverScale(0.96f, 1f), 0f)
        // Closed by the chevron or Back, nothing was shrunk.
        assertEquals(1f, closeCoverScale(1f, 0.3f), 0f)
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
    fun `the cover growing out of the bar starts on the bitmap the bar shows, under the same key`() {
        assertEquals("/Music/Paper Lanterns/Harbour Glass.flac", coverRequestKey(SongArt("/Music/Paper Lanterns/Harbour Glass.flac", mediaStoreId = 7L)))
        assertEquals("/Music/Paper Lanterns/Harbour Glass.flac#2", coverRequestKey(SongArt("/Music/Paper Lanterns/Harbour Glass.flac", version = 2)))
        assertEquals(null, coverRequestKey(null))
    }

    @Test
    fun `every size of a song's cover is one key in memory, the very key the next one's placeholder looks up`() {
        // Review 2026-11-18: the bar's cached bitmap and the player's placeholder must be one key, whatever Coil would add
        // to a key it makes itself. Each request names it: Coil stores the bitmap under that key and the request's extras
        // (MemoryCacheService), and the next request of the song, at any size, looks that up as its placeholder.
        val context = object : ContextWrapper(null) {}
        val art = SongArt("/Music/Paper Lanterns/Harbour Glass.flac", mediaStoreId = 7L)
        val bar = songCoverRequest(context, art)
        assertEquals("/Music/Paper Lanterns/Harbour Glass.flac", bar.memoryCacheKey)
        val stored = MemoryCache.Key(bar.memoryCacheKey!!, bar.memoryCacheKeyExtras)
        assertEquals(stored, songCoverRequest(context, art).placeholderMemoryCacheKey)
        assertEquals(MemoryCache.Key("/Music/Paper Lanterns/Harbour Glass.flac"), stored)
        // A cover changed in Covers & tags is a new key, so the old bitmap never stands in for it.
        assertEquals(MemoryCache.Key("/Music/Paper Lanterns/Harbour Glass.flac#2"), songCoverRequest(context, art.copy(version = 2)).placeholderMemoryCacheKey)
    }

    private companion object {
        /** About where a player let go a quarter down still has to go to reach the bar, in px. */
        const val TRAVEL = 1500f
        const val DENSITY = 2.75f
        const val SETTLED = 0.01f
        const val SPAN_MS = 2000
        const val FRAME_MS = 16
    }
}
