package app.lyrify.player

import android.content.ComponentName
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaBrowser
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lyrify.library.Song
import app.lyrify.library.SongRef
import app.lyrify.settings.LibrarySettings
import app.lyrify.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
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

/**
 * KJB's own player on the phone (debug build): made-up test tones written to the app's cache, never to shared storage.
 * The facade, the bridge and the engine together through a MediaController; two songs back to back with no gap, in WAV,
 * MP3, FLAC and M4A; a song that cannot be played skipped, under Repeat all too; the queues and the place read back by
 * a player built again over the same files, as after the service stopped; the browse tree of the real service.
 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class PlayerDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val cleanups = mutableListOf<() -> Unit>()

    @After
    fun stop() {
        onMain { cleanups.reversed().forEach { it() } }
        scope.cancel()
    }

    /** [block] on the main thread, its result back on the test's thread. */
    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    /** Waits up to [timeoutMs] for [condition], checked on the main thread. */
    private fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val until = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < until) {
            if (onMain(condition)) return true
            SystemClock.sleep(50)
        }
        return false
    }

    /** A 16-bit mono WAV of [seconds] of a sine tone, in the app's cache. */
    private fun tone(name: String, seconds: Int, hz: Double): File {
        val rate = 44_100
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
            repeat(samples) { short((sin(2 * PI * hz * it / rate) * 8_000).toInt()) }
        }
        cleanups += { file.delete() }
        return file
    }

    /** A file no player can read, under an audio name, in the app's cache. */
    private fun broken(name: String): File = File(context.cacheDir, name).apply {
        writeBytes(ByteArray(8_192) { (it * 31 + 7).toByte() })
        cleanups += { delete() }
    }

    /** A copy of the test APK's asset [asset] (a 2 s tone with made-up tags) in the app's cache. */
    private fun copied(asset: String, name: String): File = File(context.cacheDir, name).apply {
        instrumentation.context.assets.open(asset).use { input -> outputStream().use { input.copyTo(it) } }
        cleanups += { delete() }
    }

    private fun song(file: File, title: String) = Song(
        ref = SongRef(file.path),
        title = title,
        artist = "Test Tones",
        album = "Tone Album",
        albumArtist = null,
        durationMs = 2_000,
        track = null,
        disc = null,
        year = null,
        genre = null,
        folder = "",
        addedAt = null,
        modifiedAt = null,
        sizeBytes = file.length(),
        mimeType = "audio/wav",
    )

    private class MemoryStorage : QueueStorage {
        override suspend fun load(): Pair<QueueBook, Repeat> = QueueBook() to Repeat.OFF

        override suspend fun saveBook(book: QueueBook) = Unit

        override suspend fun savePlace(place: PlaybackPlace) = Unit
    }

    private object NoLevels : SongLevels {
        override fun albumInOrder(mediaId: String): Boolean = false
    }

    private class Built(val core: PlayerCore, val engine: GaplessEngine, val bridge: QueueBridge, val player: LyrifyPlayer)

    /** The player as the service builds it, over [songs] and its queues in [storage], on the main thread. */
    private fun build(songs: List<Song>, storage: QueueStorage = MemoryStorage()): Built = onMain {
        val core = PlayerCore(storage, scope, System::currentTimeMillis)
        val engine = GaplessEngine(context, { Settings() }, NoLevels) { mediaItemOf(it, LibrarySettings()) }
        val bridge = QueueBridge(engine)
        val find: (SongRef) -> Song? = { ref -> songs.firstOrNull { it.path == ref.path } }
        val player = LyrifyPlayer(Looper.getMainLooper(), engine, core, { SkipAmounts(30_000, 10_000) }, { bridge.sync(core.book.value, find) })
        cleanups += {
            player.release()
            engine.release()
        }
        Built(core, engine, bridge, player)
    }

    /** The service's answer to a song that cannot be played ([skipAfterError]) on [engine]; gives the errors so far. */
    private fun skipsErrors(engine: GaplessEngine): () -> Int {
        var failedInARow = 0
        var errors = 0
        onMain {
            engine.addListener(object : EngineListener {
                override fun onPlayingChanged(playing: Boolean) {
                    if (playing) failedInARow = 0
                }

                override fun onError(error: PlaybackException, next: Int) {
                    errors++
                    failedInARow++
                    skipAfterError(engine, next, failedInARow)
                }
            })
        }
        return { errors }
    }

    private fun projected(songs: List<Song>): List<Projected> = songs.mapIndexed { i, s -> Projected(i + 1L, s) }

    /** Plays [songs] from the first; the time from playing to the second song's start, and whether it came by itself. */
    private fun backToBack(songs: List<Song>): Pair<Long, Boolean> {
        val built = build(songs)
        var playingAt = 0L
        var secondAt = 0L
        var sawNatural = false
        onMain {
            built.engine.addListener(object : EngineListener {
                override fun onPlayingChanged(playing: Boolean) {
                    if (playing && playingAt == 0L) playingAt = SystemClock.elapsedRealtime()
                }

                override fun onSongStarted(uid: Long, lap: Boolean, natural: Boolean) {
                    if (uid == 2L && secondAt == 0L) {
                        secondAt = SystemClock.elapsedRealtime()
                        sawNatural = natural
                    }
                }
            })
            built.engine.load(projected(songs), 0, 0)
            built.engine.setPlayWhenReady(true)
        }
        assertTrue("the second song never started", waitFor(6_000) { secondAt != 0L })
        return (secondAt - playingAt) to sawNatural
    }

    @Test
    fun aGeneratedSongPlaysThroughAMediaControllerAndTheNextFollows() {
        val songs = listOf(song(tone("kjb-tone-1.wav", 2, 440.0), "Tone One"), song(tone("kjb-tone-2.wav", 2, 660.0), "Tone Two"))
        val built = build(songs)
        assertTrue(waitFor(2_000) { built.core.ready.value })
        val source = QueueSource(SourceKind.ALBUM, "album:tones", "Tone Album")
        onMain {
            built.core.switch { it.play(songs.map(Song::ref), 0, source, System.currentTimeMillis()) }
            built.bridge.sync(built.core.book.value) { ref -> songs.firstOrNull { it.path == ref.path } }
        }
        val session = onMain { MediaSession.Builder(context, built.player).setId("kjb-device-test").build() }
        cleanups += { session.release() }
        val controller = MediaController.Builder(context, session.token).setApplicationLooper(Looper.getMainLooper()).buildAsync().get(10, TimeUnit.SECONDS)
        cleanups += { controller.release() }

        assertEquals(2, onMain { controller.mediaItemCount })
        assertEquals("Tone One", onMain { controller.mediaMetadata.title?.toString() })
        onMain { controller.play() }
        assertTrue("never played", waitFor(5_000) { controller.isPlaying })
        assertTrue("never reached the second song", waitFor(5_000) { controller.currentMediaItemIndex == 1 })
        // The queue model followed the player.
        assertEquals(1, onMain { built.core.book.value.current!!.index })
    }

    @Test
    fun twoSongsPlayBackToBackWithoutAGap() {
        val (first, natural) = backToBack(listOf(song(tone("kjb-gap-1.wav", 2, 440.0), "Gap One"), song(tone("kjb-gap-2.wav", 2, 550.0), "Gap Two")))
        assertTrue("not a natural transition", natural)
        assertTrue("the first 2 s song took $first ms", first in 1_700L..(2_000L + GAP_LIMIT_MS))
    }

    /**
     * MP3 (LAME delay and padding), FLAC and M4A (an edit list), each a 2 s tone twice: the second starts by itself, on
     * time. A timing check on the wall clock: a gap of a few tens of ms passes, so the ear has the last word.
     */
    @Test
    fun mp3FlacAndM4aSongsPlayBackToBackWithoutAGap() {
        for (asset in listOf("tags/paper-comet-v23.mp3", "tags/salt-clock.flac", "tags/northbound-tide.m4a")) {
            val format = asset.substringAfterLast('.')
            val songs = (1..2).map { song(copied(asset, "kjb-gap-$format-$it.$format"), "Gap $format $it") }
            val (first, natural) = backToBack(songs)
            assertTrue("$format: not a natural transition", natural)
            assertTrue("$format: the first 2 s song took $first ms", first in 1_700L..(2_000L + GAP_LIMIT_MS))
        }
    }

    @Test
    fun aSongThatCannotBePlayedInTheMiddleIsSkippedToTheNext() {
        val songs = listOf(song(tone("kjb-skip-1.wav", 1, 440.0), "Skip One"), song(broken("kjb-skip-2.mp3"), "Skip Two"), song(tone("kjb-skip-3.wav", 2, 550.0), "Skip Three"))
        val built = build(songs)
        val errors = skipsErrors(built.engine)
        onMain {
            built.engine.load(projected(songs), 0, 0)
            built.engine.setPlayWhenReady(true)
        }
        assertTrue("never reached the song after the broken one", waitFor(8_000) { built.engine.index == 2 && built.engine.isPlaying })
        assertTrue(onMain(errors) >= 1)
    }

    @Test
    fun underRepeatAllABrokenLastSongGoesRoundToTheFirst() {
        val songs = listOf(song(tone("kjb-round-1.wav", 2, 440.0), "Round One"), song(broken("kjb-round-2.mp3"), "Round Two"))
        val built = build(songs)
        skipsErrors(built.engine)
        onMain {
            built.engine.setRepeat(Repeat.ALL)
            built.engine.load(projected(songs), 1, 0)
            built.engine.setPlayWhenReady(true)
        }
        assertTrue("stalled on the broken last song", waitFor(8_000) { built.engine.index == 0 && built.engine.isPlaying })
    }

    @Test
    fun aQueueOfOnlyBrokenSongsStopsInsteadOfGoingRound() {
        val songs = listOf(song(broken("kjb-stop-1.mp3"), "Stop One"))
        val built = build(songs)
        val errors = skipsErrors(built.engine)
        onMain {
            built.engine.setRepeat(Repeat.ALL)
            built.engine.load(projected(songs), 0, 0)
            built.engine.setPlayWhenReady(true)
        }
        assertTrue("never stopped", waitFor(8_000) { errors() >= 1 && !built.engine.playWhenReady })
        SystemClock.sleep(1_000)
        assertEquals("went round again", 1, onMain(errors))
    }

    /**
     * The service stopping and starting again: the queues and the place written to their files ([QueueFiles]) by one
     * player, read back by a player built again over the same files, which opens at the song and the place it left.
     */
    @Test
    fun aPlayerBuiltAgainOverTheSameFilesOpensAtTheSongAndPlaceItLeft() {
        val songs = listOf(song(tone("kjb-restore-1.wav", 2, 440.0), "Restore One"), song(tone("kjb-restore-2.wav", 3, 550.0), "Restore Two"))
        val find: (SongRef) -> Song? = { ref -> songs.firstOrNull { it.path == ref.path } }
        val dir = File(context.cacheDir, "kjb-restore").apply {
            deleteRecursively()
            mkdirs()
        }
        cleanups += { dir.deleteRecursively() }
        val queues = File(dir, "queues.json")
        fun files(stores: CoroutineScope) = QueueFiles(queuesStore(queues, dir, stores, System::currentTimeMillis) {}, PlaceFile(File(dir, "playback.json")), Dispatchers.IO) {}

        val firstStores = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val before = build(songs, files(firstStores))
        assertTrue(waitFor(5_000) { before.core.ready.value })
        onMain {
            before.core.switch { it.play(songs.map(Song::ref), 1, QueueSource(SourceKind.ALBUM, "album:restore", "Tone Album"), System.currentTimeMillis()) }
            before.bridge.sync(before.core.book.value, find)
            before.engine.seek(1, 1_500)
        }
        assertTrue("the queues were never written", waitFor(5_000) { queues.length() > 0 })
        // As the service ends: its last place, written before it returns.
        onMain { before.core.savePlaceNow(before.engine.loaded[before.engine.index], before.engine.positionMs) }
        runBlocking { firstStores.coroutineContext.job.cancelAndJoin() }

        val againStores = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        cleanups += { againStores.cancel() }
        val again = build(songs, files(againStores))
        assertTrue(waitFor(5_000) { again.core.ready.value })
        onMain { again.bridge.sync(again.core.book.value, find) }
        assertEquals(1, onMain { again.engine.index })
        val position = onMain { again.engine.positionMs }
        assertTrue("opened at $position ms", position in 1_400L..1_600L)
    }

    @Test
    fun theServiceBrowseTreeAnswersAtItsRoot() {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val browser = MediaBrowser.Builder(context, token).setApplicationLooper(Looper.getMainLooper()).buildAsync().get(10, TimeUnit.SECONDS)
        cleanups += { browser.release() }
        val root = onMain { browser.getLibraryRoot(null) }.get(10, TimeUnit.SECONDS)
        assertEquals(LibraryTree.ROOT, root.value!!.mediaId)
        val children = onMain { browser.getChildren(LibraryTree.ROOT, 0, 20, null) }.get(10, TimeUnit.SECONDS)
        assertEquals(listOf(LibraryTree.QUEUES, LibraryTree.PLAYLISTS, LibraryTree.ALBUMS, LibraryTree.ARTISTS, LibraryTree.SONGS), children.value!!.map { it.mediaId })
    }

    private companion object {
        /** How late the second song may start: the player's startup and scheduling, not a gap in the sound. */
        const val GAP_LIMIT_MS: Long = 300
    }
}
