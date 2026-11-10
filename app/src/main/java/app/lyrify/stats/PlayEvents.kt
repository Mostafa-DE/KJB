package app.lyrify.stats

import app.lyrify.library.SongRef

/**
 * What KJB's own player tells the play counter, as it happens: player/ sends them (milestone P1), raw, and the counter
 * (stats/PlayRules.kt) works out the time heard and when a play counts ("Count a play after"). Songs played in other
 * apps send none. [playId] ties the events of one play together; [at] is wall-clock millis; positions are in the song,
 * in millis.
 */
sealed interface PlayEvent {
    val playId: Long
    val song: SongRef
    val at: Long

    /** A play begins: a song starts, or starts again (each lap of Repeat one is a new play). [durationMs]: the song's length. */
    data class Started(override val playId: Long, override val song: SongRef, override val at: Long, val durationMs: Long) : PlayEvent

    /**
     * The player is at [positionMs], and [playing] (sound coming out, not paused or buffering) or not. Sent when the play
     * starts, whenever playing starts or stops, and every few seconds while it plays, so a play counts as soon as its
     * share is heard.
     */
    data class Position(override val playId: Long, override val song: SongRef, override val at: Long, val positionMs: Long, val playing: Boolean) : PlayEvent

    /** The position jumped from [fromMs] to [toMs] (a seek); the part jumped over is not heard. */
    data class Seeked(override val playId: Long, override val song: SongRef, override val at: Long, val fromMs: Long, val toMs: Long) : PlayEvent

    /**
     * The play is over at [positionMs]: the song ended (its [Started.durationMs]), was skipped or replaced, or playback
     * stopped.
     */
    data class Ended(override val playId: Long, override val song: SongRef, override val at: Long, val positionMs: Long) : PlayEvent
}

/** Where the player sends [PlayEvent]s: the play counter ([PlayCounter]). Never blocks the player. */
fun interface PlayEvents {
    fun onEvent(event: PlayEvent)
}

/** Plays not counted: tests, and the player before the counter is built. */
object NoPlayEvents : PlayEvents {
    override fun onEvent(event: PlayEvent) = Unit
}
