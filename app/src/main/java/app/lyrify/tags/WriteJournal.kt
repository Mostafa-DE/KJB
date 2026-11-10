package app.lyrify.tags

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import app.lyrify.data.PathLocks
import app.lyrify.data.SharedWrite
import app.lyrify.data.SharedWrites
import kotlinx.serialization.Serializable
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

// The tag engine tells files only through calls that throw when they can't tell (java.io.File's length(), exists(),
// isFile and listFiles() answer 0, false or null on an error, and a test keeps them out of the engine's files).

/** [file]'s own attributes (`lstat`, a link never followed), or null when nothing is there. Throws when it can't tell. */
fun attributesOf(file: File): BasicFileAttributes? = try {
    Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
} catch (_: NoSuchFileException) {
    null
}

/** Whether [file] is a regular file itself, never one reached through a symbolic link; false when nothing is there. */
fun regularFile(file: File): Boolean = attributesOf(file)?.isRegularFile == true

/** The length of [file] itself (`lstat`). Throws when it can't tell, or nothing is there. */
fun sizeOf(file: File): Long = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).size()

/** What folder [dir] holds, or nothing when there is no folder. Throws when it can't be listed. */
fun entriesOf(dir: File): List<File> = try {
    Files.newDirectoryStream(dir.toPath()).use { entries -> entries.map { it.toFile() } }
} catch (_: NoSuchFileException) {
    emptyList()
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
    val (length, sha256) = PayloadHash.fileHash(file.toPath())
    return FileState(length, sha256, payload)
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
    /**
     * The starts at which the song read back as written but its History record could not be saved: at
     * [JournalRecovery.READ_TRIES] the write is settled as written, "Undo not saved", and the intent set aside.
     */
    val saveFailures: Int = 0,
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
    return FileInputStream(file).use { input -> tornFrom({ buffer, at -> input.channel.read(buffer, at) }, input.channel.size(), a, b) }
}

/** [tornFrom] for the [length] bytes [read] gives, positionally: a song's held descriptor. */
fun tornFrom(read: (ByteBuffer, Long) -> Int, length: Long, a: File, b: File): Boolean {
    if (!regularFile(a) || !regularFile(b)) return false
    val lengths = listOf(sizeOf(a), sizeOf(b))
    if (length < lengths.min() || length > lengths.max()) return false
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

/**
 * Copies [from] to [to], a new file (CREATE_NEW: a file already there is never written over, and the copy throws
 * FileAlreadyExistsException), and fsyncs the copy and its folder: on disk once this returns, else it throws.
 */
fun durableCopy(from: File, to: File, syncDir: (File) -> Unit) {
    to.parentFile?.mkdirs()
    FileChannel.open(from.toPath(), StandardOpenOption.READ).use { source ->
        FileChannel.open(to.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { copy ->
            val length = source.size()
            var at = 0L
            while (at < length) {
                val moved = source.transferTo(at, length - at, copy)
                if (moved <= 0) throw IOException("source ended early")
                at += moved
            }
            copy.force(true)
        }
    }
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
    /**
     * Whether an intent could not be set aside in this process: KJB can't change its safety records, so tag changes are
     * paused (the writer writes nothing, and Covers & tags says so) until it can.
     */
    @Volatile
    var paused: Boolean = false
        private set

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
    private fun store(intent: WriteIntent): Boolean = store(intent.id + SUFFIX, json.encodeToString(WriteIntent.serializer(), intent))

    /** Writes [text] as [name] in the journal: aside, fsynced, moved into place, the folder fsynced. False on any failure. */
    private fun store(name: String, text: String): Boolean {
        val file = File(dir, name)
        val aside = File(dir, name + TMP)
        return try {
            dir.mkdirs()
            FileOutputStream(aside).use { out ->
                out.write(text.encodeToByteArray())
                out.fd.sync()
            }
            Files.move(aside.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            syncDir(dir)
            true
        } catch (e: IOException) {
            aside.delete()
            log("journal: intent not written (${e.javaClass.simpleName})")
            false // safe: a failure report: nothing is opened on it, or the intent stays as it was
        }
    }

    /**
     * Closes the intent [id]: the song is settled. The count beside it goes first, so a close that fails leaves the
     * intent whole. Throws when either can't be deleted (the intent stays open then).
     */
    fun close(id: String) {
        Files.deleteIfExists(File(dir, id + UNREAD).toPath())
        Files.deleteIfExists(File(dir, id + SUFFIX).toPath())
        try {
            syncDir(dir)
        } catch (e: IOException) {
            // Should the intent come back after a crash, the recovery finds its song settled and closes it again.
            log("journal: folder not fsynced after a close (${e.javaClass.simpleName})")
        }
    }

    /** What [listing] found: the intents open, oldest first, and [unread], the ids of those that could not be read now. */
    class Listing(val intents: List<WriteIntent>, val unread: List<String>, val setAside: Int)

    /**
     * The intents still open. An intent the app's death cut short while it was written aside is deleted: the song was
     * never opened then. One that is not an intent at all (it can't be parsed) is set aside, never acted on. One that
     * can't be read now stays where it is, in [Listing.unread]. Throws when the folder can't be listed.
     */
    fun listing(): Listing = listing(tidy = true)

    private fun listing(tidy: Boolean): Listing {
        val intents = mutableListOf<WriteIntent>()
        val unread = mutableListOf<String>()
        var setAside = 0
        for (file in entriesOf(dir).sortedBy { it.name }) {
            val id = file.name.removeSuffix(SUFFIX)
            // An entry that can't be looked at: an intent waits as one that can't be read, anything else is passed by.
            val regular = try {
                regularFile(file)
            } catch (e: IOException) {
                if (file.name.endsWith(SUFFIX)) unread += id
                continue
            }
            if (!regular) continue
            if (file.name.endsWith(TMP)) {
                // An aside file is never read as an intent: left, it changes nothing.
                if (tidy) forget(file, "an aside file")
                continue
            }
            if (!file.name.endsWith(SUFFIX)) continue
            val intent = try {
                read(file)
            } catch (e: IOException) {
                log("journal: an intent could not be read now (${e.javaClass.simpleName}), left in place")
                unread += id
                continue
            }
            when {
                intent != null -> {
                    intents += intent
                    // Read: the starts it could not be are forgotten, so only starts in a row count.
                    if (tidy) forget(File(dir, id + UNREAD), "a count")
                }
                tidy -> {
                    log("journal: a file that is not an intent, set aside")
                    if (setAside(id)) setAside++
                }
            }
        }
        return Listing(intents, unread, setAside)
    }

    /** Deletes [file], the journal's own bookkeeping; one that can't be is logged as [what], and changes nothing. */
    private fun forget(file: File, what: String) {
        try {
            Files.deleteIfExists(file.toPath())
        } catch (e: IOException) {
            log("journal: $what not deleted (${e.javaClass.simpleName})")
        }
    }

    /** The intents still open ([listing]); throws when one of them can't be read now, as when the folder can't be listed. */
    fun pending(): List<WriteIntent> {
        val listing = listing()
        if (listing.unread.isNotEmpty()) throw IOException("an open intent could not be read")
        return listing.intents
    }

    /**
     * The songs with an intent still open (read only): the writer leaves them alone until they are settled. Throws when
     * an open intent can't be read, or the folder listed: then any song may be one, and none is written.
     */
    fun openPaths(): Set<String> {
        val listing = listing(tidy = false)
        if (listing.unread.isNotEmpty()) throw IOException("an open intent could not be read")
        return listing.intents.map { it.path }.toSet()
    }

    /** Whether an intent named [id] is open or set aside. Throws when it can't tell. */
    fun has(id: String): Boolean =
        listOf(File(dir, id + SUFFIX), File(File(dir, SET_ASIDE), id + SUFFIX), File(File(dir, SET_ASIDE), id + UNCHECKABLE + SUFFIX))
            .any { attributesOf(it) != null }

    /** The intents set aside, with their backups, for a person to look at, those that can be read. */
    fun setAsideIntents(): List<WriteIntent> =
        entriesOf(File(dir, SET_ASIDE)).filter { regularFile(it) && it.name.endsWith(SUFFIX) }.mapNotNull(::read)

    /**
     * The writes set aside because their song, or their intent, could not be read at [JournalRecovery.READ_TRIES]
     * starts in a row: their originals are kept. Told by the name each was set aside under, so even an intent that
     * can't be read counts. Throws when the folder can't be listed.
     */
    fun uncheckable(): Int = entriesOf(File(dir, SET_ASIDE)).count { it.name.endsWith(UNCHECKABLE + SUFFIX) }

    /**
     * Moves intent [id] to [SET_ASIDE], for a person to look at; it is never acted on again. [uncheckable]: because its
     * song, or the intent itself, could not be read ([uncheckable] counts those). False when it can't be moved: it stays
     * open, and tag changes are [paused].
     */
    fun setAside(id: String, uncheckable: Boolean = false): Boolean {
        val aside = File(dir, SET_ASIDE)
        try {
            Files.createDirectories(aside.toPath())
            Files.move(File(dir, id + SUFFIX).toPath(), File(aside, id + (if (uncheckable) UNCHECKABLE else "") + SUFFIX).toPath())
        } catch (e: IOException) {
            log("journal: an intent could not be set aside (${e.javaClass.simpleName}), tag changes paused")
            paused = true
            return false // safe: a failure report: the intent stays open, and tag changes pause
        }
        forget(File(dir, id + UNREAD), "a count")
        try {
            syncDir(dir)
        } catch (e: IOException) {
            log("journal: folder not fsynced after setting aside (${e.javaClass.simpleName})")
        }
        return true
    }

    /**
     * The starts at which intent [id] could not be read, kept beside it (it can't hold its own count): 0 when none was
     * kept. Throws when that can't be read.
     */
    fun unreadStarts(id: String): Int = try {
        Files.readAllBytes(File(dir, id + UNREAD).toPath()).decodeToString().trim().toInt()
    } catch (_: NoSuchFileException) {
        0
    } catch (_: NumberFormatException) {
        // Not a count: the file is the journal's own, so it counts as none.
        0
    }

    /** Keeps [starts] beside intent [id] (see [unreadStarts]). False when it could not be kept. */
    fun countUnread(id: String, starts: Int): Boolean = store(id + UNREAD, starts.toString())

    /** The intent in [file], or null when it is not one (it can't be parsed). Throws when it can't be read. */
    private fun read(file: File): WriteIntent? = try {
        json.decodeFromString(WriteIntent.serializer(), Files.readAllBytes(file.toPath()).decodeToString())
    } catch (_: IllegalArgumentException) {
        // A SerializationException is one: the file is not an intent.
        null
    }

    companion object {
        /** The folder in `tag-journal/` for intents that could not be settled: never acted on again. */
        const val SET_ASIDE: String = "set-aside"
        private const val SUFFIX: String = ".json"
        private const val TMP: String = ".tmp"

        /** Beside an open intent: the starts at which it could not be read. */
        private const val UNREAD: String = ".unread"

        /** Before [SUFFIX], in [SET_ASIDE]: set aside because the song, or the intent, could not be read. */
        private const val UNCHECKABLE: String = ".uncheckable"
        private val json = Json { ignoreUnknownKeys = true }
    }
}

/**
 * What the start-up recovery did: intents closed ([settled], [restored] of them by writing the backup back), set aside
 * with their backups ([unreadable] of them because their song or intent could not be read at
 * [JournalRecovery.READ_TRIES] starts in a row, [undoNotSaved] of them settled as written though their History record
 * could not be saved, and counted in [settled] too), or left waiting.
 */
data class Recovered(
    val settled: Int = 0,
    val restored: Int = 0,
    val setAside: Int = 0,
    val waiting: Int = 0,
    val unreadable: Int = 0,
    val undoNotSaved: Int = 0,
    val paused: Boolean = false,
)

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
     * nothing was written, or the backup was put back), that descriptor is still the file at the path (`fstat` against
     * `lstat`), and MediaStore's row at the path is still the song's own, [mediaStoreId]. Proven twice: the second time
     * through a descriptor opened anew on the path right before the delete, held while it is deleted.
     *
     * On the phone, a descriptor's `fstat` can't tell a song replaced under its name: MediaProvider's FUSE daemon answers
     * it by `lstat` of the path (FuseDaemon `pf_getattr`), not by the open file, while the size and the bytes read stay
     * the open file's. So a descriptor held across a replacement reports the new file's identity with the old file's
     * bytes (seen on Android 17). The second, fresh descriptor reads the bytes now at the path, and the row tells any
     * replacement made through MediaStore, which gives the path a new row at once. No fsync, scan or Undo save runs between the proofs and the delete; the
     * intent is closed after it. A backup Undo keeps as the song's snapshot is never deleted here.
     *
     * A read or `lstat` that fails throws, and leaves the backup and the intent as they are: an error decides nothing.
     */
    fun releaseBackup(intent: WriteIntent, proof: FileState, mediaStoreId: Long): Release {
        val snapshot = intent.backup in undo.keptBackups()
        fun proven(song: OpenSong) = song.holds(proof) && song.fileId() == songs.fileId(intent.path) && songs.idOf(intent.path) == mediaStoreId
        val proven = songs.openToRead(intent.path).use(::proven) && songs.openToRead(intent.path).use { song ->
            proven(song).also { if (it && !snapshot) Files.deleteIfExists(File(dirs.backups, intent.backup).toPath()) }
        }
        if (!proven) {
            log("tags: the song is not as proven at its path, its backup kept")
            return if (journal.setAside(intent.id)) Release.SET_ASIDE else Release.NOT_SET_ASIDE
        }
        try {
            journal.close(intent.id)
        } catch (e: IOException) {
            log("tags: a backup released, but its intent not closed (${e.javaClass.simpleName})")
            return Release.NOT_CLOSED
        }
        return Release.RELEASED
    }

    /** What [releaseBackup] did. */
    enum class Release {
        /** Proven: the backup deleted (unless Undo keeps it) and the intent closed. */
        RELEASED,

        /** A read proved otherwise (other bytes, or another file at the path): the backup kept, the intent set aside. */
        SET_ASIDE,

        /** Proven and the backup deleted, but the intent could not be closed: the next start closes it. Not a failed read. */
        NOT_CLOSED,

        /** A read proved otherwise, but the intent could not be set aside: the backup kept, and tag changes pause. */
        NOT_SET_ASIDE,
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
 * - a song exactly as written keeps its new tags, the write's History record is saved, and the backup is released on
 *   a fresh proof. A record that can't be saved waits, counted apart ([WriteIntent.saveFailures]); after [READ_TRIES]
 *   starts the write is settled as written, "Undo not saved", and set aside with its backup ([Recovered.undoNotSaved]);
 * - a committed intent (the write was finished and verified) never has its backup written back: a song changed since
 *   is set aside, untouched;
 * - a song exactly as before has its backup released on a fresh proof;
 * - a song torn by the write ([tornFrom]: every byte is the old or the new content's) gets its backup written back
 *   through the held descriptor, if the backup is whole (a regular file with the original's length and SHA-256) and the
 *   path still names the file held; then the backup is released on a fresh proof. Anything else (another file at the
 *   path, a backup that is not whole) has its intent set aside, with its backup, and is left alone;
 * - a read, an `lstat`, a MediaStore query or a write that fails decides nothing (Undo's own list included: one that
 *   can't be read never lets a backup go): the intent waits for the next start, and after [READ_TRIES] starts in a row
 *   that failed so it is set aside with its backup ([Recovered.unreadable]). An intent that can't be read itself waits
 *   the same way, counted beside it;
 * - a journal that can't be listed settles nothing that start.
 * An intent left waiting keeps its song [PathLocks.pin]ned for the rest of the process. Then the private copies no
 * open intent needs are deleted, when every open intent could be read. Backups are never deleted here but through
 * [Backups.releaseBackup]. Logs counts only.
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
        val intents = try {
            journal.listing().intents
        } catch (e: IOException) {
            log("journal: the intents could not be listed (${e.javaClass.simpleName})")
            emptyList() // safe: nothing is held; recover() lists again and settles nothing when it can't
        }
        intents.map { it.path }.distinct().forEach { path ->
            if (!locks.tryHold(path)) log("journal: a song of an unfinished write was already held")
        }
        return intents
    }

    /**
     * Settles [held] (from [holdPending], whose songs this lets go of as each is settled), or, when null, every intent
     * still open, holding each song first. A journal that can't be listed settles nothing: the songs held stay pinned.
     */
    suspend fun recover(held: List<WriteIntent>? = null): Recovered {
        val listing = try {
            journal.listing()
        } catch (e: IOException) {
            log("journal: the intents could not be listed (${e.javaClass.simpleName}), nothing settled this start")
            held?.map { it.path }?.distinct()?.forEach { path ->
                locks.pin(path)
                locks.release(path)
            }
            return Recovered(waiting = held?.size ?: 0, paused = true)
        }
        val intents = held ?: listing.intents.also { pending -> pending.map { it.path }.distinct().forEach { locks.hold(it) } }
        val last = intents.withIndex().associate { (index, intent) -> intent.path to index }
        var result = Recovered(setAside = listing.setAside)
        intents.forEachIndexed { index, intent ->
            try {
                result = result.counted(settle(intent))
            } finally {
                if (last[intent.path] == index) {
                    // A song whose intent stays open is pinned before its lock is let go: nothing can take it in between.
                    val open = try {
                        intent.path in journal.openPaths()
                    } catch (_: IOException) {
                        true
                    }
                    if (open) locks.pin(intent.path)
                    locks.release(intent.path)
                }
            }
        }
        // An intent left that can't be read may be any song's: tag changes stay paused while it is there.
        var unreadLeft = false
        listing.unread.forEach { id ->
            val settled = unreadIntent(id)
            if (settled == Settled.WAITING || settled == Settled.PAUSED) unreadLeft = true
            result = result.counted(settled)
        }
        result = result.copy(paused = journal.paused || unreadLeft)
        if (listing.unread.isEmpty()) cleanUp()
        if (result != Recovered()) {
            log(
                "journal: ${result.settled} unfinished writes settled (${result.restored} put back, ${result.undoNotSaved} without Undo), " +
                    "${result.setAside} set aside (${result.unreadable} unreadable), ${result.waiting} waiting",
            )
        }
        return result
    }

    private enum class Settled { AS_IT_WAS, RESTORED, SET_ASIDE, UNREADABLE, UNDO_NOT_SAVED, WAITING, PAUSED }

    private fun Recovered.counted(settled: Settled): Recovered = when (settled) {
        Settled.AS_IT_WAS -> copy(settled = this.settled + 1)
        Settled.RESTORED -> copy(settled = this.settled + 1, restored = restored + 1)
        Settled.SET_ASIDE -> copy(setAside = setAside + 1)
        Settled.UNREADABLE -> copy(setAside = setAside + 1, unreadable = unreadable + 1)
        Settled.UNDO_NOT_SAVED -> copy(settled = this.settled + 1, setAside = setAside + 1, undoNotSaved = undoNotSaved + 1)
        Settled.WAITING -> copy(waiting = waiting + 1)
        Settled.PAUSED -> copy(waiting = waiting + 1, paused = true)
    }

    private suspend fun settle(intent: WriteIntent): Settled {
        if (!songs.mounted(intent.path)) return Settled.WAITING
        return try {
            settleRead(intent)
        } catch (e: IOException) {
            failed(intent, e)
        }
    }

    /** [settle] on a mounted volume: any read, `lstat`, query or write that fails throws. */
    private suspend fun settleRead(intent: WriteIntent): Settled {
        if (!regularFile(File(intent.path))) return Settled.WAITING
        val id = songs.idOf(intent.path) ?: return Settled.WAITING
        // The write never began: nothing was written, so the song must be at its path as it was.
        val expected = intent.expected ?: return released(intent, intent.original, id, Settled.AS_IT_WAS)
        // The file at the path right before it is opened, compared with the path's again before anything is written
        // (path against path: MediaStore's descriptor is mostly on the file system under the path's, with another device
        // number), while the descriptor is held, so its inode can't be reused by another file meanwhile.
        val atOpen = songs.fileId(intent.path)
        if (intent.fileId != null && atOpen != intent.fileId) log("journal: the song's file identity changed since its write; its bytes decide")
        val open = songs.openInPlace(SongTarget(intent.path, id)) ?: throw IOException("song not opened")
        val settled = open.use { settleThrough(intent, expected, it, atOpen, id) }
        if (settled == Settled.RESTORED) songs.scan(intent.path)
        return settled
    }

    /**
     * [settle] with the song held open: decided on what [open] reads, written through [open] only. [id]: the song's
     * MediaStore row at its path when it was opened, which its backup's release must find still there.
     */
    private fun settleThrough(intent: WriteIntent, expected: FileState, open: OpenSong, atOpen: FileId, id: Long): Settled {
        if (open.holds(expected)) {
            // The write finished; its History record may not have been saved.
            if (intent.record != null && !undo.save(intent.record, intent.mediaStoreId)) return historyNotSaved(intent)
            return released(intent, expected, id, Settled.AS_IT_WAS)
        }
        // Written and verified, then changed by someone else: old bytes are never written over it.
        if (intent.committed) return setAside(intent, "the song changed after its write was committed")
        if (open.holds(intent.original)) return released(intent, intent.original, id, Settled.AS_IT_WAS)
        // Neither: the write was cut short. Written back only from a whole backup, and only over this song torn.
        val backup = File(dirs.backups, intent.backup)
        // No backup any more (it went once, on proof): never written back, and not a failed read.
        if (attributesOf(backup) == null) return setAside(intent, "nothing to restore from")
        val saved = if (regularFile(backup) && sizeOf(backup) == intent.original.length) fileState(backup) else null
        val source = intent.source?.let(::File)
        val torn = saved == intent.original && source != null && tornFrom(open::read, open.size(), source, backup)
        if (!torn || !writes.allowsOrThrow(SharedWrite.TAG_RESTORE, intent.path)) return setAside(intent, "the song is not as the write left it")
        // Never written into a file the path no longer names.
        if (songs.fileId(intent.path) != atOpen) return setAside(intent, "the song was replaced during recovery")
        writeInPlace(open.channel, backup, intent.original.length)
        if (!open.holds(intent.original)) throw IOException("the backup did not read back as written")
        return released(intent, intent.original, id, Settled.RESTORED)
    }

    /**
     * [settled] once [intent]'s backup is released on proof that the song is at its path as [proof]; else set aside. A
     * backup released whose intent could not be closed waits, uncounted: the next start closes it.
     */
    private fun released(intent: WriteIntent, proof: FileState, id: Long, settled: Settled): Settled = when (backups.releaseBackup(intent, proof, id)) {
        Backups.Release.RELEASED -> settled
        Backups.Release.SET_ASIDE -> Settled.SET_ASIDE
        Backups.Release.NOT_CLOSED -> Settled.WAITING
        Backups.Release.NOT_SET_ASIDE -> Settled.PAUSED
    }

    /**
     * A start that could not read [intent]'s song (or query it, or write it back): it waits, until [READ_TRIES] starts in
     * a row could not (a start that reads it and only waits for its History record starts the count again). When the
     * count can't be kept it could never reach the last start: the intent is set aside now, its backup kept.
     */
    private fun failed(intent: WriteIntent, e: IOException): Settled {
        val tries = intent.readFailures + 1
        log("journal: a song could not be read (${e.javaClass.simpleName}), $tries of $READ_TRIES starts")
        val kept = journal.update(intent.copy(readFailures = tries))
        if (kept && tries < READ_TRIES) return Settled.WAITING
        log("journal: an unfinished write set aside, its song could not be read" + if (kept) " at $READ_TRIES starts in a row" else ", nor its count kept")
        return if (journal.setAside(intent.id, uncheckable = true)) Settled.UNREADABLE else Settled.PAUSED
    }

    /**
     * The song read back as written, but its History record could not be saved: it waits, counted apart from reads, and
     * after [READ_TRIES] starts is settled as written, "Undo not saved", with its backup kept (set aside). Not a song
     * that could not be checked.
     */
    private fun historyNotSaved(intent: WriteIntent): Settled {
        val tries = intent.saveFailures + 1
        log("journal: a History record could not be saved, $tries of $READ_TRIES starts")
        // The song was read: the starts that could not read it no longer count.
        val kept = journal.update(intent.copy(saveFailures = tries, readFailures = 0))
        if (kept && tries < READ_TRIES) return Settled.WAITING
        log("journal: an unfinished write settled as written, Undo not saved, its backup kept")
        return if (journal.setAside(intent.id)) Settled.UNDO_NOT_SAVED else Settled.PAUSED
    }

    /**
     * An intent that could not be read itself at this start: it waits (no song is written meanwhile, as any may be its
     * song), counted beside it, until [READ_TRIES] starts in a row could not; then it is set aside, its backup kept.
     */
    private fun unreadIntent(id: String): Settled {
        val tries = try {
            journal.unreadStarts(id) + 1
        } catch (e: IOException) {
            // The count can't be read, so it could never reach the last start: set aside now.
            log("journal: the count of an intent that could not be read could not be read (${e.javaClass.simpleName})")
            return unreadSetAside(id)
        }
        if (journal.countUnread(id, tries) && tries < READ_TRIES) return Settled.WAITING
        return unreadSetAside(id)
    }

    /** Intent [id], which could not be read, set aside with its backup; tag changes pause when even that can't be done. */
    private fun unreadSetAside(id: String): Settled {
        log("journal: an intent that could not be read, set aside")
        return if (journal.setAside(id, uncheckable = true)) Settled.UNREADABLE else Settled.PAUSED
    }

    private fun setAside(intent: WriteIntent, why: String): Settled {
        log("journal: an unfinished write set aside, $why")
        return if (journal.setAside(intent.id)) Settled.SET_ASIDE else Settled.PAUSED
    }

    /** Deletes the private copies no open intent needs. Backups are left alone: only [Backups.releaseBackup] deletes one. */
    private fun cleanUp() {
        try {
            val sources = journal.pending().mapNotNull { it.source }.toSet()
            entriesOf(dirs.work).filter { it.path !in sources }.forEach { it.delete() }
        } catch (e: IOException) {
            log("journal: private copies left, the journal or their folder could not be read (${e.javaClass.simpleName})")
        }
    }

    companion object {
        /** The starts in a row that may fail to read a song, or its intent, before its intent is set aside. */
        const val READ_TRIES: Int = 3
    }
}
