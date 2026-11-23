package app.lyrify.suggestions

import app.lyrify.library.Library
import app.lyrify.library.Song
import app.lyrify.library.albumKey
import app.lyrify.lyrics.TrackNames
import app.lyrify.stats.LoggedPlay
import app.lyrify.stats.PlayEnd
import app.lyrify.stats.StartedBy
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

// The taste model: what each play says about its song, each song's score with its recency fading like a power law
// (ACT-R's base level: the sum over its plays of (time since)^-d, never zero), the artists, albums and genres rolled up
// from the songs, and the next-song table learned from the user's own sessions. Pure: built from the play log, the
// library and Favourites; time of day plays no part (user, 2026-11-22).

/**
 * The starting weights (plan Q2.2, user 2026-11-22), in one place so the replay test can tune them: a song picked by hand
 * counts [picked] times one that came on; heard [heardShare] or more is [heard]; about half heard ([neutralShare]) is
 * neutral; skipped in its first [earlySkipMs] is [earlySkip], [shuffleSkip] of that under Shuffle; skipped after
 * [lateSkipShare] about zero; a Repeat one lap counts normally up to [lapsPerDay] plays of the song a day; a play from
 * before 3.1.1 (or an import's) that counted is a plain [heard]. A play a suggestion started counts [suggested] of its
 * good part, so suggestions never learn from their own picks. A favourite adds [favourite]; recency fades as
 * (days since)^-[decay].
 */
data class Weights(
    val picked: Double = 2.0,
    val heard: Double = 1.0,
    val heardShare: Double = 0.8,
    val neutralShare: Double = 0.5,
    val lateSkipShare: Double = 0.7,
    val earlySkipMs: Long = 30_000,
    val earlySkip: Double = -1.0,
    val shuffleSkip: Double = 0.5,
    val suggested: Double = 0.25,
    val favourite: Double = 1.5,
    val decay: Double = 0.5,
    val lapsPerDay: Int = 3,
)

/**
 * What [play] of a song [durationMs] long says about the song, under [weights]: plus for heard nearly to the end, minus
 * for an early skip, about zero between. Next is a skip; a jump to another song or list is half one (it may be a change
 * of list, not of mind); Previous, a stop or an unknown end are no skip.
 */
fun playValue(play: LoggedPlay, durationMs: Long, weights: Weights = Weights()): Double {
    val start = play.start
    if (play.imported || start == null) return if (play.counted) weights.heard else 0.0
    val end = play.end
    val share = when {
        end?.how == PlayEnd.FINISHED -> 1.0
        durationMs <= 0 -> 0.0
        end != null -> end.atMs.toDouble() / durationMs
        else -> play.heardMs.toDouble() / durationMs
    }.coerceIn(0.0, 1.0)
    val skip = when (end?.how) {
        PlayEnd.NEXT -> 1.0
        PlayEnd.JUMP -> JUMP_SKIP
        else -> 0.0
    }
    val raw = when {
        share >= weights.heardShare -> weights.heard
        skip == 0.0 || end == null || share >= weights.neutralShare -> 0.0
        end.atMs < weights.earlySkipMs -> weights.earlySkip * skip
        // From half an early skip at its end (30 s) to nothing at half the song.
        else -> {
            val from = weights.earlySkipMs.toDouble() / durationMs
            val left = (weights.neutralShare - share) / (weights.neutralShare - from).coerceAtLeast(EPSILON)
            weights.earlySkip / 2 * left.coerceIn(0.0, 1.0) * skip
        }
    }
    return when {
        raw > 0 -> raw * (if (start.by == StartedBy.PICKED) weights.picked else 1.0) * (if (start.by == StartedBy.SUGGESTED) weights.suggested else 1.0)
        raw < 0 -> raw * (if (start.shuffled) weights.shuffleSkip else 1.0)
        else -> 0.0
    }
}

/** A jump to another song or list counts as this much of a skip. */
private const val JUMP_SKIP = 0.5
private const val EPSILON = 1e-9

/** Whether [play] was an early skip under [weights]: Next or a jump before [Weights.earlySkipMs]. */
fun earlySkip(play: LoggedPlay, weights: Weights = Weights()): Boolean {
    val end = play.end ?: return false
    return (end.how == PlayEnd.NEXT || end.how == PlayEnd.JUMP) && end.atMs < weights.earlySkipMs && play.start != null
}

/** Whether [play] was heard to its end or nearly ([Weights.heardShare] of [durationMs]). */
fun heardToEnd(play: LoggedPlay, durationMs: Long, weights: Weights = Weights()): Boolean {
    val end = play.end ?: return false
    return end.how == PlayEnd.FINISHED || (durationMs > 0 && end.atMs >= durationMs * weights.heardShare)
}

/** The day [at] (wall-clock millis) fell on where the play was ([offsetMinutes] from UTC), else in [zone]. */
fun dayOf(at: Long, offsetMinutes: Int?, zone: ZoneId): LocalDate =
    Instant.ofEpochMilli(at).atZone(offsetMinutes?.let { ZoneOffset.ofTotalSeconds(it * SECONDS_PER_MINUTE) } ?: zone).toLocalDate()

private const val SECONDS_PER_MINUTE = 60

/**
 * One song as suggestions know it: its plays as times and values ([times], [values], the oldest first) for its score at
 * any time ([activation]), and the counts the reasons use.
 * - [counted]: its plays that counted (imports included); [picks] those picked by hand (not skipped at once);
 *   [heardFully] those heard to the end; [earlySkips] its early skips; [suggestedPlays] the plays a suggestion started;
 *   [ended] its plays that tell how they ended (3.1.1 on).
 * - [firstPlayedAt], [lastPlayedAt]: its first and last play of any kind (skips too); [lastCountedAt] the last that counted.
 * - [love]: its values summed without fading: how much it was liked over all time.
 * - [picksByMonth]: its picks per month of the phone's calendar, for "You picked this 9 times in October"; [playsByMonth]
 *   its plays that counted, the same way.
 * - [favouriteAt]: when it became a favourite (0 when the date is unknown); null when it is none.
 */
class SongTaste(
    val path: String,
    val times: LongArray,
    val values: DoubleArray,
    val counted: Int,
    val picks: Int,
    val heardFully: Int,
    val earlySkips: Int,
    val suggestedPlays: Int,
    val ended: Int,
    val firstPlayedAt: Long?,
    val lastPlayedAt: Long?,
    val lastCountedAt: Long?,
    val love: Double,
    val picksByMonth: Map<YearMonth, Int>,
    val playsByMonth: Map<YearMonth, Int>,
    val lastSkipAt: Long?,
    val favouriteAt: Long?,
) {
    val favourite: Boolean get() = favouriteAt != null

    /** Its score at [at]: each play's value faded by (days since)^-decay, never to zero; plays after [at] left out. */
    fun activation(at: Long, decay: Double): Double {
        var sum = 0.0
        for (i in times.indices) {
            if (times[i] > at) break
            sum += values[i] * fade(at - times[i], decay)
        }
        return sum
    }
}

/**
 * How much a play [ageMs] old still weighs: its age in days (at least an hour, so a play just now does not weigh without
 * bound) to the power -[decay]. The default 0.5 is a square root, several times quicker than pow on a phone, and the
 * replay test runs this millions of times.
 */
fun fade(ageMs: Long, decay: Double): Double {
    val days = max(ageMs, MIN_AGE_MS).toDouble() / DAY_MS
    return if (decay == SQUARE_ROOT) 1 / sqrt(days) else days.pow(-decay)
}

private const val SQUARE_ROOT = 0.5

private const val MIN_AGE_MS: Long = 3_600_000
internal const val DAY_MS: Double = 86_400_000.0

/** A song that often comes after another: [path], and how strongly ([weight]: picked transitions count most). */
data class Follow(val path: String, val weight: Double)

/**
 * Everything suggestions know about the user's taste at [now]: each song played ([songs], by its path in the library now),
 * each song's [score] (its activation now, its favourite boost and its interest boost), the artists, albums and genres
 * rolled up from the songs (0 to 1, [artist], [album], [genre]), the next-song table ([follows]), friends' love, and the
 * songs played lately ([recentPaths]: the last [RECENT_PLAYS] plays or the last day, whichever holds more).
 */
class Taste(
    val now: Long,
    val weights: Weights,
    val songs: Map<String, SongTaste>,
    private val scores: Map<String, Double>,
    private val artists: Map<String, Double>,
    private val albums: Map<String, Double>,
    private val genres: Map<String, Double>,
    val follows: Map<String, List<Follow>>,
    val friends: Map<String, FriendLove>,
    val recentPaths: Set<String>,
    /** Similar artists in the library to each liked artist, by the liked artist's name. */
    val similar: Map<String, List<SimilarArtist>>,
    /** How each play a suggestion started went, the oldest first: what the mix learns from ([mixOf]). */
    val outcomes: List<Outcome> = emptyList(),
) {
    /** The song at [path]'s score now; 0 for a song never played and no favourite. */
    fun score(path: String): Double = scores[path] ?: 0.0

    fun artist(name: String): Double = artists[name] ?: 0.0

    fun album(key: String): Double = albums[key] ?: 0.0

    fun genre(name: String): Double = genres[name] ?: 0.0

    /** The liked artists, the most liked first. */
    val likedArtists: List<Pair<String, Double>> by lazy { artists.entries.filter { it.value > 0 }.sortedByDescending { it.value }.map { it.key to it.value } }

    companion object {
        /**
         * The taste in [plays] (the play log, the oldest first) for [library]'s music, as of [now] in [zone]: each play
         * found again in the library (moved files keep their plays), books and podcasts left out. [favourites]: the
         * favourite songs' paths with the date each became one (0 unknown). [interactions], [friendPlays] and [similar]
         * add small, capped boosts.
         */
        @Suppress("LongParameterList")
        fun of(
            plays: List<LoggedPlay>,
            library: Library,
            favourites: Map<String, Long>,
            now: Long,
            zone: ZoneId,
            weights: Weights = Weights(),
            interactions: List<Interaction> = emptyList(),
            friendPlays: List<FriendPlay> = emptyList(),
            similar: Map<String, List<SimilarArtist>> = emptyMap(),
        ): Taste {
            val found = HashMap<String, Song?>()
            val songOf: (LoggedPlay) -> Song? = { play -> found.getOrPut(play.song.path) { library.resolver.find(play.song)?.takeIf { music(library, it) } } }
            val outcomes = ArrayList<Outcome>()
            val songs = songTastes(plays, songOf, favourites, library, zone, weights, outcomes)
            val interest = interest(interactions, library, now)
            val scores = HashMap<String, Double>(songs.size)
            songs.values.forEach { taste ->
                scores[taste.path] = taste.activation(now, weights.decay) + (if (taste.favourite) weights.favourite else 0.0) + (interest.songs[taste.path] ?: 0.0)
            }
            // A song with no play but a favourite or an interest still says something.
            favourites.keys.forEach { path -> if (path !in scores && library.song(path)?.let { music(library, it) } == true) scores[path] = weights.favourite }
            interest.songs.forEach { (path, boost) -> scores.merge(path, boost) { a, _ -> a } }
            val artistRaw = HashMap<String, Double>()
            val albumRaw = HashMap<String, Double>()
            val genreRaw = HashMap<String, Double>()
            scores.forEach { (path, score) ->
                val song = library.song(path) ?: return@forEach
                // A song that is skipped a lot pulls its artist down a little, never a lot.
                val part = max(score, MIN_PART)
                library.artistsOf(song).forEach { artistRaw.merge(it.name, part, Double::plus) }
                albumKey(song)?.let { albumRaw.merge(it, part, Double::plus) }
                genreOf(song)?.let { genreRaw.merge(it, part, Double::plus) }
            }
            interest.artists.forEach { (name, boost) -> artistRaw.merge(name, boost, Double::plus) }
            interest.albums.forEach { (key, boost) -> albumRaw.merge(key, boost, Double::plus) }
            val recent = recentPaths(plays, songOf, now)
            return Taste(
                now = now,
                weights = weights,
                songs = songs,
                scores = scores,
                artists = scaled(artistRaw),
                albums = scaled(albumRaw),
                genres = scaled(genreRaw),
                follows = follows(plays, songOf, weights),
                friends = friendLove(friendPlays, library, now),
                recentPaths = recent,
                similar = inLibrary(similar, library),
                outcomes = outcomes,
            )
        }

        /** The songs played lately: the last [RECENT_PLAYS] plays, or all of the last [RECENT_MS], whichever holds more. */
        private fun recentPaths(plays: List<LoggedPlay>, songOf: (LoggedPlay) -> Song?, now: Long): Set<String> {
            val since = now - RECENT_MS
            val out = HashSet<String>()
            for (i in plays.indices.reversed()) {
                val play = plays[i]
                if (plays.size - i > RECENT_PLAYS && play.startedAt < since) break
                songOf(play)?.let { out += it.path }
            }
            return out
        }
    }
}

/** A suggested song does not come back within this many plays ... */
const val RECENT_PLAYS: Int = 50

/** ... or this long, whichever is longer (user, 2026-11-22). */
const val RECENT_MS: Long = 24 * 3_600_000L

/** The least a song gives its artist, album and genre: a song skipped a lot pulls them down only this much. */
private const val MIN_PART = -0.5

/** Whether [song] is music suggestions may use: in the library's music (not hidden, not a book) and no podcast. */
fun music(library: Library, song: Song): Boolean = library.shows(song) && library.bookOf(song.path) == null && !song.isPodcast && !song.isAudiobook

/** [song]'s genre as the library groups it: its tag trimmed; null without one. */
fun genreOf(song: Song): String? = song.genre?.trim()?.takeIf(String::isNotEmpty)

/** [raw] scaled to 0 to 1 by its largest value; those at or below 0 left out. */
private fun scaled(raw: Map<String, Double>): Map<String, Double> {
    val top = raw.values.maxOrNull()?.takeIf { it > 0 } ?: return emptyMap()
    return raw.filterValues { it > 0 }.mapValues { it.value / top }
}

/**
 * Each song's [SongTaste] from [plays] (the oldest first), Repeat one laps past [Weights.lapsPerDay] a day counting
 * nothing; each play a suggestion started goes into [outcomes], with the bucket its song was in then.
 */
@Suppress("LongParameterList", "CyclomaticComplexMethod", "LongMethod")
private fun songTastes(
    plays: List<LoggedPlay>,
    songOf: (LoggedPlay) -> Song?,
    favourites: Map<String, Long>,
    library: Library,
    zone: ZoneId,
    weights: Weights,
    outcomes: MutableList<Outcome>,
): Map<String, SongTaste> {
    class Builder(val path: String) {
        val times = ArrayList<Long>()
        val values = ArrayList<Double>()
        var counted = 0
        var picks = 0
        var heardFully = 0
        var earlySkips = 0
        var suggested = 0
        var ended = 0
        var first: Long? = null
        var last: Long? = null
        var lastCounted: Long? = null
        var lastSkip: Long? = null
        var love = 0.0
        val byMonth = HashMap<YearMonth, Int>()
        val playedByMonth = HashMap<YearMonth, Int>()
        var day: LocalDate? = null
        var playsThatDay = 0
    }
    val builders = LinkedHashMap<String, Builder>()
    var before: LoggedPlay? = null
    var beforePath: String? = null
    for (play in plays) {
        val song = songOf(play)
        if (song == null) {
            before = play
            beforePath = null
            continue
        }
        val b = builders.getOrPut(song.path) { Builder(song.path) }
        val day = dayOf(play.startedAt, play.zoneOffsetMinutes, zone)
        if (day != b.day) {
            b.day = day
            b.playsThatDay = 0
        }
        b.playsThatDay++
        val lap = beforePath == song.path && before?.end?.how == PlayEnd.FINISHED && play.start?.by == StartedBy.AUTO
        val value = if (lap && b.playsThatDay > weights.lapsPerDay) 0.0 else playValue(play, song.durationMs, weights)
        b.times += play.startedAt
        b.values += value
        b.love += value
        if (play.start?.by == StartedBy.SUGGESTED) outcomes += Outcome(play.startedAt, bucketAt(b.counted, b.last, play.startedAt), outcomeOf(play, song.durationMs, weights))
        if (play.counted) {
            b.counted++
            b.lastCounted = play.startedAt
            b.playedByMonth.merge(YearMonth.from(day), 1, Int::plus)
        }
        if (b.first == null) b.first = play.startedAt
        b.last = play.startedAt
        val skipped = earlySkip(play, weights)
        when (play.start?.by) {
            // A pick skipped at once was a mistap, not a choice.
            StartedBy.PICKED -> if (!skipped) {
                b.picks++
                b.byMonth.merge(YearMonth.from(day), 1, Int::plus)
            }
            StartedBy.SUGGESTED -> b.suggested++
            else -> Unit
        }
        if (play.end != null) b.ended++
        if (heardToEnd(play, song.durationMs, weights)) b.heardFully++
        if (skipped) {
            b.earlySkips++
            b.lastSkip = play.startedAt
        }
        before = play
        beforePath = song.path
    }
    val out = HashMap<String, SongTaste>(builders.size + favourites.size)
    builders.values.forEach { b ->
        out[b.path] = SongTaste(
            b.path, b.times.toLongArray(), b.values.toDoubleArray(), b.counted, b.picks, b.heardFully, b.earlySkips, b.suggested, b.ended, b.first, b.last, b.lastCounted,
            b.love, b.byMonth, b.playedByMonth, b.lastSkip, favourites[b.path],
        )
    }
    favourites.forEach { (path, at) ->
        if (path !in out && library.song(path)?.let { music(library, it) } == true) {
            out[path] = SongTaste(path, LongArray(0), DoubleArray(0), 0, 0, 0, 0, 0, 0, null, null, null, 0.0, emptyMap(), emptyMap(), null, at)
        }
    }
    return out
}

/**
 * The next-song table: for each song, the songs played right after it in the same session (started within
 * [SESSION_GAP_MS] of it), weighed by how the next one began: picked by hand counts most, a song that came on in a list
 * in order some, one that came on in Shuffle little (its place was chance), one a suggestion started nothing (no
 * learning from its own picks), and none when it was skipped early. The [FOLLOWS_KEPT] strongest per song.
 */
internal fun follows(plays: List<LoggedPlay>, songOf: (LoggedPlay) -> Song?, weights: Weights): Map<String, List<Follow>> {
    val table = HashMap<String, HashMap<String, Double>>()
    var before: Song? = null
    var beforeAt = Long.MIN_VALUE
    for (play in plays) {
        val song = songOf(play)
        val previous = before
        if (song != null && previous != null && song.path != previous.path && play.startedAt - beforeAt <= SESSION_GAP_MS && !earlySkip(play, weights)) {
            val start = play.start
            val weight = when {
                start == null -> FOLLOW_PLAIN
                start.by == StartedBy.SUGGESTED -> 0.0
                start.by == StartedBy.PICKED -> 1.0
                start.shuffled -> FOLLOW_SHUFFLED
                start.by == StartedBy.AUTO -> FOLLOW_IN_ORDER
                else -> FOLLOW_SHUFFLED
            }
            if (weight > 0) table.getOrPut(previous.path) { HashMap() }.merge(song.path, weight, Double::plus)
        }
        if (song != null) {
            before = song
            beforeAt = play.startedAt + play.heardMs
        } else {
            before = null
        }
    }
    return table.mapValues { (_, next) -> next.entries.sortedByDescending { it.value }.take(FOLLOWS_KEPT).map { Follow(it.key, it.value) } }
}

/** A song comes after another in the same session when it starts within this long of the other's end. */
private const val SESSION_GAP_MS: Long = 30 * 60_000L
private const val FOLLOW_PLAIN = 0.5
private const val FOLLOW_IN_ORDER = 0.5
private const val FOLLOW_SHUFFLED = 0.25
private const val FOLLOWS_KEPT = 10

/** "Often follows": a follow at least this strong (two picks, or four songs in order). */
const val FOLLOW_MIN: Double = 2.0

/** What the interaction log adds: small boosts per song, artist and album, each capped. */
private class Interest(val songs: Map<String, Double>, val artists: Map<String, Double>, val albums: Map<String, Double>)

/** The interactions of the last [INTEREST_MS], each worth [INTEREST_EACH], at most [INTEREST_CAP] per song, artist or album. */
private fun interest(interactions: List<Interaction>, library: Library, now: Long): Interest {
    val songs = HashMap<String, Double>()
    val artists = HashMap<String, Double>()
    val albums = HashMap<String, Double>()
    interactions.filter { it.at in (now - INTEREST_MS)..now }.forEach { seen ->
        when (seen.kind) {
            InteractionKind.SEARCH_PICK, InteractionKind.SONG_OPENED, InteractionKind.LYRICS_OPENED ->
                seen.song?.let(library.resolver::find)?.takeIf { music(library, it) }?.let { songs.merge(it.path, INTEREST_EACH, Double::plus) }
            InteractionKind.ARTIST_OPENED -> seen.artist?.takeIf { library.artist(it) != null }?.let { artists.merge(it, INTEREST_EACH, Double::plus) }
            InteractionKind.ALBUM_OPENED -> seen.album?.takeIf { library.album(it) != null }?.let { albums.merge(it, INTEREST_EACH, Double::plus) }
        }
    }
    val capped = { map: Map<String, Double> -> map.mapValues { min(it.value, INTEREST_CAP) } }
    return Interest(capped(songs), capped(artists), capped(albums))
}

internal const val INTEREST_MS: Long = 90 * 86_400_000L
private const val INTEREST_EACH = 0.1
private const val INTEREST_CAP = 0.5

/** Friends' love of one of the user's songs: the friends who love it, and their plays of it this week. */
data class FriendLove(val friends: Int, val playsThisWeek: Int)

/**
 * Friends' plays matched to the library's music by title and artist ([TrackNames.normalize]) and, when both are known,
 * a length within [LENGTH_SLACK_MS]: a friend loves a song that is a favourite on their phone or that they played
 * [FRIEND_LOVES_AT] times or more in the last [FRIENDS_MS].
 */
private fun friendLove(plays: List<FriendPlay>, library: Library, now: Long): Map<String, FriendLove> {
    if (plays.isEmpty()) return emptyMap()
    val byName = HashMap<String, MutableList<Song>>()
    library.songs.forEach { song -> if (music(library, song)) byName.getOrPut(nameKey(song.title, song.artist)) { ArrayList() } += song }
    val perSong = HashMap<String, HashMap<String, Pair<Int, Boolean>>>()
    val week = HashMap<String, Int>()
    plays.filter { it.at in (now - FRIENDS_MS)..now }.forEach { play ->
        val song = byName[nameKey(play.title, play.artist)]?.firstOrNull { song -> play.durationMs == null || abs(song.durationMs - play.durationMs) <= LENGTH_SLACK_MS }
            ?: return@forEach
        perSong.getOrPut(song.path) { HashMap() }.merge(play.friend, 1 to play.favourite) { a, b -> (a.first + b.first) to (a.second || b.second) }
        if (play.at >= now - WEEK_MS) week.merge(song.path, 1, Int::plus)
    }
    return perSong.mapValues { (path, byFriend) ->
        FriendLove(byFriend.values.count { (count, favourite) -> favourite || count >= FRIEND_LOVES_AT }, week[path] ?: 0)
    }.filterValues { it.friends > 0 || it.playsThisWeek > 0 }
}

/** A song's title and artist as friends' plays are matched by (Insights' friends too, stats/InsightsPage.kt). */
internal fun nameKey(title: String, artist: String?): String = TrackNames.normalize(title) + "|" + TrackNames.normalize(artist.orEmpty())

internal const val FRIENDS_MS: Long = 30 * 86_400_000L
private const val WEEK_MS: Long = 7 * 86_400_000L
private const val FRIEND_LOVES_AT = 3
private const val LENGTH_SLACK_MS: Long = 2_000

/** [similar] with each list kept to the artists the library has, by the library's own name for them. */
private fun inLibrary(similar: Map<String, List<SimilarArtist>>, library: Library): Map<String, List<SimilarArtist>> {
    if (similar.isEmpty()) return emptyMap()
    val names = library.artists.associateBy { TrackNames.normalize(it.name) }
    return similar.mapValues { (_, list) -> list.mapNotNull { like -> names[TrackNames.normalize(like.name)]?.let { SimilarArtist(it.name, like.score) } }.distinctBy { it.name } }
        .filterValues { it.isNotEmpty() }
}

/**
 * How a play a suggestion started went: at [at], of a song in [bucket] then, [good] when heard to its end, false when
 * skipped early, null in between.
 */
data class Outcome(val at: Long, val bucket: Bucket, val good: Boolean?)

private fun outcomeOf(play: LoggedPlay, durationMs: Long, weights: Weights): Boolean? = when {
    heardToEnd(play, durationMs, weights) -> true
    earlySkip(play, weights) -> false
    else -> null
}
