package app.lyrify.stats

import app.lyrify.library.Library
import app.lyrify.library.Song
import app.lyrify.library.SongRef
import app.lyrify.library.VOLUME
import app.lyrify.library.song
import app.lyrify.player.SourceKind
import app.lyrify.settings.Settings
import app.lyrify.suggestions.FriendPlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The Insights page (KJB v4, the approved mockup): each card's figures for a period and the period before, from made-up
 * plays. The phone is in Amman (UTC+3 all year, its week from Monday, its weekend Friday and Saturday) unless a test says
 * otherwise; now is Thursday 26 November 2026, 21:40. Made-up names only.
 */
class InsightsPageTest {
    private val amman = ammanCalendar.zone

    private fun at(text: String): Long = ZonedDateTime.parse(text).toInstant().toEpochMilli()

    /** [day] of November 2026 at [time], Amman. */
    private fun nov(day: Int, time: String = "12:00"): Long = at("2026-11-%02dT$time+03:00[Asia/Amman]".format(day))

    /** [day] of October 2026 at [time], Amman. */
    private fun oct(day: Int, time: String = "12:00"): Long = at("2026-10-%02dT$time+03:00[Asia/Amman]".format(day))

    private val now = nov(26, "21:40")

    // Low Sun: Kestrel's album of four, 2014, Indie.
    private val copper = song("Music/Low Sun/01 Copper Sky.mp3", artist = "Kestrel", album = "Low Sun", track = 1, year = 2014, genre = "Indie", id = 1, addedAt = 1_000)
    private val tide = song("Music/Low Sun/02 Tidewater.mp3", artist = "Kestrel", album = "Low Sun", track = 2, year = 2014, genre = "Indie", id = 2, addedAt = 2_000)
    private val lantern = song("Music/Low Sun/03 Lanterns.mp3", artist = "Kestrel", album = "Low Sun", track = 3, year = 2014, genre = "Indie", id = 3, addedAt = 3_000)
    private val signal = song("Music/Low Sun/04 Signal Fires.mp3", artist = "Kestrel", album = "Low Sun", track = 4, year = 2014, genre = "Indie", id = 4, addedAt = 4_000)

    // Tidelines: Mira Solen's, 2019, Folk. Night Drive: The Lowlands', 2008, Electronic. A loose song of Bexley Rowe's, 1996.
    private val harbour = song("Music/Tidelines/01 Paper Harbour.mp3", artist = "Mira Solen", album = "Tidelines", track = 1, year = 2019, genre = "Folk", id = 5, addedAt = 5_000)
    private val glass = song("Music/Tidelines/02 Glass Harbour.mp3", artist = "Mira Solen", album = "Tidelines", track = 2, year = 2019, genre = "Folk", id = 6, addedAt = 6_000)
    private val north = song("Music/Night Drive/Northbound.mp3", artist = "The Lowlands", album = "Night Drive", year = 2008, genre = "Electronic", id = 7, addedAt = 7_000)
    private val kite = song("Music/Loose/Kite Season.mp3", artist = "Bexley Rowe", year = 1996, genre = "Folk", id = 8, addedAt = 8_000)

    // A podcast episode, and a book's part (the book log's).
    private val talk = song("Music/Shows/Small Talk 12.mp3", artist = "Small Talk Radio", isPodcast = true, id = 9, addedAt = 9_000)
    private val saltRoad = SongRef(VOLUME + "Audiobooks/Salt Road/part 1.m4b")

    private val library = Library.of(listOf(copper, tide, lantern, signal, harbour, glass, north, kite, talk), Settings())

    private fun play(song: Song, startedAt: Long, heardMs: Long = 120_000, counted: Boolean = true, zone: Int? = null, clockMs: Long? = null) =
        LoggedPlay(song.ref, startedAt, heardMs, counted, zoneOffsetMinutes = zone, clockMs = clockMs)

    /** [count] plays of [song] that counted, ten minutes apart from [from]. */
    private fun plays(song: Song, count: Int, from: Long): List<LoggedPlay> = List(count) { play(song, from + it * 10 * MINUTE) }

    private fun bookPlay(startedAt: Long, heardMs: Long = 600_000, clockMs: Long? = null) = LoggedPlay(saltRoad, startedAt, heardMs, counted = false, clockMs = clockMs)

    private fun page(
        plays: List<LoggedPlay>,
        period: ListeningPeriod = ListeningPeriod.MONTH,
        books: List<LoggedPlay> = emptyList(),
        library: Library? = this.library,
        calendar: PhoneCalendar = ammanCalendar,
        now: Long = this.now,
        interactions: List<Interaction> = emptyList(),
        sources: PageSources = PageSources(),
        favourites: List<SongRef> = emptyList(),
    ): InsightsPage = runBlocking { stats(plays, books, library, interactions, sources, favourites).page(period, now, calendar) }

    private fun recap(plays: List<LoggedPlay>, month: YearMonth, calendar: PhoneCalendar = ammanCalendar, sources: PageSources = PageSources()): InsightsPage =
        runBlocking { stats(plays, emptyList(), library, sources = sources).page(month, now, calendar) }

    private fun stats(
        plays: List<LoggedPlay>,
        books: List<LoggedPlay>,
        library: Library?,
        interactions: List<Interaction> = emptyList(),
        sources: PageSources = PageSources(),
        favourites: List<SongRef> = emptyList(),
    ) = PlayStats(
        FakePlayLog(plays.sortedBy { it.startedAt }, interactions),
        { library },
        Dispatchers.Unconfined,
        books = FakePlayLog(books),
        favourites = { favourites },
        sources = { from -> sources.copy(friendPlays = sources.friendPlays.filter { from == null || it.at >= from }) },
    )

    /** [card], which must show. */
    private fun <T : Any> shown(card: T?): T {
        assertNotNull(card)
        return checkNotNull(card)
    }

    private fun minutes(vararg values: Long?): List<Long?> = values.map { it?.times(MINUTE) }

    // The period

    @Test
    fun `a week runs from the phone's first day of the week to today, against last week up to the same point`() {
        assertEquals(
            PagePeriod(
                kind = ListeningPeriod.WEEK,
                bounds = Period(nov(23, "00:00"), nov(30, "00:00")),
                firstDay = LocalDate.of(2026, 11, 23),
                lastDay = LocalDate.of(2026, 11, 26),
                going = true,
                before = Period(nov(16, "00:00"), nov(23, "00:00")),
                beforeUntil = nov(19, "21:40"),
            ),
            page(emptyList(), ListeningPeriod.WEEK).period,
        )
        val fromSunday = page(emptyList(), ListeningPeriod.WEEK, calendar = ammanCalendar.copy(firstDayOfWeek = DayOfWeek.SUNDAY)).period
        assertEquals(LocalDate.of(2026, 11, 22), fromSunday.firstDay)
        assertEquals(Period(nov(15, "00:00"), nov(22, "00:00")), fromSunday.before)
    }

    @Test
    fun `a month over ends on its last day against all of the month before, and all time starts on the first play's day`() {
        val october = recap(emptyList(), YearMonth.of(2026, 10)).period
        assertEquals(LocalDate.of(2026, 10, 31), october.lastDay)
        assertEquals(false, october.going)
        assertEquals(oct(1, "00:00"), october.beforeUntil)

        val all = page(listOf(play(copper, oct(3, "01:00"))), ListeningPeriod.ALL_TIME).period
        assertEquals(LocalDate.of(2026, 10, 3), all.firstDay)
        assertEquals(LocalDate.of(2026, 11, 26), all.lastDay)
        assertNull(all.before)
    }

    // Your time

    @Test
    fun `your time counts real minutes, books too, against last week up to the same point`() {
        val plays = listOf(
            play(copper, nov(23, "09:00"), clockMs = 100_000),
            play(harbour, nov(24, "10:00")),
            play(north, nov(26, "21:00")),
            // A podcast: time and a play, but no song, artist or album of the lists.
            play(talk, nov(25, "08:00")),
            // Last week up to Thursday 21:40, then after it: left out of the comparison.
            play(copper, nov(16, "09:00"), heardMs = 20 * MINUTE),
            play(tide, nov(19, "21:39"), heardMs = 20 * MINUTE),
            play(harbour, nov(19, "21:41")),
            play(north, nov(21)),
        )

        val time = shown(page(plays, ListeningPeriod.WEEK, books = listOf(bookPlay(nov(25, "07:00"), clockMs = 300_000))).cards.time)

        assertEquals(Totals(heardMs = 100_000 + 3 * 120_000L + 300_000, plays = 4, songs = 3, artists = 3, albums = 3), time.totals)
        assertEquals(Totals(heardMs = 40 * MINUTE, plays = 2, songs = 2, artists = 1, albums = 1), time.before)
    }

    @Test
    fun `the period before is left out when it had under 10 plays and under 30 minutes`() {
        val plays = listOf(play(copper, nov(23)), play(copper, nov(16), heardMs = 29 * MINUTE))

        assertNull(shown(page(plays, ListeningPeriod.WEEK).cards.time).before)
        assertNull(shown(page(plays, ListeningPeriod.WEEK).cards.time).heardBefore)
        assertNull(shown(page(plays, ListeningPeriod.ALL_TIME).cards.time).before)
    }

    @Test
    fun `the time step by step is a day each for a week or a month and a month each for a year or all time, none yet for days to come`() {
        val plays = listOf(
            play(copper, nov(23, "09:00"), heardMs = 60 * MINUTE),
            play(harbour, nov(23, "10:00"), heardMs = 30 * MINUTE),
            play(north, nov(26, "21:00"), heardMs = 10 * MINUTE),
            play(copper, nov(17), heardMs = 40 * MINUTE),
            play(tide, nov(22, "23:59"), heardMs = 50 * MINUTE),
            play(kite, oct(2), heardMs = 20 * MINUTE),
            play(kite, at("2026-09-30T23:00+03:00[Asia/Amman]"), heardMs = 5 * MINUTE),
        )
        val book = bookPlay(nov(25, "07:00"), heardMs = 45 * MINUTE)

        val week = shown(page(plays, ListeningPeriod.WEEK, books = listOf(book)).cards.time)
        assertEquals(Steps(Step.DAY, LocalDate.of(2026, 11, 23), minutes(90, 0, 45, 10, null, null, null)), week.heard)
        assertEquals(Steps(Step.DAY, LocalDate.of(2026, 11, 16), minutes(0, 40, 0, 0, 0, 0, 50)), week.heardBefore)
        assertEquals(week.totals.heardMs, week.heard.heardMs.sumOf { it ?: 0 })

        val month = shown(page(plays, ListeningPeriod.MONTH, books = listOf(book)).cards.time).heard
        assertEquals(30, month.heardMs.size)
        assertEquals(LocalDate.of(2026, 11, 1), month.first)
        assertEquals(minutes(90, 0, 45, 10, null, null, null, null), month.heardMs.subList(22, 30))
        assertEquals(40 * MINUTE, month.heardMs[16])

        val year = shown(page(plays, ListeningPeriod.YEAR, books = listOf(book)).cards.time).heard
        assertEquals(Steps(Step.MONTH, LocalDate.of(2026, 1, 1), minutes(0, 0, 0, 0, 0, 0, 0, 0, 5, 20, 235, null)), year)

        val all = shown(page(plays, ListeningPeriod.ALL_TIME, books = listOf(book)).cards.time)
        assertEquals(Steps(Step.MONTH, LocalDate.of(2026, 9, 1), minutes(5, 20, 235)), all.heard)
        assertNull(all.heardBefore)
    }

    @Test
    fun `a play counts on the day and at the hour it was heard, where it was heard`() {
        // Tuesday 02:00 in Amman is Monday 18:00 in New York, where it was heard.
        val away = play(copper, nov(24, "02:00"), zone = -300)
        val home = plays(harbour, 9, nov(24, "10:00"))

        val page = page(home + away, ListeningPeriod.WEEK)

        assertEquals(minutes(2, 18, 0, 0, null, null, null), shown(page.cards.time).heard.heardMs)
        assertEquals(120_000L, shown(page.cards.clock).hours[18])
    }

    @Test
    fun `a play heard where it is tomorrow already counts on today's step, so the steps still add up`() {
        // Thursday 21:30 in Amman is Friday 03:30 in Tokyo, where it was heard.
        val ahead = play(copper, nov(26, "21:30"), heardMs = 10 * MINUTE, zone = 540)

        val cards = page(plays(harbour, 9, nov(23)) + ahead, ListeningPeriod.WEEK).cards

        assertEquals(minutes(18, 0, 0, 10, null, null, null), shown(cards.time).heard.heardMs)
        assertEquals(minutes(18, 0, 0, 10, null, null, null), shown(cards.days).heard.heardMs)
    }

    @Test
    fun `the period before goes up to the same day and time on the clock, across a clock change`() {
        val berlin = ammanCalendar.copy(zone = ZoneId.of("Europe/Berlin"), weekend = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY))
        // Sunday 25 October 2026 has 25 hours in Berlin: its noon is 157 hours into the week, the Sunday before's 156.
        val sundayNoon = at("2026-10-25T12:00+01:00[Europe/Berlin]")
        val plays = listOf(
            play(copper, at("2026-10-19T12:00+02:00[Europe/Berlin]")),
            play(harbour, at("2026-10-18T11:00+02:00[Europe/Berlin]"), heardMs = 40 * MINUTE),
            play(north, at("2026-10-18T12:30+02:00[Europe/Berlin]"), heardMs = 40 * MINUTE),
        )

        val page = page(plays, ListeningPeriod.WEEK, calendar = berlin, now = sundayNoon)

        assertEquals(at("2026-10-18T12:00+02:00[Europe/Berlin]"), page.period.beforeUntil)
        assertEquals(40 * MINUTE, shown(page.cards.time).before?.heardMs)
        // in1's Insights set the week against the same point.
        val insights = runBlocking { stats(plays, emptyList(), library).insights(ListeningPeriod.WEEK, sundayNoon, berlin.zone, DayOfWeek.MONDAY) }
        assertEquals(40 * MINUTE, insights.versus?.beforeHeardMs)
    }

    @Test
    fun `a month's days follow the phone's calendar across a clock change`() {
        val berlin = ammanCalendar.copy(zone = ZoneId.of("Europe/Berlin"), weekend = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY))
        // 25 October 2026 has 25 hours in Berlin: a play at 23:30 is still on the 25th.
        val late = play(copper, at("2026-10-25T23:30+01:00[Europe/Berlin]"), heardMs = 10 * MINUTE)

        val heard = shown(recap(listOf(late), YearMonth.of(2026, 10), berlin).cards.time).heard

        assertEquals(31, heard.heardMs.size)
        assertEquals(10 * MINUTE, heard.heardMs[24])
    }

    // Every day you listened

    @Test
    fun `every day you listened has the run up to today, the period's longest run from its first day, and the busiest day`() {
        val plays = listOf(nov(2), nov(3), nov(4)).map { play(kite, it) } +
            (10..14).map { play(kite, nov(it)) } + plays(harbour, 2, nov(12, "18:00")) +
            listOf(nov(24), nov(25), nov(26, "08:00")).map { play(kite, it) }

        val days = shown(page(plays).cards.days)

        assertEquals(3, days.current)
        assertEquals(Run(5, LocalDate.of(2026, 11, 10)), days.longest)
        assertEquals(DayHeard(LocalDate.of(2026, 11, 12), 3 * 120_000L), days.busiest)
        assertEquals(page(plays).cards.time?.heard, days.heard)
    }

    @Test
    fun `a year's heat map has a day each, and a month over has no run up to today`() {
        val plays = (2..4).map { play(kite, oct(it)) }

        val year = shown(page(plays, ListeningPeriod.YEAR).cards.days)
        assertEquals(Steps(Step.DAY, LocalDate.of(2026, 1, 1), year.heard.heardMs), year.heard)
        assertEquals(365, year.heard.heardMs.size)
        assertEquals(listOf(2 * MINUTE, null), listOf(year.heard.heardMs[275], year.heard.heardMs[331]))

        val october = shown(recap(plays, YearMonth.of(2026, 10)).cards.days)
        assertNull(october.current)
        assertEquals(Run(3, LocalDate.of(2026, 10, 2)), october.longest)
    }

    @Test
    fun `every day you listened needs plays on two days`() {
        assertNull(page(plays(kite, 5, nov(3))).cards.days)
    }

    // Your day on a record

    @Test
    fun `the clock puts real minutes in the hour heard, and splits weekdays from the phone's own weekend`() {
        // Friday 20 November is a weekend day in Jordan, a weekday where the weekend is Saturday and Sunday.
        val plays = plays(kite, 4, nov(20, "10:00")) + plays(harbour, 6, nov(23, "21:00"))

        val jordan = shown(page(plays).cards.clock)
        assertEquals(21, jordan.peak)
        assertEquals(listOf(4 * 120_000L, 6 * 120_000L), listOf(jordan.hours[10], jordan.hours[21]))
        assertEquals(6 * 120_000L to 4 * 120_000L, jordan.weekdaysMs to jordan.weekendMs)

        val elsewhere = shown(page(plays, calendar = ammanCalendar.copy(weekend = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY))).cards.clock)
        assertEquals(10 * 120_000L to 0L, elsewhere.weekdaysMs to elsewhere.weekendMs)
    }

    @Test
    fun `the clock has the morning, afternoon, evening and night, and the time after 9 pm`() {
        val plays = plays(kite, 2, nov(3, "07:00")) + plays(kite, 2, nov(3, "13:00")) + plays(kite, 2, nov(3, "19:00")) +
            plays(kite, 2, nov(3, "21:30")) + plays(kite, 2, nov(3, "23:00"))

        val clock = shown(page(plays).cards.clock)

        assertEquals(listOf(240_000L, 240_000L, 480_000L, 240_000L), listOf(clock.morningMs, clock.afternoonMs, clock.eveningMs, clock.nightMs))
        assertEquals(480_000L, clock.lateMs)
    }

    @Test
    fun `the clock needs 10 plays that counted`() {
        assertNull(page(plays(kite, 9, nov(3))).cards.clock)
    }

    // The stories row

    @Test
    fun `the stories row lists each month with a recap, the latest first, with covers of its most played songs of different albums`() {
        val october = plays(copper, 4, oct(3)) + plays(tide, 3, oct(4)) + plays(harbour, 2, oct(5)) + plays(north, 1, oct(6))
        val august = plays(kite, 10, at("2026-08-10T12:00+03:00[Asia/Amman]"))
        val september = plays(kite, 9, at("2026-09-10T12:00+03:00[Asia/Amman]"))
        val november = plays(kite, 12, nov(3))
        val plays = october + august + september + november

        assertEquals(
            listOf(RecapMonth(YearMonth.of(2026, 10), listOf(copper, harbour, north)), RecapMonth(YearMonth.of(2026, 8), listOf(kite))),
            page(plays, ListeningPeriod.WEEK).cards.recaps,
        )
        assertEquals(emptyList<RecapMonth>(), recap(plays, YearMonth.of(2026, 10)).cards.recaps)
    }

    // The top lists

    @Test
    fun `the top lists rank the music by plays, each with its place in all of the period before and the song whose cover shows it`() {
        val november = plays(copper, 5, nov(3)) + plays(harbour, 4, nov(4)) + plays(tide, 3, nov(5)) + plays(north, 2, nov(6)) + plays(kite, 1, nov(7))
        // October: The Lowlands' plays come after the 26th, so only all of October ranks them.
        val october = plays(harbour, 6, oct(3)) + plays(north, 5, oct(28)) + plays(copper, 2, oct(5))

        val cards = page(november + october).cards

        val songs = shown(cards.songs)
        assertEquals(true, songs.compared)
        assertEquals(listOf(Triple(copper, 3, copper), Triple(harbour, 1, harbour), Triple(tide, null, tide), Triple(north, 2, north), Triple(kite, null, kite)), songs.rows.map { Triple(it.top.item, it.before, it.cover) })
        assertEquals(listOf(5, 4, 3, 2, 1), songs.rows.map { it.top.plays })
        assertEquals(
            listOf(Triple("Kestrel", 3, copper), Triple("Mira Solen", 1, harbour), Triple("The Lowlands", 2, north), Triple("Bexley Rowe", null, kite)),
            shown(cards.artists).rows.map { Triple(it.top.item.name, it.before, it.cover) },
        )
        assertEquals(listOf(Triple("Low Sun", 3, copper), Triple("Tidelines", 1, harbour), Triple("Night Drive", 2, north)), shown(cards.albums).rows.map { Triple(it.top.item.title, it.before, it.cover) })
    }

    @Test
    fun `the top lists are not compared for all time, nor against a period before with under 10 plays`() {
        val plays = plays(copper, 5, nov(3)) + plays(harbour, 4, nov(4)) + plays(tide, 3, nov(5)) + plays(harbour, 9, oct(3))

        assertEquals(false, shown(page(plays).cards.songs).compared)
        assertEquals(listOf(null, null, null), shown(page(plays).cards.songs).rows.map { it.before })
        assertEquals(false, shown(page(plays + plays(north, 1, oct(4)), ListeningPeriod.ALL_TIME).cards.songs).compared)
        assertEquals(true, shown(page(plays + plays(north, 1, oct(4))).cards.songs).compared)
    }

    @Test
    fun `the top lists need 10 music plays that counted`() {
        val plays = plays(copper, 4, nov(3)) + plays(harbour, 3, nov(4)) + plays(north, 2, nov(5)) + plays(talk, 5, nov(6))

        assertNull(page(plays).cards.songs)
        assertNull(page(plays).cards.artists)
        assertNotNull(page(plays + plays(kite, 1, nov(7))).cards.songs)
    }

    @Test
    fun `the top artist's run counts the weeks or months in a row it was number 1`() {
        // Kestrel leads this week and the two before; Mira Solen led the week before those.
        val weeks = listOf(nov(24), nov(17), nov(10)).flatMap { plays(copper, 4, it) + plays(harbour, 3, it + HOUR) + plays(north, 3, it + 2 * HOUR) } +
            plays(harbour, 5, nov(3)) + plays(copper, 1, nov(3) + HOUR)

        assertEquals(Loyalty.Running(3), shown(page(weeks, ListeningPeriod.WEEK).cards.artists).loyalty)
        // November (Kestrel 16 to Mira Solen's 14) and October: two months.
        assertEquals(Loyalty.Running(2), shown(page(weeks + plays(copper, 3, nov(26, "08:00")) + plays(copper, 10, oct(5)), ListeningPeriod.MONTH).cards.artists).loyalty)
        // Number 1 for this week only: no run to tell.
        assertNull(shown(page(weeks.filter { it.startedAt >= nov(20) } + plays(harbour, 9, nov(18)), ListeningPeriod.WEEK).cards.artists).loyalty)
    }

    @Test
    fun `a year's number 1 counts its months, and all time's says since when it leads`() {
        val year = listOf(1, 2, 4).flatMap { plays(copper, 4, at("2026-%02d-10T12:00+03:00[Asia/Amman]".format(it))) } +
            plays(harbour, 5, at("2026-03-10T12:00+03:00[Asia/Amman]")) + plays(north, 2, nov(5))

        assertEquals(Loyalty.MonthsOf(3, 11), shown(page(year, ListeningPeriod.YEAR).cards.artists).loyalty)

        // Mira Solen leads in September, Kestrel overtakes her in October and stays ahead.
        val all = plays(harbour, 5, at("2026-09-10T12:00+03:00[Asia/Amman]")) + plays(copper, 10, oct(10)) + plays(harbour, 3, nov(5)) + plays(north, 2, nov(6))
        assertEquals(Loyalty.Since(YearMonth.of(2026, 10)), shown(page(all, ListeningPeriod.ALL_TIME).cards.artists).loyalty)
    }

    @Test
    fun `the top song's busiest day`() {
        val plays = plays(copper, 3, nov(10)) + plays(copper, 2, nov(11)) + plays(harbour, 4, nov(12)) + plays(north, 2, nov(13))

        assertEquals(DayPlays(copper, LocalDate.of(2026, 11, 10), 3), shown(page(plays).cards.songs).firstsDay)
    }

    // Your taste

    @Test
    fun `your taste has the genres by plays, the plays by decade with the empty ones between, and the middle year`() {
        val plays = plays(copper, 5, nov(3)) + plays(tide, 3, nov(4)) + plays(harbour, 4, nov(5)) + plays(north, 2, nov(6)) + plays(kite, 1, nov(7))

        val taste = shown(page(plays).cards.taste)

        assertEquals(listOf("Indie" to 8, "Folk" to 5, "Electronic" to 2), taste.genres.map { it.item.name to it.plays })
        assertEquals(listOf(Decade(1990, 1), Decade(2000, 2), Decade(2010, 12)), taste.decades)
        assertEquals(2014, taste.middleYear)
    }

    @Test
    fun `decades need 10 plays of songs with a year, and the genres 2 genres`() {
        val untagged = song("Music/Loose/No Tags.mp3", artist = "Bexley Rowe", id = 20)
        val library = Library.of(listOf(copper, harbour, untagged), Settings())
        val plays = plays(copper, 5, nov(3)) + plays(harbour, 4, nov(4)) + plays(untagged, 3, nov(5))

        val taste = shown(page(plays, library = library).cards.taste)
        assertEquals(emptyList<Decade>(), taste.decades)
        assertNull(taste.middleYear)
        assertEquals(2, taste.genres.size)
        assertNull(page(plays(copper, 12, nov(3)) + plays(tide, 1, nov(4)), library = Library.of(listOf(copper, tide), Settings())).cards.taste?.genres?.takeIf { it.isNotEmpty() })
    }

    // How you listen

    private fun started(play: LoggedPlay, by: StartedBy, source: SourceKind = SourceKind.SONGS, shuffled: Boolean = false, end: PlayEnd? = PlayEnd.FINISHED, endedAtMs: Long = 200_000) =
        play.copy(start = PlayStart(by, source, shuffled, songKey = null), end = end?.let { PlayEnded(it, endedAtMs) })

    @Test
    fun `how you listen sorts the plays by what started them and how they ended, quick skips too, older plays left out`() {
        val day = nov(10)
        val plays = listOf(
            started(play(copper, day), StartedBy.PICKED),
            started(play(tide, day + 1 * MINUTE), StartedBy.PICKED),
            started(play(lantern, day + 2 * MINUTE), StartedBy.PICKED, end = PlayEnd.STOPPED),
            // Low Sun in order: two to the end, one skipped at 10 s, one moved on from at a minute.
            started(play(copper, day + 3 * MINUTE), StartedBy.AUTO, SourceKind.ALBUM),
            started(play(tide, day + 4 * MINUTE), StartedBy.AUTO, SourceKind.ALBUM),
            started(play(lantern, day + 5 * MINUTE, heardMs = 10_000, counted = false), StartedBy.AUTO, SourceKind.ALBUM, end = PlayEnd.NEXT, endedAtMs = 10_000),
            started(play(signal, day + 6 * MINUTE, heardMs = 60_000, counted = false), StartedBy.AUTO, SourceKind.ALBUM, end = PlayEnd.NEXT, endedAtMs = 60_000),
            // Shuffle: one to the end, three skipped early, two of them quick skips the log keeps apart.
            started(play(harbour, day + 7 * MINUTE), StartedBy.AUTO, shuffled = true),
            started(play(glass, day + 8 * MINUTE, heardMs = 500, counted = false), StartedBy.AUTO, shuffled = true, end = PlayEnd.NEXT, endedAtMs = 500),
            started(play(glass, day + 9 * MINUTE, heardMs = 400, counted = false), StartedBy.AUTO, shuffled = true, end = PlayEnd.JUMP, endedAtMs = 400),
            started(play(glass, day + 10 * MINUTE, heardMs = 20_000, counted = false), StartedBy.AUTO, shuffled = true, end = PlayEnd.NEXT, endedAtMs = 20_000),
            started(play(glass, day + 11 * MINUTE), StartedBy.AUTO, shuffled = true),
            started(play(north, day + 12 * MINUTE), StartedBy.SUGGESTED),
            started(play(kite, day + 13 * MINUTE), StartedBy.AUTO, SourceKind.SUGGESTIONS),
            started(play(kite, day + 14 * MINUTE), StartedBy.PICKED, SourceKind.HUDDLE),
            // From before KJB kept how plays began.
            play(copper, nov(2)),
            play(tide, nov(2, "13:00")),
        )

        val how = shown(page(plays).cards.how)

        assertEquals(Starts(picked = 3, inOrder = 4, shuffle = 5, suggestions = 2, huddle = 1), how.started)
        assertEquals(Ends(finished = 9, movedOn = 1, early = 4), how.ends)
        assertEquals(Ends(finished = 2, movedOn = 0, early = 3), how.shuffled)
        assertEquals(Ends(finished = 2, movedOn = 1, early = 1), how.inOrder)
        assertEquals(Skipped(glass, early = 3, plays = 4), how.mostSkipped)
    }

    @Test
    fun `how you listen needs 10 plays that keep how they began, and a song skipped early 3 times to name one`() {
        val few = List(9) { started(play(copper, nov(10) + it * MINUTE), StartedBy.PICKED) }
        assertNull(page(few).cards.how)
        assertNull(shown(page(few + started(play(tide, nov(11)), StartedBy.PICKED, end = PlayEnd.NEXT, endedAtMs = 5_000)).cards.how).mostSkipped)
    }

    // Moments

    @Test
    fun `moments find the song on repeat, the longest session, the latest night and a year ago today`() {
        val plays = plays(copper, 4, nov(10, "09:00")) + plays(harbour, 3, nov(11, "09:00")) +
            // Twelve 4-minute plays back to back from 20:00: 48 minutes; then, after a break, three more.
            List(12) { play(if (it < 7) north else kite, nov(12, "20:00") + it * 4 * MINUTE, heardMs = 4 * MINUTE) } +
            List(3) { play(lantern, nov(12, "21:10") + it * 4 * MINUTE, heardMs = 4 * MINUTE) } +
            listOf(play(glass, nov(13, "23:30")), play(harbour, nov(14, "01:48")), play(tide, nov(14, "06:30"))) +
            plays(signal, 3, at("2025-11-26T12:00+03:00[Asia/Amman]"))

        val moments = page(plays).cards.moments

        assertEquals(
            listOf(
                Moment.OnRepeat(north, LocalDate.of(2026, 11, 12), 7),
                Moment.LongestSession(java.time.LocalDateTime.of(2026, 11, 12, 20, 0), 48 * MINUTE, north),
                Moment.LatestNight(java.time.LocalDateTime.of(2026, 11, 14, 1, 48), harbour),
                Moment.YearAgo(signal, LocalDate.of(2025, 11, 26), 3),
            ),
            moments,
        )
    }

    @Test
    fun `moments find albums played start to finish, time zones, and the time faster speeds saved`() {
        val inOrder = listOf(copper, tide, lantern, signal).mapIndexed { i, song -> started(play(song, nov(10) + i * 5 * MINUTE, zone = 180), StartedBy.AUTO, SourceKind.ALBUM) }
        // Low Sun again, a song skipped; Tidelines in order, but only two songs long.
        val broken = listOf(copper, tide, signal).mapIndexed { i, song -> started(play(song, nov(11) + i * 5 * MINUTE, zone = 180), StartedBy.AUTO, SourceKind.ALBUM) }
        val short = listOf(harbour, glass).mapIndexed { i, song -> started(play(song, nov(12) + i * 5 * MINUTE, zone = 60), StartedBy.AUTO, SourceKind.ALBUM) }
        val podcast = play(talk, nov(13), heardMs = 30 * MINUTE, clockMs = 20 * MINUTE)
        val book = bookPlay(nov(14), heardMs = 60 * MINUTE, clockMs = 40 * MINUTE)

        val moments = page(inOrder + broken + short + podcast, books = listOf(book)).cards.moments

        assertEquals(
            listOf(
                Moment.StartToFinish(1, checkNotNull(library.albums.firstOrNull { it.title == "Low Sun" })),
                Moment.OnTheMove(listOf(180, 60)),
                Moment.Faster(booksMs = 20 * MINUTE, podcastsMs = 10 * MINUTE),
            ),
            moments.filter { it is Moment.StartToFinish || it is Moment.OnTheMove || it is Moment.Faster },
        )
    }

    @Test
    fun `on the move needs a time zone other than the phone's own, never only its summer time`() {
        val berlin = ammanCalendar.copy(zone = ZoneId.of("Europe/Berlin"), weekend = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY))
        val home = listOf(play(copper, at("2026-07-10T12:00+02:00[Europe/Berlin]"), zone = 120), play(harbour, at("2026-11-10T12:00+01:00[Europe/Berlin]"), zone = 60))
        val away = play(north, at("2026-08-01T12:00+03:00[Asia/Amman]"), zone = 180)

        assertEquals(emptyList<Moment>(), page(home, ListeningPeriod.YEAR, calendar = berlin).cards.moments.filterIsInstance<Moment.OnTheMove>())
        // The phone's own time zone as it is now (UTC+1 in November), the most heard first.
        assertEquals(listOf(Moment.OnTheMove(listOf(60, 180))), page(home + away, ListeningPeriod.YEAR, calendar = berlin).cards.moments.filterIsInstance<Moment.OnTheMove>())
    }

    @Test
    fun `the longest session is the longest listening, never a shorter one shown in its place`() {
        // A book for an hour without a break, then 40 minutes of music on another day.
        val book = List(6) { bookPlay(nov(10, "08:00") + it * 10 * MINUTE, heardMs = 10 * MINUTE) }
        val music = List(10) { play(north, nov(12, "20:00") + it * 4 * MINUTE, heardMs = 4 * MINUTE) }

        assertEquals(emptyList<Moment>(), page(music, books = book).cards.moments.filterIsInstance<Moment.LongestSession>())
        assertEquals(
            listOf(Moment.LongestSession(java.time.LocalDateTime.of(2026, 11, 12, 20, 0), 40 * MINUTE, north)),
            page(music).cards.moments.filterIsInstance<Moment.LongestSession>(),
        )
    }

    @Test
    fun `all time begins with the first play, and a month over has no year ago today`() {
        val plays = listOf(play(north, at("2025-02-14T19:00+03:00[Asia/Amman]"))) + plays(signal, 3, at("2025-10-26T12:00+03:00[Asia/Amman]")) + plays(copper, 10, nov(3))

        assertEquals(Moment.FirstPlay(north, LocalDate.of(2025, 2, 14)), page(plays, ListeningPeriod.ALL_TIME).cards.moments.firstOrNull())
        assertEquals(emptyList<Moment>(), page(plays, ListeningPeriod.ALL_TIME).cards.moments.filterIsInstance<Moment.YearAgo>())
        assertEquals(emptyList<Moment>(), recap(plays + plays(copper, 10, oct(3)), YearMonth.of(2026, 10)).cards.moments.filterIsInstance<Moment.YearAgo>())
    }

    // New to you, back again, still quiet

    @Test
    fun `new to you has the artists first heard in the period, those kept, the new songs' share, and where the artists came from`() {
        val known = plays(copper, 2, oct(10)) + LoggedPlay(kite.ref, oct(1), 0, counted = true, imported = true)
        val november = plays(copper, 5, nov(2)) +
            listOf(started(play(harbour, nov(3)), StartedBy.SUGGESTED), play(harbour, nov(5)), play(harbour, nov(7))) +
            started(play(north, nov(4)), StartedBy.PICKED, SourceKind.HUDDLE) + play(tide, nov(6)) + play(kite, nov(8))

        val found = shown(page(known + november).cards.found)

        assertEquals(listOf(FoundArtist(checkNotNull(library.artist("Mira Solen")), kept = true), FoundArtist(checkNotNull(library.artist("The Lowlands")), kept = false)), found.artists)
        assertEquals(3, found.songs)
        assertEquals(5 to 11, found.newPlays to found.plays)
        assertEquals(Triple(1, 1, 0), Triple(found.fromSuggestions, found.fromFriends, found.onYourOwn))

        val all = shown(page(known + november, ListeningPeriod.ALL_TIME).cards.found)
        assertEquals(listOf("Kestrel", "Mira Solen", "Bexley Rowe", "The Lowlands"), all.artists.map { it.artist.name })
        assertEquals(5, all.songs)
    }

    @Test
    fun `back again has the songs played after 60 quiet days, the longest quiet first, and the favourites still quiet stay on the period pages`() {
        val plays = plays(tide, 5, at("2026-08-01T12:00+03:00[Asia/Amman]")) + play(tide, nov(10)) +
            play(harbour, at("2026-09-01T12:00+03:00[Asia/Amman]")) + play(harbour, nov(11)) +
            play(copper, oct(20)) + plays(copper, 9, nov(3)) +
            plays(north, 5, at("2026-07-01T12:00+03:00[Asia/Amman]")) + plays(kite, 5, at("2026-07-02T12:00+03:00[Asia/Amman]")) + plays(signal, 5, at("2026-07-03T12:00+03:00[Asia/Amman]"))

        val cards = page(plays).cards

        assertEquals(listOf(Comeback(tide, 101), Comeback(harbour, 71)), cards.comebacks)
        assertEquals(listOf(signal, kite, north), cards.forgotten.map { it.item })
        assertEquals(emptyList<Top<Song>>(), recap(plays, YearMonth.of(2026, 10)).cards.forgotten)
    }

    // Your library

    @Test
    fun `your library counts its music, what was ever played, what the period added, file types, never played and dig deeper`() {
        val library = Library.of(
            listOf(copper, tide.copy(addedAt = nov(20)), lantern, signal, harbour, glass.copy(addedAt = nov(21), mimeType = "audio/flac"), north, kite.copy(mimeType = "audio/flac"), talk),
            Settings(),
        )
        val plays = plays(copper, 12, nov(3)) + plays(harbour, 8, nov(4)) + play(glass, nov(22)) + LoggedPlay(kite.ref, oct(1), 0, counted = true, imported = true)
        val lowSun = checkNotNull(library.albums.firstOrNull { it.title == "Low Sun" })

        val card = shown(page(plays, library = library).cards.library)

        assertEquals(Triple(8, 8 * 4_000_000L, 8 * 200_000L), Triple(card.songs, card.bytes, card.durationMs))
        assertEquals(4, card.played)
        assertEquals(2 to 1, card.added to card.addedPlayed)
        assertEquals(listOf(FileType("audio/mpeg", 6), FileType("audio/flac", 2)), card.types)
        assertEquals(listOf(tide.path, north.path, signal.path, lantern.path), card.neverPlayed.map { it.path })
        assertEquals(listOf(DigDeeper(lowSun, copper, lowSun.songs.drop(1))), card.digDeeper)
        // All time: added in the last 30 days.
        assertEquals(2, shown(page(plays, ListeningPeriod.ALL_TIME, library = library).cards.library).added)
        assertNull(recap(plays + plays(copper, 10, oct(5)), YearMonth.of(2026, 10)).cards.library)
    }

    // Milestones and badges

    @Test
    fun `milestones have those reached in the period, the next one and when this month's pace reaches it, and the badges of all time`() {
        val october = (1..20).flatMap { day -> plays(copper, 3, oct(day)) }
        val november = (1..15).flatMap { day -> listOf(play(copper, nov(day)), play(harbour, nov(day, "13:00")), play(copper, nov(day, "14:00"))) }
        val hundredth = (october + november).sortedBy { it.startedAt }[99].startedAt

        val card = shown(page(october + november).cards.milestones)

        assertEquals(listOf(Milestone(MilestoneKind.PLAYS, 100, hundredth)), card.reached)
        assertEquals(NextMilestone(MilestoneKind.PLAYS, 250, 145), card.next)
        assertEquals(YearMonth.of(2027, 2), card.nextAround)
        assertEquals(Run(20, LocalDate.of(2026, 10, 1)), card.longestRun)
        assertEquals(Milestone(MilestoneKind.PLAYS, 100, hundredth), card.plays)
        assertNull(card.hours)
        assertEquals(MonthArtists(YearMonth.of(2026, 11), 2), card.artistsMonth)
        assertEquals(0, card.booksFinished)
    }

    // Your listening type

    @Test
    fun `your listening type has the three strongest words, each with its number`() {
        val night = List(10) { started(play(copper, nov(10 + it, "22:00")), StartedBy.AUTO, shuffled = true) }
        val day = List(5) { started(play(harbour, nov(10 + it, "10:00")), StartedBy.PICKED) }
        val album = List(5) { started(play(north, nov(10 + it, "12:00")), StartedBy.AUTO, SourceKind.ALBUM) }
        val kestrel = checkNotNull(library.artist("Kestrel"))

        assertEquals(
            listOf(Trait(TraitKind.EXPLORER, 100), Trait(TraitKind.LOYAL, 50, kestrel), Trait(TraitKind.SHUFFLER, 50)),
            page(night + day + album).cards.traits,
        )
        assertEquals(
            listOf(Trait(TraitKind.LOYAL, 50, kestrel), Trait(TraitKind.SHUFFLER, 50), Trait(TraitKind.NIGHT_OWL, 50)),
            page(night + day + album, ListeningPeriod.ALL_TIME).cards.traits,
        )
        assertEquals(emptyList<Trait>(), page(night.take(9)).cards.traits)
    }

    // Read along, new favourites, your searches

    private fun lyrics(song: Song, at: Long) = Interaction(at, InteractionKind.LYRICS, 180, Target(TargetKind.SONG, song.path))

    private fun search(words: String?, at: Long, target: Target? = null) = Interaction(at, InteractionKind.SEARCH, 180, target, words)

    @Test
    fun `read along counts the times you turned to lyrics, the song read most, and the plays of songs with synced lyrics`() {
        val plays = plays(copper, 5, nov(3)) + plays(harbour, 5, nov(4))
        val opened = listOf(lyrics(harbour, nov(4, "08:00")), lyrics(harbour, nov(5)), lyrics(copper, nov(6)), lyrics(harbour, nov(7)), lyrics(copper, oct(3)), lyrics(copper, oct(4)))

        val card = page(plays, interactions = opened, sources = PageSources(synced = setOf(harbour.path))).cards.lyrics

        assertEquals(LyricsCard(opened = 4, most = harbour, mostTimes = 3, syncedPlays = 5, plays = 10), card)
        assertNull(page(plays, interactions = opened.drop(4)).cards.lyrics)
    }

    @Test
    fun `your searches count the searches, the taps, the plays within a minute, the words and what was opened`() {
        val searches = listOf(
            search("Kestrel", nov(10, "10:00"), Target(TargetKind.ARTIST, "Kestrel")),
            search("harbour lights", nov(10, "11:00"), Target(TargetKind.SONG, harbour.path)),
            search("HARBOUR", nov(10, "12:00")),
            // Words removed from Recent searches: the search stays, its words are gone.
            search(null, nov(10, "13:00"), Target(TargetKind.ALBUM, "Low Sun")),
            search("kite", oct(3)),
        )
        val plays = listOf(play(copper, nov(10, "10:00") + 30_000), play(harbour, nov(10, "11:02")))

        val card = shown(page(plays, interactions = searches).cards.searches)

        assertEquals(
            SearchesCard(4, tapped = 3, played = 1, words = listOf(WordCount("harbour", 2), WordCount("kestrel", 1), WordCount("lights", 1)), songs = 1, artists = 1, albums = 1, others = 0),
            card,
        )
        assertNull(page(plays).cards.searches)
    }

    @Test
    fun `new favourites are the songs hearted in the period and the one played most, all time every favourite`() {
        val dates = mapOf(harbour.path to nov(5), copper.path to nov(6), tide.path to oct(3))
        val favourites = listOf(harbour.ref, copper.ref, tide.ref, kite.ref)
        val plays = plays(copper, 3, nov(7)) + plays(harbour, 2, nov(8)) + plays(tide, 9, oct(4))

        assertEquals(FavouritesCard(added = 2, mostPlayed = copper), page(plays, sources = PageSources(favouriteAdded = dates), favourites = favourites).cards.favourites)
        assertEquals(FavouritesCard(added = 4, mostPlayed = tide), page(plays, ListeningPeriod.ALL_TIME, sources = PageSources(favouriteAdded = dates), favourites = favourites).cards.favourites)
        assertNull(page(plays, ListeningPeriod.WEEK, sources = PageSources(favouriteAdded = dates), favourites = favourites).cards.favourites)
    }

    // Books and podcasts

    @Test
    fun `books and podcasts count real minutes, the time faster speeds saved, bookmarks made and books finished`() {
        val podcast = play(talk, nov(5), heardMs = 20 * MINUTE, clockMs = 10 * MINUTE)
        val book = bookPlay(nov(6), heardMs = 90 * MINUTE, clockMs = 60 * MINUTE)
        val sources = PageSources(bookmarks = listOf(nov(3), nov(4), oct(1)), finishedBooks = listOf(nov(10), oct(20)))
        // The book's part in the library, so its cover shows.
        val withBook = Library.of(listOf(copper, harbour, talk, song("Audiobooks/Salt Road/part 1.m4b", artist = "Wren Aster", album = "Salt Road", id = 30)), Settings())

        val page = page(listOf(podcast), books = listOf(book), sources = sources, library = withBook)

        assertEquals(
            SpokenCard(booksMs = 60 * MINUTE, podcastsMs = 10 * MINUTE, savedMs = 40 * MINUTE, bookmarks = 2, finished = 1, book = withBook.bookOf(saltRoad.path), podcast = talk),
            page.cards.spoken,
        )
        assertNotNull(withBook.bookOf(saltRoad.path))
        assertEquals(2, shown(page(plays(copper, 10, oct(5)) + plays(copper, 2, nov(3)), sources = sources).cards.milestones).booksFinished)
        assertNull(page(plays(copper, 3, nov(3)), sources = sources).cards.spoken)
    }

    // You and your friends

    private fun heard(friend: String, song: Song, at: Long) = FriendPlay(friend, song.title, song.artist, song.album, song.durationMs, at)

    private fun shared(card: String, friend: String, at: Long, sent: Boolean, song: Song) = SharedSong(card, friend, at, sent, song.title, song.artist, song.durationMs)

    @Test
    fun `you and your friends have the taste match by artists, past friends too, cards both ways, songs kept, in sync and the song sent you played most`() {
        val mine = plays(copper, 5, nov(3)) + play(copper, nov(10, "20:00")) + plays(harbour, 3, nov(4)) + play(harbour, nov(11, "10:00")) +
            play(north, nov(7)) + play(north, nov(8)) + play(glass, nov(9))
        val wren = List(5) { heard("f-wren", copper, nov(12) + it * HOUR) } + heard("f-wren", copper, nov(10, "20:30")) +
            List(3) { heard("f-wren", harbour, nov(13) + it * HOUR) } + heard("f-wren", harbour, nov(11, "12:00"))
        val omar = List(10) { heard("f-omar", north, nov(14) + it * HOUR) }
        val sources = PageSources(
            friends = listOf(FriendName("f-wren", "Wren", past = false), FriendName("f-omar", "Omar", past = true)),
            friendPlays = wren + omar,
            shared = listOf(
                shared("c1", "f-wren", nov(3), sent = true, kite),
                shared("c2", "f-omar", oct(20), sent = true, kite),
                shared("c3", "f-wren", nov(4), sent = false, north),
                shared("c4", "f-omar", nov(6), sent = false, glass),
            ),
        )

        val card = shown(page(mine, sources = sources).cards.friends)

        assertEquals(listOf(FriendMatch("f-wren", "Wren", past = false, percent = 85), FriendMatch("f-omar", "Omar", past = true, percent = 15)), card.matches)
        assertEquals(listOf("Kestrel", "Mira Solen"), card.both.map { it.name })
        assertEquals(Triple(1, 2, 1), Triple(card.sent, card.got, card.kept))
        assertEquals(InSync("f-wren", "Wren", copper, 1), card.inSync)
        assertEquals(SentKept("f-wren", "Wren", north, 2), card.favourite)
        assertNull(page(mine).cards.friends)
    }

    @Test
    fun `a card from a friend no longer listed is left out, never the whole page`() {
        val sources = PageSources(
            friends = listOf(FriendName("f-wren", "Wren", past = false)),
            shared = listOf(shared("c1", "f-wren", nov(3), sent = true, north), shared("c9", "f-gone", nov(4), sent = false, kite)),
        )

        val card = shown(page(plays(kite, 12, nov(5)), sources = sources).cards.friends)

        assertEquals(Triple(1, 0, 0), Triple(card.sent, card.got, card.kept))
        assertNull(card.favourite)
    }

    // Nothing to show

    @Test
    fun `an empty log and an empty library show no card, and without the library only your time is known`() {
        assertEquals(InsightsCards(), page(emptyList(), library = Library.of(emptyList(), Settings())).cards)

        val cards = page(plays(kite, 12, nov(3)), library = null).cards
        assertEquals(Totals(12 * 120_000L, 12, 0, 0, 0), shown(cards.time).totals)
        assertEquals(InsightsCards(time = cards.time), cards)
    }

    private companion object {
        const val MINUTE: Long = 60_000
        const val HOUR: Long = 3_600_000
    }
}
