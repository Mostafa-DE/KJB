package app.lyrify.player

import android.content.Intent
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.media3.common.PlaybackParameters
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.sin
import android.media.session.MediaController as PlatformController

/**
 * P2's extras on the phone (debug build), on made-up test tones in the app's cache: speed and pitch reach the player and
 * the session; headset presses sent as the phone sends them (through the platform session) play or pause, skip and go
 * back; the sleep timer's fade turns the real player's volume down.
 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class PlaybackExtrasDeviceTest {
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

    private fun song(file: File, title: String) = Song(
        ref = SongRef(file.path),
        title = title,
        artist = "Test Tones",
        album = "Tone Album",
        albumArtist = null,
        durationMs = 20_000,
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

    private class Built(val engine: GaplessEngine, val player: LyrifyPlayer, val session: MediaSession)

    /** Three 20 s tones in a queue, in the facade and engine the service builds, behind a session whose callback is P2's headset buttons. */
    private fun build(headsetButtons: Boolean = true): Built {
        val songs = (1..3).map { song(tone("kjb-extras-$it.wav", 20, 330.0 + it * 110), "Tone $it") }
        val built = onMain {
            val core = PlayerCore(MemoryStorage(), scope, System::currentTimeMillis)
            val engine = GaplessEngine(context, { Settings() }, NoLevels) { mediaItemOf(it, LibrarySettings(), CarCovers.uris(context)) }
            val bridge = QueueBridge(engine)
            val find: (SongRef) -> Song? = { ref -> songs.firstOrNull { it.path == ref.path } }
            val player = LyrifyPlayer(Looper.getMainLooper(), engine, core, { SkipAmounts(30_000, 10_000) }, { bridge.sync(core.book.value, find) })
            val headset = HeadsetButtons(scope) { headsetButtons }
            val session = MediaSession.Builder(context, player)
                .setId("kjb-extras-test")
                .setCallback(object : MediaSession.Callback {
                    override fun onMediaButtonEvent(session: MediaSession, controllerInfo: MediaSession.ControllerInfo, intent: Intent): Boolean =
                        headset.onMediaButton(session, controllerInfo, intent) || super.onMediaButtonEvent(session, controllerInfo, intent)
                })
                .build()
            cleanups += {
                session.release()
                player.release()
                engine.release()
            }
            scope.launch {
                core.ready.first { it }
                core.switch { it.play(songs.map(Song::ref), 1, QueueSource(SourceKind.ALBUM, "album:tones", "Tone Album"), System.currentTimeMillis()) }
                bridge.sync(core.book.value, find)
            }
            Built(engine, player, session)
        }
        assertTrue("the queue never loaded", waitFor(5_000) { built.engine.loaded.size == 3 })
        return built
    }

    @Test
    fun speedAndPitchReachThePlayerAndTheSession() {
        val built = build()
        val controller = MediaController.Builder(context, built.session.token).setApplicationLooper(Looper.getMainLooper()).buildAsync().get(10, TimeUnit.SECONDS)
        cleanups += { controller.release() }
        val speed = Speed(1.25f, keepPitch = false, semitones = 2)
        onMain { EngineExtras(built.engine).setSpeed(speed.speed, speed.pitch) }
        assertEquals(PlaybackParameters(1.25f, speed.pitch), onMain { built.engine.playbackParameters })
        assertTrue("the session never showed the speed", waitFor(3_000) { controller.playbackParameters.speed == 1.25f })
        // At 1.25x the song moves 1.25 s a second, measured once the speed is heard: the player fills its audio output
        // while paused, and Media3 plays what it holds (250 to 750 ms, at 1x here) before a new speed, after the output's
        // own start. From play, 2 s moved about 2.1 s on the phone. Within 0.1 of 1.25 tells it from 1x and from the pitch's 1.40.
        onMain { built.engine.setPlayWhenReady(true) }
        assertTrue("never played past the audio held at 1x", waitFor(5_000) { built.engine.isPlaying && built.engine.positionMs > 1_500 })
        val (fromMs, fromAt) = onMain { built.engine.positionMs to SystemClock.elapsedRealtime() }
        SystemClock.sleep(2_000)
        val (toMs, toAt) = onMain { built.engine.positionMs to SystemClock.elapsedRealtime() }
        val rate = (toMs - fromMs).toFloat() / (toAt - fromAt)
        assertTrue("moved ${toMs - fromMs} ms in ${toAt - fromAt} ms", rate in 1.15f..1.35f)
        // Nothing set it back while it played.
        assertEquals(PlaybackParameters(1.25f, speed.pitch), onMain { built.engine.playbackParameters })
    }

    @Test
    fun headsetPressesPlayOrPauseSkipAndGoBack() {
        val built = build()
        val platform = PlatformController(context, built.session.platformToken)
        fun press(times: Int) {
            repeat(times) {
                platform.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HEADSETHOOK))
                platform.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HEADSETHOOK))
                SystemClock.sleep(120)
            }
            SystemClock.sleep(PRESS_WINDOW_MS + 300)
        }
        assertEquals(1, onMain { built.engine.index })
        press(1)
        assertTrue("one press did not play", waitFor(3_000) { built.engine.playWhenReady })
        press(2)
        assertTrue("two presses did not skip", waitFor(3_000) { built.engine.index == 2 })
        press(3)
        assertTrue("three presses did not go back", waitFor(3_000) { built.engine.index == 1 })
        press(1)
        assertTrue("one press did not pause", waitFor(3_000) { !built.engine.playWhenReady })
        // A car's Next is not counted: it goes on at once.
        platform.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT))
        platform.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_NEXT))
        assertTrue("Next did not skip", waitFor(1_000) { built.engine.index == 2 })
    }

    @Test
    fun withHeadsetButtonsOffOnlyHeadsetPressesAreIgnored() {
        val built = build(headsetButtons = false)
        val platform = PlatformController(context, built.session.platformToken)
        fun key(code: Int) {
            platform.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            platform.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        }
        // A headset's press: KJB ignores it ("Headset buttons are off in Settings").
        key(KeyEvent.KEYCODE_HEADSETHOOK)
        SystemClock.sleep(PRESS_WINDOW_MS + 500)
        assertFalse("a headset press played with Headset buttons off", onMain { built.engine.playWhenReady })
        // The lock screen and the phone's media card: the session's transport controls.
        platform.transportControls.play()
        assertTrue("Play from the media card did nothing", waitFor(3_000) { built.engine.playWhenReady })
        platform.transportControls.pause()
        assertTrue("Pause from the media card did nothing", waitFor(3_000) { !built.engine.playWhenReady })
        // A MediaController: the widget, a car's screen, the app itself.
        val controller = MediaController.Builder(context, built.session.token).setApplicationLooper(Looper.getMainLooper()).buildAsync().get(10, TimeUnit.SECONDS)
        cleanups += { controller.release() }
        onMain { controller.play() }
        assertTrue("Play from a controller did nothing", waitFor(3_000) { built.engine.playWhenReady })
        onMain { controller.pause() }
        assertTrue("Pause from a controller did nothing", waitFor(3_000) { !built.engine.playWhenReady })
        // A car's or a two-button headset's own Play key is no headset press.
        key(KeyEvent.KEYCODE_MEDIA_PLAY)
        assertTrue("the Play key did nothing", waitFor(3_000) { built.engine.playWhenReady })
    }

    @Test
    fun theSleepFadeTurnsThePlayersVolumeDown() {
        val built = build()
        val timer = MutableStateFlow<SleepTimer?>(null)
        onMain {
            built.engine.setPlayWhenReady(true)
            val driver = SleepDriver(EngineExtras(built.engine), timer, SystemClock::elapsedRealtime)
            scope.launch { driver.run() }
            // Twelve seconds to go: inside the 15 s fade.
            timer.value = SleepTimer.ByTime(SystemClock.elapsedRealtime() + 12_000, minutes = 1)
        }
        assertTrue("never played", waitFor(5_000) { built.engine.isPlaying })
        assertTrue("the volume never went down", waitFor(2_000) { built.engine.volume in 0.1f..0.6f })
        assertTrue("the music never paused", waitFor(14_000) { !built.engine.playWhenReady })
        assertEquals(1f, onMain { built.engine.volume }, 0f)
        assertEquals(null, onMain { timer.value })
    }
}
