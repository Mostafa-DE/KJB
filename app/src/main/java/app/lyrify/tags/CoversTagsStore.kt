package app.lyrify.tags

import app.lyrify.tags.lookup.Doubt
import app.lyrify.tags.lookup.MbRelease
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
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

// What Covers & tags keeps between runs, read leniently: the albums done or skipped (`covers-tags.json`), and what each
// album's last check found (`covers-tags-checks/`).

/** A file as it was when its album was settled: a change to its size or modified time brings the album back. */
@Serializable
data class FileMark(val path: String, val size: Long, val modified: Long)

enum class Settled { DONE, SKIPPED }

/** A song an apply left as it was, and why. */
@Serializable
data class SongProblem(val path: String, val outcome: SongOutcome)

/**
 * An album done or skipped, by its [id]: its [songs] as they were then, and what it shows. DONE: the History entry
 * Undo takes back ([entryId], none when nothing had to change), the songs [changed], and the [problems]; [restored] when
 * a restore brought it from another phone, whose History keeps its Undo.
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
    val restored: Boolean = false,
)

/** A release offered for an album, as kept: the release whole, and its [distance] from the album as the check found it. */
@Serializable
data class KeptCandidate(val release: MbRelease, val distance: Double)

/**
 * What an album's last check found, kept so it stays after KJB closes: the album's files as they were then ([marks]),
 * Ready with its one release or Pick one with its [candidates] (best first) and [doubts], and when it was checked ([at],
 * the order To review lists them in). An album with nothing to suggest is kept too: a later check leaves it out until
 * one of its files changes.
 */
@Serializable
data class CheckedAlbum(
    val id: String,
    val marks: List<FileMark>,
    val ready: Boolean,
    val doubts: Set<Doubt> = emptySet(),
    val candidates: List<KeptCandidate> = emptyList(),
    val at: Long = 0,
)

/**
 * What `covers-tags.json` keeps: the albums settled, whether a check ever ran, whether the sync note was shown, and the
 * newest Covers & tags History entry it has settled ([historySeen]): a newer one without a Done album lost its record to
 * the app's death, and the album is rebuilt from History. [historySeen] is null for a file from before it was kept:
 * what that file settled is unknown, so nothing is rebuilt from it. [checkedAll]: a check ran to its end once, so
 * opening Covers & tags checks new and changed albums by itself; [unfinished]: a check started and was neither done nor
 * cancelled (KJB was closed), so opening Covers & tags carries it on.
 */
data class CoversTagsMemory(
    val albums: List<AlbumRecord> = emptyList(),
    val checkedOnce: Boolean = false,
    val noted: Boolean = false,
    val historySeen: Long? = 0,
    val checkedAll: Boolean = false,
    val unfinished: Boolean = false,
)

/**
 * `covers-tags.json`, app-private: the albums done or skipped, and two flags. Read leniently, as the ledger is: unknown
 * keys ignored, an album this version can't read left out, a file that can't be read at all kept aside and started over.
 * Written to a temporary file, fsynced, then moved over. Beside it, `covers-tags-checks/` holds one file per album
 * checked ([CheckedAlbum]), so a check writes only the album it placed. Logs counts and exception class names only.
 * Blocking.
 */
class CoversTagsStore(private val file: File, private val log: (String) -> Unit = {}) {
    private val checks = File(file.parentFile, CHECKS)

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
        return CoversTagsMemory(
            albums,
            flag(root, CHECKED),
            flag(root, NOTED),
            (root[HISTORY_SEEN] as? JsonPrimitive)?.longOrNull,
            checkedAll = flag(root, CHECKED_ALL),
            unfinished = flag(root, UNFINISHED),
        )
    }

    fun save(memory: CoversTagsMemory): Boolean {
        val root = JsonObject(
            listOfNotNull(
                VERSION_KEY to JsonPrimitive(VERSION),
                CHECKED to JsonPrimitive(memory.checkedOnce),
                NOTED to JsonPrimitive(memory.noted),
                CHECKED_ALL to JsonPrimitive(memory.checkedAll),
                UNFINISHED to JsonPrimitive(memory.unfinished),
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

    /**
     * Every album check kept. A file this version can't read is deleted: its album is only checked again. One that can't
     * be read now (an IOException) is left for the next start. Unknown keys are ignored.
     */
    fun loadChecks(): List<CheckedAlbum> {
        val files = checks.listFiles { one: File -> one.isFile && one.name.endsWith(JSON) }.orEmpty()
        val kept = files.mapNotNull { one ->
            val text = try {
                one.readText()
            } catch (_: IOException) {
                return@mapNotNull null
            }
            readCheck(one.name, text).also { if (it == null) one.delete() }
        }
        if (kept.size < files.size) log("tags: ${files.size - kept.size} kept checks not read")
        return kept
    }

    /** The check in [text], when it reads and is the album its file [name] says. */
    private fun readCheck(name: String, text: String): CheckedAlbum? = try {
        checksJson.decodeFromString(CheckedAlbum.serializer(), text).takeIf { name == it.id + JSON }
    } catch (_: IllegalArgumentException) {
        // A SerializationException is one.
        null
    }

    /**
     * Keeps [checked] in a file of its own, replacing the album's last one. Not fsynced: one lost to a crash is only an
     * album checked again. False when not stored.
     */
    fun saveCheck(checked: CheckedAlbum): Boolean {
        val target = checkFile(checked.id) ?: return false
        val aside = File(target.path + TMP)
        return try {
            checks.mkdirs()
            aside.writeText(checksJson.encodeToString(CheckedAlbum.serializer(), checked))
            Files.move(aside.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            true
        } catch (e: IOException) {
            aside.delete()
            log("tags: a check not kept (${e.javaClass.simpleName})")
            false
        }
    }

    /** Forgets the check kept for album [id]. */
    fun removeCheck(id: String) {
        checkFile(id)?.delete()
    }

    /** The file of album [id]'s check; null for an id that is not an album id ([albumId]: hex digits). */
    private fun checkFile(id: String): File? = if (ALBUM_ID.matches(id)) File(checks, id + JSON) else null

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
        const val CHECKED_ALL = "checkedAll"
        const val UNFINISHED = "unfinished"
        const val HISTORY_SEEN = "historySeen"
        const val TMP = ".tmp"
        const val CHECKS = "covers-tags-checks"
        const val JSON = ".json"
        val ALBUM_ID = Regex("^[0-9a-f]{1,64}$")

        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        /** Kept checks, small: values left at their defaults, and nulls, are not written. */
        val checksJson = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
    }
}

/** [path]'s size and modified time now, or null when it is not a file. Blocking. */
fun fileMark(path: String): FileMark? = File(path).takeIf { it.isFile }?.let { FileMark(path, it.length(), it.lastModified()) }

/**
 * `cover-versions.json`, app-private: the covers' versions by song path ([CoverVersions]), so a changed cover's uri stays
 * new after KJB closes. Read leniently: a file that can't be read is no versions (each cover counts from 0 again, as
 * before they were kept). Written whole to a temporary file, then moved over; not fsynced (one lost to a crash is at
 * worst a car showing a cover it kept until it reads it again). Logs exception class names only. Blocking.
 */
class CoverVersionsFile(private val file: File, private val log: (String) -> Unit = {}) {
    fun load(): Map<String, Int> {
        if (!file.isFile) return emptyMap()
        return try {
            Json.decodeFromString(VERSIONS, file.readText()).filterValues { it > 0 }
        } catch (e: IOException) {
            log("tags: cover versions not read (${e.javaClass.simpleName})")
            emptyMap()
        } catch (e: IllegalArgumentException) {
            // A SerializationException is one.
            log("tags: cover versions unreadable (${e.javaClass.simpleName}), counted from 0")
            emptyMap()
        }
    }

    /** False when not kept. */
    fun save(versions: Map<String, Int>): Boolean {
        val aside = File(file.path + TMP)
        return try {
            file.parentFile?.mkdirs()
            aside.writeText(Json.encodeToString(VERSIONS, versions))
            Files.move(aside.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            true
        } catch (e: IOException) {
            aside.delete()
            log("tags: cover versions not kept (${e.javaClass.simpleName})")
            false
        }
    }

    private companion object {
        val VERSIONS = MapSerializer(String.serializer(), Int.serializer())
        const val TMP = ".tmp"
    }
}
