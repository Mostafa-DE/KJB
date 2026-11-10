package app.lyrify.backup

import app.lyrify.audio.EqProfiles
import app.lyrify.data.LedgerEntry
import app.lyrify.data.LedgerState
import app.lyrify.library.CollectionKind
import app.lyrify.library.PlaylistsState
import app.lyrify.library.Song
import app.lyrify.library.SongClue
import app.lyrify.library.SongRef
import app.lyrify.library.SongResolver
import app.lyrify.player.Queue
import app.lyrify.player.QueueBook
import app.lyrify.player.SongState
import app.lyrify.settings.Settings
import app.lyrify.stats.LoggedPlay
import kotlinx.coroutines.CancellationException
import java.util.Locale

/**
 * The stores a backup reads and a restore changes, each through its own API: the app's in AutoBackup.kt
 * ([AppBackupStores]), in memory in tests. A read is null when the store cannot be read; a change is false when it
 * could not be stored, and then nothing in that store changed.
 */
interface BackupStores {
    suspend fun playlists(): PlaylistsState?

    suspend fun updatePlaylists(change: (PlaylistsState) -> PlaylistsState): Boolean

    suspend fun queues(): QueueBook?

    suspend fun updateQueues(change: (QueueBook) -> QueueBook): Boolean

    suspend fun plays(): List<LoggedPlay>?

    /** The play log keeps one play per song and start, so plays already there are not added again. */
    suspend fun addPlays(plays: List<LoggedPlay>): Boolean

    suspend fun songState(): SongState?

    suspend fun updateSongState(change: (SongState) -> SongState): Boolean

    suspend fun equalizer(): EqProfiles?

    suspend fun replaceEqualizer(profiles: EqProfiles): Boolean

    suspend fun ledger(): LedgerState?

    suspend fun updateLedger(change: (LedgerState) -> LedgerState): Boolean

    suspend fun settings(): Settings?

    suspend fun updateSettings(change: (Settings) -> Settings): Boolean
}

/** What every store holds now, for a backup; a store that cannot be read is left out. */
suspend fun BackupStores.snapshot(): BackupSnapshot = BackupSnapshot(playlists(), queues(), plays(), songState(), equalizer(), ledger(), settings())

/** A song a backup names that is not on this phone, as the backup knew it: the summary lists it. */
data class MissingSong(val path: String, val title: String?, val artist: String?)

/** What a restore did: the sections restored and those that failed, in the summary's order. */
data class RestoreResult(val restored: List<Section>, val failed: List<Section>)

/**
 * A backup matched against this phone's library: each song by its path, then by its title, artist and length within 2 s
 * ([SongResolver.BACKUP]), so a backup works on a new phone; a song found neither way (or found twice) is left out of
 * what it was in, and listed ([missing]). Songs take this phone's path and id hint. Every song is looked for once, as
 * the plan is made (off the main thread: a library and a backup of thousands of songs); nothing is written until [apply].
 */
class RestorePlan(val contents: BackupContents, resolver: SongResolver) {
    private val songs: Map<String, BackupSong> = contents.songs.associateBy { it.path }

    /** Each song path the sections name, and the song it is on this phone: by the songs table's tags when the path is not here. */
    private val found: Map<String, Song?> = Section.entries.flatMap(::paths).distinct().associateWith { path ->
        val known = songs[path]
        resolver.find(SongClue(path, title = known?.title, artist = known?.artist, durationMs = known?.durationMs), SongResolver.BACKUP)
    }

    /** Each lyrics choice's song, by its path in the backup: its entry carries its own tags. */
    private val foundLyrics: Map<String, Song?> = contents.lyrics?.entries?.values.orEmpty().associate { entry ->
        entry.path to resolver.find(SongClue(entry.path, title = entry.title, artist = entry.artist, durationMs = entry.durationMs), SongResolver.BACKUP)
    }

    private fun find(path: String): Song? = found[path]

    private fun find(entry: LedgerEntry): Song? = foundLyrics[entry.path]

    private fun ref(ref: SongRef): SongRef? = find(ref.path)?.ref

    /** The songs the [chosen] sections name that are not on this phone, each once, in the summary's order. */
    fun missing(chosen: Set<Section>): List<MissingSong> {
        val missing = LinkedHashMap<String, MissingSong>()
        chosen(chosen).forEach { section ->
            if (section == Section.LYRICS) {
                contents.lyrics?.entries?.values?.filter { find(it) == null }?.forEach { missing.getOrPut(it.path) { MissingSong(it.path, it.title, it.artist) } }
            } else {
                paths(section).filter { find(it) == null }.forEach { path -> missing.getOrPut(path) { songs[path].let { MissingSong(path, it?.title, it?.artist) } } }
            }
        }
        return missing.values.toList()
    }

    /** The song paths [section] names (the lyrics choices aside, which carry their own tags). */
    private fun paths(section: Section): List<String> = when (section) {
        Section.PLAYLISTS -> contents.playlists?.let { state -> state.playlists.flatMap { it.songs } + state.favouriteSongs }.orEmpty().map { it.path }
        Section.QUEUES -> contents.queues?.queues.orEmpty().flatMap { queue -> queue.items.map { it.song.path } }
        Section.PLAYS -> contents.plays.orEmpty().map { it.song.path }
        Section.BOOKMARKS -> contents.bookmarks.orEmpty().map { it.path }
        Section.EQUALIZER, Section.SETTINGS, Section.LYRICS -> emptyList()
    }

    /** The [chosen] sections the backup holds, in the summary's order. */
    private fun chosen(chosen: Set<Section>): List<Section> = contents.sections.filter { it in chosen }

    /**
     * Restores the [chosen] sections the backup holds, one after another, each in one write of its store: settings and
     * the equalizer replace what is there; playlists, queues, plays, bookmarks and lyrics choices are merged in. Every
     * section is matched to this phone before the first write. A section that fails leaves its store as it was, and the
     * ones before it stay restored. [newId] names new playlists. The log gets section names only.
     */
    suspend fun apply(chosen: Set<Section>, stores: BackupStores, newId: () -> String, log: (String) -> Unit = {}): RestoreResult {
        val ready = chosen(chosen).map { it to prepare(it) }
        val restored = mutableListOf<Section>()
        val failed = mutableListOf<Section>()
        ready.forEach { (section, prepared) ->
            val done = try {
                write(prepared, stores, newId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A store's own failure (an IOException DataStore gave up on, SQLite's); its write did not happen.
                log("restore: ${section.key} failed (${e.javaClass.simpleName})")
                false
            }
            if (done) restored += section else failed += section
        }
        log("restore: ${restored.size} sections restored, ${failed.size} failed${if (failed.isEmpty()) "" else ": " + failed.joinToString { it.key }}")
        return RestoreResult(restored, failed)
    }

    /** A section matched to this phone, ready to be written. */
    private sealed interface Prepared {
        data class Playlists(val state: PlaylistsState) : Prepared

        data class Queues(val recent: List<Queue>, val saved: List<Queue>) : Prepared

        data class Plays(val plays: List<LoggedPlay>) : Prepared

        /** Each song's marks with the song they are for on this phone. */
        data class Bookmarks(val marks: List<Pair<Song, BackupMarks>>) : Prepared

        data class Equalizer(val profiles: EqProfiles) : Prepared

        data class Lyrics(val entries: List<LedgerEntry>) : Prepared

        data class AppSettings(val settings: Settings) : Prepared
    }

    private fun prepare(section: Section): Prepared = when (section) {
        Section.PLAYLISTS -> checkNotNull(contents.playlists).let { state ->
            Prepared.Playlists(
                state.copy(
                    playlists = state.playlists.map { playlist -> playlist.copy(songs = playlist.songs.mapNotNull(::ref)) },
                    favouriteSongs = state.favouriteSongs.mapNotNull(::ref).distinctBy { it.path },
                ),
            )
        }
        Section.QUEUES -> checkNotNull(contents.queues).let { book ->
            fun queues(ids: List<String>) = ids.mapNotNull { id -> book.queue(id)?.let(::matched) }
            Prepared.Queues(queues(book.recent), queues(book.saved))
        }
        Section.PLAYS -> Prepared.Plays(checkNotNull(contents.plays).mapNotNull { play -> ref(play.song)?.let { play.copy(song = it) } })
        Section.BOOKMARKS -> Prepared.Bookmarks(checkNotNull(contents.bookmarks).mapNotNull { marks -> find(marks.path)?.let { it to marks } })
        Section.EQUALIZER -> Prepared.Equalizer(checkNotNull(contents.equalizer))
        Section.LYRICS -> Prepared.Lyrics(
            checkNotNull(contents.lyrics).entries.values.mapNotNull { entry -> find(entry)?.let { entry.copy(path = it.path) } }
                .groupBy { it.path }.map { (_, entries) -> entries.maxBy { it.updatedAt } },
        )
        Section.SETTINGS -> Prepared.AppSettings(checkNotNull(contents.settings))
    }

    /** [queue] with its songs on this phone (each once), its place on the same song; null when none is here. */
    private fun matched(queue: Queue): Queue? {
        val items = queue.items.mapNotNull { item -> ref(item.song)?.let { item.copy(song = it) } }.distinctBy { it.song.path }
        if (items.isEmpty()) return null
        val current = queue.current?.uid
        val index = items.indexOfFirst { it.uid == current }
        val kept = items.map { it.uid }.toSet()
        return queue.copy(
            items = items,
            index = index.coerceAtLeast(0),
            positionMs = if (index >= 0) queue.positionMs else 0,
            unshuffled = queue.unshuffled?.filter { it in kept },
        )
    }

    private suspend fun write(prepared: Prepared, stores: BackupStores, newId: () -> String): Boolean = when (prepared) {
        is Prepared.Playlists -> stores.updatePlaylists { mergePlaylists(it, prepared.state, newId) }
        is Prepared.Queues -> stores.updateQueues { mergeQueues(it, prepared.recent, prepared.saved) }
        is Prepared.Plays -> stores.addPlays(prepared.plays)
        is Prepared.Bookmarks -> stores.updateSongState { mergeMarks(it, prepared.marks) }
        is Prepared.Equalizer -> stores.replaceEqualizer(prepared.profiles)
        is Prepared.Lyrics -> stores.updateLedger { mergeLedger(it, prepared.entries) }
        // The phone keeps its own automatic backup and its first-run state.
        is Prepared.AppSettings -> stores.updateSettings { phone -> prepared.settings.copy(backup = phone.backup, nav = prepared.settings.nav.copy(setupDone = phone.nav.setupDone)) }
    }

    companion object {
        /** What a playlist gets when one of its name is there already. */
        const val RESTORED: String = " (restored)"

        /**
         * [now] with the backup's playlists and Favourites added. A playlist already there (the same songs, under its
         * name or its restored name) is not added again; one whose name is taken gets [RESTORED] (then " (2)"...).
         * Favourite songs and collections not there yet go after the phone's own; a favourite playlist follows its
         * playlist's new id.
         */
        internal fun mergePlaylists(now: PlaylistsState, restored: PlaylistsState, newId: () -> String): PlaylistsState {
            var state = now
            val ids = mutableMapOf<String, String>()
            restored.playlists.forEach { playlist ->
                val paths = playlist.songs.map { it.path }
                val there = state.playlists.firstOrNull { it.songs.map(SongRef::path) == paths && sameName(it.name, playlist.name) }
                if (there != null) {
                    ids[playlist.id] = there.id
                    return@forEach
                }
                val taken = state.playlists.map { it.name }
                val name = if (taken.none { it.trim().equals(playlist.name.trim(), ignoreCase = true) }) playlist.name else M3u.uniqueName(playlist.name.trim() + RESTORED, taken)
                val made = playlist.copy(id = newId(), name = name)
                ids[playlist.id] = made.id
                state = state.put(made)
            }
            val known = state.favouriteSongs.map { it.path }.toSet()
            val collections = restored.favouriteCollections.mapNotNull { collection ->
                if (collection.kind == CollectionKind.PLAYLIST) ids[collection.key]?.let { collection.copy(key = it) } else collection
            }
            return state.copy(
                favouriteSongs = state.favouriteSongs + restored.favouriteSongs.filterNot { it.path in known },
                favouriteCollections = (state.favouriteCollections + collections).distinct(),
            )
        }

        /** [name], or what a restore names it ("Road trip (restored)", "Road trip (restored) (2)"), case aside. */
        private fun sameName(there: String, name: String): Boolean {
            val have = there.trim().lowercase(Locale.ROOT)
            val base = name.trim().lowercase(Locale.ROOT)
            return have == base || have.startsWith(base + RESTORED)
        }

        /**
         * [now] with the backup's [recent] and [saved] queues added after its own, with new ids and uids from its
         * counters: Recent up to its cap, Saved all. A queue holding the same songs in the same order as one there is
         * not added again. Nothing plays.
         */
        internal fun mergeQueues(now: QueueBook, recent: List<Queue>, saved: List<Queue>): QueueBook {
            val seen = now.queues.map { queue -> queue.items.map { it.song.path } }.toMutableSet()
            fun fresh(queues: List<Queue>) = queues.filter { queue -> seen.add(queue.items.map { it.song.path }) }
            val toRecent = fresh(recent).take((QueueBook.MAX_RECENT - now.recent.size).coerceAtLeast(0))
            val toSaved = fresh(saved)
            if (toRecent.isEmpty() && toSaved.isEmpty()) return now
            var nextId = now.nextId
            var nextUid = now.nextUid
            val made = (toRecent + toSaved).map { queue ->
                val uids = queue.items.associate { it.uid to nextUid++ }
                queue.copy(id = "q${nextId++}", items = queue.items.map { it.copy(uid = uids.getValue(it.uid)) }, unshuffled = queue.unshuffled?.mapNotNull(uids::get))
            }
            return now.copy(
                queues = now.queues + made,
                recent = now.recent + made.take(toRecent.size).map { it.id },
                saved = now.saved + made.drop(toRecent.size).map { it.id },
                nextId = nextId,
                nextUid = nextUid,
            )
        }

        /**
         * [now] with the backup's places for songs that have none, and its bookmarks not there yet (the same spot and
         * note), each [song] as the song state knows it, through the song state's own API ([SongState.withPlace],
         * [SongState.withBookmark]), which gives each bookmark its new id and keeps the newest places.
         */
        internal fun mergeMarks(now: SongState, marks: List<Pair<Song, BackupMarks>>): SongState = marks.fold(now) { before, (song, kept) ->
            val key = song.path
            val placed = kept.placeMs?.takeIf { key !in before.places }?.let { before.withPlace(key, it) } ?: before
            kept.bookmarks.fold(placed) { state, mark ->
                if (state.bookmarksOf(key).any { it.positionMs == mark.positionMs && it.note == mark.note }) state else state.withBookmark(key, mark.positionMs, mark.note).first
            }
        }

        /** [now] with the backup's choices for songs it knows nothing about: a choice the phone has, the user's own above all, stays. */
        internal fun mergeLedger(now: LedgerState, restored: List<LedgerEntry>): LedgerState {
            val added = restored.filter { it.path !in now.entries && it.path !in now.unknownEntries }
            return if (added.isEmpty()) now else now.copy(entries = now.entries + added.associateBy { it.path })
        }
    }
}
