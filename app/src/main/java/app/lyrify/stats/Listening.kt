package app.lyrify.stats

import app.lyrify.library.Album
import app.lyrify.library.Artist
import app.lyrify.library.Library
import app.lyrify.library.Song
import app.lyrify.library.SongRef
import app.lyrify.library.albumKey
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Collator
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

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
 * Your listening for one period: the time heard and the plays that counted, songs gone or hidden included, then the top
 * songs, artists and albums of the library, the most played first.
 */
data class Listening(val heardMs: Long, val plays: Int, val songs: List<Top<Song>>, val artists: List<Top<Artist>>, val albums: List<Top<Album>>)

/**
 * Your listening from a period's [totals], joined with [library] now, so a song moved or renamed keeps its plays (found
 * again by its path, then its id hint) and artists and albums follow today's tags. Songs gone or hidden leave the top
 * lists, and books every figure ([withoutBooks]); songs only heard (no play counted) add time but no row. Ties go to the latest played, then by name, then by
 * path or key, so the order never changes between two looks. Without a library (not read yet) only the totals are known.
 */
fun listeningOf(totals: List<SongPlays>, library: Library?): Listening {
    val counted = withoutBooks(totals, library)
    val heard = counted.sumOf { it.heardMs }
    val plays = counted.sumOf { it.count.toLong() }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    if (library == null) return Listening(heard, plays, emptyList(), emptyList(), emptyList())
    val collator = Collator.getInstance()
    val songs = bySong(counted, library, shownOnly = true)
    val artists = songs.mapNotNull { top -> top.item.artist?.trim()?.let(library::artist)?.let { top to it } }
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
private fun <T> topOrder(): Comparator<Top<T>> = compareByDescending<Top<T>> { it.plays }.thenByDescending { it.lastPlayedAt ?: Long.MIN_VALUE }

private fun <T> sum(item: T, tops: List<Top<*>>): Top<T> =
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
 * ([PlaysQuery]), and Your listening. Sorting and joining run on [compute].
 */
class PlayStats(
    private val store: PlayLogStore,
    private val library: () -> Library?,
    private val compute: CoroutineDispatcher = Dispatchers.Default,
) : PlaysQuery {
    /** The library's songs played most, all time, under their paths now; ties as in [listeningOf]. */
    override suspend fun mostPlayed(limit: Int): List<SongPlays> {
        val totals = store.totals()
        return withContext(compute) { listeningOf(totals, library()).songs.take(limit).map(::songPlays) }
    }

    /** The library's songs whose last play that counted is the latest, the latest first. */
    override suspend fun recentlyPlayed(limit: Int): List<SongPlays> {
        val totals = store.totals()
        return withContext(compute) {
            val library = library() ?: return@withContext emptyList()
            bySong(totals, library, shownOnly = true).filter { it.lastPlayedAt != null }
                .sortedWith(compareByDescending<Top<Song>> { it.lastPlayedAt }.thenBy { it.item.path })
                .take(limit)
                .map(::songPlays)
        }
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

    /** Your listening for [period] as of [now], in the phone's [zone] and with its [firstDayOfWeek]. */
    suspend fun listening(period: ListeningPeriod, now: Long, zone: ZoneId, firstDayOfWeek: DayOfWeek): Listening {
        val bounds = periodOf(period, now, zone, firstDayOfWeek)
        val totals = store.totals(bounds?.from, bounds?.until)
        return withContext(compute) { listeningOf(totals, library()) }
    }

    private fun songPlays(top: Top<Song>): SongPlays = SongPlays(top.item.ref, top.plays, top.lastPlayedAt, top.heardMs)
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
