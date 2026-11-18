package app.lyrify.tags

import app.lyrify.library.Song
import app.lyrify.lyrics.LocalTrack
import app.lyrify.lyrics.namesChanged
import app.lyrify.tags.lookup.AlbumChanges
import app.lyrify.tags.lookup.AlbumLookup
import app.lyrify.tags.lookup.AlbumMatch
import app.lyrify.tags.lookup.Cover
import app.lyrify.tags.lookup.CoverPipeline
import app.lyrify.tags.lookup.Lookup
import app.lyrify.tags.lookup.MbRelease
import app.lyrify.tags.lookup.MetaAnswer
import app.lyrify.tags.lookup.OpusCover
import app.lyrify.tags.lookup.Part
import app.lyrify.tags.lookup.PhoneAlbum
import app.lyrify.tags.lookup.PhoneAlbums
import app.lyrify.tags.lookup.PhoneSong
import app.lyrify.tags.lookup.Proposal
import app.lyrify.tags.lookup.ReleaseMatch
import app.lyrify.tags.lookup.SongFields
import app.lyrify.tags.lookup.TagChanges
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.MessageDigest

// Covers & tags (covers-tags-spec.md, and the CT2 redesign in handoff/user-feedback-2026-11-11.md): the check, the
// review (To review, Done, Skipped), apply and Undo through the safe writer, what an apply does to the lyrics and the
// covers, and the albums done or skipped (`covers-tags.json`). An apply writes only the songs and the fields ticked.

/** What Covers & tags asks of MusicBrainz and the Cover Art Archive: [PhoneAlbumSource] in the app, a fake in tests. */
interface AlbumSource {
    /** True while MusicBrainz answers 503 and the gate waits it out: "MusicBrainz is busy". */
    val busy: StateFlow<Boolean>

    /** "Check my albums"; [fresh] ("Check again") skips the cache. */
    suspend fun check(album: PhoneAlbum, fresh: Boolean): Lookup

    /** "Search myself": always Pick one. */
    suspend fun search(album: PhoneAlbum, title: String, artist: String?): Lookup

    /** [match] with its album's original year, asked for when its answer did not carry it (a recording search's). */
    suspend fun dated(match: ReleaseMatch): MetaAnswer<ReleaseMatch>

    /** [release]'s front cover as a card or the review shows it: the small thumbnail, as the Cover Art Archive serves it. */
    suspend fun preview(release: MbRelease): MetaAnswer<ByteArray>

    /** [release]'s front cover, ready to embed: fetched only when an apply writes it. */
    suspend fun cover(release: MbRelease): MetaAnswer<Cover>

    /** [release]'s smaller front cover for Opus songs; [MetaAnswer.NotFound] when even the smallest won't fit. */
    suspend fun opusCover(release: MbRelease): MetaAnswer<Cover>
}

/** [AlbumSource] over the one [AlbumLookup] and the cover pipeline. */
class PhoneAlbumSource(private val lookup: AlbumLookup, private val pipeline: CoverPipeline, override val busy: StateFlow<Boolean>) : AlbumSource {
    override suspend fun check(album: PhoneAlbum, fresh: Boolean): Lookup = lookup.check(album, fresh)

    override suspend fun search(album: PhoneAlbum, title: String, artist: String?): Lookup = lookup.search(album, title, artist)

    override suspend fun dated(match: ReleaseMatch): MetaAnswer<ReleaseMatch> = lookup.withOriginalYear(match)

    override suspend fun preview(release: MbRelease): MetaAnswer<ByteArray> = lookup.thumbnail(release)

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
 * An album waiting for the user: Ready with one release, or Pick one with its candidates. [marks] are its files as they
 * were when it was checked ([checkedAt]): one changed since is not written.
 */
data class Waiting(val album: TagAlbum, val proposal: Proposal, val marks: List<FileMark> = emptyList(), val checkedAt: Long = 0) {
    /** The releases to pick from, best first: the Ready one alone. */
    val candidates: List<ReleaseMatch>
        get() = when (proposal) {
            is Proposal.Ready -> listOf(proposal.match)
            is Proposal.PickOne -> proposal.candidates
        }

    /** The candidates, by release id, with a new value for one of the songs. */
    private val changing: Set<String> = candidates.filter { TagChanges.of(album.album, it).parts.isNotEmpty() }.map { it.release.id }.toSet()

    /**
     * Whether anything could change: a candidate with a new value for one of the songs, or with a front cover the Cover
     * Art Archive confirmed (its release says so) and that was not found missing since ([noCover], by release id). An
     * album with neither is not listed in To review.
     */
    fun suggests(noCover: Set<String>): Boolean =
        candidates.any { it.release.id in changing || (it.release.hasFront == true && it.release.id !in noCover) }

    /** [proposal] with [match] in its candidate's place (the same release, its year now known). */
    internal fun with(match: ReleaseMatch): Waiting = copy(
        proposal = when (proposal) {
            is Proposal.Ready -> if (proposal.match.release.id == match.release.id) Proposal.Ready(match) else proposal
            is Proposal.PickOne -> proposal.copy(candidates = proposal.candidates.map { if (it.release.id == match.release.id) match else it })
        },
    )

    /** This album as kept between runs. */
    internal fun kept(): CheckedAlbum = CheckedAlbum(
        id = album.id,
        marks = marks,
        ready = proposal is Proposal.Ready,
        doubts = (proposal as? Proposal.PickOne)?.doubts.orEmpty(),
        candidates = candidates.map { KeptCandidate(it.release, it.distance) },
        at = checkedAt,
    )
}

/**
 * [album] waiting again with what its last check found ([checked], kept between runs): each candidate paired with the
 * album's songs again on the phone, as the check paired them, at the distance the check found. Pure.
 */
internal fun waitingAgain(album: TagAlbum, checked: CheckedAlbum): Waiting {
    val matches = checked.candidates.map { ReleaseMatch(it.release, AlbumMatch.pair(album.album.songs, it.release.tracks), it.distance) }
    val proposal = matches.singleOrNull()?.takeIf { checked.ready }?.let(Proposal::Ready) ?: Proposal.PickOne(matches, checked.doubts)
    return Waiting(album, proposal, checked.marks, checked.at)
}

/** The item the song sheet asked for, and the song it was opened from (null: the album's own menu). */
data class Focus(val albumId: String, val song: String?)

/**
 * Whether Undo of Done row (History entry) [entryId] must wait: a newer Covers & tags apply, not undone, changed one of
 * its songs since, so it can't go back to the state before it yet. Done rows go back newest first ("Undo the newer
 * change first"). An Edit tags edit since does not block: its fields are kept, field by field.
 */
fun undoBlocked(entryId: Long, history: List<HistoryEntry>): Boolean {
    val entry = history.firstOrNull { it.id == entryId } ?: return false
    val songs = entry.songs.filterNot { it.undone }.map { it.mediaStoreId }.toSet()
    return history.any { newer -> newer.id > entryId && newer.kind == EditKind.COVERS_TAGS && newer.songs.any { !it.undone && it.mediaStoreId in songs } }
}

/** The songs ticked when the review opens: the [song] it was opened from alone; none from the list or an album's menu. */
fun firstTicked(album: TagAlbum, song: String?): Set<String> = setOfNotNull(song?.takeIf { it in album.paths })

/** A suggested cover as a card or the review shows it. */
sealed interface Preview {
    class Loaded(val image: ByteArray) : Preview

    /** The release has no front cover: no cover is shown, and no cover row offered. */
    data object None : Preview

    /** It could not be fetched now: "Couldn't load cover" with Try again, and no cover row offered. */
    data object Failed : Preview
}

/** "Checking 12/84": [done] of [total] albums looked up; [total] is 0 while the library is read. */
data class Checking(val done: Int, val total: Int)

/** An apply: [done] of [total] songs written so far; [cover] while the cover is fetched, before any song. */
data class Applying(val done: Int, val total: Int, val cover: Boolean = false)

/** The Covers & tags page's state. */
data class CoversTagsState(
    /** [memory] is read. */
    val loaded: Boolean = false,
    val checking: Checking? = null,
    /** Albums checked on their own (the song sheet, "Check again"), by id. */
    val checkingAlbums: Set<String> = emptySet(),
    val applying: Applying? = null,
    /**
     * Ready and Pick one, in the order checked, and kept between runs (`covers-tags-checks/`): what a check found stays
     * until the user applies or skips it, or one of the album's files changes.
     */
    val waiting: List<Waiting> = emptyList(),
    val memory: CoversTagsMemory = CoversTagsMemory(),
    /** Albums the last check could not look up: offline, refused, or left when MusicBrainz stayed busy. */
    val notChecked: Int = 0,
    /** The last check stopped because MusicBrainz stayed busy. */
    val stoppedBusy: Boolean = false,
    /** The songs could not be read at all. */
    val unreadable: Boolean = false,
    /** The album the song sheet asked for: the page opens its review, then [CoversTags.focused]. */
    val focus: Focus? = null,
    /** Every edit, the latest first. */
    val history: List<HistoryEntry> = emptyList(),
    /** The releases whose front cover the Cover Art Archive turned out not to have (a preview found none), by id. */
    val noCover: Set<String> = emptySet(),
    /** The History entries Undo can still take back from full copies; the others say "Undo has expired". */
    val fullCopies: Set<Long> = emptySet(),
) {
    val ready: List<Waiting> get() = waiting.filter { it.proposal is Proposal.Ready }

    val pickOne: List<Waiting> get() = waiting.filter { it.proposal is Proposal.PickOne }

    /** The To review tab: what waits with something to suggest. An album with no suggestion is not listed. */
    val toReview: List<Waiting> get() = waiting.filter { it.suggests(noCover) }

    /** The Done tab: one row per apply, the newest first. */
    val done: List<AlbumRecord> get() = memory.albums.filter { it.state == Settled.DONE }.sortedWith(compareByDescending<AlbumRecord> { it.at }.thenByDescending { it.entryId ?: 0 })

    val skipped: List<AlbumRecord> get() = memory.albums.filter { it.state == Settled.SKIPPED }.sortedByDescending { it.at }

    /** The albums to review: the badge on its Tools row. */
    val badge: Int get() = toReview.size
}

/** What an apply did. */
sealed interface Applied {
    /**
     * [songs] songs written, in History entry [entryId] (its Undo); [albums] is 1 when the album moved to Done;
     * [problems] were left as they were. Songs left untried by Cancel are none of these.
     */
    data class Done(val albums: Int, val songs: Int, val problems: List<SongProblem>, val entryId: Long? = null) : Applied

    /** Another apply or Undo is running: nothing was started. */
    data object InProgress : Applied

    /** One of the album's files changed since the check: nothing written, and the album waits for the next check. */
    data object ChangedSince : Applied

    /** No notification access, so the media sessions can't be listed: nothing written. */
    data object NoAccess : Applied

    data object NotEnoughSpace : Applied

    /** The cover ticked could not be fetched: nothing written. */
    data object CoverFailed : Applied

    /** Cancel came before any song was written (during the cover's download, or before the first song): nothing written. */
    data object Stopped : Applied

    /** The album no longer waits: nothing written. */
    data object Gone : Applied
}

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

    /** A newer change to one of its songs stands ([undoBlocked]): nothing was changed. Undo that one first. */
    data object Blocked : Undone
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
 * The tag map Covers & tags writes into a song, by TagLib property name, only for the [parts] ticked: title (with the
 * recording id), the artist as ONE credited name (the names one by one only in ARTISTS), album (with the release id),
 * album artist, track and disc ("3/12" when the total is known), and the album's original year. A blank value is never
 * written, and [cover] goes in only with Cover ticked. Never genre, comments, lyrics or ReplayGain. Pure.
 */
fun coversTagsEdit(fields: SongFields, parts: Set<Part>, cover: CoverEdit): TagEdit {
    fun number(n: Int, total: Int?) = if (total != null) "$n/$total" else n.toString()
    val map = buildMap {
        fun add(part: Part, key: String, values: List<String>) {
            val kept = values.filter(String::isNotBlank)
            if (part in parts && kept.isNotEmpty()) put(key, kept)
        }
        add(Part.TITLE, "TITLE", listOf(fields.title))
        add(Part.TITLE, "MUSICBRAINZ_TRACKID", listOf(fields.musicBrainzRecordingId))
        add(Part.ARTIST, "ARTIST", listOf(fields.artist))
        add(Part.ARTIST, "ARTISTS", fields.artists)
        add(Part.ALBUM, "ALBUM", listOf(fields.album))
        add(Part.ALBUM, "MUSICBRAINZ_ALBUMID", listOf(fields.musicBrainzReleaseId))
        add(Part.ALBUM_ARTIST, "ALBUMARTIST", listOf(fields.albumArtist))
        add(Part.NUMBERS, "TRACKNUMBER", listOf(number(fields.track, fields.trackTotal)))
        add(Part.NUMBERS, "DISCNUMBER", listOf(number(fields.disc, fields.discTotal)))
        fields.year?.let { add(Part.YEAR, "DATE", listOf(it.toString())) }
    }
    return TagEdit(map, cover = if (Part.COVER in parts) cover else CoverEdit.Keep)
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

private const val JPEG_MIME = "image/jpeg"

/** At most this many covers to write stay in memory, and this many previews (small thumbnails, some 30 KB each). */
private const val COVERS_KEPT = 8
private const val PREVIEWS_KEPT = 64

/** [this] song as the library will read it once Covers & tags wrote the [parts] ticked of [fields]. */
private fun Song.written(fields: SongFields, parts: Set<Part>): Song = copy(
    title = if (Part.TITLE in parts) fields.title else title,
    artist = if (Part.ARTIST in parts) fields.artist else artist,
    album = if (Part.ALBUM in parts) fields.album else album,
    albumArtist = if (Part.ALBUM_ARTIST in parts) fields.albumArtist else albumArtist,
    track = if (Part.NUMBERS in parts) fields.track else track,
    disc = if (Part.NUMBERS in parts) fields.disc else disc,
    year = fields.year?.takeIf { Part.YEAR in parts } ?: year,
)

/** The last [limit] values used, by key; safe from any thread. */
private class Recent<T>(private val limit: Int) {
    private val map = object : LinkedHashMap<String, T>(limit, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, T>?): Boolean = size > limit
    }

    operator fun get(key: String): T? = synchronized(map) { map[key] }

    operator fun set(key: String, value: T) = synchronized(map) { map[key] = value }

    private companion object {
        const val LOAD_FACTOR = 0.75f
    }
}

/**
 * Covers & tags: checks the library's albums against MusicBrainz ([source]) one at a time in [scope] while the app is
 * open, never by itself; keeps what waits for the user; applies a release to the songs and the fields the user ticked,
 * and to nothing else, through the one safe writer ([write], T3's [writeEdit], one file at a time with its progress,
 * busy and recovering songs left as they are and listed, favourite hearts following the album's new name); and takes
 * edits back through it ([undoEntry], [undo] holds History). Writing needs notification [access]. After an apply, each
 * song written goes to the [lyrics] with its new names, and every song written or taken back gets a new cover version
 * ([coversChanged]). Done (one row per apply, with its Undo) and Skipped are kept in [store]; a check leaves out the
 * songs they hold until one of them changes. Logs counts only.
 */
class CoversTags(
    private val songs: suspend () -> List<Song>?,
    private val source: AlbumSource,
    /** The one tag writer's write, the hearts it renames moving with it ([writeEdit]): jobs, each song before and after, kind, label, progress. */
    private val write: suspend (List<WriteJob>, List<Pair<Song, Song>>, EditKind, String, Progress) -> BatchResult,
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

    /** One write of a kept check at a time, each of the album as it is then. */
    private val keeping = Mutex()

    /**
     * What earlier checks found, waiting again once the library is read: each album whose files are as they were when it
     * was checked. An album changed or gone since is forgotten, and the next check looks the changed one up again.
     * Nothing is asked of MusicBrainz.
     */
    private val restoring: Job = scope.launch(io) {
        loading.join()
        val kept = store.loadChecks()
        if (kept.isEmpty()) return@launch
        val albums = albumsOf(songs() ?: return@launch).associateBy { it.id }
        val (still, gone) = kept.partition { checked -> albums[checked.id]?.let { it.paths.mapNotNull(files).toSet() == checked.marks.toSet() } == true }
        gone.forEach { store.removeCheck(it.id) }
        val restored = withContext(compute) { still.sortedBy { it.at }.map { waitingAgain(albums.getValue(it.id), it) } }
        _state.update { state -> state.copy(waiting = restored.filter { again -> state.waiting.none { it.album.id == again.album.id } } + state.waiting) }
        log("tags: ${restored.size} albums waiting from earlier checks, ${gone.size} changed or gone")
    }

    /** One apply or Undo at a time: a second one while one runs is refused, never queued behind it. */
    private val writing = Mutex()

    /** Cancel was tapped during the apply running: the writer stops after the song it is on. */
    @Volatile
    private var stopAsked = false

    /** The check running ("Check my albums"); only it changes [CoversTagsState.checking]. */
    @Volatile
    private var running: Job? = null

    private val covers = Recent<Cover>(COVERS_KEPT)
    private val previews = Recent<ByteArray>(PREVIEWS_KEPT)

    /** The release groups whose original year was asked for ([date]). */
    private val asked: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** The cover download of the apply running, which Cancel stops at once. */
    @Volatile
    private var fetching: Job? = null

    // Checking

    /** "Check my albums": every album not done or skipped (or changed since), one at a time; unless one is running. */
    fun check() {
        if (running?.isActive == true) return
        _state.update { it.copy(checking = Checking(0, 0), notChecked = 0, stoppedBusy = false, unreadable = false) }
        val job = scope.launch(compute, start = CoroutineStart.LAZY) {
            val self = coroutineContext.job
            try {
                restoring.join()
                val library = songs()?.let(::albumsOf)
                if (library == null) {
                    _state.update { it.copy(unreadable = true) }
                    return@launch
                }
                forgetGone(library.map { it.id }.toSet())
                val albums = toCheck(library)
                commit { it.copy(checkedOnce = true) }
                lookUp(albums, fresh = false, progress = true)
                log("tags: check done, ${albums.size} of ${library.size} albums checked, ${_state.value.toReview.size} to review")
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
            restoring.join()
            val record = _state.value.memory.albums.firstOrNull { it.id == recordId } ?: return@launch
            val paths = record.songs.map { it.path }.toSet()
            val album = albumsOf(songs()?.filter { it.path in paths } ?: return@launch).maxByOrNull { it.paths.size } ?: return@launch
            // Skipped no more only once the lookup placed the album again.
            if (single(album, fresh = true)) commit { memory -> memory.copy(albums = memory.albums.filterNot { it.id == recordId }) }
        }
    }

    /**
     * Covers & tags from a song's sheet ([fromSong]) or its album's menu: the review of [path]'s album, which the page
     * opens ([CoversTagsState.focus]) with that song alone ticked, or none from the menu. An album not waiting is looked
     * up first (its answers mostly cached); a skipped one is skipped no more once it is placed.
     */
    fun open(path: String, fromSong: Boolean = true) {
        scope.launch(compute) {
            restoring.join()
            val song = path.takeIf { fromSong }
            val known = _state.value.waiting.firstOrNull { path in it.album.paths }?.album?.id
            if (known != null) {
                _state.update { it.copy(focus = Focus(known, song)) }
                return@launch
            }
            val album = albumsOf(songs() ?: return@launch).firstOrNull { path in it.paths } ?: return@launch
            if (single(album, fresh = false, focus = Focus(album.id, song))) {
                commit { memory -> memory.copy(albums = memory.albums.filterNot { it.state == Settled.SKIPPED && it.id == album.id }) }
            }
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
     * The albums of the [library] a check looks up: all but those whose every song is in a settled record unchanged
     * since, and those checked before whose files are as they were then (they wait, or had nothing to suggest). A record
     * without an Undo (Skipped, or Done with nothing changed) one of whose songs is looked up again is forgotten; a Done
     * row with its Undo always stays.
     */
    private suspend fun toCheck(library: List<TagAlbum>): List<TagAlbum> {
        val records = _state.value.memory.albums
        val checked = _state.value.waiting.associate { it.album.id to it.marks.toSet() }
        val (settled, unchanged) = withContext(io) {
            val settled = records.filter { record -> record.songs.all { files(it.path) == it } }.flatMap { it.songs }.map { it.path }.toSet()
            val unchanged = library.filter { album -> checked[album.id]?.let { it.isNotEmpty() && it == album.paths.mapNotNull(files).toSet() } == true }
            settled to unchanged.map { it.id }.toSet()
        }
        val check = library.filterNot { album -> album.id in unchanged || album.paths.all { it in settled } }
        val again = check.flatMap { it.paths }.toSet()
        fun forgotten(record: AlbumRecord) = record.entryId == null && record.songs.any { it.path in again }
        if (records.any(::forgotten)) commit { memory -> memory.copy(albums = memory.albums.filterNot(::forgotten)) }
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
    private suspend fun single(album: TagAlbum, fresh: Boolean, focus: Focus? = null): Boolean {
        _state.update { it.copy(checkingAlbums = it.checkingAlbums + album.id, stoppedBusy = false, focus = focus ?: it.focus) }
        try {
            lookUp(listOf(album), fresh, progress = false)
        } finally {
            _state.update { it.copy(checkingAlbums = it.checkingAlbums - album.id) }
        }
        return waiting(album.id) != null
    }

    /** [album] waits with [proposal], in its own place when it waited already (and keeps that place between runs). */
    private suspend fun place(album: TagAlbum, proposal: Proposal, marks: List<FileMark>) {
        _state.update { state ->
            val at = state.waiting.indexOfFirst { it.album.id == album.id }
            val waiting = Waiting(album, proposal, marks, state.waiting.getOrNull(at)?.checkedAt ?: clock())
            state.copy(waiting = if (at < 0) state.waiting + waiting else state.waiting.toMutableList().also { it[at] = waiting })
        }
        keep(album.id)
    }

    private fun waiting(albumId: String): Waiting? = _state.value.waiting.firstOrNull { it.album.id == albumId }

    /** Keeps album [albumId] as it waits now, or forgets it when it waits no more. */
    private suspend fun keep(albumId: String) {
        keeping.withLock {
            val waiting = waiting(albumId)
            withContext(io) {
                if (waiting == null) store.removeCheck(albumId) else store.saveCheck(waiting.kept())
            }
        }
    }

    /** Albums no longer in the library ([ids]: those it holds now) wait no more, unless one is being checked on its own. */
    private suspend fun forgetGone(ids: Set<String>) {
        val gone = _state.value.waiting.map { it.album.id }.filter { it !in ids && it !in _state.value.checkingAlbums }
        if (gone.isEmpty()) return
        _state.update { state -> state.copy(waiting = state.waiting.filterNot { it.album.id in gone }) }
        gone.forEach { keep(it) }
    }

    // Reviewing

    /** What [match] could change in [waiting]'s songs: the review's "What to change" and "Your songs". */
    fun changes(waiting: Waiting, match: ReleaseMatch): AlbumChanges = TagChanges.of(waiting.album.album, match)

    /**
     * [release]'s front cover for a card or the review: the small thumbnail, kept in memory once fetched (and on disk by
     * the client). A failure is never kept, so Try again asks again.
     */
    suspend fun preview(release: MbRelease): Preview {
        previews[release.id]?.let { return Preview.Loaded(it) }
        return when (val answer = source.preview(release)) {
            is MetaAnswer.Ok -> Preview.Loaded(answer.value).also { previews[release.id] = answer.value }
            MetaAnswer.NotFound -> Preview.None.also { _state.update { it.copy(noCover = it.noCover + release.id) } }
            MetaAnswer.Busy, is MetaAnswer.Failed -> Preview.Failed
        }
    }

    /**
     * The candidate picked in the review, with its album's original year when its answer did not carry it (a recording
     * search lists none): asked once per release group, then it takes its place among the album's candidates, so the
     * Year row can be offered.
     */
    suspend fun date(albumId: String, match: ReleaseMatch) {
        val group = match.release.releaseGroup ?: return
        if (group.firstReleaseDate != null || !asked.add(group.id)) return
        when (val answer = source.dated(match)) {
            is MetaAnswer.Ok -> {
                _state.update { state -> state.copy(waiting = state.waiting.map { if (it.album.id == albumId) it.with(answer.value) else it }) }
                keep(albumId)
            }
            // Not known now: asked again the next time it is picked.
            else -> asked.remove(group.id)
        }
    }

    /** "Not this one": [match] is no longer offered; with no candidate left, the album leaves To review. */
    fun notThisOne(albumId: String, match: ReleaseMatch) {
        _state.update { state ->
            state.copy(
                waiting = state.waiting.map { waiting ->
                    if (waiting.album.id != albumId) return@map waiting
                    val pick = waiting.proposal as? Proposal.PickOne
                    waiting.copy(proposal = Proposal.PickOne(waiting.candidates.filterNot { it.release.id == match.release.id }, pick?.doubts.orEmpty()))
                },
            )
        }
        scope.launch(io) { keep(albumId) }
    }

    /** "Search myself" with the user's [title] and [artist]: its candidates take the album's place. */
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
        settle(record, waiting.album.id)
        return true
    }

    /**
     * A backup's settled albums (backup/, a restore): each added unless a record of its id is here (a Done row's id is
     * its apply's, so a second restore adds none); their Undo stays on the old phone. The flags set when either says so.
     * False when not stored.
     */
    suspend fun restoreSettled(albums: List<AlbumRecord>, checkedOnce: Boolean, noted: Boolean): Boolean = commit { memory ->
        val known = memory.albums.map { it.id }.toSet()
        memory.copy(albums = memory.albums + albums.filterNot { it.id in known }, checkedOnce = memory.checkedOnce || checkedOnce, noted = memory.noted || noted)
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
        stopAsked = false
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

    /**
     * "Change 3 songs" in the review: [match] written into the [songs] ticked (paths of the album's own files), only its
     * [parts] ticked, and nothing else. Progress shows as [CoversTagsState.applying]; [stopApplying] stops it after the
     * song being written.
     */
    suspend fun apply(albumId: String, match: ReleaseMatch, songs: Set<String>, parts: Set<Part>): Applied {
        if (waiting(albumId) == null) return Applied.Gone
        if (!access()) return Applied.NoAccess
        return writingAlone(Applying(0, songs.size, cover = Part.COVER in parts)) {
            // The album may have been applied meanwhile.
            waiting(albumId)?.let { applyOne(it, match, songs, parts) } ?: Applied.Gone
        } ?: Applied.InProgress
    }

    /** "Cancel" while applying: the cover's download stops at once; a song being written is finished, the rest left as they are. */
    fun stopApplying() {
        if (_state.value.applying == null) return
        stopAsked = true
        fetching?.cancel()
    }

    private suspend fun applyOne(waiting: Waiting, match: ReleaseMatch, ticked: Set<String>, parts: Set<Part>): Applied {
        // A file replaced since the check would get another song's names: the album waits for the next check.
        if (marks(waiting.album) != waiting.marks) {
            _state.update { state -> state.copy(waiting = state.waiting.filterNot { it.album.id == waiting.album.id }) }
            keep(waiting.album.id)
            log("tags: album changed since the check, not written")
            return Applied.ChangedSince
        }
        val album = waiting.album
        val changes = TagChanges.of(album.album, match)
        val (known, unknown) = changes.songs.filter { it.song.path in ticked }.partition { it.song.path in album.ids }
        // The 1200 px cover, fetched only now, and only when Cover is ticked; Cancel stops its download.
        val cover = if (Part.COVER in parts) {
            when (val answer = download(match.release) ?: return Applied.Stopped) {
                is MetaAnswer.Ok -> answer.value
                MetaAnswer.NotFound -> null
                MetaAnswer.Busy, is MetaAnswer.Failed -> return Applied.CoverFailed
            }
        } else {
            null
        }
        if (stopAsked) return Applied.Stopped
        // Opus songs the album's cover is too big for get a smaller one, fetched only for them.
        val opus = if (cover != null && known.any { !coverFits(it.song.path, cover.jpeg.size) }) opusCover(match.release) else null
        val jobs = known.map { change ->
            val path = change.song.path
            WriteJob(SongTarget(path, album.ids.getValue(path)), coversTagsEdit(change.fields, parts, coverFor(path, cover, opus)))
        }
        _state.update { it.copy(applying = Applying(0, jobs.size)) }
        // Each song as the library reads it now and as it will once written: a favourite album follows its new name.
        val before = songs().orEmpty().associateBy { it.path }
        val changed = known.mapNotNull { change -> before[change.song.path]?.let { it to it.written(change.fields, parts) } }
        val progress = Progress(song = { done -> _state.update { it.copy(applying = Applying(done, jobs.size)) } }, stop = { stopAsked })
        return when (val result = write(jobs, changed, EditKind.COVERS_TAGS, match.release.title, progress)) {
            BatchResult.SessionsUnknown -> Applied.NoAccess
            BatchResult.NotEnoughSpace -> Applied.NotEnoughSpace
            // An Undo's answer only: a write never gives it.
            BatchResult.HistoryUnreadable -> Applied.CoverFailed
            is BatchResult.Done -> settled(waiting, match.release, changes, parts, result, unknown.map { SongProblem(it.song.path, SongOutcome.COULD_NOT_CHANGE) })
        }
    }

    /** [release]'s cover to write, as [fetching], so Cancel stops it; null once Cancel did. */
    private suspend fun download(release: MbRelease): MetaAnswer<Cover>? = coroutineScope {
        if (stopAsked) return@coroutineScope null
        val fetch = async { coverOf(release) }
        fetching = fetch
        if (stopAsked) fetch.cancel()
        try {
            fetch.await()
        } catch (e: CancellationException) {
            // Cancel stopped the download; any other cancellation goes on up.
            if (!fetch.isCancelled || !stopAsked || !isActive) throw e
            null
        } finally {
            fetching = null
        }
    }

    /** [release]'s cover to write, kept in memory a while. */
    private suspend fun coverOf(release: MbRelease): MetaAnswer<Cover> {
        covers[release.id]?.let { return MetaAnswer.Ok(it) }
        return source.cover(release).also { answer -> if (answer is MetaAnswer.Ok) covers[release.id] = answer.value }
    }

    /** [release]'s smaller cover for Opus songs, or null when even the smallest won't fit (or it can't be fetched now). */
    private suspend fun opusCover(release: MbRelease): Cover? {
        val key = OPUS_KEY + release.id
        covers[key]?.let { return it }
        return (source.opusCover(release) as? MetaAnswer.Ok)?.value?.also { covers[key] = it }
    }

    /**
     * After the writer: a Done row for this apply when a song was written (with its Undo), or a row saying nothing had to
     * change when nothing needed to be; either way the album leaves To review, and its other songs come back with the
     * next check. With every ticked song left as it was, the album waits where it was. Songs Cancel left untried are no
     * problem. The songs written get new cover versions and go to the lyrics.
     */
    private suspend fun settled(waiting: Waiting, release: MbRelease, changes: AlbumChanges, parts: Set<Part>, result: BatchResult.Done, missing: List<SongProblem>): Applied {
        val written = result.songs.filter { it.outcome == SongOutcome.WRITTEN }.map { it.song.path }
        val untried = SongOutcome.NOT_TRIED.takeIf { stopAsked }
        val fine = setOfNotNull(SongOutcome.WRITTEN, SongOutcome.UNCHANGED, untried)
        val problems = result.songs.filter { it.outcome !in fine }.map { SongProblem(it.song.path, it.outcome) } + missing
        if (written.isNotEmpty()) coversChanged(written)
        // Cancelled before any song was written: the album waits where it was.
        val done = written.isNotEmpty() || (problems.isEmpty() && result.songs.any { it.outcome == SongOutcome.UNCHANGED })
        if (done) {
            val tried = result.songs.filter { it.outcome == SongOutcome.WRITTEN || it.outcome == SongOutcome.UNCHANGED }.map { it.song.path }
            settle(
                AlbumRecord(
                    id = result.entryId?.let { "${waiting.album.id}#$it" } ?: waiting.album.id,
                    state = Settled.DONE,
                    title = release.title,
                    artist = release.artist.text,
                    songs = withContext(io) { tried.mapNotNull(files) },
                    at = clock(),
                    entryId = result.entryId,
                    changed = written.size,
                    problems = problems,
                ),
                waiting.album.id,
            )
        }
        if (written.isNotEmpty()) {
            val paths = written.toSet()
            scope.launch(io) { lyricsAfter(changes, parts, paths) }
            readHistory()
        }
        log("tags: applied, ${written.size} songs written, ${problems.size} left")
        if (written.isEmpty() && problems.isEmpty() && stopAsked) return Applied.Stopped
        return Applied.Done(if (done) 1 else 0, written.size, problems, result.entryId)
    }

    /** Each song [written] goes to the lyrics with its names as written, and whether its normalised title or artist changed. */
    private suspend fun lyricsAfter(changes: AlbumChanges, parts: Set<Part>, written: Set<String>) {
        for (change in changes.songs) {
            val song = change.song
            if (song.path !in written) continue
            val title = if (Part.TITLE in parts) change.fields.title else song.title.orEmpty()
            val artist = if (Part.ARTIST in parts) change.fields.artist else song.artist
            val album = if (Part.ALBUM in parts) change.fields.album else song.album
            lyrics(LocalTrack(song.path, title, artist, album, song.durationMs), namesChanged(song.title, song.artist, title, artist))
        }
    }

    // Undo and History

    /** "Undo" on a Done row: the songs of its apply, put back. */
    suspend fun undoAlbum(recordId: String): Undone {
        val record = _state.value.memory.albums.firstOrNull { it.id == recordId && it.state == Settled.DONE } ?: return Undone.Gone
        val entry = record.entryId ?: return Undone.Gone
        return undo(entry)
    }

    /**
     * "Undo" on History entry [entryId]: each song from its full copy, or field by field where a field still holds the
     * edit's value (one changed again since is kept). Its Done row is forgotten once nothing is left to take back.
     */
    suspend fun undo(entryId: Long): Undone {
        if (!access()) return Undone.NoAccess
        return writingAlone(null) {
            // Newest first: History as it is now, read again.
            if (undoBlocked(entryId, withContext(io) { undo.memory().entries })) Undone.Blocked else undoing(entryId)
        } ?: Undone.InProgress
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
     * [memory] with a Done row for each Covers & tags History entry newer than [CoversTagsMemory.historySeen] that has
     * none: the app died after the files were written and before `covers-tags.json` was. One row per entry, with its own
     * Undo, as every apply has. A file from before historySeen was kept rebuilds nothing (what it settled is unknown):
     * from then on only entries newer than the newest one now count. History that can't be read rebuilds nothing, and
     * decides nothing. Blocking.
     */
    private fun withLostDone(memory: CoversTagsMemory): CoversTagsMemory {
        val entries = try {
            undo.whole().entries
        } catch (e: IOException) {
            log("tags: History could not be read (${e.javaClass.simpleName}), no Done row rebuilt")
            return memory
        }
        val seen = memory.historySeen ?: return memory.copy(historySeen = entries.maxOfOrNull { it.id } ?: 0)
        val lost = entries.filter { entry ->
            entry.kind == EditKind.COVERS_TAGS && entry.id > seen && entry.songs.any { !it.undone } && memory.albums.none { it.entryId == entry.id }
        }
        if (lost.isEmpty()) return memory
        val rebuilt = lost.map { entry ->
            val paths = entry.songs.map { it.path }
            AlbumRecord("${albumId(paths)}#${entry.id}", Settled.DONE, entry.label, songs = paths.mapNotNull(files), at = entry.at, entryId = entry.id, changed = entry.songs.size)
        }
        log("tags: ${rebuilt.size} Done rows rebuilt from History")
        return memory.copy(albums = memory.albums + rebuilt, historySeen = maxOf(seen, lost.maxOf { it.id }))
    }

    /** [record] replaces its own (same id) and the album's Skipped record, and the album waits no more. */
    private suspend fun settle(record: AlbumRecord, albumId: String) {
        commit { memory ->
            val kept = memory.albums.filterNot { it.id == record.id || (it.state == Settled.SKIPPED && it.id == albumId) }
            memory.copy(albums = kept + record, historySeen = maxOf(memory.historySeen ?: 0, record.entryId ?: 0))
        }
        _state.update { state -> state.copy(waiting = state.waiting.filterNot { it.album.id == albumId }) }
        keep(albumId)
    }

    /** Changes the memory and stores it; the state has the change even when it could not be stored. */
    private suspend fun commit(change: (CoversTagsMemory) -> CoversTagsMemory): Boolean = saving.withLock {
        loading.join()
        val memory = change(_state.value.memory)
        _state.update { it.copy(memory = memory) }
        withContext(io) { store.save(memory) }
    }

    private companion object {
        const val OPUS_KEY = "opus:"
    }
}
