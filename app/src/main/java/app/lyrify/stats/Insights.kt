package app.lyrify.stats

import app.lyrify.library.Album
import app.lyrify.library.Artist
import app.lyrify.library.Genre
import app.lyrify.library.Library
import app.lyrify.library.Song
import app.lyrify.library.SongRef
import app.lyrify.library.albumKey
import java.text.Collator
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset

// Insights (KJB 3.2, handoff/suggest/plan.md part 1): the figures behind the Insights page and the monthly recap, from
// the plays heard here ([PlayLogStore.listened]: an import's and the short ones left out, so neither adds to a count),
// the book log and the library. Time is in real minutes ([realMs]). Two ledgers, as Spotify keeps them: the time heard
// counts everything (books, podcasts, hidden songs, songs gone from the phone) and the plays everything but books, which
// never count one; the top lists and the songs, artists and albums counted are music only (no books, podcasts or hidden
// songs). Every card has a minimum before it shows. Pure, so the rules are tested on the JVM.

/**
 * What Insights are worked out from, all time: the [plays] heard here, the oldest first, the book log's ([books]), each
 * song's totals with an import's plays ([everPlayed]: what was ever played, here or in the app imported from), and the
 * [favourites].
 */
data class InsightsData(
    val plays: List<LoggedPlay>,
    val books: List<LoggedPlay> = emptyList(),
    val everPlayed: List<SongPlays> = emptyList(),
    val favourites: List<SongRef> = emptyList(),
)

/** A period's totals: the time heard and the plays that counted (both ledgers), and the songs, artists and albums played (music). */
data class Totals(val heardMs: Long, val plays: Int, val songs: Int, val artists: Int, val albums: Int)

/**
 * This period up to now ([heardMs], [plays]) against the one before up to the same point, or all of it once this one is
 * over ([beforeHeardMs], [beforePlays]).
 */
data class Versus(val heardMs: Long, val plays: Int, val beforeHeardMs: Long, val beforePlays: Int)

/** The time heard by the hour of the day the plays started in (24 of them, the local time then), and the hour heard most. */
data class ListeningClock(val hours: List<Long>, val peak: Int)

/** Days in a row with a play that counted: the longest run in the period, and the run up to today (null once the period is over). */
data class Streaks(val longest: Int, val current: Int?)

enum class MilestoneKind { PLAYS, HOURS }

/** The [count]th play that counted, or [count] hours heard, reached by the play that started [at]. */
data class Milestone(val kind: MilestoneKind, val count: Int, val at: Long)

/** The next milestone and how far off it is: plays, or ms heard. */
data class NextMilestone(val kind: MilestoneKind, val count: Int, val left: Long)

/** The milestones the period reached, the latest first, and the next one while the period goes on. */
data class Milestones(val reached: List<Milestone>, val next: NextMilestone?)

/** The time heard of books and of podcasts, their own small section. */
data class Spoken(val booksMs: Long, val podcastsMs: Long)

/** Dig deeper: [played], from [album], was played in the period, and these songs of it never were ([unplayed]). */
data class DigDeeper(val album: Album, val played: Song, val unplayed: List<Song>)

/**
 * Insights for one period, each card empty (or null) below its minimum: the [totals] (null: nothing in the period, the
 * empty state), the top lists, this period against the one before ([versus]), the [clock], the [streaks], the
 * [milestones], books and podcasts ([spoken]), and the nudges, which look at all time: the songs [neverPlayed], albums to
 * [digDeeper] into, and [forgotten] favourites.
 */
data class Insights(
    val totals: Totals?,
    val songs: List<Top<Song>> = emptyList(),
    val artists: List<Top<Artist>> = emptyList(),
    val albums: List<Top<Album>> = emptyList(),
    val genres: List<Top<Genre>> = emptyList(),
    val versus: Versus? = null,
    val clock: ListeningClock? = null,
    val streaks: Streaks? = null,
    val milestones: Milestones? = null,
    val spoken: Spoken? = null,
    val neverPlayed: List<Song> = emptyList(),
    val digDeeper: List<DigDeeper> = emptyList(),
    val forgotten: List<Top<Song>> = emptyList(),
)

/**
 * Insights for [period] (null: all time) as of [now], in the phone's [zone], from [data] joined with [library] now;
 * [previous] is the period before, for [Insights.versus]. With nothing heard in the period only the nudges show; without
 * a library (not read yet) only the totals are known.
 */
fun insightsOf(data: InsightsData, period: Period?, previous: Period?, now: Long, zone: ZoneId, library: Library?): Insights {
    val kinds = Kinds(library)
    val heard = data.plays.within(period)
    val ledger = ledgerOf(heard, data.books.within(period), kinds)
    val shows = ledger.heardMs >= MIN_HEARD_MS || ledger.plays > 0
    if (library == null) return Insights(Totals(ledger.heardMs, ledger.plays, 0, 0, 0).takeIf { shows })
    val ever = data.everPlayed.filter { it.count > 0 }
    val played = ever.mapNotNullTo(HashSet()) { library.resolver.find(it.song)?.path }
    val music = heard.filter { kinds.of(it) == Kind.MUSIC }
    val nudges = Insights(
        totals = null,
        neverPlayed = if (ever.sumOf { it.count.toLong() } < MIN_HISTORY_PLAYS) emptyList() else neverPlayedOf(library, played),
        digDeeper = digDeeperOf(music, library, played),
        forgotten = forgottenOf(data, now, library, kinds),
    )
    if (!shows) return nudges
    val tops = listeningOf(songPlaysOf(music), library)
    val enough = music.count { it.counted } >= MIN_TOP_PLAYS
    val going = period == null || now in period
    return nudges.copy(
        totals = Totals(ledger.heardMs, ledger.plays, tops.songs.size, tops.artists.size, tops.albums.size),
        songs = tops.songs.takeIf { enough && it.size >= MIN_TOP_SONGS }.orEmpty(),
        artists = tops.artists.takeIf { enough && it.size >= MIN_TOP_OTHERS }.orEmpty(),
        albums = tops.albums.takeIf { enough && it.size >= MIN_TOP_OTHERS }.orEmpty(),
        genres = genresOf(tops.songs, library).takeIf { enough && it.size >= MIN_TOP_OTHERS }.orEmpty(),
        versus = versusOf(ledger, data, period, previous, now, kinds),
        clock = clockOf(heard, zone),
        streaks = streaksOf(heard, data.plays, going, now, zone, kinds),
        milestones = milestonesOf(data, period, going, kinds),
        spoken = spokenOf(heard, data.books.within(period), kinds),
    )
}

/** The period before [bounds], one of [period]'s, in the phone's calendar ([zone], [firstDayOfWeek]); null for all time. */
fun previousPeriod(period: ListeningPeriod, bounds: Period?, zone: ZoneId, firstDayOfWeek: DayOfWeek): Period? =
    bounds?.let { periodOf(period, it.from - 1, zone, firstDayOfWeek) }

/** [month]'s bounds in the phone's calendar ([zone]). */
fun monthPeriod(month: YearMonth, zone: ZoneId): Period =
    checkNotNull(periodOf(ListeningPeriod.MONTH, month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli(), zone, DayOfWeek.MONDAY))

/** The month a recap is offered for on [now]'s day: the one before. */
fun recapMonthOf(now: Long, zone: ZoneId): YearMonth = YearMonth.from(dayOf(now, zone).date).minusMonths(1)

/**
 * The months with a recap, the latest first: those before [now]'s month with [RECAP_MIN_PLAYS] plays that counted, in
 * the phone's calendar ([zone]), as a recap's own totals count them: never a play of a file that is a book in [library]
 * now.
 */
fun recapMonths(plays: List<LoggedPlay>, now: Long, zone: ZoneId, library: Library?): List<YearMonth> {
    val current = YearMonth.from(dayOf(now, zone).date)
    val kinds = Kinds(library)
    return plays.filter { it.counted && kinds.of(it) != Kind.BOOK }
        .groupingBy { YearMonth.from(Instant.ofEpochMilli(it.startedAt).atZone(zone)) }
        .eachCount()
        .filter { (month, count) -> month < current && count >= RECAP_MIN_PLAYS }
        .keys
        .sortedDescending()
}

/** Whether the start screen shows [month]'s recap card: it has a recap ([months]) and the user has not opened or closed it ([seen]). */
fun recapDue(month: YearMonth, months: List<YearMonth>, seen: YearMonth?): Boolean = month in months && (seen == null || seen < month)

/** A month needs this many plays that counted for a recap. */
const val RECAP_MIN_PLAYS: Int = 10

// The minimums before a card shows

/** The totals: a minute heard, or one play that counted. */
internal const val MIN_HEARD_MS: Long = 60_000

/** The top lists: this many music plays that counted, of 3 songs or more, or 2 artists, albums or genres. */
internal const val MIN_TOP_PLAYS = 10
internal const val MIN_TOP_SONGS = 3
internal const val MIN_TOP_OTHERS = 2

/** This period against the one before: the one before needs this many plays, or this much time heard. */
internal const val MIN_VERSUS_PLAYS = 10
internal const val MIN_VERSUS_HEARD_MS: Long = 30 * 60_000

/** The clock: this many plays that counted in the period. */
internal const val MIN_CLOCK_PLAYS = 10

/** A streak: this many days in a row. */
internal const val MIN_STREAK_DAYS = 2

/** Books and podcasts: a minute heard. */
private const val MIN_SPOKEN_MS: Long = 60_000

/** Never played: this many plays that counted ever, here or imported, so a new phone is not told it never played anything. */
private const val MIN_HISTORY_PLAYS = 20

/** Dig deeper: an album of this many songs or more, this many never played; at most [DIG_MAX] albums. */
private const val DIG_MIN_ALBUM_SONGS = 4
private const val DIG_MIN_UNPLAYED = 2
private const val DIG_MAX = 3

/** Forgotten favourites: played this many times (or a favourite), none for [QUIET_MS]; [FORGOTTEN_MIN] to [FORGOTTEN_MAX] songs. */
private const val FORGOTTEN_MIN_PLAYS = 5
private const val QUIET_MS: Long = 60L * 24 * 3_600_000
private const val FORGOTTEN_MIN = 3
private const val FORGOTTEN_MAX = 10

/** The milestones: the plays that counted, and the hours heard. */
private val PLAY_MILESTONES = listOf(100, 250, 500, 1_000, 2_500, 5_000, 10_000, 25_000, 50_000, 100_000)
private val HOUR_MILESTONES = listOf(10, 25, 50, 100, 250, 500, 1_000, 2_500, 5_000, 10_000)

private const val HOUR_MS: Long = 3_600_000
internal const val HOURS_IN_DAY = 24

// How each card is worked out

/** A play's real minutes: the clock's time it played ([LoggedPlay.clockMs]), or its time heard for a play from before KJB kept them. */
internal val LoggedPlay.realMs: Long get() = clockMs ?: heardMs

/** What a play's song is now: music the lists show, a podcast, a book, or other (a hidden song, one gone from the phone). */
internal enum class Kind { MUSIC, PODCAST, BOOK, OTHER }

/** Each play's [Kind], by its song found again in [library]; once per song. */
internal class Kinds(private val library: Library?) {
    private val known = HashMap<String, Kind>()

    fun of(play: LoggedPlay): Kind = known.getOrPut(play.song.path) {
        val library = library ?: return@getOrPut Kind.OTHER
        val song = library.resolver.find(play.song)
        when {
            library.bookOf(song?.path ?: play.song.path) != null -> Kind.BOOK
            song?.isPodcast == true -> Kind.PODCAST
            song != null && library.shows(song) -> Kind.MUSIC
            else -> Kind.OTHER
        }
    }
}

/** The time heard (books' too) and the plays that counted (never a book's). */
internal class Ledger(val heardMs: Long, val plays: Int)

internal fun ledgerOf(heard: List<LoggedPlay>, books: List<LoggedPlay>, kinds: Kinds): Ledger =
    Ledger(heard.sumOf { it.realMs } + books.sumOf { it.realMs }, heard.count { it.counted && kinds.of(it) != Kind.BOOK })

internal operator fun Period.contains(at: Long): Boolean = at >= from && at < until

internal fun List<LoggedPlay>.within(period: Period?): List<LoggedPlay> = if (period == null) this else filter { it.startedAt in period }

/** [plays] per song, as the log's totals give them: under the latest play's song, so its id hint is the latest. */
internal fun songPlaysOf(plays: List<LoggedPlay>): List<SongPlays> = plays.groupBy { it.song.path }.map { (_, its) ->
    SongPlays(its.maxBy { it.startedAt }.song, its.count { it.counted }, its.filter { it.counted }.maxOfOrNull { it.startedAt }, its.sumOf { it.realMs })
}

/** The genres of the top [songs], each song's plays under its genre tag, the most played first. */
private fun genresOf(songs: List<Top<Song>>, library: Library): List<Top<Genre>> {
    val collator = Collator.getInstance()
    return songs.mapNotNull { top -> top.item.genre?.trim()?.takeIf(String::isNotEmpty)?.let(library::genre)?.let { top to it } }
        .groupBy { it.second.name }
        .map { (_, its) -> sum(its.first().second, its.map { it.first }) }
        .sortedWith(topOrder<Genre>().thenBy(collator) { it.item.name })
}

/** The period so far against the one before up to the same point (all of it once the period is over). */
private fun versusOf(ledger: Ledger, data: InsightsData, period: Period?, previous: Period?, now: Long, kinds: Kinds): Versus? {
    if (period == null || previous == null) return null
    val window = if (now in period) Period(previous.from, minOf(previous.until, previous.from + (now - period.from))) else previous
    val before = ledgerOf(data.plays.within(window), data.books.within(window), kinds)
    if (before.plays < MIN_VERSUS_PLAYS && before.heardMs < MIN_VERSUS_HEARD_MS) return null
    return Versus(ledger.heardMs, ledger.plays, before.heardMs, before.plays)
}

private fun clockOf(heard: List<LoggedPlay>, zone: ZoneId): ListeningClock? {
    if (heard.count { it.counted } < MIN_CLOCK_PLAYS) return null
    val hours = LongArray(HOURS_IN_DAY)
    heard.forEach { hours[localTimeOf(it, zone).hour] += it.realMs }
    return ListeningClock(hours.toList(), hours.indices.maxBy { hours[it] })
}

/** When [play] started where it was heard: in its time zone then, else the phone's now (plays from before KJB kept it). */
internal fun localTimeOf(play: LoggedPlay, zone: ZoneId): LocalDateTime {
    val start = Instant.ofEpochMilli(play.startedAt)
    return LocalDateTime.ofInstant(start, play.zoneOffsetMinutes?.let { ZoneOffset.ofTotalSeconds(it * SECONDS_PER_MINUTE) } ?: zone)
}

private const val SECONDS_PER_MINUTE = 60

/** The longest run of days with a play that counted in the period ([heard]), and while it [going] the run up to today from [all]. */
private fun streaksOf(heard: List<LoggedPlay>, all: List<LoggedPlay>, going: Boolean, now: Long, zone: ZoneId, kinds: Kinds): Streaks? {
    fun days(plays: List<LoggedPlay>): Set<LocalDate> = plays.filter { it.counted && kinds.of(it) != Kind.BOOK }.mapTo(HashSet()) { localTimeOf(it, zone).toLocalDate() }
    val longest = days(heard).let { days -> days.maxOfOrNull { day -> if (day.minusDays(1) in days) 0 else runFrom(day, days, step = 1) } ?: 0 }
    val current = if (going) {
        val ever = days(all)
        val today = dayOf(now, zone).date
        // From today or yesterday; or from tomorrow, a day a play heard in a time zone ahead of the phone's is on already.
        listOf(today.plusDays(1), today, today.minusDays(1)).filter { it in ever }.maxOfOrNull { runFrom(it, ever, step = -1) } ?: 0
    } else {
        null
    }
    return Streaks(longest, current).takeIf { longest >= MIN_STREAK_DAYS || (current ?: 0) >= MIN_STREAK_DAYS }
}

/** How many days of [days] follow one another from [start], [step] days at a time. */
internal fun runFrom(start: LocalDate, days: Set<LocalDate>, step: Long): Int = generateSequence(start) { it.plusDays(step) }.takeWhile { it in days }.count()

/**
 * The milestones reached in [period] (all of them for all time), the latest first, each by the play that crossed it:
 * the plays that counted (never a book's) and the hours heard (books' too); and while it [going] the next one. Shown
 * once one was reached in the period, or while it goes on once one was ever reached.
 */
private fun milestonesOf(data: InsightsData, period: Period?, going: Boolean, kinds: Kinds): Milestones? {
    val counted = data.plays.filter { it.counted && kinds.of(it) != Kind.BOOK }
    val reached = PLAY_MILESTONES.mapNotNull { count -> counted.getOrNull(count - 1)?.let { Milestone(MilestoneKind.PLAYS, count, it.startedAt) } }.toMutableList()
    var heardMs = 0L
    (data.plays + data.books).sortedBy { it.startedAt }.forEach { play ->
        val before = heardMs
        heardMs += play.realMs
        HOUR_MILESTONES.filter { it * HOUR_MS in (before + 1)..heardMs }.forEach { reached += Milestone(MilestoneKind.HOURS, it, play.startedAt) }
    }
    val inPeriod = reached.filter { period == null || it.at in period }.sortedWith(compareByDescending<Milestone> { it.at }.thenBy { it.kind })
    val next = if (going) nextMilestone(counted.size, heardMs) else null
    return when {
        inPeriod.isNotEmpty() -> Milestones(inPeriod, next)
        going && reached.isNotEmpty() -> Milestones(emptyList(), next)
        else -> null
    }
}

/** The next milestone of either kind, the one closer as a share of it. */
private fun nextMilestone(plays: Int, heardMs: Long): NextMilestone? {
    val byPlays = PLAY_MILESTONES.firstOrNull { it > plays }?.let { NextMilestone(MilestoneKind.PLAYS, it, (it - plays).toLong()) }
    val byHours = HOUR_MILESTONES.firstOrNull { it * HOUR_MS > heardMs }?.let { NextMilestone(MilestoneKind.HOURS, it, it * HOUR_MS - heardMs) }
    return listOfNotNull(byPlays, byHours).minByOrNull { next -> next.left.toDouble() / (if (next.kind == MilestoneKind.PLAYS) next.count.toDouble() else next.count * HOUR_MS.toDouble()) }
}

/** Books (the book log, and plays logged before their file became a book) and podcasts, from a minute heard. */
private fun spokenOf(heard: List<LoggedPlay>, books: List<LoggedPlay>, kinds: Kinds): Spoken? {
    val booksMs = books.sumOf { it.realMs } + heard.filter { kinds.of(it) == Kind.BOOK }.sumOf { it.realMs }
    val podcastsMs = heard.filter { kinds.of(it) == Kind.PODCAST }.sumOf { it.realMs }
    return Spoken(booksMs, podcastsMs).takeIf { booksMs + podcastsMs >= MIN_SPOKEN_MS }
}

/** The library's music no play counted for ([played]: here or imported), the newest added first. */
private fun neverPlayedOf(library: Library, played: Set<String>): List<Song> {
    val collator = Collator.getInstance()
    return library.songs.filter { !it.isPodcast && it.path !in played }
        .sortedWith(compareByDescending<Song> { it.addedAt ?: Long.MIN_VALUE }.thenBy(collator) { it.title }.thenBy { it.path })
}

/**
 * The albums of the [music] played in the period, with their songs never played ([played]: here or imported), the most
 * played album first, then the latest played; each with its song played most there.
 */
private fun digDeeperOf(music: List<LoggedPlay>, library: Library, played: Set<String>): List<DigDeeper> {
    class Heard(val album: Album, val song: Song, val at: Long)
    val heard = music.filter { it.counted }.mapNotNull { play ->
        val song = library.resolver.find(play.song) ?: return@mapNotNull null
        albumKey(song)?.let(library::album)?.let { Heard(it, song, play.startedAt) }
    }
    return heard.groupBy { it.album.key }.values
        .mapNotNull { its ->
            val album = its.first().album
            val unplayed = album.songs.filter { !it.isPodcast && it.path !in played }
            if (album.songs.size < DIG_MIN_ALBUM_SONGS || unplayed.size < DIG_MIN_UNPLAYED) return@mapNotNull null
            val most = its.groupBy { it.song.path }.values.maxWith(compareBy<List<Heard>> { it.size }.thenBy { song -> song.maxOf { it.at } })
            Triple(DigDeeper(album, most.first().song, unplayed), its.size, its.maxOf { it.at })
        }
        .sortedWith(compareByDescending<Triple<DigDeeper, Int, Long>> { it.second }.thenByDescending { it.third })
        .take(DIG_MAX)
        .map { it.first }
}

/** Music played [FORGOTTEN_MIN_PLAYS] times or a favourite, with no play that counted for [QUIET_MS], the most played first. */
private fun forgottenOf(data: InsightsData, now: Long, library: Library, kinds: Kinds): List<Top<Song>> {
    val favourites = data.favourites.mapNotNullTo(HashSet()) { library.resolver.find(it)?.path }
    val quiet = now - QUIET_MS
    return listeningOf(songPlaysOf(data.plays.filter { kinds.of(it) == Kind.MUSIC }), library).songs
        .filter { top -> top.lastPlayedAt?.let { it <= quiet } == true && (top.plays >= FORGOTTEN_MIN_PLAYS || top.item.path in favourites) }
        .take(FORGOTTEN_MAX)
        .takeIf { it.size >= FORGOTTEN_MIN }
        .orEmpty()
}
