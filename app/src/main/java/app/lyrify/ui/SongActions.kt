package app.lyrify.ui

import android.content.res.Resources
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lyrify.Lyrify
import app.lyrify.R
import app.lyrify.dupes.Confirmation
import app.lyrify.dupes.trashFile
import app.lyrify.library.FavouriteCollection
import app.lyrify.library.FavouritesPort
import app.lyrify.library.Library
import app.lyrify.library.LibraryGraph
import app.lyrify.library.Playlist
import app.lyrify.library.PlaylistStore
import app.lyrify.library.Song
import app.lyrify.library.SongRef
import app.lyrify.library.albumKey
import app.lyrify.player.PlaybackCommands
import app.lyrify.player.QueueSource
import app.lyrify.player.QueueTarget
import app.lyrify.player.SourceKind
import app.lyrify.player.Undo
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.text.Collator

// Song actions, the one door: the song sheet, Add to, selection, Song info, Move to trash, and the toasts with Undo.

/** Where the song sheet was opened: its rows that only fit there (Remove from playlist, Remove from queue) follow it. */
@Serializable
sealed interface SheetPlace {
    /** Any list of songs. */
    @Serializable @SerialName("list")
    data object List : SheetPlace

    /** One of the user's playlists, the song at [position] in it (a playlist may hold a song twice). */
    @Serializable @SerialName("playlist")
    data class Playlist(val id: String, val position: Int) : SheetPlace

    /** A queue's page or the Queue sheet (P1), the song at [position] in queue [id]. */
    @Serializable @SerialName("queue")
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

    /** Songs that left [list] (trashed, the list changed) leave the selection; a list still loading (null) keeps it as it is. */
    fun within(list: List<Song>?): SongSelection =
        list?.mapTo(HashSet()) { it.path }?.let { listed -> if (paths.all(listed::contains)) this else SongSelection(paths intersect listed) } ?: this

    companion object {
        private val large = InMemory<SongSelection>()

        /** Its paths in the saved state, up to [SAVED_SONGS_MAX]; a larger one (a select-all) stays in memory only. */
        val Saver: Saver<SongSelection, Any> = Saver(
            save = { if (it.paths.size <= SAVED_SONGS_MAX) ArrayList(it.paths) else large.keep(it) },
            restore = { saved -> if (saved is Long) large.find(saved) ?: SongSelection() else SongSelection((saved as List<*>).filterIsInstance<String>().toSet()) },
        )
    }
}

/**
 * Songs a page keeps in its saved state, in a selection or an open sheet: that state has a size limit (about 1 MB for
 * the whole app), so a select-all or Add all of a large library stays in memory ([InMemory]).
 */
const val SAVED_SONGS_MAX: Int = 500

/**
 * A page's state too large for the saved state, held in memory under a key the saved state keeps instead, so a turn of
 * the phone finds it again. Only the latest one of each kind is held, and none after the process dies. Main thread only.
 */
private class InMemory<T : Any> {
    private var held: Pair<Long, T>? = null
    private var keys = 0L

    fun keep(value: T): Long = (++keys).also { held = it to value }

    fun find(key: Long): T? = held?.takeIf { it.first == key }?.second
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

    data object RemovedFromQueue : Said

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

    // A playlist's Undo changes the playlist as it is then, never putting back a copy from before: a change made while the
    // toast was up (another page, an import, songs moved to the trash) stays.

    /** [songs] at the end of [playlist], even those already in it. Undo takes out what this appended. */
    suspend fun addToPlaylist(playlist: Playlist, songs: List<SongRef>): Outcome {
        if (!playlists.add(playlist.id, songs)) return Outcome(Said.NotSaved)
        return Outcome(Said.AddedTo(playlist.name, songs.size)) { playlists.edit(playlist.id) { it.copy(songs = withoutAppended(it.songs, songs)) } }
    }

    /** "New playlist" named [name] holding [songs] (none from the Playlists tab). */
    suspend fun newPlaylist(name: String, songs: List<SongRef>): Outcome {
        val made = playlists.create(name, songs) ?: return Outcome(Said.NotSaved)
        val said = if (songs.isEmpty()) Said.Made(made.name) else Said.AddedTo(made.name, songs.size)
        return Outcome(said) { playlists.delete(made.id) }
    }

    /** "Remove from playlist": the song at [position] in [playlist]. Undo puts it back at [position]. */
    suspend fun removeFromPlaylist(playlist: Playlist, position: Int): Outcome {
        if (!playlists.removeAt(playlist.id, setOf(position))) return Outcome(Said.NotSaved)
        val removed = playlist.songs.getOrNull(position) ?: return Outcome(Said.RemovedFrom(playlist.name))
        return Outcome(Said.RemovedFrom(playlist.name)) {
            playlists.edit(playlist.id) { it.copy(songs = it.songs.take(position) + removed + it.songs.drop(position)) }
        }
    }

    /** Undo gives the old name back while the playlist still has [name]. */
    suspend fun rename(playlist: Playlist, name: String): Outcome =
        if (playlists.rename(playlist.id, name)) {
            Outcome(Said.Renamed) { playlists.edit(playlist.id) { if (it.name == name.trim()) it.copy(name = playlist.name) else it } }
        } else {
            Outcome(Said.NotSaved)
        }

    /** Only the playlist goes; the songs stay on the phone. */
    suspend fun delete(playlist: Playlist): Outcome =
        if (playlists.delete(playlist.id)) Outcome(Said.PlaylistDeleted) { playlists.put(playlist) } else Outcome(Said.NotSaved)

    private fun Undo.asUndo(): suspend () -> Boolean = {
        undo()
        true
    }
}

/**
 * [songs] without the [appended] ones: from the end, the last copy of each in turn, so copies from before stay, songs
 * added since stay, and one already taken out is not looked for.
 */
private fun withoutAppended(songs: List<SongRef>, appended: List<SongRef>): List<SongRef> {
    val kept = songs.toMutableList()
    var end = kept.size
    for (song in appended.asReversed()) {
        val at = kept.subList(0, end).lastIndexOf(song)
        if (at < 0) continue
        kept.removeAt(at)
        end = at
    }
    return kept
}

// Slots other milestones fill

/**
 * The song sheet's rows that other milestones fill (plan section 4), each with a one-line change here; until then a slot
 * is null and its row does not show. "Make synced" and "Edit timing" (P3) and "Edit tags" (T3) open views declared in
 * Nav.kt, which those milestones fill, so their rows show already; Song info's plays line reads [Lyrify.plays] (S1).
 */
internal object SongSheetSlots {
    /** P2: "Remove from queue" for the song at [position] in queue [queueId], the visible twin of the swipe; its Undo. */
    val removeFromQueue: ((queueId: String, position: Int) -> Undo?)? = null

    /** P2: the song's bookmarks under the rows, each jumping there; [close] closes the sheet. */
    val bookmarks: (@Composable (song: SongRef, close: () -> Unit) -> Unit)? = null

    /** T2: "Covers & tags", which opens the review of the song's album. */
    val coversTags: ((song: Song, nav: Navigator) -> Unit)? = null
}

// The sheets a page opens

/** The sheet or dialog a page has open on songs. Stored by its type name and fields, so it survives a turn of the phone. */
@Serializable
sealed interface OpenSheet {
    /** The song sheet ("⋯"), opened at [place]. */
    @Serializable @SerialName("song")
    data class Menu(val song: SongRef, val place: SheetPlace = SheetPlace.List) : OpenSheet

    /** Add to, for [songs] from [source]; [name] is where a new queue's name starts. */
    @Serializable @SerialName("addTo")
    data class AddTo(val songs: List<SongRef>, val source: QueueSource?, val name: String) : OpenSheet

    @Serializable @SerialName("lyrics")
    data class Lyrics(val song: SongRef) : OpenSheet

    @Serializable @SerialName("info")
    data class Info(val song: SongRef) : OpenSheet

    /** The selection's More: Song info (one song), Edit tags, Move to trash. */
    @Serializable @SerialName("more")
    data class More(val songs: List<SongRef>) : OpenSheet

    @Serializable @SerialName("newQueue")
    data class NewQueue(val songs: List<SongRef>, val source: QueueSource?, val name: String) : OpenSheet

    @Serializable @SerialName("newPlaylist")
    data class NewPlaylist(val songs: List<SongRef>) : OpenSheet

    /** KJB's "Move to trash?"; the phone's own dialog follows. */
    @Serializable @SerialName("trash")
    data class Trash(val songs: List<SongRef>) : OpenSheet

    /** Today's Tone editor with the song. */
    @Serializable @SerialName("tone")
    data class MakeTone(val song: SongRef) : OpenSheet
}

/** Which sheet a page has open ([SongSheetsHost] draws it). Main thread only. */
@Stable
class SongSheets(open: OpenSheet? = null) {
    var open: OpenSheet? by mutableStateOf(open)
        private set

    fun show(sheet: OpenSheet) {
        open = sheet
    }

    /** The song sheet for [song], from any list: the entry for pages outside the Library (Your listening, Search). */
    fun openSong(song: SongRef) = show(OpenSheet.Menu(song))

    fun close() {
        open = null
    }
}

private val SHEETS_JSON = Json { ignoreUnknownKeys = true }

private val largeSheets = InMemory<OpenSheet>()

/** How many songs [sheet] holds, as the saved state would. */
private fun songsIn(sheet: OpenSheet): Int = when (sheet) {
    is OpenSheet.AddTo -> sheet.songs.size
    is OpenSheet.More -> sheet.songs.size
    is OpenSheet.NewQueue -> sheet.songs.size
    is OpenSheet.NewPlaylist -> sheet.songs.size
    is OpenSheet.Trash -> sheet.songs.size
    else -> 1
}

/**
 * A page's open sheet across a turn of the phone: by its type name and fields, or in memory only when it holds more than
 * [SAVED_SONGS_MAX] songs (Add all of a large list). One this version cannot read is closed.
 */
internal val SongSheetsSaver: Saver<SongSheets, Any> = Saver(
    save = { sheets -> sheets.open?.let { if (songsIn(it) <= SAVED_SONGS_MAX) SHEETS_JSON.encodeToString(OpenSheet.serializer(), it) else largeSheets.keep(it) } },
    restore = { saved ->
        SongSheets(
            try {
                if (saved is Long) largeSheets.find(saved) else SHEETS_JSON.decodeFromString(OpenSheet.serializer(), saved as String)
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            },
        )
    },
)

/** A page's [SongSheets], kept across a turn of the phone ([SongSheetsSaver]). */
@Composable
fun rememberSongSheets(): SongSheets = rememberSaveable(saver = SongSheetsSaver) { SongSheets() }

/** The app's [SongActions]: KJB's own player and `playlists.json`. */
@Composable
fun rememberSongActions(): SongActions {
    val context = LocalContext.current
    return remember(context) { SongActions(Lyrify.playback(context), LibraryGraph.playlists(context)) }
}

/** Shows what [outcome] says, with Undo when it has one; an Undo that could not be stored says so. */
suspend fun ToastState.say(resources: Resources, outcome: Outcome?) {
    val said = outcome?.said ?: return
    val undo = outcome.undo
    val toast = Toast(saidText(resources, said), saidIcon(said), if (said == Said.NotSaved) Tone.ERROR else null, undo?.let { resources.getString(R.string.lib_undo) })
    if (show(toast) && undo != null && !undo()) say(resources, R.string.lib_not_saved)
}

/** The words of [said]. */
fun saidText(resources: Resources, said: Said): String = when (said) {
    Said.PlaysNext -> resources.getString(R.string.lib_plays_next)
    Said.AddedToQueue -> resources.getString(R.string.lib_added_to_queue)
    is Said.AddedTo -> when {
        said.already > 0 -> resources.getQuantityString(R.plurals.lib_added_already, said.count, said.count, said.name, said.already)
        said.count == 1 -> resources.getString(R.string.lib_added_to, said.name)
        else -> resources.getQuantityString(R.plurals.lib_added_songs, said.count, said.count, said.name)
    }
    is Said.AlreadyIn ->
        if (said.count == 1) resources.getString(R.string.lib_already_in, said.name) else resources.getQuantityString(R.plurals.lib_all_already_in, said.count, said.count, said.name)
    is Said.Favourites -> resources.getString(if (said.added) R.string.lib_favourites_added else R.string.lib_favourites_removed)
    is Said.Made -> resources.getString(R.string.lib_made, said.name)
    is Said.RemovedFrom -> resources.getString(R.string.lib_removed_from, said.name)
    Said.RemovedFromQueue -> resources.getString(R.string.lib_removed_from_queue)
    Said.Renamed -> resources.getString(R.string.lib_renamed)
    Said.PlaylistDeleted -> resources.getString(R.string.lib_playlist_deleted)
    Said.NotSaved -> resources.getString(R.string.lib_not_saved)
}

@DrawableRes
private fun saidIcon(said: Said): Int = when (said) {
    Said.PlaysNext -> R.drawable.ic_queue_play_next
    Said.AddedToQueue, is Said.AlreadyIn -> R.drawable.ic_queue_music
    is Said.AddedTo, is Said.Made -> R.drawable.ic_playlist_add
    is Said.Favourites -> if (said.added) R.drawable.ic_favorite_fill else R.drawable.ic_favorite
    is Said.RemovedFrom, Said.RemovedFromQueue -> R.drawable.ic_playlist_remove
    Said.Renamed -> R.drawable.ic_edit
    Said.PlaylistDeleted -> R.drawable.ic_delete
    Said.NotSaved -> R.drawable.ic_error
}

/** How a queue's source reads: "Album · Harbour Glass", "Favourites"; the start of a new queue's name. */
fun sourceLabel(resources: Resources, source: QueueSource): String = when (source.kind) {
    SourceKind.ALBUM -> resources.getString(R.string.lib_source_album, source.name)
    SourceKind.ARTIST -> resources.getString(R.string.lib_source_artist, source.name)
    SourceKind.FOLDER -> resources.getString(R.string.lib_source_folder, source.name)
    SourceKind.GENRE -> resources.getString(R.string.lib_source_genre, source.name)
    SourceKind.PLAYLIST -> resources.getString(R.string.lib_source_playlist, source.name)
    SourceKind.SEARCH -> resources.getString(R.string.lib_source_search, source.name)
    SourceKind.PICKED -> resources.getString(R.string.lib_source_picked, source.name)
    SourceKind.FAVOURITES -> resources.getString(R.string.lib_favourites)
    SourceKind.MOST_PLAYED -> resources.getString(R.string.lib_most_played)
    SourceKind.RECENTLY_PLAYED -> resources.getString(R.string.lib_recently_played)
    SourceKind.RECENTLY_ADDED -> resources.getString(R.string.lib_recently_added)
    SourceKind.SONGS -> source.name.ifEmpty { resources.getString(R.string.library_tab_songs) }
}

/** The source of songs picked by hand in a selection: a new one each time. */
fun pickedSource(resources: Resources, count: Int, now: Long): QueueSource =
    QueueSource(SourceKind.PICKED, "picked:$now", resources.getQuantityString(R.plurals.lib_songs, count, count))

/**
 * A page outside the Library's lists that opens song sheets (Your listening's Top songs, Search): keep
 * `val sheets = rememberSongSheets()`, call `sheets.openSong(ref)` on a tap, and place this once on the page.
 */
@Composable
fun SongSheetsHost(sheets: SongSheets) {
    rememberLibraryUi()?.let { SongSheetsHost(sheets, it) }
}

/**
 * The sheet or dialog [sheets] has open, over the page: the song sheet, Add to, Lyrics, Song info, the selection's More,
 * the name dialogs, Move to trash (KJB's dialog, then the phone's) and the Tone editor. Each action closes it and says
 * what it did, with Undo; [onActed] hears it too, so a selection ends.
 */
@Composable
fun SongSheetsHost(sheets: SongSheets, ui: LibraryUi, onActed: () -> Unit = {}) {
    val nav = LocalNavigator.current
    val toasts = LocalToasts.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val actions = rememberSongActions()
    val trash = rememberDuplicates().trash
    val phoneTrash = rememberPhoneTrash(trash, toasts)
    val library = ui.library ?: return
    val open = sheets.open ?: return
    fun songOf(ref: SongRef): Song? = library.resolver.find(ref)
    fun done(outcome: Outcome?) {
        sheets.close()
        onActed()
        scope.launch { toasts.say(resources, outcome) }
    }
    fun doneLater(action: suspend () -> Outcome) {
        sheets.close()
        onActed()
        scope.launch { toasts.say(resources, action()) }
    }
    when (open) {
        is OpenSheet.Menu -> {
            val song = songOf(open.song) ?: return Gone(sheets)
            val rows = sheetRows(open.place, SongSheetSlots.coversTags != null, SongSheetSlots.removeFromQueue != null)
            SongMenuSheet(
                song = song,
                ui = ui,
                rows = rows,
                onArtist = song.artist?.takeIf { library.artist(it) != null }?.let { name -> { sheets.close(); nav.open(Page.Artist(name)) } },
                onAlbum = albumPageOf(song, library)?.let { page -> { sheets.close(); nav.open(page) } },
                bookmarks = SongSheetSlots.bookmarks?.let { slot -> { slot(song.ref, sheets::close) } },
                onDismiss = sheets::close,
            ) { row ->
                when (row) {
                    SheetRow.PLAY_NEXT -> done(actions.playNext(listOf(song.ref)))
                    SheetRow.ADD_TO -> sheets.show(OpenSheet.AddTo(listOf(song.ref), null, ui.title(song)))
                    SheetRow.FAVOURITE -> scope.launch { toasts.say(resources, actions.favourite(listOf(song.ref), ui.playlists.favouriteSongs)) }
                    SheetRow.LYRICS -> sheets.show(OpenSheet.Lyrics(song.ref))
                    SheetRow.MAKE_TONE -> sheets.show(OpenSheet.MakeTone(song.ref))
                    SheetRow.SONG_INFO -> sheets.show(OpenSheet.Info(song.ref))
                    SheetRow.EDIT_TAGS -> {
                        sheets.close()
                        nav.show(FullScreen.EditTags(listOf(song.ref)))
                    }
                    SheetRow.COVERS_TAGS -> {
                        sheets.close()
                        SongSheetSlots.coversTags?.invoke(song, nav)
                    }
                    SheetRow.REMOVE_FROM_QUEUE -> (open.place as? SheetPlace.Queue)?.let { place ->
                        done(SongSheetSlots.removeFromQueue?.invoke(place.id, place.position)?.let { undo -> Outcome(Said.RemovedFromQueue) { undo.undo(); true } })
                    }
                    SheetRow.REMOVE_FROM_PLAYLIST -> (open.place as? SheetPlace.Playlist)?.let { place ->
                        ui.playlists.playlist(place.id)?.let { playlist -> doneLater { actions.removeFromPlaylist(playlist, place.position) } }
                    }
                    SheetRow.MOVE_TO_TRASH -> sheets.show(OpenSheet.Trash(listOf(song.ref)))
                }
            }
        }
        is OpenSheet.AddTo -> AddToSheet(open.songs, addToTitle(open.songs, ui, library), ui, onDismiss = sheets::close) { target ->
            when (target) {
                AddTarget.PlayNext -> done(actions.playNext(open.songs))
                AddTarget.AddToQueue -> done(actions.addToQueue(open.songs))
                is AddTarget.Queue -> done(actions.addToQueue(target.queue, open.songs))
                AddTarget.NewQueue -> sheets.show(OpenSheet.NewQueue(open.songs, open.source, open.name))
                AddTarget.Favourites -> doneLater { actions.favourite(open.songs, ui.playlists.favouriteSongs) }
                is AddTarget.ToPlaylist -> doneLater { actions.addToPlaylist(target.playlist, open.songs) }
                AddTarget.NewPlaylist -> sheets.show(OpenSheet.NewPlaylist(open.songs))
            }
        }
        is OpenSheet.NewQueue -> NameDialog(stringResource(R.string.lib_new_queue), open.name, R.drawable.ic_queue_music, onDismiss = sheets::close) { name ->
            done(actions.newQueue(name, open.songs, open.source))
        }
        is OpenSheet.NewPlaylist -> {
            val start = nextName(stringResource(R.string.lib_new_playlist), ui.playlists.playlists.map { it.name })
            NameDialog(stringResource(R.string.lib_new_playlist), start, R.drawable.ic_playlist_add, onDismiss = sheets::close) { name ->
                doneLater { actions.newPlaylist(name, open.songs) }
            }
        }
        is OpenSheet.Lyrics -> {
            val song = songOf(open.song) ?: return Gone(sheets)
            LyricsSheet(entry = ui.ledger.entries[song.path], song = song, onPickLyrics = { sheets.close(); nav.open(Page.Check(song.path)) }, onDismiss = sheets::close)
        }
        is OpenSheet.Info -> SongInfoSheet(songOf(open.song) ?: return Gone(sheets), ui, onDismiss = sheets::close)
        is OpenSheet.More -> {
            val songs = open.songs.mapNotNull(::songOf)
            SelectionMoreSheet(
                count = songs.size,
                onInfo = songs.singleOrNull()?.let { song -> { sheets.show(OpenSheet.Info(song.ref)) } },
                onEditTags = {
                    sheets.close()
                    onActed()
                    nav.show(FullScreen.EditTags(songs.map { it.ref }))
                },
                onTrash = { sheets.show(OpenSheet.Trash(songs.map { it.ref })) },
                onDismiss = sheets::close,
            )
        }
        is OpenSheet.Trash -> SongTrashDialog(open.songs.size, onCancel = sheets::close) {
            sheets.close()
            val files = open.songs.mapNotNull { songOf(it)?.let(::trashFile) }
            scope.launch {
                if (files.size != open.songs.size || !trash.ask(files)) {
                    toasts.say(resources, R.string.dupes_trash_failed)
                    return@launch
                }
                when (val confirmation = trash.confirm(files)) {
                    is Confirmation.Ask -> {
                        onActed()
                        phoneTrash(confirmation.ids, true)
                    }
                    Confirmation.Changed -> toasts.say(resources, R.string.lib_trash_changed)
                    Confirmation.Unavailable -> toasts.say(resources, R.string.dupes_trash_failed)
                    Confirmation.NotAsked -> Unit
                }
            }
        }
        is OpenSheet.MakeTone -> {
            val song = songOf(open.song) ?: return Gone(sheets)
            ToneEditorFor(song, ui.ledger.entries[song.path], onSaved = { tone ->
                sheets.close()
                scope.launch { toasts.say(resources, R.string.tones_saved_as, tone.name) }
            }, onClose = sheets::close)
        }
    }
}

/** A sheet whose song left the phone or the library meanwhile: it closes. */
@Composable
private fun Gone(sheets: SongSheets) {
    LaunchedEffect(Unit) { sheets.close() }
}

/** The page a song's album link opens: its album, or "Unknown album" for a song without an album tag. */
internal fun albumPageOf(song: Song, library: Library): Page? = when (val key = albumKey(song)) {
    null -> Page.UnknownAlbum.takeIf { library.unknownAlbum.isNotEmpty() }
    else -> Page.Album(key).takeIf { library.album(key) != null }
}

/** Add to's line under its title: the song's name, or "7 songs". */
@Composable
private fun addToTitle(songs: List<SongRef>, ui: LibraryUi, library: Library): String =
    songs.singleOrNull()?.let { library.resolver.find(it) }?.let(ui::title) ?: pluralStringResource(R.plurals.lib_songs, songs.size, songs.size)

// Selection

/** A page's top bar while it selects: Leave selection, "3 selected", and the All checkbox. */
@Composable
fun SelectionTopBar(selection: SongSelection, list: List<Song>, under: Boolean, backdrop: GlassBackdrop?, onChange: (SongSelection) -> Unit) {
    val count = selection.picked(list).size
    val all = selection.allOf(list)
    LyrifyTopBar(
        title = {
            Text(
                pluralStringResource(R.plurals.lib_selected, count, count),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        },
        under = under,
        backdrop = backdrop,
        navigation = { ChromeIconButton(R.drawable.ic_close, stringResource(R.string.lib_leave_selection), { onChange(SongSelection()) }) },
        actions = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .clip(CircleShape)
                    .toggleable(all, role = Role.Checkbox) { onChange(selection.all(list)) }
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 8.dp),
            ) {
                LyrifyCheckbox(all)
                Text(stringResource(R.string.lib_all), style = MaterialTheme.typography.labelLarge)
            }
        },
    )
}

/**
 * A page's selection: back leaves it, songs that leave [list] leave it once the list is [loaded] (a selection kept across
 * a turn of the phone is not cleared by the empty list before), and while it is on, its bar of four verbs (Play, Add,
 * Favourite, More) stands in for the Now bar and the dock.
 */
@Composable
fun SelectionHost(selection: SongSelection, list: List<Song>, loaded: Boolean, ui: LibraryUi, sheets: SongSheets, onChange: (SongSelection) -> Unit) {
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val toasts = LocalToasts.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val actions = rememberSongActions()
    BackHandler(enabled = selection.active) { onChange(SongSelection()) }
    LaunchedEffect(list, loaded) { selection.within(list.takeIf { loaded }).let { if (it != selection) onChange(it) } }
    val bar: @Composable () -> Unit = {
        val picked = selection.picked(list)
        val refs = picked.map { it.ref }
        SelectionBar(
            onPlay = {
                Lyrify.playback(context).play(refs, 0, pickedSource(resources, refs.size, System.currentTimeMillis()))
                onChange(SongSelection())
            },
            onAdd = {
                val source = pickedSource(resources, refs.size, System.currentTimeMillis())
                sheets.show(OpenSheet.AddTo(refs, source, sourceLabel(resources, source)))
            },
            onFavourite = {
                onChange(SongSelection())
                scope.launch { toasts.say(resources, actions.favourite(refs, ui.playlists.favouriteSongs)) }
            },
            onMore = { sheets.show(OpenSheet.More(refs)) },
        )
    }
    val current by rememberUpdatedState(bar)
    val active = selection.active
    DisposableEffect(active) {
        if (active) nav.selectionBar = { current() }
        onDispose { if (active) nav.selectionBar = null }
    }
}

/** The selection's bar in place of the Now bar and the dock: Play, Add, Favourite and More, each an icon over its word. */
@Composable
private fun SelectionBar(onPlay: () -> Unit, onAdd: () -> Unit, onFavourite: () -> Unit, onMore: () -> Unit) {
    GlassSurface(null, CircleShape, Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Row(Modifier.heightIn(min = 64.dp).padding(horizontal = 4.dp)) {
            SelectionVerb(R.drawable.ic_play_arrow_fill, R.string.lib_play, Modifier.weight(1f), onPlay)
            SelectionVerb(R.drawable.ic_playlist_add, R.string.lib_add, Modifier.weight(1f), onAdd)
            SelectionVerb(R.drawable.ic_favorite, R.string.lib_favourite, Modifier.weight(1f), onFavourite)
            SelectionVerb(R.drawable.ic_more_horiz, R.string.lib_more, Modifier.weight(1f), onMore)
        }
    }
}

@Composable
private fun SelectionVerb(@DrawableRes icon: Int, @StringRes label: Int, modifier: Modifier, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .clickable(interactionSource = null, indication = PressIndication(RoundedCornerShape(20.dp), 0.92f), role = Role.Button, onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(vertical = 8.dp),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp))
        Text(stringResource(label), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
    }
}
