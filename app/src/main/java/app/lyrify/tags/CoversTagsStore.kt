package app.lyrify.tags

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

// What Covers & tags keeps between runs (`covers-tags.json`): the albums done or skipped, read leniently.

/** A file as it was when its album was settled: a change to its size or modified time brings the album back. */
@Serializable
data class FileMark(val path: String, val size: Long, val modified: Long)

enum class Settled { DONE, SKIPPED }

/** A song an apply left as it was, and why. */
@Serializable
data class SongProblem(val path: String, val outcome: SongOutcome)

/**
 * An album done or skipped, by its [id]: its [songs] as they were then, and what it shows. DONE: the History entry
 * Undo takes back ([entryId], none when nothing had to change), the songs [changed], and the [problems].
 */
@Serializable
data class AlbumRecord(
    val id: String,
    val state: Settled,
    val title: String,
    val artist: String? = null,
    val songs: List<FileMark>,
    val at: Long,
    val entryId: Long? = null,
    val changed: Int = 0,
    val problems: List<SongProblem> = emptyList(),
)

/**
 * What `covers-tags.json` keeps: the albums settled, whether a check ever ran, whether the sync note was shown, and the
 * newest Covers & tags History entry it has settled ([historySeen]): a newer one without a Done album lost its record to
 * the app's death, and the album is rebuilt from History. [historySeen] is null for a file from before it was kept:
 * what that file settled is unknown, so nothing is rebuilt from it.
 */
data class CoversTagsMemory(val albums: List<AlbumRecord> = emptyList(), val checkedOnce: Boolean = false, val noted: Boolean = false, val historySeen: Long? = 0)

/**
 * `covers-tags.json`, app-private: the albums done or skipped, and two flags. Read leniently, as the ledger is: unknown
 * keys ignored, an album this version can't read left out, a file that can't be read at all kept aside and started over.
 * Written to a temporary file, fsynced, then moved over. Logs counts and exception class names only. Blocking.
 */
class CoversTagsStore(private val file: File, private val log: (String) -> Unit = {}) {
    fun load(): CoversTagsMemory {
        if (!file.isFile) return CoversTagsMemory()
        val text = try {
            file.readText()
        } catch (e: IOException) {
            log("tags: covers-tags.json not readable (${e.javaClass.simpleName})")
            return CoversTagsMemory()
        }
        val root = try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (_: SerializationException) {
            null
        }
        if (root == null) {
            file.renameTo(File(file.path + UndoStore.ASIDE))
            log("tags: covers-tags.json unreadable (${text.length} chars), kept aside")
            return CoversTagsMemory()
        }
        val items = root[ALBUMS] as? JsonArray ?: JsonArray(emptyList())
        val albums = items.mapNotNull { decode(it, AlbumRecord.serializer()) }
        if (albums.size < items.size) log("tags: ${items.size - albums.size} stored albums unreadable, left out")
        // No historySeen: a file from before it was kept (null, never 0, which would take every History entry for lost).
        return CoversTagsMemory(albums, flag(root, CHECKED), flag(root, NOTED), (root[HISTORY_SEEN] as? JsonPrimitive)?.longOrNull)
    }

    fun save(memory: CoversTagsMemory): Boolean {
        val root = JsonObject(
            listOfNotNull(
                VERSION_KEY to JsonPrimitive(VERSION),
                CHECKED to JsonPrimitive(memory.checkedOnce),
                NOTED to JsonPrimitive(memory.noted),
                memory.historySeen?.let { HISTORY_SEEN to JsonPrimitive(it) },
                ALBUMS to JsonArray(memory.albums.map { json.encodeToJsonElement(AlbumRecord.serializer(), it) }),
            ).toMap(),
        )
        val aside = File(file.path + TMP)
        return try {
            file.parentFile?.mkdirs()
            FileOutputStream(aside).use { out ->
                out.write(json.encodeToString(JsonObject.serializer(), root).encodeToByteArray())
                out.fd.sync()
            }
            Files.move(aside.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            true
        } catch (e: IOException) {
            aside.delete()
            log("tags: covers-tags.json not saved (${e.javaClass.simpleName})")
            false
        }
    }

    private fun <T> decode(element: JsonElement, serializer: KSerializer<T>): T? = try {
        json.decodeFromJsonElement(serializer, element)
    } catch (_: IllegalArgumentException) {
        // A SerializationException is one.
        null
    }

    private fun flag(root: JsonObject, key: String): Boolean = (root[key] as? JsonPrimitive)?.booleanOrNull ?: false

    private companion object {
        const val VERSION = 1
        const val VERSION_KEY = "version"
        const val ALBUMS = "albums"
        const val CHECKED = "checkedOnce"
        const val NOTED = "noted"
        const val HISTORY_SEEN = "historySeen"
        const val TMP = ".tmp"

        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}

/** [path]'s size and modified time now, or null when it is not a file. Blocking. */
fun fileMark(path: String): FileMark? = File(path).takeIf { it.isFile }?.let { FileMark(path, it.length(), it.lastModified()) }
