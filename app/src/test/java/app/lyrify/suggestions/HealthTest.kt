package app.lyrify.suggestions

import app.lyrify.library.Library
import app.lyrify.library.Song
import app.lyrify.library.song
import app.lyrify.player.SourceKind
import app.lyrify.settings.Settings
import app.lyrify.stats.LoggedPlay
import app.lyrify.stats.PlayEnd
import app.lyrify.stats.PlayEnded
import app.lyrify.stats.PlayStart
import app.lyrify.stats.StartedBy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** Suggestions health: the replay test against three simple guesses, skips, and discoveries kept. Made-up names only. */
class HealthTest {
    private val amman = ZoneId.of("Asia/Amman")
    private val now = ZonedDateTime.parse("2026-11-22T12:00+03:00").toInstant().toEpochMilli()
    private val day = DAY_MS.toLong()

    private val songs: List<Song> = (0 until 30).map { song("Music/Artist $it/Song $it.mp3", title = "Song $it", artist = "Artist $it", album = "Album $it", id = it + 1L) }
    private val library = Library.of(songs, Settings())

    @Suppress("LongParameterList")
    private fun play(
        n: Int,
        at: Long,
        by: StartedBy = StartedBy.PICKED,
        how: PlayEnd = PlayEnd.FINISHED,
        leftAtMs: Long = songs[n].durationMs,
        shuffled: Boolean = false,
        imported: Boolean = false,
    ) = LoggedPlay(songs[n].ref, at, leftAtMs, leftAtMs >= songs[n].durationMs / 2, PlayStart(by, SourceKind.SONGS, shuffled, null), 180, PlayEnded(how, leftAtMs), imported)

    /** Songs 0 to 9 played 20 times each 100 days ago; then each of the last 10 days songs 20 and 21. */
    private val rotation: List<LoggedPlay> = buildList {
        for (n in 0 until 10) for (k in 0 until 20) add(play(n, now - 100 * day + (n * 20 + k) * 600_000L))
        for (d in 10 downTo 1) {
            add(play(20, now - d * day))
            add(play(21, now - d * day + 300_000))
        }
    }

    private fun healthOf(plays: List<LoggedPlay>, favourites: Map<String, Long> = emptyMap()): Health {
        val sorted = plays.sortedBy { it.startedAt }
        return health(sorted, Taste.of(sorted, library, favourites, now, amman), library)
    }

    @Test
    fun `the replay test steps through the last 30 days and scores each guess's top 10`() {
        val health = healthOf(rotation)
        assertEquals(20, health.steps)
        assertEquals(10.0 / 30, health.random, 1e-9)
        assertEquals(0.0, health.mostPlayed, 1e-9)
        assertEquals(18.0 / 20, health.mostRecent, 1e-9)
        assertTrue(health.hitRate > health.random)
        assertEquals(health.mostRecent, health.baseline, 1e-9)
    }

    @Test
    fun `suggested plays, early skips, imports and the same song again are no steps`() {
        val extra = listOf(
            play(22, now - 5 * day + 60_000, by = StartedBy.SUGGESTED),
            play(23, now - 5 * day + 120_000, how = PlayEnd.NEXT, leftAtMs = 4_000),
            play(24, now - 5 * day + 180_000, imported = true),
            play(24, now - 4 * day + 900_000),
            play(24, now - 4 * day + 1_200_000, by = StartedBy.AUTO),
        )
        assertEquals(20 + 1, healthOf(rotation + extra).steps)
    }

    @Test
    fun `skips of suggested songs are counted beside plain Shuffle's, in the last 30 days only`() {
        val plays = listOf(
            play(1, now - 3 * day, by = StartedBy.SUGGESTED),
            play(2, now - 3 * day + 300_000, by = StartedBy.SUGGESTED, how = PlayEnd.NEXT, leftAtMs = 5_000),
            play(3, now - 2 * day, by = StartedBy.SUGGESTED),
            play(4, now - 2 * day + 300_000, by = StartedBy.SUGGESTED),
            play(5, now - day, by = StartedBy.AUTO, shuffled = true, how = PlayEnd.NEXT, leftAtMs = 2_000),
            play(6, now - day + 100_000, by = StartedBy.NEXT, shuffled = true, how = PlayEnd.NEXT, leftAtMs = 2_000),
            play(7, now - day + 200_000, by = StartedBy.AUTO, shuffled = true),
            play(8, now - day + 300_000, by = StartedBy.AUTO, shuffled = true),
            play(9, now - day + 400_000, by = StartedBy.AUTO, shuffled = true),
            play(10, now - day + 500_000, by = StartedBy.PICKED, shuffled = true, how = PlayEnd.NEXT, leftAtMs = 2_000),
            play(11, now - day + 600_000, by = StartedBy.AUTO, shuffled = false, how = PlayEnd.NEXT, leftAtMs = 2_000),
            play(12, now - 40 * day, by = StartedBy.SUGGESTED, how = PlayEnd.NEXT, leftAtMs = 2_000),
        )
        val health = healthOf(plays)
        assertEquals(4, health.suggestedPlays)
        assertEquals(1, health.suggestedSkips)
        assertEquals(5, health.shufflePlays)
        assertEquals(2, health.shuffleSkips)
    }

    @Test
    fun `a discovery is kept when it becomes a favourite or is played again by hand`() {
        val plays = listOf(
            // Song 14, loved a year ago: a rediscovery.
            *Array(5) { play(14, now - (365 - it) * day) },
            // Song 15, played this month: familiar.
            play(15, now - 20 * day), play(15, now - 19 * day),
            play(13, now - 10 * day, by = StartedBy.SUGGESTED),
            play(13, now - 5 * day, by = StartedBy.PICKED),
            play(14, now - 9 * day, by = StartedBy.SUGGESTED),
            play(16, now - 8 * day, by = StartedBy.SUGGESTED, how = PlayEnd.NEXT, leftAtMs = 3_000),
            play(15, now - 7 * day, by = StartedBy.SUGGESTED),
        )
        val health = healthOf(plays, favourites = mapOf(songs[14].path to now - 2 * day, songs[16].path to 0L))
        assertEquals(3, health.discoveries)
        assertEquals(2, health.kept)
    }
}
