package app.lyrify.backup

import app.lyrify.library.Playlist
import app.lyrify.library.PlaylistsState
import app.lyrify.library.Song
import app.lyrify.library.SongRef
import app.lyrify.stats.LoggedPlay

/** What an import can bring, each with its check box in the preview, in its order. */
enum class ImportKind { PLAYLISTS, FAVOURITES, PLAYS }

/**
 * The stores an import changes, each through its own API: the app's in ImportSources.kt ([AppImportStores]), in memory
 * in tests. A change is false when it could not be stored, and then nothing in that store changed.
 */
interface ImportStores {
    suspend fun updatePlaylists(change: (PlaylistsState) -> PlaylistsState): Boolean

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
) {
    val isEmpty: Boolean get() = created.isEmpty() && added.isEmpty() && favourites.isEmpty() && plays.isEmpty()
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
    ).filter { it.second > 0 }.toMap()

    /** The songs the [chosen] kinds name that are not on this phone, each once, in their order. */
    fun missing(chosen: Set<ImportKind>): List<ImportSong> = songs(chosen).filter { it !in found }.distinctBy(::key)

    /** The songs the [chosen] kinds name, in the preview's order, repeats and all. */
    private fun songs(chosen: Set<ImportKind>): List<ImportSong> = buildList {
        if (ImportKind.PLAYLISTS in chosen) bundle.playlists.forEach { addAll(it.songs) }
        if (ImportKind.FAVOURITES in chosen) addAll(bundle.favourites)
        if (ImportKind.PLAYS in chosen) bundle.plays.forEach { add(it.song) }
    }

    /**
     * Writes the [chosen] kinds after matching, all or nothing: playlists and favourites in one write of their store,
     * then the plays; when the plays fail, the playlists are taken back. Never wipes: see [merge] and [raised]. [now]
     * stamps new playlists and the plays of songs whose last play is not known; [untitled] names a playlist without
     * a name; [reserved] are the names of KJB's own lists ("Favourites"), which a playlist does not take. Null when
     * nothing could be written. The log gets counts and exception class names only.
     */
    suspend fun apply(
        chosen: Set<ImportKind>,
        stores: ImportStores,
        newId: () -> String,
        now: Long,
        untitled: String,
        reserved: Set<String> = emptySet(),
        log: (String) -> Unit = {},
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
        val undo = (merged ?: ImportUndo()).copy(plays = plays)
        if (plays.isNotEmpty() && !stores.addPlays(plays)) {
            val back = stores.updatePlaylists { unmerge(it, undo) }
            log("import: plays not written, playlists ${if (back) "taken back" else "not taken back"}")
            return null
        }
        val outcome = ImportOutcome(
            created = undo.created.size,
            extended = undo.added.size,
            songsAdded = undo.added.values.sumOf { it.size },
            favouritesAdded = undo.favourites.size,
            countsRaised = plays.distinctBy { it.song.path }.size,
            undo = undo,
        )
        log("import: ${outcome.created} playlists made, ${outcome.extended} added to, ${outcome.favouritesAdded} favourites, ${outcome.countsRaised} counts raised")
        return outcome
    }

    companion object {
        /** An import adds at most this many plays to one song, whatever count a file claims. */
        const val MAX_PLAYS_PER_SONG: Int = 10_000

        /**
         * Takes back everything [undo] lists, the plays then the playlists and favourites, each in one write; when the
         * playlists cannot be written the plays are put back. False when it could not.
         */
        suspend fun undo(undo: ImportUndo, stores: ImportStores, log: (String) -> Unit = {}): Boolean {
            if (undo.plays.isNotEmpty() && !stores.removePlays(undo.plays)) {
                log("import undo: plays not removed, nothing changed")
                return false
            }
            if (!stores.updatePlaylists { unmerge(it, undo) }) {
                val back = undo.plays.isEmpty() || stores.addPlays(undo.plays)
                log("import undo: playlists not written, plays ${if (back) "put back" else "not put back"}")
                return false
            }
            log("import undo: ${undo.created.size} playlists, ${undo.added.size} added to, ${undo.favourites.size} favourites, ${undo.plays.size} plays taken back")
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

        /** The same playlist name, case and surrounding spaces aside. */
        private fun sameName(a: String, b: String): Boolean = a.trim().equals(b.trim(), ignoreCase = true)

        /** Songs the same for the preview and the not-found list: by where they are, else by what they are called. */
        private fun key(song: ImportSong): String = song.location.ifEmpty { song.label }
    }
}
