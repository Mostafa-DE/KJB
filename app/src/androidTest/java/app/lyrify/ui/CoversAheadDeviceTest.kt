package app.lyrify.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/**
 * The covers read ahead (PW3), drawn: once a song's covers are read ahead, the player's cover and the Now bar's show it in
 * their first frame, whole (no fade, never KJB's default cover), with no read of their own, and the backdrop's request is
 * answered from memory; for a large embedded cover and for one smaller than the player's. A made-up cover from a fetcher
 * that counts its reads stands in for MediaStore's thumbnail. Made-up names only.
 */
@OptIn(DelicateCoilApi::class)
@RunWith(AndroidJUnit4::class)
class CoversAheadDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val reads = AtomicInteger()

    /** The made-up song's embedded cover, in px on a side. */
    private var artPx = 0

    /**
     * KJB's keys ([SongArtKeyer]) and cross-fade, the covers made up and counted instead of read from MediaStore: as its
     * thumbnail, never larger than the song's cover, cut down by a whole sample to at least the size asked, and marked as
     * SongArtFetcher marks it ([coverSampled]).
     */
    private val loader = ImageLoader.Builder(context)
        .components {
            add(SongArtKeyer())
            add(
                Fetcher.Factory<SongArt> { _, options, _ ->
                    Fetcher {
                        reads.incrementAndGet()
                        val px = options.size.width.pxOrElse { 256 }
                        val side = artPx / maxOf(1, artPx / px)
                        ImageFetchResult(Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888).apply { eraseColor(COVER) }.asImage(), isSampled = coverSampled(side, side, px), dataSource = DataSource.DISK)
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

    private fun readAheadThenDrawn(artPx: Int) {
        this.artPx = artPx
        val density = Density(context)
        val read = SongArt(PATH, mediaStoreId = 7L)
        runBlocking { coverAheadRequests(context, read, with(density) { SIDE.roundToPx() }, with(density) { ORB_COVER.roundToPx() }).forEach { loader.execute(it) } }
        assertEquals(3, reads.get())
        // No frame moves on by itself: a fade would show part way, a cover still loading would show the default.
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LyrifyTheme {
                Column {
                    SongCover(SongArt(PATH), Modifier.testTag(PLAYER_TAG), size = SIDE, at = CoverAt.PLAYER)
                    SongCover(SongArt(PATH), Modifier.testTag(BAR_TAG), size = ORB_COVER, shape = CircleShape, at = CoverAt.BAR)
                }
            }
        }
        for (tag in listOf(PLAYER_TAG, BAR_TAG)) {
            val drawn = compose.onNodeWithTag(tag).captureToImage().toPixelMap()
            assertEquals(tag, COVER, drawn[drawn.width / 2, drawn.height / 2].toArgb())
        }
        // The backdrop's request, inexact as its AsyncImage asks, finds its small cover too.
        val backdrop = runBlocking { loader.execute(backdropRequest(context, SongArt(PATH)).newBuilder().precision(Precision.INEXACT).build()) }
        assertEquals(DataSource.MEMORY_CACHE, (backdrop as SuccessResult).dataSource)
        assertEquals(3, reads.get())
    }

    private companion object {
        const val PATH = "/storage/emulated/0/Music/Paper Harbour/made-up.mp3"
        const val PLAYER_TAG = "player cover"
        const val BAR_TAG = "bar cover"
        const val COVER = 0xFFC8285A.toInt()
        val SIDE = COVER_MAX
    }
}
