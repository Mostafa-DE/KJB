package app.lyrify.tags

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import app.lyrify.data.PathLocks
import app.lyrify.data.SharedWrite
import app.lyrify.data.SharedWrites
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * Where the tag writer keeps its files, all in the app's own storage (never backed up: allowBackup is off), and how a
 * folder is fsynced ([syncDir], [fsyncDirectory] on the phone; it throws when it can't, and then nothing written in
 * that folder counts as durable).
 */
class TagDirs(root: File, val syncDir: (File) -> Unit) {
    /** One fsynced intent per write in progress ([WriteJournal]). */
    val journal: File = File(root, "tag-journal")

    /** The durable copy of each song before it is written; a song's first one is kept for Undo ([UndoStore]). */
    val backups: File = File(root, "tag-backups")

    /** The second private copy TagLib edits; deleted after each song. */
    val work: File = File(root, "tag-work")

    /** Old front covers, for the field-level Undo. */
    val pictures: File = File(root, "tag-pictures")

    /** History and Undo ([UndoStore]). */
    val undo: File = File(root, "tag-undo.json")
}

/**
 * Fsyncs the folder [dir] itself, so what was created, moved or deleted in it stays so: `open(O_RDONLY)`, a check that
 * it is a folder (`fstat`; the SDK does not expose O_DIRECTORY), `fsync`. Throws when any step fails.
 */
fun fsyncDirectory(dir: File) {
    val fd = try {
        Os.open(dir.path, OsConstants.O_RDONLY or OsConstants.O_CLOEXEC, 0)
    } catch (e: ErrnoException) {
        throw IOException("folder not opened", e)
    }
    try {
        if (!OsConstants.S_ISDIR(Os.fstat(fd).st_mode)) throw IOException("not a folder")
        Os.fsync(fd)
    } catch (e: ErrnoException) {
        throw IOException("folder not fsynced", e)
    } finally {
        try {
            Os.close(fd)
        } catch (_: ErrnoException) {
            // Already fsynced, or already failed.
        }
    }
}

/** Whether [file] is a regular file itself, never one reached through a symbolic link. */
fun regularFile(file: File): Boolean = Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)

/** A file as the writer checks it: its length, the SHA-256 of all its bytes, and its audio payload. */
@Serializable
data class FileState(val length: Long, val sha256: String, val payload: Payload)

/**
 * The state of the file at [file], or null when it is not a regular file (a link is never followed), not a well-formed
 * file of an allowed format ([PayloadHash]), or can't be read. Blocking.
 */
fun fileState(file: File): FileState? = try {
    val payload = if (regularFile(file)) PayloadHash.of(file.toPath()) else null
    payload?.let { FileState(file.length(), PayloadHash.fileSha256(file.toPath()), it) }
} catch (_: IOException) {
    null
}

/**
 * One write in progress, recorded before any byte of the song changes: the song, its backup in `tag-backups/`, the
 * song's state before ([original]) and as it will be once written ([expected]), and the History record the write makes
 * ([record]), which the start-up recovery saves if the app died, or Undo could not be saved, after the write.
 */
@Serializable
data class WriteIntent(
    val id: String,
    val path: String,
    val mediaStoreId: Long,
    val backup: String,
    val original: FileState,
    val expected: FileState,
    val record: UndoRecord? = null,
)

/** Copies [from] to [to] and fsyncs the copy and its folder: on disk once this returns, else it throws. */
fun durableCopy(from: File, to: File, syncDir: (File) -> Unit) {
    to.parentFile?.mkdirs()
    Files.copy(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
    FileChannel.open(to.toPath(), StandardOpenOption.WRITE).use { it.force(true) }
    syncDir(checkNotNull(to.parentFile))
}

/**
 * Writes [source]'s bytes into [channel] (a song opened in place, "rw") from its start, cuts it to [length], and fsyncs
 * it; closing is the caller's. Never a rename, never "w" or "rwt": the song keeps its MediaStore row.
 */
fun writeInPlace(channel: FileChannel, source: File, length: Long) {
    FileInputStream(source).use { input ->
        val from = input.channel
        val buffer = ByteBuffer.allocate(COPY_BYTES)
        channel.position(0)
        var at = 0L
        while (at < length) {
            buffer.clear().limit(minOf(COPY_BYTES.toLong(), length - at).toInt())
            if (from.read(buffer, at) < 0) throw IOException("source ended early")
            buffer.flip()
            while (buffer.hasRemaining()) at += channel.write(buffer)
        }
    }
    channel.truncate(length)
    channel.force(true)
}

private const val COPY_BYTES: Int = 256 * 1024

/**
 * The write journal (`tag-journal/`): one intent file per write, written aside, fsynced and moved into place, its
 * folder fsynced, before the song's first byte changes; deleted once the song is settled. Logs counts and exception
 * class names only. Blocking.
 */
class WriteJournal(private val dir: File, private val syncDir: (File) -> Unit, private val log: (String) -> Unit = {}) {
    /** Records [intent] durably. False when it could not be: then no intent is left, and the song must not be opened. */
    fun open(intent: WriteIntent): Boolean {
        val file = File(dir, intent.id + SUFFIX)
        val aside = File(dir, intent.id + SUFFIX + TMP)
        return try {
            dir.mkdirs()
            FileOutputStream(aside).use { out ->
                out.write(json.encodeToString(WriteIntent.serializer(), intent).encodeToByteArray())
                out.fd.sync()
            }
            Files.move(aside.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            syncDir(dir)
            true
        } catch (e: IOException) {
            aside.delete()
            file.delete()
            log("journal: intent not written (${e.javaClass.simpleName})")
            false
        }
    }

    /** Closes the intent [id]: the song is settled. */
    fun close(id: String): Boolean {
        val gone = File(dir, id + SUFFIX).let { !it.exists() || it.delete() }
        try {
            syncDir(dir)
        } catch (e: IOException) {
            // Should the intent come back after a crash, the recovery finds its song settled and closes it again.
            log("journal: folder not fsynced after a close (${e.javaClass.simpleName})")
        }
        return gone
    }

    /**
     * The intents still open, oldest first. An intent the app's death cut short while it was written aside is deleted:
     * the song was never opened then. One that can't be read is set aside, never acted on.
     */
    fun pending(): List<WriteIntent> {
        val files = dir.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }
        files.filter { it.name.endsWith(TMP) }.forEach { it.delete() }
        return files.filter { it.name.endsWith(SUFFIX) }.mapNotNull { file ->
            read(file) ?: null.also {
                setAside(file.name.removeSuffix(SUFFIX))
                log("journal: an intent could not be read, set aside")
            }
        }
    }

    /** The songs with an intent still open (read only): the writer leaves them alone until they are settled. */
    fun openPaths(): Set<String> =
        dir.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(SUFFIX) }.mapNotNull { read(it)?.path }.toSet()

    /** Whether an intent named [id] is open or set aside. */
    fun has(id: String): Boolean = File(dir, id + SUFFIX).exists() || File(File(dir, SET_ASIDE), id + SUFFIX).exists()

    /** Moves intent [id] to [SET_ASIDE], for a person to look at; it is never acted on again. */
    fun setAside(id: String) {
        val aside = File(dir, SET_ASIDE).apply { mkdirs() }
        File(dir, id + SUFFIX).renameTo(File(aside, id + SUFFIX))
        try {
            syncDir(dir)
        } catch (e: IOException) {
            log("journal: folder not fsynced after setting aside (${e.javaClass.simpleName})")
        }
    }

    private fun read(file: File): WriteIntent? = try {
        json.decodeFromString(WriteIntent.serializer(), file.readText())
    } catch (_: IOException) {
        null
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    companion object {
        /** The folder in `tag-journal/` for intents that could not be settled: never acted on again. */
        const val SET_ASIDE: String = "set-aside"
        private const val SUFFIX: String = ".json"
        private const val TMP: String = ".tmp"
        private val json = Json { ignoreUnknownKeys = true }
    }
}

/** What the start-up recovery did: intents closed ([restored] of them by writing the backup back), set aside, left. */
data class Recovered(val settled: Int = 0, val restored: Int = 0, val setAside: Int = 0, val waiting: Int = 0)

/**
 * The start-up recovery. [holdPending] runs first, at process start (TagsGraph), and holds the song of every intent
 * still open in [locks], so the lyrics pipeline, the AI and the tone preview stay off them; then [recover] settles each
 * one and lets it go:
 * - a song that is not a regular file at its path (a link is never followed), not mounted, or no longer MediaStore's
 *   row [WriteIntent.mediaStoreId] waits for the next start, untouched;
 * - a song exactly as written keeps its new tags, and the write's History record is saved (if that still fails, the
 *   intent waits);
 * - a song exactly as before is left so;
 * - anything else gets its backup written back, in place, if the backup is whole (a regular file with the original's
 *   length and SHA-256); else the intent is set aside with its backup and the song is left alone.
 * Then the private copies and the backups nothing needs are deleted. Logs counts only.
 */
class JournalRecovery(
    private val dirs: TagDirs,
    private val journal: WriteJournal,
    private val undo: UndoStore,
    private val songs: SongFiles,
    private val locks: PathLocks,
    private val writes: SharedWrites,
    private val log: (String) -> Unit = {},
) {
    /** The intents still open, with their songs held now. Not suspending: called before anything else can take them. */
    fun holdPending(): List<WriteIntent> {
        val intents = journal.pending()
        intents.map { it.path }.distinct().forEach { path ->
            if (!locks.tryHold(path)) log("journal: a song of an unfinished write was already held")
        }
        return intents
    }

    /**
     * Settles [held] (from [holdPending], whose songs this lets go of as each is settled), or, when null, every intent
     * still open, holding each song first.
     */
    suspend fun recover(held: List<WriteIntent>? = null): Recovered {
        val asideBefore = setAside().size
        val intents = held ?: journal.pending().also { pending -> pending.map { it.path }.distinct().forEach { locks.hold(it) } }
        val last = intents.withIndex().associate { (index, intent) -> intent.path to index }
        var result = Recovered()
        intents.forEachIndexed { index, intent ->
            try {
                result = when (settle(intent)) {
                    Settled.AS_IT_WAS -> result.copy(settled = result.settled + 1)
                    Settled.RESTORED -> result.copy(settled = result.settled + 1, restored = result.restored + 1)
                    Settled.SET_ASIDE -> result
                    Settled.WAITING -> result.copy(waiting = result.waiting + 1)
                }
            } finally {
                if (last[intent.path] == index) locks.release(intent.path)
            }
        }
        cleanUp()
        result = result.copy(setAside = setAside().size - asideBefore)
        if (result != Recovered()) log("journal: ${result.settled} unfinished writes settled (${result.restored} put back), ${result.setAside} set aside, ${result.waiting} waiting")
        return result
    }

    private enum class Settled { AS_IT_WAS, RESTORED, SET_ASIDE, WAITING }

    private suspend fun settle(intent: WriteIntent): Settled {
        val song = File(intent.path)
        if (!songs.mounted(intent.path) || !regularFile(song) || !songs.sameFile(intent.mediaStoreId, intent.path)) return Settled.WAITING
        val now = fileState(song)
        val written = now != null && now.sha256 == intent.expected.sha256 && now.length == intent.expected.length
        val untouched = now != null && now.sha256 == intent.original.sha256 && now.length == intent.original.length
        if (written) {
            // The write finished; its History record may not have been saved.
            if (intent.record != null && !undo.save(intent.record, intent.mediaStoreId)) return Settled.WAITING
            closeAndDrop(intent)
            return Settled.AS_IT_WAS
        }
        if (untouched) {
            closeAndDrop(intent)
            return Settled.AS_IT_WAS
        }
        // Neither: the write was cut short. Written back only from a whole backup.
        val backup = File(dirs.backups, intent.backup)
        val saved = if (regularFile(backup) && backup.length() == intent.original.length) fileState(backup) else null
        if (saved != intent.original || !writes.allows(SharedWrite.TAG_RESTORE, intent.path)) {
            journal.setAside(intent.id)
            return Settled.SET_ASIDE
        }
        val channel = songs.openInPlace(SongTarget(intent.path, intent.mediaStoreId)) ?: return Settled.WAITING
        try {
            channel.use { writeInPlace(it, backup, intent.original.length) }
        } catch (e: IOException) {
            log("journal: backup not written back yet (${e.javaClass.simpleName})")
            return Settled.WAITING
        }
        if (fileState(song) != intent.original) return Settled.WAITING
        songs.scan(intent.path)
        closeAndDrop(intent)
        return Settled.RESTORED
    }

    /** Closes [intent], and deletes its backup unless it is a song's snapshot. */
    private fun closeAndDrop(intent: WriteIntent) {
        journal.close(intent.id)
        if (intent.backup !in undo.keptBackups()) File(dirs.backups, intent.backup).delete()
    }

    /** Deletes the private copies, and the backups no snapshot, open intent or intent set aside needs. */
    private fun cleanUp() {
        dirs.work.listFiles().orEmpty().forEach { it.delete() }
        val needed = undo.keptBackups() + journal.pending().map { it.backup }
        val asideIds = setAside().map { it.nameWithoutExtension }.toSet()
        dirs.backups.listFiles().orEmpty().filter { it.name !in needed && it.nameWithoutExtension !in asideIds }.forEach { it.delete() }
    }

    private fun setAside(): List<File> = File(dirs.journal, WriteJournal.SET_ASIDE).listFiles().orEmpty().toList()
}
