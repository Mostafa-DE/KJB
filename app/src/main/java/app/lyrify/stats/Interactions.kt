package app.lyrify.stats

import app.lyrify.library.search.fold
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.time.ZoneId

// The interaction log (plan 0b, decided 2026-11-22): what the user does in KJB besides playing, each with its time and
// the phone's time zone then. Kept in `plays.db` beside the plays and in backups with them; it never leaves the phone
// otherwise. Logcat gets counts and exception names only, never a search's words, a song or a name.

/** What an interaction was. Kept by its name in lower case ([codeOf]); a row of a kind this version does not know is left out. */
enum class InteractionKind {
    /** A search, at the moments Recent searches keeps one: the keyboard's search key, View all, or a result tapped. */
    SEARCH,

    /** A page opened: an album's, an artist's or a playlist's, or a song's Song info. */
    OPEN,

    /** The user turned to a song's lyrics: the player's cover turned over, Full screen, or the song sheet's Lyrics. */
    LYRICS,
}

/** What an interaction was about, by its name in lower case as [InteractionKind]. */
enum class TargetKind { SONG, ALBUM, ARTIST, FOLDER, PLAYLIST }

/**
 * What an interaction was about, by its [key] in the library: a song's path, an album's key, an artist's name, a
 * folder's path below its volume, a playlist's id.
 */
data class Target(val kind: TargetKind, val key: String)

/**
 * One interaction, at [at] (wall-clock millis), the phone [zoneOffsetMinutes] ahead of UTC then. [target]: the page or
 * the song, or a search's result tapped (none: a search left without one). [words]: a search's words as typed, trimmed;
 * none once the user removed that search from Recent searches.
 */
data class Interaction(val at: Long, val kind: InteractionKind, val zoneOffsetMinutes: Int?, val target: Target? = null, val words: String? = null)

/** The interaction log as Suggestions reads it ([StatsGraph.interactions]). */
interface InteractionsQuery {
    /** The interactions from [from] (inclusive) until [until] (exclusive), either end open, the oldest first; none when the log cannot be read. */
    suspend fun interactions(from: Long? = null, until: Long? = null): List<Interaction>
}

/**
 * An interaction as a row of [PlaysSchema.INTERACTIONS] has it, read as leniently as [loggedPlayOf]: a row without a
 * time, or of a kind this version does not know, is dropped; a target of a kind it does not know, or without its key,
 * is none, and so are blank words and a time zone out of range.
 */
fun interactionOf(row: PlayRow): Interaction? {
    val at = row.number("at")?.takeIf { it > 0 } ?: return null
    val kind = enumOf<InteractionKind>(row.text("kind")) ?: return null
    val target = enumOf<TargetKind>(row.text("target"))?.let { targetKind -> row.text("target_key")?.let { Target(targetKind, it) } }
    return Interaction(at, kind, zoneOffsetOf(row.number("zone_offset_min")), target, row.text("words")?.takeIf(String::isNotBlank))
}

/**
 * Notes the interactions as they happen, in order, in [scope], so a screen never waits for `plays.db`: each with the
 * time it happened, later than the one before (two never share a time), and the time zone then ([zone]).
 *
 * A search is noted once. A result tapped on the same words (case and accents aside) within [SEARCH_GOES_ON_MS] of it is
 * its result; another result tapped on them after that is a search of its own, and the search key or View all on them
 * again adds nothing. Removing a search from Recent searches forgets the words of every search of those words.
 */
class InteractionLog(
    private val store: PlayLogStore,
    scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val log: (String) -> Unit = {},
) : InteractionsQuery {
    private val work = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    /** The time given to the last interaction noted. */
    private var last = 0L

    /** The search noted last, whose result a tap on its words soon after is; none once its words are forgotten. */
    private var search: Interaction? = null

    init {
        scope.launch { for (job in work) job() }
    }

    /** A search for [words], with the [result] tapped (none: the search key or View all). Blank words are no search. */
    fun searched(words: String, result: Target? = null) {
        val typed = words.trim().takeIf(String::isNotEmpty) ?: return
        val now = now()
        work.trySend {
            val going = search?.takeIf { now.at - it.at <= SEARCH_GOES_ON_MS && it.words?.let(::fold) == fold(typed) }
            val noted = when {
                going == null -> interaction(now, InteractionKind.SEARCH, result, typed)
                result == null -> null
                going.target == null -> going.copy(target = result)
                else -> interaction(now, InteractionKind.SEARCH, result, typed)
            }
            if (noted != null) {
                search = noted
                note(noted)
            }
        }
    }

    /** An album's, an artist's or a playlist's page opened, or a song's Song info. */
    fun opened(target: Target) {
        val now = now()
        work.trySend { note(interaction(now, InteractionKind.OPEN, target)) }
    }

    /** The user turned to the lyrics of the song at [path]. */
    fun lyrics(path: String) {
        val now = now()
        work.trySend { note(interaction(now, InteractionKind.LYRICS, Target(TargetKind.SONG, path))) }
    }

    /** A search removed from Recent searches: the words of every search of [words] are forgotten, the searches kept. */
    fun forget(words: String) {
        val folded = fold(words)
        work.trySend {
            if (search?.words?.let(::fold) == folded) search = null
            val searches = store.interactions()?.filter { it.kind == InteractionKind.SEARCH && it.words?.let(::fold) == folded }
            if (searches == null || !store.forget(searches.map { it.at })) log("interactions: search words not forgotten")
        }
    }

    override suspend fun interactions(from: Long?, until: Long?): List<Interaction> = store.interactions(from, until).orEmpty()

    /** When something happened: the clock, and how far the phone's time zone was ahead of UTC then. */
    private class Moment(val at: Long, val zoneOffsetMinutes: Int)

    private fun now(): Moment = clock().let { at -> Moment(at, zoneOffsetMinutes(zone(), at)) }

    /** An interaction that happened [then], at a time after the last one's. */
    private fun interaction(then: Moment, kind: InteractionKind, target: Target?, words: String? = null): Interaction {
        last = maxOf(then.at, last + 1)
        return Interaction(last, kind, then.zoneOffsetMinutes, target, words)
    }

    private suspend fun note(interaction: Interaction) {
        if (!store.note(interaction)) log("interactions: one not written")
    }

    companion object {
        /** How long after a search a result tapped on its words is that search's result. */
        const val SEARCH_GOES_ON_MS: Long = 10 * 60_000
    }
}
