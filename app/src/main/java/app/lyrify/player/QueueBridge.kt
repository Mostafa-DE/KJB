package app.lyrify.player

import app.lyrify.library.Song
import app.lyrify.library.SongRef
import app.lyrify.stats.PlayEvent
import app.lyrify.stats.PlayEvents
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

// The queue model is the truth; the player holds a projection of the queue playing. This file applies the model to the
// player and reads the player back: which songs it holds, the edits that keep the song playing, the play events, and
// when the place is saved.

/** A queue item the player can play: its [uid], and the library's song it names (found again, so a moved file's new path). */
data class Projected(val uid: Long, val song: Song)

/** What the player holds for [queue]: its songs in order, those not on the phone left out ([find] gives null for them). */
fun projection(queue: Queue?, find: (SongRef) -> Song?): List<Projected> =
    queue?.items?.mapNotNull { item -> find(item.song)?.let { Projected(item.uid, it) } }.orEmpty()

/**
 * Where in [projected] the player starts for [queue]: its current song, or when that one is not on the phone the next
 * one that is (round to the start); -1 when none is.
 */
fun startOf(queue: Queue, projected: List<Projected>): Int {
    if (projected.isEmpty()) return -1
    val at = projected.associate { it.uid to it }
    val order = queue.items.drop(queue.index) + queue.items.take(queue.index)
    val uid = order.firstOrNull { it.uid in at }?.uid ?: return -1
    return projected.indexOfFirst { it.uid == uid }
}

/** One change to the songs the player holds, by place: never a reload, so the song playing goes on. */
sealed interface Edit {
    /** [count] songs side by side go, from [at]. */
    data class Remove(val at: Int, val count: Int = 1) : Edit

    data class Insert(val at: Int, val items: List<Projected>) : Edit

    /** The song at [from] moves to [to] (its place once taken out), as `Player.moveMediaItem` does. */
    data class Move(val from: Int, val to: Int) : Edit

    /** The song at [at] is [item] at another path (its file moved): the player reads it from there, at the same place. */
    data class Replace(val at: Int, val item: Projected) : Edit
}

/**
 * At most this many edits reach the player for one change of the queue playing ([plan]). Each edit makes ExoPlayer build
 * its whole list again, on its playback thread too, and the lists that thread sends back wait on the main thread until
 * the change is done: a Shuffle of 6,906 songs as one move per song ran KJB out of memory (CR1). A larger change is made
 * around the song playing instead ([around]): six edits at most, however long the queue.
 */
const val MAX_EDITS: Int = 8

/**
 * The fewest edits that turn the player's songs [from] (by uid) into [to]: first the songs [to] no longer holds go (from
 * the end, songs side by side in one edit); the longest run of the others already in [to]'s order stays where it is; then,
 * in [to]'s order, each other song moves right after the one before it, and new songs come in there (side by side in one
 * edit). A song dragged anywhere is one move. A player removing its current song goes on with the next one; any other
 * song keeps playing through every edit. Null when it takes more than [limit]; it stops looking then.
 */
fun edits(from: List<Long>, to: List<Projected>, limit: Int = Int.MAX_VALUE): List<Edit>? {
    val wanted = to.map { it.uid }
    val keep = wanted.toHashSet()
    val list = from.toMutableList()
    val out = mutableListOf<Edit>()
    var last = list.lastIndex
    while (last >= 0) {
        if (list[last] in keep) {
            last--
            continue
        }
        var first = last
        while (first > 0 && list[first - 1] !in keep) first--
        out += Edit.Remove(first, last - first + 1)
        if (out.size > limit) return null
        list.subList(first, last + 1).clear()
        last = first - 1
    }
    val place = wanted.withIndex().associate { (i, uid) -> uid to i }
    val stays = rising(list.map(place::getValue)).mapTo(HashSet()) { list[it] }
    val held = list.toHashSet()
    var i = 0
    while (i < wanted.size) {
        if (wanted[i] in stays) {
            i++
            continue
        }
        val after = if (i == 0) 0 else list.indexOf(wanted[i - 1]) + 1
        if (wanted[i] in held) {
            val at = list.indexOf(wanted[i])
            val target = if (at < after) after - 1 else after
            if (at != target) {
                out += Edit.Move(at, target)
                list.add(target, list.removeAt(at))
            }
            i++
        } else {
            var end = i
            while (end < wanted.size && wanted[end] !in held) end++
            val run = to.subList(i, end).toList()
            out += Edit.Insert(after, run)
            list.addAll(after, run.map { it.uid })
            i = end
        }
        if (out.size > limit) return null
    }
    return out
}

/** The places in [values] (each value once) of a longest run of them that only rises, from its last place to its first. */
private fun rising(values: List<Int>): List<Int> {
    // ends[k]: where the run of k + 1 values found so far with the lowest last value ends; before[i]: the place before i in its run.
    val ends = IntArray(values.size)
    val before = IntArray(values.size)
    var length = 0
    for (i in values.indices) {
        var low = 0
        var high = length
        while (low < high) {
            val middle = (low + high) ushr 1
            if (values[ends[middle]] < values[i]) low = middle + 1 else high = middle
        }
        before[i] = if (low > 0) ends[low - 1] else -1
        ends[low] = i
        if (low == length) length++
    }
    return generateSequence(ends.getOrNull(length - 1)) { at -> before[at].takeIf { it >= 0 } }.toList()
}

/**
 * The edits that turn [from] into [to] around the song [playing], which both hold, and the song after it in [from] (the
 * first song when it is the last: Repeat all). ExoPlayer's playback thread goes on to that one at a song's end before the
 * main thread hears of it, so as the edits come in it may be the song really playing (CR2), and a song taken out and put
 * in again would stop. So neither of the two is ever taken out: every other song goes, then [to]'s songs come in around
 * the two (the song after moved before the song playing when [to] has it there). A song after that [to] leaves out goes
 * last, from right after the song playing, so a player on it goes on with [to]'s song after the one playing. Six edits
 * at most, whatever the change.
 */
fun around(from: List<Long>, to: List<Projected>, playing: Long): List<Edit> {
    val at = from.indexOf(playing)
    val kept = to.indexOfFirst { it.uid == playing }
    if (from.size == 1) {
        return listOfNotNull(
            Edit.Insert(1, to.subList(kept + 1, to.size).toList()).takeIf { it.items.isNotEmpty() },
            Edit.Insert(0, to.subList(0, kept).toList()).takeIf { it.items.isNotEmpty() },
        )
    }
    val nextAt = (at + 1) % from.size
    val next = from[nextAt]
    val nextKept = to.indexOfFirst { it.uid == next }
    val leaves = nextKept < 0
    // The two in [to]'s order; a song after that leaves stands right after the song playing until it goes.
    val last = if (leaves || kept < nextKept) next else playing
    val low = if (leaves) kept else minOf(kept, nextKept)
    val high = if (leaves) kept else maxOf(kept, nextKept)
    val before = to.subList(0, low).toList()
    val between = if (leaves) emptyList() else to.subList(low + 1, high).toList()
    val after = to.subList(high + 1, to.size).toList()
    // Once the others go, the player holds the two in [from]'s order, the other way round from [to]'s when [reversed].
    val firstAt = minOf(at, nextAt)
    val secondAt = maxOf(at, nextAt)
    val reversed = from[firstAt] == last
    return listOfNotNull(
        Edit.Remove(secondAt + 1, from.size - secondAt - 1).takeIf { it.count > 0 },
        Edit.Remove(firstAt + 1, secondAt - firstAt - 1).takeIf { it.count > 0 },
        Edit.Remove(0, firstAt).takeIf { it.count > 0 },
        Edit.Insert(if (reversed) 1 else 2, after).takeIf { it.items.isNotEmpty() },
        Edit.Move(1 + after.size, 0).takeIf { reversed },
        Edit.Insert(1, between).takeIf { it.items.isNotEmpty() },
        Edit.Insert(0, before).takeIf { it.items.isNotEmpty() },
        Edit.Remove(before.size + 1).takeIf { leaves },
    )
}

/**
 * How the player holding [from] and playing [playing] comes to hold [to], [paths] being the path it was handed for each
 * song: the [edits], and each song whose file moved replaced at its new path ([Edit.Replace]), while that is at most
 * [MAX_EDITS] edits; else everything around the song playing ([around]), the songs it keeps replaced if their files
 * moved. Null when the song playing leaves in a larger change: the player loads [to] again then.
 */
fun plan(from: List<Long>, to: List<Projected>, playing: Long?, paths: Map<Long, String>): List<Edit>? {
    val moved = to.indices.filter { at -> paths[to[at].uid].let { it != null && it != to[at].song.path } }
    edits(from, to, MAX_EDITS - moved.size)?.takeIf { it.size + moved.size <= MAX_EDITS }?.let { edits ->
        return edits + moved.map { Edit.Replace(it, to[it]) }
    }
    if (playing == null || to.none { it.uid == playing }) return null
    val edits = around(from, to, playing)
    val fresh = edits.flatMapTo(HashSet()) { edit -> if (edit is Edit.Insert) edit.items.map { it.uid } else emptyList() }
    return edits + moved.filter { to[it].uid !in fresh }.map { Edit.Replace(it, to[it]) }
}

/**
 * What the queue bridge, place saving and play counting need from a player: its songs by uid, the current one, the
 * position, and the changes. The engine implements it; JVM tests use a fake. Main thread only.
 */
interface PlayerPort {
    /** The uids of the songs it holds, in order. */
    val loaded: List<Long>

    /** The current song's place in [loaded]; -1 for none. */
    val index: Int

    val positionMs: Long

    /** Replaces everything: [items], from [index] at [positionMs] (-1: nothing). Keeps whether it plays. */
    fun load(items: List<Projected>, index: Int, positionMs: Long)

    fun apply(edit: Edit)

    /** [edits] in order, as one change: an engine may settle once, after the last. */
    fun applyAll(edits: List<Edit>) = edits.forEach(::apply)

    fun seek(index: Int, positionMs: Long)
}

/**
 * Applies the queue model to the player ([sync] on every change of the book or the library): another queue is loaded at
 * its place; a change to the queue playing reaches the player as a few edits ([plan]), however large, so the song
 * playing never stops; a song whose file moved is replaced with its new path ([Edit.Replace]); a jump in the book
 * ([QueueBook.jumps]) moves the player to the current song at its position. The player moving on by itself comes back
 * through [QueueBook.at], which leaves the player as it is.
 */
class QueueBridge(private val port: PlayerPort) {
    private var loadedId: String? = null
    private var jumps: Long = 0

    /** The path the player was handed for each song it holds, by uid. */
    private var paths: Map<Long, String> = emptyMap()

    fun sync(book: QueueBook, find: (SongRef) -> Song?) {
        val queue = book.current
        if (queue == null) {
            if (loadedId != null || port.loaded.isNotEmpty()) port.load(emptyList(), -1, 0)
            loadedId = null
            paths = emptyMap()
            return
        }
        val projected = projection(queue, find)
        val start = startOf(queue, projected)
        val position = if (start >= 0 && projected[start].uid == queue.current?.uid) queue.positionMs else 0
        if (queue.id != loadedId) {
            port.load(projected, start, position)
            loadedId = queue.id
            jumps = book.jumps
            paths = projected.associate { it.uid to it.song.path }
            return
        }
        val playing = port.loaded.getOrNull(port.index)
        val plan = plan(port.loaded, projected, playing, paths)
        paths = projected.associate { it.uid to it.song.path }
        if (plan == null) {
            port.load(projected, start, position)
            jumps = book.jumps
            return
        }
        if (plan.isNotEmpty()) port.applyAll(plan)
        val lost = playing != null && projected.none { it.uid == playing }
        if (book.jumps != jumps || lost) {
            jumps = book.jumps
            if (start >= 0) port.seek(start, position)
        }
    }
}

/**
 * The answer to a headset's or a car's play after KJB was closed: [items] once [ready] says the queues and the library
 * are read, [none] when it says the library cannot be read, when that takes longer than [timeoutMs], or when [scope]
 * ends first. Never left waiting.
 */
fun <T> resumption(scope: CoroutineScope, timeoutMs: Long, none: T, ready: suspend () -> Boolean, items: () -> T): ListenableFuture<T> {
    val result = SettableFuture.create<T>()
    scope.launch { result.set(if (withTimeoutOrNull(timeoutMs) { ready() } == true) items() else none) }
        // Cancelled (the service ended), or set already: set does nothing then.
        .invokeOnCompletion { result.set(none) }
    return result
}

/** How long a headset's play after KJB was closed waits for the queues and the library. */
const val RESUME_WAIT_MS: Long = 10_000

/** What the player does after a song could not be played. */
sealed interface AfterError {
    /** On to the song at [index]. */
    data class Skip(val index: Int) : AfterError

    /** Stop where it is, the error showing. */
    data object Stop : AfterError
}

/**
 * After a song failed: on to [next], the song the player would play next for its repeat mode (the first one after the
 * last under Repeat all; -1 for none), unless [failedInARow] reached the [size] of the queue: then every song failed,
 * and the player stops instead of going round for ever.
 */
fun afterError(next: Int, failedInARow: Int, size: Int): AfterError = if (next < 0 || failedInARow >= size) AfterError.Stop else AfterError.Skip(next)

/** [afterError] done on [engine]: on to the next song from its start, or stopped where it is. */
fun skipAfterError(engine: PlaybackEngine, next: Int, failedInARow: Int): AfterError =
    afterError(next, failedInARow, engine.loaded.size).also { step ->
        when (step) {
            is AfterError.Skip -> engine.seek(step.index, 0)
            AfterError.Stop -> engine.setPlayWhenReady(false)
        }
    }

/**
 * Tells the play counter ([PlayEvents], stats/) about KJB's own plays, raw, one play at a time: [start] (a song starts,
 * or starts again: each lap of Repeat one is a new play) with its position, the position whenever playing starts or
 * stops and every [POSITION_EVERY_MS] while it plays ([position]), the seeks, and the [end]: at the song's length when it
 * played to its end, else where it was last. The counter works out what was heard. Ids grow from the wall clock, so they
 * never repeat across runs. Main thread.
 */
class PlayTracker(private val events: PlayEvents, private val wall: () -> Long) {
    private class Play(val id: Long, val song: SongRef, val durationMs: Long, var positionMs: Long)

    private var play: Play? = null
    private var lastId: Long = 0

    /** [song] starts at [positionMs]; the play before ends, at its length when [naturalEnd] (it played to its end). */
    fun start(song: SongRef, durationMs: Long, positionMs: Long, playing: Boolean, naturalEnd: Boolean = false) {
        end(naturalEnd)
        val id = maxOf(lastId + 1, wall())
        lastId = id
        play = Play(id, song, durationMs, positionMs)
        events.onEvent(PlayEvent.Started(id, song, wall(), durationMs))
        position(positionMs, playing)
    }

    /** The player is at [positionMs], playing or not. */
    fun position(positionMs: Long, playing: Boolean) {
        val current = play ?: return
        current.positionMs = positionMs
        events.onEvent(PlayEvent.Position(current.id, current.song, wall(), positionMs, playing))
    }

    /** A jump from [fromMs] to [toMs]: the part jumped over is not heard. */
    fun seeked(fromMs: Long, toMs: Long) {
        val current = play ?: return
        current.positionMs = toMs
        events.onEvent(PlayEvent.Seeked(current.id, current.song, wall(), fromMs, toMs))
    }

    /** The play is over: at the song's length when [naturalEnd], else at its last position. */
    fun end(naturalEnd: Boolean = false) {
        val current = play ?: return
        val at = if (naturalEnd && current.durationMs > 0) current.durationMs else current.positionMs
        events.onEvent(PlayEvent.Ended(current.id, current.song, wall(), at))
        play = null
    }
}

/** How often a play reports its position while playing. */
const val POSITION_EVERY_MS: Long = 5_000

/** Runs [action] every [everyMs] while [playing] says so; a pause stops it until playing starts again. Runs until cancelled. */
suspend fun repeatWhilePlaying(playing: Flow<Boolean>, everyMs: Long, action: suspend () -> Unit) {
    playing.distinctUntilChanged().collectLatest { on ->
        while (on) {
            delay(everyMs)
            action()
        }
    }
}

/** How often the place is saved while playing. */
const val PLACE_EVERY_MS: Long = 10_000

/**
 * Saves the place ([save]) every [everyMs] while [playing] says so, and once on every pause; not for the first state
 * when it is paused (nothing moved yet). Runs until cancelled.
 */
suspend fun savePlaces(playing: Flow<Boolean>, everyMs: Long = PLACE_EVERY_MS, save: suspend () -> Unit) {
    var first = true
    playing.distinctUntilChanged().collectLatest { on ->
        val wasFirst = first
        first = false
        if (on) {
            while (true) {
                delay(everyMs)
                save()
            }
        } else if (!wasFirst) {
            save()
        }
    }
}
