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
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

/**
 * Where the tag writer keeps its files, all in the app's own storage (never backed up: allowBackup is off), and how a
 * folder is fsynced ([syncDir], [fsyncDirectory] on the phone; it throws when it can't, and then nothing written in
 * that folder counts as durable).
 */
class TagDirs(val root: File, val syncDir: (File) -> Unit) {
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

    /** Makes the writer's folders and fsyncs [root], which lists them, so they are there after a crash. Throws when it can't. */
    fun ensure() {
        listOf(journal, backups, work, pictures).forEach { it.mkdirs() }
        syncDir(root)
    }
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

/**
 * Whether [file] is a regular file itself, never one reached through a symbolic link; false when nothing is there.
 * Throws when it can't be told (`lstat` failed): that decides nothing.
 */
fun regularFile(file: File): Boolean = try {
    Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).isRegularFile
} catch (_: NoSuchFileException) {
    false
}

/** A file as the writer checks it: its length, the SHA-256 of all its bytes, and its audio payload. */
@Serializable
data class FileState(val length: Long, val sha256: String, val payload: Payload)

/**
 * The state of the file at [file], or null when it is not a regular file (a link is never followed; or none is there)
 * or not a well-formed file of an allowed format ([PayloadHash]). Throws when it can't be read: that decides nothing.
 * Blocking.
 */
fun fileState(file: File): FileState? {
    if (!regularFile(file)) return null
    val payload = PayloadHash.of(file.toPath(), strict = true) ?: return null
    return FileState(file.length(), PayloadHash.fileSha256(file.toPath()), payload)
}

/**
 * One write in progress, recorded before its backup is made (so no backup is ever left that nothing names) and again,
 * complete, before any byte of the song changes: the song, its backup in `tag-backups/`, the song's state before
 * ([original]) and as it will be once written ([expected]: null until then, so a write never began while it is), and
 * the History record the write makes ([record]), which the start-up recovery saves if the app died, or Undo could not
 * be saved, after the write.
 */
@Serializable
data class WriteIntent(
    val id: String,
    val path: String,
    val mediaStoreId: Long,
    val backup: String,
    val original: FileState,
    val expected: FileState? = null,
    val record: UndoRecord? = null,
    /** The file the new content was written from (the private copy, or a snapshot for a full-copy Undo). */
    val source: String? = null,
    /** The identity (`lstat`) of the file at [path] when the intent was written: nothing is written over another. */
    val fileId: FileId? = null,
    /**
     * Set, durably, once the song was written and verified: from then on its backup is never written back, so a song
     * changed after the write (while its Undo record still waits to be saved) is never overwritten with old bytes.
     */
    val committed: Boolean = false,
    /** The starts in a row at which the song could not be read: at [JournalRecovery.READ_TRIES] the intent is set aside. */
    val readFailures: Int = 0,
)

/** Reads up to [count] bytes into [buffer], fewer only at the end of the stream (API 30 has no readNBytes). */
private fun FileInputStream.readUpTo(buffer: ByteArray, count: Int): Int {
    var done = 0
    while (done < count) {
        val read = read(buffer, done, count - done)
        if (read < 0) break
        done += read
    }
    return done
}

/**
 * Whether [file] could be what writes of [a] and [b] over each other left, cut short: KJB writes a song's new or old
 * content from its first byte on, so every byte of [file] is [a]'s or [b]'s at that place, and its length is between
 * theirs. A song torn by a write, or by putting its backup back after one, is this; another file is not. A read that
 * fails, or finds the file ended before [file]'s length, throws: it decides nothing.
 */
fun tornFrom(file: File, a: File, b: File): Boolean {
    if (!regularFile(file)) return false
    return FileInputStream(file).use { input -> tornFrom({ buffer, at -> input.channel.read(buffer, at) }, file.length(), a, b) }
}

/** [tornFrom] for the [length] bytes [read] gives, positionally: a song's held descriptor. */
fun tornFrom(read: (ByteBuffer, Long) -> Int, length: Long, a: File, b: File): Boolean {
    if (!regularFile(a) || !regularFile(b)) return false
    if (length < minOf(a.length(), b.length()) || length > maxOf(a.length(), b.length())) return false
    return FileInputStream(a).use { fa ->
        FileInputStream(b).use { fb ->
            val chunk = 64 * 1024
            val bf = ByteBuffer.allocate(chunk)
            val ba = ByteArray(chunk)
            val bb = ByteArray(chunk)
            var at = 0L
            while (at < length) {
                bf.clear().limit(minOf(chunk.toLong(), length - at).toInt())
                val n = readFully(read, bf, at)
                if (n <= 0) throw IOException("song ended early")
                val na = fa.readUpTo(ba, n)
                val nb = fb.readUpTo(bb, n)
                for (i in 0 until n) {
                    val byte = bf.get(i)
                    if (!(i < na && byte == ba[i]) && !(i < nb && byte == bb[i])) return false
                }
                at += n
            }
            true
        }
    }
}

/** SHA-256 (hex) of every byte the song's descriptor reads now, by positional reads that never move its offset. */
fun OpenSong.sha256(): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    val buffer = ByteBuffer.allocate(COPY_BYTES)
    val length = size()
    var at = 0L
    while (at < length) {
        buffer.clear().limit(minOf(COPY_BYTES.toLong(), length - at).toInt())
        val n = readFully(::read, buffer, at)
        if (n <= 0) throw IOException("song ended early")
        buffer.flip()
        digest.update(buffer)
        at += n
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/** Whether the song's descriptor reads exactly [state]'s bytes: its length and SHA-256. A read that fails throws. */
fun OpenSong.holds(state: FileState): Boolean = size() == state.length && sha256() == state.sha256

/** Fills [buffer] from [read] at [position], stopping early only at the end of the file; the bytes read. */
private fun readFully(read: (ByteBuffer, Long) -> Int, buffer: ByteBuffer, position: Long): Int {
    var done = 0
    while (buffer.hasRemaining()) {
        val n = read(buffer, position + done)
        if (n <= 0) break
        done += n
    }
    return done
}

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
    fun open(intent: WriteIntent): Boolean = store(intent) || false.also { File(dir, intent.id + SUFFIX).delete() }

    /** Records [intent] again, durably, over the open one of its id. False when it could not be; then that one stays. */
    fun update(intent: WriteIntent): Boolean = store(intent)

    /**
     * Marks [intent] committed, durably: the song was written and verified. False when it could not be; the intent
     * stays as it was.
     */
    fun commit(intent: WriteIntent): Boolean = store(intent.copy(committed = true))

    /** Writes [intent] aside, fsyncs it, moves it into place and fsyncs the folder. False on any failure. */
    private fun store(intent: WriteIntent): Boolean {
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

    /** The intents set aside, with their backups, for a person to look at. */
    fun setAsideIntents(): List<WriteIntent> = File(dir, SET_ASIDE).listFiles().orEmpty().filter { it.isFile && it.name.endsWith(SUFFIX) }.mapNotNull(::read)

    /** The writes set aside because their song could not be read at [JournalRecovery.READ_TRIES] starts in a row. */
    fun uncheckable(): Int = setAsideIntents().count { it.readFailures >= JournalRecovery.READ_TRIES }

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

/**
 * What the start-up recovery did: intents closed ([restored] of them by writing the backup back), set aside (with
 * [unreadable] of them because their song could not be read at [JournalRecovery.READ_TRIES] starts in a row), left.
 */
data class Recovered(val settled: Int = 0, val restored: Int = 0, val setAside: Int = 0, val waiting: Int = 0, val unreadable: Int = 0)

/**
 * The one door through which a write's backup is deleted: [releaseBackup]. A test scans the sources for any other
 * (Undo's own expiry of a song's snapshot aside).
 */
class Backups(
    private val dirs: TagDirs,
    private val journal: WriteJournal,
    private val undo: UndoStore,
    private val songs: SongFiles,
    private val log: (String) -> Unit = {},
) {
    /**
     * Closes [intent], deleting its backup, only on proof that the backup is no longer needed: the file at the song's
     * path, opened anew here and read whole, holds exactly [proof] (the written state after a write; the original when
     * nothing was written, or the backup was put back), and that descriptor is still the file at the path (`fstat`
     * against `lstat`) right before the delete. No fsync, scan or Undo save runs between that proof and the delete; the
     * intent is closed after it. A backup Undo keeps as the song's snapshot is never deleted here.
     *
     * True: released. False: the read proved otherwise (other bytes, or another file at the path), so the backup is
     * kept and the intent set aside. A read or `lstat` that fails throws, and leaves the backup and the intent as they
     * are: an error decides nothing.
     */
    fun releaseBackup(intent: WriteIntent, proof: FileState): Boolean {
        val snapshot = intent.backup in undo.keptBackups()
        val proven = songs.openToRead(intent.path).use { song ->
            val proven = song.holds(proof) && song.fileId() == songs.fileId(intent.path)
            if (proven && !snapshot) Files.deleteIfExists(File(dirs.backups, intent.backup).toPath())
            proven
        }
        if (!proven) {
            journal.setAside(intent.id)
            log("tags: the song is not as proven at its path, its backup kept and its write set aside")
            return false
        }
        journal.close(intent.id)
        return true
    }
}

/**
 * The start-up recovery. [holdPending] runs first, at process start (TagsGraph), and holds the song of every intent
 * still open in [locks], so the lyrics pipeline, the AI and the tone preview stay off them; then [recover] settles each
 * one and lets it go:
 * - a song that is not mounted, not a regular file at its path (a link is never followed), or in no MediaStore row
 *   waits for the next start, untouched;
 * - a write that never began (no [WriteIntent.expected]) has its backup released ([Backups.releaseBackup]) once the
 *   song is proven at its path as it was;
 * - otherwise the song is opened, read and write, and held to the end: every decision below is made on the bytes read
 *   through that descriptor, and any write goes through it, so nothing put at the path meanwhile is written over. Its
 *   MediaStore id is found by its path (closing the "rw" descriptor may have made MediaStore index the path again under
 *   a new one), and the file identity stored in the intent is only a hint (a remount changes the device number);
 * - a song exactly as written keeps its new tags, the write's History record is saved (if that still fails, the intent
 *   waits), and the backup is released on a fresh proof;
 * - a committed intent (the write was finished and verified) never has its backup written back: a song changed since
 *   is set aside, untouched;
 * - a song exactly as before has its backup released on a fresh proof;
 * - a song torn by the write ([tornFrom]: every byte is the old or the new content's) gets its backup written back
 *   through the held descriptor, if the backup is whole (a regular file with the original's length and SHA-256) and the
 *   path still names the file held; then the backup is released on a fresh proof. Anything else (another file at the
 *   path, a backup that is not whole) has its intent set aside, with its backup, and is left alone;
 * - a read, an `lstat` or a write that fails decides nothing: the intent waits for the next start, and after
 *   [READ_TRIES] starts in a row that failed so it is set aside with its backup ([Recovered.unreadable]).
 * An intent left waiting keeps its song [PathLocks.pin]ned for the rest of the process. Then the private copies no
 * open intent needs are deleted. Backups are never deleted here but through [Backups.releaseBackup]. Logs counts only.
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
    private val backups = Backups(dirs, journal, undo, songs, log)

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
        val asideBefore = asideCount()
        val intents = held ?: journal.pending().also { pending -> pending.map { it.path }.distinct().forEach { locks.hold(it) } }
        val last = intents.withIndex().associate { (index, intent) -> intent.path to index }
        var result = Recovered()
        intents.forEachIndexed { index, intent ->
            try {
                result = when (settle(intent)) {
                    Settled.AS_IT_WAS -> result.copy(settled = result.settled + 1)
                    Settled.RESTORED -> result.copy(settled = result.settled + 1, restored = result.restored + 1)
                    Settled.SET_ASIDE -> result
                    Settled.UNREADABLE -> result.copy(unreadable = result.unreadable + 1)
                    Settled.WAITING -> result.copy(waiting = result.waiting + 1)
                }
            } finally {
                if (last[intent.path] == index) {
                    // A song whose intent stays open is pinned before its lock is let go: nothing can take it in between.
                    if (intent.path in journal.openPaths()) locks.pin(intent.path)
                    locks.release(intent.path)
                }
            }
        }
        cleanUp()
        result = result.copy(setAside = asideCount() - asideBefore)
        if (result != Recovered()) {
            log("journal: ${result.settled} unfinished writes settled (${result.restored} put back), ${result.setAside} set aside (${result.unreadable} unreadable), ${result.waiting} waiting")
        }
        return result
    }

    private enum class Settled { AS_IT_WAS, RESTORED, SET_ASIDE, UNREADABLE, WAITING }

    /** The intents set aside so far, those that can't be read included. */
    private fun asideCount(): Int = File(dirs.journal, WriteJournal.SET_ASIDE).listFiles().orEmpty().size

    private suspend fun settle(intent: WriteIntent): Settled {
        if (!songs.mounted(intent.path)) return Settled.WAITING
        return try {
            settleRead(intent)
        } catch (e: IOException) {
            failed(intent, e)
        }
    }

    /** [settle] on a mounted volume: any read, `lstat` or write that fails throws. */
    private suspend fun settleRead(intent: WriteIntent): Settled {
        if (!regularFile(File(intent.path))) return Settled.WAITING
        // The write never began: nothing was written, so the song must be at its path as it was.
        val expected = intent.expected ?: return released(intent, intent.original, Settled.AS_IT_WAS)
        val id = songs.idOf(intent.path) ?: return Settled.WAITING
        // The file at the path right before it is opened, compared with the path's again before anything is written
        // (path against path: MediaStore's descriptor is mostly on the file system under the path's, with another device
        // number), while the descriptor is held, so its inode can't be reused by another file meanwhile.
        val atOpen = songs.fileId(intent.path)
        if (intent.fileId != null && atOpen != intent.fileId) log("journal: the song's file identity changed since its write; its bytes decide")
        val open = songs.openInPlace(SongTarget(intent.path, id)) ?: throw IOException("song not opened")
        val settled = open.use { settleThrough(intent, expected, it, atOpen) }
        if (settled == Settled.RESTORED) songs.scan(intent.path)
        return settled
    }

    /** [settle] with the song held open: decided on what [open] reads, written through [open] only. */
    private fun settleThrough(intent: WriteIntent, expected: FileState, open: OpenSong, atOpen: FileId): Settled {
        if (open.holds(expected)) {
            // The write finished; its History record may not have been saved.
            if (intent.record != null && !undo.save(intent.record, intent.mediaStoreId)) {
                // The song was read: this start does not count as one that could not read it.
                if (intent.readFailures > 0) journal.update(intent.copy(readFailures = 0))
                return Settled.WAITING
            }
            return released(intent, expected, Settled.AS_IT_WAS)
        }
        // Written and verified, then changed by someone else: old bytes are never written over it.
        if (intent.committed) return setAside(intent, "the song changed after its write was committed")
        if (open.holds(intent.original)) return released(intent, intent.original, Settled.AS_IT_WAS)
        // Neither: the write was cut short. Written back only from a whole backup, and only over this song torn.
        val backup = File(dirs.backups, intent.backup)
        val saved = if (regularFile(backup) && backup.length() == intent.original.length) fileState(backup) else null
        val source = intent.source?.let(::File)
        val torn = saved == intent.original && source != null && tornFrom(open::read, open.size(), source, backup)
        if (!torn || !writes.allows(SharedWrite.TAG_RESTORE, intent.path)) return setAside(intent, "the song is not as the write left it")
        // Never written into a file the path no longer names.
        if (songs.fileId(intent.path) != atOpen) return setAside(intent, "the song was replaced during recovery")
        writeInPlace(open.channel, backup, intent.original.length)
        if (!open.holds(intent.original)) throw IOException("the backup did not read back as written")
        return released(intent, intent.original, Settled.RESTORED)
    }

    /** [settled] once [intent]'s backup is released on proof that the song is at its path as [proof]; else set aside. */
    private fun released(intent: WriteIntent, proof: FileState, settled: Settled): Settled =
        if (backups.releaseBackup(intent, proof)) settled else Settled.SET_ASIDE

    /** A start that could not read [intent]'s song: it waits, until [READ_TRIES] starts in a row could not. */
    private fun failed(intent: WriteIntent, e: IOException): Settled {
        val tries = intent.readFailures + 1
        log("journal: a song could not be read (${e.javaClass.simpleName}), $tries of $READ_TRIES starts")
        if (!journal.update(intent.copy(readFailures = tries))) return Settled.WAITING
        if (tries < READ_TRIES) return Settled.WAITING
        setAside(intent, "its song could not be read at $READ_TRIES starts in a row")
        return Settled.UNREADABLE
    }

    private fun setAside(intent: WriteIntent, why: String): Settled {
        journal.setAside(intent.id)
        log("journal: an unfinished write set aside, $why")
        return Settled.SET_ASIDE
    }

    /** Deletes the private copies no open intent needs. Backups are left alone: only [Backups.releaseBackup] deletes one. */
    private fun cleanUp() {
        val sources = journal.pending().mapNotNull { it.source }.toSet()
        dirs.work.listFiles().orEmpty().filter { it.path !in sources }.forEach { it.delete() }
    }

    companion object {
        /** The starts in a row that may fail to read a song before its intent is set aside. */
        const val READ_TRIES: Int = 3
    }
}
