package app.lyrify.player

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import app.lyrify.data.writeWithRetries
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
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

/**
 * The stored form of [SongState] (`song-state.json`): a schema version, the usual speed, the next bookmark id, the part
 * repeating, and one entry per song with its own speed, place and bookmarks (songs with a place first, the oldest first,
 * so the places keep their order). A song is named as queues.json names it: `path`, and `mediaStoreId` as a hint when
 * known (files written before the hint read without it). The caps hold on reading too. Read leniently: unknown keys are ignored, a speed's fields read as their defaults when
 * they cannot be used, and each song, place, bookmark and the part is read on its own, so one this version cannot read
 * is left out and counted. Only text that is not a JSON object at all is not song state.
 */
object SongStateJson {
    const val SCHEMA_VERSION: Int = 1

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(state: SongState): String {
        val paths = (state.places.keys + state.speeds.keys + state.bookmarks.keys + listOfNotNull(state.part?.path)).distinct()
        val songs = paths.map { path ->
            JsonObject(
                buildMap {
                    put(PATH_KEY, JsonPrimitive(path))
                    state.ids[path]?.let { put(MEDIA_STORE_ID_KEY, JsonPrimitive(it)) }
                    state.speeds[path]?.let { put(SPEED_KEY, speed(it)) }
                    state.places[path]?.let { put(PLACE_KEY, JsonPrimitive(it)) }
                    state.bookmarks[path]?.takeIf { it.isNotEmpty() }?.let { marks -> put(BOOKMARKS_KEY, JsonArray(marks.map(::bookmark))) }
                },
            )
        }
        return json.encodeToString(
            JsonObject.serializer(),
            JsonObject(
                buildMap {
                    put(SCHEMA_VERSION_KEY, JsonPrimitive(SCHEMA_VERSION))
                    put(USUAL_KEY, speed(state.usual))
                    put(NEXT_BOOKMARK_KEY, JsonPrimitive(state.nextBookmarkId))
                    state.part?.let { put(PART_KEY, JsonObject(mapOf(PATH_KEY to JsonPrimitive(it.path), START_KEY to JsonPrimitive(it.startMs), END_KEY to JsonPrimitive(it.endMs)))) }
                    put(SONGS_KEY, JsonArray(songs))
                },
            ),
        )
    }

    /** The state in [text]; null when it is not a JSON object. [onDropped] hears how many entries were left out. */
    fun decode(text: String, onDropped: (Int) -> Unit = {}): SongState? {
        val root = try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (_: SerializationException) {
            null
        } ?: return null
        var dropped = 0
        val speeds = mutableMapOf<String, Speed>()
        val places = linkedMapOf<String, Long>()
        val bookmarks = mutableMapOf<String, List<Bookmark>>()
        val ids = mutableMapOf<String, Long>()
        val seen = mutableSetOf<String>()
        (root[SONGS_KEY] as? JsonArray).orEmpty().forEach { element ->
            val song = element as? JsonObject
            val path = song?.get(PATH_KEY)?.text()?.takeIf(String::isNotEmpty)
            if (song == null || path == null || !seen.add(path)) {
                dropped++
                return@forEach
            }
            song[MEDIA_STORE_ID_KEY]?.long()?.let { ids[path] = it }
            song[SPEED_KEY]?.let { stored -> speed(stored)?.let { speeds[path] = it } ?: dropped++ }
            song[PLACE_KEY]?.let { stored -> stored.long()?.takeIf { it >= 0 }?.let { places[path] = it } ?: dropped++ }
            val marks = (song[BOOKMARKS_KEY] as? JsonArray).orEmpty().mapNotNull { stored -> bookmark(stored) ?: null.also { dropped++ } }
            if (marks.isNotEmpty()) bookmarks[path] = marks.distinctBy { it.id }.sortedWith(compareBy(Bookmark::positionMs, Bookmark::id)).take(SongState.MAX_BOOKMARKS_PER_SONG)
        }
        val part = root[PART_KEY]?.let { stored -> part(stored) ?: null.also { dropped++ } }
        if (dropped > 0) onDropped(dropped)
        val usedId = bookmarks.values.flatten().maxOfOrNull { it.id } ?: 0
        val kept = speeds.entries.toList().takeLast(SongState.MAX_SPEEDS).associate { it.key to it.value }
        val placed = places.entries.toList().takeLast(SongState.MAX_PLACES).associate { it.key to it.value }
        val keeps = placed.keys + kept.keys + bookmarks.keys + listOfNotNull(part?.path)
        return SongState(
            usual = root[USUAL_KEY]?.let(::speed) ?: Speed(),
            speeds = kept,
            places = placed,
            bookmarks = bookmarks,
            part = part,
            nextBookmarkId = maxOf(root[NEXT_BOOKMARK_KEY]?.long() ?: 1, usedId + 1),
            ids = ids.filterKeys { it in keeps },
        )
    }

    private fun speed(speed: Speed): JsonElement =
        JsonObject(mapOf(SPEED_KEY to JsonPrimitive(speed.speed), KEEP_PITCH_KEY to JsonPrimitive(speed.keepPitch), SEMITONES_KEY to JsonPrimitive(speed.semitones)))

    /** A speed, each field its default when it cannot be used; null when it is not an object. */
    private fun speed(element: JsonElement): Speed? {
        val stored = element as? JsonObject ?: return null
        val d = Speed()
        return Speed(
            speed = stored[SPEED_KEY]?.number()?.floatOrNull?.takeIf { it.isFinite() } ?: d.speed,
            keepPitch = stored[KEEP_PITCH_KEY]?.number()?.booleanOrNull ?: d.keepPitch,
            semitones = stored[SEMITONES_KEY]?.number()?.intOrNull ?: d.semitones,
        ).normalised()
    }

    /** A bookmark; when it was made only when known, so a file without it reads as before. */
    private fun bookmark(mark: Bookmark): JsonElement = JsonObject(
        buildMap {
            put(ID_KEY, JsonPrimitive(mark.id))
            put(POSITION_KEY, JsonPrimitive(mark.positionMs))
            put(NOTE_KEY, JsonPrimitive(mark.note))
            if (mark.addedAt > 0) put(ADDED_AT_KEY, JsonPrimitive(mark.addedAt))
        },
    )

    private fun bookmark(element: JsonElement): Bookmark? {
        val stored = element as? JsonObject ?: return null
        val id = stored[ID_KEY]?.long() ?: return null
        val position = stored[POSITION_KEY]?.long()?.takeIf { it >= 0 } ?: return null
        return Bookmark(id, position, stored[NOTE_KEY]?.text()?.take(SongState.MAX_NOTE).orEmpty(), stored[ADDED_AT_KEY]?.long()?.takeIf { it > 0 } ?: 0)
    }

    private fun part(element: JsonElement): AbPart? {
        val stored = element as? JsonObject ?: return null
        val path = stored[PATH_KEY]?.text()?.takeIf(String::isNotEmpty) ?: return null
        val start = stored[START_KEY]?.long()?.takeIf { it >= 0 } ?: return null
        val end = stored[END_KEY]?.long()?.takeIf { it > start } ?: return null
        return AbPart(path, start, end)
    }

    private fun JsonElement.text(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** A primitive that is not a string: a number or a boolean as written, never "1.5". */
    private fun JsonElement.number(): JsonPrimitive? = (this as? JsonPrimitive)?.takeUnless { it.isString }

    private fun JsonElement.long(): Long? = number()?.longOrNull

    private const val SCHEMA_VERSION_KEY: String = "schemaVersion"
    private const val USUAL_KEY: String = "usual"
    private const val NEXT_BOOKMARK_KEY: String = "nextBookmarkId"
    private const val PART_KEY: String = "part"
    private const val SONGS_KEY: String = "songs"
    private const val PATH_KEY: String = "path"
    private const val MEDIA_STORE_ID_KEY: String = "mediaStoreId"
    private const val SPEED_KEY: String = "speed"
    private const val KEEP_PITCH_KEY: String = "keepPitch"
    private const val SEMITONES_KEY: String = "semitones"
    private const val PLACE_KEY: String = "place"
    private const val BOOKMARKS_KEY: String = "bookmarks"
    private const val ID_KEY: String = "id"
    private const val POSITION_KEY: String = "positionMs"
    private const val NOTE_KEY: String = "note"
    private const val ADDED_AT_KEY: String = "addedAt"
    private const val START_KEY: String = "startMs"
    private const val END_KEY: String = "endMs"
}

/** Where the song state is kept: `song-state.json` in the app ([SongStateFiles]), memory in tests. */
interface SongStateStorage {
    suspend fun load(): SongState

    suspend fun save(state: SongState)
}

/** [SongStateStorage] on its DataStore, written on change. A file that cannot be read loads as nothing kept. */
class SongStateFiles internal constructor(
    private val store: DataStore<SongState>,
    private val io: CoroutineDispatcher,
    private val log: (String) -> Unit,
) : SongStateStorage {
    override suspend fun load(): SongState = withContext(io) {
        store.data.catch { e ->
            if (e !is IOException) throw e
            log("song state unreadable: ${e.javaClass.simpleName}")
            emit(SongState())
        }.first()
    }

    override suspend fun save(state: SongState) {
        writeWithRetries(onFailure = { log("song state not written: ${it.javaClass.simpleName}") }) { store.updateData { state } }
    }
}

internal class SongStateSerializer(private val backupDir: File, private val clock: () -> Long, private val log: (String) -> Unit) : Serializer<SongState> {
    override val defaultValue: SongState = SongState()

    override suspend fun readFrom(input: InputStream): SongState {
        val bytes = input.readBytes()
        return SongStateJson.decode(bytes.decodeToString()) { log("song state: $it entries this version cannot read left out") }
            ?: throw CorruptionException("Unreadable song state, kept as ${keep(bytes)}")
    }

    override suspend fun writeTo(t: SongState, output: OutputStream) {
        output.write(SongStateJson.encode(t).encodeToByteArray())
    }

    /** The newest unreadable file only, kept in [backupDir] before the store starts over. */
    private fun keep(bytes: ByteArray): String {
        val copies = backupDir.listFiles().orEmpty().filter { it.name.startsWith(BACKUP_PREFIX) && it.name.endsWith(BACKUP_SUFFIX) }
        copies.firstOrNull { it.length() == bytes.size.toLong() && it.readBytes().contentEquals(bytes) }?.let { return it.name }
        val copy = File(backupDir, "$BACKUP_PREFIX${clock()}$BACKUP_SUFFIX")
        Files.write(copy.toPath(), bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.SYNC)
        log("song state unreadable: kept as ${copy.name} (${bytes.size} bytes), starting over")
        copies.forEach { old ->
            try {
                Files.deleteIfExists(old.toPath())
            } catch (e: IOException) {
                log("song state: old copy ${old.name} not removed: ${e.javaClass.simpleName}")
            }
        }
        return copy.name
    }

    private companion object {
        const val BACKUP_PREFIX: String = "song-state-corrupt-"
        const val BACKUP_SUFFIX: String = ".json"
    }
}

/** The song state's DataStore at [file]: one per file and process. */
internal fun songStateStore(file: File, backupDir: File, scope: CoroutineScope, clock: () -> Long, log: (String) -> Unit): DataStore<SongState> =
    DataStoreFactory.create(
        serializer = SongStateSerializer(backupDir, clock, log),
        corruptionHandler = ReplaceFileCorruptionHandler { SongState() },
        scope = scope,
        produceFile = { file },
    )
