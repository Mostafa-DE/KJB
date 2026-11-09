package app.lyrify.tags

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** One field an edit changed, by TagLib property name: its values before and after (empty: no field). */
@Serializable
data class FieldChange(val key: String, val old: List<String>, val new: List<String>)

/** What kind of edit a History entry is (player-spec "Edit tags" > History). */
enum class EditKind { SONG, ALBUM, ARTIST, MERGE, COVERS_TAGS }

/** A picture kept for Undo, by the SHA-256 of its bytes (in `tag-pictures/`), with its tag fields. */
@Serializable
data class PictureRef(val sha256: String, val description: String, val type: String, val mime: String)

/**
 * What one edit did to one song, for History and Undo: the fields it changed (old and new values only), its front cover
 * before and after, the audio payload, and the whole file's SHA-256 [before] and [after] the write, which tells whether
 * the song changed since. Keyed by MediaStore id, with the [path] as a hint.
 */
@Serializable
data class SongEdit(
    val mediaStoreId: Long,
    val path: String,
    val payload: Payload,
    val before: String,
    val after: String,
    val fields: List<FieldChange>,
    val coverChanged: Boolean = false,
    /** The front cover before (null: none); its bytes are kept. */
    val coverBefore: PictureRef? = null,
    /** The front cover the edit wrote (null: it removed it); only its hash matters. */
    val coverAfter: PictureRef? = null,
    val undone: Boolean = false,
)

/** One edit in History: a song, an album, an artist, a merge or a Covers & tags album, with [label] to show. */
@Serializable
data class HistoryEntry(val id: Long, val kind: EditKind, val label: String, val at: Long, val songs: List<SongEdit>)

/**
 * A song's full copy from before KJB first changed it (one generation: a later edit never replaces it), in
 * `tag-backups/` as [file], with what it holds.
 */
@Serializable
data class Snapshot(
    val mediaStoreId: Long,
    val path: String,
    val file: String,
    val length: Long,
    val sha256: String,
    val payload: Payload,
    val entryId: Long,
    val at: Long,
)

data class UndoMemory(val entries: List<HistoryEntry> = emptyList(), val snapshots: List<Snapshot> = emptyList())

/** The entry a song's edit belongs to. */
data class EntryInfo(val id: Long, val kind: EditKind, val label: String, val at: Long)

/** [SongEdit.revert]'s answer: the change that takes the edit back ([edit], null when nothing is left to take back). */
data class Revert(val edit: TagEdit?, val reverted: Int, val skipped: Int)

/**
 * The field-level undo of this edit on a song whose tags are now [current]: each field goes back only where it still
 * holds this edit's new value, so a later edit to the same song is never wiped; the others are [Revert.skipped]. The
 * front cover likewise, from [oldCover] (a cover whose bytes are gone counts as skipped). Pure.
 */
fun SongEdit.revert(current: SongTags, oldCover: (PictureRef) -> TagPicture?): Revert {
    val back = mutableMapOf<String, List<String>>()
    var skipped = 0
    for (change in fields) {
        if (current.fields[change.key].orEmpty() == change.new) back[change.key] = change.old else skipped++
    }
    var cover: CoverEdit = CoverEdit.Keep
    if (coverChanged) {
        val front = TagEdit().applyTo(current).frontAfter
        val stillOurs = front?.sha256 == coverAfter?.sha256
        val old = coverBefore?.let(oldCover)
        cover = when {
            !stillOurs -> CoverEdit.Keep.also { skipped++ }
            coverBefore == null -> CoverEdit.Remove
            old == null -> CoverEdit.Keep.also { skipped++ }
            else -> CoverEdit.Replace(old)
        }
    }
    val edit = TagEdit(fields = back, cover = cover).takeIf { back.isNotEmpty() || cover != CoverEdit.Keep }
    return Revert(edit, back.size + if (cover != CoverEdit.Keep) 1 else 0, skipped)
}

/**
 * History and Undo's stored half (the writer does the undoing): `tag-undo.json` with every entry's field-level record
 * and the snapshots, the snapshot files in `tag-backups/`, and the old covers in `tag-pictures/`. Snapshots are kept
 * [keepMs] (30 days) within [budgetBytes] (2 GB), the oldest entries' dropped first; the field-level record stays.
 * Read leniently, as the ledger is: unknown keys ignored, an entry or snapshot this version can't read left out, a file
 * that can't be read at all kept aside and started over. Logs counts and exception class names only. Blocking;
 * the writer is its only user, one call at a time.
 */
class UndoStore(
    private val dirs: TagDirs,
    private val log: (String) -> Unit = {},
    private val budgetBytes: Long = BUDGET_BYTES,
    private val keepMs: Long = KEEP_MS,
) {
    private var loaded: UndoMemory? = null

    @Synchronized
    fun memory(): UndoMemory = loaded ?: load().also { loaded = it }

    /** The song's snapshot, while it is kept and its file is there. */
    fun snapshot(mediaStoreId: Long): Snapshot? =
        memory().snapshots.firstOrNull { it.mediaStoreId == mediaStoreId }?.takeIf { File(dirs.backups, it.file).isFile }

    /** Whether entry [entryId] can still be undone from full copies; otherwise its row says "Undo has expired". */
    fun hasFullCopy(entryId: Long): Boolean {
        val entry = memory().entries.firstOrNull { it.id == entryId } ?: return false
        return entry.songs.any { song -> snapshot(song.mediaStoreId)?.sha256 == song.before }
    }

    /** A new entry's id: [now], or one more than the last when the clock has not moved on. */
    fun newEntryId(now: Long): Long = maxOf(now, (memory().entries.maxOfOrNull { it.id } ?: Long.MIN_VALUE) + 1)

    /**
     * Records [song] in [entry] (made on its first song), and [snapshot] unless the song already has one. False when it
     * could not be stored.
     */
    @Synchronized
    fun record(entry: EntryInfo, song: SongEdit, snapshot: Snapshot?): Boolean {
        val now = memory()
        val existing = now.entries.firstOrNull { it.id == entry.id }
        val entries = if (existing == null) {
            now.entries + HistoryEntry(entry.id, entry.kind, entry.label, entry.at, listOf(song))
        } else {
            now.entries.map { if (it.id == entry.id) it.copy(songs = it.songs.filterNot { s -> s.mediaStoreId == song.mediaStoreId } + song) else it }
        }
        val snapshots = if (snapshot != null && now.snapshots.none { it.mediaStoreId == snapshot.mediaStoreId }) now.snapshots + snapshot else now.snapshots
        return save(UndoMemory(entries, snapshots))
    }

    @Synchronized
    fun markUndone(entryId: Long, mediaStoreId: Long): Boolean {
        val now = memory()
        val entries = now.entries.map { entry ->
            if (entry.id != entryId) entry else entry.copy(songs = entry.songs.map { if (it.mediaStoreId == mediaStoreId) it.copy(undone = true) else it })
        }
        return save(now.copy(entries = entries))
    }

    /** Keeps [picture]'s bytes for Undo, once per content. Null when it could not be written. */
    fun savePicture(picture: TagPicture): PictureRef? {
        val ref = PictureRef(picture.sha256, picture.description, picture.type, picture.mime)
        val file = File(dirs.pictures, ref.sha256)
        if (file.isFile) return ref
        return try {
            dirs.pictures.mkdirs()
            val aside = File(dirs.pictures, ref.sha256 + TMP)
            aside.writeBytes(picture.data)
            Files.move(aside.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            ref
        } catch (e: IOException) {
            log("undo: cover not kept (${e.javaClass.simpleName})")
            null
        }
    }

    /** The picture kept as [ref], if its bytes are there and whole. */
    fun picture(ref: PictureRef): TagPicture? = try {
        TagPicture(File(dirs.pictures, ref.sha256).readBytes(), ref.description, ref.type, ref.mime).takeIf { it.sha256 == ref.sha256 }
    } catch (_: IOException) {
        null
    }

    /**
     * Drops the snapshots older than [keepMs] at [now], then the oldest entries' snapshots (all of an entry's together)
     * until the rest fit [budgetBytes]; entry [keep]'s stay. The entries stay too, for the field-level undo. Covers no
     * entry needs any more are deleted.
     */
    @Synchronized
    fun prune(now: Long, keep: Long? = null) {
        val memory = memory()
        val fresh = memory.snapshots.filter { now - it.at <= keepMs || it.entryId == keep }.toMutableList()
        val byAge = fresh.map { it.entryId }.distinct().filter { it != keep }.sorted().toMutableList()
        while (fresh.sumOf { it.length } > budgetBytes && byAge.isNotEmpty()) {
            val oldest = byAge.removeAt(0)
            fresh.removeAll { it.entryId == oldest }
        }
        val dropped = memory.snapshots - fresh.toSet()
        if (dropped.isEmpty()) return
        if (!save(memory.copy(snapshots = fresh))) return
        dropped.forEach { File(dirs.backups, it.file).delete() }
        log("undo: ${dropped.size} full copies dropped")
    }

    /** The files in `tag-backups/` the snapshots keep: start-up recovery deletes the others. */
    fun keptBackups(): Set<String> = memory().snapshots.map { it.file }.toSet()

    private fun load(): UndoMemory {
        val file = dirs.undo
        if (!file.isFile) return UndoMemory()
        val text = try {
            file.readText()
        } catch (e: IOException) {
            log("undo: not readable (${e.javaClass.simpleName})")
            return UndoMemory()
        }
        val root = try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (_: SerializationException) {
            null
        }
        if (root == null) {
            // Kept aside for a person to look at, never read again; History starts over.
            file.renameTo(File(file.path + ASIDE))
            log("undo: stored file unreadable (${text.length} chars), kept aside")
            return UndoMemory()
        }
        val entries = items(root[ENTRIES_KEY], HistoryEntry.serializer())
        val snapshots = items(root[SNAPSHOTS_KEY], Snapshot.serializer())
        val dropped = (root[ENTRIES_KEY] as? JsonArray).orEmpty().size - entries.size + (root[SNAPSHOTS_KEY] as? JsonArray).orEmpty().size - snapshots.size
        if (dropped > 0) log("undo: $dropped stored items unreadable, left out")
        return UndoMemory(entries, snapshots)
    }

    private fun save(memory: UndoMemory): Boolean {
        val root = JsonObject(
            mapOf(
                VERSION_KEY to JsonPrimitive(VERSION),
                ENTRIES_KEY to JsonArray(memory.entries.map { json.encodeToJsonElement(HistoryEntry.serializer(), it) }),
                SNAPSHOTS_KEY to JsonArray(memory.snapshots.map { json.encodeToJsonElement(Snapshot.serializer(), it) }),
            ),
        )
        val file = dirs.undo
        val aside = File(file.path + TMP)
        return try {
            file.parentFile?.mkdirs()
            FileOutputStream(aside).use { out ->
                out.write(json.encodeToString(JsonObject.serializer(), root).encodeToByteArray())
                out.fd.sync()
            }
            Files.move(aside.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            loaded = memory
            true
        } catch (e: IOException) {
            aside.delete()
            log("undo: not written (${e.javaClass.simpleName})")
            false
        }
    }

    private fun <T> items(element: JsonElement?, serializer: KSerializer<T>): List<T> =
        (element as? JsonArray).orEmpty().mapNotNull { item ->
            try {
                json.decodeFromJsonElement(serializer, item)
            } catch (_: IllegalArgumentException) {
                // A SerializationException is one.
                null
            }
        }

    companion object {
        const val BUDGET_BYTES: Long = 2L * 1024 * 1024 * 1024
        const val KEEP_MS: Long = 30L * 24 * 60 * 60 * 1000

        /** The suffix of a stored file that could not be read, kept aside. */
        const val ASIDE: String = ".unreadable"

        private const val VERSION: Int = 1
        private const val VERSION_KEY: String = "version"
        private const val ENTRIES_KEY: String = "entries"
        private const val SNAPSHOTS_KEY: String = "snapshots"
        private const val TMP: String = ".tmp"

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
