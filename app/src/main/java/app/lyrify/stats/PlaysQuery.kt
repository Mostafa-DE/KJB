package app.lyrify.stats

import android.content.Context
import app.lyrify.library.SongRef

/** One song's plays: how many, the last one (wall-clock millis), and how long it was heard in all. */
data class SongPlays(val song: SongRef, val count: Int, val lastPlayedAt: Long?, val heardMs: Long)

/**
 * What the library asks of the play counter: the "Most played" and "Recently played" lists and Song info's plays line.
 * stats/ implements it over `plays.db` (milestone S1); until then [NoPlays].
 */
interface PlaysQuery {
    /** "Most played": the songs played most, all time, the most first. */
    suspend fun mostPlayed(limit: Int = 50): List<SongPlays>

    /** "Recently played": the songs played last, the latest first. */
    suspend fun recentlyPlayed(limit: Int = 50): List<SongPlays>

    /** [song]'s plays; null when it was never played. */
    suspend fun plays(song: SongRef): SongPlays?
}

/** Until milestone S1: no song has been played. */
object NoPlays : PlaysQuery {
    override suspend fun mostPlayed(limit: Int): List<SongPlays> = emptyList()

    override suspend fun recentlyPlayed(limit: Int): List<SongPlays> = emptyList()

    override suspend fun plays(song: SongRef): SongPlays? = null
}

/** The play counter's objects, built by hand (milestone S1 builds them; it may move this object to its own file). */
object StatsGraph {
    @Suppress("UNUSED_PARAMETER") // Until S1: no plays.db to open.
    fun events(context: Context): PlayEvents = NoPlayEvents

    @Suppress("UNUSED_PARAMETER") // Until S1: no plays.db to open.
    fun plays(context: Context): PlaysQuery = NoPlays
}
