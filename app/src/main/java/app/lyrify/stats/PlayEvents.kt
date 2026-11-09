package app.lyrify.stats

import app.lyrify.library.SongRef

/**
 * What KJB's own player tells the play counter, one play at a time: player/ sends them (milestone P1), and
 * stats/PlayRules.kt (milestone S1) decides when a play counts ("Count a play after"). Songs played in other apps send
 * none. [playId] ties the events of one play together; [at] is wall-clock millis.
 */
sealed interface PlayEvent {
    val playId: Long
    val song: SongRef
    val at: Long

    /** A play begins: a song starts, or starts again (each lap of Repeat one is a new play). [durationMs]: the song's length. */
    data class Started(override val playId: Long, override val song: SongRef, override val at: Long, val durationMs: Long) : PlayEvent

    /** [heardMs] more of the song was heard, actually playing (paused time never counts), since the play's last event. */
    data class Heard(override val playId: Long, override val song: SongRef, override val at: Long, val heardMs: Long) : PlayEvent

    /** The position jumped from [fromMs] to [toMs]; the part jumped over is not heard. */
    data class Seeked(override val playId: Long, override val song: SongRef, override val at: Long, val fromMs: Long, val toMs: Long) : PlayEvent

    /** The play is over: the song ended, was skipped or replaced, or playback stopped. */
    data class Ended(override val playId: Long, override val song: SongRef, override val at: Long) : PlayEvent
}

/** Where the player sends [PlayEvent]s: the play counter (milestone S1); until then [NoPlayEvents]. Never blocks the player. */
fun interface PlayEvents {
    fun onEvent(event: PlayEvent)
}

/** Until milestone S1: plays are not counted. */
object NoPlayEvents : PlayEvents {
    override fun onEvent(event: PlayEvent) = Unit
}
