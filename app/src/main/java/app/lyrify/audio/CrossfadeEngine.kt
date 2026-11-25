@file:OptIn(UnstableApi::class)

package app.lyrify.audio

import android.content.ComponentCallbacks2
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
import app.lyrify.player.SourceKind
import app.lyrify.player.Speed
import app.lyrify.player.albumInOrder
import app.lyrify.player.edits
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

/** The song a Next by hand goes to waits on the second player once the music has played this long: no tap makes a player. */
const val NEXT_READY_AFTER_MS: Long = 1_000

/**
 * While crossfade is on, a player holds the song it plays and the next ones, this many in all, never the queue (a queue
 * this long or shorter is held whole): a Next hands a player a few songs, not thousands, and a song shorter than the main
 * thread's delay still finds the one after it.
 */
const val SONGS_HELD: Int = 3

/** How often the gains step during a fade, and the longest wait between looks while a song plays. */
const val FADE_STEP_MS: Long = 25
const val LOOK_EVERY_MS: Long = 1_000

/** A fade paused this long is finished, so two paused players are not kept. */
const val FROZEN_FADE_MS: Long = 60_000

private const val HALF_PI: Float = (PI / 2).toFloat()

/**
 * A song this close to its end (in time) may have ended already on ExoPlayer's own thread, which then went on to the next
 * song before the main thread hears of it (CR2); further from its end it plays there for sure.
 */
private const val MAY_HAVE_ENDED_MS: Long = 5_000
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
    // A book's parts never fade into each other: a sentence goes on across them (books/BookPlayback.kt).
    if (queue == null || queue.source.kind == SourceKind.BOOK) return false
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

/**
 * One ExoPlayer as the crossfade engine drives it: a [PlaybackEngine] (GaplessEngine) holding the queue while crossfade is
 * off, a few songs of it ([SONGS_HELD]) while it is on, and what a fade needs.
 */
interface FadeSlot : PlaybackEngine {
    /** The song going out of a fade pauses at its end instead of going on to the next song. */
    fun setPauseAtEndOfItems(pause: Boolean)

    /** Whether it last paused itself at the end of its song ([setPauseAtEndOfItems]), not paused by the phone (headphones out). */
    val pausedAtEnd: Boolean

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
 * - On: the engine holds the queue ([items], made with [itemOf]), and each player only the song it plays and the next
 *   ones, [SONGS_HELD] in all (PW2): a Next never hands a player thousands of songs. While the music plays, a second
 *   player waits, silent, with the song a Next goes to: from [NEXT_READY_AFTER_MS] after Play (no tap makes a player),
 *   and from [PREPARE_LEAD_MS] before a natural change would fade, at that song's own place and speed. At the fade's
 *   start it plays, the two cross on the equal-power curve over the chosen seconds (at most half of either song), and
 *   the player going out pauses at its end and is let go. A song changed by hand while playing goes the same way over
 *   [SHORT_FADE_MS], in from the player waiting (its song loaded there first when it is another). Nothing fades while
 *   paused, with Repeat one, at the end of the queue, or when [FadeRules.mayFade] says no: the player playing goes on
 *   into the next song itself, gapless.
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
    private val itemOf: (Projected) -> MediaItem,
    private val rules: FadeRules,
    focusOf: (AudioFocusManager.PlayerControl) -> FocusPort,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val later: (() -> Unit) -> Unit,
) : PlaybackEngine {
    private val listeners = mutableListOf<EngineListener>()

    /** The player of the song playing: the one the session sees. */
    private var active: FadeSlot = newSlot().also { it.addListener(SlotEvents(it)) }

    /**
     * The queue's songs as the bridge hands them, kept on and off: with crossfade on, a player is handed a few of them
     * ([windowOf]), and turned off, the one player all of them again.
     */
    private var songs: List<Projected> = emptyList()

    /** The queue as the session sees it while crossfade is on, each player holding a few songs of it; null while off. */
    private var held: Held? = null

    /**
     * Each song that came right after the song playing on its player since the song came near its end
     * ([MAY_HAVE_ENDED_MS]), by uid: ExoPlayer's own thread may have gone on to any of them before the main thread hears of
     * it (CR2), so none is taken out from under it ([reshape]).
     */
    private val mayBePlaying = mutableMapOf<Long, Projected>()

    /**
     * The song a Next goes to, loaded and waiting silent on the second player ([standbyFor] its uid, at [standbyAt]):
     * the next song, or the first after the last as the session's Next goes round.
     */
    private var standby: FadeSlot? = null
    private var standbyFor: Long? = null
    private var standbyAt: Long = 0

    /** The song going out of a fade, on the other player, and its fade; the song playing's fade in. */
    private var tail: FadeSlot? = null
    private var tailRamp: Ramp? = null
    private var activeRamp: Ramp? = null

    /**
     * Where the song going out of the natural fade beginning now was as it began: read by the listeners while they hear
     * of the song coming in ([EngineListener.onSongStarted]), where a play ends (stats/); null at any other time.
     */
    var fadeFromMs: Long? = null
        private set

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

    /** Since when the music sounds (Play), for the song a Next goes to ([NEXT_READY_AFTER_MS]); null while it does not. */
    private var heardFrom: Long? = null

    /**
     * The phone asked for memory back ([trimMemory]): until the next song starts, the song a Next goes to waits only near
     * its fade, as before PW2.
     */
    private var lean: Boolean = false

    /**
     * The listeners hear of a song that started by itself: a move they make now is the app's own (a part going back to
     * A), never a change by hand, so it fades nothing.
     */
    private var settling: Boolean = false

    /**
     * The queue's song a player that ran past its songs went on to ([SlotEvents.onEnded]): its start is told as natural,
     * the song before having played to its end (the play log, the sleep timer), as without crossfade.
     */
    private var wentOnTo: Long? = null
    private var next: Job? = null
    private var looking: Boolean = false
    private var lookAgain: Boolean = false

    /** Whether the listeners were last told it plays. */
    private var told: Boolean = false
    private val focus: FocusPort = focusOf(FocusControl())

    private val on: Boolean get() = crossfadeMs > 0
    private val fading: Boolean get() = tail != null || activeRamp != null

    /**
     * Repeat as a player plays it: while crossfade is on, Repeat all goes round through the songs each player is handed
     * ([windowOf]), never round a player's own few, unless it holds the whole queue.
     */
    private val slotRepeat: Repeat get() = if (held != null && repeat == Repeat.ALL && loaded.size > SONGS_HELD) Repeat.OFF else repeat

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
                // The engine takes the one player's focus over; offload goes off at once. It holds the queue from now, and
                // the player its song and the next ones (at the look below), the song playing on.
                offloadAtNextSong = false
                active.setOffload(false)
                held = Held(active.items, active.loaded)
                wantPlay = active.playWhenReady
                suppressed = false
                active.setFocusHandled(false)
                focus.setHandled(true)
                updateFocus()
                applyPlay()
            } else {
                dropStandby()
                if (fading) shorten(fromSilence = false)
                // The one player takes the whole queue back around its song, which plays on, and a song ExoPlayer's
                // thread may be on already too ([reshape]). The engine lets the queue go first: the look the player's news
                // asks for must not take its songs back to a few.
                held = null
                reshape(songs)
                mayBePlaying.clear()
                active.setRepeat(repeat)
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

    override val loaded: List<Long> get() = held?.uids ?: active.loaded
    override val index: Int get() = held?.let { queue -> active.loaded.getOrNull(active.index)?.let(queue::placeOf) ?: -1 } ?: active.index
    override val positionMs: Long get() = active.positionMs
    override val items: List<MediaItem> get() = held?.items ?: active.items
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
        songs = if (index < 0) emptyList() else items
        if (!on && !fading) return active.load(items, index, positionMs)
        if (on) {
            // The engine holds the queue (none at -1). The song waiting is the queue before's: its player stays, for this
            // queue's song a Next goes to.
            held = Held(songs.map(itemOf), songs.map { it.uid })
            standbyFor = null
        }
        when {
            items.isEmpty() || index < 0 -> {
                letTailGo()
                dropStandby()
                activeRamp = null
                active.load(items, index, positionMs)
            }
            on && !fading && sounding && active.isPlaying -> return swap(index, positionMs)
            else -> {
                if (fading) shorten(fromSilence = true)
                // Crossfade on, the player takes the song and the next ones; turned off in a fade, the whole queue.
                if (on) loadAt(active, index, positionMs) else active.load(items, index, positionMs)
            }
        }
        applyVolumes()
        look()
    }

    override fun apply(edit: Edit) = applyAll(listOf(edit))

    override fun applyAll(edits: List<Edit>) {
        // No edits (a queue renamed) change nothing: the song waiting stays, ready for its fade (CR2).
        if (edits.isEmpty()) return
        // The song waiting may not be the one a Next goes to any more: its player loads that one at the look after the
        // last edit, once for the whole change; a look the players' events ask for meanwhile waits for it.
        val was = looking
        looking = true
        try {
            // The engine's songs take the edits, and with crossfade on its media items too. Each player then takes a
            // song's new file, or its new details (a cover Covers & tags changed), in place where it holds that song; the
            // songs around the one playing come and go at the look.
            val queue = held
            val now = songs.toMutableList()
            val items = queue?.items?.toMutableList()
            edits.forEach { edit ->
                when (edit) {
                    is Edit.Remove -> {
                        now.subList(edit.at, edit.at + edit.count).clear()
                        items?.subList(edit.at, edit.at + edit.count)?.clear()
                    }
                    is Edit.Insert -> {
                        now.addAll(edit.at, edit.items)
                        items?.addAll(edit.at, edit.items.map(itemOf))
                    }
                    is Edit.Move -> {
                        now.add(edit.to, now.removeAt(edit.from))
                        items?.let { it.add(edit.to, it.removeAt(edit.from)) }
                    }
                    is Edit.Replace -> {
                        now[edit.at] = edit.item
                        items?.set(edit.at, itemOf(edit.item))
                    }
                    is Edit.Refresh -> edit.items.forEachIndexed { k, item ->
                        now[edit.at + k] = item
                        items?.set(edit.at + k, itemOf(item))
                    }
                }
            }
            songs = now
            if (items == null) {
                active.applyAll(edits)
            } else {
                held = Held(items, now.map { it.uid })
                listOfNotNull(active, standby).forEach { slot ->
                    edits.forEach { edit ->
                        when (edit) {
                            is Edit.Replace -> slot.loaded.indexOf(edit.item.uid).takeIf { it >= 0 }?.let { slot.apply(Edit.Replace(it, edit.item)) }
                            is Edit.Refresh -> edit.items.forEach { item -> slot.loaded.indexOf(item.uid).takeIf { it >= 0 }?.let { slot.apply(Edit.Refresh(it, listOf(item))) } }
                            is Edit.Remove, is Edit.Insert, is Edit.Move -> Unit
                        }
                    }
                }
            }
        } finally {
            looking = was
        }
        look()
    }

    override fun seek(index: Int, positionMs: Long) {
        if (!on && !fading) return active.seek(index, positionMs)
        if (index !in loaded.indices) return
        // The song waiting stays: after a seek inside the song it is still the one a Next goes to, and after a move to
        // another song its player loads that one at the look.
        val other = index != this.index
        when {
            fading -> {
                shorten(fromSilence = other)
                goTo(index, positionMs)
            }
            on && other && sounding && active.isPlaying && !settling -> return swap(index, positionMs)
            else -> goTo(index, positionMs)
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
        active.setRepeat(slotRepeat)
        standby?.setRepeat(slotRepeat)
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

    /** The phone asks for memory back ([memoryLow]): the player waiting is let go, and waits again from the next song on. */
    fun trimMemory() {
        lean = true
        dropStandby()
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
        if (sounding) {
            pausedAt = null
            if (heardFrom == null) heardFrom = now
        } else {
            if (pausedAt == null) pausedAt = now
            heardFrom = null
        }
        // While crossfade is on, the player playing holds its song and the next ones as the queue and Repeat have them now
        // ([windowOf]); the songs played go. Near the song's end, ExoPlayer's own thread may be on the song after it already
        // ([mayBePlaying]): one the queue still has waits before the song playing, where it never plays by itself.
        val at = index
        if (held != null && at >= 0) {
            val nearEnd = active.durationMs == C.TIME_UNSET || (active.durationMs - active.positionMs) / active.playbackParameters.speed <= MAY_HAVE_ENDED_MS
            if (!nearEnd) {
                mayBePlaying.clear()
            } else {
                active.loaded.getOrNull(active.index + 1)?.takeIf { it !in mayBePlaying }?.let { uid -> songs.getOrNull(loaded.indexOf(uid))?.let { mayBePlaying[uid] = it } }
            }
            val window = windowOf(at).map(songs::get)
            reshape(mayBePlaying.values.filter { song -> song.uid in active.loaded && window.none { it.uid == song.uid } && song.uid in loaded } + window)
            active.setRepeat(slotRepeat)
        }
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

    /**
     * The song a Next goes to, waiting ready on the second player; and the natural change ahead: that song at its place
     * and speed in time and its fade begun, or a change played gapless.
     */
    private fun plan(): Long? {
        if (fading) return FADE_STEP_MS
        if (!sounding) {
            dropStandby()
            return null
        }
        if (!active.isPlaying) return LOOK_EVERY_MS
        val at = index
        val from = loaded.getOrNull(at)
        // A Next by hand goes to the song after this one, or round to the first as the session's Next does. Its player is
        // made once the music has played a moment, never in the turn of a tap; when its fade is near, at once (below).
        val ahead = if (from == null || loaded.size < 2) null else (at + 1) % loaded.size
        when {
            ahead == null || loaded[ahead] == failedAhead -> dropStandby()
            standbyFor != loaded[ahead] && !lean && (standby != null || lookedAt - (heardFrom ?: lookedAt) >= NEXT_READY_AFTER_MS) ->
                prepareAhead(ahead, loaded[ahead], comingIn(ahead, loaded[ahead], null))
        }
        val toIndex = nextIndexFor(at, loaded.size, repeat)
        val outMs = active.durationMs
        // Nothing to fade into (Repeat one, the last song): a look again only while the song a Next goes to is not waiting.
        if (from == null || toIndex == null) return LOOK_EVERY_MS.takeIf { ahead != null && standby == null }
        if (outMs <= 0) return LOOK_EVERY_MS
        val to = loaded[toIndex]
        val outSpeed = active.playbackParameters.speed.coerceAtLeast(MIN_SPEED)
        val leftMs = ((outMs - active.positionMs) / outSpeed).toLong()
        val waiting = standby?.takeIf { standbyFor == to }
        // Far from the end the song coming in is not needed yet.
        val coming = if (leftMs > crossfadeMs + PREPARE_LEAD_MS) null else comingIn(toIndex, to, waiting)
        // A player waiting already takes the song's own speed again: the Speed sheet may have changed it since.
        if (waiting != null && coming != null && waiting.playbackParameters != coming.parameters) waiting.setPlaybackParameters(coming.parameters)
        val fadeMs = naturalFadeMs(crossfadeMs, outMs, outSpeed, coming?.playsMs, coming?.parameters?.speed ?: outSpeed)
        val step = fadeStep(leftMs, fadeMs, ready = waiting?.playbackState == Player.STATE_READY && waiting.error == null)
        // Not near yet, or no fade into it: the song waits for a Next only, and the player playing goes on into it gapless.
        if (!step.prepare) return step.nextMs
        if (to == failedAhead || !rules.mayFade(from, to)) return LOOK_EVERY_MS
        if (waiting == null) prepareAhead(toIndex, to, coming ?: comingIn(toIndex, to, null))
        if (step.start) startFade(minOf(fadeMs, leftMs))
        return step.nextMs
    }

    /** The song coming in as its fade sees it: where it comes in ([atMs]), how much of it plays from there, its speed. */
    private class Coming(val atMs: Long, val playsMs: Long?, val parameters: PlaybackParameters)

    /**
     * The song [to] at [index], coming in: at its kept place, if any; as long as the library says, or as its file really
     * is once loaded on the second player ([waiting]), whichever is shorter; at its own speed as the rules say now, never
     * the speed a waiting player was left at.
     */
    private fun comingIn(index: Int, to: Long, waiting: FadeSlot?): Coming {
        val libraryMs = items.getOrNull(index)?.mediaMetadata?.durationMs?.takeIf { it > 0 }
        val fileMs = waiting?.durationMs?.takeIf { it > 0 }
        val lengthMs = listOfNotNull(libraryMs, fileMs).minOrNull()
        val atMs = if (waiting != null) standbyAt else (libraryMs ?: lengthMs)?.let { rules.placeOf(to, it) } ?: 0
        return Coming(atMs, lengthMs?.let { it - atMs }, rules.parametersOf(to) ?: active.playbackParameters)
    }

    /**
     * The song [to], at [index], and the next ones loaded on the second player, which waits silent where the song comes
     * in, at its own speed: the player waiting when there is one (with another song), else a new one.
     */
    private fun prepareAhead(index: Int, to: Long, coming: Coming) {
        val slot = standby ?: makeSlot(coming.parameters)
        if (slot.playbackParameters != coming.parameters) slot.setPlaybackParameters(coming.parameters)
        loadAt(slot, index, coming.atMs)
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
        mayBePlaying.clear()
        lean = false
        activeRamp = Ramp(up = true, lengthMs = lengthMs)
        applyVolumes()
        incoming.loaded.getOrNull(incoming.index)?.let { uid ->
            fadeFromMs = out.positionMs
            tell { it.onSongStarted(uid, lap = false, natural = true) }
            fadeFromMs = null
        }
        tellPlaying()
        tell { it.onChanged() }
    }

    /**
     * A song changed by hand while one plays and nothing fades: it goes out over the short fade as the queue's song at
     * [index] comes in on the player waiting, at once when that song waits there, else loaded there first (PW2). A new
     * player only when none waits, in the music's first moment: no Next makes one, or hands one the queue.
     */
    private fun swap(index: Int, positionMs: Long) {
        advance()
        val uid = loaded[index]
        val parameters = rules.parametersOf(uid) ?: active.playbackParameters
        val waiting = standby?.takeIf { standbyFor == uid }
        val incoming = standby ?: makeSlot(parameters)
        standby = null
        standbyFor = null
        when {
            waiting == null -> loadAt(incoming, index, positionMs)
            standbyAt != positionMs -> incoming.seek(incoming.index, positionMs)
        }
        if (incoming.playbackParameters != parameters) incoming.setPlaybackParameters(parameters)
        incoming.setPlayWhenReady(true)
        val out = active
        out.setPauseAtEndOfItems(true)
        tail = out
        tailRamp = Ramp(up = false, lengthMs = SHORT_FADE_MS)
        active = incoming
        mayBePlaying.clear()
        lean = false
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

    // What each player holds while crossfade is on

    /**
     * The queue's media items and their uids, as the session sees them. [placeOf] keeps the last song it found: the song
     * playing is looked up again and again.
     */
    private class Held(val items: List<MediaItem>, val uids: List<Long>) {
        private var last: Long? = null
        private var lastAt: Int = -1

        /** Where song [uid] is in the queue; -1 for none. */
        fun placeOf(uid: Long): Int {
            if (uid != last) {
                last = uid
                lastAt = uids.indexOf(uid)
            }
            return lastAt
        }
    }

    /**
     * The places in the queue of the songs a player holds for the song at [index]: it and the next ones as the queue goes
     * on (Repeat one keeps them, so turning it on takes none out), or the whole queue when it is that short.
     */
    private fun windowOf(index: Int): List<Int> =
        if (loaded.size <= SONGS_HELD) loaded.indices.toList() else generateSequence(index) { nextIndexFor(it, loaded.size, if (repeat == Repeat.ONE) Repeat.OFF else repeat) }.take(SONGS_HELD).toList()

    /** [slot] loads the queue's song at [index], at [positionMs], with the songs a player holds around it ([windowOf]). */
    private fun loadAt(slot: FadeSlot, index: Int, positionMs: Long) {
        val window = windowOf(index)
        slot.load(window.map(songs::get), window.indexOf(index), positionMs)
    }

    /**
     * The player playing comes to hold [to], which holds its song, with edits that keep every song both hold ([edits]), so
     * a song ExoPlayer's thread may be on already ([mayBePlaying]) is never taken out and put back, which would start it
     * again. One [to] leaves out first comes right after the song playing, before the songs [to] has there, and goes last:
     * a thread on it goes on to the queue's next song (CR2).
     */
    private fun reshape(to: List<Projected>) {
        if (active.loaded == to.map { it.uid }) return
        val playing = active.loaded.getOrNull(active.index) ?: return
        val leaving = mayBePlaying.values.filter { song -> song.uid in active.loaded && to.none { it.uid == song.uid } }
        if (leaving.isNotEmpty()) {
            val at = to.indexOfFirst { it.uid == playing }
            active.applyAll(checkNotNull(edits(active.loaded, to.take(at + 1) + leaving + to.drop(at + 1))))
        }
        active.applyAll(checkNotNull(edits(active.loaded, to)))
    }

    /** The player playing goes to the queue's song at [index]: a seek when it holds that song, else it and the next ones loaded there. */
    private fun goTo(index: Int, positionMs: Long) {
        val queue = held ?: return active.seek(index, positionMs)
        val at = active.loaded.indexOf(queue.uids[index])
        if (at >= 0) active.seek(at, positionMs) else loadAt(active, index, positionMs)
    }

    /** A second player set up as the first: its audio session, the engine's focus, no offload, the same settings. */
    private fun makeSlot(parameters: PlaybackParameters): FadeSlot {
        val slot = newSlot()
        active.audioSessionId.takeIf { it != C.AUDIO_SESSION_ID_UNSET }?.let(slot::setAudioSessionId)
        slot.setFocusHandled(false)
        slot.setOffload(false)
        slot.setGapless(gapless)
        slot.setRepeat(slotRepeat)
        slot.setPlaybackParameters(parameters)
        slot.volume = 0f
        slot.addListener(SlotEvents(slot))
        return slot
    }

    /** The song going out is over: faded, paused at its end, ended or failed. */
    private fun finished(slot: FadeSlot): Boolean =
        slot.playbackState == Player.STATE_ENDED || slot.playbackState == Player.STATE_IDLE || (sounding && !slot.playWhenReady && slot.pausedAtEnd)

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
            // Paused by the phone (headphones out), the player playing or the song going out of a fade, whichever hears
            // it first: the music is paused, both players, and the fade waits. The player playing tells the listeners.
            if ((slot === active || slot === tail) && on && sounding && !slot.playWhenReady && !slot.pausedAtEnd) {
                wantPlay = false
                applyPlay()
            }
            if (slot === active) tell { it.onChanged() }
            if (slot === active || slot === tail || slot === standby) look()
        }

        override fun onSongStarted(uid: Long, lap: Boolean, natural: Boolean) {
            if (slot !== active) return
            val byItself = natural || uid == wentOnTo
            wentOnTo = null
            // The player says where it is: its thread is on this song, no other.
            mayBePlaying.clear()
            lean = false
            if (byItself && fading) finishFade()
            failedAhead = null
            if (offloadAtNextSong) {
                offloadAtNextSong = false
                if (!on) slot.setOffload(offload)
            }
            settling = byItself
            try {
                tell { it.onSongStarted(uid, lap, byItself) }
            } finally {
                settling = false
            }
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
                // Crossfade on, a player holds a few songs: one that ran past them before the main thread heard of them
                // (songs shorter than its delay) ended while the queue goes on, and goes on to the queue's next song, as
                // at a song's end. Unless a listener moved it already as it heard of the song before (the sleep timer
                // parking the music): ExoPlayer tells of the end after that song's start, ended then, not now.
                val queueNext = if (held == null || index < 0) null else nextIndexFor(index, loaded.size, repeat)
                when {
                    queueNext == null -> tell { it.onEnded() }
                    slot.playbackState == Player.STATE_ENDED -> {
                        wentOnTo = loaded[queueNext]
                        goTo(queueNext, 0)
                    }
                }
            }
            if (slot === tail) {
                letTailGo()
                look()
            }
        }

        override fun onError(error: PlaybackException, next: Int) {
            when {
                slot === active -> {
                    // Crossfade on, the song after it is the queue's: the player holds only a few songs of it.
                    val queueNext = held?.let { queue -> slot.loaded.getOrNull(next)?.let(queue::placeOf) ?: C.INDEX_UNSET } ?: next
                    tell { it.onError(error, queueNext) }
                }
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

/**
 * Whether the phone asks KJB's player service for memory back at this onTrimMemory [trimLevel]: KJB gone to the
 * background list, or on Android 13 and older the phone running low while it plays (later versions send no such level).
 * Not when its screens close (UI_HIDDEN): the music plays on with the screen off.
 */
@Suppress("DEPRECATION") // The running levels, still sent up to Android 13.
fun memoryLow(trimLevel: Int): Boolean =
    trimLevel >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND || trimLevel in ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW until ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN

/** Music, as the player asks for focus with it. */
private val MUSIC: AudioAttributes = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build()

/** The engine's one audio focus: Media3's own [AudioFocusManager], as ExoPlayer uses it, its callbacks on [looper]. */
class MediaFocus(context: Context, looper: Looper, control: AudioFocusManager.PlayerControl) : FocusPort {
    private val manager = AudioFocusManager(context, looper, control)

    override fun setHandled(handled: Boolean) = manager.setAudioAttributes(if (handled) MUSIC else null)

    override fun update(playWhenReady: Boolean, playbackState: Int): Int = manager.updateAudioFocus(playWhenReady, playbackState)

    override fun release() = manager.release()
}

/**
 * The rules from the queue playing ([core]), the extras (the part repeating, the sleep timer, speeds) and the settings; a
 * book's parts play at the book's speed ([bookSpeed], null while music plays).
 */
class QueueFadeRules(private val core: PlayerCore, private val extras: PlayerExtras, private val settings: () -> Settings, private val bookSpeed: () -> Speed? = { null }) : FadeRules {
    override fun mayFade(from: Long, to: Long): Boolean =
        mayCrossfade(core.book.value.current, from, to, settings().advanced.keepAlbumsGapless, extras.state.value.part, extras.sleep.value)

    override fun placeOf(uid: Long, durationMs: Long): Long? =
        comingInAt(core.book.value.current, uid, durationMs, extras.state.value, settings().playback.rememberPlace)

    override fun parametersOf(uid: Long): PlaybackParameters? {
        val song = core.book.value.current?.items?.firstOrNull { it.uid == uid }?.song ?: return null
        val speed = bookSpeed() ?: extras.state.value.speedOf(song.path)
        return PlaybackParameters(speed.speed, speed.pitch)
    }
}
