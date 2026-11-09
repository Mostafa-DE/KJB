package app.lyrify.ui

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import app.lyrify.library.FavouriteCollection
import app.lyrify.library.FavouritesPort
import app.lyrify.library.Playlist
import app.lyrify.library.PlaylistStore
import app.lyrify.library.Song
import app.lyrify.library.SongRef
import app.lyrify.player.PlaybackCommands
import app.lyrify.player.QueueSource
import app.lyrify.player.QueueTarget
import app.lyrify.player.Undo
import java.text.Collator

// Song actions, the one door: the song sheet, Add to, selection, Song info, Move to trash, and the toasts with Undo.

/** Where the song sheet was opened: its rows that only fit there (Remove from playlist, Remove from queue) follow it. */
sealed interface SheetPlace {
    /** Any list of songs. */
    data object List : SheetPlace

    /** One of the user's playlists, the song at [position] in it (a playlist may hold a song twice). */
    data class Playlist(val id: String, val position: Int) : SheetPlace

    /** A queue's page or the Queue sheet (P1), the song at [position] in queue [id]. */
    data class Queue(val id: String, val position: Int) : SheetPlace
}

/** The song sheet's rows, in the order they show. Move to trash sits apart at the bottom. */
enum class SheetRow { PLAY_NEXT, ADD_TO, FAVOURITE, LYRICS, MAKE_TONE, SONG_INFO, EDIT_TAGS, COVERS_TAGS, REMOVE_FROM_QUEUE, REMOVE_FROM_PLAYLIST, MOVE_TO_TRASH }

/**
 * The rows the song sheet shows when opened at [place]. [coversTags] and [removeFromQueue]: their slots are filled
 * ([SongSheetSlots]); an empty slot's row does not show.
 */
fun sheetRows(place: SheetPlace, coversTags: Boolean, removeFromQueue: Boolean): List<SheetRow> = SheetRow.entries.filter { row ->
    when (row) {
        SheetRow.COVERS_TAGS -> coversTags
        SheetRow.REMOVE_FROM_QUEUE -> removeFromQueue && place is SheetPlace.Queue
        SheetRow.REMOVE_FROM_PLAYLIST -> place is SheetPlace.Playlist
        else -> true
    }
}

/** One place in the Add to sheet, in the order they show. */
sealed interface AddTarget {
    data object PlayNext : AddTarget

    data object AddToQueue : AddTarget

    data class Queue(val queue: QueueTarget) : AddTarget

    data object NewQueue : AddTarget

    data object Favourites : AddTarget

    data class ToPlaylist(val playlist: Playlist) : AddTarget

    data object NewPlaylist : AddTarget
}

/**
 * The Add to sheet's places: Play next and Add to queue, then the queues (saved, then recent; not the one playing,
 * which Add to queue is) and New queue, then Favourites, then the user's [playlists] by name and New playlist.
 */
fun addTargets(queues: List<QueueTarget>, playlists: List<Playlist>): List<AddTarget> {
    val others = queues.filterNot { it.playing }
    val collator = Collator.getInstance()
    return listOf(AddTarget.PlayNext, AddTarget.AddToQueue) +
        (others.filter { it.saved } + others.filterNot { it.saved }).map(AddTarget::Queue) +
        listOf(AddTarget.NewQueue, AddTarget.Favourites) +
        playlists.sortedWith(compareBy(collator) { it.name }).map(AddTarget::ToPlaylist) +
        AddTarget.NewPlaylist
}

/** [base], or "[base] 2", "[base] 3" and on: the first name none of [taken] has. */
fun nextName(base: String, taken: Collection<String>): String =
    generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base $it" }.first { it !in taken }

/** A list's selection, by song path: started by a long press, kept across a turn of the phone. */
@Immutable
data class SongSelection(val paths: Set<String> = emptySet()) {
    /** Something is selected: the list shows its checkboxes and the selection bar. */
    val active: Boolean get() = paths.isNotEmpty()

    fun toggle(song: Song): SongSelection = copy(paths = if (song.path in paths) paths - song.path else paths + song.path)

    /** Whether every song of [list] is selected: the "All" checkbox. */
    fun allOf(list: List<Song>): Boolean = list.isNotEmpty() && list.all { it.path in paths }

    /** "All": every song of [list], or none when every one already is. */
    fun all(list: List<Song>): SongSelection = if (allOf(list)) SongSelection() else SongSelection(list.mapTo(HashSet()) { it.path })

    /** The selected songs in [list]'s order, each once: what the four verbs act on. */
    fun picked(list: List<Song>): List<Song> = list.filter { it.path in paths }.distinctBy { it.path }

    /** Songs that left [list] (trashed, the list changed) leave the selection. */
    fun within(list: List<Song>): SongSelection = list.mapTo(HashSet()) { it.path }.let { listed -> if (paths.all(listed::contains)) this else SongSelection(paths intersect listed) }

    companion object {
        val Saver: Saver<SongSelection, Any> = listSaver(save = { it.paths.toList() }, restore = { SongSelection(it.toSet()) })
    }
}

/** What a song action did, as its toast says it. */
sealed interface Said {
    data object PlaysNext : Said

    data object AddedToQueue : Said

    /** [count] songs added to [name]; [already] were there (a queue holds a song once). */
    data class AddedTo(val name: String, val count: Int, val already: Int = 0) : Said

    /** Every one of the [count] songs is in queue [name] already: nothing added. */
    data class AlreadyIn(val name: String, val count: Int) : Said

    data class Favourites(val added: Boolean) : Said

    /** The empty playlist [name] was made. */
    data class Made(val name: String) : Said

    data class RemovedFrom(val name: String) : Said

    data object Renamed : Said

    data object PlaylistDeleted : Said

    /** The change could not be stored: nothing changed. */
    data object NotSaved : Said
}

/** What a toast says after an action, and its Undo when the action can be taken back (false when that was not stored). */
class Outcome(val said: Said, val undo: (suspend () -> Boolean)? = null)

/**
 * What the song sheet, Add to, selection and collection pages do to songs, playlists and Favourites. Queues go through
 * [playback] (the player's own model, milestone P1); playlists and Favourites through [playlists] and [favourites]. Each
 * returns what its toast says, with Undo; null when nothing changed (the player has no queue yet).
 */
class SongActions(private val playback: PlaybackCommands, private val playlists: PlaylistStore, private val favourites: FavouritesPort = playlists) {
    fun playNext(songs: List<SongRef>): Outcome? = playback.playNext(songs)?.let { Outcome(Said.PlaysNext, it.asUndo()) }

    fun addToQueue(songs: List<SongRef>): Outcome? = playback.addToQueue(songs)?.let { Outcome(Said.AddedToQueue, it.asUndo()) }

    fun addToQueue(queue: QueueTarget, songs: List<SongRef>): Outcome? {
        val added = playback.addToQueue(queue.id, songs) ?: return null
        if (added.added == 0) return Outcome(Said.AlreadyIn(queue.name, added.already))
        return Outcome(Said.AddedTo(queue.name, added.added, added.already), added.undo?.asUndo())
    }

    fun newQueue(name: String, songs: List<SongRef>, source: QueueSource?): Outcome? =
        playback.newQueue(name, songs, source)?.let { Outcome(Said.AddedTo(name, songs.distinctBy { song -> song.path }.size), it.asUndo()) }

    /** The heart for [songs]: all of them favourites already, they all stop being one; else the others become one. */
    suspend fun favourite(songs: List<SongRef>, now: List<SongRef>): Outcome {
        val known = now.mapTo(HashSet()) { it.path }
        val adding = songs.any { it.path !in known }
        val change = (if (adding) songs.filterNot { it.path in known } else songs).distinctBy { it.path }
        if (!favourites.setSongs(change, adding)) return Outcome(Said.NotSaved)
        return Outcome(Said.Favourites(adding)) { favourites.setSongs(change, !adding) }
    }

    /** The heart on a collection page. */
    suspend fun favourite(collection: FavouriteCollection, on: Boolean): Outcome {
        if (!favourites.setCollection(collection, on)) return Outcome(Said.NotSaved)
        return Outcome(Said.Favourites(on)) { favourites.setCollection(collection, !on) }
    }

    /** [songs] at the end of [playlist], even those already in it. */
    suspend fun addToPlaylist(playlist: Playlist, songs: List<SongRef>): Outcome {
        if (!playlists.add(playlist.id, songs)) return Outcome(Said.NotSaved)
        return Outcome(Said.AddedTo(playlist.name, songs.size)) { playlists.put(playlist) }
    }

    /** "New playlist" named [name] holding [songs] (none from the Playlists tab). */
    suspend fun newPlaylist(name: String, songs: List<SongRef>): Outcome {
        val made = playlists.create(name, songs) ?: return Outcome(Said.NotSaved)
        val said = if (songs.isEmpty()) Said.Made(made.name) else Said.AddedTo(made.name, songs.size)
        return Outcome(said) { playlists.delete(made.id) }
    }

    /** "Remove from playlist": the song at [position] in [playlist]. */
    suspend fun removeFromPlaylist(playlist: Playlist, position: Int): Outcome {
        if (!playlists.removeAt(playlist.id, setOf(position))) return Outcome(Said.NotSaved)
        return Outcome(Said.RemovedFrom(playlist.name)) { playlists.put(playlist) }
    }

    suspend fun rename(playlist: Playlist, name: String): Outcome =
        if (playlists.rename(playlist.id, name)) Outcome(Said.Renamed) { playlists.put(playlist) } else Outcome(Said.NotSaved)

    /** Only the playlist goes; the songs stay on the phone. */
    suspend fun delete(playlist: Playlist): Outcome =
        if (playlists.delete(playlist.id)) Outcome(Said.PlaylistDeleted) { playlists.put(playlist) } else Outcome(Said.NotSaved)

    private fun Undo.asUndo(): suspend () -> Boolean = {
        undo()
        true
    }
}
