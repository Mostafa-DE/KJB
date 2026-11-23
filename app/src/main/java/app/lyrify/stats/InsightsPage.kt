package app.lyrify.stats

import app.lyrify.library.Album
import app.lyrify.library.Artist
import app.lyrify.library.Genre
import app.lyrify.library.Library
import app.lyrify.library.Song
import app.lyrify.library.albumKey
import app.lyrify.player.SourceKind
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
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
    return InsightsPage(period, PageWork(data, period, now, calendar, library).cards())
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

// How each card is worked out

/** Covers on a story's ring. */
private const val RECAP_COVERS = 4

/** Every day you listened: plays that counted on this many days. */
private const val MIN_DAYS = 2

/** The top artist's run: number 1 this many weeks or months. */
private const val MIN_LOYALTY = 2

/** The top song's busiest day: this many plays that counted in it. */
private const val MIN_DAY_PLAYS = 2

private const val DECADE = 10

/** How you listen: this many plays that keep how they began; a skip in the first 30 s is early; a song skipped early this often is named. */
private const val MIN_HOW_PLAYS = 10
private const val EARLY_SKIP_MS: Long = 30_000
private const val MIN_EARLY_SKIPS = 3

/** Moments: a song this many times in a day; a session from this long, broken by this long a gap; night from 9 pm to 6 am. */
private const val MIN_REPEAT = 3
private const val MIN_SESSION_MS: Long = 30 * 60_000
private const val SESSION_GAP_MS: Long = 5 * 60_000
private const val NIGHT_FROM = 21
private const val NIGHT_UNTIL = 6
private const val MINUTES_IN_HOUR = 60

/** Moments: an album played through has this many songs; this many time zones; this much time saved. */
private const val ALBUM_MIN_SONGS = 3
private const val MIN_ZONES = 2
private const val MIN_SAVED_MS: Long = 60_000

/** New to you: an artist played on this many days is kept. Back again: after this many quiet days, this many shown. */
private const val KEPT_DAYS = 3
private const val QUIET_DAYS = 60
private const val COMEBACKS_SHOWN = 3

/**
 * One page's working out, each part once for the cards that share it: [heard], the play log's plays in the period (an
 * import's and the short ones left out), and [books], the book log's.
 */
private class PageWork(private val data: InsightsData, private val period: PagePeriod, private val now: Long, private val calendar: PhoneCalendar, private val library: Library?) {
    private val zone = calendar.zone
    private val kinds = Kinds(library)
    private val heard = data.plays.within(period.bounds)
    private val books = data.books.within(period.bounds)
    private val ledger = ledgerOf(heard, books, kinds)
    private val music by lazy { heard.filter { kinds.of(it) == Kind.MUSIC } }
    private val tops by lazy { listeningOf(songPlaysOf(music), library) }

    /** Each play's day where it was heard, and the days of plays that counted (books never count one). */
    private fun localDay(play: LoggedPlay): LocalDate = localTimeOf(play, zone).toLocalDate()

    private fun countedDays(plays: List<LoggedPlay>): Set<LocalDate> = plays.filter { it.counted && kinds.of(it) != Kind.BOOK }.mapTo(HashSet(), ::localDay)

    fun cards(): InsightsCards {
        val shows = ledger.heardMs >= MIN_HEARD_MS || ledger.plays > 0
        val time = timeOf().takeIf { shows }
        if (library == null) return InsightsCards(time = time)
        return InsightsCards(
            recaps = if (period.going) recapsOf(library) else emptyList(),
            time = time,
            days = daysOf(),
            clock = clockOf(),
            artists = topCardOf(tops.artists, MIN_TOP_OTHERS, placesBefore?.artists, { it.name }) { coverOf.byArtist[it.name] }
                ?.let { card -> card.copy(loyalty = loyaltyOf(card.rows.first().top.item.name, library)) },
            songs = topCardOf(tops.songs, MIN_TOP_SONGS, placesBefore?.songs, { it.path }) { it }
                ?.let { card -> card.copy(firstsDay = firstsDayOf(card.rows.first().top.item)) },
            albums = topCardOf(tops.albums, MIN_TOP_OTHERS, placesBefore?.albums, { it.key }) { coverOf.byAlbum[it.key] },
            taste = tasteOf(library),
            how = howOf(),
            moments = momentsOf(library),
            found = foundOf(library),
            comebacks = comebacksOf(),
            forgotten = if (period.going) forgottenOf(data, now, library, kinds) else emptyList(),
        )
    }

    /** [play]'s song in the library now (found again by its path, then its id hint), once per path. */
    private fun songOf(play: LoggedPlay): Song? = songs.getOrPut(play.song.path) { library?.resolver?.find(play.song) }

    private val songs = HashMap<String, Song?>()

    /** The music played in the period whose song the library has, each with its song. */
    private val musicSongs by lazy { music.mapNotNull { play -> songOf(play)?.let { play to it } } }

    /** How you listen: from [MIN_HOW_PLAYS] plays that keep how they began, quick skips too; books left out. */
    private fun howOf(): HowCard? {
        val plays = (heard + data.skips.within(period.bounds)).filter { it.start != null && kinds.of(it) != Kind.BOOK }
        if (plays.size < MIN_HOW_PLAYS) return null
        val ways = plays.groupingBy { wayOf(checkNotNull(it.start)) }.eachCount()
        val ended = plays.mapNotNull { play -> endOf(play)?.let { end -> songOf(play)?.takeIf { kinds.of(play) == Kind.MUSIC }?.let { it to end } } }
        val mostSkipped = ended.filter { it.second == End.EARLY }.groupingBy { it.first }.eachCount()
            .filter { it.value >= MIN_EARLY_SKIPS }
            .maxWithOrNull(compareBy<Map.Entry<Song, Int>> { it.value }.thenBy { it.key.path })
            ?.let { (song, early) -> Skipped(song, early, ended.count { it.first == song }) }
        return HowCard(
            started = Starts(ways[Way.PICKED] ?: 0, ways[Way.IN_ORDER] ?: 0, ways[Way.SHUFFLE] ?: 0, ways[Way.SUGGESTIONS] ?: 0, ways[Way.HUDDLE] ?: 0),
            ends = endsOf(plays),
            shuffled = endsOf(plays.filter { checkNotNull(it.start).shuffled }),
            inOrder = endsOf(plays.filter { play -> checkNotNull(play.start).let { !it.shuffled && it.source == SourceKind.ALBUM } }),
            mostSkipped = mostSkipped,
        )
    }

    /** What started a play, the first that holds: Huddle, a suggestion, Shuffle, the user's own pick, else the list in order. */
    private fun wayOf(start: PlayStart): Way = when {
        start.source == SourceKind.HUDDLE -> Way.HUDDLE
        start.by == StartedBy.SUGGESTED || start.source == SourceKind.SUGGESTIONS -> Way.SUGGESTIONS
        start.shuffled -> Way.SHUFFLE
        start.by == StartedBy.PICKED -> Way.PICKED
        else -> Way.IN_ORDER
    }

    private enum class Way { PICKED, IN_ORDER, SHUFFLE, SUGGESTIONS, HUDDLE }

    /** How a play ended for the cards: none for a play stopped (KJB closed) or that could not go on. */
    private fun endOf(play: LoggedPlay): End? = when (play.end?.how) {
        PlayEnd.FINISHED -> End.FINISHED
        PlayEnd.NEXT, PlayEnd.JUMP, PlayEnd.BACK -> if (checkNotNull(play.end).atMs < EARLY_SKIP_MS) End.EARLY else End.MOVED_ON
        else -> null
    }

    private enum class End { FINISHED, MOVED_ON, EARLY }

    private fun endsOf(plays: List<LoggedPlay>): Ends {
        val ends = plays.groupingBy(::endOf).eachCount()
        return Ends(ends[End.FINISHED] ?: 0, ends[End.MOVED_ON] ?: 0, ends[End.EARLY] ?: 0)
    }

    /** The postcards, each from its own minimum: where it began (all time), then the period's own, then a year ago today. */
    private fun momentsOf(library: Library): List<Moment> = listOfNotNull(
        firstPlayOf().takeIf { period.bounds == null },
        onRepeatOf(),
        longestSessionOf(),
        latestNightOf(),
        startToFinishOf(library),
        onTheMoveOf(),
        fasterOf(),
        yearAgoOf().takeIf { period.going && period.bounds != null },
    )

    private fun firstPlayOf(): Moment.FirstPlay? = data.plays.firstOrNull { it.counted && kinds.of(it) == Kind.MUSIC && songOf(it) != null }
        ?.let { Moment.FirstPlay(checkNotNull(songOf(it)), localDay(it)) }

    /** The song played most in one day: from [MIN_REPEAT] plays that counted. */
    private fun onRepeatOf(): Moment.OnRepeat? = mostInADay(musicSongs.filter { it.first.counted })
        ?.takeIf { it.plays >= MIN_REPEAT }
        ?.let { Moment.OnRepeat(it.song, it.day, it.plays) }

    /** The song of [plays] played most on one day, the latest day of equals. */
    private fun mostInADay(plays: List<Pair<LoggedPlay, Song>>): DayPlays? = plays.groupingBy { (play, song) -> song to localDay(play) }.eachCount()
        .maxWithOrNull(compareBy<Map.Entry<Pair<Song, LocalDate>, Int>> { it.value }.thenBy { it.key.second })
        ?.let { (key, count) -> DayPlays(key.first, key.second, count) }

    /**
     * The longest listening without a break: plays (books too) with under [SESSION_GAP_MS] from one's end to the next's
     * start, from [MIN_SESSION_MS], its most played music song shown.
     */
    private fun longestSessionOf(): Moment.LongestSession? {
        val sessions = mutableListOf<MutableList<LoggedPlay>>()
        var end = Long.MIN_VALUE
        (heard + books).sortedBy { it.startedAt }.forEach { play ->
            if (sessions.isEmpty() || play.startedAt - end > SESSION_GAP_MS) sessions += mutableListOf<LoggedPlay>()
            sessions.last() += play
            end = maxOf(end, play.startedAt + play.realMs)
        }
        return sessions.map { it to it.sumOf { play -> play.realMs } }
            .filter { it.second >= MIN_SESSION_MS }
            .sortedByDescending { it.second }
            .firstNotNullOfOrNull { (plays, ms) ->
                val song = plays.filter { it.counted && kinds.of(it) == Kind.MUSIC }.mapNotNull(::songOf).groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
                song?.let { Moment.LongestSession(localTimeOf(plays.first(), zone).truncatedTo(ChronoUnit.MINUTES), ms, it) }
            }
    }

    /** The latest a music play started at night, [NIGHT_FROM] to [NIGHT_UNTIL] o'clock where it was heard. */
    private fun latestNightOf(): Moment.LatestNight? = musicSongs.map { (play, song) -> localTimeOf(play, zone) to song }
        .filter { (at) -> at.hour >= NIGHT_FROM || at.hour < NIGHT_UNTIL }
        .maxWithOrNull(compareBy { (at) -> (if (at.hour < NIGHT_UNTIL) at.hour + HOURS_IN_DAY else at.hour) * MINUTES_IN_HOUR + at.minute })
        ?.let { (at, song) -> Moment.LatestNight(at.truncatedTo(ChronoUnit.MINUTES), song) }

    /**
     * The albums played start to finish: an album of [ALBUM_MIN_SONGS] songs or more played from its page, not shuffled,
     * every song in its order, one after the other, each to its end.
     */
    private fun startToFinishOf(library: Library): Moment.StartToFinish? {
        var album: Album? = null
        var played = 0
        var through = 0
        var latest: Album? = null
        musicSongs.forEach { (play, song) ->
            val start = play.start
            val own = albumKey(song)?.let(library::album)?.takeIf { it.songs.size >= ALBUM_MIN_SONGS }
            val inOrder = start != null && start.source == SourceKind.ALBUM && !start.shuffled && play.end?.how == PlayEnd.FINISHED
            played = when {
                own == null || !inOrder -> 0
                own.key == album?.key && own.songs.getOrNull(played) == song -> played + 1
                own.songs.first() == song -> 1
                else -> 0
            }
            album = own.takeIf { played > 0 }
            if (own != null && played == own.songs.size) {
                through += 1
                latest = own
                played = 0
            }
        }
        return latest?.let { Moment.StartToFinish(through, it) }
    }

    /** The time zones the period's plays were heard in, from two, the most heard first. */
    private fun onTheMoveOf(): Moment.OnTheMove? = (heard + books).filter { it.zoneOffsetMinutes != null }
        .groupBy { checkNotNull(it.zoneOffsetMinutes) }
        .mapValues { (_, its) -> its.sumOf { it.realMs } }
        .entries.sortedByDescending { it.value }.map { it.key }
        .takeIf { it.size >= MIN_ZONES }
        ?.let(Moment::OnTheMove)

    /** The time faster speeds saved in books and podcasts: time heard past the real minutes, from a minute. */
    private fun fasterOf(): Moment.Faster? {
        fun saved(plays: List<LoggedPlay>): Long = plays.sumOf { play -> play.clockMs?.let { (play.heardMs - it).coerceAtLeast(0) } ?: 0 }
        val booksMs = saved(books) + saved(heard.filter { kinds.of(it) == Kind.BOOK })
        val podcastsMs = saved(heard.filter { kinds.of(it) == Kind.PODCAST })
        return Moment.Faster(booksMs, podcastsMs).takeIf { booksMs + podcastsMs >= MIN_SAVED_MS }
    }

    /** The song played most on this day a year ago, where it was heard. */
    private fun yearAgoOf(): Moment.YearAgo? {
        val day = period.lastDay.minusYears(1)
        return mostInADay(data.plays.filter { it.counted && kinds.of(it) == Kind.MUSIC && localDay(it) == day }.mapNotNull { play -> songOf(play)?.let { play to it } })
            ?.let { Moment.YearAgo(it.song, it.day, it.plays) }
    }

    /**
     * New to you: the artists and songs whose first play that counted is in the period, none known before it (played here,
     * or in an app imported from); all time: every one. Kept: played on [KEPT_DAYS] days.
     */
    private fun foundOf(library: Library): FoundCard? {
        if (!enough) return null
        val from = period.bounds?.from
        val known = if (from == null) emptySet() else (data.plays.filter { it.startedAt < from && it.counted } + data.imports).mapNotNullTo(HashSet()) { songOf(it)?.path }
        val knownArtists = known.flatMapTo(HashSet()) { path -> library.song(path)?.let(library::artistsOf).orEmpty().map { it.name } }
        val counted = musicSongs.filter { it.first.counted }
        val daysOf = HashMap<String, MutableSet<LocalDate>>()
        val firstOf = HashMap<String, LoggedPlay>()
        counted.forEach { (play, song) ->
            library.artistsOf(song).forEach { artist ->
                daysOf.getOrPut(artist.name) { HashSet() } += localDay(play)
                firstOf.putIfAbsent(artist.name, play)
            }
        }
        val artists = tops.artists.filter { it.item.name !in knownArtists }.map { FoundArtist(it.item, (daysOf[it.item.name]?.size ?: 0) >= KEPT_DAYS) }
        val songs = tops.songs.filter { it.item.path !in known }
        if (artists.isEmpty() && songs.isEmpty()) return null
        val ways = artists.mapNotNull { firstOf[it.artist.name]?.start }.map(::wayOf)
        return FoundCard(
            artists = artists,
            songs = songs.size,
            newPlays = songs.sumOf { it.plays },
            plays = counted.size,
            fromSuggestions = ways.count { it == Way.SUGGESTIONS },
            fromFriends = ways.count { it == Way.HUDDLE },
            onYourOwn = artists.size - ways.count { it == Way.SUGGESTIONS || it == Way.HUDDLE },
        )
    }

    /** Back again: the songs played in the period after [QUIET_DAYS] days without a play that counted, the longest quiet first. */
    private fun comebacksOf(): List<Comeback> {
        val bySong = data.plays.filter { it.counted && kinds.of(it) == Kind.MUSIC }.mapNotNull { play -> songOf(play)?.let { play to it } }.groupBy({ it.second }, { it.first })
        return bySong.mapNotNull { (song, plays) ->
            plays.zipWithNext().filter { (_, next) -> period.bounds == null || next.startedAt in period.bounds }
                .maxOfOrNull { (before, next) -> ChronoUnit.DAYS.between(localDay(before), localDay(next)) }
                ?.takeIf { it >= QUIET_DAYS }
                ?.let { Comeback(song, it.toInt()) }
        }.sortedByDescending { it.quietDays }.take(COMEBACKS_SHOWN)
    }

    /** The top lists show from [MIN_TOP_PLAYS] music plays that counted. */
    private val enough by lazy { music.count { it.counted } >= MIN_TOP_PLAYS }

    /** All of the period before's top lists, to rank against: none for all time, nor with under [MIN_TOP_PLAYS] music plays that counted. */
    private val placesBefore: Listening? by lazy {
        val plays = period.before?.let { data.plays.within(it) }?.filter { kinds.of(it) == Kind.MUSIC } ?: return@lazy null
        listeningOf(songPlaysOf(plays), library).takeIf { plays.count { it.counted } >= MIN_TOP_PLAYS }
    }

    /** The most played song of each artist and each album in the period, the cover their rows show. */
    private val coverOf by lazy {
        val covers = Covers(HashMap(), HashMap())
        tops.songs.forEach { top ->
            library?.artistsOf(top.item)?.forEach { covers.byArtist.putIfAbsent(it.name, top.item) }
            albumKey(top.item)?.let { covers.byAlbum.putIfAbsent(it, top.item) }
        }
        covers
    }

    private class Covers(val byArtist: HashMap<String, Song>, val byAlbum: HashMap<String, Song>)

    /** A top list from its [rows] (at least [min] of them), each with its place in [before] (by [key]) and its [cover]. */
    private fun <T> topCardOf(rows: List<Top<T>>, min: Int, before: List<Top<T>>?, key: (T) -> String, cover: (T) -> Song?): TopCard<T>? {
        if (!enough || rows.size < min) return null
        val places = before?.withIndex()?.associate { (at, top) -> key(top.item) to at + 1 }
        return TopCard(rows.mapNotNull { top -> cover(top.item)?.let { Ranked(top, places?.get(key(top.item)), it) } }, compared = places != null)
    }

    /**
     * How long [first] has been number 1: the weeks or months in a row up to this one, a year's months, or all time's
     * month it took the lead and kept it; by the phone's calendar, as the periods are.
     */
    private fun loyaltyOf(first: String, library: Library): Loyalty? {
        val music = data.plays.filter { kinds.of(it) == Kind.MUSIC }
        fun numberOne(plays: List<LoggedPlay>?): String? = plays?.let { listeningOf(songPlaysOf(it), library).artists.firstOrNull()?.item?.name }
        fun monthOf(play: LoggedPlay): YearMonth = YearMonth.from(dayOf(play.startedAt, zone).date)
        return when (period.kind) {
            ListeningPeriod.WEEK -> {
                val weeks = music.groupBy { dayOf(it.startedAt, zone).date.with(TemporalAdjusters.previousOrSame(calendar.firstDayOfWeek)) }
                generateSequence(period.firstDay) { it.minusWeeks(1) }.takeWhile { numberOne(weeks[it]) == first }.count().takeIf { it >= MIN_LOYALTY }?.let(Loyalty::Running)
            }
            ListeningPeriod.MONTH -> {
                val months = music.groupBy(::monthOf)
                generateSequence(YearMonth.from(period.firstDay)) { it.minusMonths(1) }.takeWhile { numberOne(months[it]) == first }.count().takeIf { it >= MIN_LOYALTY }?.let(Loyalty::Running)
            }
            ListeningPeriod.YEAR -> {
                val months = heard.filter { kinds.of(it) == Kind.MUSIC }.groupBy(::monthOf)
                val of = YearMonth.from(period.lastDay).monthValue
                (1..of).count { numberOne(months[YearMonth.of(period.firstDay.year, it)]) == first }.takeIf { it >= MIN_LOYALTY }?.let { Loyalty.MonthsOf(it, of) }
            }
            ListeningPeriod.ALL_TIME -> {
                // Each song's plays so far, month by month: the number 1 of all time at the end of each month.
                val totals = HashMap<String, SongPlays>()
                var leader: String? = null
                var since: YearMonth? = null
                music.groupBy(::monthOf).toSortedMap().forEach { (month, plays) ->
                    songPlaysOf(plays).forEach { added ->
                        totals.merge(added.song.path, added) { kept, new -> SongPlays(new.song, kept.count + new.count, listOfNotNull(kept.lastPlayedAt, new.lastPlayedAt).maxOrNull(), kept.heardMs + new.heardMs) }
                    }
                    val now = listeningOf(totals.values.toList(), library).artists.firstOrNull()?.item?.name
                    if (now != leader) since = month
                    leader = now
                }
                since?.takeIf { leader == first }?.let(Loyalty::Since)
            }
        }
    }

    /** The day [song] was played most in the period: from [MIN_DAY_PLAYS] plays that counted. */
    private fun firstsDayOf(song: Song): DayPlays? = mostInADay(musicSongs.filter { it.first.counted && it.second == song })?.takeIf { it.plays >= MIN_DAY_PLAYS }

    /** Your taste: the genres from 2 of them, the decades and middle year from [MIN_TOP_PLAYS] plays of songs with a year. */
    private fun tasteOf(library: Library): TasteCard? {
        if (!enough) return null
        val genres = genresOf(tops.songs, library).takeIf { it.size >= MIN_TOP_OTHERS }.orEmpty()
        val years = tops.songs.mapNotNull { top -> top.item.year?.takeIf { it > 0 }?.let { it to top.plays } }.sortedBy { it.first }
        val dated = years.sumOf { it.second }
        if (dated < MIN_TOP_PLAYS) return TasteCard(genres, emptyList(), null).takeIf { genres.isNotEmpty() }
        val byDecade = years.groupBy { it.first / DECADE * DECADE }.mapValues { (_, its) -> its.sumOf { it.second } }
        val decades = (byDecade.keys.min()..byDecade.keys.max() step DECADE).map { Decade(it, byDecade[it] ?: 0) }
        var count = 0
        val middle = years.first { (_, plays) -> count += plays; count * 2 >= dated }.first
        return TasteCard(genres, decades, middle)
    }

    /** Your time: the totals now and the period before's up to the same point, step by step. */
    private fun timeOf(): TimeCard {
        val step = if (period.kind == ListeningPeriod.WEEK || period.kind == ListeningPeriod.MONTH) Step.DAY else Step.MONTH
        val before = period.before?.let { previous -> totalsOf(Period(previous.from, checkNotNull(period.beforeUntil))).takeIf { it.plays >= MIN_VERSUS_PLAYS || it.heardMs >= MIN_VERSUS_HEARD_MS } }
        val heardBefore = period.before?.takeIf { before != null }?.let { previous ->
            val first = dayOf(previous.from, zone).date
            stepsOf(data.plays.within(previous) + data.books.within(previous), step, first, stepsBetween(step, first, dayOf(previous.until, zone).date), last = null)
        }
        return TimeCard(totalsOf(period.bounds), before, stepsOf(heard + books, step), heardBefore)
    }

    /** The totals of [bounds]: the time heard and plays (both ledgers), and the songs, artists and albums of the music. */
    private fun totalsOf(bounds: Period?): Totals {
        val plays = data.plays.within(bounds)
        val ledger = ledgerOf(plays, data.books.within(bounds), kinds)
        if (library == null) return Totals(ledger.heardMs, ledger.plays, 0, 0, 0)
        val tops = if (bounds == period.bounds) tops else listeningOf(songPlaysOf(plays.filter { kinds.of(it) == Kind.MUSIC }), library)
        return Totals(ledger.heardMs, ledger.plays, tops.songs.size, tops.artists.size, tops.albums.size)
    }

    /** [plays]' real minutes by [step] over the period (a step after today has none yet). */
    private fun stepsOf(plays: List<LoggedPlay>, step: Step): Steps {
        val first = if (step == Step.MONTH) period.firstDay.withDayOfMonth(1) else period.firstDay
        val end = period.bounds?.let { dayOf(it.until, zone).date } ?: if (step == Step.MONTH) first.plusMonths(1 + stepsBetween(step, first, period.lastDay).toLong()) else period.lastDay.plusDays(1)
        return stepsOf(plays, step, first, stepsBetween(step, first, end), last = period.lastDay.takeIf { period.going })
    }

    /**
     * [plays]' real minutes by [step], [count] steps from [first], each play in the step of the day it was heard (one a
     * time zone took past either end goes in the first or the last); a step after [last] has none yet.
     */
    private fun stepsOf(plays: List<LoggedPlay>, step: Step, first: LocalDate, count: Int, last: LocalDate?): Steps {
        val sums = LongArray(count)
        plays.forEach { sums[stepsBetween(step, first, localDay(it)).coerceIn(0, count - 1)] += it.realMs }
        val lastStep = last?.let { stepsBetween(step, first, it) } ?: count
        return Steps(step, first, sums.mapIndexed { at, ms -> ms.takeIf { at <= lastStep } })
    }

    private fun stepsBetween(step: Step, from: LocalDate, to: LocalDate): Int =
        (if (step == Step.DAY) ChronoUnit.DAYS.between(from, to) else ChronoUnit.MONTHS.between(YearMonth.from(from), YearMonth.from(to))).toInt()

    /** Every day you listened: plays that counted on [MIN_DAYS] days, the heat map a day each but for all time. */
    private fun daysOf(): DaysCard? {
        val days = countedDays(heard)
        if (days.size < MIN_DAYS) return null
        val longest = days.filter { it.minusDays(1) !in days }.map { Run(runFrom(it, days, step = 1), it) }.maxWithOrNull(compareBy<Run> { it.days }.thenBy { it.from })
        val current = if (period.going) {
            val ever = countedDays(data.plays)
            val today = period.lastDay
            // From today or yesterday; or from tomorrow, a day a play heard in a time zone ahead of the phone's is on already.
            listOf(today.plusDays(1), today, today.minusDays(1)).filter { it in ever }.maxOfOrNull { runFrom(it, ever, step = -1) } ?: 0
        } else {
            null
        }
        val byDay = (heard + books).groupBy(::localDay).mapValues { (_, its) -> its.sumOf { it.realMs } }
        val busiest = byDay.entries.maxWith(compareBy<Map.Entry<LocalDate, Long>> { it.value }.thenBy { it.key })
        return DaysCard(stepsOf(heard + books, if (period.bounds == null) Step.MONTH else Step.DAY), current, longest?.takeIf { it.days >= MIN_STREAK_DAYS }, DayHeard(busiest.key, busiest.value))
    }

    /** Your day on a record: from [MIN_CLOCK_PLAYS] plays that counted. */
    private fun clockOf(): ClockCard? {
        if (heard.count { it.counted } < MIN_CLOCK_PLAYS) return null
        val hours = LongArray(HOURS_IN_DAY)
        var weekend = 0L
        (heard + books).forEach { play ->
            val local = localTimeOf(play, zone)
            hours[local.hour] += play.realMs
            if (local.dayOfWeek in calendar.weekend) weekend += play.realMs
        }
        return ClockCard(hours.toList(), hours.indices.maxBy { hours[it] }, hours.sum() - weekend, weekend)
    }

    /** The stories row: each month with a recap ([recapMonths]) and its most played songs of different albums. */
    private fun recapsOf(library: Library): List<RecapMonth> {
        val byMonth = data.plays.filter { kinds.of(it) == Kind.MUSIC }.groupBy { YearMonth.from(Instant.ofEpochMilli(it.startedAt).atZone(zone)) }
        return recapMonths(data.plays, now, zone, library).map { month ->
            val songs = listeningOf(songPlaysOf(byMonth[month].orEmpty()), library).songs.map { it.item }
            RecapMonth(month, songs.distinctBy { albumKey(it) ?: it.path }.take(RECAP_COVERS))
        }
    }
}
