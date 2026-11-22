package app.lyrify.suggestions

import app.lyrify.library.Album
import app.lyrify.library.Artist
import app.lyrify.library.Library
import app.lyrify.library.Song
import app.lyrify.library.albumKey
import app.lyrify.settings.Adventure
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

// From the taste to the songs each surface suggests: the three buckets and their candidates, the mix and how it learns,
// the freshness rules (no song again within 50 songs or a day, no artist twice within 3 songs, at most 2 of one album in
// a row) with the freshest of several draws kept (Spotify's "Fewer Repeats"), and each pick's reason. Pure.

/** Where a suggestion comes from: songs you love now, songs you loved that went quiet, songs new to you. */
enum class Bucket { FAMILIAR, REDISCOVER, DISCOVER }

/** A song played at most this often is new to you. */
private const val DISCOVER_MAX_PLAYS = 1

/** A song not played for this long has gone quiet. */
private const val QUIET_MS: Long = 60 * 86_400_000L

/** The bucket of a song with [counted] plays before [at], the last of any kind at [lastPlayedAt]. */
fun bucketAt(counted: Int, lastPlayedAt: Long?, at: Long): Bucket = when {
    counted <= DISCOVER_MAX_PLAYS -> Bucket.DISCOVER
    lastPlayedAt == null || at - lastPlayedAt >= QUIET_MS -> Bucket.REDISCOVER
    else -> Bucket.FAMILIAR
}

internal fun bucketOf(taste: Taste, path: String): Bucket = taste.songs[path].let { bucketAt(it?.counted ?: 0, it?.lastPlayedAt, taste.now) }

/** Why a song is suggested, in plain words on screen (ui/SuggestionScreens.kt says them). */
sealed interface Reason {
    /** "You picked this 9 times in October". */
    data class Picked(val times: Int, val month: YearMonth) : Reason

    /** "You played this 9 times in October". */
    data class Played(val times: Int, val month: YearMonth) : Reason

    /** "Not played since March". */
    data class NotSince(val lastPlayedAt: Long) : Reason

    /** "Often follows Copper Sky". */
    data class Follows(val song: Song) : Reason

    /** "You often play the two together". */
    data class Together(val song: Song) : Reason

    /** "New from Kestrel, who you play a lot". */
    data class NewFrom(val artist: String) : Reason

    /** "Like Kestrel, who you play a lot": a similar artist's song. */
    data class LikeArtist(val artist: String) : Reason

    /** "2 friends love this". */
    data class Friends(val count: Int) : Reason

    /** "Popular with your friends this week". */
    data object FriendsThisWeek : Reason

    /** "You hear it to the end every time". */
    data object HeardToEnd : Reason

    /** "One of your favourites". */
    data object Favourite : Reason

    /** "Added in September, played once" ([plays] 0: "not played yet"); no date when MediaStore gave none. */
    data class Added(val addedAt: Long?, val plays: Int) : Reason

    /** "Same artist" ([sameAlbum]: "Same artist, same album"). */
    data class SameArtist(val sameAlbum: Boolean) : Reason

    /** "Indie folk" ([sameYears]: "Indie folk, from the same years"). */
    data class SameGenre(val genre: String, val sameYears: Boolean) : Reason

    /** "From the same years". */
    data object SameYears : Reason

    /** Against it, in Why this?: "You skipped it once last week". */
    data class Skipped(val times: Int, val lastAt: Long) : Reason
}

/** A suggested song, the bucket it came from, and its top reason. */
data class Pick(val song: Song, val bucket: Bucket, val reason: Reason)

/** A candidate: [song] from [bucket], weighed [weight] (more is likelier). */
data class Scored(val song: Song, val weight: Double, val bucket: Bucket)

/** The candidates of each bucket, the heaviest first. */
class Pools(val familiar: List<Scored>, val rediscover: List<Scored>, val discover: List<Scored>) {
    fun of(bucket: Bucket): List<Scored> = when (bucket) {
        Bucket.FAMILIAR -> familiar
        Bucket.REDISCOVER -> rediscover
        Bucket.DISCOVER -> discover
    }
}

/** A song skipped early this often, and liked less than not at all, is skipped a lot: never suggested, whoever loves it. */
private const val SKIPPED_A_LOT = 2

private fun skippedALot(taste: SongTaste?): Boolean = taste != null && taste.earlySkips >= SKIPPED_A_LOT && taste.love < 0

/** A quiet song is a rediscovery when it was liked at least this much (or is a favourite). */
private const val REDISCOVER_LOVE = 3.0

/** An artist liked at least this much (0 to 1) is one "you play a lot". */
private const val LIKED_ARTIST = 0.3

/** Every new song has this much weight, so a phone with no history still finds something. */
private const val DISCOVER_BASE = 0.05

/** Friends add at most this to a new song's weight (a liked artist adds up to 0.6): small, below your own taste. */
private const val FRIENDS_CAP = 0.15

/** Friends add at most this share to a song you play. */
private const val FRIENDS_SHARE_CAP = 0.1

/**
 * The candidates in [library]'s music for [taste], without the songs at [avoid] and those skipped a lot:
 * - familiar: played lately and liked, weighed by their score;
 * - rediscover: quiet for 60 days and loved before (or a favourite), weighed by how much;
 * - discover: never or once played, weighed by their artists', genre's and album's place in your taste, artists like
 *   the ones you play (similar artists), and friends' love (small, capped).
 */
fun pools(taste: Taste, library: Library, avoid: Set<String>): Pools {
    val familiar = ArrayList<Scored>()
    val rediscover = ArrayList<Scored>()
    val discover = ArrayList<Scored>()
    val likeness = similarLikeness(taste)
    library.songs.forEach { song ->
        if (song.path in avoid || !music(library, song)) return@forEach
        val mine = taste.songs[song.path]
        if (skippedALot(mine)) return@forEach
        val friends = taste.friends[song.path]
        when (bucketOf(taste, song.path)) {
            Bucket.FAMILIAR -> taste.score(song.path).takeIf { it > 0 }?.let { familiar += Scored(song, it * friendsShare(friends), Bucket.FAMILIAR) }
            Bucket.REDISCOVER -> mine?.takeIf { it.love >= REDISCOVER_LOVE || it.favourite }?.let {
                rediscover += Scored(song, (max(it.love, 0.0) + if (it.favourite) taste.weights.favourite else 0.0) * friendsShare(friends), Bucket.REDISCOVER)
            }
            Bucket.DISCOVER -> discover += Scored(song, discoverWeight(song, taste, library, likeness, friends), Bucket.DISCOVER)
        }
    }
    val heaviest = compareByDescending<Scored> { it.weight }.thenBy { it.song.path }
    return Pools(familiar.sortedWith(heaviest), rediscover.sortedWith(heaviest), discover.sortedWith(heaviest))
}

private fun friendsShare(friends: FriendLove?): Double = 1 + min(FRIENDS_SHARE_CAP, FRIENDS_EACH * (friends?.friends ?: 0))

private const val FRIENDS_EACH = 0.05
private const val FRIENDS_WEEK_EACH = 0.02

private fun discoverWeight(song: Song, taste: Taste, library: Library, likeness: Map<String, Pair<String, Double>>, friends: FriendLove?): Double {
    val artist = library.artistsOf(song).maxOfOrNull { taste.artist(it.name) } ?: 0.0
    val genre = genreOf(song)?.let(taste::genre) ?: 0.0
    val album = albumKey(song)?.let(taste::album) ?: 0.0
    val similar = library.artistsOf(song).maxOfOrNull { likeness[it.name]?.second ?: 0.0 } ?: 0.0
    val friendly = min(FRIENDS_CAP, FRIENDS_EACH * (friends?.friends ?: 0) + FRIENDS_WEEK_EACH * (friends?.playsThisWeek ?: 0))
    return DISCOVER_BASE + ARTIST_PART * artist + GENRE_PART * genre + ALBUM_PART * album + SIMILAR_PART * similar + friendly
}

private const val ARTIST_PART = 0.6
private const val GENRE_PART = 0.3
private const val ALBUM_PART = 0.1
private const val SIMILAR_PART = 0.5

/** For each artist like one you play: the liked artist it is most like, and how much (closeness times your liking). */
private fun similarLikeness(taste: Taste): Map<String, Pair<String, Double>> {
    val out = HashMap<String, Pair<String, Double>>()
    taste.similar.forEach { (liked, likes) ->
        val mine = taste.artist(liked)
        if (mine < LIKED_ARTIST) return@forEach
        likes.forEach { like ->
            val strength = like.score * mine
            if (strength > (out[like.name]?.second ?: 0.0) && taste.artist(like.name) < LIKED_ARTIST) out[like.name] = liked to strength
        }
    }
    return out
}

/**
 * The mix right now: the share of suggestions that are rediscoveries or new songs ([explore]), and of new songs among
 * those ([discover]). The rest are songs you love.
 */
data class Mix(val explore: Double, val discover: Double) {
    val familiarShare: Double get() = 1 - explore
    val rediscoverShare: Double get() = explore * (1 - discover)
    val discoverShare: Double get() = explore * discover

    /** Smart shuffle puts in about 1 suggestion in this many songs ("about 1 song in 3"). */
    val oneIn: Int get() = (1 / explore.coerceAtLeast(MIN_EXPLORE)).roundToInt().coerceIn(MIN_GAP, MAX_GAP)
}

/** Where the mix starts for [adventure]: about 1 suggestion in 3 a rediscovery or a new song for Mix (user, 2026-11-22). */
fun startOf(adventure: Adventure): Mix = when (adventure) {
    Adventure.FAMILIAR -> Mix(0.2, 0.35)
    Adventure.MIX -> Mix(1.0 / 3, 0.5)
    Adventure.DISCOVER -> Mix(0.5, 0.6)
}

/** Each outcome moves the mix this much: slowly. */
private const val MIX_STEP = 0.01

/** The mix moves at most this far from its start, the rediscover/new split [SPLIT_RANGE]. */
private const val MIX_RANGE = 0.15
private const val SPLIT_RANGE = 0.2

/** The mix learns from the last this many outcomes. */
private const val MIX_MEMORY = 200

/**
 * The mix now: [adventure]'s start, moved a step by each of the last [MIX_MEMORY] [outcomes] of rediscoveries and new
 * songs (a bandit, slow and bounded): heard to the end, toward more of that kind; skipped early, toward less. Familiar
 * picks and plays neither heard through nor skipped early do not move it.
 */
fun mixOf(adventure: Adventure, outcomes: List<Outcome>): Mix {
    val start = startOf(adventure)
    val exploreRange = max(MIN_EXPLORE, start.explore - MIX_RANGE)..min(MAX_EXPLORE, start.explore + MIX_RANGE)
    val splitRange = (start.discover - SPLIT_RANGE)..(start.discover + SPLIT_RANGE)
    var explore = start.explore
    var discover = start.discover
    outcomes.takeLast(MIX_MEMORY).forEach { outcome ->
        val good = outcome.good ?: return@forEach
        if (outcome.bucket == Bucket.FAMILIAR) return@forEach
        val step = if (good) MIX_STEP else -MIX_STEP
        explore = (explore + step).coerceIn(exploreRange)
        discover = (discover + if (outcome.bucket == Bucket.DISCOVER) step else -step).coerceIn(splitRange)
    }
    return Mix(explore, discover)
}

private const val MIN_EXPLORE = 0.1
private const val MAX_EXPLORE = 0.6

/** A month with at least this many picks (or plays) is worth saying. */
private const val MONTH_MIN = 3

/** The months a "this month" reason looks back over. */
private const val MONTHS_BACK = 3L

/** Heard to the end this share of its plays, at least [MONTH_MIN] times: "every time". */
private const val EVERY_TIME = 0.9

/** The top reason [song] (from [bucket]) is suggested, right [after] a song when there is one. */
fun reasonFor(song: Song, bucket: Bucket, taste: Taste, library: Library, zone: ZoneId, after: Song? = null): Reason =
    followReason(song, taste, after) ?: when (bucket) {
        Bucket.REDISCOVER -> taste.songs[song.path]?.lastPlayedAt?.let { Reason.NotSince(it) } ?: familiarReasons(song, taste, zone).firstOrNull()
        Bucket.DISCOVER -> discoverReasons(song, taste, library).firstOrNull()
        Bucket.FAMILIAR -> familiarReasons(song, taste, zone).firstOrNull()
    } ?: added(song, taste)

private fun followReason(song: Song, taste: Taste, after: Song?): Reason? =
    after?.takeIf { taste.follows[it.path]?.any { follow -> follow.path == song.path && follow.weight >= FOLLOW_MIN } == true }?.let { Reason.Follows(it) }

private fun added(song: Song, taste: Taste): Reason = Reason.Added(song.addedAt, taste.songs[song.path]?.counted ?: 0)

/** The reasons a song you play is liked, the strongest first. */
private fun familiarReasons(song: Song, taste: Taste, zone: ZoneId): List<Reason> {
    val mine = taste.songs[song.path] ?: return listOfNotNull(Reason.Favourite.takeIf { song.path in taste.songs })
    val since = YearMonth.from(Instant.ofEpochMilli(taste.now).atZone(zone)).minusMonths(MONTHS_BACK - 1)
    fun best(byMonth: Map<YearMonth, Int>) = byMonth.filterKeys { it >= since }.maxWithOrNull(compareBy<Map.Entry<YearMonth, Int>> { it.value }.thenBy { it.key })
    return listOfNotNull(
        best(mine.picksByMonth)?.takeIf { it.value >= MONTH_MIN }?.let { Reason.Picked(it.value, it.key) },
        best(mine.playsByMonth)?.takeIf { it.value >= MONTH_MIN }?.let { Reason.Played(it.value, it.key) },
        Reason.HeardToEnd.takeIf { mine.heardFully >= MONTH_MIN && mine.earlySkips == 0 && mine.heardFully >= mine.ended * EVERY_TIME },
        Reason.Favourite.takeIf { mine.favourite },
        taste.friends[song.path]?.friends?.takeIf { it >= 2 }?.let { Reason.Friends(it) },
        best(mine.playsByMonth)?.let { Reason.Played(it.value, it.key) },
    )
}

/** The reasons a song new to you is suggested, the strongest first. */
private fun discoverReasons(song: Song, taste: Taste, library: Library): List<Reason> {
    val counted = taste.songs[song.path]?.counted ?: 0
    val liked = library.artistsOf(song).filter { taste.artist(it.name) >= LIKED_ARTIST }.maxByOrNull { taste.artist(it.name) }
    val like = library.artistsOf(song).mapNotNull { similarLikeness(taste)[it.name] }.maxByOrNull { it.second }
    return listOfNotNull(
        liked?.takeIf { counted == 0 }?.let { Reason.NewFrom(it.name) },
        like?.let { Reason.LikeArtist(it.first) },
        taste.friends[song.path]?.friends?.takeIf { it > 0 }?.let { Reason.Friends(it) },
        added(song, taste),
    )
}

/** Every reason for [song] (the strongest first) and those against it, for Why this?. */
data class Why(val reasons: List<Reason>, val against: List<Reason>)

fun why(song: Song, taste: Taste, library: Library, zone: ZoneId, after: Song? = null): Why {
    val bucket = bucketOf(taste, song.path)
    val reasons = listOfNotNull(followReason(song, taste, after)) + when (bucket) {
        Bucket.DISCOVER -> discoverReasons(song, taste, library)
        Bucket.REDISCOVER -> listOfNotNull(taste.songs[song.path]?.lastPlayedAt?.let { Reason.NotSince(it) }) + familiarReasons(song, taste, zone)
        Bucket.FAMILIAR -> familiarReasons(song, taste, zone)
    }
    val mine = taste.songs[song.path]
    val against = listOfNotNull(mine?.lastSkipAt?.let { Reason.Skipped(mine.earlySkips, it) })
    return Why(reasons.distinct().take(WHY_REASONS), against)
}

private const val WHY_REASONS = 4

// Freshness

/** No artist again within this many songs. */
private const val ARTIST_GAP = 3

/** At most this many songs of one album in a row, unless the user started that album. */
private const val ALBUM_RUN = 2

/** Draws tried; the freshest kept. */
private const val TRIES = 8

/**
 * Whether [song] may come between [before] (the songs before it, the latest last) and [after] (the songs after it, when it
 * goes in among a queue's songs): no artist of it within [ARTIST_GAP] either side ([sameArtist]), and no more than
 * [ALBUM_RUN] songs of its album in a row with it, unless the user started that album.
 */
private fun fits(song: Song, before: List<Song>, startedAlbum: String?, library: Library, after: List<Song> = emptyList()): Boolean {
    if ((before.takeLast(ARTIST_GAP) + after.take(ARTIST_GAP)).any { sameArtist(it, song, library) }) return false
    val album = albumKey(song) ?: return true
    return album == startedAlbum || before.takeLastWhile { albumKey(it) == album }.size + 1 + after.takeWhile { albumKey(it) == album }.size <= ALBUM_RUN
}

/** A weighted draw of up to [n] of [pool] without putting back (each kept with a key u^(1/w), the largest first). */
private fun draw(pool: List<Scored>, n: Int, random: Random): List<Scored> {
    if (n <= 0 || pool.isEmpty()) return emptyList()
    return pool.map { it to ln(random.nextDouble().coerceAtLeast(Double.MIN_VALUE)) / max(it.weight, MIN_WEIGHT) }
        .sortedByDescending { it.second }
        .take(n)
        .map { it.first }
}

private const val MIN_WEIGHT = 1e-6

/** How many of [count] songs each bucket gets under [mix]: rounded, the rest familiar. */
private fun shares(mix: Mix, count: Int): Map<Bucket, Int> {
    val rediscover = (count * mix.rediscoverShare).roundToInt()
    val discover = (count * mix.discoverShare).roundToInt()
    return mapOf(Bucket.FAMILIAR to count - rediscover - discover, Bucket.REDISCOVER to rediscover, Bucket.DISCOVER to discover)
}

/**
 * Up to [count] songs from [drawn] (per bucket, in their drawn order), as many of each as [wanted] says, the buckets
 * interleaved by share, each song fitting the freshness rules after [before] and the songs placed so far.
 */
@Suppress("LongParameterList")
private fun arrange(drawn: Map<Bucket, List<Scored>>, wanted: Map<Bucket, Int>, before: List<Song>, startedAlbum: String?, library: Library, random: Random): List<Scored> {
    val total = wanted.values.sum()
    val left = drawn.mapValues { it.value.toMutableList() }
    val taken = HashMap<Bucket, Int>()
    val placed = ArrayList<Scored>()
    val songs = before.toMutableList()
    while (placed.size < total) {
        val step = placed.size + 1
        val order = Bucket.entries.filter { (left[it]?.isNotEmpty() == true) }
            .sortedByDescending { (wanted[it] ?: 0).toDouble() * step / total - (taken[it] ?: 0) + random.nextDouble() * TIE }
            .sortedBy { (taken[it] ?: 0) >= (wanted[it] ?: 0) }
        val next = order.firstNotNullOfOrNull { bucket -> left.getValue(bucket).firstOrNull { fits(it.song, songs, startedAlbum, library) } } ?: break
        left.getValue(next.bucket).remove(next)
        taken.merge(next.bucket, 1, Int::plus)
        placed += next
        songs += next.song
    }
    return placed
}

private const val TIE = 1e-3

/**
 * How stale [songs] are: each song played lately loses points, the more the earlier it comes and the more recently it
 * was played (Spotify's "Fewer Repeats"); a short list loses [SHORT] per missing song.
 */
private fun staleness(songs: List<Song>, taste: Taste, count: Int): Double =
    songs.withIndex().sumOf { (i, song) ->
        val last = taste.songs[song.path]?.lastPlayedAt ?: return@sumOf 0.0
        fade(taste.now - last, taste.weights.decay) / (1 + i)
    } + SHORT * (count - songs.size)

private const val SHORT = 100.0

/** The freshest of [TRIES] arrangements of [count] songs drawn from [pools] by [wanted]. */
@Suppress("LongParameterList")
private fun freshest(pools: Pools, wanted: Map<Bucket, Int>, count: Int, before: List<Song>, startedAlbum: String?, taste: Taste, library: Library, random: Random): List<Scored> {
    var best: List<Scored> = emptyList()
    var bestScore = Double.MAX_VALUE
    repeat(TRIES) {
        val drawn = Bucket.entries.associateWith { bucket -> draw(pools.of(bucket), (wanted[bucket] ?: 0) * 2 + EXTRA, random) }
        val arranged = arrange(drawn, wanted, before, startedAlbum, library, random)
        val score = staleness(arranged.map { it.song }, taste, count)
        if (score < bestScore) {
            best = arranged
            bestScore = score
        }
    }
    return best
}

/** Each draw takes this many more than wanted, so the rules have room. */
private const val EXTRA = 4

/** [wanted] with the shortfall of buckets whose pool is too small given to the others, familiar first. */
private fun filled(wanted: Map<Bucket, Int>, pools: Pools): Map<Bucket, Int> {
    val out = wanted.mapValues { (bucket, n) -> min(n, pools.of(bucket).size) }.toMutableMap()
    var missing = wanted.values.sum() - out.values.sum()
    for (bucket in listOf(Bucket.FAMILIAR, Bucket.REDISCOVER, Bucket.DISCOVER)) {
        val room = pools.of(bucket).size - out.getValue(bucket)
        val add = min(room, missing)
        out[bucket] = out.getValue(bucket) + add
        missing -= add
    }
    return out
}

/** For you: [count] songs for today, mostly loved, some rediscovered, a few new, kept fresh, each with its reason. */
@Suppress("LongParameterList")
fun forYou(taste: Taste, library: Library, mix: Mix, avoid: Set<String>, random: Random, zone: ZoneId, count: Int = FOR_YOU_SONGS): List<Pick> {
    val pools = pools(taste, library, avoid)
    val songs = freshest(pools, filled(shares(mix, count), pools), count, emptyList(), null, taste, library, random)
    return songs.mapIndexed { i, it -> Pick(it.song, it.bucket, reasonFor(it.song, it.bucket, taste, library, zone, songs.getOrNull(i - 1)?.song)) }
}

const val FOR_YOU_SONGS: Int = 20

/** Autoplay leans this much more toward a song by an artist of the queue, and [LIKE_GENRE] toward one of its genres. */
private const val LIKE_ARTIST = 2.0
private const val LIKE_GENRE = 1.0

/** Autoplay looks at this many of the queue's last songs for what it is like. */
private const val QUEUE_TAIL = 20

/**
 * Autoplay: [count] songs like [queue]'s (its artists and genres weigh more), to play after it. The first is a song that
 * often follows the queue's last song, when there is one and it fits; each comes with its reason.
 */
@Suppress("LongParameterList")
fun autoplay(taste: Taste, library: Library, mix: Mix, queue: List<Song>, avoid: Set<String>, random: Random, zone: ZoneId, count: Int = AUTOPLAY_SONGS): List<Pick> {
    val tail = queue.takeLast(QUEUE_TAIL)
    val artists = tail.flatMap { library.artistsOf(it).map(Artist::name) }.toSet()
    val genres = tail.mapNotNull(::genreOf).toSet()
    val last = queue.lastOrNull()
    val follow = last?.let { taste.follows[it.path] }.orEmpty()
        .firstOrNull { it.weight >= FOLLOW_MIN && it.path !in avoid }
        ?.let { library.song(it.path) }
        ?.takeIf { music(library, it) && !skippedALot(taste.songs[it.path]) && fits(it, tail, null, library) }
    val base = pools(taste, library, avoid + listOfNotNull(follow?.path))
    val like = { scored: Scored ->
        val artist = if (library.artistsOf(scored.song).any { it.name in artists }) LIKE_ARTIST else 0.0
        val genre = if (genreOf(scored.song) in genres) LIKE_GENRE else 0.0
        scored.copy(weight = scored.weight * (1 + artist + genre))
    }
    val pools = Pools(base.familiar.map(like), base.rediscover.map(like), base.discover.map(like))
    val first = if (follow != null && last != null) Pick(follow, bucketOf(taste, follow.path), Reason.Follows(last)) else null
    val rest = count - (if (first != null) 1 else 0)
    val before = tail + listOfNotNull(first?.song)
    val songs = freshest(pools, filled(shares(mix, rest), pools), rest, before, null, taste, library, random)
    val picks = songs.mapIndexed { i, it ->
        val after = if (i == 0) first?.song ?: last else songs[i - 1].song
        Pick(it.song, it.bucket, reasonFor(it.song, it.bucket, taste, library, zone, after))
    }
    return listOfNotNull(first) + picks
}

/** Autoplay's batch: this many songs once the queue's own end, the next batch only when its last song plays (user 2026-11-22). */
const val AUTOPLAY_SONGS: Int = 20

/** A song of the queue after the one playing, and whether a suggestion put it there. */
data class Upcoming(val song: Song, val suggested: Boolean)

/** A suggestion Smart shuffle puts right after [after] (a place in the songs after the one playing; -1: right after it). */
data class Insert(val after: Int, val pick: Pick)

/**
 * Smart shuffle: suggestions to put among [upcoming] (the queue's songs after [playing], in order), so about
 * [Mix.explore] of the next [SMART_WINDOW] songs are suggestions, never two in a row: one after every so many of the
 * queue's own songs, unless one already waits close ahead. Each is a rediscovery or a new song (a song that often
 * follows the one before it first), fitting the freshness rules with the songs around it; [startedAlbum] is the
 * album the user started, whose songs may run.
 */
@Suppress("LongParameterList", "CyclomaticComplexMethod")
fun smartInserts(taste: Taste, library: Library, mix: Mix, playing: Song?, upcoming: List<Upcoming>, avoid: Set<String>, random: Random, zone: ZoneId, startedAlbum: String? = null): List<Insert> {
    val gap = mix.oneIn - 1
    val window = upcoming.take(SMART_WINDOW)
    val slots = ArrayList<Int>()
    var run = if (playing != null) 1 else 0
    window.forEachIndexed { i, item ->
        if (item.suggested) {
            run = 0
            return@forEachIndexed
        }
        run++
        // A suggestion waiting just past the window counts too: the window moves on a song at a time.
        val waiting = upcoming.subList(i + 1, min(upcoming.size, i + 1 + gap)).any { it.suggested }
        if (run >= gap && !waiting) {
            slots += i
            run = 0
        }
    }
    if (slots.isEmpty()) return emptyList()
    val pools = pools(taste, library, avoid)
    val rediscover = (slots.size * (1 - mix.discover)).roundToInt()
    val wanted = mapOf(Bucket.REDISCOVER to rediscover, Bucket.DISCOVER to slots.size - rediscover)
    val drawn = Bucket.entries.associateWith { bucket -> draw(pools.of(bucket), (wanted[bucket] ?: 0) * 2 + EXTRA, random).toMutableList() }
    val sequence = listOfNotNull(playing) + upcoming.map { it.song }
    val offset = if (playing != null) 1 else 0
    val used = HashSet<String>()
    val out = ArrayList<Insert>()
    val taken = HashMap<Bucket, Int>()
    for (slot in slots) {
        val prev = sequence[slot + offset]
        val before = buildList {
            addAll(sequence.subList(0, offset))
            for (i in 0..slot) {
                add(upcoming[i].song)
                out.filter { it.after == i }.forEach { add(it.pick.song) }
            }
        }
        val after = sequence.subList(slot + offset + 1, min(sequence.size, slot + offset + 1 + ARTIST_GAP))
        val fitsHere = { song: Song -> song.path !in used && fits(song, before, startedAlbum, library, after) }
        val follow = taste.follows[prev.path].orEmpty().firstOrNull { it.weight >= FOLLOW_MIN && it.path !in avoid }
            ?.let { library.song(it.path) }?.takeIf { music(library, it) && !skippedALot(taste.songs[it.path]) && fitsHere(it) }
        val pick = if (follow != null) {
            Pick(follow, bucketOf(taste, follow.path), Reason.Follows(prev))
        } else {
            val order = listOf(Bucket.REDISCOVER, Bucket.DISCOVER).sortedByDescending { (wanted[it] ?: 0) - (taken[it] ?: 0) + random.nextDouble() * TIE }
            val chosen = order.firstNotNullOfOrNull { bucket -> drawn.getValue(bucket).firstOrNull { fitsHere(it.song) } } ?: continue
            drawn.getValue(chosen.bucket).remove(chosen)
            Pick(chosen.song, chosen.bucket, reasonFor(chosen.song, chosen.bucket, taste, library, zone, prev))
        }
        taken.merge(pick.bucket, 1, Int::plus)
        used += pick.song.path
        out += Insert(slot, pick)
    }
    return out
}

/** Whether [a] and [b] share an artist, by the names [library] reads their artist tags as: a split credit ("Kestrel, Mira Solen") names each. */
private fun sameArtist(a: Song, b: Song, library: Library): Boolean {
    val names = library.artistNames(a.artist).mapTo(HashSet()) { it.lowercase() }
    return library.artistNames(b.artist).any { it.lowercase() in names }
}

private const val MIN_GAP = 2
private const val MAX_GAP = 6

const val SMART_WINDOW: Int = 12

/** What More like this starts from. */
sealed interface Seed {
    data class OfSong(val song: Song) : Seed

    data class OfAlbum(val album: Album) : Seed

    data class OfArtist(val artist: Artist) : Seed
}

/** What More like this is built from: the chips on its page. */
enum class Basis { TOGETHER, ARTIST, GENRE, YEARS }

/** More like this: [picks], the [bases] that gave any (the chips to show, in order), and the seed's [genre]. */
data class MoreLike(val picks: List<Pick>, val bases: List<Basis>, val genre: String?)

/** Songs within this many years of the seed's are "from the same years". */
private const val SAME_YEARS = 3

private const val TOGETHER_PART = 3.0
private const val SAME_ARTIST_PART = 2.0
private const val SAME_ALBUM_PART = 0.5
private const val LIKE_ARTIST_PART = 2.0
private const val GENRE_ONLY_PART = 1.0
private const val YEARS_PART = 0.5
private const val FRIENDS_WEEK_PART = 0.3
private const val TASTE_PART = 0.1
private const val TASTE_CAP = 5.0

/**
 * Songs that go with [seed], from [bases] only, without the seed's songs, the songs at [avoid] and songs skipped a lot,
 * the best first: songs played around the seed's (both ways in the next-song table), its artist's (or for an artist,
 * the artists like it), its genre's (other artists), its years'; songs you like weigh a little more, and friends' songs
 * of the week a little. Each with the reason that weighed most.
 */
@Suppress("LongParameterList", "CyclomaticComplexMethod", "LongMethod")
fun moreLike(taste: Taste, library: Library, seed: Seed, bases: Set<Basis>, avoid: Set<String>, zone: ZoneId, limit: Int = MORE_LIKE_SONGS): MoreLike {
    val seeds = when (seed) {
        is Seed.OfSong -> listOf(seed.song)
        is Seed.OfAlbum -> seed.album.songs
        is Seed.OfArtist -> seed.artist.songs
    }
    val seedPaths = seeds.mapTo(HashSet()) { it.path }
    val seedArtists = seeds.flatMap { library.artistsOf(it).map(Artist::name) }.toSet()
    val seedAlbums = seeds.mapNotNull(::albumKey).toSet()
    val genre = seeds.mapNotNull(::genreOf).groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
    val years = seeds.mapNotNull { it.year }.let { if (it.isEmpty()) null else (it.min() - SAME_YEARS)..(it.max() + SAME_YEARS) }
    val likes = seedArtists.flatMap { name -> taste.similar[name].orEmpty().map { name to it } }
    // The next-song table both ways: after a seed song, and before one.
    val together = HashMap<String, Pair<Song, Double>>()
    seeds.forEach { s ->
        taste.follows[s.path].orEmpty().forEach { together.merge(it.path, s to it.weight) { a, b -> a.first to a.second + b.second } }
    }
    taste.follows.forEach { (from, next) ->
        next.forEach { follow -> if (follow.path in seedPaths) library.song(follow.path)?.let { s -> together.merge(from, s to follow.weight) { a, b -> a.first to a.second + b.second } } }
    }
    val available = LinkedHashSet<Basis>()
    val scored = ArrayList<Triple<Song, Double, Reason>>()
    library.songs.forEach { song ->
        if (song.path in seedPaths || !music(library, song) || skippedALot(taste.songs[song.path])) return@forEach
        val artistsHere = library.artistsOf(song).map(Artist::name)
        val withSeed = together[song.path]
        val sameArtist = seed !is Seed.OfArtist && artistsHere.any { it in seedArtists }
        val like = likes.filter { (_, similar) -> similar.name in artistsHere }.maxByOrNull { it.second.score }
        val sameGenre = genre != null && genreOf(song) == genre && artistsHere.none { it in seedArtists }
        val sameYears = years != null && song.year != null && song.year in years
        if (withSeed != null) available += Basis.TOGETHER
        if (sameArtist || like != null) available += Basis.ARTIST
        if (sameGenre) available += Basis.GENRE
        if (sameYears) available += Basis.YEARS
        if (song.path in avoid) return@forEach
        var weight = 0.0
        var reason: Reason? = null
        if (Basis.YEARS in bases && sameYears) {
            weight += YEARS_PART
            reason = Reason.SameYears
        }
        if (Basis.GENRE in bases && sameGenre) {
            weight += GENRE_ONLY_PART
            reason = Reason.SameGenre(genre, sameYears && Basis.YEARS in bases)
        }
        if (Basis.ARTIST in bases && like != null) {
            weight += LIKE_ARTIST_PART * like.second.score
            reason = Reason.LikeArtist(like.first)
        }
        if (Basis.ARTIST in bases && sameArtist) {
            val sameAlbum = albumKey(song) in seedAlbums
            weight += SAME_ARTIST_PART + if (sameAlbum) SAME_ALBUM_PART else 0.0
            reason = Reason.SameArtist(sameAlbum)
        }
        if (Basis.TOGETHER in bases && withSeed != null) {
            weight += TOGETHER_PART + withSeed.second
            val (other, _) = withSeed
            val follows = taste.follows[other.path]?.any { it.path == song.path && it.weight >= FOLLOW_MIN } == true
            reason = if (follows) Reason.Follows(other) else Reason.Together(other)
        }
        if (reason == null) return@forEach
        if ((taste.friends[song.path]?.playsThisWeek ?: 0) > 0) weight += FRIENDS_WEEK_PART
        weight *= 1 + TASTE_PART * taste.score(song.path).coerceIn(0.0, TASTE_CAP)
        scored += Triple(song, weight, reason)
    }
    val picks = scored.sortedWith(compareByDescending<Triple<Song, Double, Reason>> { it.second }.thenBy { it.first.path })
        .take(limit)
        .map { (song, _, reason) -> Pick(song, bucketOf(taste, song.path), reason) }
    return MoreLike(picks, Basis.entries.filter { it in available }, genre)
}

const val MORE_LIKE_SONGS: Int = 30
