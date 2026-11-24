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

    /** The made-up songs' embedded covers, in px on a side. */
    private var artPx = 2_000

    /**
     * KJB's keys ([SongArtKeyer]) and cross-fade, the covers made up and counted instead of read from MediaStore: as its
     * thumbnail, never larger than the song's cover, cut down by a whole sample to at least the size asked, and marked as
     * SongArtFetcher marks it ([coverSampled]).
     */
    private val loader = ImageLoader.Builder(context)
        .components {
            add(SongArtKeyer())
            add(
                Fetcher.Factory<SongArt> { art, options, _ ->
                    Fetcher {
                        reads.incrementAndGet()
                        val px = options.size.width.pxOrElse { 256 }
                        val side = artPx / maxOf(1, artPx / px)
                        ImageFetchResult(Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888).apply { eraseColor(COLOURS.getValue(art.path)) }.asImage(), isSampled = coverSampled(side, side, px), dataSource = DataSource.DISK)
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
    fun aSongReadAheadShowsItsCoverInThePlayerAndTheNowBarInTheFirstFrameReadOnceOnly() = readAheadThenDrawn(artPx = 2_000)

    @Test
    fun aCoverSmallerThanThePlayersReadAheadShowsInTheFirstFrameTooNotReadAgain() = readAheadThenDrawn(artPx = 300)

    @Test
    fun aBackdropReadAheadShowsAtOnceAndOneNotReadAheadStillCrossFadesIn() {
        readAhead(NEXT)
        var path by mutableStateOf(PATH)
        compose.setContent {
            LyrifyTheme { Box(Modifier.size(GLOW).testTag(BACKDROP_TAG)) { Backdrop(path, mediaStoreId = null) } }
        }
        compose.waitForIdle()
        // The song read ahead: whole one frame on (Crossfade's snap lands at the next frame), as it is once settled.
        compose.mainClock.autoAdvance = false
        path = NEXT
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        val snapped = glow()
        compose.mainClock.advanceTimeBy(SETTLED_MS)
        assertEquals(glow(), snapped)
        // A song not read ahead: two frames on, the glow before it still shows nearly whole, fading out over 300 ms (a
        // snap would show the new song's colour, or no glow while it is read).
        val reading = reads.get()
        path = OTHER
        compose.mainClock.advanceTimeByFrame()
        compose.waitUntil(TIMEOUT_MS) { reads.get() > reading }
        compose.mainClock.advanceTimeByFrame()
        val fading = glow()
        assertTrue("$fading against $snapped", listOf(24, 16, 8, 0).all { shift -> abs((fading shr shift and 0xFF) - (snapped shr shift and 0xFF)) <= NEAR })
    }

    /** Reads [path]'s covers ahead, as the shell does while the song before it plays: three reads. */
    private fun readAhead(path: String) {
        val before = reads.get()
        runBlocking { coverAheadRequests(context, SongArt(path, mediaStoreId = 7L), with(density) { COVER_MAX.roundToPx() }, with(density) { ORB_COVER.roundToPx() }).forEach { loader.execute(it) } }
        assertEquals(before + 3, reads.get())
    }

    private fun readAheadThenDrawn(artPx: Int) {
        this.artPx = artPx
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

    private companion object {
        const val PATH = "/storage/emulated/0/Music/Paper Harbour/made-up.mp3"
        const val NEXT = "/storage/emulated/0/Music/Paper Harbour/made-up-next.mp3"
        const val OTHER = "/storage/emulated/0/Music/Glass Rooms/made-up-other.mp3"
        val COLOURS = mapOf(PATH to 0xFFC8285A.toInt(), NEXT to 0xFFE53935.toInt(), OTHER to 0xFF1E88E5.toInt())
        const val PLAYER_TAG = "player cover"
        const val BAR_TAG = "bar cover"
        const val BACKDROP_TAG = "backdrop"
        val GLOW = 320.dp
        const val SETTLED_MS = 1_000L

        /** How far a channel of the glow may be from the one before, a few frames into its 300 ms fade out. */
        const val NEAR = 48
        const val TIMEOUT_MS = 5_000L
    }
}
