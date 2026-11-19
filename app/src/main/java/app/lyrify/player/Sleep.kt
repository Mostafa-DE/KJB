package app.lyrify.player

import android.os.SystemClock
import androidx.media3.session.MediaLibraryService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

// The sleep timer (milestone P2): by time or by songs, the music fading out over the last 15 s on an ease-out curve
// and then pausing, its volume given back for the next play. The screens set it ([PlayerExtras.sleep]); [SleepDriver]
// runs it in the service, on the player.

/** The sleep sheet's choices: minutes after a time, songs after songs (1 is "End of song"). */
val SLEEP_MINUTES: List<Int> = listOf(15, 30, 45, 60)
val SLEEP_SONGS: List<Int> = listOf(1, 3, 5)

/** Custom choices, and the fade at the end (user feedback 2026-11-11: longer, from 10 s). */
val SLEEP_CUSTOM_MINUTES: IntRange = 1..180
val SLEEP_CUSTOM_SONGS: IntRange = 1..30
const val SLEEP_FADE_MS: Long = 15_000

/** How often the fade steps, and how often a last song of unknown length or end is looked at. */
private const val FADE_STEP_MS: Long = 100
private const val LOOK_EVERY_MS: Long = 1_000

/** A sleep timer that runs. [custom] when it came from Custom. */
sealed interface SleepTimer {
    val custom: Boolean

    /** Stops at [endsAt] (elapsed realtime, so the phone's clock changing does not move it), [minutes] after it was set. */
    data class ByTime(val endsAt: Long, val minutes: Int, override val custom: Boolean = false) : SleepTimer

    /** Stops at the end of the song [left] songs on, the one playing counting as the first; [songs] were chosen. */
    data class BySongs(val songs: Int, val left: Int, override val custom: Boolean = false) : SleepTimer

    /**
     * A book's "End of chapter" in a file of chapters: stops at [endMs] in the file, the end of the chapter playing; a
     * seek moves it to the end of the chapter it lands in ([seekedTo]), from the chapters' [ends].
     */
    data class ByChapter(val endMs: Long, val ends: List<Long>, override val custom: Boolean = false) : SleepTimer
}

/** "End of chapter" after a seek to [positionMs]: the end of the chapter it lands in. */
fun SleepTimer.ByChapter.seekedTo(positionMs: Long): SleepTimer.ByChapter = copy(endMs = ends.firstOrNull { it > positionMs } ?: ends.lastOrNull() ?: endMs)

/** A timer of [minutes], set at [now] (elapsed realtime). */
fun sleepFor(minutes: Int, now: Long, custom: Boolean = false): SleepTimer = SleepTimer.ByTime(now + minutes * 60_000L, minutes, custom)

/** A timer of [songs] songs, the one playing the first. */
fun sleepAfter(songs: Int, custom: Boolean = false): SleepTimer = SleepTimer.BySongs(songs, songs, custom)

/**
 * How long until the music stops, at [now] with the song at [positionMs] of [durationMs] playing at [speed]: by time,
 * until then; by songs, until the end of the last song once it plays; null while other songs come first or the song's
 * length is unknown.
 */
fun sleepLeftMs(timer: SleepTimer, now: Long, positionMs: Long, durationMs: Long, speed: Float): Long? = when (timer) {
    is SleepTimer.ByTime -> (timer.endsAt - now).coerceAtLeast(0)
    is SleepTimer.BySongs -> if (timer.left > 1 || durationMs <= 0) null else ((durationMs - positionMs).coerceAtLeast(0) / speed.coerceAtLeast(MIN_SPEED)).toLong()
    is SleepTimer.ByChapter -> ((timer.endMs - positionMs).coerceAtLeast(0) / speed.coerceAtLeast(MIN_SPEED)).toLong()
}

private const val MIN_SPEED: Float = 0.1f

/**
 * The fade's volume with [leftMs] to go: full until the last [SLEEP_FADE_MS], then down on an ease-out curve, the cube
 * of the share left, so it falls fast at first and slips gently into silence at the end, as the ear hears a smooth stop
 * (a straight line of the player's volume sounds loud until the last seconds, then drops).
 */
fun sleepFadeVolume(leftMs: Long): Float {
    val share = (leftMs.toFloat() / SLEEP_FADE_MS).coerceIn(0f, 1f)
    return share * share * share
}

/** What the timer does now: the player's [volume], whether the music [stops], and when to look again (null: once a song ends). */
data class SleepStep(val volume: Float, val stops: Boolean, val nextMs: Long?)

/**
 * The timer at [now], the song at [positionMs] of [durationMs] at [speed]. Paused inside the fade, the volume holds the
 * fade's level, so the music comes back on the curve, never at full; only the stop gives full volume back.
 */
fun sleepStep(timer: SleepTimer, now: Long, positionMs: Long, durationMs: Long, speed: Float): SleepStep {
    val left = sleepLeftMs(timer, now, positionMs, durationMs, speed)
    if ((timer is SleepTimer.ByTime || timer is SleepTimer.ByChapter) && left == 0L) return SleepStep(1f, stops = true, nextMs = null)
    val volume = if (left != null) sleepFadeVolume(left) else 1f
    val next = when {
        // Other songs first: nothing to do until one ends.
        timer is SleepTimer.BySongs && timer.left > 1 -> null
        left == null -> LOOK_EVERY_MS
        left <= SLEEP_FADE_MS -> FADE_STEP_MS
        // By time nothing moves the end; a song's end moves with seeks and pauses, so it is looked at again.
        timer is SleepTimer.ByTime -> left - SLEEP_FADE_MS
        else -> minOf(left - SLEEP_FADE_MS, LOOK_EVERY_MS)
    }
    return SleepStep(volume, stops = false, nextMs = next)
}

/** A song played to its end: by songs, one fewer to go, or null when that was the last (the music stops); by time, the same. */
fun sleepAfterSong(timer: SleepTimer): SleepTimer? = when (timer) {
    is SleepTimer.ByTime -> timer
    is SleepTimer.BySongs -> if (timer.left <= 1) null else timer.copy(left = timer.left - 1)
    // The file played to its end: past its last chapter.
    is SleepTimer.ByChapter -> null
}

/**
 * The sleep timer on the player ([port]), in the service: the fade over the last 15 s and the stop, the same for a time,
 * "End of song" and a count of songs. By songs it counts the songs that play to their end ([songEnded]); after the last
 * one the music stops at the start of the next song, as a paused player waits. The stop pauses first, then gives the
 * volume back, so the next play is never silent. The fade is the player's volume only: the crossfade engine scales its
 * own gains by it, and volume levelling works in the audio chain, so neither fights it. [timer] is shared with the
 * screens; [stopped] hears each stop. Main thread.
 */
class SleepDriver(
    private val port: ExtrasPort,
    private val timer: MutableStateFlow<SleepTimer?>,
    private val clock: () -> Long,
    private val stopped: () -> Unit = {},
    /** The music stopped at the start of a song (after the last song): that song is parked there. */
    private val parked: () -> Unit = {},
) {
    /** Runs until cancelled: each timer set, until it stops or is turned off; without one the volume is full. */
    suspend fun run() {
        timer.collectLatest { running ->
            if (running == null) {
                port.volume = 1f
                awaitCancellation()
            }
            while (true) {
                val step = sleepStep(running, clock(), port.positionMs, port.durationMs, port.speed)
                if (step.stops) return@collectLatest stop()
                port.volume = step.volume
                delay(step.nextMs ?: awaitCancellation())
            }
        }
    }

    /** The user jumped inside the song playing, to [toMs]: "End of chapter" now ends the chapter landed in. */
    fun seeked(toMs: Long) {
        val running = timer.value as? SleepTimer.ByChapter ?: return
        timer.value = running.seekedTo(toMs)
    }

    /** A song played to its end (the next one starts, or the same again for Repeat one). */
    fun songEnded() {
        if (timer.value is SleepTimer.ByChapter) return stop()
        val running = timer.value as? SleepTimer.BySongs ?: return
        val after = sleepAfterSong(running)
        if (after != null) {
            timer.value = after
            return
        }
        // The next song has just started, faded to nothing: it waits at its start.
        port.setPlayWhenReady(false)
        port.seek(port.index, 0)
        parked()
        stop()
    }

    /**
     * Nothing is left to play: the queue played to its end with Repeat off, or the song playing left it with none after
     * it ([ServicePart.queueStopped]). The player waits paused already; a timer by songs is done, as when it fires, and
     * one by time runs on.
     */
    fun queueEnded() {
        if (timer.value is SleepTimer.BySongs) stop()
    }

    private fun stop() {
        port.setPlayWhenReady(false)
        port.volume = 1f
        timer.value = null
        stopped()
    }
}

/**
 * The sleep timer in the service: [SleepDriver] on the engine, told of the songs that end and of the queue left with
 * nothing to play. A timer ends with the service.
 */
class SleepPart : ServicePart {
    private var scope: CoroutineScope? = null
    private var extras: PlayerExtras? = null
    private var driver: SleepDriver? = null

    override fun start(service: PlaybackService, session: MediaLibraryService.MediaLibrarySession) {
        val engine = service.playbackEngine
        val shared = PlayerExtras.of(service).also { extras = it }
        val driver = SleepDriver(
            EngineExtras(engine),
            shared.sleep,
            SystemClock::elapsedRealtime,
            stopped = { shared.say(ExtrasSaid.SleepEnded) },
            parked = { shared.parked = engine.loaded.getOrNull(engine.index) },
        ).also { this.driver = it }
        engine.addListener(object : EngineListener {
            override fun onSongStarted(uid: Long, lap: Boolean, natural: Boolean) {
                if (natural) driver.songEnded()
            }

            override fun onSeeked(fromMs: Long, toMs: Long) = driver.seeked(toMs)

            override fun onEnded() = driver.queueEnded()
        })
        scope = partScope().also { it.launch { driver.run() } }
    }

    override fun queueStopped() {
        driver?.queueEnded()
    }

    override fun stop() {
        scope?.cancel()
        scope = null
        driver = null
        extras?.sleep?.value = null
    }
}
