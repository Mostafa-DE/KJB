package app.lyrify.player

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.dataStoreFile
import app.lyrify.LOG_TAG
import app.lyrify.data.writeWithRetries
import app.lyrify.library.LibraryGraph
import app.lyrify.library.LibraryState
import app.lyrify.logFailures
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
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.random.Random

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
 * book is stored (`queues.json`), while the place of the queue playing is kept apart ([savePlace], `playback.json`, every
 * 10 s while playing) and folded into the book before the next change, so the book is not rewritten every 10 s. Main
 * thread only. A change asked for before the queues are read waits for them.
 */
class PlayerCore(
    private val storage: QueueStorage,
    private val scope: CoroutineScope,
    private val wall: () -> Long,
    private val random: Random = Random.Default,
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

    init {
        scope.launch {
            val (book, repeat) = storage.load()
            _book.value = book
            _repeat.value = repeat
            _ready.value = true
            launch { _book.drop(1).collect { storage.saveBook(it) } }
        }
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

    fun now(): Long = wall()

    fun shuffleRandom(): Random = random

    fun setRepeat(repeat: Repeat) {
        _repeat.value = repeat
        val book = _book.value
        val place = live.value ?: book.current?.let { PlaybackPlace(it.id, it.index, it.positionMs) } ?: PlaybackPlace(null, 0, 0)
        savePlace(place.index, place.positionMs, place.queueId)
    }

    /** The queue playing is at song [index], [positionMs] in: `playback.json` now, the book at the next change. */
    fun savePlace(index: Int, positionMs: Long, queueId: String? = _book.value.currentId) {
        if (!_ready.value) return
        val place = PlaybackPlace(queueId, index, positionMs, _repeat.value)
        live.value = place
        scope.launch { storage.savePlace(place) }
    }

    /** The book with the live place in it, as the next change sees it. */
    private fun folded(): QueueBook {
        val place = live.value ?: return _book.value
        live.value = null
        return withPlace(_book.value, place)
    }

    private fun whenReady(run: () -> Unit) {
        if (_ready.value) run() else scope.launch { _ready.first { it }; run() }
    }
}

/** The player's objects, built by hand, one of each per process. */
object PlayerGraph {
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + logFailures)
    private var core: PlayerCore? = null
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
        PlayerCore(QueueFiles(store, PlaceFile(File(app.filesDir, "playback.json"), log), io, log), scope, System::currentTimeMillis).also { created ->
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
        PlayerConnection(app, core(app), LibraryGraph.repository(app), scope)
    }.also { connection = it }

    /** Plays songs from any list. */
    fun commands(context: Context): PlaybackCommands = connection(context)

    /** The "Add to" sheet's queues. */
    fun queueTargets(context: Context): QueueTargets = connection(context)
}
