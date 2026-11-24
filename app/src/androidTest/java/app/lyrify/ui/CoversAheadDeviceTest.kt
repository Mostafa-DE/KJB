package app.lyrify.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.SuccessResult
import coil3.request.crossfade
import coil3.size.Precision
import coil3.size.pxOrElse
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * The covers read ahead (PW3), drawn: once a song's covers are read ahead, the player's cover (drawn a little smaller
 * than read, as inside the player's margins) and the Now bar's show it in their first frame, whole (no fade, never
 * KJB's default cover), with no read of their own, and the backdrop's request is answered from memory; for a large
 * embedded cover and for one smaller than the player's. The backdrop of a song read ahead shows at once, one not read
 * ahead still cross-fades in. A made-up cover from a fetcher that counts its reads stands in for MediaStore's
 * thumbnail, each song in a colour of its own. Made-up names only.
 */
@OptIn(DelicateCoilApi::class)
@RunWith(AndroidJUnit4::class)
class CoversAheadDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val density = Density(context)
    private val reads = AtomicInteger()

    /** The made-up songs' embedded covers, in px: square, smaller than the player's, wide or tall. */
    private var artWidth = 2_000
    private var artHeight = 2_000

    /**
     * KJB's keys ([SongArtKeyer]) and cross-fade, the covers made up and counted instead of read from MediaStore: as its
     * thumbnail, never larger than the song's cover, cut down by a whole sample of its longer side to at least the size
     * asked (ContentResolver.loadThumbnail), and marked as SongArtFetcher marks it ([coverSampled]).
     */
    private val loader = ImageLoader.Builder(context)
        .components {
            add(SongArtKeyer())
            add(
                Fetcher.Factory<SongArt> { art, options, _ ->
                    Fetcher {
                        reads.incrementAndGet()
                        val px = options.size.width.pxOrElse { 256 }
                        val sample = maxOf(1, artWidth / px, artHeight / px)
                        val bitmap = Bitmap.createBitmap(artWidth / sample, artHeight / sample, Bitmap.Config.ARGB_8888).apply { eraseColor(COLOURS.getValue(art.path)) }
                        ImageFetchResult(bitmap.asImage(), isSampled = coverSampled(bitmap.width, bitmap.height, px), dataSource = DataSource.DISK)
                    }
                },
            )
        }
        .crossfade(true)
        .build()

    @Before
    fun useLoader() = SingletonImageLoader.setUnsafe(loader)

    @After
    fun resetLoader() = SingletonImageLoader.reset()

    @Test
    fun aSongReadAheadShowsItsCoverInThePlayerAndTheNowBarInTheFirstFrameReadOnceOnly() = readAheadThenDrawn(2_000, 2_000)

    @Test
    fun aCoverSmallerThanThePlayersReadAheadShowsInTheFirstFrameTooNotReadAgain() = readAheadThenDrawn(300, 300)

    @Test
    fun aWideCoverReadAheadShowsInTheFirstFrameTooNotReadAgain() = readAheadThenDrawn(1_600, 800)

    @Test
    fun aTallCoverReadAheadShowsInTheFirstFrameTooNotReadAgain() = readAheadThenDrawn(800, 1_200)

    @Test
    fun aBackdropReadAheadShowsAtOnceAndOneNotReadAheadStillCrossFadesIn() {
        // PATH's and NEXT's covers read ahead whole; OTHER's only at the player's size: its backdrop is not in memory,
        // but it starts on that cover at once (placeholderMemoryCacheKey): its colour shows from its first frame too.
        readAhead(PATH)
        readAhead(NEXT)
        runBlocking { loader.execute(coverAheadRequests(context, SongArt(OTHER), with(density) { COVER_MAX.roundToPx() }, with(density) { ORB_COVER.roundToPx() }).first()) }
        var path by mutableStateOf(PATH)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LyrifyTheme { Box(Modifier.size(GLOW).testTag(BACKDROP_TAG)) { Backdrop(path, mediaStoreId = null) } }
        }
        compose.mainClock.advanceTimeBy(SETTLED_MS)
        val first = glow()
        // The song read ahead: whole one frame on (Crossfade's snap lands at the next frame), as it is once settled,
        // and not the glow before it.
        path = NEXT
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        val snapped = glow()
        compose.mainClock.advanceTimeBy(SETTLED_MS)
        assertEquals(glow(), snapped)
        assertTrue("$snapped against $first", !near(snapped, first))
        // A song not read ahead: four frames into its 300 ms cross-fade it has moved off the glow before it, but only a
        // little; settled, it is far from it (a snap would be there at once, a glow that never changed never).
        path = OTHER
        repeat(4) { compose.mainClock.advanceTimeByFrame() }
        val fading = glow()
        compose.mainClock.advanceTimeBy(SETTLED_MS)
        val settled = glow()
        assertTrue("$fading against $snapped", fading != snapped && near(fading, snapped))
        assertTrue("$settled against $snapped", !near(settled, snapped))
    }

    /** Reads [path]'s covers ahead, as the shell does while the song before it plays: three reads. */
    private fun readAhead(path: String) {
        val before = reads.get()
        runBlocking { coverAheadRequests(context, SongArt(path, mediaStoreId = 7L), with(density) { COVER_MAX.roundToPx() }, with(density) { ORB_COVER.roundToPx() }).forEach { loader.execute(it) } }
        assertEquals(before + 3, reads.get())
    }

    private fun readAheadThenDrawn(artWidth: Int, artHeight: Int) {
        this.artWidth = artWidth
        this.artHeight = artHeight
        readAhead(PATH)
        // No frame moves on by itself: a fade would show part way, a cover still loading would show the default.
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LyrifyTheme {
                Column {
                    SongCover(SongArt(PATH), Modifier.testTag(PLAYER_TAG), size = COVER_MAX - 48.dp, at = CoverAt.PLAYER)
                    SongCover(SongArt(PATH), Modifier.testTag(BAR_TAG), size = ORB_COVER, shape = CircleShape, at = CoverAt.BAR)
                }
            }
        }
        for (tag in listOf(PLAYER_TAG, BAR_TAG)) {
            val drawn = compose.onNodeWithTag(tag).captureToImage().toPixelMap()
            assertEquals(tag, COLOURS.getValue(PATH), drawn[drawn.width / 2, drawn.height / 2].toArgb())
        }
        // The backdrop's request, inexact as its AsyncImage asks, finds its small cover too.
        val backdrop = runBlocking { loader.execute(backdropRequest(context, SongArt(PATH)).newBuilder().precision(Precision.INEXACT).build()) }
        assertEquals(DataSource.MEMORY_CACHE, (backdrop as SuccessResult).dataSource)
        assertEquals(3, reads.get())
    }

    /** The backdrop's colour at its middle now. */
    private fun glow(): Int = compose.onNodeWithTag(BACKDROP_TAG).captureToImage().toPixelMap().let { it[it.width / 2, it.height / 4].toArgb() }

    /** Whether two colours are within [NEAR] on every channel. */
    private fun near(one: Int, other: Int): Boolean = listOf(24, 16, 8, 0).all { shift -> abs((one shr shift and 0xFF) - (other shr shift and 0xFF)) <= NEAR }

    private companion object {
        const val PATH = "/storage/emulated/0/Music/Paper Harbour/made-up.mp3"
        const val NEXT = "/storage/emulated/0/Music/Paper Harbour/made-up-next.mp3"
        const val OTHER = "/storage/emulated/0/Music/Glass Rooms/made-up-other.mp3"
        val COLOURS = mapOf(PATH to 0xFF43A047.toInt(), NEXT to 0xFFE53935.toInt(), OTHER to 0xFF1E88E5.toInt())
        const val PLAYER_TAG = "player cover"
        const val BAR_TAG = "bar cover"
        const val BACKDROP_TAG = "backdrop"
        val GLOW = 320.dp
        const val SETTLED_MS = 1_000L

        /** How far a channel of the glow may be from the one before, a few frames into its 300 ms fade out. */
        const val NEAR = 48
    }
}
