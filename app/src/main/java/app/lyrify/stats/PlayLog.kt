package app.lyrify.stats

/**
 * Where plays are kept: `plays.db`, one row per play. Safe from any thread; a failure is false or an empty answer,
 * never thrown, so counting goes on.
 */
interface PlayLogStore {
    /** Keeps [play], replacing the one of the same song and start (a play is written when it counts, then when it ends). */
    suspend fun put(play: LoggedPlay): Boolean

    /**
     * Each song's totals over the plays that started from [from] (inclusive) until [until] (exclusive), either end open:
     * its plays that counted, the last of those, the time heard in all its plays, and the id hint of its latest play.
     */
    suspend fun totals(from: Long? = null, until: Long? = null): List<SongPlays>
}
