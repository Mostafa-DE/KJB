package app.lyrify.backup

import app.lyrify.library.Playlist
import app.lyrify.library.PlaylistsState
import app.lyrify.library.Song
import app.lyrify.library.SongRef
import app.lyrify.player.QueueBook
import app.lyrify.player.QueueSource
import app.lyrify.player.SourceKind
import app.lyrify.stats.LoggedPlay

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
}

/**
 * What one import added, so one Undo takes all of it back: the playlists it made, the songs it added at the end of
 * playlists already there, the songs it made favourites, and the plays it added.
 */
data class ImportUndo(
    val created: List<String> = emptyList(),
    val added: Map<String, List<SongRef>> = emptyMap(),
    val favourites: List<SongRef> = emptyList(),
    val plays: List<LoggedPlay> = emptyList(),
    /** The saved queues it made, by id. */
    val queues: List<String> = emptyList(),
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
) {
    /** "Imported 12 playlists". */
    val playlists: Int get() = created + extended

    /** False when the phone already had all of it. */
    val changed: Boolean get() = !undo.isEmpty
}

/**
 * An import matched against this phone's songs ([phone], the library's every song) by [SongMatcher.find], each song
 * once, before anything is written: the preview's counts and the songs not found come from it, and [apply] writes it.
 */
class ImportPlan(val bundle: ImportBundle, phone: List<Song>) {
    /** Each song the import names, matched once: the song it is on this phone, when found. */
    private val found: Map<ImportSong, Song> = SongMatcher(phone).let { matcher ->
        songs(ImportKind.entries.toSet()).distinct().mapNotNull { song -> matcher.find(song)?.let { song to it } }.toMap()
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
     * then the queues ([mergeQueues], named [queueName]), then the plays; when a later write fails, the earlier ones are
     * taken back. Never wipes: see [merge] and [raised]. [now] stamps new playlists and queues and the plays of songs
     * whose last play is not known; [untitled] names a playlist without a name; [reserved] are the names of KJB's own
     * lists ("Favourites"), which a playlist does not take. Null when nothing could be written. The log gets counts
     * and exception class names only.
     */
    suspend fun apply(
        chosen: Set<ImportKind>,
        stores: ImportStores,
        newId: () -> String,
        now: Long,
        untitled: String,
        reserved: Set<String> = emptySet(),
        log: (String) -> Unit = {},
        queueName: String = untitled,
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
        var merged: ImportUndo? = null
        if (!stores.updatePlaylists { state -> merge(state, playlists, favourites, newId, now, reserved).also { merged = it.second }.first }) {
            log("import: playlists not written, nothing changed")
            return null
        }
        var undo = (merged ?: ImportUndo()).copy(plays = plays)
        val queues = if (ImportKind.QUEUES in chosen) bundle.queues.map(::onPhone) else emptyList()
        if (queues.isNotEmpty()) {
            var made = emptyList<String>()
            if (!stores.updateQueues { book -> mergeQueues(book, queues, queueName, now).also { made = it.second }.first }) {
                val back = stores.updatePlaylists { unmerge(it, undo) }
                log("import: queues not written, playlists ${if (back) "taken back" else "not taken back"}")
                return null
            }
            undo = undo.copy(queues = made)
        }
        if (plays.isNotEmpty() && !stores.addPlays(plays)) {
            val back = stores.updatePlaylists { unmerge(it, undo) } && (undo.queues.isEmpty() || stores.updateQueues { unqueue(it, undo.queues) })
            log("import: plays not written, playlists and queues ${if (back) "taken back" else "not taken back"}")
            return null
        }
        val outcome = ImportOutcome(
            created = undo.created.size,
            extended = undo.added.size,
            songsAdded = undo.added.values.sumOf { it.size },
            favouritesAdded = undo.favourites.size,
            countsRaised = plays.distinctBy { it.song.path }.size,
            undo = undo,
            queuesAdded = undo.queues.size,
        )
        log("import: ${outcome.created} playlists made, ${outcome.extended} added to, ${outcome.favouritesAdded} favourites, ${outcome.countsRaised} counts raised, ${outcome.queuesAdded} queues")
        return outcome
    }

    /** [queue]'s songs on this phone, in order, the index among them of the one it was at (-1 when that one is not here), and the second in it. */
    private fun onPhone(queue: ImportQueue): Triple<List<SongRef>, Int, Long> {
        val here = queue.songs.mapIndexedNotNull { at, song -> found[song]?.let { at to it.ref } }
        return Triple(here.map { it.second }, here.indexOfFirst { it.first == queue.index }, queue.positionMs)
    }

    companion object {
        /** An import adds at most this many plays to one song, whatever count a file claims. */
        const val MAX_PLAYS_PER_SONG: Int = 10_000

        /**
         * Takes back everything [undo] lists, each store in one write: the queues, then the plays, then the playlists
         * and favourites last. The first two can be taken back again without harm, so an Undo that fails part way can
         * be tried again; the playlists, which take one copy of each added song off the end, change only once, after
         * the rest, and when they cannot be written the plays are put back. False when it could not.
         */
        suspend fun undo(undo: ImportUndo, stores: ImportStores, log: (String) -> Unit = {}): Boolean {
            if (undo.queues.isNotEmpty() && !stores.updateQueues { unqueue(it, undo.queues) }) {
                log("import undo: queues not written, nothing changed")
                return false
            }
            if (undo.plays.isNotEmpty() && !stores.removePlays(undo.plays)) {
                log("import undo: plays not removed, ${undo.queues.size} queues taken back")
                return false
            }
            if (!stores.updatePlaylists { unmerge(it, undo) }) {
                val back = undo.plays.isEmpty() || stores.addPlays(undo.plays)
                log("import undo: playlists not written, plays ${if (back) "put back" else "not put back"}")
                return false
            }
            log("import undo: ${undo.created.size} playlists, ${undo.added.size} added to, ${undo.favourites.size} favourites, ${undo.plays.size} plays, ${undo.queues.size} queues taken back")
            return true
        }

        /**
         * [now] with an import's [playlists] (each a name and its songs on this phone, in order) and [favourites] added,
         * never wiping anything: a name not there makes a playlist; a playlist of the same name (case aside) gets only
         * the songs it does not hold yet, each once, at its end in the import's order, and one that would not change is
         * left as it is; favourites are a union. A name of [reserved] gets " (2)". What it added comes back with it.
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
            val created = mutableListOf<String>()
            val added = LinkedHashMap<String, List<SongRef>>()
            playlists.forEach { (named, songs) ->
                if (songs.isEmpty()) return@forEach
                val name = named.trim().let { if (reserved.any { taken -> sameName(taken, it) }) M3u.uniqueName(it, reserved) else it }
                val there = state.playlists.firstOrNull { sameName(it.name, name) }
                if (there == null) {
                    val made = Playlist(newId(), name, songs, at)
                    created += made.id
                    state = state.put(made)
                    return@forEach
                }
                val held = there.songs.map { it.path }.toSet()
                val new = songs.filter { it.path !in held }.distinctBy { it.path }
                if (new.isEmpty()) return@forEach
                state = state.add(there.id, new)
                if (there.id !in created) added[there.id] = added[there.id].orEmpty() + new
            }
            val known = state.favouriteSongs.map { it.path }.toSet()
            val loved = favourites.filter { it.path !in known }.distinctBy { it.path }
            if (loved.isNotEmpty()) state = state.favourite(loved, favourite = true)
            return state to ImportUndo(created = created, added = added, favourites = loved)
        }

        /**
         * [state] with what [undo] added taken out: its playlists deleted, one copy of each song it added taken off the
         * end of its playlist (the user's own copies stay), its favourites unfavourited. What is gone already is no matter.
         */
        internal fun unmerge(state: PlaylistsState, undo: ImportUndo): PlaylistsState {
            var back = undo.created.fold(state) { now, id -> now.delete(id) }
            undo.added.forEach { (id, songs) ->
                val playlist = back.playlist(id) ?: return@forEach
                val left = songs.groupingBy { it.path }.eachCount().toMutableMap()
                val positions = playlist.songs.indices.reversed().filter { at ->
                    val path = playlist.songs[at].path
                    val count = left[path] ?: 0
                    if (count > 0) left[path] = count - 1
                    count > 0
                }.toSet()
                back = back.removeAt(id, positions)
            }
            return if (undo.favourites.isEmpty()) back else back.favourite(undo.favourites, favourite = false)
        }

        /**
         * The plays to add so each song's count reaches the import's, never lowering one: for each song (its plays
         * counted in [log], KJB's own) the difference as counted plays, at its last play in the other app, else at
         * [now], a millisecond apart, each at a start its song has no play at yet. At most [MAX_PLAYS_PER_SONG] a song;
         * a last play after [now] is not believed.
         */
        internal fun raised(log: List<LoggedPlay>, wanted: List<Pair<Song, ImportPlays>>, now: Long): List<LoggedPlay> {
            val counted = log.filter { it.counted }.groupingBy { it.song.path }.eachCount()
            val taken = log.groupBy({ it.song.path }, { it.startedAt })
            return wanted.groupBy { it.first.path }.flatMap { (path, its) ->
                val song = its.first().first
                val count = its.maxOf { it.second.count }.coerceAtMost(MAX_PLAYS_PER_SONG)
                val missing = count - (counted[path] ?: 0)
                if (missing <= 0) return@flatMap emptyList()
                val last = its.mapNotNull { it.second.lastPlayedAt?.takeIf { at -> at in 1..now } }.maxOrNull() ?: now
                val busy = taken[path].orEmpty().toSet()
                generateSequence(last) { it - 1 }.filter { it !in busy }.take(missing).map { LoggedPlay(song.ref, it, 0, counted = true) }.toList()
            }
        }

        /**
         * [book] with an import's [queues] (each its songs on this phone, in order, the one it was at and the second in
         * it) added as saved queues named [name], never the one playing: one whose songs, in that order, a saved queue
         * holds already is not added again. The ids of those it made come back with it.
         */
        internal fun mergeQueues(book: QueueBook, queues: List<Triple<List<SongRef>, Int, Long>>, name: String, now: Long): Pair<QueueBook, List<String>> {
            var state = book
            val made = mutableListOf<String>()
            val source = QueueSource(SourceKind.PICKED, QUEUE_KEY, name)
            queues.forEach { (songs, index, positionMs) ->
                val paths = songs.map { it.path }.distinct()
                if (paths.isEmpty() || state.saved.mapNotNull(state::queue).any { saved -> saved.items.map { it.song.path } == paths }) return@forEach
                val added = state.newQueue(name, songs, source, now)?.book ?: return@forEach
                val id = added.saved.first()
                // The song it was at, among the queue's songs (each once), at its second; else the first, from its start.
                val at = songs.getOrNull(index)?.path?.let(paths::indexOf) ?: -1
                state = added.copy(queues = added.queues.map { if (it.id == id) it.copy(index = at.coerceAtLeast(0), positionMs = if (at >= 0) positionMs else 0) else it })
                made += id
            }
            return state to made
        }

        /** [book] without the saved queues [ids] an import made (one playing now stays). */
        internal fun unqueue(book: QueueBook, ids: List<String>): QueueBook = ids.fold(book) { now, id -> now.delete(id) }

        /** The key of the list an imported queue came from: its own, so a tap in another list never lands in it. */
        private const val QUEUE_KEY: String = "import"

        /** The same playlist name, case and surrounding spaces aside. */
        private fun sameName(a: String, b: String): Boolean = a.trim().equals(b.trim(), ignoreCase = true)

        /** Songs the same for the preview and the not-found list: by where they are, else by what they are called. */
        private fun key(song: ImportSong): String = song.location.ifEmpty { song.label }
    }
}
