package app.lyrify.audio

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lyrify.auto.CarCovers
import app.lyrify.library.Song
import app.lyrify.library.SongRef
import app.lyrify.player.EngineListener
import app.lyrify.player.GaplessEngine
import app.lyrify.player.Projected
import app.lyrify.player.SongLevels
import app.lyrify.player.mediaItemOf
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
import kotlin.math.PI
import kotlin.math.sin

/**
 * Crossfade on the phone (debug build), with real ExoPlayers: made-up test tones in the app's cache, never in shared
 * storage. A natural change fades on a second player and the session's song moves at the fade's start; a Next by hand
 * fades over 300 ms; the second player plays in the first one's audio session. Every player call is on the main thread,
 * as ExoPlayer requires.
 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class CrossfadeDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val main = Handler(Looper.getMainLooper())
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
            SystemClock.sleep(20)
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

    private fun song(file: File, title: String, seconds: Int) = Song(
        ref = SongRef(file.path),
        title = title,
        artist = "Test Tones",
        album = null,
        albumArtist = null,
        durationMs = seconds * 1_000L,
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

    private object NoLevels : SongLevels {
        override fun albumInOrder(mediaId: String): Boolean = false
    }

    private object AnyFade : FadeRules {
        override fun mayFade(from: Long, to: Long): Boolean = true

        override fun parametersOf(uid: Long): PlaybackParameters? = null

        override fun placeOf(uid: Long, durationMs: Long): Long? = null
    }

    private class Built(val engine: CrossfadeEngine, val made: List<GaplessEngine>)

    /** The engine as the service builds it, crossfade [seconds] on, over [songs] as uids 1, 2, 3. */
    private fun build(songs: List<Song>, seconds: Int): Built = onMain {
        val made = mutableListOf<GaplessEngine>()
        val engine = CrossfadeEngine(
            newSlot = { GaplessEngine(context, { Settings() }, NoLevels) { mediaItemOf(it, LibrarySettings(), CarCovers.uris(context)) }.also { made += it } },
            rules = AnyFade,
            focusOf = { control -> MediaFocus(context, Looper.getMainLooper(), control) },
            scope = scope,
            clock = SystemClock::elapsedRealtime,
            later = { task -> main.post(task) },
        )
        engine.setCrossfade(seconds)
        engine.load(songs.mapIndexed { i, s -> Projected(i + 1L, s) }, 0, 0)
        cleanups += { engine.release() }
        Built(engine, made)
    }

    @Test
    fun aNaturalChangeFadesOnASecondPlayerAndTheSongMovesAtTheFadesStart() {
        val songs = listOf(song(tone("kjb-fade-1.wav", 8, 440.0), "Fade One", 8), song(tone("kjb-fade-2.wav", 8, 550.0), "Fade Two", 8))
        val built = build(songs, seconds = 2)
        var playingAt = 0L
        var secondAt = 0L
        var sawNatural = false
        var changes = 0
        onMain {
            built.engine.addListener(object : EngineListener {
                override fun onChanged() {
                    changes++
                }

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
            built.engine.setPlayWhenReady(true)
        }
        assertTrue("the second song never came in", waitFor(12_000) { secondAt != 0L })
        assertTrue("not a natural change", sawNatural)
        // An 8 s song fading over 2 s: the song coming in starts about 6 s in.
        val first = secondAt - playingAt
        assertTrue("the fade started after $first ms", first in 5_500L..6_600L)
        assertEquals(2, built.made.size)
        assertEquals(1, onMain { built.engine.index })
        val (firstSession, sessionNow) = onMain { built.made[0].audioSessionId to built.engine.audioSessionId }
        assertEquals(firstSession, sessionNow)
        // The song coming in reaches 2 s about 2 s after the fade's start; it plays 8 s, so 5 s is room, not a pass by chance.
        assertTrue("never playing after the fade", waitFor(5_000) { built.engine.isPlaying && built.engine.positionMs > 2_000 })
        // The fade's 80 gain steps are no news for the session: a few changes in all, not one per step.
        assertTrue("the session heard $changes changes", changes < 60)
    }

    /**
     * Songs long enough that nothing waits on a second player yet at the Next (the next song loads 5 s before its 6 s fade,
     * 9 s into a 20 s song), so the Next makes the one player it fades in on. With the next song waiting, the Next lets
     * that player go and makes another (CrossfadeEngineTest covers it), so the count would not be the Next's alone.
     */
    @Test
    fun aNextByHandFadesOverAShortFadeAndPlaysOn() {
        val songs = listOf(song(tone("kjb-skip-1.wav", 20, 440.0), "Skip One", 20), song(tone("kjb-skip-2.wav", 20, 660.0), "Skip Two", 20))
        val built = build(songs, seconds = 6)
        onMain { built.engine.setPlayWhenReady(true) }
        assertTrue("never played", waitFor(3_000) { built.engine.isPlaying })
        SystemClock.sleep(500)
        onMain { built.engine.seek(1, 0) }
        assertEquals(1, onMain { built.engine.index })
        assertTrue("the next song never played", waitFor(2_000) { built.engine.isPlaying && built.engine.positionMs > 400 })
        assertEquals(2, built.made.size)
    }
}
