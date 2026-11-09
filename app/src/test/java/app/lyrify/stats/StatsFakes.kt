package app.lyrify.stats

import app.lyrify.library.SongRef

/**
 * `plays.db` made up, in memory, with its rules (PlaysDbDeviceTest checks the real one against the same): a play
 * replaces the one of the same song and start; a song's totals count its plays that counted, the last of those, and
 * the time heard in all its plays; its id hint is its latest play's. [failing]: every write fails.
 */
class FakePlayLog(plays: List<LoggedPlay> = emptyList()) : PlayLogStore {
    val plays: MutableList<LoggedPlay> = plays.toMutableList()
    var failing: Boolean = false
    var puts: Int = 0
        private set

    override suspend fun put(play: LoggedPlay): Boolean {
        puts++
        if (failing) return false
        plays.removeAll { it.song.path == play.song.path && it.startedAt == play.startedAt }
        plays += play
        return true
    }

    override suspend fun totals(from: Long?, until: Long?): List<SongPlays> {
        val hints = plays.groupBy { it.song.path }.mapValues { (_, its) -> its.maxBy { it.startedAt }.song.mediaStoreId }
        return plays.filter { (from == null || it.startedAt >= from) && (until == null || it.startedAt < until) }
            .groupBy { it.song.path }
            .map { (path, its) ->
                SongPlays(SongRef(path, hints[path]), its.count { it.counted }, its.filter { it.counted }.maxOfOrNull { it.startedAt }, its.sumOf { it.heardMs })
            }
    }
}
