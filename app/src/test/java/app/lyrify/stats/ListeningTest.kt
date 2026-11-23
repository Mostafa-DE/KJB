package app.lyrify.stats

import app.lyrify.library.Library
import app.lyrify.library.Song
import app.lyrify.library.SongRef
import app.lyrify.library.VOLUME
import app.lyrify.library.albumKey
import app.lyrify.library.notPlayed
import app.lyrify.library.recentlyAdded
import app.lyrify.library.song
import app.lyrify.settings.LibrarySettings
import app.lyrify.settings.ListPeriod
import app.lyrify.settings.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** Your listening, Most played, Recently played and Song info's plays, from the log and the library. Made-up names only. */
class ListeningTest {
    private val berlin = ZoneId.of("Europe/Berlin")

    private val alder = song("Music/Harbour Glass/01 Alder Smoke.mp3", album = "Harbour Glass", id = 1)
    private val birch = song("Music/Harbour Glass/02 Birch Window.mp3", album = "Harbour Glass", id = 2)
    private val ember = song("Music/Night Ferry/Ember Ledger.mp3", artist = "Wren Halloran", album = "Night Ferry", id = 3)
    private val salt = song("Music/Loose/Salt Lattice.mp3", artist = "Wren Halloran", id = 4)
    private val library = Library.of(listOf(alder, birch, ember, salt), Settings())

    private fun at(text: String): Long = ZonedDateTime.parse(text).toInstant().toEpochMilli()

    private fun play(song: Song, startedAt: Long, heardMs: Long = 120_000, counted: Boolean = true) = LoggedPlay(song.ref, startedAt, heardMs, counted)

    private fun stats(vararg plays: LoggedPlay, library: Library? = this.library) = PlayStats(FakePlayLog(plays.toList()), { library }, Dispatchers.Unconfined)

    // Periods, in the phone's time

    private fun week(now: Long, zone: ZoneId = berlin, first: DayOfWeek = DayOfWeek.MONDAY) = periodOf(ListeningPeriod.WEEK, now, zone, first)

    @Test
    fun `the week runs from midnight of the first day of the phone's week to the next one`() {
        val now = at("2026-11-11T15:00+01:00[Europe/Berlin]")
        assertEquals(Period(at("2026-11-09T00:00+01:00[Europe/Berlin]"), at("2026-11-16T00:00+01:00[Europe/Berlin]")), week(now))
        assertEquals(Period(at("2026-11-08T00:00+01:00[Europe/Berlin]"), at("2026-11-15T00:00+01:00[Europe/Berlin]")), week(now, first = DayOfWeek.SUNDAY))
        assertEquals(at("2026-11-09T00:00+01:00[Europe/Berlin]"), week(at("2026-11-09T00:30+01:00[Europe/Berlin]"))?.from)
    }

    @Test
    fun `month and year run from their first day at local midnight to the next, and all time has no bounds`() {
        val now = at("2026-11-11T15:00+01:00[Europe/Berlin]")
        assertEquals(Period(at("2026-11-01T00:00+01:00[Europe/Berlin]"), at("2026-12-01T00:00+01:00[Europe/Berlin]")), periodOf(ListeningPeriod.MONTH, now, berlin, DayOfWeek.MONDAY))
        assertEquals(Period(at("2026-01-01T00:00+01:00[Europe/Berlin]"), at("2027-01-01T00:00+01:00[Europe/Berlin]")), periodOf(ListeningPeriod.YEAR, now, berlin, DayOfWeek.MONDAY))
        assertNull(periodOf(ListeningPeriod.ALL_TIME, now, berlin, DayOfWeek.MONDAY))
    }

    @Test
    fun `the week that holds the clock change runs midnight to midnight, 167 hours`() {
        // Clocks went forward at 02:00 on Sunday 29 March 2026.
        val period = week(at("2026-03-29T12:00+02:00[Europe/Berlin]"))
        assertEquals(Period(Instant.parse("2026-03-22T23:00:00Z").toEpochMilli(), Instant.parse("2026-03-29T22:00:00Z").toEpochMilli()), period)
        assertEquals(167 * 3_600_000L, period!!.until - period.from)
    }

    @Test
    fun `a week whose first day has no midnight starts at that day's first hour`() {
        // São Paulo's clocks jumped from midnight to 01:00 on Sunday 4 November 2018.
        val saoPaulo = ZoneId.of("America/Sao_Paulo")
        val period = week(at("2018-11-07T12:00-02:00[America/Sao_Paulo]"), saoPaulo, DayOfWeek.SUNDAY)
        assertEquals(Period(Instant.parse("2018-11-04T03:00:00Z").toEpochMilli(), Instant.parse("2018-11-11T02:00:00Z").toEpochMilli()), period)
    }

    @Test
    fun `a period read a minute before midnight turns at that midnight`() {
        val sunday = at("2026-11-15T23:59+01:00[Europe/Berlin]")
        val day = dayOf(sunday, berlin)

        assertEquals(Day(LocalDate.of(2026, 11, 15), at("2026-11-16T00:00+01:00[Europe/Berlin]")), day)
        assertEquals(MINUTE, day.nextStart - sunday)
        assertEquals(week(sunday)!!.until, day.nextStart)
        assertEquals(day.nextStart, week(day.nextStart)!!.from)
        assertEquals(LocalDate.of(2026, 11, 16), dayOf(day.nextStart, berlin).date)
        // The last minute of the year: Month and Year turn at the same midnight.
        val lastMinute = at("2026-12-31T23:59+01:00[Europe/Berlin]")
        listOf(ListeningPeriod.MONTH, ListeningPeriod.YEAR).forEach { period ->
            assertEquals(period.name, periodOf(period, lastMinute, berlin, DayOfWeek.MONDAY)!!.until, dayOf(lastMinute, berlin).nextStart)
        }
    }

    @Test
    fun `on a clock change day the page turns at the next day's first moment, not 24 hours on`() {
        // Berlin's clocks went forward at 02:00 on Sunday 29 March 2026 (a 23 hour day) and back on 25 October (25 hours).
        val spring = at("2026-03-29T00:00:30+01:00[Europe/Berlin]")
        assertEquals(at("2026-03-30T00:00+02:00[Europe/Berlin]"), dayOf(spring, berlin).nextStart)
        assertEquals(23 * HOUR - 30_000, dayOf(spring, berlin).nextStart - spring)
        val autumn = at("2026-10-25T00:00+02:00[Europe/Berlin]")
        assertEquals(25 * HOUR, dayOf(autumn, berlin).nextStart - autumn)
        val saturday = at("2026-03-28T23:59+01:00[Europe/Berlin]")
        assertEquals(MINUTE, dayOf(saturday, berlin).nextStart - saturday)

        // São Paulo's clocks jumped from midnight to 01:00 on Sunday 4 November 2018: Saturday 23:59 turns a minute later, at 01:00, into a new week.
        val saoPaulo = ZoneId.of("America/Sao_Paulo")
        val night = at("2018-11-03T23:59-03:00[America/Sao_Paulo]")
        val turn = dayOf(night, saoPaulo)
        assertEquals(Day(LocalDate.of(2018, 11, 3), Instant.parse("2018-11-04T03:00:00Z").toEpochMilli()), turn)
        assertEquals(MINUTE, turn.nextStart - night)
        assertEquals(week(night, saoPaulo, DayOfWeek.SUNDAY)!!.until, turn.nextStart)
        assertEquals(turn.nextStart, week(turn.nextStart, saoPaulo, DayOfWeek.SUNDAY)!!.from)
        assertEquals(LocalDate.of(2018, 11, 4), dayOf(turn.nextStart, saoPaulo).date)
    }

    @Test
    fun `the same moment falls in each phone's own week`() {
        val now = Instant.parse("2026-11-09T02:00:00Z").toEpochMilli()
        assertEquals(Instant.parse("2026-11-08T23:00:00Z").toEpochMilli(), week(now)?.from)
        assertEquals(Instant.parse("2026-11-02T08:00:00Z").toEpochMilli(), week(now, ZoneId.of("America/Los_Angeles"))?.from)
    }

    // Changed on purpose (IN1): Your listening's figures are Insights' now (PlayStats.insights), each play told by its time heard.
    @Test
    fun `last week's figures leave out this week's plays, and so do last month's and last year's`() = runBlocking {
        val stats = stats(
            play(alder, at("2025-12-31T23:59+01:00[Europe/Berlin]"), heardMs = 100_000),
            play(birch, at("2026-10-31T23:59+01:00[Europe/Berlin]"), heardMs = 110_000),
            play(ember, at("2026-11-08T23:59+01:00[Europe/Berlin]"), heardMs = 130_000),
            play(salt, at("2026-11-09T00:00+01:00[Europe/Berlin]"), heardMs = 170_000),
        )
        fun heard(period: ListeningPeriod, now: String) = runBlocking { stats.insights(period, at(now), berlin, DayOfWeek.MONDAY).totals?.heardMs }

        assertEquals(130_000L, heard(ListeningPeriod.WEEK, "2026-11-05T15:00+01:00[Europe/Berlin]"))
        assertEquals(110_000L, heard(ListeningPeriod.MONTH, "2026-10-15T15:00+02:00[Europe/Berlin]"))
        assertEquals(100_000L, heard(ListeningPeriod.YEAR, "2025-06-15T15:00+02:00[Europe/Berlin]"))
    }

    @Test
    fun `a period holds the plays that started in it, in local time`() = runBlocking {
        val now = at("2026-11-11T15:00+01:00[Europe/Berlin]")
        val stats = stats(
            play(alder, at("2026-11-08T23:59+01:00[Europe/Berlin]")),
            play(alder, at("2026-11-09T00:00+01:00[Europe/Berlin]")),
            play(birch, at("2026-11-10T09:00+01:00[Europe/Berlin]"), heardMs = 15_000, counted = false),
            play(birch, at("2026-10-31T23:30+01:00[Europe/Berlin]")),
            play(ember, at("2025-12-31T23:30+01:00[Europe/Berlin]")),
        )

        // Alder's play, and Birch heard without counting: one song played.
        assertEquals(Totals(heardMs = 135_000, plays = 1, songs = 1, artists = 1, albums = 1), stats.insights(ListeningPeriod.WEEK, now, berlin, DayOfWeek.MONDAY).totals)

        assertEquals(2, stats.insights(ListeningPeriod.MONTH, now, berlin, DayOfWeek.MONDAY).totals?.plays)
        assertEquals(3, stats.insights(ListeningPeriod.YEAR, now, berlin, DayOfWeek.MONDAY).totals?.plays)
        assertEquals(4, stats.insights(ListeningPeriod.ALL_TIME, now, berlin, DayOfWeek.MONDAY).totals?.plays)
    }

    // Top lists

    @Test
    fun `top songs go by plays, then the latest played, then title, then path`() {
        val twin = song("Music/Other/01 Alder Smoke.mp3", artist = "Wren Halloran", id = 9)
        val library = Library.of(listOf(alder, birch, ember, salt, twin), Settings())
        val totals = listOf(
            SongPlays(salt.ref, 3, 100, 0),
            SongPlays(birch.ref, 2, 900, 0),
            SongPlays(alder.ref, 2, 500, 0),
            SongPlays(ember.ref, 2, 900, 0),
            SongPlays(twin.ref, 2, 500, 0),
        )

        assertEquals(listOf(salt, birch, ember, alder, twin), listeningOf(totals, library).songs.map { it.item })
        assertEquals(listOf(salt, birch, ember, alder, twin), listeningOf(totals.reversed(), library).songs.map { it.item })
    }

    @Test
    fun `top artists and albums add up their songs under today's tags, ties going by the latest played then name`() {
        val totals = listOf(
            SongPlays(alder.ref, 2, 300, 60_000),
            SongPlays(birch.ref, 1, 200, 30_000),
            SongPlays(ember.ref, 2, 100, 50_000),
            SongPlays(salt.ref, 1, 400, 20_000),
        )
        val listening = listeningOf(totals, library)

        assertEquals(listOf(Top("Wren Halloran", 3, 70_000, 400), Top("Kit Calloway", 3, 90_000, 300)), listening.artists.map { Top(it.item.name, it.plays, it.heardMs, it.lastPlayedAt) })
        assertEquals(listOf(albumKey(alder), albumKey(ember)), listening.albums.map { it.item.key })
        assertEquals(listOf(3, 2), listening.albums.map { it.plays })

        // Retagged: the same file now under another artist moves its plays there.
        val retagged = Library.of(listOf(alder.copy(artist = "Wren Halloran"), birch, ember, salt), Settings())
        assertEquals(listOf("Wren Halloran" to 5, "Kit Calloway" to 1), listeningOf(totals, retagged).artists.map { it.item.name to it.plays })
    }

    @Test
    fun `a song with several artists counts for each of them in the top artists`() {
        val duet = song("Music/Duets/Lamp Oil.mp3", artist = "Wren Halloran feat. Kit Calloway", id = 5)
        val library = Library.of(listOf(alder, ember, duet), Settings())
        val totals = listOf(SongPlays(alder.ref, 1, 100, 10_000), SongPlays(ember.ref, 1, 400, 20_000), SongPlays(duet.ref, 2, 300, 30_000))

        assertEquals(listOf("Wren Halloran" to 3, "Kit Calloway" to 3), listeningOf(totals, library).artists.map { it.item.name to it.plays })
    }

    @Test
    fun `a song moved or renamed keeps its plays through its id, one gone leaves the top lists but not the totals, and one hidden leaves both`() {
        val moved = alder.copy(ref = SongRef(VOLUME + "Music/Harbour Glass/Alder Smoke (live).mp3", 1))
        val hidden = Library.of(listOf(moved, birch, ember, salt), Settings(library = LibrarySettings(foldersToSkip = listOf("Music/Night Ferry"))))
        val totals = listOf(
            SongPlays(alder.ref, 2, 300, 60_000),
            SongPlays(moved.ref, 1, 400, 30_000),
            SongPlays(ember.ref, 4, 200, 90_000),
            SongPlays(SongRef(VOLUME + "Music/Gone/Tin Roof.mp3", 77), 5, 100, 100_000),
        )
        val listening = listeningOf(totals, hidden)

        assertEquals(listOf(Top(moved, 3, 90_000, 400)), listening.songs)
        // The library's filters (Folders to skip here, Hidden songs too) take a song out of the headline as out of the
        // lists below it (review F2, finding 8); a song gone from the phone stays in it, as its listening happened.
        assertEquals(8, listening.plays)
        assertEquals(190_000, listening.heardMs)
    }

    @Test
    fun `songs only heard, never counted, add listening time but no rows`() {
        val listening = listeningOf(listOf(SongPlays(alder.ref, 0, null, 40_000)), library)
        assertEquals(Listening(40_000, 0, emptyList(), emptyList(), emptyList()), listening)
    }

    @Test
    fun `with no plays the page is empty, and without a library only the totals are known`() = runBlocking {
        val now = at("2026-11-11T15:00+01:00[Europe/Berlin]")
        assertEquals(Insights(totals = null), stats().insights(ListeningPeriod.ALL_TIME, now, berlin, DayOfWeek.MONDAY))

        val noLibrary = stats(play(alder, now - 1_000), library = null).insights(ListeningPeriod.ALL_TIME, now, berlin, DayOfWeek.MONDAY)
        assertEquals(Insights(Totals(120_000, 1, 0, 0, 0)), noLibrary)
    }

    // What the library asks

    @Test
    fun `Most played is the top 50 songs of all time in the library, under their paths now`() = runBlocking {
        val songs = (1..60).map { song("Music/Many/Song $it.mp3", id = 100L + it) }
        val library = Library.of(songs + alder, Settings())
        val movedAway = SongRef(VOLUME + "Music/Old place/01 Alder Smoke.mp3", 1)
        val gone = SongRef(VOLUME + "Music/Gone/Tin Roof.mp3")
        val plays = songs.flatMapIndexed { i, song -> List(i + 1) { lap -> play(song, T0 + i * 1_000L + lap * 100_000L) } } +
            List(100) { LoggedPlay(movedAway, T0 + 10_000_000L + it, 120_000, counted = true) } +
            List(200) { LoggedPlay(gone, T0 + 20_000_000L + it, 120_000, counted = true) }
        val stats = PlayStats(FakePlayLog(plays), { library }, Dispatchers.Unconfined)

        val most = stats.mostPlayed(50)
        assertEquals(listOf(alder.ref) + songs.takeLast(49).reversed().map { it.ref }, most.map { it.song })
        assertEquals(listOf(100) + (60 downTo 12).toList(), most.map { it.count })
    }

    @Test
    fun `Recently played is the songs that last counted a play, the latest first`() = runBlocking {
        val stats = stats(
            play(alder, T0),
            play(birch, T0 + 1_000),
            play(alder, T0 + 2_000),
            play(ember, T0 + 3_000, counted = false),
            LoggedPlay(SongRef(VOLUME + "Music/Gone/Tin Roof.mp3"), T0 + 4_000, 120_000, counted = true),
        )

        assertEquals(listOf(alder.ref to T0 + 2_000, birch.ref to T0 + 1_000), stats.recentlyPlayed(50).map { it.song to it.lastPlayedAt })
        assertEquals(listOf(alder.ref), stats.recentlyPlayed(1).map { it.song })
    }

    @Test
    fun `Song info has a song's plays, its last and its time heard, or nothing until one counted`() = runBlocking {
        val stats = stats(
            play(alder, T0, heardMs = 100_000),
            play(alder, T0 + 500_000, heardMs = 150_000),
            play(alder, T0 + 900_000, heardMs = 20_000, counted = false),
            play(birch, T0, heardMs = 20_000, counted = false),
        )

        assertEquals(SongPlays(alder.ref, 2, T0 + 500_000, 270_000), stats.plays(alder.ref))
        assertNull(stats.plays(birch.ref))
        assertNull(stats.plays(ember.ref))
    }

    // The short plays (skips) the log keeps since it keeps how plays end: each reader reads as it did without them.

    /** Plays of every kind 3.1.0 logged: counted, heard a while, an import's, in this week and before. */
    private val logged: List<LoggedPlay> = listOf(
        play(alder, at("2026-11-02T10:00+01:00[Europe/Berlin]")),
        play(alder, at("2026-11-10T10:00+01:00[Europe/Berlin]")),
        play(birch, at("2026-11-10T11:00+01:00[Europe/Berlin]"), heardMs = 40_000, counted = false),
        play(ember, at("2026-11-11T09:00+01:00[Europe/Berlin]")),
        LoggedPlay(ember.ref, at("2026-10-01T09:00+02:00[Europe/Berlin]"), 0, counted = true, imported = true),
    )

    /** Skips: one the latest play of its song, one of a song only ever skipped, one of a song never counted. */
    private val short: List<LoggedPlay> = listOf(
        LoggedPlay(SongRef(alder.path, 77), at("2026-11-11T12:00+01:00[Europe/Berlin]"), 600, counted = false, end = PlayEnded(PlayEnd.NEXT, 600)),
        LoggedPlay(salt.ref, at("2026-11-11T12:01+01:00[Europe/Berlin]"), 0, counted = false, end = PlayEnded(PlayEnd.JUMP, 0)),
        LoggedPlay(birch.ref, at("2026-11-11T12:02+01:00[Europe/Berlin]"), 999, counted = false, end = PlayEnded(PlayEnd.BACK, 999)),
    )

    private val today: Long = at("2026-11-11T15:00+01:00[Europe/Berlin]")

    /**
     * [check] on `plays.db`'s own SQL holding [logged] alone, then [logged] and [short]: the two must read the same. What
     * they read, so each test also checks it is not nothing.
     */
    private fun <T> sameWithShort(check: suspend (PlayStats) -> T): T = runBlocking {
        JdbcPlayLog().use { before ->
            JdbcPlayLog().use { now ->
                before.putAll(logged)
                now.putAll(logged + short)
                check(PlayStats(before, { library }, Dispatchers.Unconfined)).also { assertEquals(it, check(PlayStats(now, { library }, Dispatchers.Unconfined))) }
            }
        }
    }

    @Test
    fun `the short plays change nothing in Most played`() {
        assertEquals(listOf(ember.ref to 2, alder.ref to 2), sameWithShort { it.mostPlayed() }.map { it.song to it.count })
    }

    @Test
    fun `the short plays change nothing in Recently played`() {
        assertEquals(listOf(ember.ref, alder.ref), sameWithShort { it.recentlyPlayed() }.map { it.song })
    }

    @Test
    fun `the short plays change no song's plays line in Song info`() {
        val lines = sameWithShort { stats -> listOf(alder, birch, ember, salt).map { stats.plays(it.ref)?.count } }
        assertEquals(listOf(2, null, 2, null), lines)
    }

    @Test
    fun `the short plays change nothing the lists sorted by plays go by`() {
        assertEquals(setOf(alder.path, birch.path, ember.path), sameWithShort { it.everySong() }.keys)
    }

    @Test
    fun `the short plays change nothing in Insights, any period`() {
        val periods = sameWithShort { stats -> ListeningPeriod.entries.map { stats.insights(it, today, berlin, DayOfWeek.MONDAY).totals } }
        // All time: alder twice, ember once; birch only heard. Changed on purpose (IN1): ember's imported play no longer
        // counts, as Insights leave an import's plays out.
        assertEquals(3, periods.last()?.plays)
        assertEquals(3 * 120_000L + 40_000L, periods.last()?.heardMs)
        // This week: alder and birch on the 10th, ember on the 11th.
        assertEquals(2, periods.first()?.plays)
    }

    // The period chips of Recently added, Recently played and Most played (RP1): the days up to today, in the phone's time

    @Test
    fun `a period of days runs from the first moment of its earliest day, today one of them, in the phone's calendar`() {
        val now = at("2026-11-22T15:00+01:00[Europe/Berlin]")
        assertEquals(at("2026-11-08T00:00+01:00[Europe/Berlin]"), sinceOf(ListPeriod.DAYS_15, now, berlin))
        // Across the clock change of 25 October: midnight in summer time.
        assertEquals(at("2026-10-24T00:00+02:00[Europe/Berlin]"), sinceOf(ListPeriod.DAYS_30, now, berlin))
        assertEquals(at("2026-09-24T00:00+02:00[Europe/Berlin]"), sinceOf(ListPeriod.DAYS_60, now, berlin))
        assertEquals(at("2025-11-23T00:00+01:00[Europe/Berlin]"), sinceOf(ListPeriod.DAYS_365, now, berlin))
        assertNull(sinceOf(ListPeriod.ALL_TIME, now, berlin))
        // At the first moment of today, the same days.
        assertEquals(at("2026-11-08T00:00+01:00[Europe/Berlin]"), sinceOf(ListPeriod.DAYS_15, at("2026-11-22T00:00+01:00[Europe/Berlin]"), berlin))
    }

    @Test
    fun `a period's days are the phone's, so half an hour after its midnight the days have moved on`() {
        // 00:30 on the 22nd in Berlin, still 23:30 on the 21st in UTC.
        val now = Instant.parse("2026-11-21T23:30:00Z").toEpochMilli()
        assertEquals(at("2026-11-08T00:00+01:00[Europe/Berlin]"), sinceOf(ListPeriod.DAYS_15, now, berlin))
        assertEquals(Instant.parse("2026-11-07T00:00:00Z").toEpochMilli(), sinceOf(ListPeriod.DAYS_15, now, ZoneId.of("UTC")))
    }

    @Test
    fun `a period whose first day has no midnight starts at that day's first hour`() {
        // São Paulo's clocks jumped from midnight to 01:00 on Sunday 4 November 2018.
        val saoPaulo = ZoneId.of("America/Sao_Paulo")
        assertEquals(Instant.parse("2018-11-04T03:00:00Z").toEpochMilli(), sinceOf(ListPeriod.DAYS_15, at("2018-11-18T12:00-02:00[America/Sao_Paulo]"), saoPaulo))
    }

    /** The past 15 days as of [lateToday]: from midnight on 8 November 2026 in Berlin. */
    private val lateToday: Long = at("2026-11-22T15:00+01:00[Europe/Berlin]")
    private val fifteenDays: Long = at("2026-11-08T00:00+01:00[Europe/Berlin]")

    @Test
    fun `Most played over a period counts the plays that counted in it, the most first, and all time counts as it always did`() = runBlocking {
        val stats = stats(
            play(alder, fifteenDays - 1),
            play(alder, fifteenDays - 2),
            play(alder, fifteenDays - 3),
            play(birch, fifteenDays),
            play(birch, fifteenDays + HOUR),
            play(ember, lateToday - HOUR),
            // Heard a while and never counted ("Count a play after" not reached), and a skip.
            play(ember, lateToday - 2 * HOUR, heardMs = 20_000, counted = false),
            LoggedPlay(ember.ref, lateToday - 3 * HOUR, 300, counted = false, end = PlayEnded(PlayEnd.NEXT, 300)),
            // An import's play, dated in the period by the app it came from.
            LoggedPlay(salt.ref, lateToday - 4 * HOUR, 0, counted = true, imported = true),
        )

        assertEquals(listOf(birch.ref to 2, ember.ref to 1), stats.mostPlayed(50, fifteenDays).map { it.song to it.count })
        assertEquals(listOf(birch.ref), stats.mostPlayed(1, fifteenDays).map { it.song })
        // All time: the log's totals, an import's plays too, as before the chips.
        assertEquals(listOf(alder.ref to 3, birch.ref to 2, ember.ref to 1, salt.ref to 1), stats.mostPlayed(50).map { it.song to it.count })
    }

    @Test
    fun `Recently played over a period has the songs with a play that counted in it, by that play, the latest first`() = runBlocking {
        val stats = stats(
            play(alder, fifteenDays - 1),
            // In the period, never counted: alder is not played in it.
            play(alder, fifteenDays + HOUR, heardMs = 20_000, counted = false),
            play(birch, fifteenDays),
            play(ember, lateToday - HOUR),
            LoggedPlay(salt.ref, lateToday - 2 * HOUR, 0, counted = true, imported = true),
        )

        assertEquals(listOf(ember.ref to lateToday - HOUR, birch.ref to fifteenDays), stats.recentlyPlayed(500, fifteenDays).map { it.song to it.lastPlayedAt })
        assertEquals(listOf(ember.ref, salt.ref, birch.ref, alder.ref), stats.recentlyPlayed(500).map { it.song })
    }

    @Test
    fun `a period's lists leave out books and hidden songs, as all time does`() = runBlocking {
        val part = song("Music/Rips/Lantern Keeper/01.mp3", album = "Lantern Keeper", genre = "Audiobook", id = 9)
        val hiding = Library.of(listOf(alder, birch, ember, salt, part), Settings(library = LibrarySettings(hiddenSongs = listOf(birch.ref))))
        val stats = stats(play(alder, lateToday - HOUR), play(birch, lateToday - 2 * HOUR), play(part, lateToday - 3 * HOUR), library = hiding)

        assertEquals(listOf(alder.ref), stats.mostPlayed(50, fifteenDays).map { it.song })
        assertEquals(listOf(alder.ref), stats.recentlyPlayed(500, fifteenDays).map { it.song })
    }

    @Test
    fun `Most played and Recently played leave out podcast episodes, as Insights does, in a period and over all time`() = runBlocking {
        val episode = song("Music/Talks/Episode 7.mp3", isPodcast = true, id = 60)
        val withTalks = Library.of(listOf(alder, birch, episode), Settings())
        val stats = stats(play(alder, lateToday - HOUR), play(episode, lateToday - 2 * HOUR), play(episode, lateToday - 3 * HOUR), library = withTalks)

        assertEquals(listOf(alder.ref), stats.mostPlayed(50, fifteenDays).map { it.song })
        assertEquals(listOf(alder.ref), stats.recentlyPlayed(500, fifteenDays).map { it.song })
        assertEquals(listOf(alder.ref), stats.mostPlayed(50).map { it.song })
        assertEquals(listOf(alder.ref), stats.recentlyPlayed(500).map { it.song })
        // Recently added keeps them: an episode new on the phone is news.
        assertEquals(setOf(alder.path, birch.path, episode.path), recentlyAdded(withTalks, null).map { it.path }.toSet())
    }

    @Test
    fun `Not played leaves out exactly the songs Most played counts a play of, in a period and over all time`() = runBlocking {
        val episode = song("Music/Talks/Episode 7.mp3", isPodcast = true, id = 60)
        val withTalks = Library.of(listOf(alder, birch, ember, salt, episode), Settings())
        val stats = stats(
            play(alder, fifteenDays - HOUR),
            play(birch, fifteenDays + HOUR),
            // Heard a while and never counted, then a skip: ember was never played.
            play(ember, lateToday - HOUR, heardMs = 20_000, counted = false),
            LoggedPlay(ember.ref, lateToday - 2 * HOUR, 300, counted = false, end = PlayEnded(PlayEnd.NEXT, 300)),
            // An import's play dated in the period: Most played counts it over all time, not in a period.
            LoggedPlay(salt.ref, lateToday - 3 * HOUR, 0, counted = true, imported = true),
            library = withTalks,
        )

        // The past 15 days: ember never played, then alder (played before them), then salt (its import's play).
        assertEquals(listOf(ember, alder, salt), notPlayed(stats, withTalks, fifteenDays).map { it.first })
        assertEquals(listOf(null, fifteenDays - HOUR, lateToday - 3 * HOUR), notPlayed(stats, withTalks, fifteenDays).map { it.second?.lastPlayedAt })
        // All time: only ember. The podcast episode is never in it, as it is never in Most played.
        assertEquals(listOf(ember), notPlayed(stats, withTalks, null).map { it.first })
    }

    @Test
    fun `a period's lists read Insights' copy of the log, and a play written since is in the next read`() = runBlocking {
        val log = FakePlayLog(listOf(play(alder, lateToday - 2 * HOUR)))
        val stats = PlayStats(log, { library }, Dispatchers.Unconfined)
        assertEquals(listOf(alder.ref), stats.recentlyPlayed(500, fifteenDays).map { it.song })

        log.put(play(birch, lateToday - HOUR))
        // The copy is not read again until the log says a play was written (plays.db does, WatchedPlayLog).
        assertEquals(listOf(alder.ref), stats.recentlyPlayed(500, fifteenDays).map { it.song })
        stats.changed(lateToday - HOUR)
        assertEquals(listOf(birch.ref, alder.ref), stats.recentlyPlayed(500, fifteenDays).map { it.song })
        assertEquals(listOf(birch.ref to 1, alder.ref to 1), stats.mostPlayed(50, fifteenDays).map { it.song to it.count })
    }

    // Last played

    @Test
    fun `last played reads today with its time, yesterday, some days ago, then the date`() {
        val now = at("2026-11-11T15:00+01:00[Europe/Berlin]")
        assertEquals(WhenPlayed.Never, whenPlayed(null, now, berlin))
        assertEquals(WhenPlayed.Today(at("2026-11-11T00:05+01:00[Europe/Berlin]")), whenPlayed(at("2026-11-11T00:05+01:00[Europe/Berlin]"), now, berlin))
        assertEquals(WhenPlayed.Yesterday, whenPlayed(at("2026-11-10T23:55+01:00[Europe/Berlin]"), now, berlin))
        assertEquals(WhenPlayed.DaysAgo(2), whenPlayed(at("2026-11-09T08:00+01:00[Europe/Berlin]"), now, berlin))
        assertEquals(WhenPlayed.DaysAgo(6), whenPlayed(at("2026-11-05T08:00+01:00[Europe/Berlin]"), now, berlin))
        assertEquals(WhenPlayed.OnDate(at("2026-11-04T23:00+01:00[Europe/Berlin]")), whenPlayed(at("2026-11-04T23:00+01:00[Europe/Berlin]"), now, berlin))
        // Played "later" than now (the clock was changed): today.
        assertEquals(WhenPlayed.Today(now + 60_000), whenPlayed(now + 60_000, now, berlin))
    }

    @Test
    fun `yesterday is the phone's yesterday`() {
        // 00:30 on the 11th in Berlin, still 23:30 on the 10th in UTC.
        val now = Instant.parse("2026-11-10T23:30:00Z").toEpochMilli()
        val played = Instant.parse("2026-11-10T22:30:00Z").toEpochMilli()
        assertEquals(WhenPlayed.Yesterday, whenPlayed(played, now, berlin))
        assertEquals(WhenPlayed.Today(played), whenPlayed(played, now, ZoneId.of("UTC")))
    }

    private companion object {
        const val T0: Long = 1_800_000_000_000L
        const val MINUTE: Long = 60_000
        const val HOUR: Long = 3_600_000
    }

    @Test
    fun `plays of a file that is a book now leave every figure and list, and come back if it is music again`() = runBlocking {
        val part = song("Music/Rips/Lantern Keeper/01.mp3", album = "Lantern Keeper", genre = "Audiobook", id = 9)
        val plays = arrayOf(play(alder, 1_000), play(part, 2_000), play(part, 3_000))
        val withBook = Library.of(listOf(alder, birch, ember, salt, part), Settings())
        val listening = listeningOf(plays.map { SongPlays(it.song, 1, it.startedAt, it.heardMs) }, withBook)
        assertEquals(1, listening.plays)
        assertEquals(120_000L, listening.heardMs)
        assertEquals(listOf(alder), listening.songs.map { it.item })
        val stats = stats(*plays, library = withBook)
        assertEquals(listOf(alder.ref), stats.mostPlayed().map { it.song })
        assertEquals(listOf(alder.ref), stats.recentlyPlayed().map { it.song })
        assertNull(stats.plays(part.ref))
        // "Not a book": its plays are back.
        val music = Library.of(listOf(alder, birch, ember, salt, part), Settings(), app.lyrify.books.BookMarks(notSongs = setOf(part.path)))
        assertEquals(3, listeningOf(plays.map { SongPlays(it.song, 1, it.startedAt, it.heardMs) }, music).plays)
        assertEquals(2, stats(*plays, library = music).plays(part.ref)?.count)
    }
}
