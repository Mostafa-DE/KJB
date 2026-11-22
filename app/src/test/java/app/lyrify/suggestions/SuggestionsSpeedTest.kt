package app.lyrify.suggestions

import app.lyrify.library.Library
import app.lyrify.library.song
import app.lyrify.player.SourceKind
import app.lyrify.settings.Adventure
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
import kotlin.math.pow
import kotlin.random.Random

/**
 * The engine on a big phone's history: 5,000 songs and 100,000 plays over three years, made up. Each step's time goes to
 * the test's output (sg1-report.md has the numbers); the bound is loose, to catch a step gone quadratic, not to time a phone.
 */
class SuggestionsSpeedTest {
    private val amman = ZoneId.of("Asia/Amman")
    private val now = 1_795_000_000_000L
    private val random = Random(42)

    private val songs = (0 until 500).flatMap { a ->
        (0 until 10).map { n ->
            song(
                "Music/Artist $a/Album ${n / 5}/Song $n.mp3", title = "Song $a $n", artist = "Artist $a", album = "Album $a ${n / 5}", track = n % 5 + 1,
                genre = "Genre ${a % 20}", year = 1970 + a % 50, addedAt = now - 1_000L * 86_400_000L, id = a * 10L + n + 1,
            )
        }
    }
    private val library = Library.of(songs, Settings())

    /** 100,000 plays over three years, a few songs played far more than most (a power law), sessions of a few songs. */
    private val plays: List<LoggedPlay> = run {
        val span = 3 * 365 * 86_400_000L
        var at = now - span
        val step = span / 100_000
        List(100_000) {
            at += (step * random.nextDouble() * 2).toLong()
            val index = (songs.size * random.nextDouble().pow(3)).toInt().coerceAtMost(songs.size - 1)
            val song = songs[index]
            val by = StartedBy.entries[random.nextInt(StartedBy.entries.size)]
            val skipped = random.nextDouble() < 0.25
            val left = if (skipped) (song.durationMs * random.nextDouble()).toLong() else song.durationMs
            LoggedPlay(song.ref, at, left, left >= song.durationMs / 2, PlayStart(by, SourceKind.SONGS, random.nextBoolean(), null), 180, PlayEnded(if (skipped) PlayEnd.NEXT else PlayEnd.FINISHED, left))
        }
    }

    private inline fun <T> timed(times: MutableMap<String, Long>, name: String, work: () -> T): T {
        val start = System.nanoTime()
        return work().also { times[name] = (System.nanoTime() - start) / 1_000_000 }
    }

    @Test
    fun `5,000 songs and 100,000 plays take well under a few seconds per step on the laptop`() {
        val times = LinkedHashMap<String, Long>()
        val favourites = songs.take(200).associate { it.path to 0L }
        val taste = timed(times, "taste") { Taste.of(plays, library, favourites, now, amman) }
        val mix = mixOf(Adventure.MIX, taste.outcomes)
        val avoid = taste.recentPaths
        val forYou = timed(times, "forYou") { forYou(taste, library, mix, avoid, Random(1), amman) }
        val queue = songs.subList(100, 150)
        val autoplay = timed(times, "autoplay") { autoplay(taste, library, mix, queue, avoid + queue.map { it.path }, Random(2), amman) }
        val upcoming = songs.subList(200, 260).map { Upcoming(it, suggested = false) }
        val inserts = timed(times, "smartShuffle") { smartInserts(taste, library, mix, songs[199], upcoming, avoid + upcoming.map { it.song.path }, Random(3), amman) }
        val more = timed(times, "moreLike") { moreLike(taste, library, Seed.OfSong(songs[5]), Basis.entries.toSet(), emptySet(), amman) }
        val health = timed(times, "health") { health(plays, taste, library) }
        System.out.println("sg1 speed (ms, laptop JVM): $times; steps=${health.steps}")

        assertEquals(FOR_YOU_SONGS, forYou.size)
        assertEquals(AUTOPLAY_SONGS, autoplay.size)
        assertTrue(inserts.isNotEmpty() && more.picks.isNotEmpty() && health.steps > 0)
        assertTrue("too slow: $times", times.values.all { it < 5_000 })
    }
}
