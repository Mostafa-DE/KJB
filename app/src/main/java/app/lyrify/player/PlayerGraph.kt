package app.lyrify.player

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.dataStoreFile
import app.lyrify.LOG_TAG
import app.lyrify.books.BooksGraph
import app.lyrify.data.writeWithRetries
import app.lyrify.library.LibraryGraph
import app.lyrify.library.LibraryState
import app.lyrify.logFailures
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import kotlin.random.Random

/** Where the player is: the song [uid] of the queue playing, [positionMs] in. */
data class PlayerAt(val uid: Long, val positionMs: Long)

/** Where the queues are kept: `queues.json` and `playback.json` in the app ([QueueFiles]), memory in tests. */
interface QueueStorage {
    /** The queues as stored, with the place of the one playing applied, and Repeat. */
    suspend fun load(): Pair<QueueBook, Repeat>

    suspend fun saveBook(book: QueueBook)

    suspend fun savePlace(place: PlaybackPlace)
}

/** [QueueStorage] on the two files: the queues' DataStore, written on change, and the small place file, written whole. */
class QueueFiles internal constructor(
    private val store: DataStore<QueueBook>,
    private val places: PlaceFile,
    private val io: CoroutineDispatcher,
    private val log: (String) -> Unit,
) : QueueStorage {
    override suspend fun load(): Pair<QueueBook, Repeat> = withContext(io) {
        val book = store.data.catch { e ->
            if (e !is IOException) throw e
            log("queues unreadable: ${e.javaClass.simpleName}")
            emit(QueueBook())
        }.first()
        val place = places.load()
        withPlace(book, place) to (place?.repeat ?: Repeat.OFF)
    }

    override suspend fun saveBook(book: QueueBook) {
        writeWithRetries(onFailure = { log("queues not written: ${it.javaClass.simpleName}") }) { store.updateData { book } }
    }

    override suspend fun savePlace(place: PlaybackPlace) {
        withContext(io) { places.save(place) }
    }
}

/**
 * The queues of the whole app, one per process: the [book] (every queue, the one playing, Recent and Saved), [repeat],
 * and the operations the screens and the player's session ask for. Read from storage once ([ready]); every change of the
 * book is stored (`queues.json`, see [keep]), while the place of the queue playing is kept apart ([savePlace],
 * `playback.json`, on every song start and pause and every 10 s while playing) and folded into the book before the next
 * change, so the book is not rewritten every 10 s. Each place carries when it was said; on a restart the newer of the two
 * wins ([withPlace]). Main thread only. A change asked for before the queues are read waits for them.
 */
class PlayerCore(
    private val storage: QueueStorage,
    private val scope: CoroutineScope,
    private val wall: () -> Long,
    private val random: Random = Random.Default,
    private val log: (String) -> Unit = {},
) {
    private val _book = MutableStateFlow(QueueBook())
    val book: StateFlow<QueueBook> = _book.asStateFlow()

    private val _repeat = MutableStateFlow(Repeat.OFF)
    val repeat: StateFlow<Repeat> = _repeat.asStateFlow()

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val _toRecent = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** A queue just moved to Recent, for the one-time "Your last queue is in Recent". */
    val toRecent: SharedFlow<Unit> = _toRecent.asSharedFlow()

    private val live = MutableStateFlow<PlaybackPlace?>(null)

    /** The place of the queue playing saved since the last change of the book (the book holds it after that). */
    val place: StateFlow<PlaybackPlace?> = live.asStateFlow()

    /** False once the queues could not be read: nothing is written over them this run. */
    private var stored = true

    /** Counts [flush]es: [keep] writes a change still waiting when it moves. */
    private val flushes = MutableStateFlow(0L)

    init {
        scope.launch {
            val read = try {
                storage.load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Read as nothing loaded, so a player waiting for the queues closes; their files are left as they are.
                log("queues not read: ${e.javaClass.simpleName}")
                null
            }
            stored = read != null
            read?.let { (book, repeat) ->
                _book.value = book
                _repeat.value = repeat
            }
            _ready.value = true
            if (stored) launch { keep() }
        }
    }

    /**
     * Writes the book after each change, the newest each time. A change of the queues themselves (their songs, names and
     * lists, the queue playing) is written at once. One that only moves places ([placesOnly]: a song starting, skips) is
     * written at once after a calm, then at most every [PLACES_EVERY_MS] (the last one always), or at once on a [flush]:
     * a run of skips is two writes, not one each. A kill can lose places only, and `playback.json` holds the one playing.
     */
    private suspend fun keep() {
        var written = _book.value
        while (true) {
            val book = _book.first { it !== written }
            val asked = flushes.value
            storage.saveBook(book)
            written = book
            withTimeoutOrNull(PLACES_EVERY_MS) { combine(_book, flushes) { now, flushed -> flushed != asked || !placesOnly(book, now) }.first { it } }
        }
    }

    /** A change still waiting to be written ([keep]) is written now: the player pausing, the app going to the background, the service ending. */
    fun flush() {
        flushes.value++
    }

    /**
     * Returns once the queues are read and nothing is loaded: the player closes then. Never before they are read, so a
     * player opened on a cold start (the widget's cover, the notification) waits for the queues instead of closing.
     */
    suspend fun awaitNothingLoaded() {
        // The book as it is now: it is set before ready, so a ready read never meets the empty book from before the read.
        combine(_ready, _book) { ready, _ -> ready && _book.value.current?.current == null }.first { it }
    }

    /** [change] applied to the book (the live place folded in first); before the queues are read, once they are. */
    fun change(change: (QueueBook) -> QueueBook) {
        whenReady { _book.value = change(folded()) }
    }

    /** A change that may move the queue left into Recent; null [change] results change nothing. */
    fun switch(change: (QueueBook) -> Switched?) {
        whenReady {
            val switched = change(folded()) ?: return@whenReady
            _book.value = switched.book
            if (switched.toRecent) _toRecent.tryEmit(Unit)
        }
    }

    /** [change] applied now, for a result the caller shows (an Undo); null and nothing changed before the queues are read. */
    fun <T> edit(change: (QueueBook) -> Pair<QueueBook, T>?): T? {
        if (!_ready.value) return null
        val (book, result) = change(folded()) ?: return null
        _book.value = book
        return result
    }

    /** Songs the phone moved to its trash leave every queue ([QueueBook.forget]); before the queues are read, once they are. */
    fun trashed(paths: Set<String>) {
        change { it.forget(paths) }
    }

    /** Where the player is, while the service runs; null when it stops. */
    private var player: (() -> PlayerAt?)? = null

    /**
     * The service hands in where its player is ([player]; null as it stops). Every change then first takes that place
     * onto the queue playing, so a queue left (another queue played, Resume) keeps where it really was, not its last save.
     */
    fun follow(player: (() -> PlayerAt?)?) {
        this.player = player
    }

    fun now(): Long = wall()

    fun shuffleRandom(): Random = random

    fun setRepeat(repeat: Repeat) {
        _repeat.value = repeat
        val book = _book.value
        val place = live.value ?: book.current?.let { PlaybackPlace(it.id, it.current?.uid, it.positionMs) } ?: PlaybackPlace(null, null, 0)
        savePlace(place.uid ?: return, place.positionMs, place.queueId)
    }

    /** The queue playing is at song [uid], [positionMs] in, now: `playback.json` at once, the book at the next change. */
    fun savePlace(uid: Long, positionMs: Long, queueId: String? = _book.value.currentId) {
        val place = placed(uid, positionMs, queueId) ?: return
        scope.launch { storage.savePlace(place) }
    }

    /** As [savePlace], written before it returns: the service's last word as it ends. Blocks for one small file. */
    fun savePlaceNow(uid: Long, positionMs: Long) {
        val place = placed(uid, positionMs, _book.value.currentId) ?: return
        runBlocking { storage.savePlace(place) }
    }

    private fun placed(uid: Long, positionMs: Long, queueId: String?): PlaybackPlace? {
        if (!_ready.value || !stored) return null
        return PlaybackPlace(queueId, uid, positionMs, _repeat.value, at = wall()).also { live.value = it }
    }

    /** The book with the live place in it (the player's own first), as the next change sees it. */
    private fun folded(): QueueBook {
        player?.invoke()?.let { savePlace(it.uid, it.positionMs) }
        val place = live.value ?: return _book.value
        live.value = null
        return withPlace(_book.value, place)
    }

    private fun whenReady(run: () -> Unit) {
        if (_ready.value) run() else scope.launch { _ready.first { it }; run() }
    }
}

/** A book change that only moves places waits at most this long after the write before ([PlayerCore.flush] sooner). */
private const val PLACES_EVERY_MS: Long = 10_000

/**
 * Whether [after] is [before] with only places moved: each queue's song, position and last play, and the jumps, which are
 * not stored. Lists count as the same only as the same lists, so any edit of a queue's songs is more than that.
 */
private fun placesOnly(before: QueueBook, after: QueueBook): Boolean =
    before.copy(queues = after.queues, jumps = after.jumps) == after && before.queues.size == after.queues.size &&
        before.queues.indices.all { i ->
            val was = before.queues[i]
            val now = after.queues[i]
            was === now || (was.items === now.items && was.unshuffled === now.unshuffled && was.copy(index = now.index, positionMs = now.positionMs, updatedAt = now.updatedAt) == now)
        }

/** The player's objects, built by hand, one of each per process. */
object PlayerGraph {
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + logFailures)
    private var core: PlayerCore? = null

    // Holds the application's context only, as every graph object does.
    @SuppressLint("StaticFieldLeak")
    private var connection: PlayerConnection? = null

    /** The audio session of KJB's player, for the phone's sound effects panel; 0 before the service ran. */
    @Volatile
    var audioSessionId: Int = 0

    /** The queues: app-private `files/datastore/queues.json` and `files/playback.json`, copies of an unreadable file in `files`. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Synchronized
    fun core(context: Context): PlayerCore = core ?: run {
        val app = context.applicationContext
        val log: (String) -> Unit = { Log.w(LOG_TAG, it) }
        val io = Dispatchers.IO.limitedParallelism(1)
        val store = queuesStore(app.dataStoreFile("queues.json"), app.filesDir, CoroutineScope(Dispatchers.IO + SupervisorJob()), System::currentTimeMillis, log)
        PlayerCore(QueueFiles(store, PlaceFile(File(app.filesDir, "playback.json"), log), io, log), scope, System::currentTimeMillis, log = log).also { created ->
            // Each library read: moved songs take their new path, and songs gone leave the saved queues.
            scope.launch {
                created.ready.first { it }
                LibraryGraph.repository(app).state.collect { state ->
                    val library = (state as? LibraryState.Ready)?.library ?: return@collect
                    created.change { book -> book.refresh { ref -> library.resolver.find(ref)?.ref } }
                }
            }
        }
    }.also { core = it }

    /** The screens' side of the player: its state, and the commands that go through a MediaController. */
    @Synchronized
    fun connection(context: Context): PlayerConnection = connection ?: run {
        val app = context.applicationContext
        PlayerConnection(app, core(app), LibraryGraph.repository(app), scope, BooksGraph.store(app).state)
    }.also { connection = it }

    /** Plays songs from any list. */
    fun commands(context: Context): PlaybackCommands = connection(context)

    /** A queue change still waiting is written now ([PlayerCore.flush]): the app going to the background. Nothing before the queues were opened. */
    @Synchronized
    fun flush() {
        core?.flush()
    }
}
