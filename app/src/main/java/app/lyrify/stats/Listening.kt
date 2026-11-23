package app.lyrify.stats

import app.lyrify.library.Album
import app.lyrify.library.Artist
import app.lyrify.library.Library
import app.lyrify.library.Song
import app.lyrify.library.SongRef
import app.lyrify.library.albumKey
import app.lyrify.settings.ListPeriod
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.Collator
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.atomic.AtomicLong

/** Your listening's periods: this week, this month, this year, or all time, in the phone's own calendar. */
enum class ListeningPeriod { WEEK, MONTH, YEAR, ALL_TIME }

/** A period's bounds in wall-clock millis: from (inclusive) until (exclusive). */
data class Period(val from: Long, val until: Long)

/**
 * [period]'s bounds as of [now], in the phone's calendar ([zone], [firstDayOfWeek]): from the first moment of the week's
 * first day, the month's first or the year's first (midnight, or that day's first hour where a clock change skips
 * midnight) until the first moment of the next one, so a week across a clock change has 167 or 169 hours. Null for all
 * time. Plays count in the period they started in.
 */
fun periodOf(period: ListeningPeriod, now: Long, zone: ZoneId, firstDayOfWeek: DayOfWeek): Period? {
    val today = dateOf(now, zone)
    val first = when (period) {
        ListeningPeriod.WEEK -> today.with(TemporalAdjusters.previousOrSame(firstDayOfWeek))
        ListeningPeriod.MONTH -> today.withDayOfMonth(1)
        ListeningPeriod.YEAR -> today.withDayOfYear(1)
        ListeningPeriod.ALL_TIME -> return null
    }
    val next = when (period) {
        ListeningPeriod.WEEK -> first.plusWeeks(1)
        ListeningPeriod.MONTH -> first.plusMonths(1)
        else -> first.plusYears(1)
    }
    return Period(startOf(first, zone), startOf(next, zone))
}

private fun startOf(day: LocalDate, zone: ZoneId): Long = day.atStartOfDay(zone).toInstant().toEpochMilli()

/**
 * Where a list's [period] chip starts as of [now] (Recently added, Recently played, Most played): the first moment of the
 * earliest of its days in the phone's calendar ([zone]), today one of them, as the Songs tab counts its last 30 days
 * (midnight, or that day's first hour where a clock change skips midnight). It has no end, so a song added or played
 * "later" than now (the clock was changed) is in it. Null for all time.
 */
fun sinceOf(period: ListPeriod, now: Long, zone: ZoneId): Long? = period.days?.let { startOf(dateOf(now, zone).minusDays(it - 1L), zone) }

/** A day of the phone's calendar: its [date], and the first moment of the next day ([nextStart], wall-clock millis). */
data class Day(val date: LocalDate, val nextStart: Long)

/**
 * The day [now] falls on in [zone], and when the next one starts (midnight, or that day's first hour where a clock change
 * skips midnight): where Your listening's periods may turn, so a page left open reads again then.
 */
fun dayOf(now: Long, zone: ZoneId): Day {
    val date = dateOf(now, zone)
    return Day(date, startOf(date.plusDays(1), zone))
}

/** One row of a top list: its plays that counted, the time heard in all its plays, and the last play that counted. */
data class Top<T>(val item: T, val plays: Int, val heardMs: Long, val lastPlayedAt: Long?)

/**
 * Your listening for one period: the time heard and the plays that counted, songs gone from the phone included and
 * songs the library hides left out, then the top songs, artists and albums of the library, the most played first.
 */
data class Listening(val heardMs: Long, val plays: Int, val songs: List<Top<Song>>, val artists: List<Top<Artist>>, val albums: List<Top<Album>>)

/**
 * Your listening from a period's [totals], joined with [library] now, so a song moved or renamed keeps its plays (found
 * again by its path, then its id hint) and artists and albums follow today's tags. Songs gone leave the top lists, songs
 * hidden the totals too, and books every figure ([withoutBooks]); songs only heard (no play counted) add time but no
 * row. Ties go to the latest played, then by name, then by path or key, so the order never changes between two looks. Without a library (not read yet) only the totals are known.
 */
fun listeningOf(totals: List<SongPlays>, library: Library?): Listening {
    // The headline counts what the lists below it may show: a song the library's filters hide (Hidden songs, Folders to
    // skip, short clips) is left out, while one gone from the phone stays, as its listening happened. Books never count.
    val shown = if (library == null) totals else totals.filter { total -> library.resolver.find(total.song)?.let(library::shows) != false }
    val counted = withoutBooks(shown, library)
    val heard = counted.sumOf { it.heardMs }
    val plays = counted.sumOf { it.count.toLong() }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    if (library == null) return Listening(heard, plays, emptyList(), emptyList(), emptyList())
    val collator = Collator.getInstance()
    val songs = bySong(counted, library, shownOnly = true)
    // A song of several artists counts for each ("Artists with several names").
    val artists = songs.flatMap { top -> library.artistsOf(top.item).map { top to it } }
        .groupBy { it.second.name }
        .map { (_, its) -> sum(its.first().second, its.map { it.first }) }
    val albums = songs.mapNotNull { top -> albumKey(top.item)?.let(library::album)?.let { top to it } }
        .groupBy { it.second.key }
        .map { (_, its) -> sum(its.first().second, its.map { it.first }) }
    return Listening(
        heardMs = heard,
        plays = plays,
        songs = songs.filter { it.plays > 0 }.sortedWith(topOrder<Song>().thenBy(collator) { it.item.title }.thenBy { it.item.path }),
        artists = artists.filter { it.plays > 0 }.sortedWith(topOrder<Artist>().thenBy(collator) { it.item.name }.thenBy { it.item.name }),
        albums = albums.filter { it.plays > 0 }.sortedWith(topOrder<Album>().thenBy(collator) { it.item.title }.thenBy { it.item.key }),
    )
}

/**
 * [totals] without the plays of files that are books now (books/): a book's plays never count, also those from before
 * it was a book; marked "Not a book", its plays are back.
 */
private fun withoutBooks(totals: List<SongPlays>, library: Library?): List<SongPlays> =
    if (library == null || library.books.isEmpty()) totals else totals.filter { total -> library.bookOf(library.resolver.find(total.song)?.path ?: total.song.path) == null }

/** The most plays first, then the latest played. */
internal fun <T> topOrder(): Comparator<Top<T>> = compareByDescending<Top<T>> { it.plays }.thenByDescending { it.lastPlayedAt ?: Long.MIN_VALUE }

/** [item]'s row: the plays and time of [tops] added up, and the latest of their last plays. */
internal fun <T> sum(item: T, tops: List<Top<*>>): Top<T> =
    Top(item, tops.sumOf { it.plays }, tops.sumOf { it.heardMs }, tops.mapNotNull { it.lastPlayedAt }.maxOrNull())

/**
 * [totals] per song of [library] now: each total's song found again (path, then id hint), those naming the same song
 * added up. [shownOnly]: songs the library's filters hide are left out too.
 */
private fun bySong(totals: List<SongPlays>, library: Library, shownOnly: Boolean): List<Top<Song>> =
    totals.mapNotNull { total -> library.resolver.find(total.song)?.takeIf { !shownOnly || library.shows(it) }?.let { it to total } }
        .groupBy { it.first.path }
        .map { (_, its) ->
            val counted = its.map { it.second }
            Top(its.first().first, counted.sumOf { it.count }, counted.sumOf { it.heardMs }, counted.mapNotNull { it.lastPlayedAt }.maxOrNull())
        }

/**
 * Plays per song from [store], joined with the [library] of the moment: Most played, Recently played and Song info
 * ([PlaysQuery]), and Insights with the book log ([books]) and the [favourites]. Sorting and joining run on [compute].
 * Most played and Recently played over a period's chip read Insights' copy of the log (below), so they count as Insights
 * do; over all time the log's own totals, as they always did.
 *
 * Insights keep their own copy of the logs between reads, as a heavy listener's history reaches 100,000 plays (lead,
 * 2026-11-22): read whole the first time and after a restore or an import, else only from the earliest play written
 * since ([changed], [booksChanged]: its row may have changed, and the plays after it), spliced in. A period that is over
 * is worked out once and again only when a play in it or before it is written, or the library or Favourites change.
 */
class PlayStats(
    private val store: PlayLogStore,
    private val library: () -> Library?,
    private val compute: CoroutineDispatcher = Dispatchers.Default,
    private val books: PlayLogStore? = null,
    private val favourites: suspend () -> List<SongRef> = { emptyList() },
) : PlaysQuery {
    /** The library's songs played most since [since] ([totals]), under their paths now; ties as in [listeningOf]. */
    override suspend fun mostPlayed(limit: Int, since: Long?): List<SongPlays> {
        val totals = totals(since)
        return withContext(compute) { listeningOf(totals, library()).songs.filterNot { it.item.isPodcast }.take(limit).map(::songPlays) }
    }

    /** The library's songs whose last play that counted since [since] ([totals]) is the latest, the latest first. */
    override suspend fun recentlyPlayed(limit: Int, since: Long?): List<SongPlays> {
        val totals = totals(since)
        return withContext(compute) {
            val library = library() ?: return@withContext emptyList()
            bySong(totals, library, shownOnly = true).filter { it.lastPlayedAt != null && !it.item.isPodcast }
                .sortedWith(compareByDescending<Top<Song>> { it.lastPlayedAt }.thenBy { it.item.path })
                .take(limit)
                .map(::songPlays)
        }
    }

    /**
     * Each song's totals for Most played and Recently played: all time, the log's own (an import's plays too); from
     * [since] on, those Insights count, from their copy of the plays heard here (an import's and the skips left out).
     */
    private suspend fun totals(since: Long?): List<SongPlays> {
        if (since == null) return store.totals()
        val heard = logs()?.plays.orEmpty()
        return withContext(compute) { songPlaysOf(heard.filter { it.startedAt >= since }) }
    }

    /** [song]'s plays, hidden or not, those under an old path included; null until one counted. */
    override suspend fun plays(song: SongRef): SongPlays? {
        val totals = store.totals()
        return withContext(compute) {
            val library = library()
            if (library?.bookOf(song.path) != null) return@withContext null
            val mine = if (library == null) {
                totals.filter { it.song.path == song.path }.map { Top(song, it.count, it.heardMs, it.lastPlayedAt) }
            } else {
                bySong(totals, library, shownOnly = false).filter { it.item.path == song.path }.map { Top(song, it.plays, it.heardMs, it.lastPlayedAt) }
            }
            mine.singleOrNull()?.takeIf { it.plays > 0 }?.let { SongPlays(song, it.plays, it.lastPlayedAt, it.heardMs) }
        }
    }

    /** Every song played, hidden or not, under its path in the library now. */
    override suspend fun everySong(): Map<String, SongPlays> {
        val totals = store.totals()
        return withContext(compute) {
            val library = library() ?: return@withContext emptyMap()
            bySong(totals, library, shownOnly = false).associate { it.item.path to songPlays(it) }
        }
    }

    /** Insights for [period] as of [now], in the phone's [zone] and with its [firstDayOfWeek], set against the period before. */
    suspend fun insights(period: ListeningPeriod, now: Long, zone: ZoneId, firstDayOfWeek: DayOfWeek): Insights {
        val bounds = periodOf(period, now, zone, firstDayOfWeek)
        return insights(bounds, previousPeriod(period, bounds, zone, firstDayOfWeek), now, zone)
    }

    /** [month]'s recap: its insights, set against the month before. */
    suspend fun recap(month: YearMonth, now: Long, zone: ZoneId): Insights = insights(monthPeriod(month, zone), monthPeriod(month.minusMonths(1), zone), now, zone)

    /** The Insights page of [period] as of [now], in the phone's [calendar] ([phoneCalendar]), set against the period before. */
    suspend fun page(period: ListeningPeriod, now: Long, calendar: PhoneCalendar): InsightsPage {
        val bounds = periodOf(period, now, calendar.zone, calendar.firstDayOfWeek)
        return page(period, bounds, previousPeriod(period, bounds, calendar.zone, calendar.firstDayOfWeek), now, calendar)
    }

    /** [month]'s recap as a page (its story), set against the month before. */
    suspend fun page(month: YearMonth, now: Long, calendar: PhoneCalendar): InsightsPage =
        page(ListeningPeriod.MONTH, monthPeriod(month, calendar.zone), monthPeriod(month.minusMonths(1), calendar.zone), now, calendar)

    private suspend fun page(kind: ListeningPeriod, bounds: Period?, before: Period?, now: Long, calendar: PhoneCalendar): InsightsPage {
        val logs = logs()
        val library = library()
        return withContext(compute) { insightsPageOf(logs ?: InsightsData(emptyList()), kind, bounds, before, now, calendar, library) }
    }

    /** The months with a recap ([recapMonths]), the latest first. */
    suspend fun recaps(now: Long, zone: ZoneId): List<YearMonth> {
        val plays = logs()?.plays.orEmpty()
        return withContext(compute) { recapMonths(plays, now, zone, library()) }
    }

    /**
     * A play of [startedAt] written to the play log, or (null) many at once: a restore, an import or its Undo. Called by
     * the log itself ([WatchedPlayLog]), on any thread.
     */
    fun changed(startedAt: Long?) = mark(musicSince, startedAt)

    /** The same for the book log. */
    fun booksChanged(startedAt: Long?) = mark(booksSince, startedAt)

    private fun mark(since: AtomicLong, startedAt: Long?) {
        since.accumulateAndGet(startedAt ?: EVERY, ::minOf)
    }

    /**
     * Insights for [bounds] against [previous]. A period that is over keeps only its own figures (the nudges look at now
     * and all time, so they are the page's, never a recap's), which is what makes it safe to keep.
     */
    private suspend fun insights(bounds: Period?, previous: Period?, now: Long, zone: ZoneId): Insights {
        val logs = logs()
        val library = library()
        val favourites = favourites()
        val stamp = library to favourites
        val over = bounds?.takeIf { it.until <= now }
        held.withLock {
            if (overFor != stamp) {
                worked.clear()
                overFor = stamp
            }
            over?.let { worked[it to previous] }?.let { return it }
        }
        val insights = withContext(compute) {
            val all = insightsOf((logs ?: InsightsData(emptyList())).copy(favourites = favourites), bounds, previous, now, zone, library)
            if (over == null) all else all.copy(neverPlayed = emptyList(), digDeeper = emptyList(), forgotten = emptyList())
        }
        // A log that could not be read gives nothing, worked out again next time.
        if (over != null && logs != null) held.withLock { if (overFor == stamp) worked[over to previous] = insights }
        return insights
    }

    /**
     * The logs as Insights read them, kept between reads (see the class), with the periods over that a write touched let
     * go; null while the play log cannot be read (read whole again next time).
     */
    private suspend fun logs(): InsightsData? = held.withLock {
        val musicFrom = musicSince.getAndSet(NONE)
        val booksFrom = booksSince.getAndSet(NONE)
        val music = music
        this.music = when {
            music == null || musicFrom == EVERY -> store.all()?.let { all ->
                imports = slim(all.filter { it.imported })
                slim(all.filter { !it.imported && !it.isShort })
            }
            musicFrom == NONE -> music
            else -> spliced(music, musicFrom, store.listened(from = musicFrom))
        }
        val bookPlays = this.bookPlays
        this.bookPlays = when {
            books == null -> emptyList()
            bookPlays == null || booksFrom == EVERY -> books.all()?.let { all -> slim(all.filter { !it.isShort }) }
            booksFrom == NONE -> bookPlays
            else -> spliced(bookPlays, booksFrom, books.listened(from = booksFrom))
        }
        val touched = minOf(musicFrom, booksFrom)
        if (touched != NONE) worked.keys.removeAll { (period, _) -> touched < period.until }
        // A log not read (or a read cut short) stays null, so the next read is whole.
        val read = this.music ?: return@withLock null
        if (read !== music) ever = songPlaysOf(imports + read)
        InsightsData(read, this.bookPlays.orEmpty(), ever)
    }

    /**
     * [kept] with its plays from [from] on as [read] gives them now; none when the read came short (a play the copy has
     * from there on is missing: a row is never taken out but by an import's Undo, which reads all), to read whole.
     */
    private fun spliced(kept: List<LoggedPlay>, from: Long, read: List<LoggedPlay>): List<LoggedPlay>? {
        val before = kept.filter { it.startedAt < from }
        return if (read.size < kept.size - before.size) null else before + slim(read)
    }

    /** Only what Insights use of [plays] (how each began and ended is not kept), each song's reference once: 100,000 plays stay a few MB. */
    private fun slim(plays: List<LoggedPlay>): List<LoggedPlay> = plays.map { it.copy(song = refs.getOrPut(it.song) { it.song }, start = null, end = null) }

    /** Insights' copy of the logs and the periods over already worked out, by their bounds and the period before. */
    private val held = Mutex()
    private var music: List<LoggedPlay>? = null
    private var imports: List<LoggedPlay> = emptyList()
    private var bookPlays: List<LoggedPlay>? = null
    private var ever: List<SongPlays> = emptyList()
    private val refs = HashMap<SongRef, SongRef>()
    private val worked = HashMap<Pair<Period, Period?>, Insights>()
    private var overFor: Pair<Library?, List<SongRef>>? = null

    /** The earliest start among the plays written since Insights last read each log: [NONE] for none, [EVERY] for a restore or an import. */
    private val musicSince = AtomicLong(NONE)
    private val booksSince = AtomicLong(NONE)

    private fun songPlays(top: Top<Song>): SongPlays = SongPlays(top.item.ref, top.plays, top.lastPlayedAt, top.heardMs)

    private companion object {
        const val NONE: Long = Long.MAX_VALUE
        const val EVERY: Long = Long.MIN_VALUE
    }
}

/** When a song was last played, as Song info and Recently played say it. */
sealed interface WhenPlayed {
    data object Never : WhenPlayed

    /** Today, at [at]'s time. */
    data class Today(val at: Long) : WhenPlayed

    data object Yesterday : WhenPlayed

    /** 2 to 6 days ago. */
    data class DaysAgo(val days: Int) : WhenPlayed

    /** A week ago or more: [at]'s date. */
    data class OnDate(val at: Long) : WhenPlayed
}

/** How long ago [at] was, in days of the phone's calendar ([zone]) as of [now]; a time after now (the clock was changed) is today. */
fun whenPlayed(at: Long?, now: Long, zone: ZoneId): WhenPlayed {
    if (at == null) return WhenPlayed.Never
    val days = ChronoUnit.DAYS.between(dateOf(at, zone), dateOf(now, zone))
    return when {
        days <= 0 -> WhenPlayed.Today(at)
        days == 1L -> WhenPlayed.Yesterday
        days < WEEK_DAYS -> WhenPlayed.DaysAgo(days.toInt())
        else -> WhenPlayed.OnDate(at)
    }
}

private const val WEEK_DAYS = 7

private fun dateOf(millis: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
