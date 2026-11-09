package app.lyrify.player

import android.content.Context
import app.lyrify.library.SongRef
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
 * What lists, sheets, search and the widget ask of Lyrify's own player. player/PlayerConnection.kt implements it
 * (milestone P1); until then [NoPlayback]. Called on the main thread; never blocks.
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

/** Until milestone P1: nothing plays, and nothing changes. */
object NoPlayback : PlaybackCommands {
    override fun play(songs: List<SongRef>, start: Int, source: QueueSource) = Unit

    override fun shuffle(songs: List<SongRef>, source: QueueSource) = Unit

    override fun playNext(songs: List<SongRef>): Undo? = null

    override fun addToQueue(songs: List<SongRef>): Undo? = null
}

/** The player's objects, built by hand (milestone P1 builds them; it may move this object to player/PlayerGraph.kt). */
object PlayerGraph {
    @Suppress("UNUSED_PARAMETER") // Until P1: no player to connect to.
    fun commands(context: Context): PlaybackCommands = NoPlayback
}
