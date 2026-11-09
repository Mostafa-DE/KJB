package app.lyrify.stats

import app.lyrify.library.SongRef
import app.lyrify.settings.PlaybackSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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

/**
 * One play's time heard, worked out from the player's raw events ([PlayEvent]): the position moving on while playing is
 * heard; paused time and a seek's jump are not. [heardMs] is all the time played (listening time), [coveredMs] the part
 * of the song heard at least once (what "Count a play after" measures), so hearing a part again after seeking back adds
 * listening time but no share. A jump the player did not report (the position moving faster than [MAX_SPEED] times
 * the clock) counts only the time it took.
 */
class HeardTime(val durationMs: Long) {
    var heardMs: Long = 0
        private set

    /** A part of the song heard: from [start] up to [end], in millis. */
    private data class Span(val start: Long, val end: Long)

    /** The parts heard, in order and apart. */
    private var spans: List<Span> = emptyList()
    private var position: Long? = null
    private var at: Long = 0
    private var playing = false

    val coveredMs: Long get() = spans.sumOf { it.end - it.start }

    /** The player is at [positionMs], [playing] or not, at wall-clock [at]. */
    fun position(positionMs: Long, playing: Boolean, at: Long) {
        moveTo(positionMs, at)
        this.playing = playing
    }

    /** A seek from [fromMs] to [toMs]: heard up to [fromMs], then nothing in between. */
    fun seeked(fromMs: Long, toMs: Long, at: Long) {
        moveTo(fromMs, at)
        position = toMs
    }

    /** The play stopped at [positionMs]. */
    fun ended(positionMs: Long, at: Long) {
        moveTo(positionMs, at)
        playing = false
    }

    private fun moveTo(positionMs: Long, now: Long) {
        val from = position
        if (playing && from != null && positionMs > from) {
            val moved = positionMs - from
            val took = (now - at).coerceAtLeast(0)
            val heard = if (moved <= took * MAX_SPEED + SLACK_MS) moved else minOf(moved, took)
            heardMs += heard
            cover(from, from + heard)
        }
        position = positionMs
        at = now
    }

    /** Adds the part from [start] to [end], within the song, to [spans], merged with the parts it touches. */
    private fun cover(start: Long, end: Long) {
        val length = durationMs.coerceAtLeast(0)
        var added = Span(start.coerceIn(0, length), end.coerceIn(0, length))
        if (added.end <= added.start) return
        // In order, so a part merged in can only reach the ones after it.
        val apart = spans.filter { span ->
            val touches = span.start <= added.end && added.start <= span.end
            if (touches) added = Span(minOf(span.start, added.start), maxOf(span.end, added.end))
            !touches
        }
        spans = (apart + added).sortedBy { it.start }
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
 * its share ([share], "Count a play after", read at every event) is heard, so Recently played has it at once, then
 * again when it ends, with all it heard. A play skipped before [MIN_LOGGED_MS] leaves nothing. At most [MAX_OPEN] plays
 * are open at once (a crossfade has two); a play that never ended is closed, as it stands, when a newer one would be
 * one too many. Writes run in [scope], one at a time and in order; [written] counts those that landed, so pages showing
 * plays can read them again. The log gets counts and exception names only.
 */
class PlayCounter(
    private val store: PlayLogStore,
    private val share: () -> Int,
    scope: CoroutineScope,
    private val log: (String) -> Unit = {},
) : PlayEvents {
    private class OpenPlay(val song: SongRef, val startedAt: Long, val heard: HeardTime, var counted: Boolean = false)

    private val open = LinkedHashMap<Long, OpenPlay>()
    private val writes = Channel<LoggedPlay>(Channel.UNLIMITED)
    private val _written = MutableStateFlow(0)

    val written: StateFlow<Int> = _written.asStateFlow()

    init {
        scope.launch {
            for (play in writes) {
                if (store.put(play)) _written.update { it + 1 } else log("plays: a play was not written")
            }
        }
    }

    @Synchronized
    override fun onEvent(event: PlayEvent) {
        if (event is PlayEvent.Started) {
            open.remove(event.playId)?.let(::close)
            while (open.size >= MAX_OPEN) open.remove(open.keys.first())?.let(::close)
            open[event.playId] = OpenPlay(event.song, event.at, HeardTime(event.durationMs))
            return
        }
        val play = open[event.playId] ?: return
        when (event) {
            is PlayEvent.Position -> play.heard.position(event.positionMs, event.playing, event.at)
            is PlayEvent.Seeked -> play.heard.seeked(event.fromMs, event.toMs, event.at)
            is PlayEvent.Ended -> play.heard.ended(event.positionMs, event.at)
        }
        val countsNow = !play.counted && PlayRules.counts(play.heard.durationMs, play.heard.coveredMs, share())
        if (countsNow) play.counted = true
        if (event is PlayEvent.Ended) {
            open.remove(event.playId)
            close(play)
        } else if (countsNow) {
            write(play)
        }
    }

    private fun close(play: OpenPlay) {
        if (play.counted || play.heard.heardMs >= MIN_LOGGED_MS) write(play)
    }

    private fun write(play: OpenPlay) {
        writes.trySend(LoggedPlay(play.song, play.startedAt, play.heard.heardMs, play.counted))
    }

    companion object {
        /** A play heard less than this, and not counted, is not logged: a skip. */
        const val MIN_LOGGED_MS: Long = 1_000

        const val MAX_OPEN: Int = 2
    }
}
