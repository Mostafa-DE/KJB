package app.lyrify.library

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import app.lyrify.data.writeWithRetries
import app.lyrify.stats.PlaysQuery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit

/** One of the user's playlists: its songs in the user's order, a song possibly twice. [id] never changes. */
data class Playlist(val id: String, val name: String, val songs: List<SongRef>, val createdAt: Long)

/**
 * The user's playlists, in the order they were made, and Favourites: songs, and albums, artists, folders and playlists,
 * the latest added first. Songs are known by their path ([SongRef]); [Library.listed] finds them again. Stored by
 * [PlaylistsJson] in `playlists.json`; every change gives a new state.
 */
data class PlaylistsState(
    val playlists: List<Playlist> = emptyList(),
    val favouriteSongs: List<SongRef> = emptyList(),
    val favouriteCollections: List<FavouriteCollection> = emptyList(),
    /** Playlists this version cannot read (a newer version's), kept as stored and written back so. */
    val unknownPlaylists: List<JsonElement> = emptyList(),
    /** Favourite collections of a kind this version does not know, kept the same way. */
    val unknownCollections: List<JsonElement> = emptyList(),
) {
    fun playlist(id: String): Playlist? = playlists.firstOrNull { it.id == id }

    /** [playlist] in place of the one with its id, else among the others by when it was made: a new one, or Undo putting one back. */
    fun put(playlist: Playlist): PlaylistsState {
        if (playlists.any { it.id == playlist.id }) return edit(playlist.id) { playlist }
        val at = playlists.indexOfFirst { it.createdAt > playlist.createdAt }.takeIf { it >= 0 } ?: playlists.size
        return copy(playlists = playlists.take(at) + playlist + playlists.drop(at))
    }

    /** A blank [name] changes nothing. */
    fun rename(id: String, name: String): PlaylistsState = name.trim().takeIf(String::isNotEmpty)?.let { trimmed -> edit(id) { it.copy(name = trimmed) } } ?: this

    /** [songs] at the end, in their order, even those already there. */
    fun add(id: String, songs: List<SongRef>): PlaylistsState = edit(id) { it.copy(songs = it.songs + songs) }

    /** The songs at [positions] taken out ("Remove from playlist"); other copies of them stay. */
    fun removeAt(id: String, positions: Set<Int>): PlaylistsState = edit(id) { it.copy(songs = it.songs.filterIndexed { at, _ -> at !in positions }) }

    /** Only the playlist goes; the songs stay on the phone. */
    fun delete(id: String): PlaylistsState = copy(playlists = playlists.filterNot { it.id == id })

    /** [songs] made favourites, each once and before the earlier ones, or not; a song is known by its path. */
    fun favourite(songs: List<SongRef>, favourite: Boolean): PlaylistsState {
        val paths = songs.map { it.path }.toSet()
        if (!favourite) return copy(favouriteSongs = favouriteSongs.filterNot { it.path in paths })
        val known = favouriteSongs.map { it.path }.toSet()
        return copy(favouriteSongs = songs.distinctBy { it.path }.filterNot { it.path in known } + favouriteSongs)
    }

    fun favourite(collection: FavouriteCollection, favourite: Boolean): PlaylistsState = when {
        !favourite -> copy(favouriteCollections = favouriteCollections - collection)
        collection in favouriteCollections -> this
        else -> copy(favouriteCollections = listOf(collection) + favouriteCollections)
    }

    /** Songs no longer on the phone (moved to the trash) leave every playlist and Favourites. */
    fun forget(paths: Set<String>): PlaylistsState = copy(
        playlists = playlists.map { playlist -> playlist.copy(songs = playlist.songs.filterNot { it.path in paths }) },
        favouriteSongs = favouriteSongs.filterNot { it.path in paths },
    )

    private fun edit(id: String, change: (Playlist) -> Playlist): PlaylistsState = copy(playlists = playlists.map { if (it.id == id) change(it) else it })
}

/**
 * The stored form of [PlaylistsState]: JSON with a schema version. Read leniently, item by item, as the ledger is: keys
 * this version does not know are ignored, a playlist or favourite collection it cannot read is kept as stored (and
 * written back after the others), and a song entry without a path is dropped and counted. Only text that is not a JSON
 * object at all is not playlists.
 */
object PlaylistsJson {
    const val SCHEMA_VERSION: Int = 1

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(state: PlaylistsState): String = json.encodeToString(
        JsonObject.serializer(),
        JsonObject(
            mapOf(
                SCHEMA_VERSION_KEY to JsonPrimitive(SCHEMA_VERSION),
                PLAYLISTS_KEY to JsonArray(state.playlists.map(::playlist) + state.unknownPlaylists),
                FAVOURITES_KEY to JsonObject(
                    mapOf(
                        SONGS_KEY to songs(state.favouriteSongs),
                        COLLECTIONS_KEY to JsonArray(state.favouriteCollections.map(::collection) + state.unknownCollections),
                    ),
                ),
            ),
        ),
    )

    /** The state in [text]; null when it is not a JSON object. [onDropped] hears how many song entries had no path, when any did. */
    fun decode(text: String, onDropped: (Int) -> Unit = {}): PlaylistsState? {
        val root = try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (_: SerializationException) {
            null
        } ?: return null
        var dropped = 0
        val songs = { element: JsonElement? ->
            val stored = (element as? JsonArray).orEmpty()
            stored.mapNotNull(::song).also { dropped += stored.size - it.size }
        }
        val playlists = (root[PLAYLISTS_KEY] as? JsonArray).orEmpty().map { it to playlist(it, songs) }
        val favourites = root[FAVOURITES_KEY] as? JsonObject
        val collections = (favourites?.get(COLLECTIONS_KEY) as? JsonArray).orEmpty().map { it to collection(it) }
        return PlaylistsState(
            playlists = playlists.mapNotNull { it.second },
            favouriteSongs = songs(favourites?.get(SONGS_KEY)),
            favouriteCollections = collections.mapNotNull { it.second },
            unknownPlaylists = playlists.filter { it.second == null }.map { it.first },
            unknownCollections = collections.filter { it.second == null }.map { it.first },
        ).also { if (dropped > 0) onDropped(dropped) }
    }

    private fun playlist(playlist: Playlist): JsonElement = JsonObject(
        mapOf(
            ID_KEY to JsonPrimitive(playlist.id),
            NAME_KEY to JsonPrimitive(playlist.name),
            CREATED_AT_KEY to JsonPrimitive(playlist.createdAt),
            SONGS_KEY to songs(playlist.songs),
        ),
    )

    /** Null for one this version cannot read: no id or name, or songs in a form it does not know. */
    private fun playlist(element: JsonElement, songs: (JsonElement?) -> List<SongRef>): Playlist? {
        val stored = element as? JsonObject ?: return null
        val id = stored[ID_KEY]?.text()?.takeIf(String::isNotBlank) ?: return null
        val name = stored[NAME_KEY]?.text() ?: return null
        val list = stored[SONGS_KEY]
        if (list != null && list !is JsonArray) return null
        return Playlist(id, name, songs(list), (stored[CREATED_AT_KEY] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull ?: 0)
    }

    private fun songs(songs: List<SongRef>): JsonArray = JsonArray(songs.map { json.encodeToJsonElement(SongRef.serializer(), it) })

    private fun song(element: JsonElement): SongRef? = try {
        json.decodeFromJsonElement(SongRef.serializer(), element).takeIf { it.path.isNotBlank() }
    } catch (_: IllegalArgumentException) {
        // A SerializationException is one.
        null
    }

    private fun collection(collection: FavouriteCollection): JsonElement =
        JsonObject(mapOf(KIND_KEY to JsonPrimitive(collection.kind.name), KEY_KEY to JsonPrimitive(collection.key)))

    private fun collection(element: JsonElement): FavouriteCollection? {
        val stored = element as? JsonObject ?: return null
        val kind = CollectionKind.entries.firstOrNull { it.name == stored[KIND_KEY]?.text() } ?: return null
        return FavouriteCollection(kind, stored[KEY_KEY]?.text() ?: return null)
    }

    private fun JsonElement.text(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    private const val SCHEMA_VERSION_KEY: String = "schemaVersion"
    private const val PLAYLISTS_KEY: String = "playlists"
    private const val FAVOURITES_KEY: String = "favourites"
    private const val SONGS_KEY: String = "songs"
    private const val COLLECTIONS_KEY: String = "collections"
    private const val ID_KEY: String = "id"
    private const val NAME_KEY: String = "name"
    private const val CREATED_AT_KEY: String = "createdAt"
    private const val KIND_KEY: String = "kind"
    private const val KEY_KEY: String = "key"
}

/** Playlists and Favourites: `playlists.json` in the app ([PlaylistsRepository]), a fake in other features' tests. */
interface PlaylistStore : FavouritesPort {
    /** The playlists and Favourites now and after every change; empty while the file cannot be read. */
    val state: Flow<PlaylistsState>

    /** A new playlist named [name] (trimmed) holding [songs]; null when the name is blank or it could not be stored. */
    suspend fun create(name: String, songs: List<SongRef> = emptyList()): Playlist?

    // Each change below is false when it could not be stored, and then nothing changed. One on a playlist that is gone
    // changes nothing.

    suspend fun rename(id: String, name: String): Boolean

    suspend fun add(id: String, songs: List<SongRef>): Boolean

    suspend fun removeAt(id: String, positions: Set<Int>): Boolean

    suspend fun delete(id: String): Boolean

    /** Undo: [playlist] as it was before a change, or back after a delete. */
    suspend fun put(playlist: Playlist): Boolean

    /** Undo: [change] made to the playlist [id] as it is now, in one write, so the changes made since stay. */
    suspend fun edit(id: String, change: (Playlist) -> Playlist): Boolean

    /** After songs were moved to the trash: they leave every playlist and Favourites. */
    suspend fun forget(paths: Set<String>): Boolean

    /** A restore (backup/): [change] made to the whole state in one write, so a backup's playlists land together or not at all. */
    suspend fun update(change: (PlaylistsState) -> PlaylistsState): Boolean
}

/**
 * Reads and writes [PlaylistsState]. A file that is not playlists at all is first copied to [backupDir] as
 * `playlists-corrupt-<epochMillis>.json` (the [MAX_BACKUPS] newest copies are kept), and only then reported as corrupt,
 * so the store starts over without losing the user's playlists. When the copy cannot be made, the read fails with a
 * plain IOException instead and the file stays as it is. DataStore reads a corrupt file twice before it starts over: the
 * second read finds the copy of the first. The log gets counts, sizes and names of copies only.
 */
internal class PlaylistsSerializer(private val backupDir: File, private val clock: () -> Long, private val log: (String) -> Unit) : Serializer<PlaylistsState> {
    override val defaultValue: PlaylistsState = PlaylistsState()

    override suspend fun readFrom(input: InputStream): PlaylistsState {
        val bytes = input.readBytes()
        val state = PlaylistsJson.decode(bytes.decodeToString()) { log("playlists: $it song entries without a path left out") }
            ?: throw CorruptionException("Unreadable playlists, kept as ${keep(bytes)}")
        if (state.unknownPlaylists.isNotEmpty() || state.unknownCollections.isNotEmpty()) {
            log("playlists: ${state.unknownPlaylists.size + state.unknownCollections.size} entries from a newer version kept as they are")
        }
        return state
    }

    override suspend fun writeTo(t: PlaylistsState, output: OutputStream) {
        output.write(PlaylistsJson.encode(t).encodeToByteArray())
    }

    /** The name of a copy of [bytes] in [backupDir]: one already there, or a new one. Throws an IOException when it cannot make one. */
    private fun keep(bytes: ByteArray): String {
        backups().firstOrNull { (_, copy) -> copy.length() == bytes.size.toLong() && copy.readBytes().contentEquals(bytes) }?.let { return it.second.name }
        val copy = File(backupDir, "$BACKUP_PREFIX${clock()}$BACKUP_SUFFIX")
        // On storage before the store starts over.
        Files.write(copy.toPath(), bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.SYNC)
        log("playlists unreadable: kept as ${copy.name} (${bytes.size} bytes), starting over")
        // The new copy always stays, even when the clock stepped back and it is not the newest by its name.
        backups().filter { it.second != copy }.sortedByDescending { it.first }.drop(MAX_BACKUPS - 1).forEach { (_, old) ->
            try {
                Files.deleteIfExists(old.toPath())
            } catch (e: IOException) {
                log("playlists: old copy ${old.name} not removed: ${e.javaClass.simpleName}")
            }
        }
        return copy.name
    }

    /** The copies in [backupDir], with the time in their names. */
    private fun backups(): List<Pair<Long, File>> = backupDir.listFiles().orEmpty().mapNotNull { file ->
        val time = file.name.takeIf { it.startsWith(BACKUP_PREFIX) && it.endsWith(BACKUP_SUFFIX) }
            ?.removePrefix(BACKUP_PREFIX)?.removeSuffix(BACKUP_SUFFIX)?.toLongOrNull()
        time?.let { it to file }
    }

    companion object {
        const val MAX_BACKUPS: Int = 3
        private const val BACKUP_PREFIX: String = "playlists-corrupt-"
        private const val BACKUP_SUFFIX: String = ".json"
    }
}

/** The playlists' DataStore at [file]: one per file and process. It starts over only once [PlaylistsSerializer] kept a copy in [backupDir]. */
internal fun playlistsStore(file: File, backupDir: File, scope: CoroutineScope, clock: () -> Long, log: (String) -> Unit): DataStore<PlaylistsState> =
    DataStoreFactory.create(
        serializer = PlaylistsSerializer(backupDir, clock, log),
        corruptionHandler = ReplaceFileCorruptionHandler { PlaylistsState() },
        scope = scope,
        produceFile = { file },
    )

/**
 * [PlaylistStore] and [FavouritesPort] on a DataStore: app-private `files/datastore/playlists.json`, never backed up by
 * the phone (allowBackup is off; KJB's own backup holds it from 3.0). A failed write is retried a few times, then
 * reported. Built once per process by [LibraryGraph].
 */
class PlaylistsRepository internal constructor(
    private val store: DataStore<PlaylistsState>,
    private val clock: () -> Long,
    private val newId: () -> String,
    private val log: (String) -> Unit,
) : PlaylistStore {
    override val state: Flow<PlaylistsState> = store.data.catch { e ->
        if (e !is IOException) throw e
        log("playlists unreadable: ${e.javaClass.simpleName}")
        emit(PlaylistsState())
    }

    override val songs: Flow<List<SongRef>> = state.map { it.favouriteSongs }.distinctUntilChanged()

    override val collections: Flow<List<FavouriteCollection>> = state.map { it.favouriteCollections }.distinctUntilChanged()

    override suspend fun setSongs(songs: List<SongRef>, favourite: Boolean): Boolean = write { it.favourite(songs, favourite) }

    override suspend fun setCollection(collection: FavouriteCollection, favourite: Boolean): Boolean = write { it.favourite(collection, favourite) }

    override suspend fun create(name: String, songs: List<SongRef>): Playlist? {
        val trimmed = name.trim().takeIf(String::isNotEmpty) ?: return null
        val playlist = Playlist(newId(), trimmed, songs, clock())
        return playlist.takeIf { write { it.put(playlist) } }
    }

    override suspend fun rename(id: String, name: String): Boolean = write { it.rename(id, name) }

    override suspend fun add(id: String, songs: List<SongRef>): Boolean = write { it.add(id, songs) }

    override suspend fun removeAt(id: String, positions: Set<Int>): Boolean = write { it.removeAt(id, positions) }

    override suspend fun delete(id: String): Boolean = write { it.delete(id) }

    override suspend fun put(playlist: Playlist): Boolean = write { it.put(playlist) }

    override suspend fun edit(id: String, change: (Playlist) -> Playlist): Boolean = write { state -> state.playlist(id)?.let { state.put(change(it)) } ?: state }

    override suspend fun forget(paths: Set<String>): Boolean = write { it.forget(paths) }

    override suspend fun update(change: (PlaylistsState) -> PlaylistsState): Boolean = write(change)

    private suspend fun write(change: (PlaylistsState) -> PlaylistsState): Boolean =
        writeWithRetries(onFailure = { log("playlists not written: ${it.javaClass.simpleName}") }) { store.updateData(change) }
}

/** How far back "Recently added" goes. */
const val RECENTLY_ADDED_DAYS: Long = 30

/** "Recently added": the library's songs MediaStore added in the [RECENTLY_ADDED_DAYS] days before [now], the newest first. */
fun recentlyAdded(library: Library, now: Long): List<Song> {
    val since = now - TimeUnit.DAYS.toMillis(RECENTLY_ADDED_DAYS)
    return library.songs.filter { (it.addedAt ?: return@filter false) >= since }.sortedByDescending { it.addedAt }
}

/** How many songs "Most played" and "Recently played" hold. */
const val PLAYED_LIST_SIZE: Int = 50

/** "Most played": the [PLAYED_LIST_SIZE] songs played most, all time, the most first; those gone or hidden left out. */
suspend fun mostPlayed(plays: PlaysQuery, library: Library): List<Song> = library.listed(plays.mostPlayed(PLAYED_LIST_SIZE).map { it.song })

/** "Recently played": the [PLAYED_LIST_SIZE] songs played last, the latest first; those gone or hidden left out. */
suspend fun recentlyPlayed(plays: PlaysQuery, library: Library): List<Song> = library.listed(plays.recentlyPlayed(PLAYED_LIST_SIZE).map { it.song })
