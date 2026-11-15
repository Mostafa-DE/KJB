package app.lyrify.backup

import app.lyrify.library.Playlist
import app.lyrify.library.PlaylistsState
import app.lyrify.library.Song
import app.lyrify.library.SongRef
import app.lyrify.player.QueueBook
import app.lyrify.player.QueueSource
import app.lyrify.player.SourceKind
import app.lyrify.stats.LoggedPlay
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/** What an import can bring, each with its check box in the preview, in its order. */
enum class ImportKind { PLAYLISTS, FAVOURITES, PLAYS, QUEUES }

/**
 * The stores an import changes, each through its own API: the app's in ImportSources.kt ([AppImportStores]), in memory
 * in tests. A change is false when it could not be stored, and then nothing in that store changed.
 */
interface ImportStores {
    suspend fun updatePlaylists(change: (PlaylistsState) -> PlaylistsState): Boolean

    suspend fun updateQueues(change: (QueueBook) -> QueueBook): Boolean

    /** Every play in the log; null when it cannot be read. */
    suspend fun plays(): List<LoggedPlay>?

    suspend fun addPlays(plays: List<LoggedPlay>): Boolean

    /** Undo: [plays] taken out of the log, all or none. */
    suspend fun removePlays(plays: List<LoggedPlay>): Boolean

    /**
     * Keeps [undo], what an import or an Undo under way still has to take back, so one the app's death cuts short is
     * taken back on the next start ([ImportPlan.recover]); null when nothing is under way. False when it could not.
     */
    suspend fun journal(undo: ImportUndo?): Boolean

    /** What [journal] last kept; null when nothing is under way or it cannot be read. */
    suspend fun journaled(): ImportUndo?
}

/**
 * What one import added, so one Undo takes back that and nothing else: the playlists it made with the songs it put in
 * them, the songs it added at the end of playlists already there, the songs it made favourites, the plays it added,
 * and the saved queues it made with their items' uids.
 */
data class ImportUndo(
    val created: Map<String, List<SongRef>> = emptyMap(),
    val added: Map<String, List<SongRef>> = emptyMap(),
    val favourites: List<SongRef> = emptyList(),
    val plays: List<LoggedPlay> = emptyList(),
    val queues: Map<String, List<Long>> = emptyMap(),
) {
    val isEmpty: Boolean get() = created.isEmpty() && added.isEmpty() && favourites.isEmpty() && plays.isEmpty() && queues.isEmpty()
}

/**
 * What an import did, for its summary: playlists made, playlists that got new songs and how many songs that was,
 * favourites added, songs whose play count went up; and how to take it all back.
 */
data class ImportOutcome(
    val created: Int,
    val extended: Int,
    val songsAdded: Int,
    val favouritesAdded: Int,
    val countsRaised: Int,
    val undo: ImportUndo,
    val queuesAdded: Int = 0,
    /** False when a write failed and what was written could not all be taken back: [undo] holds what is left. */
    val complete: Boolean = true,
) {
    /** "Imported 12 playlists". */
    val playlists: Int get() = created + extended

    /** False when the phone already had all of it. */
    val changed: Boolean get() = !undo.isEmpty
}

/** A queue as an import adds it: its [name], its songs on this phone, the one it was at among them (-1: not here) and the second. */
internal data class PlacedQueue(val name: String, val songs: List<SongRef>, val index: Int, val positionMs: Long)

/** [ImportUndo] as the journal keeps it on storage: JSON; null for what is not one. */
object ImportJournal {
    fun encode(undo: ImportUndo): String = JsonObject(
        mapOf(
            CREATED_KEY to JsonObject(undo.created.mapValues { (_, songs) -> refs(songs) }),
            ADDED_KEY to JsonObject(undo.added.mapValues { (_, songs) -> refs(songs) }),
            FAVOURITES_KEY to refs(undo.favourites),
            PLAYS_KEY to JsonArray(undo.plays.map { play -> JsonObject(ref(play.song) + mapOf(STARTED_KEY to JsonPrimitive(play.startedAt), HEARD_KEY to JsonPrimitive(play.heardMs), COUNTED_KEY to JsonPrimitive(play.counted))) }),
            QUEUES_KEY to JsonObject(undo.queues.mapValues { (_, uids) -> JsonArray(uids.map(::JsonPrimitive)) }),
        ),
    ).toString()

    fun decode(text: String): ImportUndo? = try {
        val root = Json.parseToJsonElement(text) as? JsonObject ?: return null
        fun lists(key: String): Map<String, List<SongRef>>? = (root[key] as? JsonObject)?.mapValues { (_, songs) -> refs(songs) ?: return null }
        ImportUndo(
            created = lists(CREATED_KEY) ?: return null,
            added = lists(ADDED_KEY) ?: return null,
            favourites = refs(root[FAVOURITES_KEY] ?: return null) ?: return null,
            plays = (root[PLAYS_KEY] as? JsonArray ?: return null).map { element ->
                val play = element as? JsonObject ?: return null
                LoggedPlay(ref(play) ?: return null, play.number(STARTED_KEY) ?: return null, play.number(HEARD_KEY) ?: 0, (play[COUNTED_KEY] as? JsonPrimitive)?.booleanOrNull ?: return null)
            },
            queues = (root[QUEUES_KEY] as? JsonObject ?: return null).mapValues { (_, uids) -> (uids as? JsonArray ?: return null).map { (it as? JsonPrimitive)?.longOrNull ?: return null } },
        )
    } catch (_: SerializationException) {
        null
    }

    private fun refs(songs: List<SongRef>): JsonArray = JsonArray(songs.map { JsonObject(ref(it)) })

    private fun ref(song: SongRef): Map<String, JsonElement> = buildMap {
        put(PATH_KEY, JsonPrimitive(song.path))
        song.mediaStoreId?.let { put(ID_KEY, JsonPrimitive(it)) }
    }

    private fun refs(element: JsonElement): List<SongRef>? = (element as? JsonArray)?.map { ref(it as? JsonObject ?: return null) ?: return null }

    private fun ref(stored: JsonObject): SongRef? {
        val path = (stored[PATH_KEY] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        return SongRef(path, stored.number(ID_KEY))
    }

    private fun JsonObject.number(key: String): Long? = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull

    private const val CREATED_KEY: String = "created"
    private const val ADDED_KEY: String = "added"
    private const val FAVOURITES_KEY: String = "favourites"
    private const val PLAYS_KEY: String = "plays"
    private const val QUEUES_KEY: String = "queues"
    private const val PATH_KEY: String = "path"
    private const val ID_KEY: String = "id"
    private const val STARTED_KEY: String = "startedAt"
    private const val HEARD_KEY: String = "heardMs"
    private const val COUNTED_KEY: String = "counted"
}

/**
 * An import matched against this phone's songs ([phone], the library's every song) by [SongMatcher.find], each song
 * once, before anything is written: the preview's counts and the songs not found come from it, and [apply] writes it.
 */
class ImportPlan(val bundle: ImportBundle, phone: List<Song>) {
    /**
     * Each song the import names, matched once: the song it is on this phone, when found by [SongMatcher.find], else by
     * a file name only one song of the phone has ([byName]).
     */
    private val found: Map<ImportSong, Song> = SongMatcher(phone).let { matcher ->
        val songs = songs(ImportKind.entries.toSet()).distinct()
        val sure = songs.mapNotNull { song -> matcher.find(song)?.let { song to it } }.toMap()
        sure + byName(songs.filter { it !in sure }, matcher, sure.values.mapTo(HashSet()) { it.path })
    }

    /** "12 playlists, 340 favourites, play counts for 1,200 songs": what the files hold, kind by kind, those with none left out. */
    val counts: Map<ImportKind, Int> = listOf(
        ImportKind.PLAYLISTS to bundle.playlists.size,
        ImportKind.FAVOURITES to bundle.favourites.distinctBy(::key).size,
        ImportKind.PLAYS to bundle.plays.filter { it.count > 0 }.distinctBy { key(it.song) }.size,
        ImportKind.QUEUES to bundle.queues.size,
    ).filter { it.second > 0 }.toMap()

    /** "1 queue, 6,863 songs": how many songs the queues hold together. */
    val queueSongs: Int = bundle.queues.sumOf { it.songs.size }

    /** The kinds ticked at first: all but the queues (stale, and seldom wanted), unless the queues are all there is. */
    fun defaultChosen(): Set<ImportKind> = (counts.keys - ImportKind.QUEUES).ifEmpty { counts.keys }

    /** The songs the [chosen] kinds name that are not on this phone, each once, in their order. */
    fun missing(chosen: Set<ImportKind>): List<ImportSong> = songs(chosen).filter { it !in found }.distinctBy(::key)

    /** The songs the [chosen] kinds name, in the preview's order, repeats and all. */
    private fun songs(chosen: Set<ImportKind>): List<ImportSong> = buildList {
        if (ImportKind.PLAYLISTS in chosen) bundle.playlists.forEach { addAll(it.songs) }
        if (ImportKind.FAVOURITES in chosen) addAll(bundle.favourites)
        if (ImportKind.PLAYS in chosen) bundle.plays.forEach { add(it.song) }
        if (ImportKind.QUEUES in chosen) bundle.queues.forEach { addAll(it.songs) }
    }

    /**
     * Writes the [chosen] kinds after matching, all or nothing: playlists and favourites in one write of their store,
     * then the queues ([mergeQueues], each named by [queueName] from its name in the other app, if any), then the
     * plays. What is written is kept in the journal ([ImportStores.journal]) after each write, so when a later write
     * fails it is taken back ([undo]), and when the app dies meanwhile it is taken back on the next start ([recover]).
     * Never wipes: see [merge] and [raised]. [now] stamps new playlists and queues and the plays of songs whose last
     * play is not known; [untitled] names a playlist without a name; [reserved] are the names of KJB's own lists
     * ("Favourites"), which a playlist does not take. Null when nothing was written, or all of it was taken back; an
     * outcome not [ImportOutcome.complete] when a write failed and not all could be taken back yet, with the Undo for
     * what is left. The log gets counts and exception class names only.
     */
    suspend fun apply(
        chosen: Set<ImportKind>,
        stores: ImportStores,
        newId: () -> String,
        now: Long,
        untitled: String,
        reserved: Set<String> = emptySet(),
        log: (String) -> Unit = {},
        queueName: (String?) -> String = { untitled },
    ): ImportOutcome? {
        val playlists = if (ImportKind.PLAYLISTS in chosen) bundle.playlists.map { playlist -> (playlist.name ?: untitled) to playlist.songs.mapNotNull { found[it]?.ref } } else emptyList()
        val favourites = if (ImportKind.FAVOURITES in chosen) bundle.favourites.mapNotNull { found[it]?.ref } else emptyList()
        val wanted = if (ImportKind.PLAYS in chosen) bundle.plays.mapNotNull { plays -> found[plays.song]?.let { it to plays } } else emptyList()
        // The plays are worked out before the first write, so a log that cannot be read changes nothing.
        val plays = if (wanted.isEmpty()) {
            emptyList()
        } else {
            val kept = stores.plays() ?: run {
                log("import: play log not read, nothing written")
                return null
            }
            raised(kept, wanted, now)
        }
        // What is written so far: kept in the journal after each write, so one the app's death cuts short is taken back.
        var written = ImportUndo()
        suspend fun failed(store: String): ImportOutcome? {
            val left = takeBack(written, stores, log)
            log("import: $store not written, ${if (left.isEmpty) "the rest taken back" else "not all taken back yet"}")
            return if (left.isEmpty) null else outcome(left, complete = false)
        }
        var merged: ImportUndo? = null
        if (!stores.updatePlaylists { state -> merge(state, playlists, favourites, newId, now, reserved).also { merged = it.second }.first }) {
            log("import: playlists not written, nothing changed")
            return null
        }
        written = merged ?: ImportUndo()
        if (!stores.journal(written)) return failed("journal")
        val queues = if (ImportKind.QUEUES in chosen) bundle.queues.map { onPhone(it, queueName(it.name)) } else emptyList()
        if (queues.isNotEmpty()) {
            var made = emptyMap<String, List<Long>>()
            if (!stores.updateQueues { book -> mergeQueues(book, queues, now).also { made = it.second }.first }) return failed("queues")
            written = written.copy(queues = made)
            if (!stores.journal(written)) return failed("journal")
        }
        if (plays.isNotEmpty()) {
            // Journaled before they are written: taking back plays never written takes nothing.
            written = written.copy(plays = plays)
            if (!stores.journal(written) || !stores.addPlays(plays)) return failed("plays")
        }
        if (!stores.journal(null)) log("import: journal not cleared")
        return outcome(written).also { done ->
            log("import: ${done.created} playlists made, ${done.extended} added to, ${done.favouritesAdded} favourites, ${done.countsRaised} counts raised, ${done.queuesAdded} queues")
        }
    }

    private fun outcome(undo: ImportUndo, complete: Boolean = true): ImportOutcome = ImportOutcome(
        created = undo.created.size,
        extended = undo.added.size,
        songsAdded = undo.added.values.sumOf { it.size },
        favouritesAdded = undo.favourites.size,
        countsRaised = undo.plays.distinctBy { it.song.path }.size,
        undo = undo,
        queuesAdded = undo.queues.size,
        complete = complete,
    )

    /** [queue] named [name], with its songs on this phone, in order, and the one it was at among them. */
    private fun onPhone(queue: ImportQueue, name: String): PlacedQueue {
        val here = queue.songs.mapIndexedNotNull { at, song -> found[song]?.let { at to it.ref } }
        return PlacedQueue(name, here.map { it.second }, here.indexOfFirst { it.first == queue.index }, queue.positionMs)
    }

    companion object {
        /** An import adds at most this many plays to one song, whatever count a file claims... */
        const val MAX_PLAYS_PER_SONG: Int = 10_000

        /** ...and this many in all: plays.db keeps plays, not counts, so each one counted is a row. */
        const val MAX_IMPORTED_PLAYS: Int = 200_000

        /**
         * Takes back everything [undo] lists and nothing else ([unqueue], [unmerge]), each store in one write: the
         * queues, then the plays, then the playlists and favourites last. What is still to take back is kept in the
         * journal after each step, so an Undo the app's death cuts short is finished on the next start ([recover]), and
         * one that fails can be tried again: the queues and plays can be taken back twice without harm, and the
         * playlists change once, last. False when not all could be taken back yet.
         */
        suspend fun undo(undo: ImportUndo, stores: ImportStores, log: (String) -> Unit = {}): Boolean = takeBack(undo, stores, log).isEmpty

        /** [undo]'s way, giving back what is still to take back: nothing when all of it is. */
        private suspend fun takeBack(undo: ImportUndo, stores: ImportStores, log: (String) -> Unit): ImportUndo {
            if (undo.isEmpty) {
                if (!stores.journal(null)) log("import undo: journal not cleared")
                return undo
            }
            var left = undo
            if (!stores.journal(left)) log("import undo: journal not written")
            if (left.queues.isNotEmpty()) {
                if (!stores.updateQueues { unqueue(it, left.queues) }) {
                    log("import undo: queues not written, nothing changed")
                    return left
                }
                left = left.copy(queues = emptyMap())
                stores.journal(left)
            }
            if (left.plays.isNotEmpty()) {
                if (!stores.removePlays(left.plays)) {
                    log("import undo: plays not removed")
                    return left
                }
                left = left.copy(plays = emptyList())
                stores.journal(left)
            }
            if (!stores.updatePlaylists { unmerge(it, left) }) {
                log("import undo: playlists not written")
                return left
            }
            if (!stores.journal(null)) log("import undo: journal not cleared")
            log("import undo: ${undo.created.size} playlists, ${undo.added.size} added to, ${undo.favourites.size} favourites, ${undo.plays.size} plays, ${undo.queues.size} queues taken back")
            return ImportUndo()
        }

        /** On start: takes back an import or an Undo the app's death cut short, as the journal kept it; true when there is none left. */
        suspend fun recover(stores: ImportStores, log: (String) -> Unit = {}): Boolean {
            val left = stores.journaled() ?: return true
            log("import: an import cut short is taken back")
            return undo(left, stores, log)
        }

        /**
         * [now] with an import's [playlists] (each a name and its songs on this phone, in order) and [favourites] added,
         * never wiping anything: a name not there makes a playlist; a playlist of the same name (case aside) gets only
         * the songs it does not hold yet, each once, at its end in the import's order, and one that would not change is
         * left as it is; favourites are a union, the new ones each once after the user's own, in the import's order. A
         * name of [reserved] gets " (2)". What it added comes back with it.
         */
        internal fun merge(
            now: PlaylistsState,
            playlists: List<Pair<String, List<SongRef>>>,
            favourites: List<SongRef>,
            newId: () -> String,
            at: Long,
            reserved: Set<String> = emptySet(),
        ): Pair<PlaylistsState, ImportUndo> {
            var state = now
            val created = LinkedHashMap<String, List<SongRef>>()
            val added = LinkedHashMap<String, List<SongRef>>()
            playlists.forEach { (named, songs) ->
                if (songs.isEmpty()) return@forEach
                val name = named.trim().let { if (reserved.any { taken -> sameName(taken, it) }) M3u.uniqueName(it, reserved) else it }
                val there = state.playlists.firstOrNull { sameName(it.name, name) }
                if (there == null) {
                    val made = Playlist(newId(), name, songs, at)
                    created[made.id] = songs
                    state = state.put(made)
                    return@forEach
                }
                val held = there.songs.map { it.path }.toSet()
                val new = songs.filter { it.path !in held }.distinctBy { it.path }
                if (new.isEmpty()) return@forEach
                state = state.add(there.id, new)
                if (there.id in created) created[there.id] = created.getValue(there.id) + new else added[there.id] = added[there.id].orEmpty() + new
            }
            val known = state.favouriteSongs.map { it.path }.toSet()
            val loved = favourites.filter { it.path !in known }.distinctBy { it.path }
            // After the user's own, so an import never pushes down the songs they hearted in KJB.
            if (loved.isNotEmpty()) state = state.copy(favouriteSongs = state.favouriteSongs + loved)
            return state to ImportUndo(created = created, added = added, favourites = loved)
        }

        /**
         * [state] with what [undo] added taken out and nothing else. Playlist entries carry no ids, so the import's
         * entry of a song is its first copy: the import only adds songs a playlist does not hold, and starts the ones
         * it makes with them. That copy goes; copies the user added since stay where they are. A playlist the import
         * made is deleted when nothing but its songs is left in it, else keeps what the user put in (and its name);
         * its favourites are unfavourited. What is gone already is no matter.
         */
        internal fun unmerge(state: PlaylistsState, undo: ImportUndo): PlaylistsState {
            var back = state
            undo.created.forEach { (id, songs) ->
                val playlist = back.playlist(id) ?: return@forEach
                val left = withoutFirstCopies(playlist.songs, songs)
                back = if (left.isEmpty()) back.delete(id) else back.put(playlist.copy(songs = left))
            }
            undo.added.forEach { (id, songs) ->
                val playlist = back.playlist(id) ?: return@forEach
                back = back.put(playlist.copy(songs = withoutFirstCopies(playlist.songs, songs)))
            }
            return if (undo.favourites.isEmpty()) back else back.favourite(undo.favourites, favourite = false)
        }

        /** [songs] without the first copy of each of [taken] (a song there twice, its first two copies). */
        private fun withoutFirstCopies(songs: List<SongRef>, taken: List<SongRef>): List<SongRef> {
            val left = taken.groupingBy { it.path }.eachCount().toMutableMap()
            return songs.filter { song ->
                val count = left[song.path] ?: 0
                if (count > 0) left[song.path] = count - 1
                count == 0
            }
        }

        /**
         * The plays to add so each song's count reaches the import's, never lowering one: for each song (its plays
         * counted in [log], KJB's own) the difference as counted plays, at its last play in the other app, else at
         * [now], a millisecond apart and never before the first, each at a start its song has no play at yet. At most
         * [MAX_PLAYS_PER_SONG] a song and [budget] in all, a song's whole difference or none of it; a last play after
         * [now] is not believed.
         */
        internal fun raised(log: List<LoggedPlay>, wanted: List<Pair<Song, ImportPlays>>, now: Long, budget: Int = MAX_IMPORTED_PLAYS): List<LoggedPlay> {
            val counted = log.filter { it.counted }.groupingBy { it.song.path }.eachCount()
            val taken = log.groupBy({ it.song.path }, { it.startedAt })
            var left = budget
            return wanted.groupBy { it.first.path }.flatMap { (path, its) ->
                val song = its.first().first
                val count = its.maxOf { it.second.count }.coerceAtMost(MAX_PLAYS_PER_SONG)
                val missing = count - (counted[path] ?: 0)
                // A whole song or none: a count raised only part of the way would be a wrong count.
                if (missing <= 0 || missing > left) return@flatMap emptyList()
                val last = its.mapNotNull { it.second.lastPlayedAt?.takeIf { at -> at in 1..now } }.maxOrNull() ?: now
                val busy = taken[path].orEmpty().toSet()
                val starts = generateSequence(last) { (it - 1).takeIf { at -> at >= 1 } }.filter { it !in busy }.take(missing).toList()
                left -= starts.size
                starts.map { LoggedPlay(song.ref, it, 0, counted = true) }
            }
        }

        /**
         * [book] with an import's [queues] (each its songs on this phone, in order, the one it was at and the second in
         * it) added as saved queues, each by its own name, never the one playing: one whose songs, in that order, a saved
         * queue holds already is not added again. Those it made come back with it, each with its items' uids.
         */
        internal fun mergeQueues(book: QueueBook, queues: List<PlacedQueue>, now: Long): Pair<QueueBook, Map<String, List<Long>>> {
            var state = book
            val made = LinkedHashMap<String, List<Long>>()
            queues.forEach { (name, songs, index, positionMs) ->
                val source = QueueSource(SourceKind.PICKED, QUEUE_KEY, name)
                val paths = songs.map { it.path }.distinct()
                if (paths.isEmpty() || state.saved.mapNotNull(state::queue).any { saved -> saved.items.map { it.song.path } == paths }) return@forEach
                val added = state.newQueue(name, songs, source, now)?.book ?: return@forEach
                val id = added.saved.first()
                // The song it was at, among the queue's songs (each once), at its second; else the first, from its start.
                val at = songs.getOrNull(index)?.path?.let(paths::indexOf) ?: -1
                state = added.copy(queues = added.queues.map { if (it.id == id) it.copy(index = at.coerceAtLeast(0), positionMs = if (at >= 0) positionMs else 0) else it })
                made[id] = state.queue(id)?.items.orEmpty().map { it.uid }
            }
            return state to made
        }

        /**
         * [book] without what an import put in [queues] (each saved queue it made, by the uids of its items): a queue the
         * user has not added to goes, one they added to keeps their songs, and one playing now is theirs and stays.
         */
        internal fun unqueue(book: QueueBook, queues: Map<String, List<Long>>): QueueBook = queues.entries.fold(book) { now, (id, uids) ->
            val queue = now.queue(id)
            val mine = uids.toSet()
            when {
                queue == null || id == now.currentId -> now
                queue.items.all { it.uid in mine } -> now.delete(id)
                else -> now.removeUids(id, mine)
            }
        }

        /**
         * The songs of [left], those no other pass found, each by a file name only one song of the phone has
         * ([SongMatcher.byUniqueName]): never a song of [taken], which other songs of the import found, and never one
         * that songs of two paths both name, as it could be either. Two spellings of one path ([SongMatcher.pathKey]:
         * Unicode form, case, `\`) are one path.
         */
        private fun byName(left: List<ImportSong>, matcher: SongMatcher, taken: Set<String>): Map<ImportSong, Song> {
            val claims = left.mapNotNull { song -> matcher.byUniqueName(song)?.takeIf { it.path !in taken }?.let { song to it } }
            return claims.groupBy { it.second.path }.values
                .filter { same -> same.distinctBy { (song, _) -> (song.path ?: song.relativePath)?.let(SongMatcher::pathKey) }.size == 1 }
                .flatten().toMap()
        }

        /** The key of the list an imported queue came from: its own, so a tap in another list never lands in it. */
        private const val QUEUE_KEY: String = "import"

        /** The same playlist name, case and surrounding spaces aside. */
        private fun sameName(a: String, b: String): Boolean = a.trim().equals(b.trim(), ignoreCase = true)

        /** Songs the same for the preview and the not-found list: by where they are, else by what they are called. */
        private fun key(song: ImportSong): String = song.location.ifEmpty { song.label }
    }
}
