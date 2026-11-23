package app.lyrify.stats

import app.lyrify.library.Album
import app.lyrify.library.Artist
import app.lyrify.library.Genre
import app.lyrify.library.Library
import app.lyrify.library.Song
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Locale
import android.icu.util.Calendar as IcuCalendar

// The Insights page, redone (KJB v4, the approved mockup handoff/insights/preview-insights-v1.html): one page per period
// with every card's figures, each set against the period before up to the same point, worked out on the phone from the
// play log, the book log, the interaction log, Huddle's history, the library, Favourites, bookmarks and books. Each card
// is null (or empty) below its minimum, as in1's cards are (stats/Insights.kt). Time is in real minutes ([realMs]):
// 2x counts half. Nothing here leaves the phone.

/** The phone's calendar as Insights count in it: its time [zone], the first day of its week, and its weekend days. */
data class PhoneCalendar(val zone: ZoneId, val firstDayOfWeek: DayOfWeek, val weekend: Set<DayOfWeek>)

/** The phone's calendar now: its time zone, and its language's first day of the week and weekend (Friday and Saturday in Jordan). */
fun phoneCalendar(): PhoneCalendar {
    val week = IcuCalendar.getInstance(Locale.getDefault()).weekData
    // ICU counts days from Sunday (1) to Saturday (7); a weekend may run over Sunday (Saturday to Sunday).
    val weekend = generateSequence(week.weekendOnset) { it % DAYS_IN_WEEK + 1 }.take(DAYS_IN_WEEK).takeWhile { it != week.weekendCease % DAYS_IN_WEEK + 1 }
        .mapTo(HashSet()) { DayOfWeek.SUNDAY.plus(it - 1L) }
    return PhoneCalendar(ZoneId.systemDefault(), WeekFields.of(Locale.getDefault()).firstDayOfWeek, weekend)
}

private const val DAYS_IN_WEEK = 7

/**
 * The period a page shows: its [kind] (a month's recap is a [ListeningPeriod.MONTH]) and [bounds] (null for all time);
 * the days it covers so far, [firstDay] (all time: the first play's day) to [lastDay] (today while it is [going], else its
 * last day); and the period before ([before], null for all time), whose figures go up to [beforeUntil]: as far into it as
 * now is into this one while this one goes on, its end once this one is over.
 */
data class PagePeriod(
    val kind: ListeningPeriod,
    val bounds: Period?,
    val firstDay: LocalDate,
    val lastDay: LocalDate,
    val going: Boolean,
    val before: Period?,
    val beforeUntil: Long?,
)

/** One Insights page: a [period] and its [cards]. */
data class InsightsPage(val period: PagePeriod, val cards: InsightsCards)

/**
 * The page of [kind] with [bounds] (null: all time), set against the period [before] it, as of [now] in the phone's
 * [calendar], from [data] joined with [library] now (null while it is read: only the time is known).
 */
@Suppress("LongParameterList")
fun insightsPageOf(data: InsightsData, kind: ListeningPeriod, bounds: Period?, before: Period?, now: Long, calendar: PhoneCalendar, library: Library?): InsightsPage {
    val zone = calendar.zone
    val going = bounds == null || now < bounds.until
    val today = dayOf(now, zone).date
    val firstDay = bounds?.let { dayOf(it.from, zone).date } ?: data.plays.firstOrNull()?.let { dayOf(it.startedAt, zone).date } ?: today
    val period = PagePeriod(
        kind = kind,
        bounds = bounds,
        firstDay = firstDay,
        lastDay = if (going || bounds == null) today else dayOf(bounds.until - 1, zone).date,
        going = going,
        before = before,
        beforeUntil = before?.let { if (going && bounds != null) minOf(it.until, it.from + (now - bounds.from)) else it.until },
    )
    return InsightsPage(period, InsightsCards())
}

/**
 * A page's cards in the page's order, each null (or empty) below its minimum: the stories row ([recaps]; on the period
 * pages only), Your time, Every day you listened, Your day on a record, the top artists, songs and albums, Your taste,
 * How you listen, [moments], New to you ([found]), Back again ([comebacks]) and the favourites still quiet ([forgotten]),
 * You and your friends, Read along, New favourites, Your searches, Books and podcasts, Your library, your listening type
 * ([traits]) and Milestones and badges.
 */
data class InsightsCards(
    val recaps: List<RecapMonth> = emptyList(),
    val time: TimeCard? = null,
    val days: DaysCard? = null,
    val clock: ClockCard? = null,
    val artists: TopCard<Artist>? = null,
    val songs: TopCard<Song>? = null,
    val albums: TopCard<Album>? = null,
    val taste: TasteCard? = null,
    val how: HowCard? = null,
    val moments: List<Moment> = emptyList(),
    val found: FoundCard? = null,
    val comebacks: List<Comeback> = emptyList(),
    val forgotten: List<Top<Song>> = emptyList(),
    val friends: FriendsCard? = null,
    val lyrics: LyricsCard? = null,
    val favourites: FavouritesCard? = null,
    val searches: SearchesCard? = null,
    val spoken: SpokenCard? = null,
    val library: LibraryCard? = null,
    val traits: List<Trait> = emptyList(),
    val milestones: MilestonesCard? = null,
)

/** A month with a recap, a story on the stories row, the latest first; [covers]: its most played songs of different albums, for its ring. */
data class RecapMonth(val month: YearMonth, val covers: List<Song>)

/**
 * Your time: the period's [totals], the period before's up to the same point ([before]: null for all time, or when it had
 * too little to set against), and the real minutes step by step ([heard], [heardBefore]): a day each for a week or a
 * month, a month each for a year or all time.
 */
data class TimeCard(val totals: Totals, val before: Totals?, val heard: Steps, val heardBefore: Steps?)

/** Real minutes heard step by step from [first]: a day each, or a month each ([step]); null for a step still to come. */
data class Steps(val step: Step, val first: LocalDate, val heardMs: List<Long?>)

enum class Step { DAY, MONTH }

/**
 * Every day you listened: the heat map ([heard]: a day each, all time a month each), the run of days with a play up to
 * today ([current]: while the period goes on, 0 for none), the [longest] run in the period, and the [busiest] day.
 */
data class DaysCard(val heard: Steps, val current: Int?, val longest: Run?, val busiest: DayHeard)

/** [days] days in a row with a play that counted, the first of them [from]. */
data class Run(val days: Int, val from: LocalDate)

/** The real minutes heard on [day]. */
data class DayHeard(val day: LocalDate, val heardMs: Long)

/**
 * Your day on a record: real minutes by the hour the plays started in ([hours], 24, the local time where each was heard),
 * the hour heard most ([peak]), and the real minutes heard on weekdays and on weekend days ([PhoneCalendar.weekend]).
 */
data class ClockCard(val hours: List<Long>, val peak: Int, val weekdaysMs: Long, val weekendMs: Long)

/**
 * A top list: every row, the most played first, each with its place in the period before when [compared] (not for all
 * time, nor when the period before had too little). For artists, how long the first has been number 1 ([loyalty]); for
 * songs, the first one's busiest day ([firstsDay]).
 */
data class TopCard<T>(val rows: List<Ranked<T>>, val compared: Boolean, val loyalty: Loyalty? = null, val firstsDay: DayPlays? = null)

/**
 * A top list's row: its plays and real minutes ([top]), its place in the period before ([before], from 1; null when it
 * was not in it, new), and the song whose cover shows it ([cover]: the song itself, else the most played song of the
 * album or the artist in the period).
 */
data class Ranked<T>(val top: Top<T>, val before: Int?, val cover: Song)

/** How long the top artist has been number 1. */
sealed interface Loyalty {
    /** A week's or a month's: number 1 for [periods] weeks or months in a row, this one the last (2 or more). */
    data class Running(val periods: Int) : Loyalty

    /** A year's: number 1 in [months] of its [of] months so far (2 or more). */
    data class MonthsOf(val months: Int, val of: Int) : Loyalty

    /** All time's: number 1 of all time ever since [month]. */
    data class Since(val month: YearMonth) : Loyalty
}

/** [song]'s [plays] that counted on [day]. */
data class DayPlays(val song: Song, val day: LocalDate, val plays: Int)

/**
 * Your taste: the [genres] of the music played (its tags), by plays, the most first; the plays by the decade the songs
 * came out ([decades], the earliest first, by their year tag); and the [middleYear] of the music played, by plays (half
 * came out before it, half after).
 */
data class TasteCard(val genres: List<Top<Genre>>, val decades: List<Decade>, val middleYear: Int?)

/** The [plays] of songs that came out [from] to [from] + 9. */
data class Decade(val from: Int, val plays: Int)

/**
 * How you listen, from the plays that keep how they began (3.1.1 on): what started them ([started]); how they ended
 * ([ends]), and under Shuffle ([shuffled]) and in order ([inOrder]) for the Shuffle line; the song skipped early most
 * ([mostSkipped]).
 */
data class HowCard(val started: Starts, val ends: Ends, val shuffled: Ends, val inOrder: Ends, val mostSkipped: Skipped?)

/**
 * The plays started [picked] by you, coming on [inOrder] in an album or a list, by [shuffle], by [suggestions] (Autoplay,
 * Smart shuffle, For you, More like this) or from [huddle].
 */
data class Starts(val picked: Int, val inOrder: Int, val shuffle: Int, val suggestions: Int, val huddle: Int)

/** Plays [finished] (played to the end), [movedOn] from after 30 s, and skipped [early] (in the first 30 s, quick skips too). */
data class Ends(val finished: Int, val movedOn: Int, val early: Int)

/** [song], skipped [early] times of its [plays] in the period. */
data class Skipped(val song: Song, val early: Int, val plays: Int)

/** A postcard of the Moments card: the real and the odd, from the plays. */
sealed interface Moment {
    /** On repeat: [song] played [plays] times on one [day], the most of any song in a day. */
    data class OnRepeat(val song: Song, val day: LocalDate, val plays: Int) : Moment

    /** The longest listening without a break (under 5 minutes between plays): [heardMs] real minutes from [start] (local), its most played [song]. */
    data class LongestSession(val start: LocalDateTime, val heardMs: Long, val song: Song) : Moment

    /** The latest a play started at night (9 pm to 6 am, local): [song], at [at]. */
    data class LatestNight(val at: LocalDateTime, val song: Song) : Moment

    /** Start to finish: [albums] albums played through in order, the latest [album]. */
    data class StartToFinish(val albums: Int, val album: Album) : Moment

    /** On the move: plays heard in these time zones ([offsets], minutes ahead of UTC), the most heard first. */
    data class OnTheMove(val offsets: List<Int>) : Moment

    /** Faster: the time faster speeds saved in books ([booksMs]) and podcasts ([podcastsMs]). */
    data class Faster(val booksMs: Long, val podcastsMs: Long) : Moment

    /** One year ago today: [song] played [plays] times on [day], a year before today. */
    data class YearAgo(val song: Song, val day: LocalDate, val plays: Int) : Moment

    /** Where it began (all time): the first play here, [song] on [day]. */
    data class FirstPlay(val song: Song, val day: LocalDate) : Moment
}

/**
 * New to you: the [artists] first heard in the period (all time: every artist), the most played first; the [songs] first
 * played in it, and their share of the period's plays ([newPlays] of [plays]); and where the artists came from, by how
 * their first play began: [fromSuggestions], [fromFriends] (Huddle) or [onYourOwn].
 */
data class FoundCard(
    val artists: List<FoundArtist>,
    val songs: Int,
    val newPlays: Int,
    val plays: Int,
    val fromSuggestions: Int,
    val fromFriends: Int,
    val onYourOwn: Int,
)

/** An artist first heard in the period, [kept] when played on 3 days or more. */
data class FoundArtist(val artist: Artist, val kept: Boolean)

/** Back again: [song] played in the period after [quietDays] days without a play (60 or more). */
data class Comeback(val song: Song, val quietDays: Int)

/**
 * You and your friends (Huddle's history, past friends too): the taste [matches], the closest first; the artists you
 * and the closest both played most ([both]); the cards you [sent] and were sent ([got]) in the period, and how many sent
 * to you you went on playing ([kept]); the times you played the same song as a friend within the hour ([inSync]); and the
 * song a friend sent you that you played most since ([favourite]).
 */
data class FriendsCard(
    val matches: List<FriendMatch>,
    val both: List<Artist>,
    val sent: Int,
    val got: Int,
    val kept: Int,
    val inSync: InSync?,
    val favourite: SentKept?,
)

/** [percent] of your plays and [friend]'s (their id; [name] as you see them, [past] once they left) went to the same artists. */
data class FriendMatch(val friend: String, val name: String, val past: Boolean, val percent: Int)

/** In sync [times] times, the latest with [friend] (their id, [name]) on [song]. */
data class InSync(val friend: String, val name: String, val song: Song, val times: Int)

/** The song [friend] (their id, [name]) sent you that you played most since: [song], [plays] times. */
data class SentKept(val friend: String, val name: String, val song: Song, val plays: Int)

/**
 * Read along: the times you turned to lyrics in the period ([opened]), the song you read most ([most], [mostTimes]), and
 * how many of the period's plays ([plays]) were of a song with synced lyrics ([syncedPlays]).
 */
data class LyricsCard(val opened: Int, val most: Song?, val mostTimes: Int, val syncedPlays: Int, val plays: Int)

/** New favourites: the songs that became favourites in the period ([added]; all time: every favourite) and the one of them played most. */
data class FavouritesCard(val added: Int, val mostPlayed: Song?)

/**
 * Your searches, on this phone only, never in a story or a picture: the [searches] in the period, those that ended in a
 * tap ([tapped]), those a play followed within a minute ([played]); the [words] searched most (folded, the most first;
 * forgotten words are gone); and what the taps opened: [songs], [artists], [albums] or [others].
 */
data class SearchesCard(
    val searches: Int,
    val tapped: Int,
    val played: Int,
    val words: List<WordCount>,
    val songs: Int,
    val artists: Int,
    val albums: Int,
    val others: Int,
)

/** A word, searched [times] times. */
data class WordCount(val word: String, val times: Int)

/**
 * Books and podcasts in real minutes ([booksMs], [podcastsMs]); the time faster speeds saved ([savedMs]: time heard minus
 * real minutes); the [bookmarks] made and the books [finished] in the period.
 */
data class SpokenCard(val booksMs: Long, val podcastsMs: Long, val savedMs: Long, val bookmarks: Int, val finished: Int)

/**
 * Your library: its music ([songs]; books and podcasts left out), their size ([bytes]) and length ([durationMs]), how many
 * were ever played ([played]: here or in an app imported from), those added in the period ([added]; all time: the last 30
 * days) and of them played already ([addedPlayed]), by file type ([types], the most first), the songs [neverPlayed] (the
 * newest added first) and albums to [digDeeper] into.
 */
data class LibraryCard(
    val songs: Int,
    val bytes: Long,
    val durationMs: Long,
    val played: Int,
    val added: Int,
    val addedPlayed: Int,
    val types: List<FileType>,
    val neverPlayed: List<Song>,
    val digDeeper: List<DigDeeper>,
)

/** The [songs] of one [mimeType] (null: not known). */
data class FileType(val mimeType: String?, val songs: Int)

/** What your listening type says. */
enum class TraitKind { NIGHT_OWL, SHUFFLER, ALBUM_LOVER, EXPLORER, LOYAL }

/**
 * A word of your listening type and the number behind it: [percent] of the time after 9 pm (night owl), of the plays
 * from Shuffle (shuffler), albums in order (album lover) or new to you (explorer), or of the plays that were the top
 * [artist]'s (loyal).
 */
data class Trait(val kind: TraitKind, val percent: Int, val artist: Artist? = null)

/**
 * Milestones and badges: those [reached] in the period, the latest first, and while it goes on the [next] one and the
 * month this month's pace reaches it ([nextAround]); and the badges, all time: the [longestRun] of days, the highest plays
 * and hours milestones reached ([plays], [hours]), the month with the most artists ([artistsMonth]), the books finished.
 */
data class MilestonesCard(
    val reached: List<Milestone>,
    val next: NextMilestone?,
    val nextAround: YearMonth?,
    val longestRun: Run?,
    val plays: Milestone?,
    val hours: Milestone?,
    val artistsMonth: MonthArtists?,
    val booksFinished: Int,
)

/** [artists] artists played in [month]. */
data class MonthArtists(val month: YearMonth, val artists: Int)
