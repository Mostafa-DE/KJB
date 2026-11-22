package app.lyrify.stats

import app.lyrify.library.Library
import app.lyrify.library.Song
import app.lyrify.library.SongRef
import app.lyrify.library.VOLUME
import app.lyrify.library.song
import app.lyrify.settings.LibrarySettings
import app.lyrify.settings.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Insights: the periods and the one before, what counts in which ledger, each card's minimum, and the cards themselves.
 * The phone is in Berlin and its week starts on Monday unless a test says otherwise; now is Wednesday 11 November 2026,
 * 15:00. Made-up names only.
 */
class InsightsTest {
    private val berlin = ZoneId.of("Europe/Berlin")

    private fun at(text: String): Long = ZonedDateTime.parse(text).toInstant().toEpochMilli()

    /** [day] of November 2026 at [time], Berlin. */
    private fun nov(day: Int, time: String = "12:00"): Long = at("2026-11-%02dT$time+01:00[Europe/Berlin]".format(day))

    /** [day] of October 2026 at [time], Berlin (summer time until the 25th). */
    private fun oct(day: Int, time: String = "12:00"): Long = at("2026-10-%02dT$time${if (day < 25) "+02:00" else "+01:00"}[Europe/Berlin]".format(day))

    private val now = nov(11, "15:00")
    private val month = Period(nov(1, "00:00"), at("2026-12-01T00:00+01:00[Europe/Berlin]"))
    private val october = Period(oct(1, "00:00"), nov(1, "00:00"))
    private val september = Period(at("2026-09-01T00:00+02:00[Europe/Berlin]"), oct(1, "00:00"))
    private val week = Period(nov(9, "00:00"), nov(16, "00:00"))

    // Harbour Glass: an album of five, Kit Calloway's, Ambient.
    private val alder = song("Music/Harbour Glass/01 Alder Smoke.mp3", album = "Harbour Glass", track = 1, genre = "Ambient", id = 1, addedAt = 1_000)
    private val birch = song("Music/Harbour Glass/02 Birch Window.mp3", album = "Harbour Glass", track = 2, genre = "Ambient", id = 2, addedAt = 2_000)
    private val cedar = song("Music/Harbour Glass/03 Cedar Hall.mp3", album = "Harbour Glass", track = 3, genre = "Ambient", id = 3, addedAt = 3_000)
    private val dune = song("Music/Harbour Glass/04 Dune Letter.mp3", album = "Harbour Glass", track = 4, genre = "Ambient", id = 4, addedAt = 4_000)
    private val elm = song("Music/Harbour Glass/05 Elm Static.mp3", album = "Harbour Glass", track = 5, genre = "Ambient", id = 5, addedAt = 5_000)

    // Wren Halloran: Night Ferry (one song) and a loose one, Folk.
    private val ember = song("Music/Night Ferry/Ember Ledger.mp3", artist = "Wren Halloran", album = "Night Ferry", genre = "Folk", id = 6, addedAt = 6_000)
    private val salt = song("Music/Loose/Salt Lattice.mp3", artist = "Wren Halloran", genre = "Folk", id = 7, addedAt = 7_000)

    // Quiet Rooms: an album of four, Oriel Vance's, Jazz.
    private val fern = song("Music/Quiet Rooms/1 Fern Signal.mp3", artist = "Oriel Vance", album = "Quiet Rooms", track = 1, genre = "Jazz", id = 8, addedAt = 800)
    private val gale = song("Music/Quiet Rooms/2 Gale Notes.mp3", artist = "Oriel Vance", album = "Quiet Rooms", track = 2, genre = "Jazz", id = 9, addedAt = 900)
    private val hush = song("Music/Quiet Rooms/3 Hush Paper.mp3", artist = "Oriel Vance", album = "Quiet Rooms", track = 3, genre = "Jazz", id = 10, addedAt = 950)
    private val iris = song("Music/Quiet Rooms/4 Iris Lamp.mp3", artist = "Oriel Vance", album = "Quiet Rooms", track = 4, genre = "Jazz", id = 11, addedAt = 990)

    // Not music for the top lists: a podcast episode, a hidden song, a book.
    private val episode = song("Music/Shows/Tidepool Talk 12.mp3", artist = "Tidepool Talk", isPodcast = true, id = 12, addedAt = 12_000)
    private val veil = song("Music/Loose/Veil Hum.mp3", artist = "Wren Halloran", genre = "Folk", id = 13, addedAt = 13_000)
    private val tome = song("Music/Rips/Lantern Keeper/01.mp3", album = "Lantern Keeper", genre = "Audiobook", id = 14)
    private val gone = SongRef(VOLUME + "Music/Gone/Tin Roof.mp3")

    private val library = Library.of(
        listOf(alder, birch, cedar, dune, elm, ember, salt, fern, gale, hush, iris, episode, veil, tome),
        Settings(library = LibrarySettings(hiddenSongs = listOf(veil.ref))),
    )

    private fun play(song: Song, startedAt: Long, heardMs: Long = 120_000, counted: Boolean = true, zone: Int? = null) =
        LoggedPlay(song.ref, startedAt, heardMs, counted, zoneOffsetMinutes = zone)

    /** [count] plays of [song] that counted, ten minutes apart from [from]. */
    private fun plays(song: Song, count: Int, from: Long, heardMs: Long = 120_000): List<LoggedPlay> = List(count) { play(song, from + it * 600_000L, heardMs) }

    /** [card], which must show. */
    private fun <T : Any> shown(card: T?): T {
        assertNotNull(card)
        return checkNotNull(card)
    }

    private fun everPlayed(plays: List<LoggedPlay>): List<SongPlays> = runBlocking { FakePlayLog(plays).totals() }

    private fun insights(
        plays: List<LoggedPlay>,
        period: Period? = month,
        previous: Period? = october,
        books: List<LoggedPlay> = emptyList(),
        ever: List<SongPlays> = everPlayed(plays),
        favourites: List<SongRef> = emptyList(),
        library: Library? = this.library,
        now: Long = this.now,
    ): Insights = insightsOf(InsightsData(plays.sortedBy { it.startedAt }, books, ever, favourites), period, previous, now, berlin, library)

    // The periods

    @Test
    fun `the week before starts on the phone's own first day of the week`() {
        val monday = periodOf(ListeningPeriod.WEEK, now, berlin, DayOfWeek.MONDAY)
        assertEquals(Period(nov(2, "00:00"), nov(9, "00:00")), previousPeriod(ListeningPeriod.WEEK, monday, berlin, DayOfWeek.MONDAY))
        val sunday = periodOf(ListeningPeriod.WEEK, now, berlin, DayOfWeek.SUNDAY)
        assertEquals(Period(nov(1, "00:00"), nov(8, "00:00")), previousPeriod(ListeningPeriod.WEEK, sunday, berlin, DayOfWeek.SUNDAY))
        val saturday = periodOf(ListeningPeriod.WEEK, now, berlin, DayOfWeek.SATURDAY)
        assertEquals(Period(oct(31, "00:00"), nov(7, "00:00")), previousPeriod(ListeningPeriod.WEEK, saturday, berlin, DayOfWeek.SATURDAY))
    }

    @Test
    fun `the month and year before run midnight to midnight in local time, across a clock change`() {
        assertEquals(october, previousPeriod(ListeningPeriod.MONTH, month, berlin, DayOfWeek.MONDAY))
        val year = periodOf(ListeningPeriod.YEAR, now, berlin, DayOfWeek.MONDAY)
        assertEquals(Period(at("2025-01-01T00:00+01:00[Europe/Berlin]"), at("2026-01-01T00:00+01:00[Europe/Berlin]")), previousPeriod(ListeningPeriod.YEAR, year, berlin, DayOfWeek.MONDAY))
        // The week before holds the spring clock change: 167 hours.
        val spring = periodOf(ListeningPeriod.WEEK, at("2026-03-31T12:00+02:00[Europe/Berlin]"), berlin, DayOfWeek.MONDAY)
        val before = shown(previousPeriod(ListeningPeriod.WEEK, spring, berlin, DayOfWeek.MONDAY))
        assertEquals(Period(at("2026-03-23T00:00+01:00[Europe/Berlin]"), at("2026-03-30T00:00+02:00[Europe/Berlin]")), before)
        assertEquals(167 * HOUR, before.until - before.from)
    }

    @Test
    fun `all time has no period before`() {
        assertNull(previousPeriod(ListeningPeriod.ALL_TIME, null, berlin, DayOfWeek.MONDAY))
    }

    @Test
    fun `a month's bounds are its first day's midnight to the next month's, in the phone's time`() {
        assertEquals(month, monthPeriod(YearMonth.of(2026, 11), berlin))
        assertEquals(october, monthPeriod(YearMonth.of(2026, 10), berlin))
        assertEquals(Period(Instant.parse("2026-02-01T08:00:00Z").toEpochMilli(), Instant.parse("2026-03-01T08:00:00Z").toEpochMilli()), monthPeriod(YearMonth.of(2026, 2), ZoneId.of("America/Los_Angeles")))
    }

    @Test
    fun `the recap offered is the month before today's, in the phone's time`() {
        assertEquals(YearMonth.of(2026, 10), recapMonthOf(now, berlin))
        assertEquals(YearMonth.of(2026, 10), recapMonthOf(nov(1, "00:30"), berlin))
        // 00:30 on 1 November in Berlin is still 31 October in New York.
        assertEquals(YearMonth.of(2026, 9), recapMonthOf(nov(1, "00:30"), ZoneId.of("America/New_York")))
        assertEquals(YearMonth.of(2025, 12), recapMonthOf(at("2026-01-05T09:00+01:00[Europe/Berlin]"), berlin))
    }

    // What counts

    @Test
    fun `time heard counts everything, plays all but books, and songs, artists and albums only music`() {
        val heard = listOf(
            play(alder, nov(2), heardMs = 120_000),
            play(veil, nov(2, "13:00"), heardMs = 100_000),
            play(episode, nov(3), heardMs = 300_000),
            LoggedPlay(gone, nov(3, "13:00"), 50_000, counted = true),
            // A play logged before its file became a book: book time, never a play.
            play(tome, nov(4), heardMs = 400_000),
            // Heard, but it did not count under "Count a play after".
            play(birch, nov(4, "13:00"), heardMs = 20_000, counted = false),
        )
        val books = listOf(LoggedPlay(SongRef(VOLUME + "Audiobooks/Salt Road/part 1.m4b"), nov(5), 600_000, counted = false), LoggedPlay(SongRef(VOLUME + "Audiobooks/Salt Road/part 1.m4b"), oct(30), 999_000, counted = false))

        val insights = insights(heard, books = books)

        assertEquals(Totals(heardMs = 1_590_000, plays = 4, songs = 1, artists = 1, albums = 1), insights.totals)
        assertEquals(Spoken(booksMs = 1_000_000, podcastsMs = 300_000), insights.spoken)
    }

    @Test
    fun `the top lists are music only, the most played first, ties going to the latest played`() {
        val heard = plays(alder, 4, nov(2)) + plays(birch, 3, nov(3)) + plays(ember, 3, nov(4)) + plays(veil, 5, nov(5)) + plays(episode, 5, nov(6)) + plays(tome, 5, nov(7))

        val insights = insights(heard)

        assertEquals(listOf(alder to 4, ember to 3, birch to 3), insights.songs.map { it.item to it.plays })
        assertEquals(listOf("Kit Calloway" to 7, "Wren Halloran" to 3), insights.artists.map { it.item.name to it.plays })
        assertEquals(listOf("Harbour Glass" to 7, "Night Ferry" to 3), insights.albums.map { it.item.title to it.plays })
        assertEquals(listOf("Ambient" to 7, "Folk" to 3), insights.genres.map { it.item.name to it.plays })
    }

    @Test
    fun `an import's plays and the short ones never add to a count`() = runBlocking {
        val own = plays(alder, 6, nov(2)) + plays(cedar, 2, nov(3, "08:00")) + plays(dune, 2, nov(3, "09:00"))
        val imported = List(50) { LoggedPlay(birch.ref, nov(3) + it, 0, counted = true, imported = true) }
        val short = List(3) { LoggedPlay(ember.ref, nov(4) + it * 1_000L, 500, counted = false, end = PlayEnded(PlayEnd.NEXT, 500)) }
        val stats = PlayStats(FakePlayLog(own + imported + short), { library }, Dispatchers.Unconfined)

        val insights = stats.insights(ListeningPeriod.MONTH, now, berlin, DayOfWeek.MONDAY)

        assertEquals(Totals(heardMs = 10 * 120_000L, plays = 10, songs = 3, artists = 1, albums = 1), insights.totals)
        assertEquals(listOf(alder to 6, dune to 2, cedar to 2), insights.songs.map { it.item to it.plays })
        // An import's play still means the song was played: it is not "never played".
        assertFalse(birch in insights.neverPlayed)
        assertTrue(ember in insights.neverPlayed)
    }

    @Test
    fun `a play counts in the period it started in, in the phone's time`() {
        val heard = listOf(play(alder, oct(31, "23:59")), play(birch, nov(1, "00:00")), play(cedar, nov(30, "23:59")), play(dune, at("2026-12-01T00:00+01:00[Europe/Berlin]")))

        assertEquals(2, insights(heard).totals?.plays)
        assertEquals(1, insights(heard, period = october, previous = september).totals?.plays)
    }

    // Each card's minimum

    @Test
    fun `nothing heard is the empty state, a minute heard or one play shows the totals`() {
        assertNull(insights(emptyList()).totals)
        assertNull(insights(listOf(play(alder, nov(2), heardMs = 59_999, counted = false))).totals)
        assertEquals(Totals(60_000, 0, 0, 0, 0), insights(listOf(play(alder, nov(2), heardMs = 60_000, counted = false))).totals)
        assertEquals(Totals(40_000, 1, 1, 1, 1), insights(listOf(play(alder, nov(2), heardMs = 40_000))).totals)
        val book = LoggedPlay(SongRef(VOLUME + "Audiobooks/Salt Road/part 1.m4b"), nov(5), 60_000, counted = false)
        assertEquals(Totals(60_000, 0, 0, 0, 0), insights(emptyList(), books = listOf(book)).totals)
    }

    @Test
    fun `top songs need 10 music plays of 3 songs or more`() {
        val nine = plays(alder, 4, nov(2)) + plays(birch, 3, nov(3)) + plays(cedar, 2, nov(4))
        assertEquals(emptyList<Top<Song>>(), insights(nine + plays(veil, 5, nov(5)) + plays(episode, 5, nov(6))).songs)
        assertEquals(listOf(alder, cedar, birch), insights(nine + play(cedar, nov(8))).songs.map { it.item })
        assertEquals(emptyList<Top<Song>>(), insights(plays(alder, 5, nov(2)) + plays(birch, 5, nov(3))).songs)
    }

    @Test
    fun `top artists, albums and genres need 10 music plays of 2 or more`() {
        val one = plays(alder, 4, nov(2)) + plays(birch, 3, nov(3)) + plays(cedar, 3, nov(4))
        val single = insights(one)
        assertEquals(3, single.songs.size)
        assertTrue(single.artists.isEmpty())
        assertTrue(single.albums.isEmpty())
        assertTrue(single.genres.isEmpty())

        val two = insights(one + play(ember, nov(5)))
        assertEquals(listOf("Kit Calloway", "Wren Halloran"), two.artists.map { it.item.name })
        assertEquals(listOf("Harbour Glass", "Night Ferry"), two.albums.map { it.item.title })
        assertEquals(listOf("Ambient", "Folk"), two.genres.map { it.item.name })
        // The counts on the totals card show whatever the lists' minimums.
        assertEquals(Totals(11 * 120_000L, 11, 4, 2, 2), two.totals)
        assertEquals(Totals(10 * 120_000L, 10, 3, 1, 1), single.totals)
    }

    @Test
    fun `this period against the one before needs 10 plays or half an hour before it, and all time has none`() {
        val nowPlays = plays(alder, 2, nov(2))
        assertNull(insights(nowPlays + plays(birch, 9, oct(3))).versus)
        assertNotNull(insights(nowPlays + plays(birch, 10, oct(3))).versus)
        assertNotNull(insights(nowPlays + play(birch, oct(3), heardMs = 30 * MINUTE)).versus)
        assertNull(insights(nowPlays + play(birch, oct(3), heardMs = 30 * MINUTE - 1, counted = false)).versus)
        assertNull(insights(plays(birch, 20, oct(3))).versus)
        assertNull(insights(nowPlays + plays(birch, 20, oct(3)), period = null, previous = null).versus)
    }

    @Test
    fun `a period going on is set against the one before up to the same point`() {
        // Now is 10 days and 15 hours into November: October up to the 11th at 15:00 counts, the rest does not.
        val heard = plays(alder, 3, nov(2)) + plays(birch, 10, oct(5)) + play(birch, oct(11, "14:59")) + plays(cedar, 10, oct(11, "15:00"))

        assertEquals(Versus(heardMs = 3 * 120_000L, plays = 3, beforeHeardMs = 11 * 120_000L, beforePlays = 11), insights(heard).versus)
    }

    @Test
    fun `a period that is over is set against all of the one before`() {
        val heard = plays(alder, 3, oct(2)) + plays(birch, 10, at("2026-09-05T12:00+02:00[Europe/Berlin]")) + plays(cedar, 10, at("2026-09-28T12:00+02:00[Europe/Berlin]"))

        assertEquals(Versus(3 * 120_000L, 3, 20 * 120_000L, 20), insights(heard, period = october, previous = september).versus)
    }

    @Test
    fun `the clock needs 10 plays, and puts each play's time in the hour it started in where it was heard`() {
        // 21:30 UTC is 00:30 in Amman (180 minutes ahead), 22:30 in Berlin.
        val night = Instant.parse("2026-11-03T21:30:00Z").toEpochMilli()
        val amman = List(10) { play(alder, night + it * DAY, zone = 180) }
        val berlinLate = List(2) { play(birch, night + it * DAY + 60_000, heardMs = 700_000) }

        assertNull(insights(amman.take(9)).clock)
        val clock = shown(insights(amman + berlinLate).clock)
        assertEquals(24, clock.hours.size)
        assertEquals(1_200_000L, clock.hours[0])
        assertEquals(1_400_000L, clock.hours[22])
        assertEquals(22, clock.peak)
        assertEquals(2_600_000L, clock.hours.sum())
        // A tie goes to the earlier hour.
        assertEquals(0, shown(insights(amman + berlinLate.map { it.copy(heardMs = 600_000) }).clock).peak)
    }

    @Test
    fun `a streak is days in a row with a play that counted, in the time zone each was heard in`() {
        // Monday the 9th in Berlin, then 21:30 UTC on Tuesday the 10th: in Amman already Wednesday the 11th.
        val apart = listOf(play(alder, nov(9)), play(birch, Instant.parse("2026-11-10T21:30:00Z").toEpochMilli(), zone = 180))
        assertNull(insights(apart, period = week, now = nov(16, "09:00")).streaks)
        val together = listOf(play(alder, nov(9)), play(birch, Instant.parse("2026-11-10T21:30:00Z").toEpochMilli()))
        assertEquals(Streaks(longest = 2, current = null), insights(together, period = week, now = nov(16, "09:00")).streaks)
        // A play that did not count makes no day.
        assertNull(insights(listOf(play(alder, nov(9)), play(birch, nov(10), counted = false)), period = week, now = nov(16, "09:00")).streaks)
    }

    @Test
    fun `the longest run is the period's own, the current one runs back from today or yesterday`() {
        val heard = listOf(nov(2), nov(3), nov(4), nov(6), nov(8), nov(9), nov(10)).map { play(alder, it) }

        assertEquals(Streaks(longest = 3, current = 3), insights(heard).streaks)
        assertEquals(Streaks(longest = 4, current = 4), insights(heard + play(birch, nov(11, "09:00"))).streaks)
        // This week holds the 9th and 10th; the run up to today began on Sunday the 8th.
        assertEquals(Streaks(longest = 2, current = 3), insights(heard, period = week, previous = null).streaks)
        // Nothing yesterday or today: no run now.
        assertEquals(Streaks(longest = 3, current = 0), insights(heard.dropLast(2)).streaks)
    }

    @Test
    fun `milestones are reached by the play that crosses them, imports and books never counting plays`() {
        val before = plays(alder, 99, oct(1))
        val hundredth = play(birch, nov(3))
        val book = play(tome, nov(2))

        val milestones = shown(insights(before + book + hundredth).milestones)
        assertEquals(listOf(Milestone(MilestoneKind.PLAYS, 100, nov(3))), milestones.reached)
        // 150 plays to 250 is closer, as a share, than 10 hours (100 plays of 2 minutes and the book's 2 make 3 h 22 min).
        assertEquals(NextMilestone(MilestoneKind.PLAYS, 250, 150), milestones.next)
    }

    @Test
    fun `hours heard reach their milestones with books and podcasts, at the play that crosses them`() {
        val book = LoggedPlay(SongRef(VOLUME + "Audiobooks/Salt Road/part 1.m4b"), oct(1), 10 * MINUTE, counted = false)
        val before = plays(alder, 58, oct(2), heardMs = 10 * MINUTE)
        val crossing = play(episode, nov(3), heardMs = 10 * MINUTE)

        val milestones = shown(insights(before + crossing, books = listOf(book)).milestones)
        assertEquals(listOf(Milestone(MilestoneKind.HOURS, 10, nov(3))), milestones.reached)
        assertEquals(NextMilestone(MilestoneKind.PLAYS, 100, 41), milestones.next)
    }

    @Test
    fun `the milestones card needs one reached in the period, or one ever reached while the period goes on`() {
        assertNull(insights(plays(alder, 20, nov(2))).milestones)
        val reachedBefore = plays(alder, 100, oct(1)) + play(birch, nov(2))
        assertEquals(Milestones(emptyList(), NextMilestone(MilestoneKind.PLAYS, 250, 149)), insights(reachedBefore).milestones)
        // October is over and reached nothing itself.
        assertNull(insights(plays(alder, 100, at("2026-09-01T12:00+02:00[Europe/Berlin]")) + play(birch, oct(2)), period = october, previous = september).milestones)
    }

    @Test
    fun `books and podcasts show from a minute heard`() {
        val book = { ms: Long -> LoggedPlay(SongRef(VOLUME + "Audiobooks/Salt Road/part 1.m4b"), nov(5), ms, counted = false) }
        assertNull(insights(plays(alder, 3, nov(2)), books = listOf(book(59_999))).spoken)
        assertEquals(Spoken(60_000, 0), insights(plays(alder, 3, nov(2)), books = listOf(book(60_000))).spoken)
        assertEquals(Spoken(0, 60_000), insights(listOf(play(episode, nov(2), heardMs = 60_000))).spoken)
    }

    // The nudges

    @Test
    fun `never played is the music no play counted for, here or imported, the newest added first, from 20 plays ever`() {
        val heard = plays(alder, 15, nov(2)) + play(birch, nov(3), counted = false)
        val ever = everPlayed(heard) + SongPlays(fern.ref, 5, oct(1), 0)

        assertEquals(listOf(salt, ember, elm, dune, cedar, birch, iris, hush, gale), insights(heard, ever = ever).neverPlayed)
        assertTrue(insights(heard, ever = everPlayed(heard) + SongPlays(fern.ref, 4, oct(1), 0)).neverPlayed.isEmpty())
    }

    @Test
    fun `dig deeper offers an album's songs never played after a play of it in the period, the most played album first`() {
        val heard = plays(alder, 3, nov(2)) + play(birch, nov(3)) + play(cedar, at("2026-09-02T12:00+02:00[Europe/Berlin]")) +
            plays(fern, 4, nov(4)) + play(gale, oct(1)) + play(hush, oct(2)) + plays(ember, 6, nov(5))

        val dig = insights(heard).digDeeper

        assertEquals(listOf(Triple("Harbour Glass", alder, listOf(dune, elm))), dig.map { Triple(it.album.title, it.played, it.unplayed) })
        // Quiet Rooms has one song never played: not enough. Night Ferry has one song: no album to dig into.
        assertEquals(
            listOf("Quiet Rooms", "Harbour Glass"),
            insights(heard.filterNot { it.song == hush.ref }).digDeeper.map { it.album.title },
        )
    }

    @Test
    fun `dig deeper needs a play that counted in the period`() {
        val heard = listOf(play(alder, oct(20)), play(birch, nov(3), counted = false)) + plays(ember, 20, oct(2))
        assertTrue(insights(heard).digDeeper.isEmpty())
    }

    @Test
    fun `forgotten favourites are songs played 5 times or hearted, quiet for 60 days, the most played first`() {
        val quiet = now - 60 * DAY
        val heard = plays(alder, 6, at("2026-08-01T12:00+02:00[Europe/Berlin]")) +
            plays(birch, 5, at("2026-09-01T12:00+02:00[Europe/Berlin]")) +
            plays(cedar, 4, at("2026-07-01T12:00+02:00[Europe/Berlin]")) +
            plays(dune, 9, oct(1)) +
            plays(elm, 6, quiet - 5 * 600_000L + 1) +
            plays(salt, 5, quiet - 4 * 600_000L) +
            plays(veil, 10, at("2026-07-01T12:00+02:00[Europe/Berlin]")) +
            plays(episode, 10, at("2026-07-01T12:00+02:00[Europe/Berlin]"))

        val forgotten = insights(heard, favourites = listOf(cedar.ref)).forgotten

        assertEquals(listOf(alder to 6, salt to 5, birch to 5, cedar to 4), forgotten.map { it.item to it.plays })
        assertEquals(quiet, forgotten[1].lastPlayedAt)
    }

    @Test
    fun `forgotten favourites need 3 songs, and show at most 10`() {
        val long = at("2026-07-01T12:00+02:00[Europe/Berlin]")
        assertTrue(insights(plays(alder, 6, long) + plays(birch, 5, long + DAY)).forgotten.isEmpty())
        val many = listOf(alder, birch, cedar, dune, elm, ember, salt, fern, gale, hush, iris).flatMapIndexed { i, song -> plays(song, 5 + i, long + i * DAY) }
        assertEquals(10, insights(many).forgotten.size)
        assertEquals(iris, insights(many).forgotten.first().item)
    }

    @Test
    fun `without a library only the totals are known`() {
        val insights = insights(plays(alder, 12, nov(2)), library = null)
        assertEquals(Totals(12 * 120_000L, 12, 0, 0, 0), insights.totals)
        assertTrue(insights.songs.isEmpty())
        assertTrue(insights.neverPlayed.isEmpty())
    }

    // The monthly recap

    @Test
    fun `the months with a recap are those before this one with 10 plays that counted, the latest first`() {
        val heard = plays(alder, 9, oct(1)) + play(alder, oct(31, "23:30")) + plays(birch, 9, at("2026-09-02T12:00+02:00[Europe/Berlin]")) +
            List(5) { play(cedar, at("2026-09-03T12:00+02:00[Europe/Berlin]") + it, counted = false) } +
            plays(dune, 12, at("2026-08-02T12:00+02:00[Europe/Berlin]")) + plays(elm, 20, nov(2))

        assertEquals(listOf(YearMonth.of(2026, 10), YearMonth.of(2026, 8)), recapMonths(heard, now, berlin))
    }

    @Test
    fun `the recap card shows for a month with a recap until it is opened or closed`() {
        val months = listOf(YearMonth.of(2026, 10), YearMonth.of(2026, 8))
        assertTrue(recapDue(YearMonth.of(2026, 10), months, seen = null))
        assertTrue(recapDue(YearMonth.of(2026, 10), months, seen = YearMonth.of(2026, 8)))
        assertFalse(recapDue(YearMonth.of(2026, 10), months, seen = YearMonth.of(2026, 10)))
        // The clock went back: a later month was seen already.
        assertFalse(recapDue(YearMonth.of(2026, 10), months, seen = YearMonth.of(2026, 11)))
        assertFalse(recapDue(YearMonth.of(2026, 9), months, seen = null))
    }

    @Test
    fun `a month's recap is its insights set against the month before, the nudges left to the page`() = runBlocking {
        val heard = plays(alder, 10, oct(2)) + plays(birch, 10, at("2026-09-02T12:00+02:00[Europe/Berlin]")) + plays(cedar, 3, nov(2))
        val stats = PlayStats(FakePlayLog(heard), { library }, Dispatchers.Unconfined)

        val recap = stats.recap(YearMonth.of(2026, 10), now, berlin)

        assertEquals(Totals(10 * 120_000L, 10, 1, 1, 1), recap.totals)
        assertEquals(Versus(10 * 120_000L, 10, 10 * 120_000L, 10), recap.versus)
        assertEquals(listOf(YearMonth.of(2026, 10), YearMonth.of(2026, 9)), stats.recaps(now, berlin))
    }

    private companion object {
        const val MINUTE: Long = 60_000
        const val HOUR: Long = 3_600_000
        const val DAY: Long = 86_400_000
    }
}
