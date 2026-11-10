@file:OptIn(UnstableApi::class)

package app.lyrify.audio

import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioFocusManager
import androidx.media3.common.util.UnstableApi
import app.lyrify.library.Song
import app.lyrify.library.song
import app.lyrify.player.Edit
import app.lyrify.player.EngineListener
import app.lyrify.player.Projected
import app.lyrify.player.Queue
import app.lyrify.player.QueueBook
import app.lyrify.player.QueueSource
import app.lyrify.player.Repeat
import app.lyrify.player.SourceKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** A file that could not be read, as the player reports it. Made without the phone's clock. */
class LoadError : PlaybackException("unreadable", null, ERROR_CODE_IO_FILE_NOT_FOUND, Bundle.EMPTY, 0)

/**
 * One ExoPlayer as the crossfade engine drives it, on virtual time: the songs it holds by uid, each [lengthOf] long;
 * a load or a jump to another song buffers for [BUFFER_MS] first; playing moves the position at its speed, and the end
 * of a song goes on as ExoPlayer does (the next song, Repeat one's lap, the end of the queue, or a pause at the end when
 * asked). It tells its listeners as GaplessEngine does. [calls] notes what it was asked, for the Off path.
 */
class FakeSlot(val number: Int, private val clock: () -> Long, private val lengthOf: (Long) -> Long) : FadeSlot {
    val calls = mutableListOf<String>()
    private val listeners = mutableListOf<EngineListener>()
    var released: Boolean = false
        private set
    var pauseAtEnd: Boolean = false
        private set
    var focusHandled: Boolean = true
        private set
    var sessionId: Int = 100 + number
        private set
    var repeat: Repeat = Repeat.OFF
        private set
    var offload: Boolean = false
        private set
    var gapless: Boolean = true
        private set

    override var loaded: List<Long> = emptyList()
        private set
    override var index: Int = -1
        private set
    override var playWhenReady: Boolean = false
        private set
    override var playbackState: Int = Player.STATE_IDLE
        private set
    override var error: PlaybackException? = null
        private set
    override var playbackParameters: PlaybackParameters = PlaybackParameters.DEFAULT
        private set
    /** As in GaplessEngine, the volume alone is no news for the listeners; the same volume again is not a call. */
    override var volume: Float = 1f
        set(value) {
            if (value == field) return
            calls += "volume $value"
            field = value
        }

    private var base: Long = 0
    private var since: Long = 0
    private var readyAt: Long = 0

    override val isPlaying: Boolean get() = playWhenReady && playbackState == Player.STATE_READY
    override val positionMs: Long get() = if (isPlaying) base + ((clock() - since) * playbackParameters.speed).toLong() else base
    override val items: List<MediaItem>
        get() = loaded.map { uid -> MediaItem.Builder().setMediaId("$uid").setMediaMetadata(MediaMetadata.Builder().setDurationMs(lengthOf(uid)).build()).build() }
    override val isLoading: Boolean get() = playbackState == Player.STATE_BUFFERING
    override val suppression: Int get() = Player.PLAYBACK_SUPPRESSION_REASON_NONE
    override val audioSessionId: Int get() = sessionId
    override val bufferedMs: Long get() = positionMs
    override val durationMs: Long get() = loaded.getOrNull(index)?.let(lengthOf) ?: C.TIME_UNSET
    val uid: Long? get() = loaded.getOrNull(index)

    override fun load(items: List<Projected>, index: Int, positionMs: Long) {
        calls += "load ${items.map { it.uid }} $index $positionMs"
        loadUids(items.map { it.uid }, index, positionMs)
    }

    override fun loadItems(items: List<MediaItem>, index: Int, positionMs: Long) {
        calls += "loadItems ${items.map { it.mediaId }} $index $positionMs"
        loadUids(items.map { it.mediaId.toLong() }, index, positionMs)
    }

    private fun loadUids(uids: List<Long>, at: Int, positionMs: Long) = changing {
        loaded = uids
        index = if (uids.isEmpty()) -1 else at
        error = null
        anchor(positionMs)
        if (uids.isEmpty()) {
            playbackState = Player.STATE_IDLE
        } else {
            buffer()
            emit { it.onSongStarted(uids[at], lap = false, natural = false) }
        }
    }

    override fun apply(edit: Edit) = changing {
        calls += "apply $edit"
        val playing = uid
        val list = loaded.toMutableList()
        when (edit) {
            is Edit.Remove -> list.removeAt(edit.at)
            is Edit.Insert -> list.addAll(edit.at, edit.items.map { it.uid })
            is Edit.Move -> list.add(edit.to, list.removeAt(edit.from))
        }
        loaded = list
        index = list.indexOf(playing)
    }

    override fun seek(index: Int, positionMs: Long) = changing {
        calls += "seek $index $positionMs"
        if (index !in loaded.indices) return@changing
        val from = this.positionMs
        val other = index != this.index
        this.index = index
        anchor(positionMs)
        if (other || playbackState == Player.STATE_IDLE) {
            error = null
            buffer()
        }
        if (other) emit { it.onSongStarted(loaded[index], lap = false, natural = false) } else emit { it.onSeeked(from, positionMs) }
    }

    override fun setPlaybackParameters(parameters: PlaybackParameters) = changing {
        calls += "speed ${parameters.speed}"
        anchor(positionMs)
        playbackParameters = parameters
    }

    override fun setPlayWhenReady(play: Boolean) = changing {
        calls += "play $play"
        if (play && playbackState == Player.STATE_IDLE && loaded.isNotEmpty()) buffer()
        anchor(positionMs)
        playWhenReady = play
    }

    override fun prepare() = changing {
        calls += "prepare"
        if (playbackState == Player.STATE_IDLE && loaded.isNotEmpty()) buffer()
    }

    override fun stop() = changing {
        calls += "stop"
        anchor(positionMs)
        playbackState = Player.STATE_IDLE
    }

    override fun release() {
        calls += "release"
        released = true
        listeners.clear()
    }

    override fun setRepeat(repeat: Repeat) {
        calls += "repeat $repeat"
        this.repeat = repeat
    }

    override fun setOffload(allowed: Boolean) {
        calls += "offload $allowed"
        offload = allowed
    }

    override fun setGapless(on: Boolean) {
        calls += "gapless $on"
        gapless = on
    }

    override fun setPauseAtEndOfItems(pause: Boolean) {
        calls += "pauseAtEnd $pause"
        pauseAtEnd = pause
    }

    override fun setFocusHandled(handled: Boolean) {
        calls += "focus $handled"
        focusHandled = handled
    }

    override fun setAudioSessionId(id: Int) {
        calls += "session $id"
        sessionId = id
    }

    override fun addListener(listener: EngineListener) {
        listeners += listener
    }

    /** The song cannot be read: the player stops with an error, as ExoPlayer does. */
    fun fail() = changing {
        anchor(positionMs)
        playbackState = Player.STATE_IDLE
        val failed = LoadError()
        error = failed
        val next = index < loaded.lastIndex
        emit { it.onError(failed, next) }
    }

    /** Virtual time moved on: buffering ends, and a song that reached its end goes on as ExoPlayer would. */
    fun advance() {
        if (released) return
        if (playbackState == Player.STATE_BUFFERING && clock() >= readyAt) changing { playbackState = Player.STATE_READY }
        val length = durationMs
        if (!isPlaying || length == C.TIME_UNSET || positionMs < length) return
        changing {
            val over = positionMs - length
            when {
                pauseAtEnd -> {
                    anchor(length)
                    playWhenReady = false
                }
                repeat == Repeat.ONE -> {
                    anchor(over)
                    emit { it.onSongStarted(loaded[index], lap = true, natural = true) }
                }
                index < loaded.lastIndex || repeat == Repeat.ALL -> {
                    index = if (index < loaded.lastIndex) index + 1 else 0
                    anchor(over)
                    emit { it.onSongStarted(loaded[index], lap = false, natural = true) }
                }
                else -> {
                    anchor(length)
                    playbackState = Player.STATE_ENDED
                    emit { it.onEnded() }
                }
            }
        }
    }

    private fun anchor(positionMs: Long) {
        base = positionMs
        since = clock()
    }

    private fun buffer() {
        playbackState = Player.STATE_BUFFERING
        readyAt = clock() + BUFFER_MS
    }

    /** [change], then the listeners hear about it, playing or not first, as ExoPlayer orders them. */
    private fun changing(change: () -> Unit) {
        if (released) return
        val wasPlaying = isPlaying
        change()
        if (isPlaying != wasPlaying) {
            anchor(positionMs)
            emit { it.onPlayingChanged(isPlaying) }
        }
        emit { it.onChanged() }
    }

    private fun emit(event: (EngineListener) -> Unit) = listeners.toList().forEach(event)

    companion object {
        const val BUFFER_MS: Long = 100
    }
}

/** Audio focus as Media3's AudioFocusManager gives it, by hand: granted unless [deny]; a call, ducking and losses on cue. */
class FakeFocus : FocusPort {
    lateinit var control: AudioFocusManager.PlayerControl
    var handled: Boolean = false
        private set
    var held: Boolean = false
        private set
    var requests: Int = 0
        private set
    var deny: Boolean = false
    var released: Boolean = false
        private set

    override fun setHandled(handled: Boolean) {
        this.handled = handled
    }

    override fun update(playWhenReady: Boolean, playbackState: Int): Int {
        if (!handled || playbackState == Player.STATE_IDLE) {
            held = false
            return AudioFocusManager.PLAYER_COMMAND_PLAY_WHEN_READY
        }
        if (!playWhenReady) return AudioFocusManager.PLAYER_COMMAND_PLAY_WHEN_READY
        if (held) return AudioFocusManager.PLAYER_COMMAND_PLAY_WHEN_READY
        requests++
        if (deny) return AudioFocusManager.PLAYER_COMMAND_DO_NOT_PLAY
        held = true
        return AudioFocusManager.PLAYER_COMMAND_PLAY_WHEN_READY
    }

    override fun release() {
        released = true
        held = false
    }

    /** A phone call: focus lost for a while. */
    fun callStarts() = control.executePlayerCommand(AudioFocusManager.PLAYER_COMMAND_WAIT_FOR_CALLBACK)

    fun callEnds() = control.executePlayerCommand(AudioFocusManager.PLAYER_COMMAND_PLAY_WHEN_READY)

    fun duck(on: Boolean) = control.setVolumeMultiplier(if (on) 0.2f else 1f)

    /** Another app plays: focus lost for good. */
    fun lose() {
        held = false
        control.executePlayerCommand(AudioFocusManager.PLAYER_COMMAND_DO_NOT_PLAY)
    }
}

/** What the engine told its listeners, in order. */
class Heard : EngineListener {
    val events = mutableListOf<String>()
    val starts get() = events.filter { it.startsWith("start") }

    override fun onSongStarted(uid: Long, lap: Boolean, natural: Boolean) {
        events += "start $uid" + (if (lap) " lap" else "") + (if (natural) " natural" else "")
    }

    override fun onSeeked(fromMs: Long, toMs: Long) {
        events += "seeked $toMs"
    }

    override fun onPlayingChanged(playing: Boolean) {
        events += "playing $playing"
    }

    override fun onEnded() {
        events += "ended"
    }

    override fun onError(error: PlaybackException, hasNext: Boolean) {
        events += "error $hasNext"
    }
}

/** [count] made-up test tones of [lengthMs] each, as an album's tracks when [album] is named. */
fun tones(count: Int, lengthMs: Long = 60_000, album: String? = null): List<Song> =
    (1..count).map { song("Music/Fade Test/%02d Tone %d.flac".format(it, it), album = album, track = it, durationMs = lengthMs) }

val PLAYLIST: QueueSource = QueueSource(SourceKind.PLAYLIST, "playlist:fade", "Fade test")
val ALBUM: QueueSource = QueueSource(SourceKind.ALBUM, "album:fade", "Fade Test")

/**
 * The crossfade engine over fake players on virtual time ([scope]'s scheduler): a queue of [songs] from [source],
 * loaded from its first song and playing unless [play] is false. [before] runs before the load, to listen from the
 * first song. The players' time runs every [STEP_MS].
 */
@kotlin.OptIn(ExperimentalCoroutinesApi::class)
class FadeRig(
    private val scope: TestScope,
    crossfadeSeconds: Int,
    val songs: List<Song> = tones(4),
    source: QueueSource = PLAYLIST,
    play: Boolean = true,
    before: (FadeRig) -> Unit = {},
) {
    val clock: () -> Long = { scope.testScheduler.currentTime }
    val queue: Queue = QueueBook().play(songs.map { it.ref }, 0, source, now = 1).book.current!!
    val uids: List<Long> = queue.items.map { it.uid }
    private val byUid: Map<Long, Song> = queue.items.associate { item -> item.uid to songs.first { it.ref == item.song } }
    val slots = mutableListOf<FakeSlot>()
    val focus = FakeFocus()
    var mayFade: (Long, Long) -> Boolean = { _, _ -> true }
    var speeds: (Long) -> PlaybackParameters? = { null }
    val heard = Heard()
    val engine = CrossfadeEngine(
        newSlot = { FakeSlot(slots.size, clock) { uid -> songOf(uid).durationMs }.also { slots += it } },
        rules = object : FadeRules {
            override fun mayFade(from: Long, to: Long): Boolean = this@FadeRig.mayFade(from, to)

            override fun parametersOf(uid: Long): PlaybackParameters? = speeds(uid)
        },
        focusOf = { control -> focus.also { it.control = control } },
        scope = scope.backgroundScope,
        clock = clock,
        later = { task -> scope.backgroundScope.launch { task() } },
    )

    init {
        engine.addListener(heard)
        engine.setCrossfade(crossfadeSeconds)
        scope.backgroundScope.launch {
            while (true) {
                delay(STEP_MS)
                slots.toList().forEach { it.advance() }
            }
        }
        before(this)
        engine.load(uids.map { Projected(it, songOf(it)) }, 0, 0)
        if (play) engine.setPlayWhenReady(true)
        scope.runCurrent()
    }

    fun songOf(uid: Long): Song = byUid.getValue(uid)

    /** The players not let go. */
    val live: List<FakeSlot> get() = slots.filterNot { it.released }

    /** The two players of a fade in [of], the older first; the test fails unless there are two. */
    fun two(of: List<FakeSlot>): Pair<FakeSlot, FakeSlot> {
        assertEquals("players", 2, of.size)
        return of[0] to of[1]
    }

    /** The player made [n]th (from 0); the test fails if it never was. */
    fun made(n: Int): FakeSlot {
        assertTrue("player ${n + 1} was never made", n < slots.size)
        return slots[n]
    }

    fun run(ms: Long) {
        scope.advanceTimeBy(ms)
        scope.runCurrent()
    }

    /** Runs until the song playing is [ms] of time from its end, at its speed. */
    fun runToLeft(ms: Long) = run(((engine.durationMs - engine.positionMs) / engine.playbackParameters.speed).toLong() - ms)

    /** "start <uid>" as [Heard] notes it, for song [n] of the queue (from 0). */
    fun start(n: Int, natural: Boolean = false, lap: Boolean = false): String =
        "start ${uids[n]}" + (if (lap) " lap" else "") + (if (natural) " natural" else "")

    companion object {
        const val STEP_MS: Long = 5
    }
}
