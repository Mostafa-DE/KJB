package app.lyrify.stats

import app.lyrify.library.SongRef

/**
 * `plays.db` made up, in memory, with its rules (PlaysDbDeviceTest checks the real one against the same): a play
 * written again is the one of the same song and start, and keeps the most time heard, its count once counted, and its
 * id hint unless a newer one is given; with an [OpenState] it is kept as still going, without one it is let go. A song's
 * totals count its plays that counted, the last of those, and the time heard in all its plays; its id hint is its
 * latest play's. [failing]: every write fails.
 */
class FakePlayLog(plays: List<LoggedPlay> = emptyList()) : PlayLogStore {
    val plays: MutableList<LoggedPlay> = plays.toMutableList()
    private val open = LinkedHashMap<Pair<String, Long>, OpenState>()
    var failing: Boolean = false

    override suspend fun put(play: LoggedPlay, open: OpenState?): Boolean {
        if (failing) return false
        val key = play.song.path to play.startedAt
        val old = plays.firstOrNull { it.song.path == play.song.path && it.startedAt == play.startedAt }
        val row = if (old == null) {
            play
        } else {
            LoggedPlay(SongRef(play.song.path, play.song.mediaStoreId ?: old.song.mediaStoreId), play.startedAt, maxOf(old.heardMs, play.heardMs), old.counted || play.counted)
        }
        plays.remove(old)
        plays += row
        if (open != null) this.open[key] = open else this.open.remove(key)
        return true
    }

    override suspend fun openPlays(): List<StoredPlay> = open.map { (key, state) ->
        StoredPlay(plays.first { it.song.path == key.first && it.startedAt == key.second }, state)
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
