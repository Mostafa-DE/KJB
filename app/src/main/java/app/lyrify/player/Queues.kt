package app.lyrify.player

import app.lyrify.library.SongRef
import kotlin.random.Random

/** "Repeat": one setting for the whole app, kept in `playback.json`. */
enum class Repeat { OFF, ALL, ONE }

/**
 * One song in a queue. [uid] is the item's own for the queue's life (reorders, Shuffle, Undo); [sourceIndex] its place in
 * the list the queue came from, null for songs added with Play next or Add to queue; [playNext] while a Play next song
 * still waits its turn; [suggested] when a suggestion put it there, not the user (v4).
 */
data class QueueItem(val uid: Long, val song: SongRef, val sourceIndex: Int? = null, val playNext: Boolean = false, val suggested: Suggested? = null)

/** What put a song in a queue without the user picking it: Autoplay after the queue's end, or Smart shuffle among its songs. */
enum class Suggested { AUTOPLAY, SMART_SHUFFLE }

/** The Shuffle button's steps: off, Shuffle, Smart shuffle (Smart shuffle option A, user 2026-11-22). */
enum class ShuffleStep { OFF, SHUFFLE, SMART }

/**
 * A queue: its songs in play order, each song once (by path), the [index] of the current one and the [positionMs] in it,
 * the list it came from ([source]), and the user's [name] for it (Rename; null shows the source and [startedAt], see
 * [queueNames]). While shuffled, [unshuffled] keeps the order before, by uid, so turning Shuffle off restores it.
 * [startedAt] is when it was made, [updatedAt] when it last played or was left, wall-clock millis. [smart]: Smart shuffle
 * is on, so suggestions are mixed in among its songs (only while shuffled).
 */
data class Queue(
    val id: String,
    val source: QueueSource,
    val items: List<QueueItem>,
    val index: Int = 0,
    val positionMs: Long = 0,
    val name: String? = null,
    val unshuffled: List<Long>? = null,
    val updatedAt: Long = 0,
    val startedAt: Long = 0,
    val smart: Boolean = false,
) {
    val shuffled: Boolean get() = unshuffled != null

    /** Where the Shuffle button stands for this queue: Smart shuffle is Shuffle with suggestions mixed in ([smart]). */
    val shuffleStep: ShuffleStep
        get() = when {
            !shuffled -> ShuffleStep.OFF
            smart -> ShuffleStep.SMART
            else -> ShuffleStep.SHUFFLE
        }

    val current: QueueItem? get() = items.getOrNull(index)

    /** Whether [item] is a suggestion (v4): one a suggestion put here, or a song of a list KJB suggested, not one added to it. */
    fun suggests(item: QueueItem): Boolean = item.suggested != null || (source.kind == SourceKind.SUGGESTIONS && item.sourceIndex != null)

    /** Whether this queue came from the place [other] names: the same kind and key, whatever the name says now. */
    fun from(other: QueueSource): Boolean = source.kind == other.kind && source.key == other.key
}

/** A change that may have moved the queue left into Recent ([toRecent]), for the one-time "Your last queue is in Recent". */
data class Switched(val book: QueueBook, val toRecent: Boolean)

/** Songs added to queue [queueId]: [added] new ones (their [uids], for Undo), [already] that were there. */
data class Added(val book: QueueBook, val queueId: String, val added: Int, val already: Int, val uids: List<Long>)

/** A queue's songs before an edit, for Undo ([QueueBook.restore]). */
data class Edited(val book: QueueBook, val queueId: String, val before: List<QueueItem>)

/**
 * [queue] deleted from [book]: kept with its place in Saved ([saved]) or Recent ([at]), and among all queues ([order],
 * which names count by), for Undo ([QueueBook.undelete]).
 */
data class Deleted(val book: QueueBook, val queue: Queue, val saved: Boolean, val at: Int, val order: Int)

/** [item] left queue [queueId] from [at]; [queueGone] when it was the last one and the queue went with it. */
data class Removed(val book: QueueBook, val queueId: String, val item: QueueItem, val at: Int, val queueGone: Boolean)

/**
 * Every queue KJB keeps: the one playing ([currentId]), Recent (newest first, at most [MAX_RECENT]) and the kept queues
 * ([saved]: made with New queue or by an import, or kept with Keep in 3.0.0; newest first, no cap), which the screens
 * list with Recent ([others]), never as a part of their own. Pure: each operation gives a new book, and
 * player/PlayerCore.kt keeps the latest. Queue ids and item uids come from counters kept with the book, so they never
 * repeat. [jumps] counts the user's moves inside or between queues (a tap, Resume, Play from start): on each new count
 * the player goes to the current queue's [Queue.index] and [Queue.positionMs]; a song changing by itself does not
 * count. The count is not stored.
 */
data class QueueBook(
    val queues: List<Queue> = emptyList(),
    val currentId: String? = null,
    val recent: List<String> = emptyList(),
    val saved: List<String> = emptyList(),
    val nextId: Long = 1,
    val nextUid: Long = 1,
    val jumps: Long = 0,
) {
    val current: Queue? get() = currentId?.let(::queue)

    fun queue(id: String): Queue? = queues.firstOrNull { it.id == id }

    /**
     * Every queue but the one playing, as every list shows them under it (the Queues page, the Queue sheet, Add to, the
     * car): the last played or left first, each once. Recent and the kept queues ([saved]) are one list on screen, so a
     * queue kept with Keep before it went (3.0.0) still shows, and stays until it is deleted.
     */
    val others: List<Queue>
        get() = (recent + saved).distinct().filter { it != currentId }.mapNotNull(::queue).sortedByDescending { it.updatedAt }

    /** The queue playing first, then [others]. */
    val listed: List<Queue> get() = listOfNotNull(current) + others

    /**
     * A tap on [songs]`[start]` in a list from [source]: a new queue of the list as shown, starting at the tapped song (an
     * album's wrapped round from it, [made]), every time, also in the list the playing queue came from (user 2026-11-24).
     * The queue left goes to Recent with its place and its Play next songs ([switchTo]), or stays in Saved at its place.
     */
    fun play(songs: List<SongRef>, start: Int, source: QueueSource, now: Long): Switched {
        val song = songs.getOrNull(start) ?: return Switched(this, toRecent = false)
        val (book, queue) = made(source, songs, song.path, now, shuffle = null)
        return book.switchTo(queue.id, now)
    }

    /** A list header's Shuffle: a new queue from [source] in a random order, the old one kept in Recent. */
    fun shuffle(songs: List<SongRef>, source: QueueSource, random: Random, now: Long): Switched {
        if (songs.isEmpty()) return Switched(this, toRecent = false)
        val (book, queue) = made(source, songs, startPath = null, now, shuffle = random)
        return book.switchTo(queue.id, now)
    }

    /**
     * Play next: [songs] go right after the current song and after the Play next songs still waiting, in their order. A
     * song already in the queue moves there instead of being added twice; the current song stays where it is. Without a
     * queue, a new one is made of [songs] ([picked]). Null when nothing changed.
     */
    fun playNext(songs: List<SongRef>, picked: QueueSource, now: Long): Edited? {
        val queue = current ?: return startWith(songs, picked, now)?.let { Edited(it.book, it.book.currentId!!, emptyList()) }
        val nowPath = queue.current?.song?.path
        val want = songs.distinctBy { it.path }.filter { it.path != nowPath }
        if (want.isEmpty()) return null
        val paths = want.map { it.path }.toSet()
        val currentUid = queue.current?.uid
        val kept = queue.items.filter { it.uid == currentUid || it.song.path !in paths }
        val index = kept.indexOfFirst { it.uid == currentUid }
        var at = index + 1
        while (at < kept.size && kept[at].playNext) at++
        val added = want.mapIndexed { i, song -> QueueItem(nextUid + i, song, playNext = true) }
        val items = kept.take(at) + added + kept.drop(at)
        val edited = edit(queue.id) { it.copy(items = items, index = items.indexOfFirst { item -> item.uid == currentUid }.coerceAtLeast(0)) }
        return Edited(edited.copy(nextUid = nextUid + added.size), queue.id, queue.items)
    }

    /**
     * [songs] at the end of queue [queueId], in their order, without the ones it holds already: a queue never holds a song
     * twice. A queue that is not playing changes and nothing else does. Null when there is no such queue.
     */
    fun addTo(queueId: String, songs: List<SongRef>): Added? {
        val queue = queue(queueId) ?: return null
        val all = songs.distinctBy { it.path }
        val have = queue.items.map { it.song.path }.toSet()
        val add = all.filterNot { it.path in have }
        if (add.isEmpty()) return Added(this, queueId, added = 0, already = all.size, uids = emptyList())
        val items = add.mapIndexed { i, song -> QueueItem(nextUid + i, song) }
        val uids = items.map { it.uid }
        val book = edit(queueId) { it.copy(items = it.items + items, unshuffled = it.unshuffled?.plus(uids)) }
        return Added(book.copy(nextUid = nextUid + items.size), queueId, add.size, all.size - add.size, uids)
    }

    /** Add to queue: [songs] at the end of the queue playing now; without one, a new queue of them ([picked]). */
    fun addToCurrent(songs: List<SongRef>, picked: QueueSource, now: Long): Added? {
        val id = currentId ?: return startWith(songs, picked, now)?.let { switched ->
            val queue = switched.book.current!!
            Added(switched.book, queue.id, queue.items.size, already = 0, uids = queue.items.map { it.uid })
        }
        return addTo(id, songs)
    }

    /**
     * "New queue" in Add to: a saved queue named [name] (null keeps the source's name) holding [songs], waiting; playback
     * is not touched. Its source is its own, so a tap in that list never lands in it.
     */
    fun newQueue(name: String?, songs: List<SongRef>, source: QueueSource, now: Long): Switched? {
        if (songs.isEmpty()) return null
        val own = source.copy(key = "${source.key}#q$nextId")
        val (book, queue) = made(own, songs, startPath = null, now, shuffle = null)
        return Switched(book.edit(queue.id) { it.copy(name = name?.trim()?.takeIf(String::isNotEmpty)) }.copy(saved = listOf(queue.id) + saved), toRecent = false)
    }

    /** Drag to reorder: the song at [from] moves to [to]; the current song stays current. */
    fun move(queueId: String, from: Int, to: Int): QueueBook {
        val queue = queue(queueId) ?: return this
        if (from == to || from !in queue.items.indices || to !in queue.items.indices) return this
        val currentUid = queue.current?.uid
        val items = queue.items.toMutableList().apply { add(to, removeAt(from)) }
        return edit(queueId) { it.copy(items = items, index = items.indexOfFirst { item -> item.uid == currentUid }.coerceAtLeast(0)) }
    }

    /**
     * Swipe to remove, or "Remove from queue": the song leaves the queue only. Removing the current song makes the next
     * one current, from its start. The last song takes the queue with it. Null when there is no such song.
     */
    fun remove(queueId: String, uid: Long): Removed? {
        val queue = queue(queueId) ?: return null
        val at = queue.items.indexOfFirst { it.uid == uid }
        if (at < 0) return null
        val item = queue.items[at]
        val items = queue.items.filterIndexed { i, _ -> i != at }
        if (items.isEmpty()) return Removed(dropped(setOf(queueId), evenCurrent = true), queueId, item, at, queueGone = true)
        val current = at == queue.index
        val index = when {
            at < queue.index -> queue.index - 1
            current -> queue.index.coerceAtMost(items.lastIndex)
            else -> queue.index
        }
        val book = edit(queueId) {
            it.copy(items = items, index = index, positionMs = if (current) 0 else it.positionMs, unshuffled = it.unshuffled?.minus(uid))
        }
        val moved = if (current && queueId == currentId) book.copy(jumps = jumps + 1) else book
        return Removed(moved, queueId, item, at, queueGone = false)
    }

    /** Undo of a remove: [item] back at [at]; the song current since stays current. */
    fun putBack(queueId: String, item: QueueItem, at: Int): QueueBook {
        val queue = queue(queueId) ?: return this
        if (queue.items.any { it.uid == item.uid }) return this
        val place = at.coerceIn(0, queue.items.size)
        val items = queue.items.take(place) + item + queue.items.drop(place)
        return edit(queueId) { it.copy(items = items, index = if (place <= it.index) it.index + 1 else it.index) }
    }

    /** Undo of Play next: the songs as they were [before]; the song current since stays current. */
    fun restore(queueId: String, before: List<QueueItem>): QueueBook {
        val queue = queue(queueId) ?: return this
        if (before.isEmpty()) return this
        val currentUid = queue.current?.uid
        val index = before.indexOfFirst { it.uid == currentUid }.takeIf { it >= 0 } ?: queue.index.coerceAtMost(before.lastIndex)
        return edit(queueId) { it.copy(items = before, index = index) }
    }

    /**
     * Undo of an add, or a controller's remove: the songs with [uids] leave queue [queueId]; the current song stays
     * current, or when it went, the next one is current from its start.
     */
    fun removeUids(queueId: String, uids: Collection<Long>): QueueBook {
        val queue = queue(queueId) ?: return this
        val currentUid = queue.current?.uid
        val items = queue.items.filterNot { it.uid in uids }
        if (items.isEmpty()) return dropped(setOf(queueId), evenCurrent = true)
        val kept = items.indexOfFirst { it.uid == currentUid }
        val index = kept.takeIf { it >= 0 } ?: queue.index.coerceAtMost(items.lastIndex)
        return edit(queueId) { it.copy(items = items, index = index, positionMs = if (kept >= 0) it.positionMs else 0, unshuffled = it.unshuffled?.minus(uids.toSet())) }
    }

    /** "Keep" (3.0.0, gone from the screens since): a recent queue moves to the kept ones, where it never drops off. */
    fun keep(queueId: String): QueueBook {
        if (queue(queueId) == null || queueId in saved) return this
        return copy(recent = recent - queueId, saved = listOf(queueId) + saved)
    }

    /** "Rename"; a blank name changes nothing. */
    fun rename(queueId: String, name: String): QueueBook {
        val trimmed = name.trim().takeIf(String::isNotEmpty) ?: return this
        return edit(queueId) { it.copy(name = trimmed) }
    }

    /** "Delete": only the queue goes, never the songs; the queue playing is never deleted. */
    fun delete(queueId: String): QueueBook = if (queueId == currentId) this else dropped(setOf(queueId), evenCurrent = false, evenSaved = true)

    /** [delete] with what Undo needs; null when nothing goes (the queue playing, or none of that id). */
    fun deleted(queueId: String): Deleted? {
        val queue = queue(queueId)?.takeIf { queueId != currentId } ?: return null
        val inSaved = queueId in saved
        return Deleted(delete(queueId), queue, inSaved, (if (inSaved) saved else recent).indexOf(queueId), queues.indexOf(queue))
    }

    /**
     * Undo of [deleted]: exactly that queue back at its place in Saved or Recent, and among the queues; nothing when it
     * is back already. Undo never takes another queue: when Recent filled again meanwhile, it holds one past
     * [MAX_RECENT] until the next queue joining it ages the oldest out, as Recent always does.
     */
    fun undelete(deleted: Deleted): QueueBook {
        val queue = deleted.queue
        if (queue(queue.id) != null) return this
        val back = copy(queues = queues.toMutableList().apply { add(deleted.order.coerceIn(0, size), queue) })
        if (deleted.saved) return back.copy(saved = saved.toMutableList().apply { add(deleted.at.coerceIn(0, size), queue.id) })
        return back.copy(recent = recent.toMutableList().apply { add(deleted.at.coerceIn(0, size), queue.id) })
    }

    /** "Resume": switches to [queueId] at its saved song and position. */
    fun resume(queueId: String, now: Long): Switched {
        if (queueId == currentId || queue(queueId) == null) return Switched(this, toRecent = false)
        return switchTo(queueId, now).let { it.copy(book = it.book.copy(jumps = it.book.jumps + 1)) }
    }

    /** "Play from start": switches to [queueId] (if it is not playing) and starts at its first song. */
    fun playFromStart(queueId: String, now: Long): Switched {
        if (queue(queueId)?.items?.isEmpty() != false) return Switched(this, toRecent = false)
        return jumpTo(queueId, 0, now)
    }

    /** A tap on the song at [index] of queue [queueId], playing or not: that queue plays from that song. */
    fun jump(queueId: String, index: Int, now: Long): Switched {
        val queue = queue(queueId) ?: return Switched(this, toRecent = false)
        if (index !in queue.items.indices) return Switched(this, toRecent = false)
        return jumpTo(queueId, index, now)
    }

    /**
     * Shuffle on the queue playing: on, the current song goes first and plays on from where it is, the Play next songs
     * still waiting right after it in their order, then every other song, before it or after it, in a random order (3.1;
     * before, the songs before it stayed above it); off, the order before comes back, with songs added meanwhile at the
     * end and Play next songs still waiting right after the current one. Off from Smart shuffle, its suggestions still to
     * come leave first; one playing stays, right after the song that played before it.
     */
    fun toggleShuffle(random: Random): QueueBook {
        val queue = current ?: return this
        val playing = queue.current ?: return this
        val currentUid = playing.uid
        val unshuffled = queue.unshuffled
        if (unshuffled != null && queue.items.drop(queue.index + 1).any { it.suggested == Suggested.SMART_SHUFFLE }) {
            return unsuggest(queue.id, Suggested.SMART_SHUFFLE).toggleShuffle(random)
        }
        if (unshuffled == null) {
            val (waiting, rest) = queue.items.filterNot { it.uid == currentUid }.partition { it.playNext }
            val items = listOf(playing) + waiting + rest.shuffled(random)
            return edit(queue.id) { it.copy(items = items, index = 0, unshuffled = queue.items.map { item -> item.uid }) }
        }
        val byUid = queue.items.associateBy { it.uid }
        val known = unshuffled.toSet()
        val restored = unshuffled.mapNotNull(byUid::get)
        val extras = queue.items.filterNot { it.uid in known }
        val suggestion = playing.takeIf { it.suggested != null && it.uid !in known }
        var list = restored + extras.filterNot { it.playNext || it === suggestion }
        if (suggestion != null) {
            val before = queue.items.take(queue.index).lastOrNull { it.uid in known }?.uid
            val place = list.indexOfFirst { it.uid == before } + 1
            list = list.take(place) + suggestion + list.drop(place)
        }
        val at = list.indexOfFirst { it.uid == currentUid }
        val items = list.take(at + 1) + extras.filter { it.playNext } + list.drop(at + 1)
        return edit(queue.id) { it.copy(items = items, index = items.indexOfFirst { item -> item.uid == currentUid }, unshuffled = null, smart = false) }
    }

    /**
     * The Shuffle button's next step on the queue playing: Off to Shuffle ([toggleShuffle]), Shuffle to Smart shuffle (the
     * order stays, suggestions come in as it plays), Smart shuffle to Off (the suggestions still to come leave, the order
     * before comes back).
     */
    fun cycleShuffle(random: Random): QueueBook {
        val queue = current ?: return this
        return if (queue.shuffleStep == ShuffleStep.SHUFFLE) edit(queue.id) { it.copy(smart = true) } else toggleShuffle(random)
    }

    /** Smart shuffle back to plain Shuffle on the queue playing (its Undo): its suggestions still to come leave, the order stays. */
    fun plainShuffle(): QueueBook {
        val queue = current?.takeIf { it.shuffleStep == ShuffleStep.SMART } ?: return this
        return unsuggest(queue.id, Suggested.SMART_SHUFFLE).edit(queue.id) { it.copy(smart = false) }
    }

    /**
     * Suggestions into queue [queueId], marked [by]: each song of [songs] right after the item with the uid it names (a
     * song after the same item in their order), none the queue holds already, none after an item that is gone.
     */
    fun suggest(queueId: String, songs: List<Pair<Long, SongRef>>, by: Suggested): QueueBook {
        val queue = queue(queueId) ?: return this
        val held = queue.items.mapTo(HashSet()) { it.song.path }
        val uids = queue.items.mapTo(HashSet()) { it.uid }
        val wanted = songs.filter { (after, song) -> after in uids && held.add(song.path) }
        if (wanted.isEmpty()) return this
        val coming = wanted.withIndex().groupBy({ it.value.first }) { QueueItem(nextUid + it.index, it.value.second, suggested = by) }
        val currentUid = queue.current?.uid
        val items = queue.items.flatMap { listOf(it) + coming[it.uid].orEmpty() }
        return edit(queueId) { it.copy(items = items, index = items.indexOfFirst { item -> item.uid == currentUid }.coerceAtLeast(0)) }.copy(nextUid = nextUid + wanted.size)
    }

    /** The suggestions marked [by] still to come in queue [queueId] leave it; the song playing stays, even one of them. */
    fun unsuggest(queueId: String, by: Suggested): QueueBook {
        val queue = queue(queueId) ?: return this
        val gone = queue.items.drop(queue.index + 1).filter { it.suggested == by }.mapTo(HashSet()) { it.uid }
        return if (gone.isEmpty()) this else removeUids(queueId, gone)
    }

    /**
     * The player moved to the song [uid] by itself (the song before ended, or Next): it is current, from its start. The
     * song already current (the player loading the queue at its place) keeps its place, and only stops waiting as a Play
     * next song.
     */
    fun at(uid: Long, now: Long): QueueBook {
        val queue = current ?: return this
        val index = queue.items.indexOfFirst { it.uid == uid }
        if (index < 0) return this
        if (index == queue.index) return if (queue.current?.playNext == true) edit(queue.id) { it.copy(items = it.items.unmarked(index)) } else this
        return edit(queue.id) { it.copy(items = it.items.unmarked(index), index = index, positionMs = 0, updatedAt = now) }
    }

    /**
     * Where the queue playing is now (saved on every song start, every pause and every 10 s while playing). With [at],
     * when the player said so: a word older than the queue's own place ([Queue.updatedAt]) changes nothing, a newer one
     * becomes its place.
     */
    fun place(index: Int, positionMs: Long, at: Long? = null): QueueBook {
        val queue = current ?: return this
        if (index !in queue.items.indices || (at != null && at < queue.updatedAt)) return this
        if (index == queue.index && positionMs == queue.positionMs) return this
        return edit(queue.id) { it.copy(index = index, positionMs = positionMs.coerceAtLeast(0), updatedAt = at ?: it.updatedAt) }
    }

    /**
     * After a library read: songs found again at a new path ([find]: moved or renamed on the same volume) take it, and
     * songs no longer on the phone leave the saved queues that are not playing (other queues skip them quietly). A saved
     * queue left empty goes.
     */
    fun refresh(find: (SongRef) -> SongRef?): QueueBook {
        val empty = mutableSetOf<String>()
        val queues = queues.map { queue ->
            val dropMissing = queue.id in saved && queue.id != currentId
            val currentUid = queue.current?.uid
            val items = queue.items.mapNotNull { item ->
                val found = find(item.song)
                when {
                    found == null -> if (dropMissing) null else item
                    found != item.song -> item.copy(song = found)
                    else -> item
                }
            }.distinctBy { it.song.path }
            if (items.isEmpty()) empty += queue.id
            if (items == queue.items) queue else {
                val index = items.indexOfFirst { it.uid == currentUid }.takeIf { it >= 0 } ?: queue.index.coerceAtMost((items.size - 1).coerceAtLeast(0))
                val kept = items.map { it.uid }.toSet()
                queue.copy(items = items, index = index, positionMs = if (items.getOrNull(index)?.uid == currentUid) queue.positionMs else 0, unshuffled = queue.unshuffled?.filter { it in kept })
            }
        }
        val book = if (queues == this.queues) this else copy(queues = queues)
        return if (empty.isEmpty()) book else book.dropped(empty.filterNot { it == currentId }.toSet(), evenCurrent = false, evenSaved = true)
    }

    /**
     * Songs the phone moved to its trash ([paths]) leave every queue: the one playing, Recent and Saved. A queue left
     * without songs goes, the one playing too. A queue whose current song went goes on at the next song left, from its
     * start, or with none after it at its first song. In the queue playing that is a jump ([jumps]); with no song after
     * it the player stops there (QueueBridge, as for any song playing that leaves).
     */
    fun forget(paths: Set<String>): QueueBook {
        val left = queues.associate { it.id to it.without(paths) }
        if (queues.all { left[it.id] === it }) return this
        val gone = left.filterValues { it == null }.keys
        val book = copy(queues = queues.map { left[it.id] ?: it }).dropped(gone, evenCurrent = true)
        val moved = current?.let { queue -> queue.id !in gone && queue.current?.song?.path in paths } == true
        return if (moved) book.copy(jumps = jumps + 1) else book
    }

    // The steps the operations share

    /** Without a queue: a new one of [songs] from [source], loaded at its first song. */
    private fun startWith(songs: List<SongRef>, source: QueueSource, now: Long): Switched? {
        if (songs.isEmpty()) return null
        val (book, queue) = made(source, songs, startPath = null, now, shuffle = null)
        return book.switchTo(queue.id, now)
    }

    /**
     * A new queue of [songs] (each song once, the first of its copies kept), current at [startPath]'s song. An album
     * starts at that song instead: the album's songs after it, then round to those before it (user 2026-11-24).
     */
    private fun made(source: QueueSource, songs: List<SongRef>, startPath: String?, now: Long, shuffle: Random?): Pair<QueueBook, Queue> {
        val unique = songs.distinctBy { it.path }
        val ordered = unique.mapIndexed { i, song -> QueueItem(nextUid + i, song, sourceIndex = i) }
        val items = when {
            shuffle != null -> ordered.shuffled(shuffle)
            source.kind == SourceKind.ALBUM && startPath != null -> ordered.indexOfFirst { it.song.path == startPath }.coerceAtLeast(0).let { ordered.drop(it) + ordered.take(it) }
            else -> ordered
        }
        val queue = Queue(
            id = "q$nextId",
            source = source,
            items = items,
            index = startPath?.let { path -> items.indexOfFirst { it.song.path == path } }?.coerceAtLeast(0) ?: 0,
            unshuffled = if (shuffle != null) ordered.map { it.uid } else null,
            updatedAt = now,
            startedAt = now,
        )
        return copy(queues = queues + queue, nextId = nextId + 1, nextUid = nextUid + items.size) to queue
    }

    /**
     * [queueId] plays: the queue left goes to Recent at the front with its place (a saved one stays in Saved), whatever
     * list it came from (user 2026-11-24: two queues from one list both stay); Recent keeps what [recentKept] keeps, the
     * oldest going first. A book's queue left goes: `books.json` keeps its place, and the Books tab's Continue row goes
     * back to it.
     */
    private fun switchTo(queueId: String, now: Long): Switched {
        if (queue(queueId) == null) return Switched(this, toRecent = false)
        var recent = recent
        val drop = mutableSetOf<String>()
        val old = current
        var book = this
        if (old != null && old.id != queueId) {
            book = book.edit(old.id) { it.copy(updatedAt = now) }
            if (old.id !in saved && old.source.kind == SourceKind.BOOK) {
                drop += old.id
            } else if (old.id !in saved) {
                recent = listOf(old.id) + (recent - old.id)
            }
        }
        val kept = recentKept(recent - queueId) { queue(it)?.items?.size ?: 0 }
        drop += recent - kept.toSet()
        recent = kept
        book = book.copy(currentId = queueId, recent = recent).edit(queueId) { it.copy(updatedAt = now) }
        val toRecent = old != null && old.id != queueId && old.id in recent
        return Switched(book.dropped(drop - queueId, evenCurrent = false), toRecent)
    }

    /** Queue [queueId] plays (switched to, if it was not playing) from the song at [index], at its start. */
    private fun jumpTo(queueId: String, index: Int, now: Long): Switched {
        val moved = if (queueId == currentId) Switched(this, toRecent = false) else switchTo(queueId, now)
        val book = moved.book.edit(queueId) { it.copy(items = it.items.unmarked(index), index = index, positionMs = 0, updatedAt = now) }
        return moved.copy(book = book.copy(jumps = book.jumps + 1))
    }

    private fun edit(queueId: String, change: (Queue) -> Queue): QueueBook = copy(queues = queues.map { if (it.id == queueId) change(it) else it })

    /** [ids] removed everywhere; never a saved queue unless [evenSaved], never the current one unless [evenCurrent]. */
    private fun dropped(ids: Set<String>, evenCurrent: Boolean, evenSaved: Boolean = evenCurrent): QueueBook {
        val gone = ids.filter { (evenCurrent || it != currentId) && (evenSaved || it !in saved) }.toSet()
        if (gone.isEmpty()) return this
        return copy(
            queues = queues.filterNot { it.id in gone },
            currentId = currentId?.takeUnless { it in gone },
            recent = recent - gone,
            saved = saved - gone,
        )
    }

    companion object {
        /** Recent keeps this many queues, the newest first (user 2026-11-24). */
        const val MAX_RECENT: Int = 25

        /** Recent keeps this many songs in all, its newest queue whatever its size (2026-11-24: queues.json stays small). */
        const val MAX_RECENT_SONGS: Int = 20_000
    }
}

/**
 * Of [recent] (queue ids, the newest first), the ones Recent keeps: at most [QueueBook.MAX_RECENT] queues and
 * [QueueBook.MAX_RECENT_SONGS] songs in all ([songs] of each), the oldest going first; the newest always stays. The
 * queue playing and the saved ones are not in it, so they never count. The same rule for a switch and for a read.
 */
internal fun recentKept(recent: List<String>, songs: (String) -> Int): List<String> {
    val total = recent.runningFold(0) { sum, id -> sum + songs(id) }.drop(1)
    return recent.take(minOf(QueueBook.MAX_RECENT, total.count { it <= QueueBook.MAX_RECENT_SONGS }.coerceAtLeast(1)))
}

/**
 * This queue without the songs at [paths]; null when none is left. Its current song gone, the next one left is current
 * from its start, or with none after it the first.
 */
private fun Queue.without(paths: Set<String>): Queue? {
    val kept = items.filterNot { it.song.path in paths }
    if (kept.size == items.size) return this
    if (kept.isEmpty()) return null
    val stays = current?.takeIf { it.song.path !in paths }
    val next = stays ?: items.drop(index + 1).firstOrNull { it.song.path !in paths }
    val uids = kept.mapTo(HashSet()) { it.uid }
    return copy(
        items = kept,
        index = next?.let { song -> kept.indexOfFirst { it.uid == song.uid } } ?: 0,
        positionMs = if (stays != null) positionMs else 0,
        unshuffled = unshuffled?.filter { it in uids },
    )
}

/** These items with the one at [index] no longer waiting as a Play next song: it is playing. */
private fun List<QueueItem>.unmarked(index: Int): List<QueueItem> =
    if (getOrNull(index)?.playNext == true) mapIndexed { i, item -> if (i == index) item.copy(playNext = false) else item } else this

/**
 * The names the queues show, by id: the user's name as given, else [made] (the list the queue came from and the time it
 * started, "Songs · 21:30"). A made name that another queue shows already, a user's name or an older queue's, takes the
 * first free number ("Songs · 21:30 2"), so two queues never read the same.
 */
fun queueNames(queues: List<Queue>, made: (Queue) -> String): Map<String, String> {
    val taken = queues.mapNotNull { it.name }.toMutableSet()
    val names = queues.mapNotNull { queue -> queue.name?.let { queue.id to it } }.toMap().toMutableMap()
    // Oldest first, so a queue keeps its name when a newer one comes from the same list in the same minute.
    queues.filter { it.name == null }.sortedWith(compareBy({ it.startedAt }, { it.id.length }, { it.id })).forEach { queue ->
        val base = made(queue)
        val name = if (base !in taken) base else generateSequence(2) { it + 1 }.map { "$base $it" }.first { it !in taken }
        taken += name
        names[queue.id] = name
    }
    return names
}

/**
 * Whether the song at [index] of [queue] continues an album in track order: the queue came from an album, Shuffle is
 * off, the song came from the album's list, and so did the one before it, as its predecessor there (an album's first
 * song counts, and so does its first song right after the last of its songs in the queue, as an album tapped at a song
 * wraps round). A Play next song, a song added at the end, or one moved out of place breaks it. The one rule behind
 * volume levelling's Auto mode (album gain) and "Keep albums gapless" (no crossfade inside an album).
 */
fun albumInOrder(queue: Queue, index: Int): Boolean {
    if (queue.source.kind != SourceKind.ALBUM || queue.shuffled) return false
    val item = queue.items.getOrNull(index) ?: return false
    val place = item.sourceIndex ?: return false
    val before = queue.items.getOrNull(index - 1) ?: return true
    return before.sourceIndex == place - 1 || (place == 0 && before.sourceIndex == queue.items.maxOf { it.sourceIndex ?: -1 })
}
