package app.lyrify.player

import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
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
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
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

    /** The queues in memory: what the core saves is what the next core reads, as after a restart. */
    private class KeptStorage : QueueStorage {
        @Volatile
        var book = QueueBook()

        override suspend fun load(): Pair<QueueBook, Repeat> = book to Repeat.OFF

        override suspend fun saveBook(book: QueueBook) {
            this.book = book
        }

        override suspend fun savePlace(place: PlaybackPlace) = Unit
    }

    /**
     * The player as the service builds it over [storage]: the core, the engine (silent and without audio focus, so the
     * phone's own music is never paused), the bridge, the facade, a session and a controller. [stops] notes each time
     * the engine stopped playing, with its state then.
     */
    private inner class Stack(storage: QueueStorage, private val find: (SongRef) -> Song?, id: String) {
        val core: PlayerCore = onMain { PlayerCore(storage, scope, System::currentTimeMillis) }
        val engine: GaplessEngine = onMain {
            GaplessEngine(context, { Settings() }, NoLevels) { mediaItemOf(it, LibrarySettings(), CarCovers.uris(context)) }.apply {
                setFocusHandled(false)
                volume = 0f
            }
        }
        private val bridge = QueueBridge(engine)
        val player: LyrifyPlayer = onMain { LyrifyPlayer(Looper.getMainLooper(), engine, core, { SkipAmounts(30_000, 10_000) }, ::syncHere) }
        val session: MediaSession = onMain { MediaSession.Builder(context, player).setId(id).build() }
        val controller: MediaController = MediaController.Builder(context, session.token).setApplicationLooper(Looper.getMainLooper()).buildAsync().get(10, TimeUnit.SECONDS)
        val stops = mutableListOf<String>()
        var step = "start"

        init {
            onMain {
                engine.addListener(object : EngineListener {
                    override fun onPlayingChanged(playing: Boolean) {
                        if (!playing) stops += "$step: ${describe()}"
                    }
                })
            }
            cleanups += ::release
        }

        private fun syncHere() = bridge.sync(core.book.value, find)

        /** The bridge applied and the session told, on the main thread. */
        fun sync() = onMain {
            syncHere()
            player.changed()
        }

        /** The engine's state, on the main thread. */
        fun describe(): String =
            "state ${engine.playbackState}, playWhenReady ${engine.playWhenReady}, suppressed ${engine.suppression}, error ${engine.error?.errorCodeName}, at ${engine.positionMs} ms"

        /** The song playing, on the main thread. */
        fun current(): Long = engine.loaded[engine.index]

        val playing: Long get() = onMain(::current)

        private var released = false

        /** On the main thread; once. */
        fun release() {
            if (released) return
            released = true
            controller.release()
            session.release()
            player.release()
            engine.release()
        }
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
        val covers = CarCovers.uris(context)
        val before = usedAfterGc()
        val items = projected.map { mediaItemOf(it, LibrarySettings(), covers) }
        val perSong = (usedAfterGc() - before) / QUEUED
        Log.i(TAG, "CR1 measure: a media item costs $perSong bytes")
        assertTrue("a media item costs $perSong bytes", perSong < 2_048)
        assertTrue("a media item holds artwork bytes", items.none { it.mediaMetadata.artworkData != null })
        assertTrue("a media item has no cover uri", items.all { it.mediaMetadata.artworkUri != null })
    }

    /**
     * The crash as the user made it: a queue playing, the song tapped in a 7,000-song list while it plays (a new queue),
     * Shuffle on, KJB starting again on the queues it saved; then Shuffle on and off 100 times, a Play next of 2,000 songs
     * and its Undo. The heap stays flat, each step is quick, and the song playing never changes or stops.
     */
    @Test
    fun theCrashSequenceThenAHundredShufflesLeavesTheHeapFlatAndTheSongPlaying() {
        val songs = library(seconds = 180)
        val byPath = songs.associateBy { it.path }
        val find: (SongRef) -> Song? = { ref -> byPath[ref.path] }
        val storage = KeptStorage()
        val empty = usedAfterGc()
        val first = Stack(storage, find, "kjb-cr1-test-1")
        assertTrue(waitFor(2_000) { first.core.ready.value })
        val now = System::currentTimeMillis
        val picked = QueueSource(SourceKind.PICKED, "picked", "")
        val list = QueueSource(SourceKind.SONGS, "songs", "Songs")
        onMain { first.core.switch { it.play(songs.reversed().map(Song::ref), QUEUED - 1 - START, picked, now()) } }
        first.sync()
        onMain { first.controller.play() }
        assertTrue("never played: ${onMain { first.describe() }}", waitFor(5_000) { first.engine.isPlaying })

        // A tap starts the song again from its start in the new queue: a stop then is no fault.
        first.step = "the song tapped in the list"
        onMain { first.core.switch { it.play(songs.map(Song::ref), START, list, now()) } }
        first.sync()
        assertTrue("the tapped list never reached the controller", waitFor(10_000) { first.controller.mediaItemCount == QUEUED && first.controller.currentMediaItem?.mediaId == first.current().toString() })
        assertTrue("the tapped song never played: ${onMain { first.describe() }}", waitFor(5_000) { first.engine.isPlaying })
        onMain { first.stops.clear() }
        val tone = first.playing
        val random = Random(9)
        first.step = "Shuffle on"
        onMain { first.core.change { it.toggleShuffle(random) } }
        first.sync()
        assertTrue("the shuffled queue was never saved", waitFor(5_000) { storage.book.current?.shuffled == true })
        val playing = first.playing
        assertEquals("the song playing after Shuffle", tone, playing)
        assertTrue("the first player stopped: ${first.stops}", first.stops.isEmpty())
        onMain(first::release)

        // KJB starts again on the queues it saved.
        val second = Stack(storage, find, "kjb-cr1-test-2")
        assertTrue(waitFor(2_000) { second.core.ready.value && second.core.book.value.current?.shuffled == true })
        second.sync()
        onMain { second.controller.play() }
        assertTrue("never played after the restart: ${onMain { second.describe() }}", waitFor(5_000) { second.engine.isPlaying })
        assertEquals("the song playing after the restart", playing, second.playing)
        assertEquals(projection(onMain { second.core.book.value.current }, find).map { it.uid }, onMain { second.engine.loaded })
        val loaded = usedAfterGc()
        Log.i(TAG, "CR1 measure: a queued song costs the app ${(loaded - empty) / QUEUED} bytes (engine, facade, session, controller)")

        var settled = 0L
        var footprint = 0L
        var slowestMs = 0L
        fun timed(step: String, change: (QueueBook) -> QueueBook) {
            second.step = step
            val started = SystemClock.elapsedRealtime()
            onMain { second.core.change(change) }
            second.sync()
            // The main thread's queue drained: the playback thread's answers and the controller's update are in.
            assertTrue("$step: the controller never caught up", waitFor(10_000) { second.controller.mediaItemCount == second.engine.loaded.size && second.controller.currentMediaItem?.mediaId == playing.toString() })
            slowestMs = maxOf(slowestMs, SystemClock.elapsedRealtime() - started)
            footprint = maxOf(footprint, Runtime.getRuntime().totalMemory())
        }
        repeat(100) { round ->
            timed("Shuffle, round $round") { it.toggleShuffle(random) }
            if (round == 9) settled = usedAfterGc()
        }
        var before: List<QueueItem> = emptyList()
        timed("Play next of 2,000 songs") { book -> book.playNext(songs.takeLast(2_000).map(Song::ref), picked, now())!!.also { before = it.before }.book }
        timed("its Undo") { book -> book.restore(book.currentId!!, before) }
        val end = usedAfterGc()
        Log.i(
            TAG,
            "CR1 measure: after the crash sequence and 100 Shuffles the heap holds ${(end - empty) / 1024} KB over empty, grew ${(end - settled) / 1024} KB " +
                "since round 10, footprint peaked at ${footprint / 1024 / 1024} MB, the slowest step took $slowestMs ms, stops: ${second.stops.size}",
        )
        assertTrue("the heap grew by ${(end - settled) / 1024} KB over 90 Shuffles", end - settled < 16L * 1024 * 1024)
        assertTrue("a step took $slowestMs ms", slowestMs < 3_000)
        assertEquals("the song playing", playing, second.playing)
        assertTrue("stopped playing: ${second.stops}; now ${onMain { second.describe() }}", second.stops.isEmpty() && onMain { second.engine.isPlaying })
        assertEquals(projection(onMain { second.core.book.value.current }, find).map { it.uid }, onMain { second.engine.loaded })
    }

    /**
     * Next and Previous in a library-sized queue (PW2), back and forth over the one song that plays, whose decoded length
     * is 7 ms off the library's: the engine reads its songs once, not again on every Next, and the controller (as the
     * notification, the lock screen and the car are) follows each song with no new timeline, so the session sends the
     * phone no queue. Before PW2 each Next walked the queue about 20 times and sent it whole once or twice.
     */
    @Test
    fun nextAndPreviousInALibrarySizedQueueKeepTheEnginesSongsAndTheSessionsTimeline() {
        val songs = library(seconds = 20).map { if (it.path.endsWith(".wav")) it.copy(durationMs = it.durationMs + 7) else it }
        val byPath = songs.associateBy { it.path }
        val stack = Stack(KeptStorage(), { ref -> byPath[ref.path] }, "kjb-pw2-skip")
        assertTrue(waitFor(2_000) { stack.core.ready.value })
        onMain { stack.core.switch { it.play(songs.map(Song::ref), START, QueueSource(SourceKind.SONGS, "songs", "Songs"), System.currentTimeMillis()) } }
        stack.sync()
        assertTrue("the queue never reached the controller", waitFor(10_000) { stack.controller.mediaItemCount == QUEUED && stack.controller.currentMediaItem?.mediaId == stack.current().toString() })
        onMain { stack.controller.play() }
        assertTrue("never played: ${onMain { stack.describe() }}", waitFor(5_000) { stack.engine.isPlaying })
        val held = onMain { stack.engine.items }
        var timelines = 0
        onMain {
            stack.controller.addListener(object : Player.Listener {
                override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                    timelines++
                }
            })
        }
        val times = mutableListOf<Long>()
        repeat(SKIPS) { step ->
            val target = if (step % 2 == 0) START + 1 else START
            val started = SystemClock.elapsedRealtime()
            onMain { if (step % 2 == 0) stack.controller.seekToNextMediaItem() else stack.controller.seekToPreviousMediaItem() }
            assertTrue("skip $step never reached the controller", waitFor(5_000) { stack.controller.currentMediaItemIndex == target })
            times += SystemClock.elapsedRealtime() - started
            // The song that plays as long as it decodes, so its decoded length is known before the next skip.
            if (target == START) assertTrue("never played again: ${onMain { stack.describe() }}", waitFor(5_000) { stack.engine.isPlaying })
        }
        Log.i(TAG, "PW2 measure: a skip in a $QUEUED-song queue reached the controller in ${times.sorted()[SKIPS / 2]} ms (median of $SKIPS), $timelines new timelines")
        assertEquals("new timelines the controller heard", 0, timelines)
        assertSame("the engine's songs were read again", held, onMain { stack.engine.items })
    }

    /**
     * A Next asks the session for its state once in its own turn (PW2), as Media3 reads it when the seek returns, even
     * when the song starting changes the queue and that change asks for it too, as the service's queue collector does.
     * Before PW2 that was one more state built over the whole queue before the new song could be drawn, two with
     * Crossfade on.
     */
    @Test
    fun aNextAsksTheSessionForItsStateOnceInItsOwnTurn() {
        val songs = library(seconds = 20)
        val byPath = songs.associateBy { it.path }
        val find: (SongRef) -> Song? = { ref -> byPath[ref.path] }
        val core = onMain { PlayerCore(KeptStorage(), scope, System::currentTimeMillis) }
        val engine = onMain {
            GaplessEngine(context, { Settings() }, NoLevels) { mediaItemOf(it, LibrarySettings(), CarCovers.uris(context)) }.apply {
                setFocusHandled(false)
                volume = 0f
            }
        }
        val bridge = QueueBridge(engine)
        // The session reads the engine's songs once for each state it asks for (LyrifyPlayer.getState).
        var states = 0
        val counted = object : PlaybackEngine by engine {
            override val items: List<MediaItem>
                get() = engine.items.also { states++ }
        }
        val player = onMain { LyrifyPlayer(Looper.getMainLooper(), counted, core, { SkipAmounts(30_000, 10_000) }, { bridge.sync(core.book.value, find) }) }
        cleanups += {
            player.release()
            engine.release()
        }
        assertTrue(waitFor(2_000) { core.ready.value })
        onMain {
            // As the service: the queue follows the song starting, and each change of the queue asks the session again.
            engine.addListener(object : EngineListener {
                override fun onSongStarted(uid: Long, lap: Boolean, natural: Boolean) {
                    if (!lap) core.change { it.at(uid, System.currentTimeMillis()) }
                }
            })
            scope.launch {
                core.book.collect { book ->
                    bridge.sync(book, find)
                    player.changed()
                }
            }
            core.switch { it.play(songs.map(Song::ref), START, QueueSource(SourceKind.SONGS, "songs", "Songs"), System.currentTimeMillis()) }
        }
        assertTrue("the queue never loaded", waitFor(5_000) { engine.loaded.size == QUEUED && player.currentMediaItemIndex == START })
        val asked = onMain {
            states = 0
            player.seekToNextMediaItem()
            states
        }
        assertEquals("the song after", START + 1, onMain { player.currentMediaItemIndex })
        assertEquals("states asked for in the Next's own turn", 1, asked)
    }

    private companion object {
        const val TAG = "KJB-CR1"

        /** A library-sized queue: the crash's had 6,906 songs. */
        const val QUEUED = 7_000
        const val START = 3_000

        /** Next and Previous presses measured. */
        const val SKIPS = 21
    }
}
