package app.lyrify.books

import android.content.Context
import android.util.Log
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import app.lyrify.LOG_TAG
import app.lyrify.data.writeWithRetries
import app.lyrify.library.Song
import app.lyrify.logFailures
import app.lyrify.player.Speed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardOpenOption

// `books.json`: what KJB keeps per book (its place, its speed, finished, when it last played, a single file's chapters as
// read) and the user's "Mark as book" and "Not a book", apart from `song-state.json`, so a book never loses its place to
// Remember my place's settings or caps. Research: handoff/audiobook-research.md, section 6.

/**
 * One book as `books.json` keeps it. [title], [author] and [parts] (the part paths) are as the book last had them, for a
 * backup and for a book whose files moved. The place is part [place] at its position, the part known by its path
 * ([partPath]) first. [speed] is the book's own (null: 1×, a new book never takes the music's speed). [chapters] are a
 * single file's as read, kept while [chaptersStamp] (its size and change time) holds.
 */
data class BookEntry(
    val id: String,
    val title: String,
    val author: String? = null,
    val parts: List<String> = emptyList(),
    val place: BookPlace = BookPlace(),
    val partPath: String? = null,
    val speed: Speed? = null,
    val lastPlayedAt: Long? = null,
    val finished: Boolean = false,
    val chapters: List<Chapter>? = null,
    val chaptersStamp: String? = null,
)

/** Everything `books.json` holds: the books by [BookEntry.id] and the user's [marks]. */
data class BookState(val books: Map<String, BookEntry> = emptyMap(), val marks: BookMarks = BookMarks()) {
    /**
     * [book]'s entry: the one played last of those under its id, its id before PF10 ([oldFolderBookId]), and its files'
     * paths (the id a lone file had before its folder and album named it), so an older one never wins over a newer place,
     * speed or finished; they all go to its id at the book's next change. A single file's chapters only while they are
     * that file's, as another file of its folder and album may stand in it now.
     */
    fun entryOf(book: Book): BookEntry? {
        val entry = keysOf(book).mapNotNull(books::get).maxByOrNull { it.lastPlayedAt ?: Long.MIN_VALUE } ?: return null
        val own = book.kind != BookKind.SINGLE || entry.parts == book.parts.map { it.path }
        return if (own) entry else entry.copy(chapters = null, chaptersStamp = null)
    }

    /**
     * Where `books.json` may keep [book]: its id first (the one kept when none played later), its id before PF10 when that
     * entry holds one of its files (another folder's book could have had that id too), then its files' paths.
     */
    private fun keysOf(book: Book): List<String> {
        val paths = book.parts.map { it.path }
        val files = paths.toHashSet()
        val old = oldFolderBookId(book.id)?.takeIf { id -> books[id]?.parts?.any(files::contains) == true }
        return (listOfNotNull(book.id, old) + paths).distinct()
    }

    /**
     * Where [book] stands: its kept part found by path, else by number within the parts; its start when never played.
     * When its kept part is gone from the book (hidden, deleted), the start of the first part not yet passed; that part new
     * to the book is the kept one renamed, at the same second. The book's end when no part is left after it.
     */
    fun placeOf(book: Book): BookPlace {
        val entry = entryOf(book) ?: return BookPlace()
        val byPath = entry.partPath?.let { path -> book.parts.indexOfFirst { it.path == path } } ?: -1
        val kept = entry.partPath?.let(entry.parts::indexOf) ?: -1
        return when {
            byPath >= 0 -> entry.place.copy(partIndex = byPath)
            kept >= 0 -> {
                val passed = entry.parts.subList(0, kept + 1).toHashSet()
                val next = book.parts.indexOfFirst { it.path !in passed }
                when {
                    next < 0 -> BookPlace(book.parts.lastIndex, book.parts.last().durationMs.coerceAtLeast(0))
                    book.parts[next].path in entry.parts -> BookPlace(next, 0)
                    else -> entry.place.copy(partIndex = next)
                }
            }
            entry.place.partIndex in book.parts.indices -> entry.place
            else -> BookPlace(book.parts.lastIndex, 0)
        }
    }

    /** [book]'s own speed; 1× for a book without one. */
    fun speedOf(book: Book): Speed = entryOf(book)?.speed ?: Speed()

    /** [book] at [place] [now]: kept as last played; finished no more once it is past the start. */
    fun withPlace(book: Book, place: BookPlace, now: Long): BookState {
        val index = place.partIndex.coerceIn(0, book.parts.lastIndex)
        val at = BookPlace(index, place.positionMs.coerceAtLeast(0))
        return change(book) { entry ->
            entry.copy(place = at, partPath = book.parts[index].path, lastPlayedAt = now, finished = entry.finished && at == BookPlace())
        }
    }

    /** [book] played to its end [now]: finished, its place back at the start. */
    fun finished(book: Book, now: Long): BookState =
        change(book) { it.copy(place = BookPlace(), partPath = null, lastPlayedAt = now, finished = true) }

    /** "Start over": at the start, not finished. */
    fun startOver(book: Book): BookState = change(book) { it.copy(place = BookPlace(), partPath = null, finished = false) }

    /** "Mark finished" on the card: finished, at the start. */
    fun markFinished(book: Book): BookState = change(book) { it.copy(place = BookPlace(), partPath = null, finished = true) }

    fun withSpeed(book: Book, speed: Speed): BookState = change(book) { it.copy(speed = speed.normalised()) }

    /** The chapters read from the single file at [path] while it is [stamp] ([chaptersStamp]). */
    fun withChapters(book: Book, stamp: String, chapters: List<Chapter>): BookState =
        change(book) { it.copy(chapters = capped(chapters), chaptersStamp = stamp) }

    /** "Mark as book" ([on]) or "Not a book" on the songs at [paths]: their own marks. */
    fun markSongs(paths: Collection<String>, on: Boolean): BookState = copy(
        marks = if (on) {
            marks.copy(songs = marks.songs + paths, notSongs = marks.notSongs - paths.toSet())
        } else {
            marks.copy(songs = marks.songs - paths.toSet(), notSongs = marks.notSongs + paths)
        },
    )

    /**
     * "Mark as book" ([on]) or "Not a book" on [folder] ([folderKey]): the folder's mark, and the marks of its own songs
     * ([ownPaths]) the other way dropped, so the folder's answer holds for each of them.
     */
    fun markFolder(folder: String, ownPaths: Collection<String>, on: Boolean): BookState {
        val own = ownPaths.toSet()
        return copy(
            marks = if (on) {
                marks.copy(folders = marks.folders + folder, notFolders = marks.notFolders - folder, notSongs = marks.notSongs - own)
            } else {
                marks.copy(folders = marks.folders - folder, notFolders = marks.notFolders + folder, songs = marks.songs - own)
            },
        )
    }

    /** "Not a book" on [book]: its file, or its folder for a folder book. */
    fun notABook(book: Book): BookState = when (book.kind) {
        BookKind.SINGLE -> markSongs(book.parts.map { it.path }, on = false)
        BookKind.FOLDER -> markFolder(book.folder, book.parts.map { it.path }, on = false)
    }

    /** The song menu's book row on [song]: "Not a book" on its [book] when it is a part of one, else "Mark as book". */
    fun toggled(song: Song, book: Book?): BookState = if (book != null) notABook(book) else markSongs(listOf(song.path), on = true)

    /** The folder menu's book row on [folder] and its own songs ([ownPaths]): "Not a book" when it [holdsBook], else "Mark as book". */
    fun toggledFolder(folder: String, ownPaths: Collection<String>, holdsBook: Boolean): BookState = markFolder(folder, ownPaths, on = !holdsBook)

    /**
     * [book]'s entry changed (made when new, with its title, author and parts as they are now), within the caps, under its
     * id alone, the others it was kept under ([keysOf]) gone. Its place is kept as it reads now ([placeOf]), as the parts
     * it was found by are written over.
     */
    private fun change(book: Book, change: (BookEntry) -> BookEntry): BookState {
        val now = BookEntry(book.id, book.title.take(MAX_TITLE), book.author?.take(MAX_TITLE), book.parts.take(MAX_PARTS).map { it.path })
        val kept = entryOf(book)
        val place = placeOf(book)
        val entry = change(kept?.copy(id = book.id, title = now.title, author = now.author, parts = now.parts, place = place, partPath = kept.partPath?.let { book.parts[place.partIndex].path }) ?: now)
        return copy(books = keepNewest(books - keysOf(book).toSet() + (book.id to entry)))
    }

    companion object {
        const val MAX_BOOKS: Int = 200
        const val MAX_CHAPTERS: Int = 2_000
        const val MAX_PARTS: Int = 500
        const val MAX_TITLE: Int = 200

        /** Each list of marks ("Mark as book" and "Not a book", on songs and on folders), as a backup is read. */
        const val MAX_MARKS: Int = 2_000

        /** At most [MAX_BOOKS], those played longest ago (never played first) going first. */
        internal fun keepNewest(books: Map<String, BookEntry>): Map<String, BookEntry> =
            if (books.size <= MAX_BOOKS) books else books.values.sortedByDescending { it.lastPlayedAt ?: Long.MIN_VALUE }.take(MAX_BOOKS).associateBy { it.id }

        /** In time order ([chapterAt] searches them so), at most [MAX_CHAPTERS], each title at most [MAX_TITLE] long. */
        internal fun capped(chapters: List<Chapter>): List<Chapter> = chapters.sortedBy { it.startMs }.take(MAX_CHAPTERS).map { it.copy(title = it.title?.take(MAX_TITLE)) }
    }
}

/**
 * The stored form of [BookState]: a schema version, the books, and the marks. Read leniently, as `song-state.json` is:
 * unknown keys are ignored, and each book and each chapter is read on its own, so one this version cannot read is left
 * out and counted. Only text that is not a JSON object at all is not books.
 */
object BooksJson {
    const val SCHEMA_VERSION: Int = 1

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(state: BookState): String = json.encodeToString(
        JsonObject.serializer(),
        JsonObject(
            mapOf(
                SCHEMA_VERSION_KEY to JsonPrimitive(SCHEMA_VERSION),
                BOOKS_KEY to JsonArray(state.books.values.map(::entry)),
                MARKS_KEY to JsonObject(
                    mapOf(
                        SONGS_KEY to strings(state.marks.songs),
                        FOLDERS_KEY to strings(state.marks.folders),
                        NOT_SONGS_KEY to strings(state.marks.notSongs),
                        NOT_FOLDERS_KEY to strings(state.marks.notFolders),
                    ),
                ),
            ),
        ),
    )

    /**
     * The state in [text]; null when it is not a JSON object, or is from a newer schema ([fromNewer]). [onDropped] hears
     * how many entries were left out; each list of marks is capped at [BookState.MAX_MARKS].
     */
    fun decode(text: String, onDropped: (Int) -> Unit = {}): BookState? {
        val root = root(text) ?: return null
        if (newer(root)) return null
        var dropped = 0
        val books = linkedMapOf<String, BookEntry>()
        (root[BOOKS_KEY] as? JsonArray).orEmpty().forEach { element ->
            val entry = entry(element) { dropped++ }
            if (entry == null || entry.id in books) dropped++ else books[entry.id] = entry
        }
        val marks = root[MARKS_KEY] as? JsonObject
        fun set(key: String): Set<String> {
            val read = (marks?.get(key) as? JsonArray).orEmpty().mapNotNullTo(LinkedHashSet()) { it.text()?.takeIf(String::isNotEmpty) ?: null.also { dropped++ } }
            dropped += (read.size - BookState.MAX_MARKS).coerceAtLeast(0)
            return read.take(BookState.MAX_MARKS).toCollection(LinkedHashSet())
        }
        val state = BookState(BookState.keepNewest(books), BookMarks(set(SONGS_KEY), set(FOLDERS_KEY), set(NOT_SONGS_KEY), set(NOT_FOLDERS_KEY)))
        if (dropped > 0) onDropped(dropped)
        return state
    }

    /** Whether [text] holds books of a schema newer than this KJB reads: a newer KJB's, never read or written over. */
    fun fromNewer(text: String): Boolean = root(text)?.let(::newer) == true

    private fun root(text: String): JsonObject? = try {
        json.parseToJsonElement(text) as? JsonObject
    } catch (_: SerializationException) {
        null
    }

    private fun newer(root: JsonObject): Boolean = (root[SCHEMA_VERSION_KEY]?.number()?.intOrNull ?: SCHEMA_VERSION) > SCHEMA_VERSION

    /** One book in its stored form: as backup/ writes it too. */
    fun entry(entry: BookEntry): JsonObject = JsonObject(
        buildMap {
            put(ID_KEY, JsonPrimitive(entry.id))
            put(TITLE_KEY, JsonPrimitive(entry.title))
            entry.author?.let { put(AUTHOR_KEY, JsonPrimitive(it)) }
            put(PARTS_KEY, strings(entry.parts))
            put(PART_INDEX_KEY, JsonPrimitive(entry.place.partIndex))
            put(POSITION_KEY, JsonPrimitive(entry.place.positionMs))
            entry.partPath?.let { put(PART_PATH_KEY, JsonPrimitive(it)) }
            entry.speed?.let { put(SPEED_KEY, JsonObject(mapOf(SPEED_KEY to JsonPrimitive(it.speed), KEEP_PITCH_KEY to JsonPrimitive(it.keepPitch), SEMITONES_KEY to JsonPrimitive(it.semitones)))) }
            entry.lastPlayedAt?.let { put(LAST_PLAYED_KEY, JsonPrimitive(it)) }
            put(FINISHED_KEY, JsonPrimitive(entry.finished))
            entry.chapters?.let { chapters -> put(CHAPTERS_KEY, JsonArray(chapters.map(::chapter))) }
            entry.chaptersStamp?.let { put(CHAPTERS_STAMP_KEY, JsonPrimitive(it)) }
        },
    )

    /** One stored book, read leniently; null without an id and a title. [onDropped] hears each chapter left out. */
    fun entry(element: JsonElement, onDropped: () -> Unit = {}): BookEntry? {
        val stored = element as? JsonObject ?: return null
        val id = stored[ID_KEY]?.text()?.takeIf(String::isNotEmpty) ?: return null
        val title = stored[TITLE_KEY]?.text() ?: return null
        val chapters = (stored[CHAPTERS_KEY] as? JsonArray)?.mapNotNull { chapter(it) ?: null.also { onDropped() } }
        return BookEntry(
            id = id,
            title = title.take(BookState.MAX_TITLE),
            author = stored[AUTHOR_KEY]?.text()?.take(BookState.MAX_TITLE),
            parts = (stored[PARTS_KEY] as? JsonArray).orEmpty().mapNotNull { it.text()?.takeIf(String::isNotEmpty) }.take(BookState.MAX_PARTS),
            place = BookPlace(stored[PART_INDEX_KEY]?.number()?.intOrNull?.coerceAtLeast(0) ?: 0, stored[POSITION_KEY]?.number()?.longOrNull?.coerceAtLeast(0) ?: 0),
            partPath = stored[PART_PATH_KEY]?.text()?.takeIf(String::isNotEmpty),
            speed = (stored[SPEED_KEY] as? JsonObject)?.let(::speed),
            lastPlayedAt = stored[LAST_PLAYED_KEY]?.number()?.longOrNull?.takeIf { it > 0 },
            finished = stored[FINISHED_KEY]?.number()?.booleanOrNull ?: false,
            chapters = chapters?.let(BookState::capped),
            chaptersStamp = stored[CHAPTERS_STAMP_KEY]?.text(),
        )
    }

    private fun chapter(chapter: Chapter): JsonObject = JsonObject(
        buildMap {
            put(START_KEY, JsonPrimitive(chapter.startMs))
            put(END_KEY, JsonPrimitive(chapter.endMs))
            chapter.title?.let { put(TITLE_KEY, JsonPrimitive(it)) }
        },
    )

    private fun chapter(element: JsonElement): Chapter? {
        val stored = element as? JsonObject ?: return null
        val start = stored[START_KEY]?.number()?.longOrNull?.takeIf { it >= 0 } ?: return null
        val end = stored[END_KEY]?.number()?.longOrNull?.takeIf { it >= start } ?: return null
        return Chapter(start, end, stored[TITLE_KEY]?.text())
    }

    /** A speed, each field its default when it cannot be used. */
    private fun speed(stored: JsonObject): Speed {
        val d = Speed()
        return Speed(
            speed = stored[SPEED_KEY]?.number()?.floatOrNull?.takeIf { it.isFinite() } ?: d.speed,
            keepPitch = stored[KEEP_PITCH_KEY]?.number()?.booleanOrNull ?: d.keepPitch,
            semitones = stored[SEMITONES_KEY]?.number()?.intOrNull ?: d.semitones,
        ).normalised()
    }

    private fun strings(values: Collection<String>): JsonArray = JsonArray(values.map(::JsonPrimitive))

    private fun JsonElement.text(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** A primitive that is not a string: a number or a boolean as written. */
    private fun JsonElement.number(): JsonPrimitive? = (this as? JsonPrimitive)?.takeUnless { it.isString }

    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()

    private const val SCHEMA_VERSION_KEY: String = "schemaVersion"
    private const val BOOKS_KEY: String = "books"
    private const val MARKS_KEY: String = "marks"
    private const val SONGS_KEY: String = "songs"
    private const val FOLDERS_KEY: String = "folders"
    private const val NOT_SONGS_KEY: String = "notSongs"
    private const val NOT_FOLDERS_KEY: String = "notFolders"
    private const val ID_KEY: String = "id"
    private const val TITLE_KEY: String = "title"
    private const val AUTHOR_KEY: String = "author"
    private const val PARTS_KEY: String = "parts"
    private const val PART_INDEX_KEY: String = "partIndex"
    private const val POSITION_KEY: String = "positionMs"
    private const val PART_PATH_KEY: String = "partPath"
    private const val SPEED_KEY: String = "speed"
    private const val KEEP_PITCH_KEY: String = "keepPitch"
    private const val SEMITONES_KEY: String = "semitones"
    private const val LAST_PLAYED_KEY: String = "lastPlayedAt"
    private const val FINISHED_KEY: String = "finished"
    private const val CHAPTERS_KEY: String = "chapters"
    private const val CHAPTERS_STAMP_KEY: String = "chaptersStamp"
    private const val START_KEY: String = "startMs"
    private const val END_KEY: String = "endMs"
}

/** Where the books are kept: `books.json` in the app ([BookFiles]), memory in tests. */
interface BookStorage {
    suspend fun load(): BookState

    suspend fun save(state: BookState)
}

/**
 * [BookStorage] on its DataStore, written on change. A read that fails (the disk, or books from a newer KJB) throws, so
 * [BookStore] keeps nothing it read and writes nothing over the file; a file that is not books at all is kept aside
 * first and starts over ([BookStateSerializer]).
 */
class BookFiles internal constructor(
    private val store: DataStore<BookState>,
    private val io: CoroutineDispatcher,
    private val log: (String) -> Unit,
) : BookStorage {
    override suspend fun load(): BookState = withContext(io) { store.data.first() }

    override suspend fun save(state: BookState) {
        writeWithRetries(onFailure = { log("books not written: ${it.javaClass.simpleName}") }) { store.updateData { state } }
    }
}

internal class BookStateSerializer(private val backupDir: File, private val clock: () -> Long, private val log: (String) -> Unit) : Serializer<BookState> {
    override val defaultValue: BookState = BookState()

    override suspend fun readFrom(input: InputStream): BookState {
        val bytes = input.readBytes()
        val text = bytes.decodeToString()
        // Not corrupt: left as it is for the newer KJB, never started over (an IOException, not a CorruptionException).
        if (BooksJson.fromNewer(text)) throw IOException("books from a newer schema")
        return BooksJson.decode(text) { log("books: $it entries this version cannot read left out") }
            ?: throw CorruptionException("Unreadable books, kept as ${keep(bytes)}")
    }

    override suspend fun writeTo(t: BookState, output: OutputStream) {
        output.write(BooksJson.encode(t).encodeToByteArray())
    }

    /** The newest unreadable file only, kept in [backupDir] before the store starts over. */
    private fun keep(bytes: ByteArray): String {
        val copies = backupDir.listFiles().orEmpty().filter { it.name.startsWith(BACKUP_PREFIX) && it.name.endsWith(BACKUP_SUFFIX) }
        copies.firstOrNull { it.length() == bytes.size.toLong() && it.readBytes().contentEquals(bytes) }?.let { return it.name }
        val copy = File(backupDir, "$BACKUP_PREFIX${clock()}$BACKUP_SUFFIX")
        Files.write(copy.toPath(), bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.SYNC)
        log("books unreadable: kept as ${copy.name} (${bytes.size} bytes), starting over")
        copies.forEach { old ->
            try {
                Files.deleteIfExists(old.toPath())
            } catch (e: IOException) {
                log("books: old copy ${old.name} not removed: ${e.javaClass.simpleName}")
            }
        }
        return copy.name
    }

    private companion object {
        const val BACKUP_PREFIX: String = "books-corrupt-"
        const val BACKUP_SUFFIX: String = ".json"
    }
}

/** The books' DataStore at [file]: one per file and process. */
internal fun booksStore(file: File, backupDir: File, scope: CoroutineScope, clock: () -> Long, log: (String) -> Unit): DataStore<BookState> =
    DataStoreFactory.create(
        serializer = BookStateSerializer(backupDir, clock, log),
        corruptionHandler = ReplaceFileCorruptionHandler { BookState() },
        scope = scope,
        produceFile = { file },
    )

/**
 * The books of the whole app, one per process ([BooksGraph.store]): the [state] kept in `books.json`, read once
 * ([ready]); every change is stored. The screens and the player's service change it. Main thread.
 */
class BookStore(private val storage: BookStorage, private val scope: CoroutineScope, private val log: (String) -> Unit = {}) {
    private val _state = MutableStateFlow(BookState())
    val state: StateFlow<BookState> = _state.asStateFlow()

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    /**
     * Whether `books.json` was read: false when it could not be, and then nothing is written over it, so a backup leaves
     * the books out and a restore of them fails.
     */
    var readable: Boolean = false
        private set

    /** The user's marks, from the moment `books.json` is read: the library groups by them ([app.lyrify.library.LibraryRepository]). */
    val marks: Flow<BookMarks> = combine(_ready, _state) { ready, state -> state.marks.takeIf { ready } }.filterNotNull().distinctUntilChanged()

    init {
        scope.launch {
            val read = try {
                storage.load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Read as no books, so the library still loads; the file is left as it is, nothing written over it.
                log("books not read: ${e.javaClass.simpleName}")
                null
            }
            read?.let { _state.value = it }
            readable = read != null
            // Listening before the changes that waited for the read come in.
            if (read != null) launch(start = CoroutineStart.UNDISPATCHED) { _state.drop(1).collect { storage.save(it) } }
            _ready.value = true
        }
    }

    /** [change] applied to the state; before it is read, once it is. */
    fun change(change: (BookState) -> BookState) {
        if (_ready.value) {
            _state.value = change(_state.value)
        } else {
            scope.launch {
                _ready.first { it }
                _state.value = change(_state.value)
            }
        }
    }
}

/** The books' objects, built by hand, one of each per process. */
object BooksGraph {
    private var store: BookStore? = null
    private var chapters: BookChapters? = null

    /** `books.json` in app-private `files/datastore/`, a copy of an unreadable one in `files`. */
    @Synchronized
    fun store(context: Context): BookStore = store ?: run {
        val app = context.applicationContext
        val log: (String) -> Unit = { Log.w(LOG_TAG, it) }
        val files = booksStore(app.dataStoreFile("books.json"), app.filesDir, CoroutineScope(Dispatchers.IO + SupervisorJob()), System::currentTimeMillis, log)
        BookStore(BookFiles(files, Dispatchers.IO, log), CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + logFailures), log)
    }.also { store = it }

    /** The single files' chapters, read by Media3 and kept in `books.json`. */
    @Synchronized
    fun chapters(context: Context): BookChapters = chapters ?: run {
        val app = context.applicationContext
        val log: (String) -> Unit = { Log.w(LOG_TAG, it) }
        BookChapters(store(app), Media3Chapters(app, log), CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + logFailures), log)
    }.also { chapters = it }
}
