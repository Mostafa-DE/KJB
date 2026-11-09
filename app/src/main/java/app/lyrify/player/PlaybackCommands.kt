package app.lyrify.player

import app.lyrify.library.SongRef
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/** What a list in the app is, as the source of a queue. */
enum class SourceKind { SONGS, ALBUM, ARTIST, FOLDER, GENRE, PLAYLIST, FAVOURITES, MOST_PLAYED, RECENTLY_PLAYED, RECENTLY_ADDED, SEARCH, PICKED }

/**
 * The list a queue was made from. [kind] and [key] tell one list from another, so a tap in the list the current queue
 * came from jumps inside that queue; [name] is shown after the kind ("Album · Harbour Glass").
 */
@Serializable
data class QueueSource(val kind: SourceKind, val key: String, val name: String)

/** Takes one change to a queue back: the toast's Undo. */
fun interface Undo {
    fun undo()
}

/**
 * What lists, sheets, search and the widget ask of KJB's own player. player/PlayerConnection.kt implements it
 * ([PlayerGraph.commands]); [NoPlayback] plays nothing (previews, tests). Called on the main thread; never blocks.
 */
interface PlaybackCommands {
    /**
     * A tap on the song at [start] in [songs]: inside the current queue when that came from [source] (songs added with
     * Play next stay), else in a new queue from [source], the old one kept in Recent.
     */
    fun play(songs: List<SongRef>, start: Int, source: QueueSource)

    /** A list header's Shuffle: a new queue from [source], shuffled. */
    fun shuffle(songs: List<SongRef>, source: QueueSource)

    /** Play next: [songs] go right after the current song and any earlier Play next songs. Null when nothing changed. */
    fun playNext(songs: List<SongRef>): Undo?

    /** Add to queue: [songs] go to the end of the queue playing now. Null when nothing changed. */
    fun addToQueue(songs: List<SongRef>): Undo?
}

/** Nothing plays, and nothing changes: previews and tests. */
object NoPlayback : PlaybackCommands {
    override fun play(songs: List<SongRef>, start: Int, source: QueueSource) = Unit

    override fun shuffle(songs: List<SongRef>, source: QueueSource) = Unit

    override fun playNext(songs: List<SongRef>): Undo? = null

    override fun addToQueue(songs: List<SongRef>): Undo? = null
}

/** A queue the "Add to" sheet offers: saved queues first, then recent ones, each with its song count. */
data class QueueTarget(val id: String, val source: QueueSource, val name: String?, val songs: Int, val saved: Boolean)

/** Songs added to a queue that is not the one playing: how many were new, how many it held already, and Undo. */
data class AddedTo(val added: Int, val already: Int, val undo: Undo?)

/**
 * The "Add to" sheet's Queues section (L1 draws it, P1 keeps the queues): the saved and recent queues, adding to one
 * (never touching playback, never a song twice), and "New queue". player/PlayerConnection.kt implements it
 * ([PlayerGraph.queueTargets]). Main thread.
 */
interface QueueTargets {
    /** Saved queues, newest first, then recent ones; the queue playing is "Add to queue" at the sheet's top instead. */
    val targets: StateFlow<List<QueueTarget>>

    /** [songs] at the end of queue [queueId], those it holds already skipped; null when there is no such queue. */
    fun addTo(queueId: String, songs: List<SongRef>): AddedTo?

    /** "New queue": a saved queue named [name] (null: the source's name) of [songs], waiting; null when there are none. */
    fun newQueue(name: String?, songs: List<SongRef>, source: QueueSource): Undo?
}
