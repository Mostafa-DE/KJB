package app.lyrify.lyrics

import android.content.Context
import android.util.Log
import app.lyrify.LOG_TAG
import app.lyrify.Lyrify
import app.lyrify.books.isBookPartOnceRead
import app.lyrify.data.LedgerEntry
import app.lyrify.data.LedgerRepository
import app.lyrify.data.LedgerStore
import app.lyrify.data.LyricsStatus
import app.lyrify.data.SharedWrite
import app.lyrify.data.SharedWrites
import app.lyrify.data.recorded
import app.lyrify.library.LibraryGraph
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

/** The hold the pipeline keeps on one song while it handles it ([LyricsPipeline.holding]): the editor's writes wait for it. */
interface PathHold {
    suspend fun <T> holding(path: String, block: suspend () -> T): T
}

/** What Save in the sync editor did. */
sealed interface LrcSave {
    /** The `.lrc` is written and marked as the user's; [undo] puts back what was there. */
    data class Saved(val undo: LrcUndo) : LrcSave

    /**
     * Nothing written: this build may not change that file, the song's file name has no extension, or it is a book's
     * part (books never have lyrics).
     */
    data object Refused : LrcSave

    /**
     * Nothing changed: the old file could not be kept, the new one not written, or the ledger not marked (the file was
     * put back then); or a link, a folder or a file too large to be lyrics is in its place.
     */
    data object Failed : LrcSave
}

/**
 * What Undo needs, kept on disk until it is used or let go: the song, the hash of the bytes saved, the name of the
 * private copy of the `.lrc` before (null: there was none), and the ledger entry before.
 */
@Serializable
data class LrcUndo(val id: String, val audioPath: String, val sha256: String, val backup: String?, val before: LedgerEntry?)

/** What Undo did. */
enum class LrcUndone {
    DONE,

    /** The `.lrc` is not what was saved any more: left as it is, and the Undo let go. */
    CHANGED,

    /** Nothing changed; the Undo waits to be tried again. */
    FAILED,
}

/**
 * The bytes of the `.lrc` at [path] when it is a regular file (never through a link) of at most 1 MiB; null for anything
 * else (missing, a link, a folder, larger). Blocking; throws an IOException when it cannot be read.
 */
fun readSidecar(path: Path): ByteArray? {
    val attributes = try {
        Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    } catch (_: NoSuchFileException) {
        return null
    }
    if (!attributes.isRegularFile || attributes.size() > MAX_LRC_BYTES) return null
    // Read no further than the size just seen (a file that grew meanwhile is being written: not read now), never through a link.
    val size = attributes.size().toInt()
    return Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input ->
        val buffer = ByteArray(size + 1)
        var read = 0
        while (read < buffer.size) {
            val n = input.read(buffer, read, buffer.size - read)
            if (n < 0) break
            read += n
        }
        if (read > size) null else buffer.copyOf(read)
    }
}

private const val MAX_LRC_BYTES: Long = 1024 * 1024

/**
 * The sync editor's writes, one song at a time with the pipeline ([hold]). Save writes the lines as the song's `.lrc` in
 * one step: the bytes go into a temporary file beside it, synced, then renamed into place, so the `.lrc` never holds a
 * part of them, even when the app dies. One that is there already, the user's own from before KJB included, is replaced
 * only by this Save, after its bytes are copied into app-private [folder]. The lyrics are marked in the ledger as the
 * user's ([LedgerEntry.mine], so nothing replaces them by itself): Save goes through the app's one writer for the
 * user's lyrics ([placeMine]), the one Check these uses, so a Save the app's death cuts short is settled at the next
 * start, and a new file the ledger can't mark goes again. Each Save's Undo ([LrcUndo]) is kept in [folder] with its own copy, until it is used ([undo]) or let go
 * ([forget]), also across a restart of the app ([load], [pending]). Undo puts back the entry before, then the copy (or
 * deletes a new file), only while the `.lrc` is still what was saved. Every change to the user's files asks [writes]
 * first. The log gets outcomes and exception names, never a name, a path or a line.
 */
class LrcEdits(
    private val ledger: LedgerStore,
    private val writes: SharedWrites,
    private val hold: PathHold,
    private val folder: File,
    private val clock: () -> Long,
    private val log: (String) -> Unit = {},
    /** How the bytes go into a temporary file; tests make it die halfway. */
    private val writeBytes: (FileChannel, ByteBuffer) -> Unit = SidecarFile::writeFully,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    /** Whether a file is a book's part (books/): a book never gets a `.lrc`. It may wait for the library. */
    private val isBook: suspend (String) -> Boolean = { false },
) {
    private val waiting = MutableStateFlow<List<LrcUndo>>(emptyList())

    /** The Undos not yet used or let go, oldest first. */
    val pending: StateFlow<List<LrcUndo>> = waiting.asStateFlow()

    private val inFlight = MutableStateFlow<Set<String>>(emptySet())

    /** The songs being saved right now, by path. */
    val saving: StateFlow<Set<String>> = inFlight.asStateFlow()

    private var loaded = false

    /** The Undos kept on disk by an earlier run, once; copies left without their Undo (a Save the app's death cut short) go. Blocking. */
    @Synchronized
    fun load() {
        if (loaded) return
        loaded = true
        val files = folder.listFiles().orEmpty()
        val found = files.filter { it.name.endsWith(RECORD) }.mapNotNull { record ->
            val undo = try {
                JSON.decodeFromString(LrcUndo.serializer(), record.readText())
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            } catch (_: IOException) {
                null
            }
            if (undo == null) delete(record.toPath())
            undo
        }
        val copies = found.mapNotNull { it.backup }.toSet()
        files.filter { (it.name.endsWith(COPY) && it.name !in copies) || SidecarFile.isTemp(it.name) }.forEach { delete(it.toPath()) }
        if (files.any { it.name.endsWith(RECORD) } && found.size < files.count { it.name.endsWith(RECORD) }) log("sync editor: unreadable undo records dropped")
        waiting.update { now -> (found.sortedBy { File(folder, it.id + RECORD).lastModified() } + now).distinctBy { it.id } }
    }

    /** Writes [text] (Samsung-safe `.lrc` text) for [track], the song as the library has it. */
    suspend fun save(track: LocalTrack, text: String): LrcSave {
        inFlight.update { it + track.path }
        try {
            return hold.holding(track.path) {
                // The file, its ledger entry and the Undo go together, even if the editor closes.
                withContext(NonCancellable) { saveHeld(track, text) }
            }
        } finally {
            inFlight.update { it - track.path }
        }
    }

    private suspend fun saveHeld(track: LocalTrack, text: String): LrcSave {
        if (isBook(track.path)) return LrcSave.Refused
        val sidecar = LrcFormat.sidecarPath(track.path)?.let { File(it).toPath() } ?: return LrcSave.Refused
        val bytes = LrcFormat.encode(text)
        val before = ledger.entry(track.path)
        val replacing = Files.exists(sidecar, LinkOption.NOFOLLOW_LINKS)
        if (!writes.allows(if (replacing) SharedWrite.LRC_REPLACE else SharedWrite.LRC_CREATE, sidecar.toString())) return LrcSave.Refused
        val id = newId()
        val old = if (replacing) {
            try {
                readSidecar(sidecar)
            } catch (e: IOException) {
                log("sync editor: not saved, the file before not read: ${e.javaClass.simpleName}")
                return LrcSave.Failed
            } ?: run {
                log("sync editor: not saved, no lyrics file in place of the lyrics")
                return LrcSave.Failed
            }
        } else {
            null
        }
        val copy = old?.let { File(folder, id + COPY) }
        if (old != null && copy != null && !keep(copy.toPath(), old)) return LrcSave.Failed
        val sha256 = SidecarFile.sha256(bytes)
        val entry = recorded(before, track, LyricsStatus.SYNCED, before?.lrclibId, clock(), sha256 = sha256, size = bytes.size.toLong(), source = before?.source)
        if (!placeMine(ledger, writes, sidecar, bytes, before, entry, replace = replacing, log, writeBytes)) {
            copy?.let { delete(it.toPath()) }
            log("sync editor: not saved")
            return LrcSave.Failed
        }
        val undo = LrcUndo(id, track.path, sha256, copy?.name, before)
        if (!keep(File(folder, id + RECORD).toPath(), JSON.encodeToString(LrcUndo.serializer(), undo).encodeToByteArray())) {
            log("sync editor: its undo kept for this run only")
        }
        waiting.update { it + undo }
        log("sync editor: saved${if (copy != null) ", the file before kept" else ""}")
        return LrcSave.Saved(undo)
    }

    /**
     * Takes [undo]'s Save back, while the `.lrc` is still what it wrote: the ledger entry before first (nothing else
     * changes when that fails), then the copy or the deletion of a new file (the mark comes back when that fails).
     */
    suspend fun undo(undo: LrcUndo): LrcUndone = hold.holding(undo.audioPath) {
        withContext(NonCancellable) { undoHeld(undo) }
    }

    private suspend fun undoHeld(undo: LrcUndo): LrcUndone {
        val sidecar = LrcFormat.sidecarPath(undo.audioPath)?.let { File(it).toPath() } ?: return LrcUndone.FAILED
        val compared = SidecarFile.compare(sidecar, undo.sha256, log = {})
        if (compared == Compared.CHANGED) {
            forget(undo)
            log("sync editor: not undone, the lyrics changed since")
            return LrcUndone.CHANGED
        }
        if (compared == Compared.UNREADABLE) return LrcUndone.FAILED
        val copy = undo.backup?.let { File(folder, it).toPath() }
        val bytes = try {
            copy?.let(Files::readAllBytes)
        } catch (e: IOException) {
            log("sync editor: not undone: ${e.javaClass.simpleName}")
            return LrcUndone.FAILED
        }
        val marked = ledger.entry(undo.audioPath)
        val entry = undo.before
        if (!(if (entry != null) ledger.put(entry) else ledger.drop(undo.audioPath))) {
            log("sync editor: not undone, the ledger not written")
            return LrcUndone.FAILED
        }
        val back = when {
            bytes != null -> if (compared == Compared.MISSING) {
                writes.allows(SharedWrite.LRC_CREATE, sidecar.toString()) && writeInPlace(sidecar, bytes, replace = false)
            } else {
                writes.allows(SharedWrite.LRC_REPLACE, sidecar.toString()) && writeInPlace(sidecar, bytes, replace = true)
            }
            compared == Compared.MISSING -> true
            else -> SidecarFile.delete(sidecar, writes, log = {}) != Removal.FAILED
        }
        if (!back) {
            if (marked != null && !ledger.put(marked)) log("sync editor: the mark not put back")
            log("sync editor: not undone")
            return LrcUndone.FAILED
        }
        forget(undo)
        log("sync editor: undone")
        return LrcUndone.DONE
    }

    /** [undo] let go: its message is gone. Its record and copy are deleted; the lyrics stay as saved. Blocking. */
    fun forget(undo: LrcUndo) {
        delete(File(folder, undo.id + RECORD).toPath())
        undo.backup?.let { delete(File(folder, it).toPath()) }
        waiting.update { list -> list.filterNot { it.id == undo.id } }
    }

    /** Whether the `.lrc` of the song at [audioPath] holds exactly [text]: an editor brought back after its Save finished. Blocking. */
    fun isOnDisk(audioPath: String, text: String): Boolean {
        val sidecar = LrcFormat.sidecarPath(audioPath)?.let { File(it).toPath() } ?: return false
        val bytes = try {
            readSidecar(sidecar)
        } catch (_: IOException) {
            null
        }
        return bytes != null && bytes.contentEquals(LrcFormat.encode(text))
    }

    /** [bytes] in app-private [path] in one step; false when they could not be kept. */
    private fun keep(path: Path, bytes: ByteArray): Boolean = try {
        Files.createDirectories(path.parent)
        SidecarFile.putWhole(path, bytes, replace = true)
        true
    } catch (e: IOException) {
        log("sync editor: not saved, the file before not kept: ${e.javaClass.simpleName}")
        false
    }

    /**
     * [bytes] at the `.lrc` [sidecar] in one step ([SidecarFile.putWhole]): over the regular file there ([replace]), or
     * where nothing is (checked under the pipeline's hold, so no lookup creates one meanwhile); false when that could not
     * be done, and nothing changed.
     */
    private fun writeInPlace(sidecar: Path, bytes: ByteArray, replace: Boolean): Boolean = try {
        SidecarFile.putWhole(sidecar, bytes, replace, writeBytes)
        true
    } catch (e: IOException) {
        log("sync editor: not written: ${e.javaClass.simpleName}")
        false
    }

    private fun delete(path: Path) {
        try {
            Files.deleteIfExists(path)
        } catch (e: IOException) {
            log("sync editor: a file not deleted: ${e.javaClass.simpleName}")
        }
    }

    companion object {
        private const val RECORD = ".json"
        private const val COPY = ".lrc"
        private val JSON = Json { ignoreUnknownKeys = true }

        private var shared: LrcEdits? = null

        /** The app's one: on the one pipeline's hold and the app's ledger, its Undos in app-private `files/lrc-undo`. */
        @Synchronized
        fun of(context: Context): LrcEdits = shared ?: run {
            val app = context.applicationContext
            LrcEdits(
                ledger = LedgerRepository(app),
                writes = Lyrify.writes,
                hold = Lyrify.pipeline(app),
                folder = File(app.filesDir, "lrc-undo"),
                clock = System::currentTimeMillis,
                log = { Log.d(LOG_TAG, it) },
                // As the pipeline's: known by the library, waited for while it is read; by their path if it never is.
                isBook = { path -> isBookPartOnceRead(path, LibraryGraph.repository(app).state) },
            )
        }.also { shared = it }
    }
}
