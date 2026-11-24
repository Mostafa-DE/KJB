package app.lyrify.player

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import app.lyrify.library.SongRef
import app.lyrify.library.search.searchSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * What `playback.json` holds: the queue playing, the song in it (its item's [uid]) and the position, together, Repeat,
 * and when the player said so ([at], wall-clock millis). Small: written on every song start and pause, every 10 s while
 * playing, and as the service ends.
 */
data class PlaybackPlace(val queueId: String?, val uid: Long?, val positionMs: Long, val repeat: Repeat = Repeat.OFF, val at: Long = 0)

/**
 * The stored form of [QueueBook] (`queues.json`): a schema version, the lists, the counters, and each queue with its
 * songs. Read leniently, as the ledger and playlists are: unknown keys are ignored, and each queue and each song is read
 * on its own, so one this version cannot read (a newer source kind, a song without a path) is left out, counted, and
 * costs nothing else. A queue left without songs, or in none of the lists, goes; Saved wins over Recent. Only text that
 * is not a JSON object at all is not queues.
 */
object QueuesJson {
    const val SCHEMA_VERSION: Int = 1

    private val json = Json { ignoreUnknownKeys = true }

    /** The whole book as one text (a backup's queues), as [Encoder] writes `queues.json`. */
    fun encode(book: QueueBook): String = ByteArrayOutputStream().also { Encoder().write(book, it) }.toByteArray().decodeToString()

    /**
     * Writes books as `queues.json` holds them, keeping each queue's songs (and its order before Shuffle) as last encoded
     * while the queue holds that very list. A change makes a new book that shares every list it left alone, so a skip or a
     * new place encodes a few fields of one queue, and a change to one queue that queue's lists: never every song of
     * every queue. One per file, one write at a time. [onEncoded] hears a queue's id for each of its lists encoded.
     */
    class Encoder(private val onEncoded: (String) -> Unit = {}) {
        /** By queue id, the lists of the last write. */
        private var songs: Map<String, Encoded> = emptyMap()
        private var orders: Map<String, Encoded> = emptyMap()

        fun write(book: QueueBook, out: OutputStream) {
            val songs = book.queues.map { queue -> encoded(queue.id, queue.items, this.songs[queue.id]) { item(it) } }
            val orders = book.queues.map { queue -> queue.unshuffled?.let { order -> encoded(queue.id, order, this.orders[queue.id]) { JsonPrimitive(it) } } }
            // The members before the queues and each queue's before its lists, then the lists: the order 3.0.2 wrote.
            out.write(open(top(book)))
            out.text(",\"$QUEUES_KEY\":[")
            book.queues.forEachIndexed { i, queue ->
                if (i > 0) out.text(",")
                out.write(open(head(queue)))
                orders[i]?.let { order ->
                    out.text(",\"$UNSHUFFLED_KEY\":")
                    out.write(order.json)
                }
                out.text(",\"$ITEMS_KEY\":")
                out.write(songs[i].json)
                out.text("}")
            }
            out.text("]}")
            this.songs = book.queues.indices.associate { book.queues[it].id to songs[it] }
            this.orders = book.queues.indices.mapNotNull { i -> orders[i]?.let { book.queues[i].id to it } }.toMap()
        }

        private fun <T> encoded(queueId: String, list: List<T>, before: Encoded?, element: (T) -> JsonElement): Encoded =
            before?.takeIf { it.list === list } ?: Encoded(list, array(list, element)).also { onEncoded(queueId) }

        private fun OutputStream.text(text: String) = write(text.encodeToByteArray())
    }

    /** A queue's [list] and its JSON array ([json]), kept while the queue holds that very list. */
    private class Encoded(val list: List<*>, val json: ByteArray)

    /** [list] as a JSON array, encoded an element at a time: no tree of a whole long list is built. */
    private fun <T> array(list: List<T>, element: (T) -> JsonElement): ByteArray = ByteArrayOutputStream().apply {
        write('['.code)
        list.forEachIndexed { i, it ->
            if (i > 0) write(','.code)
            write(json.encodeToString(JsonElement.serializer(), element(it)).encodeToByteArray())
        }
        write(']'.code)
    }.toByteArray()

    /** [members] as a JSON object left open (its closing brace off), for the lists to follow. */
    private fun open(members: Map<String, JsonElement>): ByteArray =
        json.encodeToString(JsonObject.serializer(), JsonObject(members)).dropLast(1).encodeToByteArray()

    private fun top(book: QueueBook): Map<String, JsonElement> = mapOf(
        SCHEMA_VERSION_KEY to JsonPrimitive(SCHEMA_VERSION),
        CURRENT_KEY to JsonPrimitive(book.currentId),
        RECENT_KEY to JsonArray(book.recent.map(::JsonPrimitive)),
        SAVED_KEY to JsonArray(book.saved.map(::JsonPrimitive)),
        NEXT_ID_KEY to JsonPrimitive(book.nextId),
        NEXT_UID_KEY to JsonPrimitive(book.nextUid),
    )

    /** The book in [text]; null when it is not a JSON object. [onDropped] hears how many queues and songs were left out. */
    fun decode(text: String, onDropped: (Int) -> Unit = {}): QueueBook? {
        val root = try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (_: SerializationException) {
            null
        } ?: return null
        var dropped = 0
        val read = (root[QUEUES_KEY] as? JsonArray).orEmpty().mapNotNull { element ->
            queue(element) { dropped += it } ?: null.also { dropped++ }
        }.distinctBy { it.id }
        val ids = read.map { it.id }.toSet()
        val currentId = root[CURRENT_KEY]?.text()?.takeIf { it in ids }
        val saved = strings(root[SAVED_KEY]).filter { it in ids }.distinct()
        val recent = recentKept(strings(root[RECENT_KEY]).filter { it in ids && it !in saved && it != currentId }.distinct()) { id -> read.first { it.id == id }.items.size }
        val listed = saved.toSet() + recent + listOfNotNull(currentId)
        val queues = read.filter { it.id in listed }
        dropped += read.size - queues.size
        if (dropped > 0) onDropped(dropped)
        val usedId = read.mapNotNull { it.id.removePrefix(ID_PREFIX).toLongOrNull() }.maxOrNull() ?: 0
        val usedUid = read.flatMap { queue -> queue.items.map { it.uid } + queue.unshuffled.orEmpty() }.maxOrNull() ?: 0
        return QueueBook(
            queues = queues,
            currentId = currentId,
            recent = recent,
            saved = saved,
            nextId = maxOf(root.long(NEXT_ID_KEY) ?: 1, usedId + 1),
            nextUid = maxOf(root.long(NEXT_UID_KEY) ?: 1, usedUid + 1),
        )
    }

    /** A queue's members but its two lists ([Encoder] adds them). */
    private fun head(queue: Queue): Map<String, JsonElement> = buildMap {
        put(ID_KEY, JsonPrimitive(queue.id))
        // A list KJB suggested, a card a friend sent, or Not played, is stored as picked songs with a mark: an older KJB,
        // which knows no such list, keeps its songs.
        val suggested = queue.source.kind == SourceKind.SUGGESTIONS
        val sent = queue.source.kind == SourceKind.HUDDLE
        val unplayed = queue.source.kind == SourceKind.NOT_PLAYED
        val kind = if (suggested || sent || unplayed) SourceKind.PICKED else queue.source.kind
        put(
            SOURCE_KEY,
            JsonObject(
                buildMap {
                    put(KIND_KEY, JsonPrimitive(kind.name))
                    put(KEY_KEY, JsonPrimitive(queue.source.key))
                    put(NAME_KEY, JsonPrimitive(queue.source.name))
                    if (suggested) put(SUGGESTIONS_KEY, JsonPrimitive(true))
                    if (sent) put(HUDDLE_KEY, JsonPrimitive(true))
                    if (unplayed) put(NOT_PLAYED_KEY, JsonPrimitive(true))
                },
            ),
        )
        queue.name?.let { put(NAME_KEY, JsonPrimitive(it)) }
        put(INDEX_KEY, JsonPrimitive(queue.index))
        put(POSITION_KEY, JsonPrimitive(queue.positionMs))
        put(UPDATED_AT_KEY, JsonPrimitive(queue.updatedAt))
        put(STARTED_AT_KEY, JsonPrimitive(queue.startedAt))
        if (queue.smart) put(SMART_KEY, JsonPrimitive(true))
    }

    private fun item(item: QueueItem): JsonElement = JsonObject(
        buildMap {
            put(UID_KEY, JsonPrimitive(item.uid))
            put(PATH_KEY, JsonPrimitive(item.song.path))
            item.song.mediaStoreId?.let { put(MEDIA_STORE_ID_KEY, JsonPrimitive(it)) }
            item.sourceIndex?.let { put(SOURCE_INDEX_KEY, JsonPrimitive(it)) }
            if (item.playNext) put(PLAY_NEXT_KEY, JsonPrimitive(true))
            item.suggested?.let { put(SUGGESTED_KEY, JsonPrimitive(it.name)) }
        },
    )

    /** A queue, its unreadable songs left out (counted with [onDropped]); null when the queue itself cannot be read or has no songs. */
    private fun queue(element: JsonElement, onDropped: (Int) -> Unit): Queue? {
        val stored = element as? JsonObject ?: return null
        val id = stored[ID_KEY]?.text() ?: return null
        val source = stored[SOURCE_KEY] as? JsonObject ?: return null
        val named = source[KIND_KEY]?.text()?.let { name -> SourceKind.entries.firstOrNull { it.name == name } } ?: return null
        val kind = when {
            (source[SUGGESTIONS_KEY] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull == true -> SourceKind.SUGGESTIONS
            (source[HUDDLE_KEY] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull == true -> SourceKind.HUDDLE
            (source[NOT_PLAYED_KEY] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull == true -> SourceKind.NOT_PLAYED
            else -> named
        }
        val all = (stored[ITEMS_KEY] as? JsonArray).orEmpty()
        val items = all.mapNotNull(::item).distinctBy { it.uid }.distinctBy { it.song.path }
        if (items.size < all.size) onDropped(all.size - items.size)
        if (items.isEmpty()) return null
        val index = stored.int(INDEX_KEY)?.takeIf { it >= 0 }
        val inRange = index != null && index < items.size
        val uids = items.map { it.uid }.toSet()
        return Queue(
            id = id,
            source = QueueSource(kind, placeKey(kind, source[KEY_KEY]?.text().orEmpty()), source[NAME_KEY]?.text().orEmpty()),
            items = items,
            index = if (inRange) index!! else items.lastIndex.takeIf { index != null } ?: 0,
            positionMs = if (inRange) stored.long(POSITION_KEY)?.coerceAtLeast(0) ?: 0 else 0,
            name = stored[NAME_KEY]?.text()?.takeIf(String::isNotBlank),
            unshuffled = (stored[UNSHUFFLED_KEY] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeUnless { p -> p.isString }?.longOrNull }?.filter { it in uids },
            updatedAt = stored.long(UPDATED_AT_KEY) ?: 0,
            // Queues stored before start times were kept: their last play is as near as is known.
            startedAt = stored.long(STARTED_AT_KEY) ?: stored.long(UPDATED_AT_KEY) ?: 0,
            // Smart shuffle is Shuffle with suggestions: only a shuffled queue has it.
            smart = stored.containsKey(UNSHUFFLED_KEY) && (stored[SMART_KEY] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull == true,
        )
    }

    /**
     * [key] of a list of [kind] as its place is named now: queues stored before one recent queue per place had a key per
     * sort and filter of the Songs tab (songs:<sort>:<filter>, the sort ending -reversed the other way round), per search
     * (search:<words>) and per selection (picked:<time>). A new queue's own key from Add to (ending #q<n>) and every other
     * key stay as they are.
     */
    private fun placeKey(kind: SourceKind, key: String): String = when {
        OWN_KEY.containsMatchIn(key) -> key
        kind == SourceKind.SONGS && OLD_SONGS_KEY.matches(key) -> SongsTabSource.key
        kind == SourceKind.SEARCH && OLD_SEARCH_KEY.containsMatchIn(key) -> searchSource("").key
        kind == SourceKind.PICKED && OLD_PICKED_KEY.matches(key) -> pickedSource("").key
        else -> key
    }

    private val OWN_KEY = Regex("""#q\d+$""")
    private val OLD_SONGS_KEY = Regex("""^songs:[A-Z_]+(-reversed)?:[A-Z_]+$""")
    private val OLD_SEARCH_KEY = Regex("""^search:""")
    private val OLD_PICKED_KEY = Regex("""^picked:\d+$""")

    private fun item(element: JsonElement): QueueItem? {
        val stored = element as? JsonObject ?: return null
        val uid = stored.long(UID_KEY) ?: return null
        val path = stored[PATH_KEY]?.text()?.takeIf(String::isNotEmpty) ?: return null
        return QueueItem(
            uid = uid,
            song = SongRef(path, stored.long(MEDIA_STORE_ID_KEY)),
            sourceIndex = stored.int(SOURCE_INDEX_KEY)?.takeIf { it >= 0 },
            playNext = (stored[PLAY_NEXT_KEY] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: false,
            // A mark a newer KJB wrote that this one does not know reads as a song like any other.
            suggested = stored[SUGGESTED_KEY]?.text()?.let { name -> Suggested.entries.firstOrNull { it.name == name } },
        )
    }

    private fun strings(element: JsonElement?): List<String> = (element as? JsonArray).orEmpty().mapNotNull { it.text() }

    private const val ID_PREFIX: String = "q"
    private const val SCHEMA_VERSION_KEY: String = "schemaVersion"
    private const val CURRENT_KEY: String = "current"
    private const val RECENT_KEY: String = "recent"
    private const val SAVED_KEY: String = "saved"
    private const val NEXT_ID_KEY: String = "nextId"
    private const val NEXT_UID_KEY: String = "nextUid"
    private const val QUEUES_KEY: String = "queues"
    private const val ID_KEY: String = "id"
    private const val SOURCE_KEY: String = "source"
    private const val KIND_KEY: String = "kind"
    private const val KEY_KEY: String = "key"
    private const val NAME_KEY: String = "name"
    private const val INDEX_KEY: String = "index"
    private const val POSITION_KEY: String = "positionMs"
    private const val UPDATED_AT_KEY: String = "updatedAt"
    private const val STARTED_AT_KEY: String = "startedAt"
    private const val UNSHUFFLED_KEY: String = "unshuffled"
    private const val ITEMS_KEY: String = "items"
    private const val UID_KEY: String = "uid"
    private const val PATH_KEY: String = "path"
    private const val MEDIA_STORE_ID_KEY: String = "mediaStoreId"
    private const val SOURCE_INDEX_KEY: String = "sourceIndex"
    private const val PLAY_NEXT_KEY: String = "playNext"
    private const val SUGGESTED_KEY: String = "suggested"
    private const val SMART_KEY: String = "smart"
    private const val SUGGESTIONS_KEY: String = "suggestions"
    private const val HUDDLE_KEY: String = "huddle"
    private const val NOT_PLAYED_KEY: String = "notPlayed"
}

/** The stored form of [PlaybackPlace]: read leniently, a field it cannot use as its default; null when it is no JSON object. */
object PlaybackJson {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(place: PlaybackPlace): String = json.encodeToString(
        JsonObject.serializer(),
        JsonObject(
            mapOf(
                QUEUE_ID_KEY to (place.queueId?.let(::JsonPrimitive) ?: JsonNull),
                UID_KEY to (place.uid?.let(::JsonPrimitive) ?: JsonNull),
                POSITION_KEY to JsonPrimitive(place.positionMs),
                REPEAT_KEY to JsonPrimitive(place.repeat.name),
                AT_KEY to JsonPrimitive(place.at),
            ),
        ),
    )

    fun decode(text: String): PlaybackPlace? {
        val root = try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (_: SerializationException) {
            null
        } ?: return null
        return PlaybackPlace(
            queueId = root[QUEUE_ID_KEY]?.text(),
            uid = root.long(UID_KEY),
            positionMs = root.long(POSITION_KEY)?.takeIf { it >= 0 } ?: 0,
            repeat = root[REPEAT_KEY]?.text()?.let { name -> Repeat.entries.firstOrNull { it.name == name } } ?: Repeat.OFF,
            at = root.long(AT_KEY)?.takeIf { it >= 0 } ?: 0,
        )
    }

    private const val QUEUE_ID_KEY: String = "queueId"
    private const val UID_KEY: String = "uid"
    private const val POSITION_KEY: String = "positionMs"
    private const val REPEAT_KEY: String = "repeat"
    private const val AT_KEY: String = "at"
}

/**
 * [book] with the place `playback.json` kept for its current queue, when [place] names that queue, a song it still has,
 * and is newer than the queue's own place in `queues.json`: the newest wins, so neither a place written before a song
 * change nor a book written before the last pause takes the other back.
 */
fun withPlace(book: QueueBook, place: PlaybackPlace?): QueueBook {
    if (place?.queueId == null || place.queueId != book.currentId) return book
    val index = book.current?.items?.indexOfFirst { it.uid == place.uid } ?: return book
    return book.place(index, place.positionMs, place.at)
}

private fun JsonElement.text(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull

private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull

/**
 * Reads and writes [QueueBook]. A file that is not queues at all is first copied to [backupDir] as
 * `queues-corrupt-<epochMillis>.json` (the newest copy only), then reported, so the store starts over without losing it.
 * When the copy cannot be made, the read fails with a plain IOException instead and the file stays as it is. The log
 * gets counts and the copy's name and size only. Writes encode only the song lists changed since the last ([encoder]).
 */
internal class QueuesSerializer(private val backupDir: File, private val clock: () -> Long, private val log: (String) -> Unit) : Serializer<QueueBook> {
    override val defaultValue: QueueBook = QueueBook()

    private val encoder = QueuesJson.Encoder()

    override suspend fun readFrom(input: InputStream): QueueBook {
        val bytes = input.readBytes()
        return QueuesJson.decode(bytes.decodeToString()) { log("queues: $it queues or songs this version cannot read left out") }
            ?: throw CorruptionException("Unreadable queues, kept as ${keep(bytes)}")
    }

    override suspend fun writeTo(t: QueueBook, output: OutputStream) {
        // Buffered for the few fields between the lists; the lists themselves go through whole.
        val buffered = output.buffered()
        encoder.write(t, buffered)
        buffered.flush()
    }

    private fun keep(bytes: ByteArray): String {
        val copies = backupDir.listFiles().orEmpty().filter { it.name.startsWith(BACKUP_PREFIX) && it.name.endsWith(BACKUP_SUFFIX) }
        copies.firstOrNull { it.length() == bytes.size.toLong() && it.readBytes().contentEquals(bytes) }?.let { return it.name }
        val copy = File(backupDir, "$BACKUP_PREFIX${clock()}$BACKUP_SUFFIX")
        // On storage before the store starts over.
        Files.write(copy.toPath(), bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.SYNC)
        log("queues unreadable: kept as ${copy.name} (${bytes.size} bytes), starting over")
        copies.forEach { old ->
            try {
                Files.deleteIfExists(old.toPath())
            } catch (e: IOException) {
                log("queues: old copy ${old.name} not removed: ${e.javaClass.simpleName}")
            }
        }
        return copy.name
    }

    private companion object {
        const val BACKUP_PREFIX: String = "queues-corrupt-"
        const val BACKUP_SUFFIX: String = ".json"
    }
}

/** The queues' DataStore at [file]: one per file and process. It starts over only once [QueuesSerializer] kept a copy in [backupDir]. */
internal fun queuesStore(file: File, backupDir: File, scope: CoroutineScope, clock: () -> Long, log: (String) -> Unit): DataStore<QueueBook> =
    DataStoreFactory.create(
        serializer = QueuesSerializer(backupDir, clock, log),
        corruptionHandler = ReplaceFileCorruptionHandler { QueueBook() },
        scope = scope,
        produceFile = { file },
    )

/**
 * `playback.json`: one small app-private file, written whole or not at all (aside, then moved over). One that cannot be
 * read is no place: the queues open at their stored places. Blocking.
 */
class PlaceFile(private val file: File, private val log: (String) -> Unit = {}) {
    fun load(): PlaybackPlace? {
        if (!file.isFile) return null
        val text = try {
            file.readText()
        } catch (e: IOException) {
            log("playback place not readable (${e.javaClass.simpleName})")
            return null
        }
        return PlaybackJson.decode(text) ?: null.also { log("playback place unreadable (${text.length} chars)") }
    }

    /** False when it could not be written; the file before stays then. */
    fun save(place: PlaybackPlace): Boolean {
        val aside = File(file.path + TMP)
        return try {
            file.parentFile?.mkdirs()
            aside.writeText(PlaybackJson.encode(place))
            Files.move(aside.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            true
        } catch (e: IOException) {
            aside.delete()
            log("playback place not written (${e.javaClass.simpleName})")
            false
        }
    }

    private companion object {
        const val TMP: String = ".tmp"
    }
}
