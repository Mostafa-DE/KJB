package app.lyrify.player

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import app.lyrify.library.SongRef
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

    fun encode(book: QueueBook): String = json.encodeToString(
        JsonObject.serializer(),
        JsonObject(
            mapOf(
                SCHEMA_VERSION_KEY to JsonPrimitive(SCHEMA_VERSION),
                CURRENT_KEY to JsonPrimitive(book.currentId),
                RECENT_KEY to JsonArray(book.recent.map(::JsonPrimitive)),
                SAVED_KEY to JsonArray(book.saved.map(::JsonPrimitive)),
                NEXT_ID_KEY to JsonPrimitive(book.nextId),
                NEXT_UID_KEY to JsonPrimitive(book.nextUid),
                QUEUES_KEY to JsonArray(book.queues.map(::queue)),
            ),
        ),
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
        val recent = strings(root[RECENT_KEY]).filter { it in ids && it !in saved && it != currentId }.distinct().take(QueueBook.MAX_RECENT)
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

    private fun queue(queue: Queue): JsonElement = JsonObject(
        buildMap {
            put(ID_KEY, JsonPrimitive(queue.id))
            put(SOURCE_KEY, JsonObject(mapOf(KIND_KEY to JsonPrimitive(queue.source.kind.name), KEY_KEY to JsonPrimitive(queue.source.key), NAME_KEY to JsonPrimitive(queue.source.name))))
            queue.name?.let { put(NAME_KEY, JsonPrimitive(it)) }
            put(INDEX_KEY, JsonPrimitive(queue.index))
            put(POSITION_KEY, JsonPrimitive(queue.positionMs))
            put(UPDATED_AT_KEY, JsonPrimitive(queue.updatedAt))
            put(STARTED_AT_KEY, JsonPrimitive(queue.startedAt))
            queue.listSize?.let { put(LIST_SIZE_KEY, JsonPrimitive(it)) }
            queue.unshuffled?.let { put(UNSHUFFLED_KEY, JsonArray(it.map(::JsonPrimitive))) }
            put(ITEMS_KEY, JsonArray(queue.items.map(::item)))
        },
    )

    private fun item(item: QueueItem): JsonElement = JsonObject(
        buildMap {
            put(UID_KEY, JsonPrimitive(item.uid))
            put(PATH_KEY, JsonPrimitive(item.song.path))
            item.song.mediaStoreId?.let { put(MEDIA_STORE_ID_KEY, JsonPrimitive(it)) }
            item.sourceIndex?.let { put(SOURCE_INDEX_KEY, JsonPrimitive(it)) }
            if (item.playNext) put(PLAY_NEXT_KEY, JsonPrimitive(true))
        },
    )

    /** A queue, its unreadable songs left out (counted with [onDropped]); null when the queue itself cannot be read or has no songs. */
    private fun queue(element: JsonElement, onDropped: (Int) -> Unit): Queue? {
        val stored = element as? JsonObject ?: return null
        val id = stored[ID_KEY]?.text() ?: return null
        val source = stored[SOURCE_KEY] as? JsonObject ?: return null
        val kind = source[KIND_KEY]?.text()?.let { name -> SourceKind.entries.firstOrNull { it.name == name } } ?: return null
        val all = (stored[ITEMS_KEY] as? JsonArray).orEmpty()
        val items = all.mapNotNull(::item).distinctBy { it.uid }.distinctBy { it.song.path }
        if (items.size < all.size) onDropped(all.size - items.size)
        if (items.isEmpty()) return null
        val index = stored.int(INDEX_KEY)?.takeIf { it >= 0 }
        val inRange = index != null && index < items.size
        val uids = items.map { it.uid }.toSet()
        return Queue(
            id = id,
            source = QueueSource(kind, source[KEY_KEY]?.text().orEmpty(), source[NAME_KEY]?.text().orEmpty()),
            items = items,
            index = if (inRange) index!! else items.lastIndex.takeIf { index != null } ?: 0,
            positionMs = if (inRange) stored.long(POSITION_KEY)?.coerceAtLeast(0) ?: 0 else 0,
            name = stored[NAME_KEY]?.text()?.takeIf(String::isNotBlank),
            unshuffled = (stored[UNSHUFFLED_KEY] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeUnless { p -> p.isString }?.longOrNull }?.filter { it in uids },
            updatedAt = stored.long(UPDATED_AT_KEY) ?: 0,
            // Queues stored before start times were kept: their last play is as near as is known.
            startedAt = stored.long(STARTED_AT_KEY) ?: stored.long(UPDATED_AT_KEY) ?: 0,
            // A size that could not be the list's (shorter than a song's place in it) reads as unknown.
            listSize = stored.int(LIST_SIZE_KEY)?.takeIf { size -> items.all { (it.sourceIndex ?: -1) < size } },
        )
    }

    private fun item(element: JsonElement): QueueItem? {
        val stored = element as? JsonObject ?: return null
        val uid = stored.long(UID_KEY) ?: return null
        val path = stored[PATH_KEY]?.text()?.takeIf(String::isNotEmpty) ?: return null
        return QueueItem(
            uid = uid,
            song = SongRef(path, stored.long(MEDIA_STORE_ID_KEY)),
            sourceIndex = stored.int(SOURCE_INDEX_KEY)?.takeIf { it >= 0 },
            playNext = (stored[PLAY_NEXT_KEY] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: false,
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
    private const val LIST_SIZE_KEY: String = "listSize"
    private const val UNSHUFFLED_KEY: String = "unshuffled"
    private const val ITEMS_KEY: String = "items"
    private const val UID_KEY: String = "uid"
    private const val PATH_KEY: String = "path"
    private const val MEDIA_STORE_ID_KEY: String = "mediaStoreId"
    private const val SOURCE_INDEX_KEY: String = "sourceIndex"
    private const val PLAY_NEXT_KEY: String = "playNext"
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
 * gets counts and the copy's name and size only.
 */
internal class QueuesSerializer(private val backupDir: File, private val clock: () -> Long, private val log: (String) -> Unit) : Serializer<QueueBook> {
    override val defaultValue: QueueBook = QueueBook()

    override suspend fun readFrom(input: InputStream): QueueBook {
        val bytes = input.readBytes()
        return QueuesJson.decode(bytes.decodeToString()) { log("queues: $it queues or songs this version cannot read left out") }
            ?: throw CorruptionException("Unreadable queues, kept as ${keep(bytes)}")
    }

    override suspend fun writeTo(t: QueueBook, output: OutputStream) {
        output.write(QueuesJson.encode(t).encodeToByteArray())
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
