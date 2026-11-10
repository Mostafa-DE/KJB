package app.lyrify.player

import app.lyrify.library.SongRef
import kotlinx.coroutines.flow.MutableStateFlow
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

/** A saved or recent queue, as the Add to sheet and the Playlists tab's Queues row show it. [id] never changes. */
data class QueueTarget(val id: String, val name: String, val songs: Int, val saved: Boolean, val playing: Boolean)

/** Songs added to a queue that is not playing: [added] of them, [already] skipped because the queue holds them. */
data class QueueAdded(val added: Int, val already: Int, val undo: Undo?)

/**
 * What lists, sheets, search and the widget ask of KJB's own player. player/PlayerConnection.kt implements it
 * (milestone P1); until then [NoPlayback]. Called on the main thread; never blocks.
 */
interface PlaybackCommands {
    /** The saved queues, then the recent ones, the newest first, the one playing among them: the Add to sheet's Queues. */
    val queues: StateFlow<List<QueueTarget>>

    /**
     * A tap on the song at [start] in [songs]: inside the current queue when that came from [source] and is not saved
     * (songs added with Play next stay), else in a new queue from [source], the old one kept in Recent (a saved one stays
     * in Saved, at its place).
     */
    fun play(songs: List<SongRef>, start: Int, source: QueueSource)

    /** A list header's Shuffle: a new queue from [source], shuffled. */
    fun shuffle(songs: List<SongRef>, source: QueueSource)

    /** Play next: [songs] go right after the current song and any earlier Play next songs. Null when nothing changed. */
    fun playNext(songs: List<SongRef>): Undo?

    /** Add to queue: [songs] go to the end of the queue playing now. Null when nothing changed. */
    fun addToQueue(songs: List<SongRef>): Undo?

    /**
     * Add to a named queue (the Add to sheet's Queues): [songs] go to the end of queue [id], each once, those it holds
     * skipped. Playback is never touched. Null when there is no such queue.
     */
    fun addToQueue(id: String, songs: List<SongRef>): QueueAdded?

    /** "New queue": a saved queue named [name] holding [songs] (each once), made from [source]; nothing starts playing. */
    fun newQueue(name: String, songs: List<SongRef>, source: QueueSource?): Undo?
}

/** Until milestone P1: nothing plays, and nothing changes. */
object NoPlayback : PlaybackCommands {
    override val queues: StateFlow<List<QueueTarget>> = MutableStateFlow(emptyList())

    override fun play(songs: List<SongRef>, start: Int, source: QueueSource) = Unit

    override fun shuffle(songs: List<SongRef>, source: QueueSource) = Unit

    override fun playNext(songs: List<SongRef>): Undo? = null

    override fun addToQueue(songs: List<SongRef>): Undo? = null

    override fun addToQueue(id: String, songs: List<SongRef>): QueueAdded? = null

    override fun newQueue(name: String, songs: List<SongRef>, source: QueueSource?): Undo? = null
}
