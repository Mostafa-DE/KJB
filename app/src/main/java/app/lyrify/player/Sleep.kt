package app.lyrify.player

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest

// The sleep timer (milestone P2): by time or by songs, the music fading out over the last 10 s and then pausing. The
// screens set it ([PlayerExtras.sleep]); [SleepDriver] runs it in the service, on the player.

/** The sleep sheet's choices: minutes after a time, songs after songs (1 is "End of song"). */
val SLEEP_MINUTES: List<Int> = listOf(15, 30, 45, 60)
val SLEEP_SONGS: List<Int> = listOf(1, 3, 5)

/** Custom choices, and the fade at the end. */
val SLEEP_CUSTOM_MINUTES: IntRange = 1..180
val SLEEP_CUSTOM_SONGS: IntRange = 1..30
const val SLEEP_FADE_MS: Long = 10_000

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
}

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
}

private const val MIN_SPEED: Float = 0.1f

/** What the timer does now: the player's [volume], whether the music [stops], and when to look again (null: once a song ends). */
data class SleepStep(val volume: Float, val stops: Boolean, val nextMs: Long?)

fun sleepStep(timer: SleepTimer, now: Long, playing: Boolean, positionMs: Long, durationMs: Long, speed: Float): SleepStep {
    val left = sleepLeftMs(timer, now, positionMs, durationMs, speed)
    if (timer is SleepTimer.ByTime && left == 0L) return SleepStep(1f, stops = true, nextMs = null)
    val volume = if (playing && left != null) (left.toFloat() / SLEEP_FADE_MS).coerceIn(0f, 1f) else 1f
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
}

/**
 * The sleep timer on the player ([port]), in the service: the fade over the last 10 s and the stop. By songs it counts
 * the songs that play to their end ([songEnded]); after the last one the music stops at the start of the next song, as
 * a paused player waits. [timer] is shared with the screens; [stopped] hears each stop. Main thread.
 */
class SleepDriver(
    private val port: ExtrasPort,
    private val timer: MutableStateFlow<SleepTimer?>,
    private val clock: () -> Long,
    private val stopped: () -> Unit = {},
) {
    /** Runs until cancelled: each timer set, until it stops or is turned off; without one the volume is full. */
    suspend fun run() {
        timer.collectLatest { running ->
            if (running == null) {
                port.volume = 1f
                awaitCancellation()
            }
            while (true) {
                val step = sleepStep(running, clock(), port.playing, port.positionMs, port.durationMs, port.speed)
                if (step.stops) return@collectLatest stop()
                port.volume = step.volume
                delay(step.nextMs ?: awaitCancellation())
            }
        }
    }

    /** A song played to its end (the next one starts, or the same again for Repeat one). */
    fun songEnded() {
        val running = timer.value as? SleepTimer.BySongs ?: return
        val after = sleepAfterSong(running)
        if (after != null) {
            timer.value = after
            return
        }
        // The next song has just started, faded to nothing: it waits at its start.
        port.setPlayWhenReady(false)
        port.seek(port.index, 0)
        stop()
    }

    /** The queue played to its end with Repeat off: the player waits paused already, and the timer is done. */
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
