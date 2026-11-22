package app.lyrify.suggestions

import app.lyrify.library.Library
import app.lyrify.stats.LoggedPlay
import app.lyrify.stats.StartedBy
import java.util.PriorityQueue
import java.util.TreeSet

/**
 * Suggestions health over the last [HEALTH_DAYS] days, worked out on the phone (plan Q2.5):
 * 1. the replay test: how often the user's next song was in the engine's top 10 ([hitRate]) over [steps] steps, beside
 *    three simple guesses: [random], [mostPlayed] and [mostRecent];
 * 2. the share of suggested songs skipped early ([suggestedSkips] of [suggestedPlays]) beside plain Shuffle's
 *    ([shuffleSkips] of [shufflePlays]);
 * 3. discoveries kept: rediscoveries and new songs suggested ([discoveries]) the user then made a favourite or played
 *    again by hand ([kept]).
 */
data class Health(
    val steps: Int,
    val hitRate: Double,
    val random: Double,
    val mostPlayed: Double,
    val mostRecent: Double,
    val suggestedPlays: Int,
    val suggestedSkips: Int,
    val shufflePlays: Int,
    val shuffleSkips: Int,
    val discoveries: Int,
    val kept: Int,
) {
    /** The best simple guess. */
    val baseline: Double get() = maxOf(random, mostPlayed, mostRecent)
}

const val HEALTH_DAYS: Int = 30

/** At most this many replay steps, the latest: enough to tell, quick on a phone. */
private const val MAX_STEPS = 400

/** The engine's guesses look at songs played within this long before each step (older ones barely weigh). */
private const val LOOKBACK_MS: Long = 365 * 86_400_000L

private const val TOP = 10

/** How much the next-song table adds to a song's score in the replay: a song that often follows the one before ranks high. */
private const val FOLLOW_BONUS = 1.0

/**
 * [Health] from [plays] (the play log, the oldest first) for [library]'s music as of [taste]'s now; [taste] must be built
 * from these very [plays], since the replay reads each song's values by its plays' places.
 *
 * The replay test walks the plays of the last [HEALTH_DAYS] days the user started (not a suggestion's, not an import,
 * not an early skip, not the same song again): at each, every guess ranks songs from what came before only, and scores
 * a hit when the song played is in its top 10. The engine ranks by each song's score then (its plays before, faded) and
 * the next-song table learned before the window; most played by plays that counted; most recent by the last songs
 * played; random's chance is 10 in the number of songs.
 */
@Suppress("CyclomaticComplexMethod", "LongMethod")
fun health(plays: List<LoggedPlay>, taste: Taste, library: Library): Health {
    val now = taste.now
    val from = now - HEALTH_DAYS * DAY_MS.toLong()
    val music = library.songs.count { music(library, it) }
    val pathOf = HashMap<String, String?>()
    val songPath = { play: LoggedPlay -> pathOf.getOrPut(play.song.path) { library.resolver.find(play.song)?.takeIf { music(library, it) }?.path } }
    val window = plays.filter { it.startedAt in from..now }
    val follows = follows(plays.filter { it.startedAt < from }, { play -> songPath(play)?.let(library::song) }, taste.weights)

    // The replay. Per song as the plays go by: how many of its plays came so far (its place in [SongTaste.times]), its
    // good values summed and its last play, so a song's score has a cheap ceiling and only the few that may reach the
    // top 10 are worked out exactly.
    val seen = HashMap<String, Int>()
    val good = HashMap<String, Double>()
    val last = HashMap<String, Long>()
    val counts = HashMap<String, Int>()
    val byCount = TreeSet(compareByDescending<Pair<Int, String>> { it.first }.thenBy { it.second })
    val recent = ArrayDeque<String>(TOP + 1)
    var steps = 0
    var engineHits = 0
    var playedHits = 0
    var recentHits = 0
    var previous: String? = null
    val stepIndexes = plays.indices.filter { i ->
        val play = plays[i]
        play.startedAt in from..now && play.start?.by != StartedBy.SUGGESTED && !play.imported && !earlySkip(play, taste.weights) &&
            songPath(play) != null && songPath(play) != plays.getOrNull(i - 1)?.let(songPath)
    }.takeLast(MAX_STEPS).toHashSet()
    for ((i, play) in plays.withIndex()) {
        val path = songPath(play)
        if (i in stepIndexes && path != null) {
            steps++
            if (path in topEngine(taste, seen, good, last, play.startedAt, previous, follows)) engineHits++
            if (byCount.asSequence().take(TOP).any { it.second == path }) playedHits++
            if (path in recent) recentHits++
        }
        if (path != null) {
            val index = seen[path] ?: 0
            taste.songs[path]?.values?.getOrNull(index)?.let { value -> if (value > 0) good.merge(path, value, Double::plus) }
            seen[path] = index + 1
            last[path] = play.startedAt
            if (play.counted) {
                val count = counts[path] ?: 0
                byCount.remove(count to path)
                counts[path] = count + 1
                byCount += (count + 1) to path
            }
            recent.remove(path)
            recent.addFirst(path)
            if (recent.size > TOP) recent.removeLast()
        }
        previous = path
    }
    val rate = { hits: Int -> if (steps == 0) 0.0 else hits.toDouble() / steps }

    // Skips: suggested songs and plain Shuffle's, early skips among them.
    val suggested = window.filter { it.start?.by == StartedBy.SUGGESTED && songPath(it) != null }
    val shuffled = window.filter { play -> play.start?.let { it.shuffled && (it.by == StartedBy.AUTO || it.by == StartedBy.NEXT) } == true && songPath(play) != null }

    // Discoveries: rediscoveries and new songs suggested in the window, kept when made a favourite or played by hand after.
    val bucketAt = taste.outcomes.filter { it.at in from..now }.associate { it.at to it.bucket }
    val discovered = HashMap<String, Long>()
    suggested.forEach { play ->
        if (bucketAt[play.startedAt]?.let { it != Bucket.FAMILIAR } == true) discovered.putIfAbsent(songPath(play)!!, play.startedAt)
    }
    val lastPicked = HashMap<String, Long>()
    window.forEach { play -> if (play.start?.by == StartedBy.PICKED && !earlySkip(play, taste.weights)) songPath(play)?.let { lastPicked[it] = play.startedAt } }
    val kept = discovered.count { (path, at) ->
        val favouriteAt = taste.songs[path]?.favouriteAt
        (favouriteAt != null && favouriteAt > at) || (lastPicked[path] ?: Long.MIN_VALUE) > at
    }
    return Health(
        steps = steps,
        hitRate = rate(engineHits),
        random = if (music == 0) 0.0 else minOf(1.0, TOP.toDouble() / music),
        mostPlayed = rate(playedHits),
        mostRecent = rate(recentHits),
        suggestedPlays = suggested.size,
        suggestedSkips = suggested.count { earlySkip(it, taste.weights) },
        shufflePlays = shuffled.size,
        shuffleSkips = shuffled.count { earlySkip(it, taste.weights) },
        discoveries = discovered.size,
        kept = kept,
    )
}

/**
 * The engine's top 10 at [at] among the songs played before ([seen]: each one's plays so far): each one's score from
 * those plays (its favourite boost once it was one) and the next-song table's weight after [previous]. A song's score is
 * at most its good values ([good]) faded from its last play ([last]); songs are tried by that ceiling, the highest
 * first, and the search stops once no ceiling left can reach the top 10.
 */
@Suppress("LongParameterList")
private fun topEngine(taste: Taste, seen: Map<String, Int>, good: Map<String, Double>, last: Map<String, Long>, at: Long, previous: String?, follows: Map<String, List<Follow>>): Set<String> {
    val decay = taste.weights.decay
    val after = previous?.let { follows[it] }.orEmpty().associate { it.path to it.weight }
    val extra = { path: String -> (taste.songs[path]?.favouriteAt?.takeIf { it <= at }?.let { taste.weights.favourite } ?: 0.0) + FOLLOW_BONUS * (after[path] ?: 0.0) }
    val ceilings = PriorityQueue<Pair<String, Double>>(seen.size.coerceAtLeast(1), compareByDescending { it.second })
    seen.keys.forEach { path ->
        val latest = last[path] ?: return@forEach
        if (at - latest <= LOOKBACK_MS) ceilings += path to (good[path] ?: 0.0) * fade(at - latest, decay) + extra(path)
    }
    val top = PriorityQueue<Pair<String, Double>>(compareBy { it.second })
    while (ceilings.isNotEmpty()) {
        val (path, ceiling) = ceilings.remove()
        if (top.size == TOP && ceiling <= top.element().second) break
        val mine = taste.songs[path] ?: continue
        var score = extra(path)
        for (k in 0 until (seen[path] ?: 0)) score += mine.values[k] * fade(at - mine.times[k], decay)
        top += path to score
        if (top.size > TOP) top.poll()
    }
    return top.mapTo(HashSet()) { it.first }
}
