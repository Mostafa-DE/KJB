package app.lyrify.player

import app.lyrify.library.SongRef
import app.lyrify.settings.PlaybackSettings
import app.lyrify.settings.RememberPlace
import kotlin.math.pow
import kotlin.math.roundToInt

// Speed and pitch with a speed of a song's own, Repeat a part (A-B), bookmarks and Remember my place (milestone P2): the
// rules as pure functions over [SongState] (kept in `song-state.json`, SongStateStore.kt).

/** The Speed sheet's chips; its slider moves in steps of 0.05 ([SPEED_STEPS_PER_X] to 1×) from the first to the last. */
val SPEEDS: List<Float> = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
const val SPEED_STEPS_PER_X: Int = 20

/** The pitch slider, in semitones. */
val SEMITONES: IntRange = -6..6

/**
 * "Speed": how fast songs play, with their pitch kept ([keepPitch]) or set in [semitones] from the speed's own (a song
 * played faster sounds higher; 0 leaves it so).
 */
data class Speed(val speed: Float = 1f, val keepPitch: Boolean = true, val semitones: Int = 0) {
    /** The pitch the player gets: 1 when kept, else the speed's own moved by [semitones]. */
    val pitch: Float get() = if (keepPitch) 1f else speed * 2f.pow(semitones / 12f)

    /** Whether the player shows its speed chip: anything that does not sound as the song does. */
    val shows: Boolean get() = speed != 1f || (!keepPitch && semitones != 0)

    /** [speed] in steps of 0.05 from 0.5 to 2, [semitones] within [SEMITONES]. */
    fun normalised(): Speed = copy(
        speed = ((speed * SPEED_STEPS_PER_X).roundToInt() / SPEED_STEPS_PER_X.toFloat()).coerceIn(SPEEDS.first(), SPEEDS.last()),
        semitones = semitones.coerceIn(SEMITONES),
    )
}

/** A bookmark: a spot in a song, with the user's note (maybe empty). [id] is its own for good. */
data class Bookmark(val id: Long, val positionMs: Long, val note: String)

/** "Repeat a part": the part of the song at [path] from [startMs] (A) to [endMs] (B), in whole seconds, that loops. */
data class AbPart(val path: String, val startMs: Long, val endMs: Long)

/**
 * What KJB keeps per song, by path: its own speed ("This song only"), its place (Remember my place, the oldest first)
 * and its bookmarks (in time order); the [usual] speed of every other song; and the part repeating, if any.
 */
data class SongState(
    val usual: Speed = Speed(),
    val speeds: Map<String, Speed> = emptyMap(),
    val places: Map<String, Long> = emptyMap(),
    val bookmarks: Map<String, List<Bookmark>> = emptyMap(),
    val part: AbPart? = null,
    val nextBookmarkId: Long = 1,
) {
    /** The speed the song at [path] plays at: its own, else the usual one. */
    fun speedOf(path: String?): Speed = path?.let(speeds::get) ?: usual

    /** The bookmarks of the song at [path], in time order. */
    fun bookmarksOf(path: String?): List<Bookmark> = path?.let(bookmarks::get).orEmpty()

    /** [change] applied to the speed the song at [path] plays at: its own when it has one, else the usual one. */
    fun withSpeed(path: String?, change: (Speed) -> Speed): SongState {
        val own = path?.let(speeds::get)
        return if (path != null && own != null) copy(speeds = speeds + (path to change(own).normalised())) else copy(usual = change(usual).normalised())
    }

    /**
     * "This song only" switched [on] for the song at [path]: the speed set now becomes the song's own, and the usual
     * speed goes back to [usualBefore] (what it was when the sheet opened); off, the song plays at the usual speed again.
     */
    fun thisSongOnly(path: String, on: Boolean, usualBefore: Speed): SongState = when {
        !on -> copy(speeds = speeds - path)
        path in speeds -> this
        else -> copy(speeds = speeds + (path to usual), usual = usualBefore.normalised())
    }

    /** A bookmark at [positionMs] (to the second) in the song at [path], with [note] trimmed; the new bookmark too. */
    fun withBookmark(path: String, positionMs: Long, note: String): Pair<SongState, Bookmark> {
        val mark = Bookmark(nextBookmarkId, wholeSeconds(positionMs.coerceAtLeast(0)), note.trim().take(MAX_NOTE))
        val list = (bookmarksOf(path) + mark).sortedWith(compareBy(Bookmark::positionMs, Bookmark::id))
        return copy(bookmarks = bookmarks + (path to list), nextBookmarkId = nextBookmarkId + 1) to mark
    }

    fun withoutBookmark(path: String, id: Long): SongState {
        val list = bookmarksOf(path)
        if (list.none { it.id == id }) return this
        val left = list.filterNot { it.id == id }
        return copy(bookmarks = if (left.isEmpty()) bookmarks - path else bookmarks + (path to left))
    }

    /** The place kept for the song at [path]: [positionMs], or none when null; at most [MAX_PLACES], the oldest go first. */
    fun withPlace(path: String, positionMs: Long?): SongState {
        val others = places - path
        if (positionMs == null) return if (path in places) copy(places = others) else this
        // A map keeps its order: the place kept now goes last, and the oldest go once there are too many.
        return copy(places = (others + (path to positionMs)).entries.toList().takeLast(MAX_PLACES).associate { it.key to it.value })
    }

    companion object {
        const val MAX_PLACES: Int = 300
        const val MAX_NOTE: Int = 200
    }
}

/** Where the progress bar shows the bookmarks of a song [durationMs] long: shares of its length, once each. */
fun bookmarkTicks(bookmarks: List<Bookmark>, durationMs: Long): List<Float> =
    if (durationMs <= 0) emptyList() else bookmarks.map { (it.positionMs.toFloat() / durationMs).coerceIn(0f, 1f) }.distinct()

/** [ms] down to the second. */
private fun wholeSeconds(ms: Long): Long = ms / 1_000 * 1_000

// Repeat a part (A-B)

/** The shortest part, and how close B may come to the end. */
const val PART_MIN_MS: Long = 2_000
const val PART_END_GAP_MS: Long = 1_000
const val PART_FIRST_MS: Long = 10_000

/**
 * "Repeat a part" at [positionMs] in a song [durationMs] long: A at the second playing, B 10 s later; near the end, the
 * last 10 s before the final second. Null when the song is too short or its length unknown.
 */
fun abPart(path: String, positionMs: Long, durationMs: Long): AbPart? {
    val last = wholeSeconds(durationMs - PART_END_GAP_MS)
    if (last < PART_MIN_MS) return null
    val start = wholeSeconds(positionMs.coerceAtLeast(0))
    if (start + PART_FIRST_MS <= last) return AbPart(path, start, start + PART_FIRST_MS)
    return AbPart(path, (last - PART_FIRST_MS).coerceAtLeast(0), last)
}

/** A dragged to [ms]: within the start and 2 s before B, to the second. */
fun AbPart.withStart(ms: Long): AbPart = copy(startMs = wholeSeconds(ms.coerceAtLeast(0)).coerceAtMost(endMs - PART_MIN_MS))

/** B dragged to [ms]: within 2 s after A and the song's final second, to the second. */
fun AbPart.withEnd(ms: Long, durationMs: Long): AbPart =
    copy(endMs = wholeSeconds(ms).coerceAtMost(wholeSeconds(durationMs - PART_END_GAP_MS)).coerceAtLeast(startMs + PART_MIN_MS))

/** Where the player goes from [positionMs] in [part]'s song: back to A once B is reached; null to play on. */
fun loopTo(part: AbPart, positionMs: Long): Long? = part.startMs.takeIf { positionMs >= part.endMs }

/** Where the player goes when [part] changed: to A when it plays outside the part; null when it is inside. */
fun intoPart(part: AbPart, positionMs: Long): Long? = part.startMs.takeIf { positionMs < part.startMs || positionMs > part.endMs }

// Remember my place

/** Long songs, for "Remember my place": 10 minutes or longer. */
const val LONG_SONG_MS: Long = 600_000
const val PLACE_FROM_MS: Long = 5_000
const val PLACE_BEFORE_END_MS: Long = 10_000

/** Whether a song [durationMs] long resumes where it was left, in [mode]. */
fun remembers(mode: RememberPlace, durationMs: Long): Boolean = when (mode) {
    RememberPlace.LONG_SONGS -> durationMs >= LONG_SONG_MS
    RememberPlace.ALL_SONGS -> durationMs > 0
    RememberPlace.OFF -> false
}

/** The place to keep for a song left at [positionMs]: null (forget it) unless it remembers, past 5 s and over 10 s from the end. */
fun placeToKeep(mode: RememberPlace, durationMs: Long, positionMs: Long): Long? =
    positionMs.takeIf { remembers(mode, durationMs) && it > PLACE_FROM_MS && it < durationMs - PLACE_BEFORE_END_MS }

/** Where a song starting at [startMs] resumes: its [kept] place, when it starts from the beginning and remembers places. */
fun resumeAt(mode: RememberPlace, durationMs: Long, kept: Long?, startMs: Long): Long? {
    if (kept == null || startMs >= FROM_THE_START_MS) return null
    return placeToKeep(mode, durationMs, kept)
}

/** A song that starts this close to its beginning starts from it. */
private const val FROM_THE_START_MS: Long = 1_000

// Jumps and the seek buttons

/**
 * A bookmark's jump: [song] plays at [positionMs] inside the queue playing; a song not in it goes there with Play next
 * first ([picked]); without a queue, a new one holds it.
 */
fun QueueBook.toSpot(song: SongRef, positionMs: Long, picked: QueueSource, now: Long): Switched {
    val holds = current?.items?.any { it.song.path == song.path } == true
    val book = if (holds) this else playNext(listOf(song), picked, now)?.book ?: this
    val queue = book.current ?: return Switched(this, toRecent = false)
    val index = queue.items.indexOfFirst { it.song.path == song.path }
    if (index < 0) return Switched(this, toRecent = false)
    val jumped = book.jump(queue.id, index, now)
    return jumped.copy(book = jumped.book.place(index, positionMs))
}

/** The uid of the song at [position] in queue [queueId]: the song sheet's "Remove from queue". */
fun QueueBook.uidAt(queueId: String, position: Int): Long? = queue(queueId)?.items?.getOrNull(position)?.uid

/** The session's seek back and forward (Bluetooth, the lock screen): the player's own buttons ("Skip amounts"). */
fun skipAmounts(playback: PlaybackSettings): SkipAmounts = SkipAmounts(playback.skipBackSeconds * 1_000L, playback.skipForwardSeconds * 1_000L)

// The player, for the service's drivers

/**
 * What the sleep timer and the extras need from the player, beyond its songs and place ([PlayerPort]): whether it plays,
 * the song's length, its speed and volume. The service's engine in the app, a fake in tests. Main thread.
 */
interface ExtrasPort : PlayerPort {
    val playing: Boolean

    /** The current song's length as decoded; 0 or less while unknown. */
    val durationMs: Long

    val speed: Float

    fun setPlayWhenReady(play: Boolean)

    fun setSpeed(speed: Float, pitch: Float)

    /** 0 to 1: the sleep timer's fade. */
    var volume: Float
}
