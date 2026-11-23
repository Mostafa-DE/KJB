package app.lyrify.stats

import app.lyrify.library.Library
import app.lyrify.library.Song
import app.lyrify.library.SongRef
import app.lyrify.library.VOLUME
import app.lyrify.library.song
import app.lyrify.settings.Settings
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
    ): InsightsPage = runBlocking { stats(plays, books, library).page(period, now, calendar) }

    private fun recap(plays: List<LoggedPlay>, month: YearMonth, calendar: PhoneCalendar = ammanCalendar): InsightsPage =
        runBlocking { stats(plays, emptyList(), library).page(month, now, calendar) }

    private fun stats(plays: List<LoggedPlay>, books: List<LoggedPlay>, library: Library?) =
        PlayStats(FakePlayLog(plays.sortedBy { it.startedAt }), { library }, Dispatchers.Unconfined, books = FakePlayLog(books))

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
