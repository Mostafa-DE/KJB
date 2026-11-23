package app.lyrify.stats

import app.lyrify.library.Library
import app.lyrify.library.SongRef
import app.lyrify.library.VOLUME
import app.lyrify.library.song
import app.lyrify.player.SourceKind
import app.lyrify.settings.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.management.ManagementFactory
import java.time.DayOfWeek
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Insights at scale (lead, 2026-11-22): what Insights keep of the logs between reads, so a play written reads again only
 * that play (and the plays after it), a month that is over is worked out once, and 100,000 plays stay quick. Every
 * figure is checked against a fresh read of the same logs. The phone is in Berlin; now is Wednesday 11 November 2026,
 * 15:00. Made-up names only.
 */
class InsightsCacheTest {
    private val berlin = ZoneId.of("Europe/Berlin")

    private fun at(text: String): Long = ZonedDateTime.parse(text).toInstant().toEpochMilli()

    private val now = at("2026-11-11T15:00+01:00[Europe/Berlin]")
    private val oct15 = at("2026-10-15T12:00+02:00[Europe/Berlin]")

    private val songs = (1..30).map { song("Music/KJB test/Made Up $it.mp3", artist = "Made Up Band ${it % 4}", album = "Made Up Album ${it % 6}", genre = "Folk", id = it.toLong(), addedAt = it.toLong()) }
    private val library = Library.of(songs, Settings())

    private fun play(i: Int, startedAt: Long, counted: Boolean = true): LoggedPlay =
        LoggedPlay(songs[i % songs.size].ref, startedAt, 120_000, counted, zoneOffsetMinutes = 60, clockMs = 100_000)

    /** A play every 6 hours from 1 August to now, most of them counted, and one import. */
    private val history: List<LoggedPlay> = run {
        val from = at("2026-08-01T00:00+02:00[Europe/Berlin]")
        val plays = generateSequence(0) { it + 1 }.map { i -> play(i, from + i * 6 * HOUR, counted = i % 7 != 0) }.takeWhile { it.startedAt < now - HOUR }.toList()
        plays + LoggedPlay(songs[29].ref, from - DAY, 0, counted = true, imported = true)
    }

    private val book = SongRef(VOLUME + "Audiobooks/Salt Road/part 1.m4b")
    private val bookHistory = List(20) { LoggedPlay(book, oct15 + it * DAY, 900_000, counted = false, clockMs = 600_000) }

    /** [next], counting the plays its reads give: what Insights read of a log. */
    private class Counted(private val next: PlayLogStore) : PlayLogStore by next {
        var rows = 0

        override suspend fun all(): List<LoggedPlay>? = next.all()?.also { rows += it.size }

        override suspend fun since(from: Long): List<LoggedPlay>? = next.since(from)?.also { rows += it.size }
    }

    private fun stats(log: PlayLogStore, books: PlayLogStore? = null) = PlayStats(log, { library }, Dispatchers.Unconfined, books = books)

    /** What a fresh read of [log] and [books] gives: the figures the kept ones must match. */
    private fun fresh(log: PlayLogStore, books: PlayLogStore? = null, ask: suspend PlayStats.() -> Insights): Insights = runBlocking { stats(log, books).ask() }

    private suspend fun PlayStats.month(): Insights = insights(ListeningPeriod.MONTH, now, berlin, DayOfWeek.MONDAY)

    /**
     * The CPU time this thread spends on [work], in ms: the work itself. The wall clock also counts the wait for a core
     * while other builds load the laptop (3,332 ms against the 3,000 ms budget at load average 49 on 2026-11-22; 4,212 ms
     * with 32 busy loops beside the test, handoff/int4-insightstiming-red.log). With Dispatchers.Unconfined and the fake
     * log, Insights do all their work on this thread.
     */
    private inline fun cpuMs(work: () -> Unit): Long {
        val threads = ManagementFactory.getThreadMXBean()
        val start = threads.currentThreadCpuTime
        work()
        return (threads.currentThreadCpuTime - start) / 1_000_000
    }

    @Test
    fun `a play written after Insights were read is read again alone, and nothing written reads nothing`() = runBlocking {
        val log = FakePlayLog(history)
        val read = Counted(log)
        val stats = stats(read)
        val written = WatchedPlayLog(log, stats::changed)
        val before = stats.month()

        read.rows = 0
        written.put(play(3, now - 10 * MINUTE), OpenState(200_000, 30_000, emptyList()))
        val after = stats.month()

        assertEquals(1, read.rows)
        assertEquals(fresh(log) { month() }, after)
        assertEquals(before.totals?.plays?.plus(1), after.totals?.plays)
        // The same play kept again as it goes on, then closed: still only it.
        written.put(play(3, now - 10 * MINUTE).copy(heardMs = 200_000, clockMs = 190_000))
        read.rows = 0
        assertEquals(fresh(log) { month() }, stats.month())
        assertEquals(1, read.rows)
        // Nothing written since: nothing read, for any period.
        read.rows = 0
        ListeningPeriod.entries.forEach { stats.insights(it, now, berlin, DayOfWeek.MONDAY) }
        stats.recaps(now, berlin)
        assertEquals(0, read.rows)
    }

    @Test
    fun `a play from earlier written again reads from it on, and a restore or an import reads everything again`() = runBlocking {
        val log = FakePlayLog(history)
        val read = Counted(log)
        val stats = stats(read)
        val written = WatchedPlayLog(log, stats::changed)
        stats.month()

        // A play kept before the app stopped, closed now as it stood: the plays from its start on.
        val kept = history.filter { !it.imported }.takeLast(3).first()
        read.rows = 0
        written.put(kept.copy(end = PlayEnded(PlayEnd.STOPPED, 60_000)))
        assertEquals(fresh(log) { month() }, stats.month())
        assertEquals(3, read.rows)

        // A restore: every play again, the imported ones too.
        read.rows = 0
        written.putAll(listOf(play(5, oct15 + HOUR), LoggedPlay(songs[28].ref, oct15 + 2 * HOUR, 0, counted = true, imported = true)))
        assertEquals(fresh(log) { month() }, stats.month())
        assertEquals(log.plays.size, read.rows)

        // An import's Undo.
        read.rows = 0
        written.removeAll(listOf(log.plays.first { it.imported && it.startedAt == oct15 + 2 * HOUR }))
        assertEquals(fresh(log) { month() }, stats.month())
        assertEquals(log.plays.size, read.rows)
    }

    @Test
    fun `a month that is over is worked out once, and again only when a play in it or before it is written`() = runBlocking {
        val log = FakePlayLog(history)
        val stats = stats(log)
        val written = WatchedPlayLog(log, stats::changed)
        val october = YearMonth.of(2026, 10)
        val first = stats.recap(october, now, berlin)

        assertSame(first, stats.recap(october, now, berlin))
        // A play this month leaves October as it was.
        written.put(play(1, now - MINUTE))
        assertSame(first, stats.recap(october, now, berlin))
        // One in October (a play kept from then, closed late) does not.
        written.put(play(2, oct15 + 30 * MINUTE))
        val again = stats.recap(october, now, berlin)
        assertNotSame(first, again)
        assertEquals(fresh(log) { recap(october, now, berlin) }, again)
        assertEquals(first.totals?.plays?.plus(1), again.totals?.plays)
    }

    @Test
    fun `a book's play written reads the book log again from it alone, and the music log not at all`() = runBlocking {
        val log = FakePlayLog(history)
        val books = FakePlayLog(bookHistory)
        val read = Counted(log)
        val readBooks = Counted(books)
        val stats = stats(read, readBooks)
        val written = WatchedPlayLog(books, stats::booksChanged)
        val before = stats.month()

        read.rows = 0
        readBooks.rows = 0
        written.put(LoggedPlay(book, now - HOUR, 1_200_000, counted = false, clockMs = 1_000_000))
        val after = stats.month()

        assertEquals(0, read.rows)
        assertEquals(1, readBooks.rows)
        assertEquals(fresh(log, books) { month() }, after)
        assertEquals(before.spoken?.booksMs?.plus(1_000_000), after.spoken?.booksMs)
    }

    @Test
    fun `on 100,000 plays Insights are worked out in a second or two, and one more play costs a small part of that`() = runBlocking {
        val many = (1..3_000).map { song("Music/KJB test/Many $it.mp3", artist = "Made Up Band ${it % 40}", album = "Made Up Album ${it % 60}", genre = "Genre ${it % 9}", id = 1_000L + it, addedAt = it.toLong()) }
        val big = Library.of(many, Settings())
        val start = now - PLAYS * 20 * MINUTE
        val log = FakePlayLog(List(PLAYS) { i -> LoggedPlay(many[(i * 7_919) % many.size].ref, start + i * 20 * MINUTE, 150_000, counted = i % 5 != 0, zoneOffsetMinutes = 60, clockMs = 140_000) })
        val read = Counted(log)
        val stats = PlayStats(read, { big }, Dispatchers.Unconfined)
        val written = WatchedPlayLog(log, stats::changed)

        val cold = cpuMs {
            ListeningPeriod.entries.forEach { stats.insights(it, now, berlin, DayOfWeek.MONDAY) }
            stats.recaps(now, berlin)
        }
        written.put(LoggedPlay(many[7].ref, now - MINUTE, 150_000, counted = true, zoneOffsetMinutes = 60, clockMs = 140_000))
        read.rows = 0
        val warm = cpuMs { stats.month() }
        println("IN1 measure: Insights on $PLAYS plays, every period and the recaps from cold: $cold ms of CPU; this month again after one play: $warm ms")

        assertEquals(1, read.rows)
        assertTrue("from cold: $cold ms", cold < COLD_MS)
        assertTrue("after one play: $warm ms", warm < WARM_MS)
    }

    private val calendar = PhoneCalendar(berlin, DayOfWeek.MONDAY, setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY))

    @Test
    fun `a page is worked out once, and again once a play is written, a minute has gone, or the library changes`() = runBlocking {
        val log = FakePlayLog(history)
        val read = Counted(log)
        var shown = library
        val stats = PlayStats(read, { shown }, Dispatchers.Unconfined)
        val written = WatchedPlayLog(log, stats::changed)
        val first = stats.page(ListeningPeriod.MONTH, now, calendar)

        assertSame(first, stats.page(ListeningPeriod.MONTH, now + 30_000, calendar))
        assertNotSame(first, stats.page(ListeningPeriod.WEEK, now, calendar))
        // A minute later: Huddle, searches and lyrics may have changed, and "up to now" has moved.
        val later = stats.page(ListeningPeriod.MONTH, now + MINUTE + 1, calendar)
        assertNotSame(first, later)
        assertEquals(runBlocking { PlayStats(FakePlayLog(history), { library }, Dispatchers.Unconfined).page(ListeningPeriod.MONTH, now + MINUTE + 1, calendar) }, later)

        read.rows = 0
        written.put(play(4, now - 5 * MINUTE))
        val after = stats.page(ListeningPeriod.MONTH, now + MINUTE + 1, calendar)
        assertNotSame(later, after)
        assertEquals(1, read.rows)
        assertEquals(later.cards.time?.totals?.plays?.plus(1), after.cards.time?.totals?.plays)

        shown = Library.of(songs.drop(1), Settings())
        assertNotSame(after, stats.page(ListeningPeriod.MONTH, now + MINUTE + 1, calendar))
    }

    @Test
    fun `a month's story is worked out once, and again only when a play in it or before it is written`() = runBlocking {
        val log = FakePlayLog(history)
        val stats = stats(log)
        val written = WatchedPlayLog(log, stats::changed)
        val october = YearMonth.of(2026, 10)
        val first = stats.page(october, now, calendar)

        written.put(play(1, now - MINUTE))
        assertSame(first, stats.page(october, now + HOUR, calendar))
        written.put(play(2, oct15 + 30 * MINUTE))
        val again = stats.page(october, now, calendar)
        assertNotSame(first, again)
        assertEquals(runBlocking { stats(FakePlayLog(log.plays)).page(october, now, calendar) }, again)
    }

    @Test
    fun `a quick skip written after the page was read reaches How you listen, read alone`() = runBlocking {
        val started = history.map { if (it.imported) it else it.copy(start = PlayStart(StartedBy.AUTO, SourceKind.ALBUM, shuffled = false, songKey = null), end = PlayEnded(PlayEnd.FINISHED, 120_000)) }
        val log = FakePlayLog(started)
        val read = Counted(log)
        val stats = stats(read)
        val written = WatchedPlayLog(log, stats::changed)
        val before = stats.page(ListeningPeriod.MONTH, now, calendar)

        read.rows = 0
        written.put(LoggedPlay(songs[3].ref, now - 5 * MINUTE, 400, counted = false, start = PlayStart(StartedBy.AUTO, SourceKind.ALBUM, false, null), end = PlayEnded(PlayEnd.NEXT, 400)))
        val after = stats.page(ListeningPeriod.MONTH, now, calendar)

        assertEquals(1, read.rows)
        assertEquals(before.cards.how?.ends?.early?.plus(1), after.cards.how?.ends?.early)
        assertEquals(runBlocking { stats(FakePlayLog(log.plays)).page(ListeningPeriod.MONTH, now, calendar) }, after)
    }

    @Test
    fun `on 100,000 plays every page is worked out in a few seconds, and one more play costs a small part of that`() = runBlocking {
        val many = (1..3_000).map { song("Music/KJB test/Many $it.mp3", artist = "Made Up Band ${it % 40}", album = "Made Up Album ${it % 60}", genre = "Genre ${it % 9}", year = 1970 + it % 55, id = 1_000L + it, addedAt = it.toLong()) }
        val big = Library.of(many, Settings())
        val start = now - PLAYS * 20 * MINUTE
        val ways = listOf(PlayStart(StartedBy.PICKED, SourceKind.SONGS, false, null), PlayStart(StartedBy.AUTO, SourceKind.ALBUM, false, null), PlayStart(StartedBy.AUTO, SourceKind.SONGS, true, null))
        val log = FakePlayLog(
            List(PLAYS) { i ->
                LoggedPlay(many[(i * 7_919) % many.size].ref, start + i * 20 * MINUTE, 150_000, counted = i % 5 != 0, ways[i % 3], 60, PlayEnded(if (i % 4 == 0) PlayEnd.NEXT else PlayEnd.FINISHED, 20_000), clockMs = 140_000)
            },
        )
        val read = Counted(log)
        val stats = PlayStats(read, { big }, Dispatchers.Unconfined)
        val written = WatchedPlayLog(log, stats::changed)

        val cold = cpuMs {
            ListeningPeriod.entries.forEach { stats.page(it, now, calendar) }
            stats.page(YearMonth.of(2026, 10), now, calendar)
        }
        written.put(LoggedPlay(many[7].ref, now - MINUTE, 150_000, counted = true, zoneOffsetMinutes = 60, clockMs = 140_000))
        read.rows = 0
        val warm = cpuMs { stats.page(ListeningPeriod.MONTH, now, calendar) }
        println("IN2D measure: the Insights page on $PLAYS plays, every period and a month's story from cold: $cold ms of CPU; this month again after one play: $warm ms")

        assertEquals(1, read.rows)
        assertTrue("from cold: $cold ms", cold < PAGES_COLD_MS)
        assertTrue("after one play: $warm ms", warm < PAGE_WARM_MS)
    }

    @Test
    fun `the app's play log and book log tell Insights each play written`() {
        val graph = java.io.File("src/main/java/app/lyrify/stats/PlaysQuery.kt").readText()
        // plays.db's helper is one, shared with Huddle's history (HY1): changed on purpose from a helper of the play log's own.
        assertTrue(graph.contains("WatchedPlayLog(SqlPlayLog(db(context), log = log)) { startedAt -> stats?.changed(startedAt) }"))
        assertTrue(graph.contains("fun db(context: Context): PlaysDb = db ?: PlaysDb(context.applicationContext, log = { Log.w(LOG_TAG, it) }).also { db = it }"))
        assertTrue(graph.contains("WatchedPlayLog(SqlPlayLog(PlaysDb(context.applicationContext, name = BOOK_PLAYS, log = log), log = log)) { startedAt -> stats?.booksChanged(startedAt) }"))
        // Seen at once by the threads that write.
        assertTrue(graph.contains("@Volatile\n    private var stats: PlayStats? = null"))
    }

    private companion object {
        const val MINUTE: Long = 60_000
        const val HOUR: Long = 3_600_000
        const val DAY: Long = 86_400_000
        const val PLAYS: Int = 100_000

        /**
         * CPU time ([cpuMs]). Measured on the laptop (2026-11-22, in1's report): 488 to 544 ms from cold and 52 to 60 ms
         * after one play; the budgets leave room for a slower machine, and other builds running at once no longer count.
         */
        const val COLD_MS: Long = 3_000
        const val WARM_MS: Long = 400

        /**
         * The same for the Insights page (in2d), every card. Measured on the laptop (2026-11-23, in2d's report): 2,211 ms
         * for the four periods and a month's story from cold, 268 ms for this month after one play.
         */
        const val PAGES_COLD_MS: Long = 8_000
        const val PAGE_WARM_MS: Long = 1_200
    }
}
