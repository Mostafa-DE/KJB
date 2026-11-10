package app.lyrify.tags

import app.lyrify.library.Song
import app.lyrify.lyrics.LocalTrack
import app.lyrify.lyrics.namesChanged
import app.lyrify.tags.lookup.AlbumChanges
import app.lyrify.tags.lookup.AlbumLookup
import app.lyrify.tags.lookup.Cover
import app.lyrify.tags.lookup.CoverPipeline
import app.lyrify.tags.lookup.Lookup
import app.lyrify.tags.lookup.MbRelease
import app.lyrify.tags.lookup.MetaAnswer
import app.lyrify.tags.lookup.OpusCover
import app.lyrify.tags.lookup.PhoneAlbum
import app.lyrify.tags.lookup.PhoneAlbums
import app.lyrify.tags.lookup.PhoneSong
import app.lyrify.tags.lookup.Proposal
import app.lyrify.tags.lookup.ReleaseMatch
import app.lyrify.tags.lookup.SongFields
import app.lyrify.tags.lookup.TagChanges
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.MessageDigest

// Covers & tags (covers-tags-spec.md): the check, the review (Ready, Pick one, Done, Skipped), apply and Undo through
// the safe writer, what an apply does to the lyrics and the covers, and the albums done or skipped (`covers-tags.json`).

/** What Covers & tags asks of MusicBrainz and the Cover Art Archive: [PhoneAlbumSource] in the app, a fake in tests. */
interface AlbumSource {
    /** True while MusicBrainz answers 503 and the gate waits it out: "MusicBrainz is busy". */
    val busy: StateFlow<Boolean>

    /** "Check my albums"; [fresh] ("Check again") skips the cache. */
    suspend fun check(album: PhoneAlbum, fresh: Boolean): Lookup

    /** "Search myself": always Pick one. */
    suspend fun search(album: PhoneAlbum, title: String, artist: String?): Lookup

    /** [match] with its album's original year, asked for when its release did not carry it. */
    suspend fun dated(match: ReleaseMatch): MetaAnswer<ReleaseMatch>

    /** [release]'s front cover, ready to embed and to show. */
    suspend fun cover(release: MbRelease): MetaAnswer<Cover>

    /** [release]'s smaller front cover for Opus songs; [MetaAnswer.NotFound] when even the smallest won't fit. */
    suspend fun opusCover(release: MbRelease): MetaAnswer<Cover>
}

/** [AlbumSource] over the one [AlbumLookup] and the cover pipeline. */
class PhoneAlbumSource(private val lookup: AlbumLookup, private val pipeline: CoverPipeline, override val busy: StateFlow<Boolean>) : AlbumSource {
    override suspend fun check(album: PhoneAlbum, fresh: Boolean): Lookup = lookup.check(album, fresh)

    override suspend fun search(album: PhoneAlbum, title: String, artist: String?): Lookup = lookup.search(album, title, artist)

    override suspend fun dated(match: ReleaseMatch): MetaAnswer<ReleaseMatch> = lookup.withOriginalYear(match)

    override suspend fun cover(release: MbRelease): MetaAnswer<Cover> = when (val answer = lookup.cover(release)) {
        is MetaAnswer.Ok -> pipeline.prepare(answer.value)?.let { MetaAnswer.Ok(it, answer.fromCache) } ?: MetaAnswer.Failed("not a usable image")
        MetaAnswer.NotFound -> MetaAnswer.NotFound
        MetaAnswer.Busy -> MetaAnswer.Busy
        is MetaAnswer.Failed -> answer
    }

    override suspend fun opusCover(release: MbRelease): MetaAnswer<Cover> = when (val answer = lookup.cover(release)) {
        is MetaAnswer.Ok -> pipeline.prepareOpus(answer.value)?.let { MetaAnswer.Ok(it, answer.fromCache) } ?: MetaAnswer.NotFound
        MetaAnswer.NotFound -> MetaAnswer.NotFound
        MetaAnswer.Busy -> MetaAnswer.Busy
        is MetaAnswer.Failed -> answer
    }
}

/** An album as Covers & tags checks it: its songs' tags as MediaStore read them from the files, with their MediaStore [ids]. */
data class TagAlbum(val album: PhoneAlbum, val ids: Map<String, Long>) {
    /** Stable while the album holds the same files: [albumId]. */
    val id: String = albumId(album.songs.map { it.path })

    val paths: List<String> get() = album.songs.map { it.path }
}

/** An album's id: its files' paths, hashed, so it stays when its tags change. */
fun albumId(paths: Collection<String>): String =
    MessageDigest.getInstance("SHA-256").digest(paths.sorted().joinToString("\n").encodeToByteArray()).take(ID_BYTES).joinToString("") { "%02x".format(it) }

private const val ID_BYTES = 12

/**
 * An album waiting for the user: Ready with one release, or Pick one with its candidates; [keepMyYear] is its sheet's
 * switch. [marks] are its files as they were when it was checked: one changed since is not written.
 */
data class Waiting(val album: TagAlbum, val proposal: Proposal, val keepMyYear: Boolean = true, val marks: List<FileMark> = emptyList())

/** "Checking 12/84": [done] of [total] albums looked up; [total] is 0 while the library is read. */
data class Checking(val done: Int, val total: Int)

/** Album [done] of [total] being written (Apply all; 0 of 1 for one album). */
data class Applying(val done: Int, val total: Int)

/** The Covers & tags page's state. */
data class CoversTagsState(
    /** [memory] is read. */
    val loaded: Boolean = false,
    val checking: Checking? = null,
    /** Albums checked on their own (the song sheet, "Check again"), by id. */
    val checkingAlbums: Set<String> = emptySet(),
    val applying: Applying? = null,
    /** Ready and Pick one, in the order checked: this process only, the next check finds them again (from the cache). */
    val waiting: List<Waiting> = emptyList(),
    val memory: CoversTagsMemory = CoversTagsMemory(),
    /** Albums the last check could not look up: offline, refused, or left when MusicBrainz stayed busy. */
    val notChecked: Int = 0,
    /** The last check stopped because MusicBrainz stayed busy. */
    val stoppedBusy: Boolean = false,
    /** The songs could not be read at all. */
    val unreadable: Boolean = false,
    /** The album the song sheet asked for, by id: the page opens it, then [CoversTags.focused]. */
    val focus: String? = null,
    /** Every edit, the latest first. */
    val history: List<HistoryEntry> = emptyList(),
    /** The History entries Undo can still take back from full copies; the others say "Undo has expired". */
    val fullCopies: Set<Long> = emptySet(),
) {
    val ready: List<Waiting> get() = waiting.filter { it.proposal is Proposal.Ready }

    val pickOne: List<Waiting> get() = waiting.filter { it.proposal is Proposal.PickOne }

    val done: List<AlbumRecord> get() = memory.albums.filter { it.state == Settled.DONE }.sortedByDescending { it.at }

    val skipped: List<AlbumRecord> get() = memory.albums.filter { it.state == Settled.SKIPPED }.sortedByDescending { it.at }

    /** The albums waiting for the user: the badge on its Tools row. */
    val badge: Int get() = waiting.size
}

/** What an apply did. */
sealed interface Applied {
    /** [albums] moved to Done with [songs] songs written; [problems] were left as they were; [failed] albums got no answer. */
    data class Done(val albums: Int, val songs: Int, val problems: List<SongProblem>, val failed: Int = 0, val stopped: Stopped? = null) : Applied

    /** Another apply or Undo is running: nothing was started. */
    data object InProgress : Applied

    /** One of the album's files changed since the check: nothing written, and the album waits for the next check. */
    data object ChangedSince : Applied

    /** No notification access, so the media sessions can't be listed: nothing written. */
    data object NoAccess : Applied

    data object NotEnoughSpace : Applied

    /** MusicBrainz stayed busy for the year or the cover: nothing written for that album. */
    data object Busy : Applied

    /** MusicBrainz or the cover could not be reached: nothing written for that album. */
    data object Failed : Applied

    /** The album no longer waits: nothing written. */
    data object Gone : Applied
}

/** Why Apply all stopped before the last album, after it changed others. */
enum class Stopped { NO_ACCESS, NOT_ENOUGH_SPACE, BUSY }

/** What an Undo did. */
sealed interface Undone {
    /** [songs] taken back; [skippedFields] changed again since and kept; [changedSince] songs skipped; [problems] left. */
    data class Done(val songs: Int, val skippedFields: Int, val changedSince: Int, val problems: List<SongProblem>) : Undone

    data object NoAccess : Undone

    data object NotEnoughSpace : Undone

    /** An apply or another Undo is running: nothing was started. */
    data object InProgress : Undone

    data object Gone : Undone

    /** History can't be read now: nothing was changed, and the album stays Done. */
    data object Failed : Undone
}

/** What "Search myself" found. */
sealed interface SearchOutcome {
    data class Found(val candidates: Int) : SearchOutcome

    data object Busy : SearchOutcome

    data object Failed : SearchOutcome

    data object Gone : SearchOutcome
}

/**
 * The covers' versions in this process, by song path: bumped on every apply and Undo, so the cover's key in the image
 * loader changes ([app.lyrify.ui.coverKey]) and KJB shows the new cover at once.
 */
object CoverVersions {
    private val _versions = MutableStateFlow<Map<String, Int>>(emptyMap())

    val versions: StateFlow<Map<String, Int>> = _versions.asStateFlow()

    fun bump(paths: Collection<String>) = _versions.update { now -> now + paths.associateWith { (now[it] ?: 0) + 1 } }

    fun of(path: String): Int = _versions.value[path] ?: 0
}

/** A library song as Covers & tags matches it: its tags as MediaStore read them from the file. */
fun phoneSong(song: Song): PhoneSong =
    PhoneSong(song.path, song.title, song.artist, song.album, song.albumArtist, song.disc, song.track, song.durationMs, song.year)

/**
 * The tag map Covers & tags writes into a song, by TagLib property name: title, the artist as ONE credited name (the
 * names one by one only in ARTISTS), album, album artist, track and disc ("3/12" when the total is known), the release
 * and recording ids, and the album's original year: only where the song has none while [keepMyYear] is on. Never genre,
 * comments, lyrics or ReplayGain. Pure.
 */
fun coversTagsEdit(fields: SongFields, originalYear: Int?, keepMyYear: Boolean, cover: CoverEdit): TagEdit {
    fun number(n: Int, total: Int?) = if (total != null) "$n/$total" else n.toString()
    val map = buildMap {
        put("TITLE", listOf(fields.title))
        put("ARTIST", listOf(fields.artist))
        if (fields.artists.isNotEmpty()) put("ARTISTS", fields.artists)
        put("ALBUM", listOf(fields.album))
        put("ALBUMARTIST", listOf(fields.albumArtist))
        put("TRACKNUMBER", listOf(number(fields.track, fields.trackTotal)))
        put("DISCNUMBER", listOf(number(fields.disc, fields.discTotal)))
        put("MUSICBRAINZ_ALBUMID", listOf(fields.musicBrainzReleaseId))
        put("MUSICBRAINZ_TRACKID", listOf(fields.musicBrainzRecordingId))
        originalYear?.let { put(YEAR, listOf(it.toString())) }
    }
    return TagEdit(map, onlyWhereEmpty = if (keepMyYear) setOf(YEAR) else emptySet(), cover = cover)
}

/**
 * Whether a cover of [jpegBytes] goes into the song at [path]: always, except into Opus, whose comment header must stay
 * within [OpusCover.COMMENT_LIMIT_BYTES] beside the song's other comments ([OpusCover.fitsBesideOthers]).
 */
fun coverFits(path: String, jpegBytes: Int): Boolean = !path.endsWith(".opus", ignoreCase = true) || OpusCover.fitsBesideOthers(jpegBytes)

/** The cover the song at [path] gets: the album's [main] one, or for Opus the smaller [opus] one; its own when neither fits. */
fun coverFor(path: String, main: Cover?, opus: Cover?): CoverEdit {
    val cover = when {
        main == null -> null
        coverFits(path, main.jpeg.size) -> main
        else -> opus?.takeIf { coverFits(path, it.jpeg.size) }
    }
    return cover?.let { CoverEdit.Replace(TagPicture(it.jpeg, "", FRONT_COVER, JPEG_MIME)) } ?: CoverEdit.Keep
}

private const val YEAR = "DATE"

private const val JPEG_MIME = "image/jpeg"

/** At most this many covers stay in memory for the cards and the sheets. */
private const val COVERS_KEPT = 24

/** [this] song as the library will read it once Covers & tags wrote [fields]: a song's own year stays when none is written. */
private fun Song.written(fields: SongFields): Song =
    copy(title = fields.title, artist = fields.artist, album = fields.album, albumArtist = fields.albumArtist, track = fields.track, disc = fields.disc, year = fields.year ?: year)

/**
 * Covers & tags: checks the library's albums against MusicBrainz ([source]) one at a time in [scope] while the app is
 * open, never by itself; keeps what waits for the user; applies an album through the one safe writer ([write], T3's
 * [writeEdit], one file at a time, busy and recovering songs left as they are and listed, favourite hearts following the
 * album's new name); and takes edits back through it ([undoEntry], [undo] holds History).
 * Writing needs notification [access]. After an apply, each song written goes to the [lyrics] with its new names, and
 * every song written or taken back gets a new cover version ([coversChanged]). Done and Skipped albums are kept in
 * [store]; they are left out of a check until one of their files changes. Logs counts only.
 */
class CoversTags(
    private val songs: suspend () -> List<Song>?,
    private val source: AlbumSource,
    /** The one tag writer's write, the hearts it renames moving with it ([writeEdit]): jobs, each song before and after, kind, label. */
    private val write: suspend (List<WriteJob>, List<Pair<Song, Song>>, EditKind, String) -> BatchResult,
    /** Its Undo of a History entry, the hearts moving back ([undoEdit]). */
    private val undoEntry: suspend (Long) -> BatchResult,
    private val undo: UndoStore,
    private val store: CoversTagsStore,
    private val lyrics: suspend (LocalTrack, Boolean) -> Unit,
    private val coversChanged: (Collection<String>) -> Unit,
    private val access: () -> Boolean,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val compute: CoroutineDispatcher = Dispatchers.Default,
    private val clock: () -> Long = System::currentTimeMillis,
    private val files: (String) -> FileMark? = ::fileMark,
    private val log: (String) -> Unit = {},
) {
    private val _state = MutableStateFlow(CoversTagsState())
    val state: StateFlow<CoversTagsState> = _state.asStateFlow()

    /** "MusicBrainz is busy": a 503 being waited out. */
    val busy: StateFlow<Boolean> get() = source.busy

    /** One write of [store] at a time, each of the memory as it is then. */
    private val saving = Mutex()

    private val loading: Job = scope.launch(io) {
        val stored = store.load()
        val memory = withLostDone(stored)
        if (memory != stored) store.save(memory)
        _state.update { it.copy(loaded = true, memory = memory) }
        readHistory()
    }

    /** One apply or Undo at a time: a second one while one runs is refused, never queued behind it. */
    private val writing = Mutex()

    /** The check running ("Check my albums"); only it changes [CoversTagsState.checking]. */
    @Volatile
    private var running: Job? = null

    private val shown = object : LinkedHashMap<String, Cover>(COVERS_KEPT, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cover>?): Boolean = size > COVERS_KEPT
    }

    // Checking

    /** "Check my albums": every album not done or skipped (or changed since), one at a time; unless one is running. */
    fun check() {
        if (running?.isActive == true) return
        _state.update { it.copy(checking = Checking(0, 0), notChecked = 0, stoppedBusy = false, unreadable = false) }
        val job = scope.launch(compute, start = CoroutineStart.LAZY) {
            val self = coroutineContext.job
            try {
                loading.join()
                val albums = toCheck()
                if (albums == null) {
                    _state.update { it.copy(unreadable = true) }
                    return@launch
                }
                commit { it.copy(checkedOnce = true) }
                val ids = albums.map { it.id }.toSet()
                if (lookUp(albums, fresh = false, progress = true)) {
                    // What a whole check did not find again (gone, settled, regrouped) waits no more.
                    _state.update { state -> state.copy(waiting = state.waiting.filter { it.album.id in ids || it.album.id in state.checkingAlbums }) }
                }
                log("tags: check done, ${albums.size} albums, ${_state.value.waiting.size} waiting")
            } finally {
                if (running === self) _state.update { it.copy(checking = null) }
            }
        }
        running = job
        job.start()
    }

    /** "Cancel": the check stops after the album it is on; what it found stays. */
    fun cancel() {
        running?.cancel()
        running = null
        _state.update { it.copy(checking = null) }
    }

    /** "Check again" on a skipped album: looked up afresh, skipping the cache. */
    fun checkAgain(recordId: String) {
        scope.launch(compute) {
            loading.join()
            val record = _state.value.memory.albums.firstOrNull { it.id == recordId } ?: return@launch
            val paths = record.songs.map { it.path }.toSet()
            val album = albumsOf(songs()?.filter { it.path in paths } ?: return@launch).maxByOrNull { it.paths.size } ?: return@launch
            // Skipped no more only once the lookup placed the album again.
            if (single(album, fresh = true)) commit { memory -> memory.copy(albums = memory.albums.filterNot { it.id == recordId }) }
        }
    }

    /**
     * The song sheet's "Covers & tags": the review of [path]'s album, which the page opens ([CoversTagsState.focus]);
     * an album not checked yet is checked first.
     */
    fun open(path: String) {
        scope.launch(compute) {
            loading.join()
            val now = _state.value
            val known = now.waiting.firstOrNull { path in it.album.paths }?.album?.id
                ?: now.memory.albums.firstOrNull { record -> record.songs.any { it.path == path } }?.id
            if (known != null) {
                _state.update { it.copy(focus = known) }
                return@launch
            }
            val album = albumsOf(songs() ?: return@launch).firstOrNull { path in it.paths } ?: return@launch
            single(album, fresh = false, focus = true)
        }
    }

    /** The page opened the album the song sheet asked for. */
    fun focused() = _state.update { it.copy(focus = null) }

    /** The library's albums now, with their songs' MediaStore ids; null when the songs can't be read. */
    private fun albumsOf(songs: List<Song>): List<TagAlbum> {
        val ids = songs.mapNotNull { song -> song.ref.mediaStoreId?.let { song.path to it } }.toMap()
        return PhoneAlbums.group(songs.map(::phoneSong)).map { album -> TagAlbum(album, album.songs.mapNotNull { s -> ids[s.path]?.let { s.path to it } }.toMap()) }
    }

    /**
     * The albums a check looks up: all but those whose every song is in a settled album unchanged since. A settled album
     * one of whose songs is looked up again is forgotten (its History entry stays).
     */
    private suspend fun toCheck(): List<TagAlbum>? {
        val albums = albumsOf(songs() ?: return null)
        val records = _state.value.memory.albums
        val settled = withContext(io) { records.filter { record -> record.songs.all { files(it.path) == it } }.flatMap { it.songs }.map { it.path }.toSet() }
        val check = albums.filterNot { album -> album.paths.all { it in settled } }
        val again = check.flatMap { it.paths }.toSet()
        if (records.any { record -> record.songs.any { it.path in again } }) {
            commit { memory -> memory.copy(albums = memory.albums.filterNot { record -> record.songs.any { it.path in again } }) }
        }
        return check
    }

    /** Looks [albums] up one by one, each answer taking the album's place. False when MusicBrainz stayed busy. */
    private suspend fun lookUp(albums: List<TagAlbum>, fresh: Boolean, progress: Boolean): Boolean {
        for ((index, album) in albums.withIndex()) {
            if (progress) _state.update { it.copy(checking = Checking(index, albums.size)) }
            when (val result = source.check(album.album, fresh)) {
                is Lookup.Done -> place(album, result.proposal, marks(album))
                Lookup.Busy -> {
                    _state.update { it.copy(stoppedBusy = true, notChecked = it.notChecked + albums.size - index) }
                    return false
                }
                is Lookup.Failed -> _state.update { it.copy(notChecked = it.notChecked + 1) }
            }
        }
        if (progress) _state.update { it.copy(checking = Checking(albums.size, albums.size)) }
        return true
    }

    /**
     * One album checked on its own, shown as such meanwhile; with [focus], the page opens it once it is checked. True
     * when the album waits for the user now.
     */
    private suspend fun single(album: TagAlbum, fresh: Boolean, focus: Boolean = false): Boolean {
        _state.update { it.copy(checkingAlbums = it.checkingAlbums + album.id, stoppedBusy = false, focus = if (focus) album.id else it.focus) }
        try {
            lookUp(listOf(album), fresh, progress = false)
        } finally {
            _state.update { it.copy(checkingAlbums = it.checkingAlbums - album.id) }
        }
        return waiting(album.id) != null
    }

    private fun place(album: TagAlbum, proposal: Proposal, marks: List<FileMark>) = _state.update { state ->
        val at = state.waiting.indexOfFirst { it.album.id == album.id }
        val waiting = Waiting(album, proposal, state.waiting.getOrNull(at)?.keepMyYear ?: true, marks)
        state.copy(waiting = if (at < 0) state.waiting + waiting else state.waiting.toMutableList().also { it[at] = waiting })
    }

    private fun waiting(albumId: String): Waiting? = _state.value.waiting.firstOrNull { it.album.id == albumId }

    // Reviewing

    /** The album sheet's "Keep my year" switch. */
    fun keepMyYear(albumId: String, keep: Boolean) = _state.update { state ->
        state.copy(waiting = state.waiting.map { if (it.album.id == albumId) it.copy(keepMyYear = keep) else it })
    }

    /** What applying [match] would change in [waiting]'s songs: the album sheet's "old → new". */
    fun changes(waiting: Waiting, match: ReleaseMatch): AlbumChanges = TagChanges.of(waiting.album.album, match, waiting.keepMyYear)

    /** [release]'s cover for a card or a sheet, or null when it has none or it can't be fetched now. */
    suspend fun cover(release: MbRelease): Cover? = (coverOf(release) as? MetaAnswer.Ok)?.value

    /** [release]'s smaller cover for Opus songs, or null when even the smallest won't fit (or it can't be fetched now). */
    suspend fun opusCover(release: MbRelease): Cover? {
        val key = OPUS_KEY + release.id
        synchronized(shown) { shown[key] }?.let { return it }
        return (source.opusCover(release) as? MetaAnswer.Ok)?.value?.also { synchronized(shown) { shown[key] = it } }
    }

    private suspend fun coverOf(release: MbRelease): MetaAnswer<Cover> {
        synchronized(shown) { shown[release.id] }?.let { return MetaAnswer.Ok(it) }
        return source.cover(release).also { answer -> if (answer is MetaAnswer.Ok) synchronized(shown) { shown[release.id] = answer.value } }
    }

    /** "Not this one": the album goes to Pick one, without [match]. */
    fun notThisOne(albumId: String, match: ReleaseMatch) = _state.update { state ->
        state.copy(
            waiting = state.waiting.map { waiting ->
                if (waiting.album.id != albumId) return@map waiting
                val pick = waiting.proposal as? Proposal.PickOne
                waiting.copy(proposal = Proposal.PickOne(pick?.candidates.orEmpty().filterNot { it.release.id == match.release.id }, pick?.doubts.orEmpty()))
            },
        )
    }

    /** "Search myself" with the user's [title] and [artist]: its candidates take the album's place in Pick one. */
    suspend fun search(albumId: String, title: String, artist: String?): SearchOutcome {
        val waiting = waiting(albumId) ?: return SearchOutcome.Gone
        return when (val result = source.search(waiting.album.album, title.trim(), artist?.trim()?.takeIf(String::isNotEmpty))) {
            is Lookup.Done -> {
                place(waiting.album, result.proposal, waiting.marks)
                SearchOutcome.Found((result.proposal as? Proposal.PickOne)?.candidates?.size ?: 1)
            }
            Lookup.Busy -> SearchOutcome.Busy
            is Lookup.Failed -> SearchOutcome.Failed
        }
    }

    /** "Skip": the album is left out of the checks until one of its files changes, or "Check again". */
    suspend fun skip(albumId: String): Boolean {
        val waiting = waiting(albumId) ?: return false
        val album = waiting.album.album
        val record = AlbumRecord(
            id = waiting.album.id,
            state = Settled.SKIPPED,
            title = album.title?.takeIf(String::isNotBlank) ?: album.songs.firstOrNull()?.title.orEmpty(),
            artist = album.artist,
            songs = marks(waiting.album),
            at = clock(),
        )
        settle(record)
        return true
    }

    /** The first apply's dialog showed the backup and sync note: it does not show again. */
    suspend fun noted() {
        commit { it.copy(noted = true) }
    }

    // Applying

    /**
     * [work] in [scope], holding [writing], with [applying] shown: leaving the page (which cancels the caller) never cuts
     * it short once a file is written, so Done, the cover versions and the lyrics follow. Null while another runs.
     */
    private suspend fun <T> writingAlone(applying: Applying?, work: suspend () -> T): T? {
        if (!writing.tryLock()) return null
        return scope.async(compute) {
            try {
                _state.update { it.copy(applying = applying) }
                work()
            } finally {
                _state.update { it.copy(applying = null) }
                writing.unlock()
            }
        }.await()
    }

    /** "Apply" in the album sheet: [match] written into the album's songs. */
    suspend fun apply(albumId: String, match: ReleaseMatch): Applied {
        if (waiting(albumId) == null) return Applied.Gone
        if (!access()) return Applied.NoAccess
        return writingAlone(Applying(0, 1)) {
            // The album may have been applied meanwhile.
            waiting(albumId)?.let { applyOne(it, match) } ?: Applied.Gone
        } ?: Applied.InProgress
    }

    /**
     * "Apply all": every Ready album, one after the other. No access, no space or MusicBrainz busy stops the rest; the
     * albums changed before are reported with why the rest stopped.
     */
    suspend fun applyAll(): Applied {
        if (!access()) return Applied.NoAccess
        val ready = _state.value.ready
        return writingAlone(Applying(0, ready.size)) {
            var albums = 0
            var written = 0
            var failed = 0
            val problems = mutableListOf<SongProblem>()
            for ((index, planned) in ready.withIndex()) {
                _state.update { it.copy(applying = Applying(index, ready.size)) }
                // Applied, skipped or picked again meanwhile: left as it is now.
                val waiting = waiting(planned.album.id)?.takeIf { it.proposal == planned.proposal } ?: continue
                val stop = when (val result = applyOne(waiting, (waiting.proposal as Proposal.Ready).match)) {
                    is Applied.Done -> {
                        albums += result.albums
                        written += result.songs
                        problems += result.problems
                        null
                    }
                    Applied.Failed, Applied.ChangedSince -> null.also { failed++ }
                    Applied.NoAccess -> Stopped.NO_ACCESS to result
                    Applied.NotEnoughSpace -> Stopped.NOT_ENOUGH_SPACE to result
                    Applied.Busy -> Stopped.BUSY to result
                    Applied.Gone, Applied.InProgress -> null
                } ?: continue
                // Nothing changed yet: the stop is all there is to say.
                return@writingAlone if (albums == 0 && problems.isEmpty()) stop.second else Applied.Done(albums, written, problems, failed, stop.first)
            }
            Applied.Done(albums, written, problems, failed)
        } ?: Applied.InProgress
    }

    private suspend fun applyOne(waiting: Waiting, match: ReleaseMatch): Applied {
        // A file replaced since the check would get another song's names: the album waits for the next check.
        if (marks(waiting.album) != waiting.marks) {
            _state.update { state -> state.copy(waiting = state.waiting.filterNot { it.album.id == waiting.album.id }) }
            log("tags: album changed since the check, not written")
            return Applied.ChangedSince
        }
        val dated = when (val answer = source.dated(match)) {
            is MetaAnswer.Ok -> answer.value
            MetaAnswer.NotFound -> match
            MetaAnswer.Busy -> return Applied.Busy
            is MetaAnswer.Failed -> return Applied.Failed
        }
        val cover = when (val answer = coverOf(dated.release)) {
            is MetaAnswer.Ok -> answer.value
            MetaAnswer.NotFound -> null
            MetaAnswer.Busy -> return Applied.Busy
            is MetaAnswer.Failed -> return Applied.Failed
        }
        val album = waiting.album
        val changes = TagChanges.of(album.album, dated, waiting.keepMyYear)
        val original = dated.release.releaseGroup?.originalYear
        val (known, unknown) = changes.songs.partition { it.song.path in album.ids }
        // Opus songs the album's cover is too big for get a smaller one, fetched only for them.
        val opus = if (cover != null && known.any { !coverFits(it.song.path, cover.jpeg.size) }) opusCover(dated.release) else null
        val jobs = known.map { change ->
            val path = change.song.path
            WriteJob(SongTarget(path, album.ids.getValue(path)), coversTagsEdit(change.fields, original, waiting.keepMyYear, coverFor(path, cover, opus)))
        }
        // Each song as the library reads it now and as it will once written: a favourite album follows its new name.
        val before = songs().orEmpty().associateBy { it.path }
        val changed = known.mapNotNull { change -> before[change.song.path]?.let { it to it.written(change.fields) } }
        return when (val result = write(jobs, changed, EditKind.COVERS_TAGS, dated.release.title)) {
            BatchResult.SessionsUnknown -> Applied.NoAccess
            BatchResult.NotEnoughSpace -> Applied.NotEnoughSpace
            // An Undo's answer only: a write never gives it.
            BatchResult.HistoryUnreadable -> Applied.Failed
            is BatchResult.Done -> settled(waiting, dated.release, changes, result, unknown.map { SongProblem(it.song.path, SongOutcome.COULD_NOT_CHANGE) })
        }
    }

    /**
     * After the writer: the album is Done when a song was written or none needed to be (its problems listed); else it
     * waits where it was. The songs written get new cover versions and go to the lyrics.
     */
    private suspend fun settled(waiting: Waiting, release: MbRelease, changes: AlbumChanges, result: BatchResult.Done, missing: List<SongProblem>): Applied {
        val written = result.songs.filter { it.outcome == SongOutcome.WRITTEN }.map { it.song.path }
        val problems = result.songs.filter { it.outcome != SongOutcome.WRITTEN && it.outcome != SongOutcome.UNCHANGED }.map { SongProblem(it.song.path, it.outcome) } + missing
        if (written.isNotEmpty()) coversChanged(written)
        val done = written.isNotEmpty() || problems.isEmpty()
        val kept = _state.value.memory.albums.firstOrNull { it.id == waiting.album.id && it.state == Settled.DONE && it.entryId != null }
        if (done && result.entryId == null && kept != null) {
            // Nothing written over an album already done: its Done row, and its Undo, stay.
            _state.update { state -> state.copy(waiting = state.waiting.filterNot { it.album.id == waiting.album.id }) }
        } else if (done) {
            settle(
                AlbumRecord(
                    id = waiting.album.id,
                    state = Settled.DONE,
                    title = release.title,
                    artist = release.artist.text,
                    songs = marks(waiting.album),
                    at = clock(),
                    entryId = result.entryId,
                    changed = written.size,
                    problems = problems,
                ),
            )
        }
        if (written.isNotEmpty()) {
            val paths = written.toSet()
            scope.launch(io) { lyricsAfter(changes, paths) }
            readHistory()
        }
        log("tags: album applied, ${written.size} songs written, ${problems.size} left")
        return Applied.Done(if (done) 1 else 0, written.size, problems)
    }

    /** Each song [written] goes to the lyrics with its new names, and whether its normalised title or artist changed. */
    private suspend fun lyricsAfter(changes: AlbumChanges, written: Set<String>) {
        for (change in changes.songs) {
            val song = change.song
            if (song.path !in written) continue
            val fields = change.fields
            lyrics(LocalTrack(song.path, fields.title, fields.artist, fields.album, song.durationMs), namesChanged(song.title, song.artist, fields.title, fields.artist))
        }
    }

    // Undo and History

    /** "Undo" on a Done album. */
    suspend fun undoAlbum(recordId: String): Undone {
        val record = _state.value.memory.albums.firstOrNull { it.id == recordId && it.state == Settled.DONE } ?: return Undone.Gone
        val entry = record.entryId ?: return Undone.Gone
        return undo(entry)
    }

    /**
     * "Undo" on History entry [entryId]: each song from its full copy, or field by field where a field still holds the
     * edit's value (one changed again since is kept). Its Done album is forgotten once nothing is left to take back.
     */
    suspend fun undo(entryId: Long): Undone {
        if (!access()) return Undone.NoAccess
        return writingAlone(null) { undoing(entryId) } ?: Undone.InProgress
    }

    private suspend fun undoing(entryId: Long): Undone {
        val result = when (val batch = undoEntry(entryId)) {
            BatchResult.SessionsUnknown -> return Undone.NoAccess
            BatchResult.NotEnoughSpace -> return Undone.NotEnoughSpace
            BatchResult.HistoryUnreadable -> return Undone.Failed
            is BatchResult.Done -> batch
        }
        val back = result.songs.filter { it.outcome == SongOutcome.WRITTEN }.map { it.song.path }
        if (back.isNotEmpty()) coversChanged(back)
        val final = setOf(SongOutcome.WRITTEN, SongOutcome.UNCHANGED, SongOutcome.CHANGED_SINCE, SongOutcome.DIFFERENT_FILE)
        if (result.songs.all { it.outcome in final }) commit { memory -> memory.copy(albums = memory.albums.filterNot { it.entryId == entryId }) }
        readHistory()
        log("tags: undo, ${back.size} songs back")
        return Undone.Done(
            songs = back.size,
            skippedFields = result.skippedFields,
            changedSince = result.songs.count { it.outcome == SongOutcome.CHANGED_SINCE || it.outcome == SongOutcome.DIFFERENT_FILE },
            problems = result.songs.filter { it.outcome !in final }.map { SongProblem(it.song.path, it.outcome) },
        )
    }

    /** Reads History again (Edit tags adds to it too). */
    fun refreshHistory() {
        scope.launch(io) { readHistory() }
    }

    private suspend fun readHistory() {
        val (entries, full) = withContext(io) {
            val entries = undo.memory().entries.sortedByDescending { it.at }
            entries to entries.filter { undo.hasFullCopy(it.id) }.map { it.id }.toSet()
        }
        _state.update { it.copy(history = entries, fullCopies = full) }
    }

    // What is kept

    private suspend fun marks(album: TagAlbum): List<FileMark> = withContext(io) { album.paths.mapNotNull(files) }

    /**
     * [memory] with a Done album for each Covers & tags History entry newer than [CoversTagsMemory.historySeen] that has
     * none: the app died after the files were written and before `covers-tags.json` was. One Done album per album: the
     * newest entry's, with its own Undo, never in place of a newer record kept. A file from before historySeen was kept
     * rebuilds nothing (what it settled is unknown): from then on only entries newer than the newest one now count.
     * History that can't be read rebuilds nothing, and decides nothing. Blocking.
     */
    private fun withLostDone(memory: CoversTagsMemory): CoversTagsMemory {
        val entries = try {
            undo.whole().entries
        } catch (e: IOException) {
            log("tags: History could not be read (${e.javaClass.simpleName}), no Done album rebuilt")
            return memory
        }
        val seen = memory.historySeen ?: return memory.copy(historySeen = entries.maxOfOrNull { it.id } ?: 0)
        val lost = entries.filter { entry ->
            entry.kind == EditKind.COVERS_TAGS && entry.id > seen && entry.songs.any { !it.undone } && memory.albums.none { it.entryId == entry.id }
        }
        if (lost.isEmpty()) return memory
        val rebuilt = lost.map { entry ->
            val paths = entry.songs.map { it.path }
            AlbumRecord(albumId(paths), Settled.DONE, entry.label, songs = paths.mapNotNull(files), at = entry.at, entryId = entry.id, changed = entry.songs.size)
        }.groupBy { it.id }.values.map { records -> records.maxBy { it.at } }
            .filter { record -> memory.albums.none { it.id == record.id && it.at >= record.at } }
        log("tags: ${rebuilt.size} Done albums rebuilt from History")
        return memory.copy(albums = memory.albums.filterNot { record -> rebuilt.any { it.id == record.id } } + rebuilt, historySeen = maxOf(seen, lost.maxOf { it.id }))
    }

    /** [record] replaces the album's own, and the album waits no more. */
    private suspend fun settle(record: AlbumRecord) {
        commit { memory ->
            memory.copy(albums = memory.albums.filterNot { it.id == record.id } + record, historySeen = maxOf(memory.historySeen ?: 0, record.entryId ?: 0))
        }
        _state.update { state -> state.copy(waiting = state.waiting.filterNot { it.album.id == record.id }) }
    }

    /** Changes the memory and stores it; the state has the change even when it could not be stored. */
    private suspend fun commit(change: (CoversTagsMemory) -> CoversTagsMemory): Boolean = saving.withLock {
        loading.join()
        val memory = change(_state.value.memory)
        _state.update { it.copy(memory = memory) }
        withContext(io) { store.save(memory) }
    }

    private companion object {
        const val LOAD_FACTOR = 0.75f
        const val OPUS_KEY = "opus:"
    }
}
