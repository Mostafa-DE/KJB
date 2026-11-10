package app.lyrify.player

import app.lyrify.library.Song
import app.lyrify.library.SongRef
import app.lyrify.stats.PlayEvent
import app.lyrify.stats.PlayEvents
import kotlinx.coroutines.delay
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
    data class Remove(val at: Int) : Edit

    data class Insert(val at: Int, val items: List<Projected>) : Edit

    /** The song at [from] moves to [to] (its place once taken out), as `Player.moveMediaItem` does. */
    data class Move(val from: Int, val to: Int) : Edit
}

/**
 * The edits that turn the player's songs [from] (by uid) into [to]: first the songs [to] no longer holds go (from the
 * end), then each place is filled in order by a move or by an insert of the new songs there. A player removing its
 * current song goes on with the next one; any other song keeps playing through every edit.
 */
fun edits(from: List<Long>, to: List<Projected>): List<Edit> {
    val wanted = to.map { it.uid }
    val keep = wanted.toSet()
    val list = from.toMutableList()
    val out = mutableListOf<Edit>()
    for (i in list.indices.reversed()) {
        if (list[i] !in keep) {
            out += Edit.Remove(i)
            list.removeAt(i)
        }
    }
    var i = 0
    while (i < wanted.size) {
        if (list.getOrNull(i) == wanted[i]) {
            i++
            continue
        }
        val at = list.indexOf(wanted[i])
        if (at >= 0) {
            out += Edit.Move(at, i)
            list.add(i, list.removeAt(at))
            i++
        } else {
            val have = list.toSet()
            var end = i
            while (end < wanted.size && wanted[end] !in have) end++
            val run = to.subList(i, end)
            out += Edit.Insert(i, run.toList())
            list.addAll(i, run.map { it.uid })
            i = end
        }
    }
    return out
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

    fun seek(index: Int, positionMs: Long)
}

/**
 * Applies the queue model to the player ([sync] on every change of the book or the library): another queue is loaded at
 * its place; edits to the queue playing are applied as [edits], so the song playing never stops; a jump in the book
 * ([QueueBook.jumps]) moves the player to the current song at its position. The player moving on by itself comes back
 * through [QueueBook.at], which leaves the player as it is.
 */
class QueueBridge(private val port: PlayerPort) {
    private var loadedId: String? = null
    private var jumps: Long = 0

    fun sync(book: QueueBook, find: (SongRef) -> Song?) {
        val queue = book.current
        if (queue == null) {
            if (loadedId != null || port.loaded.isNotEmpty()) port.load(emptyList(), -1, 0)
            loadedId = null
            return
        }
        val projected = projection(queue, find)
        val start = startOf(queue, projected)
        val position = if (start >= 0 && projected[start].uid == queue.current?.uid) queue.positionMs else 0
        if (queue.id != loadedId) {
            port.load(projected, start, position)
            loadedId = queue.id
            jumps = book.jumps
            return
        }
        val playing = port.loaded.getOrNull(port.index)
        edits(port.loaded, projected).forEach(port::apply)
        val lost = playing != null && projected.none { it.uid == playing }
        if (book.jumps != jumps || lost) {
            jumps = book.jumps
            if (start >= 0) port.seek(start, position)
        }
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
