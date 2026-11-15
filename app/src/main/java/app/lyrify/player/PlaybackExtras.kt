package app.lyrify.player

import android.content.Context
import android.util.Log
import androidx.datastore.dataStoreFile
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.session.MediaLibraryService
import app.lyrify.LOG_TAG
import app.lyrify.Lyrify
import app.lyrify.books.BooksGraph
import app.lyrify.books.bookSpeeds
import app.lyrify.books.playsAsBook
import app.lyrify.library.LibraryGraph
import app.lyrify.library.LibraryState
import app.lyrify.library.SongRef
import app.lyrify.logFailures
import app.lyrify.settings.PlaybackSettings
import app.lyrify.settings.RememberPlace
import app.lyrify.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

// Speed and pitch with a speed of a song's own, Repeat a part (A-B), bookmarks and Remember my place (milestone P2): the
// rules as pure functions over [SongState] (kept in `song-state.json`, SongStateStore.kt), [PlayerExtras] (what the
// screens and the service share), and [ExtrasDriver], which applies them to the player in the service.

/** The Speed sheet's chips; its slider moves in steps of 0.05 ([SPEED_STEPS_PER_X] to 1×) from the first to the last. */
val SPEEDS: List<Float> = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
const val SPEED_STEPS_PER_X: Int = 20

/** The pitch slider, in semitones. */
val SEMITONES: IntRange = -6..6

/**
 * "Speed": how fast songs play, with their pitch kept ([keepPitch]) or set in [semitones] from the speed's own (a song
 * played faster sounds higher; 0 leaves it so).
 */
data class Speed(val speed: Float = 1f, val keepPitch: Boolean = true, val semitones: Int = 0) {
    /** The pitch the player gets: 1 when kept, else the speed's own moved by [semitones]. */
    val pitch: Float get() = if (keepPitch) 1f else speed * 2f.pow(semitones / 12f)

    /** Whether the player shows its speed chip: anything that does not sound as the song does. */
    val shows: Boolean get() = speed != 1f || (!keepPitch && semitones != 0)

    /** [speed] in steps of 0.05 from 0.5 to 2, [semitones] within [SEMITONES]. */
    fun normalised(): Speed = copy(
        speed = ((speed * SPEED_STEPS_PER_X).roundToInt() / SPEED_STEPS_PER_X.toFloat()).coerceIn(SPEEDS.first(), SPEEDS.last()),
        semitones = semitones.coerceIn(SEMITONES),
    )
}

/** A bookmark: a spot in a song, with the user's note (maybe empty). [id] is its own for good. */
data class Bookmark(val id: Long, val positionMs: Long, val note: String)

/** "Repeat a part": the part of the song at [path] from [startMs] (A) to [endMs] (B), in whole seconds, that loops. */
data class AbPart(val path: String, val startMs: Long, val endMs: Long)

/**
 * What KJB keeps per song: its own speed ("This song only"), its place (Remember my place, the oldest first) and its
 * bookmarks (in time order); the [usual] speed of every other song; and the part repeating, if any. Songs are known as
 * queues, Favourites and the play log know them ([SongRef]): by path, with MediaStore's id as a hint ([ids]), so a moved
 * file is found again when the library next loads ([refresh]) and its state follows it.
 */
data class SongState(
    val usual: Speed = Speed(),
    val speeds: Map<String, Speed> = emptyMap(),
    val places: Map<String, Long> = emptyMap(),
    val bookmarks: Map<String, List<Bookmark>> = emptyMap(),
    val part: AbPart? = null,
    val nextBookmarkId: Long = 1,
    /** MediaStore's id of each song kept here, by path: a hint only, as in [SongRef]. */
    val ids: Map<String, Long> = emptyMap(),
) {
    /** The speed the song at [path] plays at: its own, else the usual one. */
    fun speedOf(path: String?): Speed = path?.let(speeds::get) ?: usual

    /** The bookmarks of the song at [path], in time order. */
    fun bookmarksOf(path: String?): List<Bookmark> = path?.let(bookmarks::get).orEmpty()

    /** [change] applied to the speed [song] plays at: its own when it has one, else the usual one. */
    fun withSpeed(song: SongRef?, change: (Speed) -> Speed): SongState {
        val own = song?.path?.let(speeds::get)
        if (song == null || own == null) return copy(usual = change(usual).normalised())
        return copy(speeds = speeds + (song.path to change(own).normalised())).known(song)
    }

    /**
     * "This song only" switched [on] for [song]: the speed set now becomes the song's own, and the usual speed goes back
     * to [usualBefore] (what it was when the sheet opened); off, the song plays at the usual speed again. At most
     * [MAX_SPEEDS] songs keep their own speed; the oldest go first.
     */
    fun thisSongOnly(song: SongRef, on: Boolean, usualBefore: Speed): SongState = when {
        !on -> copy(speeds = speeds - song.path).known(song)
        song.path in speeds -> this
        else -> copy(speeds = (speeds + (song.path to usual)).entries.toList().takeLast(MAX_SPEEDS).associate { it.key to it.value }, usual = usualBefore.normalised()).known(song)
    }

    /**
     * A bookmark at [positionMs] (to the second) in [song], with [note] trimmed; the new bookmark too. Null when the song
     * has [MAX_BOOKMARKS_PER_SONG] already, or all songs together [MAX_BOOKMARKS].
     */
    fun withBookmark(song: SongRef, positionMs: Long, note: String): Pair<SongState, Bookmark>? {
        if (bookmarksOf(song.path).size >= MAX_BOOKMARKS_PER_SONG || bookmarks.values.sumOf { it.size } >= MAX_BOOKMARKS) return null
        val mark = Bookmark(nextBookmarkId, wholeSeconds(positionMs.coerceAtLeast(0)), note.trim().take(MAX_NOTE))
        val list = (bookmarksOf(song.path) + mark).sortedWith(compareBy(Bookmark::positionMs, Bookmark::id))
        return copy(bookmarks = bookmarks + (song.path to list), nextBookmarkId = nextBookmarkId + 1).known(song) to mark
    }

    fun withoutBookmark(path: String, id: Long): SongState {
        val list = bookmarksOf(path)
        if (list.none { it.id == id }) return this
        val left = list.filterNot { it.id == id }
        return copy(bookmarks = if (left.isEmpty()) bookmarks - path else bookmarks + (path to left)).known(SongRef(path, ids[path]))
    }

    /** The place kept for [song]: [positionMs], or none when null; at most [MAX_PLACES], the oldest go first. */
    fun withPlace(song: SongRef, positionMs: Long?): SongState {
        val others = places - song.path
        if (positionMs == null) return if (song.path in places) copy(places = others).known(song) else this
        // A map keeps its order: the place kept now goes last, and the oldest go once there are too many.
        return copy(places = (others + (song.path to positionMs)).entries.toList().takeLast(MAX_PLACES).associate { it.key to it.value }).known(song)
    }

    /**
     * After a library read: each song kept here found again by [find] (its path, then its id hint, as queues and
     * Favourites do). A song at a new path takes its state there; a song no longer on the phone leaves.
     */
    fun refresh(find: (SongRef) -> SongRef?): SongState {
        val kept = (places.keys + speeds.keys + bookmarks.keys + listOfNotNull(part?.path)).distinct()
        val now = kept.associateWith { path -> find(SongRef(path, ids[path])) }
        if (now.all { (path, found) -> found?.path == path && found.mediaStoreId == ids[path] }) return this
        val to: (String) -> String? = { path -> now[path]?.path }

        // In order; a song moved onto one that keeps a value of its own leaves that value as it is.
        fun <V> moved(map: Map<String, V>): Map<String, V> = buildMap {
            map.forEach { (path, value) ->
                val at = to(path) ?: return@forEach
                if (at != path && at in map && to(at) == at) return@forEach
                if (at !in this) put(at, value)
            }
        }
        val marks = buildMap<String, List<Bookmark>> {
            bookmarks.forEach { (path, list) ->
                val at = to(path) ?: return@forEach
                put(at, (get(at).orEmpty() + list).distinctBy(Bookmark::id).sortedWith(compareBy(Bookmark::positionMs, Bookmark::id)))
            }
        }
        val hints = now.values.filterNotNull().mapNotNull { found -> found.mediaStoreId?.let { found.path to it } }.toMap()
        val moved = copy(places = moved(places), speeds = moved(speeds), bookmarks = marks, part = part?.let { p -> to(p.path)?.let { p.copy(path = it) } })
        return moved.copy(ids = hints.filterKeys(moved::keeps))
    }

    /** Whether the song at [path] has anything kept here. */
    private fun keeps(path: String): Boolean = path in places || path in speeds || path in bookmarks || part?.path == path

    /** [song]'s id hint noted; only songs that keep anything keep a hint, so one left with nothing (or capped) lets it go. */
    private fun known(song: SongRef): SongState {
        val noted = song.mediaStoreId?.let { ids + (song.path to it) } ?: ids
        return copy(ids = noted.filterKeys(::keeps))
    }

    companion object {
        const val MAX_PLACES: Int = 300
        const val MAX_SPEEDS: Int = 300
        const val MAX_BOOKMARKS_PER_SONG: Int = 100
        const val MAX_BOOKMARKS: Int = 1_000
        const val MAX_NOTE: Int = 200
    }
}

/**
 * The state after a library read ([LibraryState]): songs found again ([SongState.refresh]). Not before the library is
 * read, and never on an empty or unreadable library, which would drop every song's state.
 */
fun SongState.followLibrary(library: LibraryState): SongState {
    val read = (library as? LibraryState.Ready)?.library?.takeIf { it.all.isNotEmpty() } ?: return this
    return refresh { read.resolver.find(it)?.ref }
}

/** Where the progress bar shows the bookmarks of a song [durationMs] long: shares of its length, once each. */
fun bookmarkTicks(bookmarks: List<Bookmark>, durationMs: Long): List<Float> =
    if (durationMs <= 0) emptyList() else bookmarks.map { (it.positionMs.toFloat() / durationMs).coerceIn(0f, 1f) }.distinct()

/** [ms] down to the second. */
private fun wholeSeconds(ms: Long): Long = ms / 1_000 * 1_000

// Repeat a part (A-B)

/** The shortest part, and how close B may come to the end. */
const val PART_MIN_MS: Long = 2_000
const val PART_END_GAP_MS: Long = 1_000
const val PART_FIRST_MS: Long = 10_000

/**
 * "Repeat a part" at [positionMs] in a song [durationMs] long: A at the second playing, B 10 s later; near the end, the
 * last 10 s before the final second. Null when the song is too short or its length unknown.
 */
fun abPart(path: String, positionMs: Long, durationMs: Long): AbPart? {
    val last = wholeSeconds(durationMs - PART_END_GAP_MS)
    if (last < PART_MIN_MS) return null
    val start = wholeSeconds(positionMs.coerceAtLeast(0))
    if (start + PART_FIRST_MS <= last) return AbPart(path, start, start + PART_FIRST_MS)
    return AbPart(path, (last - PART_FIRST_MS).coerceAtLeast(0), last)
}

/** A dragged to [ms]: within the start and 2 s before B, to the second. */
fun AbPart.withStart(ms: Long): AbPart = copy(startMs = wholeSeconds(ms.coerceAtLeast(0)).coerceAtMost(endMs - PART_MIN_MS))

/** B dragged to [ms]: within 2 s after A and the song's final second, to the second. */
fun AbPart.withEnd(ms: Long, durationMs: Long): AbPart =
    copy(endMs = wholeSeconds(ms).coerceAtMost(wholeSeconds(durationMs - PART_END_GAP_MS)).coerceAtLeast(startMs + PART_MIN_MS))

/** Where the player goes from [positionMs] in [part]'s song: back to A once B is reached; null to play on. */
fun loopTo(part: AbPart, positionMs: Long): Long? = part.startMs.takeIf { positionMs >= part.endMs }

/** Where the player goes when [part] changed: to A when it plays outside the part; null when it is inside. */
fun intoPart(part: AbPart, positionMs: Long): Long? = part.startMs.takeIf { positionMs < part.startMs || positionMs > part.endMs }

// Remember my place

/** Long songs, for "Remember my place": 10 minutes or longer. */
const val LONG_SONG_MS: Long = 600_000
const val PLACE_FROM_MS: Long = 5_000
const val PLACE_BEFORE_END_MS: Long = 10_000

/** Whether a song [durationMs] long resumes where it was left, in [mode]. */
fun remembers(mode: RememberPlace, durationMs: Long): Boolean = when (mode) {
    RememberPlace.LONG_SONGS -> durationMs >= LONG_SONG_MS
    RememberPlace.ALL_SONGS -> durationMs > 0
    RememberPlace.OFF -> false
}

/** The place to keep for a song left at [positionMs]: null (forget it) unless it remembers, past 5 s and over 10 s from the end. */
fun placeToKeep(mode: RememberPlace, durationMs: Long, positionMs: Long): Long? =
    positionMs.takeIf { remembers(mode, durationMs) && it > PLACE_FROM_MS && it < durationMs - PLACE_BEFORE_END_MS }

/** Where a song starting at [startMs] resumes: its [kept] place, when it starts from the beginning and remembers places. */
fun resumeAt(mode: RememberPlace, durationMs: Long, kept: Long?, startMs: Long): Long? {
    if (kept == null || startMs >= FROM_THE_START_MS) return null
    return placeToKeep(mode, durationMs, kept)
}

/** Whether a song that started at [startMs] stands at its [kept] place already (crossfade loads the next song there). */
fun startedAtPlace(mode: RememberPlace, durationMs: Long, kept: Long, startMs: Long): Boolean =
    placeToKeep(mode, durationMs, kept) != null && abs(startMs - kept) < FROM_THE_START_MS

/** A song that starts this close to its beginning starts from it. */
private const val FROM_THE_START_MS: Long = 1_000

// Jumps and the seek buttons

/**
 * A bookmark's jump: [song] plays at [positionMs] inside the queue playing; a song not in it goes there with Play next
 * first ([picked]); without a queue, a new one holds it.
 */
fun QueueBook.toSpot(song: SongRef, positionMs: Long, picked: QueueSource, now: Long): Switched {
    val holds = current?.items?.any { it.song.path == song.path } == true
    val book = if (holds) this else playNext(listOf(song), picked, now)?.book ?: this
    val queue = book.current ?: return Switched(this, toRecent = false)
    val index = queue.items.indexOfFirst { it.song.path == song.path }
    if (index < 0) return Switched(this, toRecent = false)
    val jumped = book.jump(queue.id, index, now)
    return jumped.copy(book = jumped.book.place(index, positionMs))
}

/** The uid of the song at [position] in queue [queueId]: the song sheet's "Remove from queue". */
fun QueueBook.uidAt(queueId: String, position: Int): Long? = queue(queueId)?.items?.getOrNull(position)?.uid

/** The session's seek back and forward (Bluetooth, the lock screen): the player's own buttons ("Skip amounts"). */
fun skipAmounts(playback: PlaybackSettings): SkipAmounts = SkipAmounts(playback.skipBackSeconds * 1_000L, playback.skipForwardSeconds * 1_000L)

// The player, for the service's drivers

/**
 * What the sleep timer and the extras need from the player, beyond its songs and place ([PlayerPort]): whether it plays,
 * the song's length, its speed and volume. The service's engine in the app, a fake in tests. Main thread.
 */
interface ExtrasPort : PlayerPort {
    val playing: Boolean

    /** The current song's length as decoded; 0 or less while unknown. */
    val durationMs: Long

    val speed: Float

    fun setPlayWhenReady(play: Boolean)

    fun setSpeed(speed: Float, pitch: Float)

    /** 0 to 1: the sleep timer's fade. */
    var volume: Float
}

// What the screens and the service share

/** What the service tells the screens, as toasts while they show. */
sealed interface ExtrasSaid {
    /** Remember my place: the song at [path] picked up at [positionMs] ("Resumed at 23:10", with Start over). */
    data class Resumed(val path: String, val positionMs: Long) : ExtrasSaid

    /** The sleep timer paused the music. */
    data object SleepEnded : ExtrasSaid
}

/**
 * The extras of the whole app, one per process ([of]): the [state] kept in `song-state.json` (speeds, places,
 * bookmarks, the part repeating), the [sleep] timer, and what the service [said]. The player's sheets change them; the
 * service's drivers apply them to the player. Read from storage once ([ready]); every change is stored. Main thread.
 */
class PlayerExtras(private val storage: SongStateStorage, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(SongState())
    val state: StateFlow<SongState> = _state.asStateFlow()

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    /** The sleep timer: set from the player, run and ended by the service ([SleepDriver]). */
    val sleep: MutableStateFlow<SleepTimer?> = MutableStateFlow(null)

    private val _said = MutableSharedFlow<ExtrasSaid>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val said: SharedFlow<ExtrasSaid> = _said.asSharedFlow()

    /** A song about to start at a bookmark: it does not also resume at its kept place. */
    var jumpingTo: String? = null

    /**
     * The song the player was just parked at, paused at its start (the queue played to its end, the sleep timer after
     * its last song), by uid: it does not resume at a kept place either.
     */
    var parked: Long? = null

    init {
        scope.launch {
            _state.value = storage.load()
            // Listening before the changes that waited for the read come in.
            launch(start = CoroutineStart.UNDISPATCHED) { _state.drop(1).collect { storage.save(it) } }
            _ready.value = true
        }
    }

    /** [change] applied to the state; before it is read, once it is. */
    fun change(change: (SongState) -> SongState) {
        if (_ready.value) {
            _state.value = change(_state.value)
        } else {
            scope.launch {
                _ready.first { it }
                _state.value = change(_state.value)
            }
        }
    }

    /** [change] applied now, for a result the caller shows; null and nothing changed before the state is read, or when [change] says null. */
    fun <T> edit(change: (SongState) -> Pair<SongState, T>?): T? {
        if (!_ready.value) return null
        val (state, result) = change(_state.value) ?: return null
        _state.value = state
        return result
    }

    fun say(said: ExtrasSaid) {
        _said.tryEmit(said)
    }

    companion object {
        private var made: PlayerExtras? = null

        /** The app's extras: app-private `files/datastore/song-state.json`, a copy of an unreadable file in `files`. */
        @Synchronized
        fun of(context: Context): PlayerExtras = made ?: run {
            val app = context.applicationContext
            val log: (String) -> Unit = { Log.w(LOG_TAG, it) }
            val store = songStateStore(app.dataStoreFile("song-state.json"), app.filesDir, CoroutineScope(Dispatchers.IO + SupervisorJob()), System::currentTimeMillis, log)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + logFailures)
            PlayerExtras(SongStateFiles(store, Dispatchers.IO, log), scope).also { created ->
                // Each library read: a moved song's state follows it, and songs gone leave (never on an empty read).
                scope.launch {
                    created.ready.first { it }
                    LibraryGraph.repository(app).state.collect { library -> created.change { it.followLibrary(library) } }
                }
            }
        }.also { made = it }
    }
}

/**
 * A song the player holds: its queue item's [uid], the song as the library has it now, and its length (0 when unknown).
 * [inBook] while it plays in a book's queue: `books.json` keeps its place and speed (books/BookPlayback.kt).
 */
data class Playing(val uid: Long, val song: SongRef, val durationMs: Long, val inBook: Boolean = false) {
    val path: String get() = song.path
}

/**
 * The extras on the player ([port]), in the service: each song plays at its speed, the part repeating loops between A
 * and B, and Remember my place keeps a song's place when it is left or paused and picks it up when the song starts from
 * its beginning ([PlayerExtras.say] tells the screens). [songOf] names the songs the player holds; [mode] is Remember my
 * place. Main thread.
 */
class ExtrasDriver(
    private val port: ExtrasPort,
    private val extras: PlayerExtras,
    private val songOf: (Long) -> Playing?,
    /** The speed of the book playing, null while music plays: a book plays at its own speed (books/BookPlayback.kt). */
    private val bookSpeed: Flow<Speed?> = flowOf(null),
    private val mode: () -> RememberPlace,
) {
    private var current: Playing? = null

    /** Where the song playing was last seen: at a song change the player is in the next one already. */
    private var lastMs: Long = 0
    private val path = MutableStateFlow<String?>(null)
    private val playing = MutableStateFlow(false)

    /** Runs until cancelled. */
    suspend fun run() = coroutineScope {
        launch { combine(path, extras.state, bookSpeed) { song, state, book -> book ?: state.speedOf(song) }.distinctUntilChanged().collect { port.setSpeed(it.speed, it.pitch) } }
        // A part set or moved while its song plays takes the player into it; the loop runs while the song plays.
        val part = combine(path, extras.state.map { it.part }.distinctUntilChanged()) { song, part -> part?.takeIf { it.path == song } }
        launch {
            extras.state.map { it.part }.distinctUntilChanged().collect { moved ->
                if (moved != null && moved.path == path.value) intoPart(moved, port.positionMs)?.let { port.seek(port.index, it) }
            }
        }
        launch {
            combine(part, playing) { on, playing -> on.takeIf { playing } }.collectLatest { looping ->
                if (looping == null) return@collectLatest
                while (true) {
                    loopTo(looping, port.positionMs)?.let { port.seek(port.index, it) }
                    delay(((looping.endMs - port.positionMs) / port.speed.coerceAtLeast(MIN_LOOP_SPEED)).toLong().coerceIn(LOOP_MIN_WAIT_MS, LOOP_MAX_WAIT_MS))
                }
            }
        }
        launch {
            playing.collectLatest { on ->
                while (on) {
                    lastMs = port.positionMs
                    delay(LOOK_EVERY_MS)
                }
            }
        }
    }

    /** The song [uid] started ([EngineListener.onSongStarted]). */
    fun songStarted(uid: Long, lap: Boolean, natural: Boolean) {
        val looping = extras.state.value.part
        val parked = extras.parked == uid
        extras.parked = null
        // A part repeating keeps its song: one that played to its end (past the loop) goes back to A, not on.
        current?.takeIf { natural && looping?.path == it.path }?.let { left ->
            val at = port.loaded.indexOf(left.uid)
            if (at >= 0) {
                port.seek(at, looping!!.startMs)
                lastMs = looping.startMs
                return
            }
        }
        current?.let { left ->
            // Played to its end (or round again for Repeat one): its place goes.
            if (left.uid != uid || lap) keep(left, if (natural) left.durationMs else lastMs)
        }
        val song = songOf(uid)
        current = song
        lastMs = port.positionMs
        path.value = song?.path
        if (song == null) return
        if (extras.state.value.part?.let { it.path != song.path } == true) extras.change { it.copy(part = null) }
        val jump = extras.jumpingTo == song.path
        extras.jumpingTo = null
        // Not a new play: a lap, a bookmark's jump, a park at the start, or a song held by its part. A book's part
        // starts where its book's place says (books/BookPlayback.kt), never at a place of its own.
        if (lap || jump || parked || song.inBook || extras.state.value.part?.path == song.path) return
        val kept = extras.state.value.places[song.path] ?: return
        // Come in at its place already (crossfade loads the next song there): nothing to move, only the toast.
        if (natural && startedAtPlace(mode(), song.durationMs, kept, port.positionMs)) return extras.say(ExtrasSaid.Resumed(song.path, kept))
        val at = resumeAt(mode(), song.durationMs, kept, port.positionMs) ?: return
        port.seek(port.index, at)
        lastMs = at
        extras.say(ExtrasSaid.Resumed(song.path, at))
    }

    fun playingChanged(playing: Boolean) {
        this.playing.value = playing
        if (playing) extras.parked = null
        lastMs = port.positionMs
        if (!playing) current?.let { keep(it, lastMs) }
    }

    /** A seek inside the song playing: past B of its part, it goes back to A at once (before A, it plays on into the part). */
    fun seeked(toMs: Long) {
        lastMs = toMs
        val part = extras.state.value.part?.takeIf { it.path == current?.path } ?: return
        loopTo(part, toMs)?.let {
            port.seek(port.index, it)
            lastMs = it
        }
    }

    /** The queue played to its end with Repeat off: the player waits at its first song, which is parked there. */
    fun ended() {
        extras.parked = port.loaded.getOrNull(port.index)
    }

    /** The service stops: the place of the song playing is kept. */
    fun stopped() {
        current?.let { keep(it, port.positionMs) }
    }

    /** The place of [song] left at [positionMs], or none, when that changes anything; never for a book's part. */
    private fun keep(song: Playing, positionMs: Long) {
        if (song.inBook) return
        val place = placeToKeep(mode(), song.durationMs, positionMs)
        if (extras.state.value.places[song.path] != place) extras.change { it.withPlace(song.song, place) }
    }

    private companion object {
        /** How often the position is noted while playing. */
        const val LOOK_EVERY_MS: Long = 1_000

        /** The loop looks again at most this often near B, and at least this often. */
        const val LOOP_MIN_WAIT_MS: Long = 20
        const val LOOP_MAX_WAIT_MS: Long = 500
        const val MIN_LOOP_SPEED: Float = 0.1f
    }
}

// In the service

/** The player's engine as the drivers see it. */
class EngineExtras(private val engine: PlaybackEngine) : ExtrasPort, PlayerPort by engine {
    override val playing: Boolean get() = engine.isPlaying
    override val durationMs: Long get() = engine.durationMs.takeIf { it != C.TIME_UNSET } ?: 0
    override val speed: Float get() = engine.playbackParameters.speed

    override fun setPlayWhenReady(play: Boolean) = engine.setPlayWhenReady(play)

    override fun setSpeed(speed: Float, pitch: Float) {
        val parameters = PlaybackParameters(speed, pitch)
        if (engine.playbackParameters != parameters) engine.setPlaybackParameters(parameters)
    }

    override var volume: Float
        get() = engine.volume
        set(value) {
            engine.volume = value.coerceIn(0f, 1f)
        }
}

/** A part's own scope on the main thread, cancelled when the service stops. */
internal fun partScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + logFailures)

/** The song [uid] of the queue playing, as the library has it now (a moved file's new path, its length). */
internal fun playingOf(uid: Long, core: PlayerCore, library: StateFlow<LibraryState>, engine: PlaybackEngine): Playing? {
    val queue = core.book.value.current
    val ref = queue?.items?.firstOrNull { it.uid == uid }?.song ?: return null
    val read = (library.value as? LibraryState.Ready)?.library
    val song = read?.resolver?.find(ref)
    val decoded = engine.durationMs.takeIf { it != C.TIME_UNSET && engine.loaded.getOrNull(engine.index) == uid } ?: 0
    // A book's queue is a book from its source, before the library is read too ([playsAsBook]).
    return Playing(uid, song?.ref ?: ref, song?.durationMs?.takeIf { it > 0 } ?: decoded, inBook = playsAsBook(queue, read, uid))
}

/** Speeds, the part repeating and Remember my place in the service ([ExtrasDriver] on the engine). */
class ExtrasPart : ServicePart {
    private var scope: CoroutineScope? = null
    private var driver: ExtrasDriver? = null

    override fun start(service: PlaybackService, session: MediaLibraryService.MediaLibrarySession) {
        val scope = partScope().also { scope = it }
        val engine = service.playbackEngine
        val core = PlayerGraph.core(service)
        val library = LibraryGraph.repository(service).state
        val settings = Lyrify.settings(service).settings.stateIn(scope, SharingStarted.Eagerly, Settings())
        val books = bookSpeeds(core, library, BooksGraph.store(service))
        val made = ExtrasDriver(EngineExtras(engine), PlayerExtras.of(service), { uid -> playingOf(uid, core, library, engine) }, books) { settings.value.playback.rememberPlace }
        driver = made
        engine.addListener(object : EngineListener {
            override fun onSongStarted(uid: Long, lap: Boolean, natural: Boolean) = made.songStarted(uid, lap, natural)

            override fun onPlayingChanged(playing: Boolean) = made.playingChanged(playing)

            override fun onSeeked(fromMs: Long, toMs: Long) = made.seeked(toMs)

            // The service parks the queue at its first song (PlaybackService's Follower, which hears this first).
            override fun onEnded() = made.ended()
        })
        // The queue may have been loaded before the parts started.
        engine.loaded.getOrNull(engine.index)?.let { made.songStarted(it, lap = false, natural = false) }
        made.playingChanged(engine.isPlaying)
        scope.launch { made.run() }
    }

    override fun stop() {
        driver?.stopped()
        driver = null
        scope?.cancel()
        scope = null
    }
}
