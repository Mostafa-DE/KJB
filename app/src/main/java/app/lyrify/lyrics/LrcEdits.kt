package app.lyrify.lyrics

import android.content.Context
import android.util.Log
import app.lyrify.LOG_TAG
import app.lyrify.Lyrify
import app.lyrify.data.LedgerEntry
import app.lyrify.data.LedgerRepository
import app.lyrify.data.LedgerStore
import app.lyrify.data.LyricsStatus
import app.lyrify.data.SharedWrite
import app.lyrify.data.SharedWrites
import app.lyrify.data.recorded
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/** The hold the pipeline keeps on one song while it handles it ([LyricsPipeline.holding]): the editor's writes wait for it. */
interface PathHold {
    suspend fun <T> holding(path: String, block: suspend () -> T): T
}

/** What Save in the sync editor did. */
sealed interface LrcSave {
    /** The `.lrc` is written and marked as the user's; [undo] puts back what was there. */
    data class Saved(val undo: LrcUndo) : LrcSave

    /** Nothing written: this build may not change that file, or the song's file name has no extension. */
    data object Refused : LrcSave

    /** Nothing changed: the old file could not be kept, or the new one not written (or a link or folder is in its place). */
    data object Failed : LrcSave
}

/** What Undo needs: the song, the hash of the bytes saved, the private copy of the `.lrc` before (null: there was none), the ledger entry before. */
data class LrcUndo(val audioPath: String, val sha256: String, val backup: File?, val before: LedgerEntry?)

/** What Undo did. */
enum class LrcUndone {
    DONE,

    /** The `.lrc` is not what was saved any more: left as it is. */
    CHANGED,
    FAILED,
}

/**
 * The sync editor's writes, one song at a time with the pipeline ([hold]). Save writes the lines as the song's `.lrc`
 * and marks them in the ledger as the user's ([LedgerEntry.mine], so nothing replaces them by itself). A new `.lrc` is
 * created as the pipeline creates one (exclusive create, synced to storage). One that is there already, the user's own
 * from before KJB included, is replaced only by this Save: its bytes are first copied into app-private [backups] (one
 * copy per song, of what the last Save replaced), then the new bytes go into a temporary file beside it, synced, and
 * are renamed over it in one step (`.lrc` files are not MediaStore audio). A link or folder in its place is never
 * written through. Undo puts back the copy (or deletes a new file) and the entry before, only while the `.lrc` is still
 * what was saved. Every change asks [writes] first. The log gets outcomes and exception names, never a name, a path or
 * a line.
 */
class LrcEdits(
    private val ledger: LedgerStore,
    private val writes: SharedWrites,
    private val hold: PathHold,
    private val backups: File,
    private val clock: () -> Long,
    private val log: (String) -> Unit = {},
) {
    /** Writes [text] (Samsung-safe `.lrc` text) for [track], the song as the library has it. */
    suspend fun save(track: LocalTrack, text: String): LrcSave = hold.holding(track.path) {
        val sidecar = LrcFormat.sidecarPath(track.path)?.let { File(it).toPath() } ?: return@holding LrcSave.Refused
        // The file and its ledger entry go together, even if the editor closes.
        withContext(NonCancellable) {
            val bytes = LrcFormat.encode(text)
            val before = ledger.entry(track.path)
            val replacing = Files.exists(sidecar, LinkOption.NOFOLLOW_LINKS)
            if (!writes.allows(if (replacing) SharedWrite.LRC_REPLACE else SharedWrite.LRC_CREATE, sidecar.toString())) return@withContext LrcSave.Refused
            val backup = if (replacing) {
                if (!Files.isRegularFile(sidecar, LinkOption.NOFOLLOW_LINKS)) {
                    log("sync editor: not saved, no regular file in place of the lyrics")
                    return@withContext LrcSave.Failed
                }
                backUp(track.path, sidecar) ?: return@withContext LrcSave.Failed
            } else {
                null
            }
            val written = if (backup != null) replace(sidecar, bytes) else SidecarFile.create(sidecar, bytes, writes, log = {}) == Created.YES
            if (!written) {
                log("sync editor: not saved")
                return@withContext LrcSave.Failed
            }
            val sha256 = SidecarFile.sha256(bytes)
            val entry = recorded(before, track, LyricsStatus.SYNCED, before?.lrclibId, clock(), sha256 = sha256, size = bytes.size.toLong(), source = before?.source)
            // Should this fail, the file stays: the pipeline finds it there and never replaces it either.
            if (!ledger.put(entry.copy(mine = true))) log("sync editor: saved, not marked in the ledger")
            log("sync editor: saved${if (backup != null) ", the file before kept" else ""}")
            LrcSave.Saved(LrcUndo(track.path, sha256, backup, before))
        }
    }

    /** Takes [undo]'s Save back, while the `.lrc` is still what it wrote. */
    suspend fun undo(undo: LrcUndo): LrcUndone = hold.holding(undo.audioPath) {
        val sidecar = LrcFormat.sidecarPath(undo.audioPath)?.let { File(it).toPath() } ?: return@holding LrcUndone.FAILED
        withContext(NonCancellable) {
            val compared = SidecarFile.compare(sidecar, undo.sha256, log = {})
            if (compared == Compared.CHANGED) {
                log("sync editor: not undone, the lyrics changed since")
                return@withContext LrcUndone.CHANGED
            }
            if (compared == Compared.UNREADABLE) return@withContext LrcUndone.FAILED
            val backup = undo.backup
            val back = when {
                backup != null -> {
                    val bytes = try {
                        backup.readBytes()
                    } catch (e: IOException) {
                        log("sync editor: not undone: ${e.javaClass.simpleName}")
                        return@withContext LrcUndone.FAILED
                    }
                    if (compared == Compared.MISSING) {
                        writes.allows(SharedWrite.LRC_CREATE, sidecar.toString()) && SidecarFile.create(sidecar, bytes, writes, log = {}) == Created.YES
                    } else {
                        writes.allows(SharedWrite.LRC_REPLACE, sidecar.toString()) && replace(sidecar, bytes)
                    }
                }
                compared == Compared.MISSING -> true
                else -> SidecarFile.delete(sidecar, writes, log = {}) != Removal.FAILED
            }
            if (!back) {
                log("sync editor: not undone")
                return@withContext LrcUndone.FAILED
            }
            val entry = undo.before
            val stored = if (entry != null) ledger.put(entry) else ledger.drop(undo.audioPath)
            if (!stored) log("sync editor: undone, the ledger not written")
            if (backup != null) deleteBackup(backup)
            log("sync editor: undone")
            LrcUndone.DONE
        }
    }

    /** A private copy of the `.lrc` at [sidecar], in [backups] by [audioPath]'s hash; null when it could not be made. */
    private fun backUp(audioPath: String, sidecar: Path): File? = try {
        val bytes = Files.readAllBytes(sidecar)
        Files.createDirectories(backups.toPath())
        val copy = File(backups, SidecarFile.sha256(audioPath.toByteArray()) + ".lrc")
        val temp = File(backups, copy.name + TEMP_SUFFIX).toPath()
        writeSynced(temp, bytes)
        Files.move(temp, copy.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        copy
    } catch (e: IOException) {
        log("sync editor: the file before not kept: ${e.javaClass.simpleName}")
        null
    }

    /** [bytes] in place of the regular file at [sidecar]: a temporary file beside it, synced, then renamed over it. */
    private fun replace(sidecar: Path, bytes: ByteArray): Boolean {
        val temp = sidecar.resolveSibling("." + sidecar.fileName + TEMP_SUFFIX)
        return try {
            // A temporary file of an edit the app's death cut short.
            Files.deleteIfExists(temp)
            writeSynced(temp, bytes)
            Files.move(temp, sidecar, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            true
        } catch (e: IOException) {
            log("sync editor: not written: ${e.javaClass.simpleName}")
            try {
                Files.deleteIfExists(temp)
            } catch (_: IOException) {
                log("sync editor: a temporary file left")
            }
            false
        }
    }

    private fun writeSynced(path: Path, bytes: ByteArray) {
        FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
            SidecarFile.writeFully(channel, ByteBuffer.wrap(bytes))
            channel.force(true)
        }
    }

    private fun deleteBackup(backup: File) {
        try {
            Files.deleteIfExists(backup.toPath())
        } catch (e: IOException) {
            log("sync editor: a kept file not deleted: ${e.javaClass.simpleName}")
        }
    }

    companion object {
        private const val TEMP_SUFFIX = ".kjb-edit"

        private var shared: LrcEdits? = null

        /** The app's one: on the one pipeline's hold and the app's ledger, copies in app-private `files/lrc-backups`. */
        @Synchronized
        fun of(context: Context): LrcEdits = shared ?: run {
            val app = context.applicationContext
            LrcEdits(
                ledger = LedgerRepository(app),
                writes = Lyrify.writes,
                hold = Lyrify.pipeline(app),
                backups = File(app.filesDir, "lrc-backups"),
                clock = System::currentTimeMillis,
                log = { Log.d(LOG_TAG, it) },
            )
        }.also { shared = it }
    }
}
