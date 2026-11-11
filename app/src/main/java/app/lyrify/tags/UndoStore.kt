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
import java.nio.file.NoSuchFileException
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
@Serializable
data class EntryInfo(val id: Long, val kind: EditKind, val label: String, val at: Long)

/**
 * What a write records once it is done ([UndoStore.save]): a write's [edit] in [entry], with its [snapshot] when it is the
 * song's first; or, for an Undo, that [undoneEntry] was taken back for the song. Carried in the write's journal intent,
 * so the start-up recovery saves it when the app died, or the store could not be written, after the write.
 */
@Serializable
data class UndoRecord(
    val entry: EntryInfo? = null,
    val edit: SongEdit? = null,
    val snapshot: Snapshot? = null,
    val undoneEntry: Long? = null,
)

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
            // The cover this edit added goes, and only that picture.
            coverBefore == null -> coverAfter?.let { CoverEdit.RemovePicture(it.sha256) } ?: CoverEdit.Keep
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
 * History is read leniently, as the ledger is: unknown keys ignored, an entry this version can't read left out. The
 * snapshots are not: a file that can't be read or parsed, or a snapshot this version can't read, makes [keptBackups]
 * throw, and nothing is pruned or saved over the file ([record] and [markUndone] answer false) until it reads whole
 * again; [memory] then shows nothing. Logs counts and exception class names only. Blocking; the writer is its only
 * user, one call at a time.
 */
class UndoStore(
    private val dirs: TagDirs,
    private val log: (String) -> Unit = {},
    private val budgetBytes: Long = BUDGET_BYTES,
    private val keepMs: Long = KEEP_MS,
) {
    private var loaded: UndoMemory? = null

    /** Why the stored file could not be read whole at the last try, or null when it could. */
    private var unreadable: IOException? = null

    /** History and the snapshots, as far as they can be read: nothing, for now, when the stored file can't be. */
    @Synchronized
    fun memory(): UndoMemory = loaded ?: load().also { if (unreadable == null) loaded = it }

    /**
     * [memory], read whole, or a throw when the stored file can't be read or parsed: what lists, prunes or saves over
     * the snapshots works from this, never from an empty stand-in.
     */
    @Synchronized
    fun whole(): UndoMemory {
        val memory = memory()
        unreadable?.let { throw IOException("Undo's list could not be read", it) }
        return memory
    }

    /**
     * The song's snapshot, while it is kept and its file is there. Throws when Undo's list can't be read whole, or the
     * snapshot's file looked at: an error is never taken for no snapshot.
     */
    fun snapshot(mediaStoreId: Long): Snapshot? =
        whole().snapshots.firstOrNull { it.mediaStoreId == mediaStoreId }?.takeIf { regularFile(File(dirs.backups, it.file)) }

    /** Whether entry [entryId] can still be undone from full copies; otherwise its row says "Undo has expired". */
    fun hasFullCopy(entryId: Long): Boolean {
        val entry = memory().entries.firstOrNull { it.id == entryId } ?: return false
        return try {
            entry.songs.any { song -> snapshot(song.mediaStoreId)?.sha256 == song.before }
        } catch (e: IOException) {
            log("undo: full copies could not be looked at (${e.javaClass.simpleName})")
            false // safe: History's words only; the Undo itself asks again, and writes nothing when it can't tell
        }
    }

    /** A new entry's id: [now], or one more than the last when the clock has not moved on. */
    fun newEntryId(now: Long): Long = maxOf(now, (memory().entries.maxOfOrNull { it.id } ?: Long.MIN_VALUE) + 1)

    /**
     * Records [song] in [entry] (made on its first song), and [snapshot] unless the song already has one. False when it
     * could not be stored.
     */
    @Synchronized
    fun record(entry: EntryInfo, song: SongEdit, snapshot: Snapshot?): Boolean {
        val now = wholeOrNull() ?: return false
        val existing = now.entries.firstOrNull { it.id == entry.id }
        val entries = if (existing == null) {
            now.entries + HistoryEntry(entry.id, entry.kind, entry.label, entry.at, listOf(song))
        } else {
            now.entries.map { if (it.id == entry.id) it.copy(songs = it.songs.filterNot { s -> s.mediaStoreId == song.mediaStoreId } + song) else it }
        }
        val snapshots = if (snapshot != null && now.snapshots.none { it.mediaStoreId == snapshot.mediaStoreId }) now.snapshots + snapshot else now.snapshots
        return save(UndoMemory(entries, snapshots))
    }

    /** Saves [record] for song [mediaStoreId]; saving it again changes nothing. False when it could not be stored. */
    fun save(record: UndoRecord, mediaStoreId: Long): Boolean = when {
        record.undoneEntry != null -> markUndone(record.undoneEntry, mediaStoreId)
        record.entry != null && record.edit != null -> record(record.entry, record.edit, record.snapshot)
        else -> true
    }

    @Synchronized
    fun markUndone(entryId: Long, mediaStoreId: Long): Boolean {
        val now = wholeOrNull() ?: return false
        val entries = now.entries.map { entry ->
            if (entry.id != entryId) entry else entry.copy(songs = entry.songs.map { if (it.mediaStoreId == mediaStoreId) it.copy(undone = true) else it })
        }
        return save(now.copy(entries = entries))
    }

    /** Keeps [picture]'s bytes for Undo, once per content, durably. Throws when they could not be kept. */
    fun savePicture(picture: TagPicture): PictureRef {
        val ref = PictureRef(picture.sha256, picture.description, picture.type, picture.mime)
        val file = File(dirs.pictures, ref.sha256)
        if (picture(ref) != null) return ref
        val aside = File(dirs.pictures, ref.sha256 + TMP)
        try {
            dirs.pictures.mkdirs()
            FileOutputStream(aside).use { out ->
                out.write(picture.data)
                out.fd.sync()
            }
            Files.move(aside.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            dirs.syncDir(dirs.pictures)
            return ref
        } catch (e: IOException) {
            aside.delete()
            log("undo: cover not kept (${e.javaClass.simpleName})")
            throw e
        }
    }

    /**
     * The picture kept as [ref], or null when its bytes are not there or not whole (Undo's own file, damaged). Throws
     * when they can't be read.
     */
    fun picture(ref: PictureRef): TagPicture? = try {
        TagPicture(Files.readAllBytes(File(dirs.pictures, ref.sha256).toPath()), ref.description, ref.type, ref.mime).takeIf { it.sha256 == ref.sha256 }
    } catch (_: NoSuchFileException) {
        null
    }

    /**
     * Drops the snapshots older than [keepMs] at [now], then the oldest entries' snapshots (all of an entry's together)
     * until the rest fit [budgetBytes]; entry [keep]'s stay. The entries stay too, for the field-level undo. Covers no
     * entry needs any more are deleted. Nothing is pruned while the stored file can't be read whole.
     */
    @Synchronized
    fun prune(now: Long, keep: Long? = null) {
        val memory = wholeOrNull() ?: return
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

    /**
     * The files in `tag-backups/` the snapshots keep: [Backups.releaseBackup] never deletes one. Throws when the stored
     * file can't be read whole, so a backup is never taken for one no snapshot keeps.
     */
    fun keptBackups(): Set<String> = whole().snapshots.map { it.file }.toSet()

    /** [whole], or null (logged) when the stored file can't be read whole. */
    private fun wholeOrNull(): UndoMemory? = try {
        whole()
    } catch (e: IOException) {
        log("undo: not changed, its list can't be read (${e.javaClass.simpleName})")
        null // safe: a failure report: record and markUndone answer false (not saved), prune does nothing
    }

    /** The stored file, read; [unreadable] says whether it was read whole. */
    private fun load(): UndoMemory {
        unreadable = null
        val text = try {
            Files.readAllBytes(dirs.undo.toPath()).decodeToString()
        } catch (_: NoSuchFileException) {
            // Nothing stored yet.
            return UndoMemory()
        } catch (e: IOException) {
            log("undo: not readable (${e.javaClass.simpleName})")
            unreadable = e
            return UndoMemory() // safe: shown as nothing while unreadable is set; whole() throws, and nothing is saved
        }
        val root = try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (_: SerializationException) {
            null
        }
        if (root == null) {
            // Left as it is, for a person to look at: never saved over, and no backup taken for one no snapshot keeps.
            log("undo: stored file unreadable (${text.length} chars), left as it is")
            unreadable = IOException("not Undo's list")
            return UndoMemory()
        }
        val entries = items(root[ENTRIES_KEY], HistoryEntry.serializer())
        val snapshots = items(root[SNAPSHOTS_KEY], Snapshot.serializer())
        // A snapshot this version can't read may name a backup: the list is not whole.
        if (snapshots.size != (root[SNAPSHOTS_KEY] as? JsonArray).orEmpty().size) unreadable = IOException("a snapshot can't be read")
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
            // The file holds it now, durable or not: read back the same way either way.
            loaded = memory
            dirs.syncDir(checkNotNull(file.parentFile))
            true
        } catch (e: IOException) {
            aside.delete()
            log("undo: not saved durably (${e.javaClass.simpleName})")
            false // safe: a failure report: the caller keeps the intent and its backup, and says Undo was not saved
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
