package app.lyrify.stats

import app.lyrify.library.SongRef
import app.lyrify.settings.PlaybackSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs

/** "Count a play after": when a play counts. */
object PlayRules {
    /** Songs shorter than this never count, however much of them is heard. */
    const val MIN_SONG_MS: Long = 30_000

    /** The player's last position can fall a little short of the song's length: 100% is the end, give or take this. */
    const val END_SLACK_MS: Long = 1_000

    /**
     * Whether hearing [heardMs] of a song [durationMs] long counts as a play under "Count a play after" [sharePercent]
     * (one of [PlaybackSettings.PLAY_SHARES]). A song under [MIN_SONG_MS], or of unknown length, never counts.
     */
    fun counts(durationMs: Long, heardMs: Long, sharePercent: Int): Boolean {
        if (durationMs < MIN_SONG_MS) return false
        val share = sharePercent.coerceIn(PlaybackSettings.PLAY_SHARES.first(), PlaybackSettings.PLAY_SHARES.last())
        return heardMs >= minOf(durationMs * share / 100, durationMs - END_SLACK_MS)
    }
}

/** A part of a song heard: from [startMs] up to [endMs], in millis. */
data class HeardSpan(val startMs: Long, val endMs: Long)

/**
 * One play's time heard, worked out from the player's raw events ([PlayEvent]): the position moving on while playing is
 * heard; paused time and a seek's jump are not. [heardMs] is all the time played (listening time), [coveredMs] the part
 * of the song heard at least once (what "Count a play after" measures), so hearing a part again after seeking back adds
 * listening time but no share. A jump the player did not report (the position moving faster than [MAX_SPEED] times
 * the clock) counts only the time it took. A play the app kept before it stopped goes on from its [heardMs] and [spans].
 */
class HeardTime(val durationMs: Long, heardMs: Long = 0, spans: List<HeardSpan> = emptyList()) {
    var heardMs: Long = heardMs
        private set

    /** The parts heard, in order and apart. */
    var spans: List<HeardSpan> = emptyList()
        private set

    /** Where the player last was; null until it said. */
    var positionMs: Long? = null
        private set

    var playing: Boolean = false
        private set

    private var at: Long = 0

    init {
        spans.forEach { cover(it.startMs, it.endMs) }
    }

    val coveredMs: Long get() = spans.sumOf { it.endMs - it.startMs }

    /** The player is at [positionMs], [playing] or not, at wall-clock [at]. */
    fun position(positionMs: Long, playing: Boolean, at: Long) {
        moveTo(positionMs, at)
        this.playing = playing
    }

    /** A seek from [fromMs] to [toMs]: heard up to [fromMs], then nothing in between. */
    fun seeked(fromMs: Long, toMs: Long, at: Long) {
        moveTo(fromMs, at)
        positionMs = toMs
    }

    /** The play stopped at [positionMs]. */
    fun ended(positionMs: Long, at: Long) {
        moveTo(positionMs, at)
        playing = false
    }

    private fun moveTo(positionMs: Long, now: Long) {
        val from = this.positionMs
        if (playing && from != null && positionMs > from) {
            val moved = positionMs - from
            val took = (now - at).coerceAtLeast(0)
            val heard = if (moved <= took * MAX_SPEED + SLACK_MS) moved else minOf(moved, took)
            heardMs += heard
            cover(from, from + heard)
        }
        this.positionMs = positionMs
        at = now
    }

    /** Adds the part from [start] to [end], within the song, to [spans], merged with the parts it touches. */
    private fun cover(start: Long, end: Long) {
        val length = durationMs.coerceAtLeast(0)
        var added = HeardSpan(start.coerceIn(0, length), end.coerceIn(0, length))
        if (added.endMs <= added.startMs) return
        // In order, so a part merged in can only reach the ones after it.
        val apart = spans.filter { span ->
            val touches = span.startMs <= added.endMs && added.startMs <= span.endMs
            if (touches) added = HeardSpan(minOf(span.startMs, added.startMs), maxOf(span.endMs, added.endMs))
            !touches
        }
        spans = (apart + added).sortedBy { it.startMs }
    }

    private companion object {
        /** Faster than the speed sheet goes (2x), so playing is never taken for a jump. */
        const val MAX_SPEED: Long = 4

        /** Room for the player's position and the clock being read a moment apart. */
        const val SLACK_MS: Long = 2_000
    }
}

/** One play in the log: [song] started at [startedAt] (wall-clock millis), [heardMs] heard in all, and whether it [counted]. */
data class LoggedPlay(val song: SongRef, val startedAt: Long, val heardMs: Long, val counted: Boolean)

/**
 * The play counter: takes the player's raw events, keeps each play's [HeardTime], and writes the play to [store] once
 * its share ("Count a play after", [share]) is heard, so Recently played has it at once, then again when it ends.
 *
 * - Nothing is counted before the setting is read ([share] null): events wait. A change to the setting applies to the
 *   plays going on from their next event; a play counted stays counted.
 * - A play still going is kept, with where it stands, every [KEEP_EVERY_MS] while it plays and whenever it pauses, so
 *   a restart of the app loses little. After a restart, a play of the song a kept play was of, from within
 *   [RESUME_SLACK_MS] of where it stood, goes on as that play (one count, one row); any other play closes the kept ones
 *   as they stood.
 * - The same play started again (its id and song) is still one play. A play skipped before [MIN_LOGGED_MS] leaves
 *   nothing. At most [MAX_OPEN] plays go on at once (a crossfade has two); one that never ended is closed, as it stands,
 *   when a newer one would be one too many.
 *
 * Events are handled in [scope], one at a time and in order, so the player never waits; [written] counts the writes
 * that landed, so pages showing plays can read them again. The log gets counts and exception names only.
 */
class PlayCounter(
    private val store: PlayLogStore,
    private val share: StateFlow<Int?>,
    scope: CoroutineScope,
    private val log: (String) -> Unit = {},
) : PlayEvents {
    private class Live(val song: SongRef, var startedAt: Long, var heard: HeardTime, var resumable: StoredPlay?) {
        var counted: Boolean = false
        var keptAt: Long = startedAt
    }

    private val events = Channel<PlayEvent>(Channel.UNLIMITED)
    private val live = LinkedHashMap<Long, Live>()
    private var kept: List<StoredPlay> = emptyList()
    private val _written = MutableStateFlow(0)

    val written: StateFlow<Int> = _written.asStateFlow()

    init {
        scope.launch {
            share.filterNotNull().first()
            kept = store.openPlays()
            for (event in events) handle(event)
        }
    }

    override fun onEvent(event: PlayEvent) {
        events.trySend(event)
    }

    private suspend fun handle(event: PlayEvent) {
        if (event is PlayEvent.Started) return start(event)
        val play = live[event.playId] ?: return
        play.resumable?.let { resume(play, it, positionOf(event)) }
        val wasPlaying = play.heard.playing
        when (event) {
            is PlayEvent.Position -> play.heard.position(event.positionMs, event.playing, event.at)
            is PlayEvent.Seeked -> play.heard.seeked(event.fromMs, event.toMs, event.at)
            is PlayEvent.Ended -> play.heard.ended(event.positionMs, event.at)
        }
        val countsNow = !play.counted && PlayRules.counts(play.heard.durationMs, play.heard.coveredMs, checkNotNull(share.value))
        if (countsNow) play.counted = true
        val paused = wasPlaying && !play.heard.playing
        when {
            event is PlayEvent.Ended -> {
                live.remove(event.playId)
                close(play)
            }
            countsNow || paused || event.at - play.keptAt >= KEEP_EVERY_MS -> keep(play, event.at)
        }
    }

    private suspend fun start(event: PlayEvent.Started) {
        val same = live[event.playId]
        if (same != null) {
            if (same.song.path == event.song.path) return
            live.remove(event.playId)
            close(same)
        }
        while (live.size >= MAX_OPEN) live.remove(live.keys.first())?.let { close(it) }
        val resumable = kept.firstOrNull { it.play.song.path == event.song.path }
        kept.filter { it !== resumable }.forEach { write(it.play, open = null) }
        kept = emptyList()
        live[event.playId] = Live(event.song, event.at, HeardTime(event.durationMs), resumable)
    }

    /** The first word from a play that may go on from [stored]: it does when it is where [stored] stood, else that one closes. */
    private suspend fun resume(play: Live, stored: StoredPlay, positionMs: Long) {
        play.resumable = null
        if (abs(positionMs - stored.state.positionMs) > RESUME_SLACK_MS) return write(stored.play, open = null)
        play.startedAt = stored.play.startedAt
        play.heard = HeardTime(play.heard.durationMs, stored.play.heardMs, stored.state.spans)
        play.counted = stored.play.counted
        play.keptAt = stored.play.startedAt
    }

    private fun positionOf(event: PlayEvent): Long = when (event) {
        is PlayEvent.Position -> event.positionMs
        is PlayEvent.Seeked -> event.fromMs
        is PlayEvent.Ended -> event.positionMs
        is PlayEvent.Started -> 0
    }

    private fun logged(play: Live): Boolean = play.counted || play.heard.heardMs >= MIN_LOGGED_MS

    private fun row(play: Live): LoggedPlay = LoggedPlay(play.song, play.startedAt, play.heard.heardMs, play.counted)

    private suspend fun keep(play: Live, at: Long) {
        if (!logged(play)) return
        play.keptAt = at
        write(row(play), OpenState(play.heard.durationMs, play.heard.positionMs ?: 0, play.heard.spans))
    }

    private suspend fun close(play: Live) {
        play.resumable?.let { write(it.play, open = null) }
        if (logged(play)) write(row(play), open = null)
    }

    private suspend fun write(play: LoggedPlay, open: OpenState?) {
        if (store.put(play, open)) _written.update { it + 1 } else log("plays: a play was not written")
    }

    companion object {
        /** A play heard less than this, and not counted, is not logged: a skip. */
        const val MIN_LOGGED_MS: Long = 1_000

        const val MAX_OPEN: Int = 2

        /** How often a play going on is kept while it plays. */
        const val KEEP_EVERY_MS: Long = 15_000

        /**
         * How far from where a kept play stood a play of its song may start and still go on as it: the player keeps its
         * place every 10 s, the counter every [KEEP_EVERY_MS].
         */
        const val RESUME_SLACK_MS: Long = 30_000
    }
}
