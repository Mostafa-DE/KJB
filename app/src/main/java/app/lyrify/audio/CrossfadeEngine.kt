@file:OptIn(UnstableApi::class)

package app.lyrify.audio

import android.content.Context
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioFocusManager
import androidx.media3.common.util.UnstableApi
import app.lyrify.player.AbPart
import app.lyrify.player.Edit
import app.lyrify.player.EngineListener
import app.lyrify.player.PlaybackEngine
import app.lyrify.player.PlayerCore
import app.lyrify.player.PlayerExtras
import app.lyrify.player.Projected
import app.lyrify.player.Queue
import app.lyrify.player.Repeat
import app.lyrify.player.SleepTimer
import app.lyrify.player.SongState
import app.lyrify.player.albumInOrder
import app.lyrify.player.resumeAt
import app.lyrify.settings.RememberPlace
import app.lyrify.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin

// Crossfade (milestone A2): two ExoPlayers behind the one player the media session sees (LyrifyPlayer). The rules are
// pure functions (the curves, when a fade is planned, which changes never fade); [CrossfadeEngine] is the two-player
// state machine over [FadeSlot]s (GaplessEngine in the app, fakes in tests); the glue at the end ties it to the queue,
// the extras and Media3's audio focus.

/** The fade of a song changed by hand (Next, Previous, a tap in the queue), and of a seek during a fade. */
const val SHORT_FADE_MS: Long = 300

/** The next song is loaded on the second player this long before its fade starts. */
const val PREPARE_LEAD_MS: Long = 5_000

/** How often the gains step during a fade, and the longest wait between looks while a song plays. */
const val FADE_STEP_MS: Long = 25
const val LOOK_EVERY_MS: Long = 1_000

/** A fade paused this long is finished, so two paused players are not kept. */
const val FROZEN_FADE_MS: Long = 60_000

private const val HALF_PI: Float = (PI / 2).toFloat()
private const val MAX_CROSSFADE_SECONDS: Int = 12
private const val MIN_SPEED: Float = 0.1f

/** The song coming in at [share] (0 to 1) of a fade. Equal power: with [fadeOutGain] the squares add up to 1. */
fun fadeInGain(share: Float): Float = sin(share.coerceIn(0f, 1f) * HALF_PI).coerceIn(0f, 1f)

/** The song going out at [share] of a fade. */
fun fadeOutGain(share: Float): Float = cos(share.coerceIn(0f, 1f) * HALF_PI).coerceIn(0f, 1f)

/**
 * A gain moving [up] to full or down to silence over [lengthMs] of play ([playedMs] so far), on the equal-power curve
 * from where it stood ([fromGain]): from silence or from full it is [fadeInGain] or [fadeOutGain] exactly, and a fade
 * shortened part way (a seek, a skip) goes on from its gain without a jump.
 */
data class Ramp(val up: Boolean, val lengthMs: Long, val fromGain: Float = if (up) 0f else 1f, val playedMs: Long = 0) {
    val gain: Float
        get() {
            val share = if (lengthMs <= 0) 1f else (playedMs.toFloat() / lengthMs).coerceIn(0f, 1f)
            val from = fromGain.coerceIn(0f, 1f)
            val gain = if (up) {
                val start = asin(from)
                sin(start + share * (HALF_PI - start))
            } else {
                val start = acos(from)
                cos(start + share * (HALF_PI - start))
            }
            return gain.coerceIn(0f, 1f)
        }

    val done: Boolean get() = playedMs >= lengthMs

    /** [ms] more of play. */
    fun played(ms: Long): Ramp = copy(playedMs = (playedMs + ms).coerceAtMost(lengthMs))
}

/**
 * How long, in time, a song [outMs] long playing at [outSpeed] fades into one that plays [inMs] (null: not known yet) at
 * [inSpeed]: "Crossfade", at most half of either song as each one plays, since the fade runs on the clock.
 */
fun naturalFadeMs(crossfadeMs: Long, outMs: Long, outSpeed: Float, inMs: Long?, inSpeed: Float): Long {
    val inPart = inMs ?: outMs
    if (crossfadeMs <= 0 || outMs <= 0 || inPart <= 0) return 0
    return minOf(crossfadeMs.toFloat(), outMs / 2 / outSpeed.coerceAtLeast(MIN_SPEED), inPart / 2 / inSpeed.coerceAtLeast(MIN_SPEED)).toLong()
}

/** What a natural change needs now: the next song loaded ([prepare]), the fade begun ([start]), and when to look again. */
data class FadeStep(val prepare: Boolean, val start: Boolean, val nextMs: Long)

/**
 * The plan for a song [leftMs] of time from its end that fades over [fadeMs]: load the next song [PREPARE_LEAD_MS]
 * before the fade, start the fade once it is [ready], and let a song too near its end play into the next one gapless.
 */
fun fadeStep(leftMs: Long, fadeMs: Long, ready: Boolean): FadeStep = when {
    leftMs <= FADE_STEP_MS -> FadeStep(prepare = false, start = false, nextMs = LOOK_EVERY_MS)
    leftMs > fadeMs + PREPARE_LEAD_MS -> FadeStep(prepare = false, start = false, nextMs = (leftMs - fadeMs - PREPARE_LEAD_MS).coerceIn(FADE_STEP_MS, LOOK_EVERY_MS))
    leftMs > fadeMs -> FadeStep(prepare = true, start = false, nextMs = (leftMs - fadeMs).coerceIn(FADE_STEP_MS, LOOK_EVERY_MS))
    else -> FadeStep(prepare = true, start = ready, nextMs = FADE_STEP_MS)
}

/** The song after the one at [index] of [size]: the next, the first again with Repeat all; none with Repeat one or at the end. */
fun nextIndexFor(index: Int, size: Int, repeat: Repeat): Int? = when {
    index !in 0 until size || repeat == Repeat.ONE -> null
    index + 1 < size -> index + 1
    repeat == Repeat.ALL && size > 1 -> 0
    else -> null
}

/**
 * Whether song [from] of [queue] may fade into song [to] as it plays to its end: not inside an album played in track
 * order while "Keep albums gapless" is on ([keepAlbumsGapless], [albumInOrder]); not out of a song with a part
 * repeating ([part], A-B: it loops, it does not move on); and not past the sleep timer's last song ([sleep] by songs),
 * which ends as it would and stops the music there.
 */
fun mayCrossfade(queue: Queue?, from: Long, to: Long, keepAlbumsGapless: Boolean, part: AbPart?, sleep: SleepTimer?): Boolean {
    if (queue == null) return false
    if (keepAlbumsGapless && albumInOrder(queue, queue.items.indexOfFirst { it.uid == to })) return false
    if (part != null && queue.items.firstOrNull { it.uid == from }?.song?.path == part.path) return false
    return !(sleep is SleepTimer.BySongs && sleep.left <= 1)
}

/**
 * Where song [uid] of [queue], [durationMs] long, comes in: its kept place when Remember my place ([mode]) picks it up
 * there, as P2's resume would; null for its start, and for a song with a part repeating, which starts in its part.
 */
fun comingInAt(queue: Queue?, uid: Long, durationMs: Long, state: SongState, mode: RememberPlace): Long? {
    val path = queue?.items?.firstOrNull { it.uid == uid }?.song?.path ?: return null
    if (state.part?.path == path) return null
    return resumeAt(mode, durationMs, state.places[path], 0)
}

/** One ExoPlayer as the crossfade engine drives it: a [PlaybackEngine] holding the queue (GaplessEngine), and what a fade needs. */
interface FadeSlot : PlaybackEngine {
    /** Loads the media items another player holds, as they are. */
    fun loadItems(items: List<MediaItem>, index: Int, positionMs: Long)

    /** The song going out of a fade pauses at its end instead of going on to the next song. */
    fun setPauseAtEndOfItems(pause: Boolean)

    /** Whether this player asks for audio focus itself (alone, crossfade off) or leaves it to the engine (two). */
    fun setFocusHandled(handled: Boolean)

    /** Plays in audio session [id], shared by both players, so what the phone binds to the session follows the music. */
    fun setAudioSessionId(id: Int)
}

/** What the engine asks the app before a fade. Main thread, quick, no I/O. */
interface FadeRules {
    /** Whether song [from] may fade into song [to] as it plays to its end ([mayCrossfade]). */
    fun mayFade(from: Long, to: Long): Boolean

    /** The speed and pitch song [uid] plays at, so the song coming in starts at its own; null for those of the song playing. */
    fun parametersOf(uid: Long): PlaybackParameters?

    /** Where song [uid], [durationMs] long, comes in: its kept place ([comingInAt]); null for its start. */
    fun placeOf(uid: Long, durationMs: Long): Long?
}

/** The phone's audio focus, one for both players while crossfade is on ([MediaFocus]: Media3's own rules). */
interface FocusPort {
    /** On, the engine asks for focus; off, it leaves focus alone (the one player asks for it itself). */
    fun setHandled(handled: Boolean)

    /** Asks for, keeps or gives up focus for [playWhenReady] in [playbackState]: the command to play by (AudioFocusManager's). */
    fun update(playWhenReady: Boolean, playbackState: Int): Int

    fun release()
}

/**
 * The engine behind KJB's session player ([app.lyrify.player.LyrifyPlayer]), over one or two players ([FadeSlot], each
 * with its own audio chain from the one settings object).
 *
 * - Off ([setCrossfade] 0): exactly the one gapless player as before. Every call goes straight to it, it asks for audio
 *   focus itself, and no second player is ever made.
 * - On: the player playing holds the queue. [PREPARE_LEAD_MS] before a natural change would fade, a second player loads
 *   the queue at the next song and waits, silent, at that song's own speed. At the fade's start it plays, the two cross
 *   on the equal-power curve over the chosen seconds (at most half of either song), and the player going out pauses
 *   at its end and is let go. A song changed by hand while playing goes the same way over [SHORT_FADE_MS]. Nothing
 *   fades while paused, with Repeat one, at the end of the queue, or when [FadeRules.mayFade] says no.
 * - The session sees the song coming in from the fade's start: place, position, length and speed are those of the
 *   player playing, and [EngineListener.onSongStarted] tells of it once, then, so a play counts once, from its start.
 * - During a fade: a pause stops both players and the fade waits ([FROZEN_FADE_MS] at most); a seek or a change by
 *   hand happens on the song playing while the song going out fades away within the short fade, so there are never
 *   more than two players; the sleep timer's [volume] scales both.
 * - Audio focus is the engine's ([FocusPort]), one for both players: a call pauses both, ducking lowers both. Offload
 *   stays off, and both players share one audio session.
 *
 * Main thread; [scope] runs the looks ([FADE_STEP_MS] apart during a fade), [clock] is elapsed realtime, and [later]
 * runs a task on the main thread's next turn: a player let go is released there, never inside its own callback.
 */
class CrossfadeEngine(
    private val newSlot: () -> FadeSlot,
    private val rules: FadeRules,
    focusOf: (AudioFocusManager.PlayerControl) -> FocusPort,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val later: (() -> Unit) -> Unit,
) : PlaybackEngine {
    private val listeners = mutableListOf<EngineListener>()

    /** The player of the song playing: the one the session sees. */
    private var active: FadeSlot = newSlot().also { it.addListener(SlotEvents(it)) }

    /** The next song, loaded and waiting silent on the second player ([standbyFor] its uid, at [standbyAt]). */
    private var standby: FadeSlot? = null
    private var standbyFor: Long? = null
    private var standbyAt: Long = 0

    /** The song going out of a fade, on the other player, and its fade; the song playing's fade in. */
    private var tail: FadeSlot? = null
    private var tailRamp: Ramp? = null
    private var activeRamp: Ramp? = null

    /** A next song that failed to load ahead: its change plays gapless. */
    private var failedAhead: Long? = null

    private var crossfadeMs: Long = 0
    private var wantPlay: Boolean = false
    private var suppressed: Boolean = false
    private var duck: Float = 1f
    private var master: Float = 1f
    private var repeat: Repeat = Repeat.OFF
    private var gapless: Boolean = true

    /** Offload as wanted (A1's gate, [setOffload]); after crossfade is turned off it waits for the next song's start. */
    private var offload: Boolean = false
    private var offloadAtNextSong: Boolean = false

    /** The last look, and whether each fade's player played since: a fade moves only while its player plays. */
    private var lookedAt: Long = clock()
    private var tailRan: Boolean = false
    private var activeRan: Boolean = false
    private var pausedAt: Long? = null
    private var next: Job? = null
    private var looking: Boolean = false
    private var lookAgain: Boolean = false

    /** Whether the listeners were last told it plays. */
    private var told: Boolean = false
    private val focus: FocusPort = focusOf(FocusControl())

    private val on: Boolean get() = crossfadeMs > 0
    private val fading: Boolean get() = tail != null || activeRamp != null

    /** The music should be heard: wanted and not held by a call. */
    private val sounding: Boolean get() = if (on) wantPlay && !suppressed else active.playWhenReady

    /** Playback > "Crossfade" in seconds, 0 for Off. Applies from now; turned off, a fade running ends within the short fade. */
    fun setCrossfade(seconds: Int) {
        val ms = seconds.coerceIn(0, MAX_CROSSFADE_SECONDS) * 1_000L
        if (ms == crossfadeMs) return
        val wasOn = on
        crossfadeMs = ms
        if (on != wasOn) {
            if (on) {
                // The engine takes the one player's focus over; offload goes off at once.
                offloadAtNextSong = false
                active.setOffload(false)
                wantPlay = active.playWhenReady
                suppressed = false
                active.setFocusHandled(false)
                focus.setHandled(true)
                updateFocus()
                applyPlay()
            } else {
                dropStandby()
                if (fading) shorten(fromSilence = false)
                focus.setHandled(false)
                obey(focus.update(wantPlay, active.playbackState))
                duck = 1f
                active.setFocusHandled(true)
                active.setPlayWhenReady(wantPlay)
                applyVolumes()
                // Never on mid-song, as A1's gate keeps it.
                offloadAtNextSong = offload
            }
        }
        look()
    }

    override val loaded: List<Long> get() = active.loaded
    override val index: Int get() = active.index
    override val positionMs: Long get() = active.positionMs
    override val items: List<MediaItem> get() = active.items
    override val playWhenReady: Boolean get() = if (on) wantPlay else active.playWhenReady
    override val playbackState: Int get() = active.playbackState
    override val isPlaying: Boolean get() = active.isPlaying
    override val isLoading: Boolean get() = active.isLoading
    override val suppression: Int
        get() = if (on && suppressed) Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS else active.suppression
    override val error: PlaybackException? get() = active.error
    override val audioSessionId: Int get() = active.audioSessionId
    override val bufferedMs: Long get() = active.bufferedMs
    override val durationMs: Long get() = active.durationMs
    override val playbackParameters: PlaybackParameters get() = active.playbackParameters

    /** The sleep timer's volume, over both players. */
    override var volume: Float
        get() = master
        set(value) {
            master = value
            if (!on && !fading) active.volume = value else applyVolumes()
        }

    override fun load(items: List<Projected>, index: Int, positionMs: Long) {
        if (!on && !fading) return active.load(items, index, positionMs)
        dropStandby()
        when {
            items.isEmpty() || index < 0 -> {
                letTailGo()
                activeRamp = null
                active.load(items, index, positionMs)
            }
            fading -> {
                shorten(fromSilence = true)
                active.load(items, index, positionMs)
            }
            on && sounding && active.isPlaying -> return swap(items.getOrNull(index)?.uid?.let(rules::parametersOf)) { it.load(items, index, positionMs) }
            else -> active.load(items, index, positionMs)
        }
        applyVolumes()
        look()
    }

    override fun apply(edit: Edit) {
        // The song waiting may not be the next one any more: it loads again at the next look.
        dropStandby()
        active.apply(edit)
        look()
    }

    override fun seek(index: Int, positionMs: Long) {
        if (!on && !fading) return active.seek(index, positionMs)
        val loaded = active.loaded
        if (index !in loaded.indices) return
        dropStandby()
        val other = index != active.index
        when {
            fading -> {
                shorten(fromSilence = other)
                active.seek(index, positionMs)
            }
            on && other && sounding && active.isPlaying -> {
                val items = active.items
                return swap(rules.parametersOf(loaded[index])) { it.loadItems(items, index, positionMs) }
            }
            else -> active.seek(index, positionMs)
        }
        applyVolumes()
        look()
    }

    override fun setPlaybackParameters(parameters: PlaybackParameters) {
        active.setPlaybackParameters(parameters)
        look()
    }

    override fun setPlayWhenReady(play: Boolean) {
        if (!on) {
            active.setPlayWhenReady(play)
            // Only right after crossfade was turned off in a fade: the song going out pauses too.
            tail?.setPlayWhenReady(play)
            return
        }
        wantPlay = play
        if (play && active.playbackState == Player.STATE_IDLE) active.prepare()
        updateFocus()
        applyPlay()
        look()
    }

    override fun prepare() {
        active.prepare()
        if (!on) return
        updateFocus()
        applyPlay()
        look()
    }

    override fun stop() {
        if (!on && !fading) return active.stop()
        letTailGo()
        dropStandby()
        activeRamp = null
        active.stop()
        if (on) updateFocus()
        applyVolumes()
        look()
    }

    override fun release() {
        next?.cancel()
        next = null
        listeners.clear()
        letTailGo()
        dropStandby()
        focus.release()
        active.release()
    }

    override fun setRepeat(repeat: Repeat) {
        this.repeat = repeat
        active.setRepeat(repeat)
        look()
    }

    /** Offload as A1's gate wants it (off at once, on only at a song start); the players never have it while crossfade is on. */
    override fun setOffload(allowed: Boolean) {
        offload = allowed
        offloadAtNextSong = false
        if (!on) active.setOffload(allowed)
    }

    override fun setGapless(on: Boolean) {
        gapless = on
        active.setGapless(on)
        standby?.setGapless(on)
    }

    override fun addListener(listener: EngineListener) {
        listeners += listener
    }

    // Fades

    /**
     * Looks at the fades now: moves each fade by the time its player played since the last look, ends the ones done,
     * plans the next natural change, sets the gains, and books the next look. A look asked for during one (a listener
     * moving the player) runs right after it.
     */
    private fun look() {
        if (!on && !fading) return schedule(null)
        if (looking) {
            lookAgain = true
            return
        }
        looking = true
        try {
            do {
                lookAgain = false
                lookOnce()
            } while (lookAgain)
        } finally {
            looking = false
        }
    }

    private fun lookOnce() {
        advance()
        val now = lookedAt
        if (sounding) pausedAt = null else if (pausedAt == null) pausedAt = now
        if (tailRamp?.done == true || tail?.let(::finished) == true) letTailGo()
        if (activeRamp?.done == true) activeRamp = null
        val paused = pausedAt
        if (fading && paused != null && now - paused >= FROZEN_FADE_MS) {
            letTailGo()
            activeRamp = null
        }
        val planned = if (on) plan() else null
        applyVolumes()
        tailRan = tail?.isPlaying == true
        activeRan = active.isPlaying
        schedule(
            when {
                fading && paused != null -> FROZEN_FADE_MS - (now - paused)
                fading -> FADE_STEP_MS
                else -> planned
            },
        )
    }

    /** Moves each fade by the time its player played since the last look; before any fade or player changes role. */
    private fun advance() {
        val now = clock()
        val since = (now - lookedAt).coerceAtLeast(0)
        lookedAt = now
        if (tailRan) tailRamp = tailRamp?.played(since)
        if (activeRan) activeRamp = activeRamp?.played(since)
    }

    /** The natural change ahead: the next song loaded in time and its fade begun, or a change played gapless. */
    private fun plan(): Long? {
        if (fading) return FADE_STEP_MS
        if (!sounding) {
            dropStandby()
            return null
        }
        if (!active.isPlaying) return LOOK_EVERY_MS
        val loaded = active.loaded
        val at = active.index
        val from = loaded.getOrNull(at)
        val toIndex = nextIndexFor(at, loaded.size, repeat)
        val outMs = active.durationMs
        if (from == null || toIndex == null || outMs <= 0) {
            dropStandby()
            return if (from == null || toIndex == null) null else LOOK_EVERY_MS
        }
        val to = loaded[toIndex]
        val outSpeed = active.playbackParameters.speed.coerceAtLeast(MIN_SPEED)
        val leftMs = ((outMs - active.positionMs) / outSpeed).toLong()
        val waiting = standby?.takeIf { standbyFor == to }
        // Far from the end the song coming in is not needed yet.
        val coming = if (leftMs > crossfadeMs + PREPARE_LEAD_MS) null else comingIn(toIndex, to, waiting)
        val fadeMs = naturalFadeMs(crossfadeMs, outMs, outSpeed, coming?.playsMs, coming?.parameters?.speed ?: outSpeed)
        val step = fadeStep(leftMs, fadeMs, ready = waiting?.playbackState == Player.STATE_READY && waiting.error == null)
        // A song waiting already stays while its fade may still come: its real length may have shortened the fade.
        val stays = waiting != null && leftMs > FADE_STEP_MS && leftMs <= crossfadeMs + PREPARE_LEAD_MS
        if (!step.prepare && !stays) {
            dropStandby()
            return step.nextMs
        }
        if (to == failedAhead || !rules.mayFade(from, to)) {
            dropStandby()
            return LOOK_EVERY_MS
        }
        if (waiting == null) prepareAhead(toIndex, to, coming ?: comingIn(toIndex, to, null))
        if (step.start) startFade(minOf(fadeMs, leftMs))
        return step.nextMs
    }

    /** The song coming in as its fade sees it: where it comes in ([atMs]), how much of it plays from there, its speed. */
    private class Coming(val atMs: Long, val playsMs: Long?, val parameters: PlaybackParameters?)

    /**
     * The song [to] at [index], coming in: at its kept place, if any; as long as the library says, or as its file really
     * is once loaded on the second player ([waiting]), whichever is shorter; at its own speed.
     */
    private fun comingIn(index: Int, to: Long, waiting: FadeSlot?): Coming {
        val libraryMs = active.items.getOrNull(index)?.mediaMetadata?.durationMs?.takeIf { it > 0 }
        val fileMs = waiting?.durationMs?.takeIf { it > 0 }
        val lengthMs = listOfNotNull(libraryMs, fileMs).minOrNull()
        val atMs = if (waiting != null) standbyAt else (libraryMs ?: lengthMs)?.let { rules.placeOf(to, it) } ?: 0
        return Coming(atMs, lengthMs?.let { it - atMs }, waiting?.playbackParameters ?: rules.parametersOf(to))
    }

    /** The next song [to], at [index], loaded on a second player that waits silent where it comes in, at its own speed. */
    private fun prepareAhead(index: Int, to: Long, coming: Coming) {
        dropStandby()
        val slot = makeSlot(coming.parameters)
        slot.loadItems(active.items, index, coming.atMs)
        standby = slot
        standbyFor = to
        standbyAt = coming.atMs
    }

    /** The song coming in ended inside its fade (a file shorter than it said) and its player went on: the fade is over. */
    private fun finishFade() {
        letTailGo()
        activeRamp = null
        applyVolumes()
    }

    /** The natural fade: the song waiting comes in over [lengthMs] as the song playing goes out to its end. */
    private fun startFade(lengthMs: Long) {
        val incoming = standby ?: return
        standby = null
        standbyFor = null
        incoming.setPlayWhenReady(true)
        val out = active
        out.setPauseAtEndOfItems(true)
        tail = out
        tailRamp = Ramp(up = false, lengthMs = lengthMs)
        active = incoming
        activeRamp = Ramp(up = true, lengthMs = lengthMs)
        applyVolumes()
        incoming.loaded.getOrNull(incoming.index)?.let { uid -> tell { it.onSongStarted(uid, lap = false, natural = true) } }
        tellPlaying()
        tell { it.onChanged() }
    }

    /** A song changed by hand while one plays and nothing fades: it goes out over the short fade as [load] comes in. */
    private fun swap(parameters: PlaybackParameters?, load: (FadeSlot) -> Unit) {
        advance()
        dropStandby()
        val incoming = makeSlot(parameters)
        load(incoming)
        incoming.setPlayWhenReady(true)
        val out = active
        out.setPauseAtEndOfItems(true)
        tail = out
        tailRamp = Ramp(up = false, lengthMs = SHORT_FADE_MS)
        active = incoming
        activeRamp = Ramp(up = true, lengthMs = SHORT_FADE_MS)
        applyVolumes()
        incoming.loaded.getOrNull(incoming.index)?.let { uid -> tell { it.onSongStarted(uid, lap = false, natural = false) } }
        tellPlaying()
        tell { it.onChanged() }
        look()
    }

    /**
     * A move by hand during a fade: the song going out fades away within the short fade (sooner when it nearly has),
     * and the song playing comes up to full within it, from silence when it is another song now.
     */
    private fun shorten(fromSilence: Boolean) {
        advance()
        tailRamp = tailRamp?.let { ramp -> if (ramp.lengthMs - ramp.playedMs <= SHORT_FADE_MS) ramp else Ramp(up = false, lengthMs = SHORT_FADE_MS, fromGain = ramp.gain) }
        activeRamp = Ramp(up = true, lengthMs = SHORT_FADE_MS, fromGain = if (fromSilence) 0f else activeRamp?.gain ?: 1f)
    }

    /** A second player set up as the first: its audio session, the engine's focus, no offload, the same settings. */
    private fun makeSlot(parameters: PlaybackParameters?): FadeSlot {
        val slot = newSlot()
        active.audioSessionId.takeIf { it != C.AUDIO_SESSION_ID_UNSET }?.let(slot::setAudioSessionId)
        slot.setFocusHandled(false)
        slot.setOffload(false)
        slot.setGapless(gapless)
        slot.setRepeat(repeat)
        slot.setPlaybackParameters(parameters ?: active.playbackParameters)
        slot.volume = 0f
        slot.addListener(SlotEvents(slot))
        return slot
    }

    /** The song going out is over: faded, paused at its end, ended or failed. */
    private fun finished(slot: FadeSlot): Boolean =
        slot.playbackState == Player.STATE_ENDED || slot.playbackState == Player.STATE_IDLE || (sounding && !slot.playWhenReady)

    private fun letTailGo() {
        tail?.let(::letGo)
        tail = null
        tailRamp = null
        tailRan = false
    }

    private fun dropStandby() {
        standby?.let(::letGo)
        standby = null
        standbyFor = null
    }

    /** A player no longer needed: silent at once, released on the next turn (this may be its own callback). */
    private fun letGo(slot: FadeSlot) {
        slot.volume = 0f
        later { slot.release() }
    }

    /** The gains of the moment. A player's volume alone tells the engine nothing (GaplessEngine keeps it quiet). */
    private fun applyVolumes() {
        val level = master * duck
        active.volume = level * (activeRamp?.gain ?: 1f)
        tail?.let { it.volume = level * (tailRamp?.gain ?: 0f) }
    }

    /**
     * The players play as wanted: the song playing and the song going out; the song waiting stays paused. The song going
     * out first, so a look the player playing sets off never takes it, still paused, for one paused at its end.
     */
    private fun applyPlay() {
        val play = sounding
        tail?.setPlayWhenReady(play)
        active.setPlayWhenReady(play)
    }

    private fun schedule(delayMs: Long?) {
        next?.cancel()
        next = delayMs?.let { ms ->
            scope.launch {
                delay(ms.coerceAtLeast(0))
                look()
            }
        }
    }

    // Focus

    private fun updateFocus() = obey(focus.update(wantPlay, active.playbackState))

    private fun obey(command: Int) {
        when (command) {
            AudioFocusManager.PLAYER_COMMAND_DO_NOT_PLAY -> {
                wantPlay = false
                suppressed = false
            }
            AudioFocusManager.PLAYER_COMMAND_WAIT_FOR_CALLBACK -> suppressed = true
            else -> suppressed = false
        }
    }

    /** What the focus says later: a call (pause both, then go on), ducking, another app taking over. */
    private inner class FocusControl : AudioFocusManager.PlayerControl {
        override fun setVolumeMultiplier(volumeMultiplier: Float) {
            duck = volumeMultiplier
            if (on) applyVolumes()
        }

        override fun executePlayerCommand(playerCommand: Int) {
            if (!on) return
            obey(playerCommand)
            applyPlay()
            tell { it.onChanged() }
            look()
        }
    }

    // What the players say

    private fun tell(event: (EngineListener) -> Unit) = listeners.toList().forEach(event)

    private fun tellPlaying() {
        val now = active.isPlaying
        if (now == told) return
        told = now
        tell { it.onPlayingChanged(now) }
    }

    /** One player's events: the player playing speaks for the engine; the others only move the fades. */
    private inner class SlotEvents(private val slot: FadeSlot) : EngineListener {
        override fun onChanged() {
            if (slot === active) {
                // Paused by the phone (headphones out): the music is paused, both players.
                if (on && sounding && !slot.playWhenReady) {
                    wantPlay = false
                    applyPlay()
                }
                tell { it.onChanged() }
            }
            if (slot === active || slot === tail || slot === standby) look()
        }

        override fun onSongStarted(uid: Long, lap: Boolean, natural: Boolean) {
            if (slot !== active) return
            if (natural && fading) finishFade()
            failedAhead = null
            if (offloadAtNextSong) {
                offloadAtNextSong = false
                if (!on) slot.setOffload(offload)
            }
            tell { it.onSongStarted(uid, lap, natural) }
            look()
        }

        override fun onSeeked(fromMs: Long, toMs: Long) {
            if (slot === active) tell { it.onSeeked(fromMs, toMs) }
        }

        override fun onPlayingChanged(playing: Boolean) {
            if (slot === active) tellPlaying()
            if (slot === active || slot === tail) look()
        }

        override fun onEnded() {
            if (slot === active) {
                if (fading) finishFade()
                tell { it.onEnded() }
            }
            if (slot === tail) {
                letTailGo()
                look()
            }
        }

        override fun onError(error: PlaybackException, next: Int) {
            when {
                slot === active -> tell { it.onError(error, next) }
                slot === standby -> {
                    failedAhead = standbyFor
                    dropStandby()
                }
                slot === tail -> {
                    letTailGo()
                    look()
                }
            }
        }
    }
}

// In the app

/** Music, as the player asks for focus with it. */
private val MUSIC: AudioAttributes = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build()

/** The engine's one audio focus: Media3's own [AudioFocusManager], as ExoPlayer uses it, its callbacks on [looper]. */
class MediaFocus(context: Context, looper: Looper, control: AudioFocusManager.PlayerControl) : FocusPort {
    private val manager = AudioFocusManager(context, looper, control)

    override fun setHandled(handled: Boolean) = manager.setAudioAttributes(if (handled) MUSIC else null)

    override fun update(playWhenReady: Boolean, playbackState: Int): Int = manager.updateAudioFocus(playWhenReady, playbackState)

    override fun release() = manager.release()
}

/** The rules from the queue playing ([core]), the extras (the part repeating, the sleep timer, speeds) and the settings. */
class QueueFadeRules(private val core: PlayerCore, private val extras: PlayerExtras, private val settings: () -> Settings) : FadeRules {
    override fun mayFade(from: Long, to: Long): Boolean =
        mayCrossfade(core.book.value.current, from, to, settings().advanced.keepAlbumsGapless, extras.state.value.part, extras.sleep.value)

    override fun placeOf(uid: Long, durationMs: Long): Long? =
        comingInAt(core.book.value.current, uid, durationMs, extras.state.value, settings().playback.rememberPlace)

    override fun parametersOf(uid: Long): PlaybackParameters? {
        val song = core.book.value.current?.items?.firstOrNull { it.uid == uid }?.song ?: return null
        val speed = extras.state.value.speedOf(song.path)
        return PlaybackParameters(speed.speed, speed.pitch)
    }
}
