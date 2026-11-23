package app.lyrify.backup

import app.lyrify.audio.EqProfiles
import app.lyrify.books.BookEntry
import app.lyrify.books.BookMarks
import app.lyrify.books.BookPlace
import app.lyrify.books.BookState
import app.lyrify.books.folderBookAlbum
import app.lyrify.books.folderBookId
import app.lyrify.data.LedgerEntry
import app.lyrify.data.LedgerState
import app.lyrify.downloads.DownloadState
import app.lyrify.downloads.DownloadsMemory
import app.lyrify.library.CollectionKind
import app.lyrify.library.PlaylistsState
import app.lyrify.library.Song
import app.lyrify.library.SongRef
import app.lyrify.player.AbPart
import app.lyrify.player.Bookmark
import app.lyrify.player.Queue
import app.lyrify.player.QueueBook
import app.lyrify.player.Repeat
import app.lyrify.player.SongState
import app.lyrify.player.Speed
import app.lyrify.settings.HeldSong
import app.lyrify.settings.Settings
import app.lyrify.settings.SettingsJson
import app.lyrify.stats.Interaction
import app.lyrify.stats.InteractionKind
import app.lyrify.stats.LoggedPlay
import app.lyrify.stats.Target
import app.lyrify.stats.TargetKind
import app.lyrify.tags.AlbumRecord
import app.lyrify.tags.CoversTagsMemory
import app.lyrify.tags.FileMark
import app.lyrify.tags.NamePair
import app.lyrify.tags.Settled
import app.lyrify.tags.SongProblem
import app.lyrify.tags.albumId
import app.lyrify.tones.SavedTone
import app.lyrify.tones.TonesState
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

    /** The interaction log, kept beside the plays. */
    suspend fun interactions(): List<Interaction>?

    /**
     * [plays] and [interactions] added in one go: the play log keeps one play per song and start, and one interaction
     * per time and kind, so those already there are not added again.
     */
    suspend fun addPlays(plays: List<LoggedPlay>, interactions: List<Interaction> = emptyList()): Boolean

    suspend fun songState(): SongState?

    suspend fun updateSongState(change: (SongState) -> SongState): Boolean

    suspend fun equalizer(): EqProfiles?

    suspend fun replaceEqualizer(profiles: EqProfiles): Boolean

    suspend fun ledger(): LedgerState?

    suspend fun updateLedger(change: (LedgerState) -> LedgerState): Boolean

    suspend fun settings(): Settings?

    suspend fun updateSettings(change: (Settings) -> Settings): Boolean

    /** Repeat, kept with the queue playing. */
    suspend fun repeat(): Repeat?

    /** Sets Repeat and keeps the queue playing at its place with it. */
    suspend fun replaceRepeat(repeat: Repeat): Boolean

    /** The AI lyrics switch. */
    suspend fun aiLyrics(): Boolean?

    /** Turns the AI lyrics on (its models are downloaded again) or off. */
    suspend fun replaceAiLyrics(on: Boolean): Boolean

    suspend fun tones(): TonesState?

    /** [tones] put in "Your tones", each by its file's MediaStore id on this phone. */
    suspend fun addTones(tones: List<SavedTone>): Boolean

    /** The tone file [fileName] of [size] bytes in the folder [folder] ("Ringtones/") on this phone: its id; null unless exactly one. */
    suspend fun findTone(folder: String, fileName: String, size: Long): Long?

    suspend fun duplicates(): DuplicateChoices?

    /** [choices] added to Duplicates' own; groups marked "Not duplicates" leave the last scan's results. */
    suspend fun addDuplicates(choices: DuplicateChoices): Boolean

    suspend fun coversTags(): CoversTagsMemory?

    /** [albums] settled in Covers & tags, unless one of their ids is there; the flags set when either says so. */
    suspend fun addSettledAlbums(albums: List<AlbumRecord>, checkedOnce: Boolean, noted: Boolean): Boolean

    suspend fun keptApart(): Set<NamePair>?

    suspend fun addKeptApart(pairs: Set<NamePair>): Boolean

    /** The recent searches, the newest first. */
    suspend fun searches(): List<String>?

    /** [searches], the newest first, added to the recent ones that are not there yet. */
    suspend fun addSearches(searches: List<String>): Boolean

    /** The file at [path] as it is now, its size and modified time; null when it is not a file. */
    suspend fun fileMark(path: String): FileMark?

    /** The books' places and speeds, and the user's "Mark as book" and "Not a book" (`books.json`). */
    suspend fun books(): BookState?

    suspend fun updateBooks(change: (BookState) -> BookState): Boolean

    /** The book log's plays (Insights' books). */
    suspend fun bookPlays(): List<LoggedPlay>?

    /** The book log keeps one play per book file and start, as the play log does. */
    suspend fun addBookPlays(plays: List<LoggedPlay>): Boolean

    /** Settings > Downloads and the Downloads page's list (`downloads.json`). */
    suspend fun downloads(): DownloadsMemory?

    suspend fun updateDownloads(change: (DownloadsMemory) -> DownloadsMemory): Boolean
}

/** What every store holds now, for a backup; a store that cannot be read is left out. */
suspend fun BackupStores.snapshot(): BackupSnapshot = BackupSnapshot(
    playlists(), queues(), plays(), songState(), equalizer(), ledger(), settings(),
    repeat = repeat(), aiLyrics = aiLyrics(), tones = tones(), duplicates = duplicates(), coversTags = coversTags(),
    keptApart = keptApart(), searches = searches(), books = books(), interactions = interactions(), bookPlays = bookPlays(), downloads = downloads(),
)


/** A song a backup names that is not on this phone, as the backup knew it: the summary lists it. */
data class MissingSong(val path: String, val title: String?, val artist: String?)

/** A song state with a backup's places and bookmarks in, and how many of each the caps left out. */
internal data class MergedMarks(val state: SongState, val placesLeftOut: Int, val bookmarksLeftOut: Int)

/**
 * What a restore did: the sections restored and those that failed, in the summary's order, and the places and bookmarks
 * left out because the phone holds as many as it keeps (a restore never pushes out the phone's own). For the summary
 * after it: how much each section brought back ([counts]: playlists, queues, plays, bookmarks, equalizer profiles,
 * lyrics choices, tones, "Not duplicates" groups, Covers & tags albums, searches, books, downloads; Settings has none),
 * the songs of the backup not on this phone ([notFound]), the tones whose files are not on it, and the devices to pick
 * again for Start when connected.
 */
data class RestoreResult(
    val restored: List<Section>,
    val failed: List<Section>,
    val placesLeftOut: Int = 0,
    val bookmarksLeftOut: Int = 0,
    val counts: Map<Section, Int> = emptyMap(),
    val notFound: Int = 0,
    val tonesNotFound: Int = 0,
    val pickAgain: List<String> = emptyList(),
)

/**
 * A backup matched against this phone's songs ([phone], the library's every song): each song the backup names is found
 * once, all together, by [SongMatcher] (the same path, then the same path below the storage root, then the same tags
 * and length; never a guess), so a backup works on a new phone where every MediaStore id and folder differs. A song not
 * found is left out of what it was in, and listed ([missing]). Songs take this phone's path and id hint. The matching is
 * done as the plan is made (off the main thread: a library and a backup of thousands of songs); nothing is written
 * until [apply].
 */
class RestorePlan(val contents: BackupContents, phone: List<Song>) {
    /** What the backup knows of each song: the songs section, else a lyrics choice's own tags. */
    private val known: Map<String, BackupSong> = contents.lyrics?.entries?.values.orEmpty()
        .associate { it.path to BackupSong(it.path, it.title, it.artist, it.album, it.durationMs) } + contents.songs.associateBy { it.path }

    /**
     * Each song the backup names, by its path there, and the song it is on this phone. Hidden songs, the songs held out
     * of suggestions, those of the interaction log and the songs downloaded are matched too, but one not here is no song
     * missing: it was not in any list.
     */
    private val found: Map<String, Song> = SongMatcher(phone).match(
        Section.entries.flatMap(::paths) + contents.tones.orEmpty().map { it.songPath } + contents.settings?.library?.hiddenSongs.orEmpty().map { it.path } +
            contents.settings?.suggestions?.let { it.never + it.notNow }.orEmpty().map { it.song.path } +
            contents.interactions.mapNotNull { interaction -> interaction.target?.takeIf { it.kind == TargetKind.SONG }?.key } +
            contents.downloads?.items.orEmpty().mapNotNull { (it.state as? DownloadState.Done)?.path },
        known,
    )

    private fun ref(ref: SongRef): SongRef? = found[ref.path]?.ref

    /** The songs the [chosen] sections name that are not on this phone, each once, in the summary's order. */
    fun missing(chosen: Set<Section>): List<MissingSong> =
        chosen(chosen).flatMap(::paths).distinct().filter { it !in found }.map { path -> known[path].let { MissingSong(path, it?.title, it?.artist) } }

    /** The song paths [section] names and cannot restore without; a tone is its own file, so it names none. */
    private fun paths(section: Section): List<String> = when (section) {
        Section.PLAYLISTS -> contents.playlists?.let { state -> state.playlists.flatMap { it.songs } + state.favouriteSongs }.orEmpty().map { it.path }
        Section.QUEUES -> contents.queues?.queues.orEmpty().flatMap { queue -> queue.items.map { it.song.path } }
        Section.PLAYS -> contents.plays.orEmpty().map { it.song.path }
        Section.BOOK_PLAYS -> contents.bookPlays.orEmpty().map { it.song.path }
        Section.BOOKMARKS -> contents.bookmarks.orEmpty().map { it.path } + listOfNotNull(contents.part?.path)
        Section.LYRICS -> contents.lyrics?.entries?.keys.orEmpty().toList()
        Section.DUPLICATES -> contents.duplicates?.let { it.notDuplicates + it.reviewed }.orEmpty().flatten()
        Section.COVERS -> contents.covers?.albums.orEmpty().flatMap { it.paths }
        Section.BOOKS -> contents.books?.let { state -> state.books.values.flatMap { it.parts + listOfNotNull(it.partPath) } + state.marks.songs + state.marks.notSongs }.orEmpty()
        Section.EQUALIZER, Section.SETTINGS, Section.TONES, Section.NAMES, Section.SEARCHES, Section.DOWNLOADS -> emptyList()
    }

    /** The [chosen] sections the backup holds, in the summary's order. */
    private fun chosen(chosen: Set<Section>): List<Section> = contents.sections.filter { it in chosen }

    /**
     * Restores the [chosen] sections the backup holds, one after another, each in one write of its store (and Repeat,
     * the AI switch beside theirs, all or nothing): settings, Repeat and the equalizer replace what is there;
     * the rest is merged in, and on a phone with nothing yet the result is the old phone's. Every section is matched to
     * this phone before the first write. A section that fails leaves its store as it was, and the ones before it stay
     * restored. [newId] names new playlists. The log gets section names and counts only.
     */
    suspend fun apply(chosen: Set<Section>, stores: BackupStores, newId: () -> String, log: (String) -> Unit = {}): RestoreResult {
        val ready = chosen(chosen).map { it to prepare(it) }
        val restored = mutableListOf<Section>()
        val failed = mutableListOf<Section>()
        val counts = LinkedHashMap<Section, Int>()
        val tally = Tally()
        ready.forEach { (section, prepared) ->
            val count = try {
                write(prepared, stores, newId, tally)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A store's own failure (an IOException DataStore gave up on, SQLite's); its write did not happen.
                log("restore: ${section.key} failed (${e.javaClass.simpleName})")
                null
            }
            if (count == null) {
                failed += section
            } else {
                restored += section
                counts[section] = count
            }
        }
        val notFound = missing(chosen).size
        val placesLeftOut = tally.marks?.placesLeftOut ?: 0
        val bookmarksLeftOut = tally.marks?.bookmarksLeftOut ?: 0
        log("restore: ${restored.size} sections restored, ${failed.size} failed${if (failed.isEmpty()) "" else ": " + failed.joinToString { it.key }}; $notFound songs not found")
        if (placesLeftOut + bookmarksLeftOut > 0) log("restore: $placesLeftOut places and $bookmarksLeftOut bookmarks left out at the limit")
        if (tally.tonesNotFound > 0) log("restore: ${tally.tonesNotFound} tones without their files")
        return RestoreResult(restored, failed, placesLeftOut, bookmarksLeftOut, counts, notFound, tally.tonesNotFound, tally.pickAgain)
    }

    /** What the writes tell the summary besides their counts. */
    private class Tally {
        var marks: MergedMarks? = null
        var tonesNotFound: Int = 0
        var pickAgain: List<String> = emptyList()
    }

    /** A section matched to this phone, ready to be written. */
    private sealed interface Prepared {
        data class Playlists(val state: PlaylistsState) : Prepared

        /** The queues whose songs are here: the one playing, Recent and Saved, with the backup's counters; and Repeat. */
        data class Queues(val book: QueueBook, val repeat: Repeat?) : Prepared

        /** The plays whose songs are here, on them: the play log's with the interactions ([interactionsHere]), or the book log's ([books]). */
        data class Plays(val plays: List<LoggedPlay>, val interactions: List<Interaction> = emptyList(), val books: Boolean = false) : Prepared

        /** Each song's marks with the song they are for on this phone; the usual speed; the part repeating, on its song. */
        data class Bookmarks(val marks: List<Pair<Song, BackupMarks>>, val usual: Speed?, val part: Pair<Song, AbPart>?) : Prepared

        data class Equalizer(val profiles: EqProfiles) : Prepared

        data class Lyrics(val entries: List<LedgerEntry>) : Prepared

        /**
         * The settings as the backup stores them, to be read over the phone's own; the AI switch; the devices' names; the
         * hidden songs and those held out of suggestions ([never], [notNow]) found on this phone, by their paths here.
         */
        data class AppSettings(
            val stored: String,
            val ai: Boolean?,
            val connectNames: List<String>,
            val hidden: List<SongRef>,
            val never: List<HeldSong>,
            val notNow: List<HeldSong>,
        ) : Prepared

        /** Each tone whose song is here, on its path here, and how many are not ([songsMissing]). */
        data class Tones(val tones: List<BackupTone>, val songsMissing: Int) : Prepared

        data class Duplicates(val choices: DuplicateChoices) : Prepared

        /** Each album whose songs are all here, with them. */
        data class Covers(val albums: List<Pair<BackupAlbum, List<Song>>>, val checkedOnce: Boolean, val noted: Boolean) : Prepared

        data class Names(val pairs: Set<NamePair>) : Prepared

        data class Searches(val searches: List<String>) : Prepared

        /** The books whose files are here, on them, and the marks of the songs here and of every folder. */
        data class Books(val state: BookState) : Prepared

        /** Settings > Downloads, and the downloads with each song saved on its file here when it is found. */
        data class Downloads(val memory: DownloadsMemory) : Prepared
    }

    private fun prepare(section: Section): Prepared = when (section) {
        Section.PLAYLISTS -> checkNotNull(contents.playlists).let { state ->
            Prepared.Playlists(
                state.copy(
                    playlists = state.playlists.map { playlist -> playlist.copy(songs = playlist.songs.mapNotNull(::ref)) },
                    favouriteSongs = state.favouriteSongs.mapNotNull(::ref).distinctBy { it.path },
                    // Each date with its song on this phone; two that are one song here, the earlier.
                    favouriteAdded = state.favouriteAdded.entries.mapNotNull { (path, at) -> ref(SongRef(path))?.let { it.path to at } }
                        .groupBy({ it.first }, { it.second }).mapValues { (_, dates) -> dates.min() },
                ),
            )
        }
        Section.QUEUES -> checkNotNull(contents.queues).let { book ->
            val queues = book.queues.mapNotNull(::matched)
            val ids = queues.map { it.id }.toSet()
            Prepared.Queues(book.copy(queues = queues, currentId = book.currentId?.takeIf { it in ids }, recent = book.recent.filter { it in ids }, saved = book.saved.filter { it in ids }), contents.repeat)
        }
        Section.PLAYS -> Prepared.Plays(here(checkNotNull(contents.plays)), interactionsHere())
        Section.BOOKMARKS -> Prepared.Bookmarks(
            checkNotNull(contents.bookmarks).mapNotNull { marks -> found[marks.path]?.let { it to marks } },
            contents.usualSpeed,
            contents.part?.let { part -> found[part.path]?.let { it to part } },
        )
        Section.EQUALIZER -> Prepared.Equalizer(checkNotNull(contents.equalizer))
        Section.LYRICS -> Prepared.Lyrics(
            checkNotNull(contents.lyrics).entries.values.mapNotNull { entry -> found[entry.path]?.let { entry.copy(path = it.path) } }
                .groupBy { it.path }.map { (_, entries) -> entries.maxBy { it.updatedAt } },
        )
        Section.SETTINGS -> Prepared.AppSettings(
            contents.settingsStored ?: SettingsJson.encode(checkNotNull(contents.settings)),
            contents.aiLyrics,
            contents.connectNames,
            contents.settings?.library?.hiddenSongs.orEmpty().mapNotNull { found[it.path]?.ref },
            contents.settings?.suggestions?.never.orEmpty().mapNotNull { held -> found[held.song.path]?.let { held.copy(song = it.ref) } },
            contents.settings?.suggestions?.notNow.orEmpty().mapNotNull { held -> found[held.song.path]?.let { held.copy(song = it.ref) } },
        )
        // A tone keeps the song it was cut from, to open it again: one whose song is not here is left out, never kept
        // on a path of the old phone.
        Section.TONES -> checkNotNull(contents.tones).let { tones ->
            val here = tones.mapNotNull { tone -> found[tone.songPath]?.let { tone.copy(songPath = it.path) } }
            Prepared.Tones(here, tones.size - here.size)
        }
        Section.DUPLICATES -> checkNotNull(contents.duplicates).let { choices ->
            // A group by this phone's ids, as Duplicates keys it; one with a song not here is no group here.
            fun keys(groups: List<List<String>>) = groups.mapNotNull { paths -> paths.map { found[it]?.ref?.mediaStoreId ?: return@mapNotNull null }.sorted().joinToString(",") }.toSet()
            Prepared.Duplicates(DuplicateChoices(keys(choices.notDuplicates), keys(choices.reviewed)))
        }
        Section.COVERS -> checkNotNull(contents.covers).let { covers ->
            val albums = covers.albums.mapNotNull { album -> album.paths.map { found[it] ?: return@mapNotNull null }.let { album to it } }
            Prepared.Covers(albums, covers.checkedOnce, covers.noted)
        }
        Section.NAMES -> Prepared.Names(checkNotNull(contents.names))
        Section.SEARCHES -> Prepared.Searches(checkNotNull(contents.searches))
        Section.BOOKS -> checkNotNull(contents.books).let { state ->
            fun here(paths: Set<String>) = paths.mapNotNullTo(LinkedHashSet()) { found[it]?.path }
            // A folder mark names the folder below its volume, the same on this phone.
            val marks = state.marks.copy(songs = here(state.marks.songs), notSongs = here(state.marks.notSongs))
            Prepared.Books(BookState(state.books.values.mapNotNull(::matched).associateBy { it.id }, marks))
        }
        Section.BOOK_PLAYS -> Prepared.Plays(here(checkNotNull(contents.bookPlays)), books = true)
        // A saved song on its file here when it is found; else on its path there, and its sheet offers Play once a file is.
        Section.DOWNLOADS -> checkNotNull(contents.downloads).let { memory ->
            Prepared.Downloads(
                memory.copy(
                    items = memory.items.map { item ->
                        (item.state as? DownloadState.Done)?.let { done -> found[done.path]?.let { item.copy(state = done.copy(path = it.path)) } } ?: item
                    },
                ),
            )
        }
    }

    /**
     * The backup's interactions on this phone: one about a song is about it on its path here; a page or the lyrics of a
     * song not here is left out, and a search whose result was such a song keeps its words without it. Albums,
     * artists, folders and playlists by their keys, as the backup has them.
     */
    private fun interactionsHere(): List<Interaction> = contents.interactions.mapNotNull { interaction ->
        val target = interaction.target?.takeIf { it.kind == TargetKind.SONG } ?: return@mapNotNull interaction
        val here = found[target.key]?.path
        when {
            here != null -> interaction.copy(target = Target(TargetKind.SONG, here))
            interaction.kind == InteractionKind.SEARCH -> interaction.copy(target = null)
            else -> null
        }
    }

    /** [plays] whose songs are on this phone, on them. */
    private fun here(plays: List<LoggedPlay>): List<LoggedPlay> = plays.mapNotNull { play -> ref(play.song)?.let { play.copy(song = it) } }

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

    /**
     * [entry] on this phone: its parts here, in its order (those not here left out), the part it was in when that is
     * here, and its id as the library gives it to these files: a single file's path, or the folder of its first part
     * here with its album ([folderBookId]; [folderBookAlbum] reads the album as a backup made before PF10 wrote it too).
     * When the part it was in is not here, it starts again: its place never goes to whichever file now has that number.
     * Null when none of its files is here.
     */
    private fun matched(entry: BookEntry): BookEntry? {
        val parts = entry.parts.mapNotNull { found[it]?.path }
        val first = parts.firstOrNull() ?: return null
        val id = folderBookAlbum(entry.id)?.let { album -> folderBookId(first.substringBeforeLast('/'), album) } ?: first
        val part = entry.partPath?.let { found[it]?.path }
        val place = if (part == null) BookPlace() else entry.place.copy(partIndex = parts.indexOf(part))
        return entry.copy(id = id, parts = parts, place = place, partPath = part)
    }

    /**
     * Writes [prepared] in one change of its store, all or nothing: the queues with Repeat and the settings with the AI
     * switch are two writes, and the first is taken back when the second fails. The count it restored, or null when it
     * could not be written.
     */
    private suspend fun write(prepared: Prepared, stores: BackupStores, newId: () -> String, tally: Tally): Int? = when (prepared) {
        is Prepared.Playlists -> counted(stores.updatePlaylists { mergePlaylists(it, prepared.state, newId) }, prepared.state.playlists.size)
        is Prepared.Queues -> {
            var before: QueueBook? = null
            // Repeat after the queues: it keeps the place of the queue playing now with it.
            val queued = stores.updateQueues { now -> mergeQueues(now, prepared.book).also { before = now } }
            val written = queued && (prepared.repeat == null || stores.replaceRepeat(prepared.repeat))
            if (queued && !written) before?.let { book -> stores.updateQueues { book } }
            counted(written, prepared.book.queues.size)
        }
        // The plays as the summary counted them before the log kept skips: the skips come back, uncounted.
        is Prepared.Plays -> counted(if (prepared.books) stores.addBookPlays(prepared.plays) else stores.addPlays(prepared.plays, prepared.interactions), prepared.plays.count { !it.isShort })
        is Prepared.Bookmarks -> {
            var merged: MergedMarks? = null
            val written = stores.updateSongState { now -> mergeMarks(now, prepared.marks, prepared.usual, prepared.part).also { merged = it }.state }
            if (written) tally.marks = merged
            counted(written, prepared.marks.sumOf { it.second.bookmarks.size })
        }
        is Prepared.Equalizer -> counted(stores.replaceEqualizer(prepared.profiles), prepared.profiles.profiles.size)
        is Prepared.Lyrics -> counted(stores.updateLedger { mergeLedger(it, prepared.entries) }, prepared.entries.size)
        is Prepared.AppSettings -> {
            var before: Settings? = null
            val settled = stores.updateSettings { phone -> overPhone(prepared, phone).also { before = phone } }
            val written = settled && (prepared.ai == null || stores.replaceAiLyrics(prepared.ai))
            if (settled && !written) before?.let { phone -> stores.updateSettings { phone } }
            val own = before?.playback?.connectDevices.orEmpty()
            if (written) tally.pickAgain = prepared.connectNames.filter { name -> own.none { it.name.equals(name, ignoreCase = true) } }
            counted(written, 0)
        }
        is Prepared.Tones -> {
            val tones = prepared.tones.mapNotNull { tone ->
                val id = stores.findTone("${tone.kind.folder}/", tone.fileName, tone.size) ?: return@mapNotNull null
                SavedTone(id, tone.name, tone.kind, tone.songPath, tone.startMs, tone.lengthMs, tone.fadeInMs, tone.fadeOutMs, tone.createdAt, tone.fileName, tone.size)
            }
            val written = tones.isEmpty() || stores.addTones(tones)
            if (written) tally.tonesNotFound = prepared.songsMissing + prepared.tones.size - tones.size
            counted(written, tones.size)
        }
        is Prepared.Duplicates -> counted(stores.addDuplicates(prepared.choices), prepared.choices.notDuplicates.size)
        is Prepared.Covers -> {
            val albums = ownIds(prepared.albums.mapNotNull { (album, songs) -> settled(album, songs, stores) })
            counted(stores.addSettledAlbums(albums, prepared.checkedOnce, prepared.noted), albums.size)
        }
        is Prepared.Names -> counted(stores.addKeptApart(prepared.pairs), prepared.pairs.size)
        is Prepared.Searches -> counted(stores.addSearches(prepared.searches), prepared.searches.size)
        is Prepared.Books -> counted(stores.updateBooks { mergeBooks(it, prepared.state) }, prepared.state.books.size)
        // The settings replace the phone's, as Settings do; each download joins the list once, by key, the newest first.
        is Prepared.Downloads -> counted(
            stores.updateDownloads { now ->
                val keys = now.items.map { it.key }.toSet()
                DownloadsMemory(prepared.memory.settings, (now.items + prepared.memory.items.filter { it.key !in keys }).sortedByDescending { it.addedAt })
            },
            prepared.memory.items.size,
        )
    }

    private fun counted(written: Boolean, count: Int): Int? = if (written) count else null

    /**
     * The backup's stored settings ([prepared]) read over the [phone]'s own, through the same steps as `settings.json`
     * (an older version's are brought up to date, a newer one's read for what this version knows), so what the backup
     * does not carry stays as the phone has it. The phone keeps its automatic backup, its first-run state (setup, the
     * tour, a notifications denial) and the devices Android linked to it. Its hidden songs stay hidden, and the backup's
     * ones (on their paths here) join them; so do the songs held out of suggestions, each with its time.
     */
    private fun overPhone(prepared: Prepared.AppSettings, phone: Settings): Settings {
        val read = SettingsJson.decode(prepared.stored, base = phone) ?: phone
        val nav = read.nav.copy(
            setupDone = phone.nav.setupDone,
            tourSeen = phone.nav.tourSeen,
            notificationsDenied = phone.nav.notificationsDenied,
            notificationDenials = phone.nav.notificationDenials,
        )
        val library = read.library.copy(hiddenSongs = (phone.library.hiddenSongs + prepared.hidden).distinctBy { it.path })
        val suggestions = read.suggestions.copy(
            never = (phone.suggestions.never + prepared.never).distinctBy { it.song.path },
            notNow = (phone.suggestions.notNow + prepared.notNow).distinctBy { it.song.path },
        )
        return read.copy(playback = read.playback.copy(connectDevices = phone.playback.connectDevices), backup = phone.backup, nav = nav, library = library, suggestions = suggestions)
    }

    /**
     * [album] settled on this phone: on its [songs] here, under the id their paths give it, marked as the files are now
     * (a file copied has another modified time), so Covers & tags leaves it settled until one changes. Its Undo stays on
     * the old phone. A Done row is one apply's, as Covers & tags keeps them, so it gets an id by when it was applied
     * ([ownIds] parts two of one time): never "#" and a History entry's number, which an apply on this phone could take,
     * and the same on a second restore. Null when a song is not a file here.
     */
    private suspend fun settled(album: BackupAlbum, songs: List<Song>, stores: BackupStores): AlbumRecord? {
        val marks = songs.map { stores.fileMark(it.path) ?: return null }
        val problems = album.problems.mapNotNull { problem -> found[problem.path]?.let { SongProblem(it.path, problem.outcome) } }
        val done = album.state == Settled.DONE
        val id = albumId(songs.map { it.path }).let { if (done) "$it#restored-${album.at}" else it }
        return AlbumRecord(id, album.state, album.title, album.artist, marks, album.at, entryId = null, changed = album.changed, problems = problems, restored = done)
    }

    /**
     * [records] with each Done row's id its own: a second apply of the same songs at the same time gets "-2" (then
     * "-3"...), by its place in the backup, so a second restore makes the same ids and adds none.
     */
    private fun ownIds(records: List<AlbumRecord>): List<AlbumRecord> {
        val seen = HashMap<String, Int>()
        return records.map { record ->
            if (record.state != Settled.DONE) return@map record
            val nth = (seen[record.id] ?: 0) + 1
            seen[record.id] = nth
            if (nth == 1) record else record.copy(id = "${record.id}-$nth")
        }
    }

    companion object {
        /** What a playlist gets when one of its name is there already. */
        const val RESTORED: String = " (restored)"

        /**
         * [now] with the backup's playlists and Favourites added. A playlist already there (every song of the backup's in
         * it, under its name or its restored name) is not added again: it takes the backup's order, the songs only the
         * phone has after them in theirs ([backupFirst]). One whose name is taken gets [RESTORED] (then " (2)"...). A
         * playlist keeps its id unless the phone uses it, so a favourite playlist stays one. Favourite songs take the
         * backup's order the same way, those not there yet with them, each with the earlier of its dates (a favourite
         * since then); collections not there yet go after the phone's own; a favourite playlist follows its playlist's id.
         */
        internal fun mergePlaylists(now: PlaylistsState, restored: PlaylistsState, newId: () -> String): PlaylistsState {
            var state = now
            val ids = mutableMapOf<String, String>()
            restored.playlists.forEach { playlist ->
                val there = state.playlists.firstOrNull { sameName(it.name, playlist.name) && holds(it.songs, playlist.songs) }
                if (there != null) {
                    ids[playlist.id] = there.id
                    state = state.put(there.copy(songs = backupFirst(there.songs, playlist.songs)))
                    return@forEach
                }
                val taken = state.playlists.map { it.name }
                val name = if (taken.none { it.trim().equals(playlist.name.trim(), ignoreCase = true) }) playlist.name else M3u.uniqueName(playlist.name.trim() + RESTORED, taken)
                val made = playlist.copy(id = playlist.id.takeIf { id -> state.playlists.none { it.id == id } } ?: newId(), name = name)
                ids[playlist.id] = made.id
                state = state.put(made)
            }
            val collections = restored.favouriteCollections.mapNotNull { collection ->
                if (collection.kind == CollectionKind.PLAYLIST) ids[collection.key]?.let { collection.copy(key = it) } else collection
            }
            val favourites = backupFirst(state.favouriteSongs, restored.favouriteSongs)
            val paths = favourites.mapTo(HashSet()) { it.path }
            val dates = (state.favouriteAdded.keys + restored.favouriteAdded.keys).filter { it in paths }
                .associateWith { path -> listOfNotNull(state.favouriteAdded[path], restored.favouriteAdded[path]).min() }
            return state.copy(
                favouriteSongs = favourites,
                favouriteCollections = (state.favouriteCollections + collections).distinct(),
                favouriteAdded = dates,
            )
        }

        /** [phone] holds every song of [backup], each as often (a song twice, twice). */
        private fun holds(phone: List<SongRef>, backup: List<SongRef>): Boolean {
            val there = phone.groupingBy(SongRef::path).eachCount()
            return backup.groupingBy(SongRef::path).eachCount().all { (path, count) -> (there[path] ?: 0) >= count }
        }

        /**
         * A custom order restored (user rule 2026-11-13): [backup]'s songs in its order, then those only [phone] has (the
         * copies beyond the backup's of a song there twice too), in their order.
         */
        private fun backupFirst(phone: List<SongRef>, backup: List<SongRef>): List<SongRef> {
            val taken = backup.groupingBy(SongRef::path).eachCount().toMutableMap()
            return backup + phone.filter { ref ->
                val left = taken[ref.path] ?: 0
                if (left > 0) taken[ref.path] = left - 1
                left == 0
            }
        }

        /** [name], or what a restore names it ("Road trip (restored)", "Road trip (restored) (2)"), case aside. */
        private fun sameName(there: String, name: String): Boolean {
            val have = there.trim().lowercase(Locale.ROOT)
            val base = name.trim().lowercase(Locale.ROOT)
            return have == base || have.startsWith(base + RESTORED)
        }

        /**
         * [now] with the backup's queues ([restored], matched to this phone). On a phone with no queue at all it is the
         * backup's book as it was: the one playing playing again, at its song and second, Recent and Saved in their
         * order, the same ids. Else they are added after the phone's own with new ids and uids from its counters: a
         * saved queue is never lost (added unless the phone saves one with the same songs in the same order); the one
         * playing plays again when the phone plays none, else it goes first among the recent ones; a recent queue is
         * added while Recent has room, unless a queue there or one restored has its songs.
         */
        internal fun mergeQueues(now: QueueBook, restored: QueueBook): QueueBook {
            if (now.queues.isEmpty()) return restored.copy(jumps = now.jumps, nextId = maxOf(restored.nextId, now.nextId), nextUid = maxOf(restored.nextUid, now.nextUid))
            fun songs(queue: Queue) = queue.items.map { it.song.path }
            val savedHere = now.saved.mapNotNull(now::queue).map(::songs).toMutableSet()
            val toSaved = restored.saved.mapNotNull(restored::queue).filter { savedHere.add(songs(it)) }
            val seen = (now.queues + toSaved).map(::songs).toMutableSet()
            val current = restored.current
            val playing = current?.takeIf { now.currentId == null }?.let { queue ->
                if (queue.id in restored.saved) toSaved.firstOrNull { it.id == queue.id } else queue.takeIf { seen.add(songs(it)) }
            }
            val recent = listOfNotNull(current?.takeIf { playing == null && it.id !in restored.saved }) + restored.recent.mapNotNull(restored::queue)
            val toRecent = recent.filter { seen.add(songs(it)) }.take((QueueBook.MAX_RECENT - now.recent.size).coerceAtLeast(0))
            val adding = (listOfNotNull(playing?.takeIf { it.id !in restored.saved }) + toRecent + toSaved)
            if (adding.isEmpty()) return now
            var nextId = now.nextId
            var nextUid = now.nextUid
            val made = adding.associate { queue ->
                val uids = queue.items.associate { it.uid to nextUid++ }
                queue.id to queue.copy(id = "q${nextId++}", items = queue.items.map { it.copy(uid = uids.getValue(it.uid)) }, unshuffled = queue.unshuffled?.mapNotNull(uids::get))
            }
            return now.copy(
                queues = now.queues + made.values,
                currentId = playing?.let { made.getValue(it.id).id } ?: now.currentId,
                recent = now.recent + toRecent.map { made.getValue(it.id).id },
                saved = now.saved + toSaved.map { made.getValue(it.id).id },
                nextId = nextId,
                nextUid = nextUid,
            )
        }

        /**
         * [now] with the backup's places for songs that have none, its bookmarks not there yet (the same spot and note in
         * the form the song state keeps them, with when they were made; one at the same spot made at the same time is there
         * already, its note changed since), its own speeds for songs without one, each on
         * its song as this phone knows it (its path and id hint, [Song.ref]), so they follow it when it moves; the [usual] speed replaces the
         * phone's, and the [part] repeating comes back when the phone repeats none. Through the song state's own API
         * ([SongState.withPlace], [SongState.withBookmark]), which gives each bookmark its new id. A restore never pushes
         * out the phone's own: a place or a bookmark past the song state's caps is left out, and counted; a speed past
         * its cap is left out.
         */
        internal fun mergeMarks(now: SongState, marks: List<Pair<Song, BackupMarks>>, usual: Speed? = null, part: Pair<Song, AbPart>? = null): MergedMarks {
            var state = now
            var placesLeftOut = 0
            var bookmarksLeftOut = 0
            marks.forEach { (song, kept) ->
                val key = song.ref
                if (kept.placeMs != null && key.path !in state.places) {
                    if (state.places.size < SongState.MAX_PLACES) state = state.withPlace(key, kept.placeMs) else placesLeftOut++
                }
                kept.bookmarks.forEach { mark ->
                    val stored = keptForm(key, mark)
                    if (state.bookmarksOf(key.path).none { it.positionMs == stored.positionMs && (it.note == stored.note || mark.addedAt > 0 && it.addedAt == mark.addedAt) }) {
                        val added = state.withBookmark(key, stored.positionMs, stored.note, mark.addedAt)
                        if (added == null) bookmarksLeftOut++ else state = added.first
                    }
                }
                if (kept.speed != null && key.path !in state.speeds && state.speeds.size < SongState.MAX_SPEEDS) {
                    state = state.copy(speeds = state.speeds + (key.path to kept.speed.normalised())).noting(key)
                }
            }
            usual?.let { state = state.copy(usual = it.normalised()) }
            if (part != null && state.part == null) state = state.copy(part = part.second.copy(path = part.first.path)).noting(part.first.ref)
            return MergedMarks(state, placesLeftOut, bookmarksLeftOut)
        }

        /** [song]'s id hint kept with its state, as the song state's own changes keep it. */
        private fun SongState.noting(song: SongRef): SongState = song.mediaStoreId?.let { copy(ids = ids + (song.path to it)) } ?: this

        /** [mark] as the song state would keep it (its spot to the second, its note trimmed and capped), by its own rules. */
        private fun keptForm(song: SongRef, mark: BackupBookmark): Bookmark = checkNotNull(SongState().withBookmark(song, mark.positionMs, mark.note)).second

        /** [now] with the backup's choices for songs it knows nothing about: a choice the phone has, the user's own above all, stays. */
        internal fun mergeLedger(now: LedgerState, restored: List<LedgerEntry>): LedgerState {
            val added = restored.filter { it.path !in now.entries && it.path !in now.unknownEntries }
            return if (added.isEmpty()) now else now.copy(entries = now.entries + added.associateBy { it.path })
        }

        /**
         * [now] with the backup's books ([restored], matched to this phone): of a book on both, the one played last
         * wins (one never played loses to one played, and a tie keeps the phone's), always with the phone's chapters,
         * as a backup has none. The phone's marks stay; the backup's are added for the songs and folders the phone has
         * no mark for either way. Within [BookState.MAX_BOOKS], those played longest ago left out.
         */
        internal fun mergeBooks(now: BookState, restored: BookState): BookState {
            val newer = restored.books.values.mapNotNull { entry ->
                val mine = now.books[entry.id] ?: return@mapNotNull entry
                val played = entry.lastPlayedAt ?: return@mapNotNull null
                if (played > (mine.lastPlayedAt ?: Long.MIN_VALUE)) entry.copy(chapters = mine.chapters, chaptersStamp = mine.chaptersStamp) else null
            }
            val marks = now.marks
            val songs = marks.songs + marks.notSongs
            val folders = marks.folders + marks.notFolders
            return BookState(
                BookState.keepNewest(now.books + newer.associateBy { it.id }),
                BookMarks(
                    songs = marks.songs + (restored.marks.songs - songs),
                    folders = marks.folders + (restored.marks.folders - folders),
                    notSongs = marks.notSongs + (restored.marks.notSongs - songs),
                    notFolders = marks.notFolders + (restored.marks.notFolders - folders),
                ),
            )
        }
    }
}
