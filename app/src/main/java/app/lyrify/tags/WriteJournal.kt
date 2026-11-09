package app.lyrify.tags

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
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/** Where the tag writer keeps its files, all in the app's own storage (never backed up: allowBackup is off). */
class TagDirs(root: File) {
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

/** A file as the writer checks it: its length, the SHA-256 of all its bytes, and its audio payload. */
@Serializable
data class FileState(val length: Long, val sha256: String, val payload: Payload)

/**
 * The state of the file at [file], or null when it is not a well-formed file of an allowed format ([PayloadHash]) or
 * can't be read. Blocking.
 */
fun fileState(file: File): FileState? = try {
    val payload = PayloadHash.of(file.toPath())
    payload?.let { FileState(file.length(), PayloadHash.fileSha256(file.toPath()), it) }
} catch (_: IOException) {
    null
}

/**
 * One write in progress, recorded before any byte of the song changes: the song, its backup in `tag-backups/`, and the
 * song's state before ([original]) and as it will be once written ([expected]).
 */
@Serializable
data class WriteIntent(
    val id: String,
    val path: String,
    val mediaStoreId: Long,
    val backup: String,
    val original: FileState,
    val expected: FileState,
)

/** Copies [from] to [to] and fsyncs the copy and its folder: on disk once this returns. */
fun durableCopy(from: File, to: File) {
    to.parentFile?.mkdirs()
    Files.copy(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
    FileChannel.open(to.toPath(), StandardOpenOption.WRITE).use { it.force(true) }
    syncFolder(to.parentFile)
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

/** Fsyncs [dir] itself, so a file created, moved or deleted in it stays so after a crash (best effort). */
private fun syncFolder(dir: File?) {
    if (dir == null) return
    try {
        FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
    } catch (_: IOException) {
        // Some file systems can't open a folder; the file's own fsync still holds.
    }
}

private const val COPY_BYTES: Int = 256 * 1024

/**
 * The write journal (`tag-journal/`): one intent file per write, written aside, fsynced and moved into place before
 * the song's first byte changes, and deleted once the song is settled. Logs counts and exception class names only.
 * Blocking.
 */
class WriteJournal(private val dir: File, private val log: (String) -> Unit = {}) {
    /** Records [intent] durably. False when it could not be: the song must then not be opened. */
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
            syncFolder(dir)
            true
        } catch (e: IOException) {
            aside.delete()
            log("journal: intent not written (${e.javaClass.simpleName})")
            false
        }
    }

    /** Closes the intent [id]: the song is settled. */
    fun close(id: String): Boolean {
        val gone = File(dir, id + SUFFIX).let { !it.exists() || it.delete() }
        syncFolder(dir)
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
            try {
                json.decodeFromString(WriteIntent.serializer(), file.readText())
            } catch (e: IOException) {
                log("journal: intent not readable (${e.javaClass.simpleName})")
                null
            } catch (_: SerializationException) {
                setAside(file.nameWithoutExtension)
                log("journal: an intent could not be read, set aside")
                null
            } catch (_: IllegalArgumentException) {
                setAside(file.nameWithoutExtension)
                log("journal: an intent could not be read, set aside")
                null
            }
        }
    }

    /** Moves intent [id] to [SET_ASIDE], for a person to look at; it is never acted on again. */
    fun setAside(id: String) {
        val aside = File(dir, SET_ASIDE).apply { mkdirs() }
        File(dir, id + SUFFIX).renameTo(File(aside, id + SUFFIX))
        syncFolder(dir)
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
 * The start-up recovery, run before any tag write (TagsGraph): every intent still open is settled, its song held in
 * [locks] from the start until it is. A song exactly as before or exactly as written is left so; anything else gets its
 * backup written back, in place, if the backup is whole (the original's length and SHA-256), else the intent is set
 * aside with its backup and the song is left alone. A song that can't be reached now (an unmounted card) waits for the
 * next start. Then the private copies and the backups no snapshot keeps are deleted. Logs counts only.
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
    suspend fun recover(): Recovered {
        val asideBefore = setAside().size
        val intents = journal.pending()
        // Every song is held from the start, each until its last intent is settled.
        val last = intents.withIndex().associate { (index, intent) -> intent.path to index }
        last.keys.forEach { locks.hold(it) }
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
        if (!songs.mounted(intent.path) || !song.isFile) return Settled.WAITING
        val now = fileState(song)
        val written = now != null && now.sha256 == intent.expected.sha256 && now.length == intent.expected.length
        val untouched = now != null && now.sha256 == intent.original.sha256 && now.length == intent.original.length
        if (written || untouched) {
            closeAndDrop(intent)
            return Settled.AS_IT_WAS
        }
        // Neither: the write was cut short. Written back only from a whole backup.
        val backup = File(dirs.backups, intent.backup)
        val saved = if (backup.isFile && backup.length() == intent.original.length) fileState(backup) else null
        if (saved != intent.original) {
            journal.setAside(intent.id)
            return Settled.SET_ASIDE
        }
        if (!writes.allows(SharedWrite.TAG_RESTORE, intent.path)) {
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

    /** Closes [intent], and deletes its backup unless it is the song's snapshot. */
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
