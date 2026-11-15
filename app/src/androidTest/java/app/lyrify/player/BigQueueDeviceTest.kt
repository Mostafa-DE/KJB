package app.lyrify.player

import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lyrify.auto.CarCovers
import app.lyrify.library.Song
import app.lyrify.library.SongRef
import app.lyrify.settings.LibrarySettings
import app.lyrify.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * CR1 on the phone (debug build): KJB's engine, bridge, facade and a session with a controller, as the service builds
 * them, holding a library-sized queue as the crash's did (6,906 songs). One made-up tone in the app's cache plays; the
 * other songs name files that are never read (the player reads the song playing, and the next one only near its end).
 * Measures what a queued song costs the app, and that Shuffle on and off 100 times leaves the heap flat with the song
 * playing on. Before CR1 the first Shuffle ran the process out of memory: one move per song.
 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class BigQueueDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val cleanups = mutableListOf<() -> Unit>()

    @After
    fun stop() {
        onMain { cleanups.reversed().forEach { it() } }
        scope.cancel()
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val until = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < until) {
            if (onMain(condition)) return true
            SystemClock.sleep(50)
        }
        return false
    }

    /** A 16-bit mono WAV of [seconds] of a sine tone, in the app's cache. */
    private fun tone(name: String, seconds: Int): File {
        val rate = 22_050
        val samples = rate * seconds
        val file = File(context.cacheDir, name)
        DataOutputStream(file.outputStream().buffered()).use { out ->
            fun int(v: Int) = out.writeInt(Integer.reverseBytes(v))
            fun short(v: Int) = out.writeShort(java.lang.Short.reverseBytes(v.toShort()).toInt())
            out.writeBytes("RIFF")
            int(36 + samples * 2)
            out.writeBytes("WAVEfmt ")
            int(16)
            short(1)
            short(1)
            int(rate)
            int(rate * 2)
            short(2)
            short(16)
            out.writeBytes("data")
            int(samples * 2)
            repeat(samples) { short((sin(2 * PI * 440.0 * it / rate) * 8_000).toInt()) }
        }
        cleanups += { file.delete() }
        return file
    }

    private fun song(path: String, n: Int) = Song(
        ref = SongRef(path, n.toLong()),
        title = "Paper Lantern $n",
        artist = "Kit Calloway ${n % 300}",
        album = "Big Shelf ${n / 12}",
        albumArtist = null,
        durationMs = 180_000,
        track = n % 12 + 1,
        disc = null,
        year = 2_001 + n % 20,
        genre = "Test",
        folder = "",
        addedAt = null,
        modifiedAt = null,
        sizeBytes = 1_000,
        mimeType = "audio/mpeg",
    )

    /** [QUEUED] songs, the one at [START] a real tone of [seconds]; the rest name files that are not there. */
    private fun library(seconds: Int): List<Song> {
        val tone = tone("kjb-cr1-tone.wav", seconds)
        val missing = File(context.cacheDir, "kjb-cr1-never-read")
        return (0 until QUEUED).map { n -> if (n == START) song(tone.path, n).copy(durationMs = seconds * 1_000L, mimeType = "audio/wav") else song("$missing/%04d Paper Lantern.mp3".format(n), n) }
    }

    private class MemoryStorage : QueueStorage {
        override suspend fun load(): Pair<QueueBook, Repeat> = QueueBook() to Repeat.OFF

        override suspend fun saveBook(book: QueueBook) = Unit

        override suspend fun savePlace(place: PlaybackPlace) = Unit
    }

    private object NoLevels : SongLevels {
        override fun albumInOrder(mediaId: String): Boolean = false
    }

    private fun usedAfterGc(): Long {
        val runtime = Runtime.getRuntime()
        var used = Long.MAX_VALUE
        repeat(4) {
            runtime.gc()
            System.runFinalization()
            SystemClock.sleep(100)
            used = minOf(used, runtime.totalMemory() - runtime.freeMemory())
        }
        return used
    }

    @Test
    fun aQueuedSongsMediaItemIsSmallAndCarriesItsCoverAsAUriNeverAsBytes() {
        val songs = library(seconds = 2)
        val projected = songs.mapIndexed { i, s -> Projected(i + 1L, s) }
        val covers = CarCovers.authority(context)
        val before = usedAfterGc()
        val items = projected.map { mediaItemOf(it, LibrarySettings(), covers) }
        val perSong = (usedAfterGc() - before) / QUEUED
        Log.i(TAG, "CR1 measure: a media item costs $perSong bytes")
        assertTrue("a media item costs $perSong bytes", perSong < 2_048)
        assertTrue("a media item holds artwork bytes", items.none { it.mediaMetadata.artworkData != null })
        assertTrue("a media item has no cover uri", items.all { it.mediaMetadata.artworkUri != null })
    }

    @Test
    fun shuffleOnAndOffAHundredTimesOnALibrarySizedQueueLeavesTheHeapFlatAndTheSongPlaying() {
        val songs = library(seconds = 120)
        val byPath = songs.associateBy { it.path }
        val find: (SongRef) -> Song? = { ref -> byPath[ref.path] }
        val empty = usedAfterGc()
        val core = onMain { PlayerCore(MemoryStorage(), scope, System::currentTimeMillis) }
        assertTrue(waitFor(2_000) { core.ready.value })
        val engine = onMain { GaplessEngine(context, { Settings() }, NoLevels) { mediaItemOf(it, LibrarySettings(), CarCovers.authority(context)) } }
        val bridge = QueueBridge(engine)
        val sync = { bridge.sync(core.book.value, find) }
        val player = onMain { LyrifyPlayer(Looper.getMainLooper(), engine, core, { SkipAmounts(30_000, 10_000) }, sync) }
        cleanups += {
            player.release()
            engine.release()
        }
        val source = QueueSource(SourceKind.SONGS, "songs", "Songs")
        onMain {
            core.switch { it.play(songs.map(Song::ref), START, source, System.currentTimeMillis()) }
            sync()
        }
        val session = onMain { MediaSession.Builder(context, player).setId("kjb-cr1-test").build() }
        cleanups += { session.release() }
        val controller = MediaController.Builder(context, session.token).setApplicationLooper(Looper.getMainLooper()).buildAsync().get(10, TimeUnit.SECONDS)
        cleanups += { controller.release() }
        assertTrue("the controller never saw the queue", waitFor(10_000) { controller.mediaItemCount == QUEUED })
        onMain { controller.play() }
        assertTrue("never played", waitFor(5_000) { controller.isPlaying })
        val playing = onMain { engine.loaded[engine.index] }
        val loaded = usedAfterGc()
        Log.i(TAG, "CR1 measure: a queued song costs the app ${(loaded - empty) / QUEUED} bytes (engine, facade, session, controller)")

        val random = Random(9)
        var settled = 0L
        var footprint = 0L
        repeat(100) { round ->
            onMain {
                core.change { it.toggleShuffle(random) }
                sync()
                player.changed()
            }
            // The main thread's queue drained: the playback thread's answers and the controller's update are in.
            assertTrue("the controller never caught up in round $round", waitFor(10_000) { controller.mediaItemCount == QUEUED && controller.currentMediaItem?.mediaId == playing.toString() })
            footprint = maxOf(footprint, Runtime.getRuntime().totalMemory())
            if (round == 9) settled = usedAfterGc()
        }
        val end = usedAfterGc()
        Log.i(TAG, "CR1 measure: after 100 Shuffles the heap holds ${(end - empty) / 1024} KB over empty, grew ${(end - settled) / 1024} KB since round 10, footprint peaked at ${footprint / 1024 / 1024} MB")
        assertTrue("the heap grew by ${(end - settled) / 1024} KB over 90 Shuffles", end - settled < 16L * 1024 * 1024)
        assertEquals("the song playing", playing, onMain { engine.loaded[engine.index] })
        assertTrue("stopped playing", onMain { engine.isPlaying })
        assertEquals(projection(onMain { core.book.value.current }, find).map { it.uid }, onMain { engine.loaded })
    }

    private companion object {
        const val TAG = "KJB-CR1"

        /** A library-sized queue: the crash's had 6,906 songs. */
        const val QUEUED = 7_000
        const val START = 3_000
    }
}
