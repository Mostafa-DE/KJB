package app.lyrify.stats

import app.lyrify.library.Library
import app.lyrify.library.Song
import app.lyrify.library.SongRef
import app.lyrify.library.VOLUME
import app.lyrify.library.albumKey
import app.lyrify.library.song
import app.lyrify.settings.LibrarySettings
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

    @Test
    fun `last week's figures leave out this week's plays, and so do last month's and last year's`() = runBlocking {
        val stats = stats(
            play(alder, at("2025-12-31T23:59+01:00[Europe/Berlin]")),
            play(birch, at("2026-10-31T23:59+01:00[Europe/Berlin]")),
            play(ember, at("2026-11-08T23:59+01:00[Europe/Berlin]")),
            play(salt, at("2026-11-09T00:00+01:00[Europe/Berlin]")),
        )
        fun songs(period: ListeningPeriod, now: String) = runBlocking { stats.listening(period, at(now), berlin, DayOfWeek.MONDAY).songs.map { it.item } }

        assertEquals(listOf(ember), songs(ListeningPeriod.WEEK, "2026-11-05T15:00+01:00[Europe/Berlin]"))
        assertEquals(listOf(birch), songs(ListeningPeriod.MONTH, "2026-10-15T15:00+02:00[Europe/Berlin]"))
        assertEquals(listOf(alder), songs(ListeningPeriod.YEAR, "2025-06-15T15:00+02:00[Europe/Berlin]"))
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

        val week = stats.listening(ListeningPeriod.WEEK, now, berlin, DayOfWeek.MONDAY)
        assertEquals(1, week.plays)
        assertEquals(135_000, week.heardMs)
        assertEquals(listOf(alder), week.songs.map { it.item })

        assertEquals(2, stats.listening(ListeningPeriod.MONTH, now, berlin, DayOfWeek.MONDAY).plays)
        assertEquals(3, stats.listening(ListeningPeriod.YEAR, now, berlin, DayOfWeek.MONDAY).plays)
        assertEquals(4, stats.listening(ListeningPeriod.ALL_TIME, now, berlin, DayOfWeek.MONDAY).plays)
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
        assertEquals(Listening(0, 0, emptyList(), emptyList(), emptyList()), stats().listening(ListeningPeriod.ALL_TIME, now, berlin, DayOfWeek.MONDAY))

        val noLibrary = stats(play(alder, now - 1_000), library = null).listening(ListeningPeriod.ALL_TIME, now, berlin, DayOfWeek.MONDAY)
        assertEquals(Listening(120_000, 1, emptyList(), emptyList(), emptyList()), noLibrary)
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
