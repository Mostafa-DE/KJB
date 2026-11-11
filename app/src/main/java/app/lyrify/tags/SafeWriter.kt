package app.lyrify.tags

import android.content.ContentUris
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import app.lyrify.data.PathLocks
import app.lyrify.data.SharedWrite
import app.lyrify.data.SharedWrites
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import kotlin.coroutines.resume

/** A song to change: its absolute path, and its MediaStore id, which must still be the file at that path. */
data class SongTarget(val path: String, val mediaStoreId: Long)

/** The songs in shared storage, as the writer reaches them: MediaStore on the phone ([PhoneSongFiles]), a folder in tests. */
interface SongFiles {
    /** Whether the volume holding [path] is mounted. */
    fun mounted(path: String): Boolean

    /**
     * Whether MediaStore's row [mediaStoreId] is the file at [path] now (ids follow files that move). Throws when
     * MediaStore can't be asked.
     */
    fun sameFile(mediaStoreId: Long, path: String): Boolean

    /**
     * [song] opened for writing in place through MediaStore, "rw" (never "w" or "rwt", which may cut it), or null when
     * MediaStore gives no descriptor. Throws when it can't be opened. Closing it lets MediaStore rescan the song and
     * drop its cached cover.
     */
    fun openInPlace(song: SongTarget): OpenSong?

    /**
     * The file at [path] itself opened anew, read only (a link is never followed): what [Backups.releaseBackup] reads
     * to prove a backup is no longer needed. Throws when it can't be opened.
     */
    fun openToRead(path: String): OpenSong

    /** Rescans [path]: the song's MediaStore id afterwards, or null when the scan failed. */
    suspend fun scan(path: String): Long?

    /** The MediaStore id of the file at [path] now, or null when no row names it. Throws when MediaStore can't be asked. */
    fun idOf(path: String): Long?

    /** The identity of the file at [path] itself, a link never followed (`lstat`). Throws when there is none to tell. */
    fun fileId(path: String): FileId
}

/** A file's identity: its device and inode numbers (`st_dev`, `st_ino`). */
@kotlinx.serialization.Serializable
data class FileId(val dev: Long, val ino: Long)

/**
 * A song opened in place, read and write: its [channel] for writing, positional reads of the same descriptor
 * ([read], `pread`, which never moves its offset) and its [size], and [fileId], the identity of the file it names now
 * (`fstat`). What is decided from its bytes holds for the file it writes. Closing it closes the descriptor.
 */
class OpenSong(
    val channel: FileChannel,
    private val reader: (ByteBuffer, Long) -> Int,
    private val sizer: () -> Long,
    private val identify: () -> FileId,
) : java.io.Closeable {
    /** Reads from [position] into [buffer]; 0 or less at the end of the file. */
    fun read(buffer: ByteBuffer, position: Long): Int = reader(buffer, position)

    fun size(): Long = sizer()

    /** Throws when it can't be told. */
    fun fileId(): FileId = identify()

    override fun close() = channel.close()
}

/**
 * [SongFiles] on the phone. Needs All files access (MANAGE_EXTERNAL_STORAGE), which lets MediaStore open any song for
 * writing without asking (no createWriteRequest). Blocking, except [scan].
 */
class PhoneSongFiles(context: Context) : SongFiles {
    private val app = context.applicationContext

    override fun mounted(path: String): Boolean = Environment.getExternalStorageState(File(path)) == Environment.MEDIA_MOUNTED

    override fun sameFile(mediaStoreId: Long, path: String): Boolean = asked {
        val cursor = app.contentResolver.query(uri(mediaStoreId), arrayOf(MediaStore.MediaColumns.DATA), null, null, null)
            ?: throw IOException("MediaStore gave no answer")
        cursor.use { it.moveToFirst() && it.getString(0) == path }
    }

    override fun fileId(path: String): FileId = try {
        Os.lstat(path).let { FileId(it.st_dev, it.st_ino) }
    } catch (e: ErrnoException) {
        throw e.rethrowAsIOException()
    }

    override fun idOf(path: String): Long? = asked {
        val cursor = app.contentResolver.query(
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DATA} = ?",
            arrayOf(path),
            null,
        ) ?: throw IOException("MediaStore gave no answer")
        // No row: the cursor is empty.
        cursor.use { if (it.moveToFirst()) it.getLong(0) else null }
    }

    /** [ask] of MediaStore, a refusal (SecurityException) or a bad question (IllegalArgumentException) thrown as an IOException. */
    private fun <T> asked(ask: () -> T): T = try {
        ask()
    } catch (e: SecurityException) {
        throw IOException("MediaStore refused", e)
    } catch (e: IllegalArgumentException) {
        throw IOException("MediaStore could not be asked", e)
    }

    override fun openToRead(path: String): OpenSong {
        val fd = try {
            Os.open(path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 0)
        } catch (e: ErrnoException) {
            throw e.rethrowAsIOException()
        }
        val pfd = try {
            ParcelFileDescriptor.dup(fd)
        } finally {
            try {
                Os.close(fd)
            } catch (_: ErrnoException) {
                // The duplicate is the one used.
            }
        }
        return opened(pfd, ParcelFileDescriptor.AutoCloseInputStream(pfd).channel)
    }

    override fun openInPlace(song: SongTarget): OpenSong? = asked {
        app.contentResolver.openFileDescriptor(uri(song.mediaStoreId), "rw")?.let { pfd -> opened(pfd, ParcelFileDescriptor.AutoCloseOutputStream(pfd).channel) }
    }

    /** [pfd] as a song: [channel] over it, and positional reads, its size and its identity, all through it. */
    private fun opened(pfd: ParcelFileDescriptor, channel: FileChannel): OpenSong =
        OpenSong(
            channel,
            // As IOException, which every reader of a song's bytes handles.
            reader = { buffer, position ->
                try {
                    Os.pread(pfd.fileDescriptor, buffer, position)
                } catch (e: ErrnoException) {
                    throw e.rethrowAsIOException()
                }
            },
            sizer = {
                try {
                    Os.fstat(pfd.fileDescriptor).st_size
                } catch (e: ErrnoException) {
                    throw e.rethrowAsIOException()
                }
            },
        ) {
            try {
                Os.fstat(pfd.fileDescriptor).let { FileId(it.st_dev, it.st_ino) }
            } catch (e: ErrnoException) {
                throw e.rethrowAsIOException()
            }
        }

    override suspend fun scan(path: String): Long? = withTimeoutOrNull(SCAN_TIMEOUT_MS) {
        suspendCancellableCoroutine { done ->
            MediaScannerConnection.scanFile(app, arrayOf(path), null) { _, uri ->
                if (done.isActive) done.resume(uri?.let { runCatching { ContentUris.parseId(it) }.getOrNull() })
            }
        }
    }

    private fun uri(id: Long) = ContentUris.withAppendedId(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), id)

    private companion object {
        const val SCAN_TIMEOUT_MS: Long = 30_000
    }
}

/** A change for one song. */
data class WriteJob(val song: SongTarget, val edit: TagEdit)

/**
 * Who follows a batch as it goes: [song] hears how many songs were tried so far, after each one; [stop] is asked before
 * each song, and true leaves the rest untried ([SongOutcome.NOT_TRIED]) and unchanged. A song begun is always finished.
 */
class Progress(val song: (Int) -> Unit = {}, val stop: () -> Boolean = { false }) {
    companion object {
        val NONE: Progress = Progress()
    }
}

/** What happened to one song. */
enum class SongOutcome {
    /** Changed and verified (for Undo: taken back). */
    WRITTEN,

    /** The change changes nothing in this song: not written. */
    UNCHANGED,

    /** Undo: every field this edit wrote was changed again since, so nothing was taken back. */
    CHANGED_SINCE,

    /** Undo: the song's audio is not the audio the edit wrote any more (another file now): skipped. */
    DIFFERENT_FILE,

    /** A player, the tone preview, the lyrics pipeline or the AI had it, also when it was tried again: left unchanged. */
    BUSY,

    /** Outside what this build may change (a debug build changes only its test folder): left unchanged. */
    NOT_ALLOWED,

    /** "Couldn't change": not mounted, moved, not an allowed format, no audio length, or TagLib failed. Never opened. */
    COULD_NOT_CHANGE,

    /** "Couldn't change, left as it was": a step failed after the write began, and the backup was written back. */
    LEFT_AS_IT_WAS,

    /**
     * The song's earlier write still waits for the start-up recovery (a backup not written back yet, or Undo not saved):
     * it is left alone until then. Also: this write could not be finished or checked now (a read that failed, a backup
     * not written back, a rescan that did not name the song), so the start-up recovery settles it.
     */
    RECOVERY_PENDING,

    /**
     * The song at its path is not as the write must leave it (changed or replaced meanwhile): nothing was written over
     * it, and the write was set aside, its backup kept, for a person to look at.
     */
    SET_ASIDE,

    /**
     * Written and verified, but its Undo record could not be saved ("Couldn't save Undo"): the journal intent and the
     * backup stay, the song is left alone, and the start-up recovery saves the record.
     */
    UNDO_NOT_SAVED,

    /** The media sessions could no longer be listed, so the rest of the batch was not tried. */
    NOT_TRIED,

    /**
     * Tag changes are paused: KJB can't read, or change, its safety records (the write journal), so no song is written
     * blind. Shown on Covers & tags until it can.
     */
    PAUSED,
}

data class SongResult(val song: SongTarget, val outcome: SongOutcome)

/** What a batch (one album, a selection, or one History entry's Undo) did. */
sealed interface BatchResult {
    /**
     * Each song's outcome, in the order asked; [entryId] is the History entry recording the writes (null when nothing
     * was written); [skippedFields] counts the fields an Undo left because a later edit changed them (the toast says so).
     */
    data class Done(val songs: List<SongResult>, val entryId: Long?, val skippedFields: Int = 0) : BatchResult

    /** The media sessions can't be listed (no notification access): nothing was written. */
    data object SessionsUnknown : BatchResult

    /** Not enough free space for the backups, a second copy and a margin: nothing was written. */
    data object NotEnoughSpace : BatchResult

    /** An Undo's answer: History and Undo's list can't be read now, so nothing was changed. Try again. */
    data object HistoryUnreadable : BatchResult
}

/**
 * The tag writer: one song at a time, crash-safe, in the steps of covers-tags-spec "Writing safely". For each song:
 * 1. skip it while it is busy ([busy], [locks]);
 * 2. check it: mounted, still MediaStore's row at its path, a regular file of an allowed format ([PayloadHash]);
 * 3. record an intent in the [journal] (so no backup is ever left that nothing names), then copy the song to
 *    `tag-backups/` durably (the copy and its folder fsynced) and check the copy's length and hashes; never open the song
 *    otherwise;
 * 4. make the new song in a second private copy ([TagFile] runs only there) and verify it ([verifies]): the fields
 *    asked for read back, every other field and no new one as before, the pictures as intended (bytes, type,
 *    description, MIME, in order), no ID3v2 frame the property map can't show lost, the same audio length, the same
 *    audio payload (M4A: chunk offsets included). An ID3v2.4 tag holding frames v2.3 can't keep stays v2.4;
 * 5. check [busy] once more, and that the song is still exactly what was backed up and still MediaStore's row; then
 *    record the whole intent, with the new content's state and the History record the write makes, fsynced with its
 *    folder;
 * 6. write the copy into the song in place through MediaStore ("rw": seek 0, write, truncate, fsync, close), never by
 *    renaming a file over it and never touching its modified time;
 * 7. verify the song through the same descriptor (every byte is the verified copy's), commit the intent, and rescan:
 *    no id, or another id, leaves the song as written for the start-up recovery;
 * 8. when the write threw, or the verify read other bytes, write the backup back the same way (only over the same
 *    file: MediaStore's row still at that path, a regular file) and rescan. A read that fails decides nothing: the
 *    intent stays for the start-up recovery, and nothing is written back;
 * 9. save the History record. When it can't be saved, the intent and the backup stay for the start-up recovery
 *    ([SongOutcome.UNDO_NOT_SAVED]).
 * Whatever the step, a backup is deleted, and its intent closed, only through [Backups.releaseBackup], on a fresh proof
 * that the song is at its path as written (or, when nothing was written or the backup went back, as it was); the
 * backup Undo keeps as the song's first snapshot stays. Without that proof the intent is set aside with its backup.
 * A kill at any step is settled at the next start by [JournalRecovery]; until then, and while an intent is open, the
 * song is not edited again. Every song is asked of [writes] first.
 *
 * Batches run one at a time on [io], after the start-up recovery ([ready]); each song's steps run to their end even if
 * the caller is cancelled. Logs counts, steps and exception class names only: never a title, an artist, a path or a
 * URL.
 */
class SafeWriter(
    private val dirs: TagDirs,
    private val tags: TagFile,
    private val songs: SongFiles,
    private val busy: BusyFiles,
    private val locks: PathLocks,
    private val writes: SharedWrites,
    private val journal: WriteJournal,
    private val undo: UndoStore,
    /** Free bytes in the app's own storage. */
    private val freeBytes: () -> Long,
    private val clock: () -> Long,
    private val log: (String) -> Unit = {},
    /** The ID3v2 version MP3 tags are written as. */
    private val id3: Id3Version = Id3Version.V2_3,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Waits for the start-up recovery: no song is written before it is done. */
    private val ready: suspend () -> Unit = {},
    /** How a song is copied to the private folders; tests make it fail. */
    private val copy: (from: File, to: File) -> Unit = { from, to -> durableCopy(from, to, dirs.syncDir) },
) {
    /** One batch at a time, process-wide (TagsGraph builds one writer). */
    private val oneAtATime = Mutex()

    /** The one door through which a backup is deleted. */
    private val backups = Backups(dirs, journal, undo, songs, log)
    private var intents = 0L

    /** Whether the writer's folders were made and fsynced into the app's folder ([TagDirs.ensure]) in this process. */
    private var foldersReady = false

    /**
     * Applies each job's edit to its song, and records them as one History entry of [kind], shown as [label]; [progress]
     * hears each song and may stop the rest. An edit [refused] here leaves its song unopened.
     */
    suspend fun write(jobs: List<WriteJob>, kind: EditKind, label: String, progress: Progress = Progress.NONE): BatchResult = batch(jobs.map { it.song }) {
        val entry = EntryInfo(undo.newEntryId(clock()), kind, label, clock())
        val outcomes = run(jobs.map { it.song }, progress) { song ->
            val edit = jobs.first { it.song == song }.edit
            if (refused(edit, kind)) return@run couldNot("refused, it would take something away")
            change(song, SharedWrite.TAG_WRITE, Make.Tags(expected = null) { edit }) { done -> record(entry, song, done) }
        }
        undo.prune(clock(), keep = entry.id)
        val written = outcomes.count { it.outcome == SongOutcome.WRITTEN }
        BatchResult.Done(outcomes, entry.id.takeIf { written > 0 })
    }

    /**
     * Undoes History entry [entryId] for each song not undone yet: the song's snapshot written back when the song is
     * still exactly as this edit left it and the edit started from the snapshot, else each field taken back only where
     * it still holds this edit's value. A song whose audio changed since is skipped.
     */
    suspend fun undo(entryId: Long): BatchResult {
        // History read whole, or nothing is undone: an empty stand-in would pass for an entry with nothing left to undo.
        val memory = try {
            withContext(io) { undo.whole() }
        } catch (e: IOException) {
            log("tags: History could not be read (${e.javaClass.simpleName}), nothing undone")
            return BatchResult.HistoryUnreadable
        }
        val entry = memory.entries.firstOrNull { it.id == entryId } ?: return BatchResult.Done(emptyList(), null)
        val edits = entry.songs.filterNot { it.undone }.associateBy { SongTarget(it.path, it.mediaStoreId) }
        val skipped = mutableMapOf<SongTarget, Int>()
        return batch(edits.keys.toList()) {
            val outcomes = run(edits.keys.toList(), Progress.NONE) { song ->
                val edit = edits.getValue(song)
                // A snapshot or a song that can't be read now aborts this song, nothing written: never a field-level Undo
                // in place of the full copy.
                val make = try {
                    val snapshot = undo.snapshot(edit.mediaStoreId)?.takeIf { it.sha256 == edit.before }
                    if (snapshot != null && currentSha(song) == edit.after) {
                        Make.Bytes(File(dirs.backups, snapshot.file), snapshot.sha256, expected = edit.payload, from = edit.after)
                    } else {
                        Make.Tags(expected = edit.payload) { tags ->
                            edit.revert(tags) { undo.picture(it) }.also { skipped[song] = it.skipped }.edit
                        }
                    }
                } catch (e: IOException) {
                    return@run couldNot("read", e)
                }
                val outcome = change(song, SharedWrite.TAG_RESTORE, make) { UndoRecord(undoneEntry = entryId) }
                if (outcome == SongOutcome.UNCHANGED) SongOutcome.CHANGED_SINCE else outcome
            }
            BatchResult.Done(outcomes, null, skipped.values.sum())
        }
    }

    /** The checks every batch starts with, then [body] on [io], one batch at a time. */
    private suspend fun batch(songs: List<SongTarget>, body: suspend () -> BatchResult): BatchResult {
        ready()
        return oneAtATime.withLock {
            withContext(io) {
                if (songs.isEmpty()) return@withContext body()
                if (busy.check(songs.first().path) == Busy.UNKNOWN) {
                    log("tags: media sessions can't be listed, nothing written")
                    return@withContext BatchResult.SessionsUnknown
                }
                undo.prune(clock())
                val sizes = try {
                    // A song that is not there needs no room.
                    songs.map { attributesOf(File(it.path))?.size() ?: 0L }
                } catch (e: IOException) {
                    log("tags: a song could not be looked at (${e.javaClass.simpleName}), nothing written")
                    return@withContext BatchResult.Done(songs.map { SongResult(it, SongOutcome.COULD_NOT_CHANGE) }, null)
                }
                if (freeBytes() < sizes.sum() + sizes.max() + SPACE_MARGIN_BYTES) {
                    log("tags: not enough space for ${songs.size} songs")
                    return@withContext BatchResult.NotEnoughSpace
                }
                body()
            }
        }
    }

    /**
     * [songs] one by one through [one]; a busy song is tried once more at the end. Once the sessions can't be listed,
     * or [progress] says stop, the rest is not tried. [progress] hears each song of the first round. The outcomes come
     * back in [songs]' order, and the counts go to the log.
     */
    private suspend fun run(songs: List<SongTarget>, progress: Progress, one: suspend (SongTarget) -> SongOutcome): List<SongResult> {
        val outcomes = linkedMapOf<SongTarget, SongOutcome>()
        var stopped = false
        var tried = 0
        for (round in 0..1) {
            for (song in songs) {
                if (round == 1 && (outcomes[song] != SongOutcome.BUSY || progress.stop())) continue
                outcomes[song] = if (stopped || progress.stop()) SongOutcome.NOT_TRIED else withContext(NonCancellable) { one(song) }
                if (outcomes[song] == SongOutcome.NOT_TRIED) {
                    stopped = true
                } else if (round == 0) {
                    progress.song(++tried)
                }
            }
        }
        val counts = outcomes.values.groupingBy { it }.eachCount().entries.joinToString { (outcome, count) -> "$count ${outcome.name.lowercase()}" }
        log("tags: ${songs.size} songs: $counts")
        return songs.map { SongResult(it, outcomes.getValue(it)) }
    }

    /** How a song's new content is made, from its private copy. */
    private sealed interface Make {
        /** The audio payload the song must have, or null for any; otherwise [SongOutcome.DIFFERENT_FILE]. */
        val expected: Payload?

        /** TagLib on the second copy, with the edit [edit] makes of the song's own tags (null: nothing to change). */
        class Tags(override val expected: Payload?, val edit: (SongTags) -> TagEdit?) : Make

        /**
         * [source]'s bytes as they are (when their SHA-256 is still [sha256]), written only over a song whose SHA-256
         * is [from]: a full-copy Undo.
         */
        class Bytes(val source: File, val sha256: String, override val expected: Payload?, val from: String) : Make
    }

    /** What a write leaves for the record: the song's state before and after, and the edit TagLib made (if any). */
    private class Written(val original: FileState, val written: FileState, val applied: AppliedEdit?, val backup: File)

    /**
     * The steps for one song (see the class). [record] makes the History record before the intent is written (null:
     * the record could not be prepared, and nothing is written); it is saved after the write, before the intent closes.
     */
    private suspend fun change(song: SongTarget, kind: SharedWrite, make: Make, record: (Written) -> UndoRecord): SongOutcome {
        val path = song.path
        // KJB can't change its safety records: no song is written blind.
        if (journal.paused) return SongOutcome.PAUSED
        // An earlier write of this song still waits for the start-up recovery: never edited, nor snapshotted, now.
        if (locks.pinned(path)) return SongOutcome.RECOVERY_PENDING
        if (!locks.tryHold(path)) return SongOutcome.BUSY
        var pin = false
        try {
            val openPaths = try {
                journal.openPaths()
            } catch (e: IOException) {
                // An open intent that can't be read may be this song's: tag changes pause until it can be.
                log("tags: the journal could not be read (${e.javaClass.simpleName}), nothing written")
                return SongOutcome.PAUSED
            }
            if (path in openPaths) return SongOutcome.RECOVERY_PENDING.also { pin = true }
            if (!foldersReady) {
                try {
                    dirs.ensure()
                    foldersReady = true
                } catch (e: IOException) {
                    return couldNot("folders", e)
                }
            }
            when (busy.check(path)) {
                Busy.IN_USE -> return SongOutcome.BUSY
                Busy.UNKNOWN -> return SongOutcome.NOT_TRIED
                Busy.FREE -> Unit
            }
            if (!writes.allows(kind, path)) return SongOutcome.NOT_ALLOWED
            val file = File(path)
            val original = try {
                if (!songs.mounted(path) || !regularFile(file) || !songs.sameFile(song.mediaStoreId, path)) {
                    return couldNot("check")
                }
                fileState(file) ?: return couldNot("format")
            } catch (e: IOException) {
                return couldNot("read", e)
            }
            if (make.expected != null && original.payload != make.expected) return SongOutcome.DIFFERENT_FILE
            // Changed in the moment since Undo chose the full copy: not written over.
            if (make is Make.Bytes && original.sha256 != make.from) return couldNot("changed")

            val extension = file.extension.lowercase()
            // Never a name already used: a snapshot or an earlier process's backup is never written over. The intent
            // first: from then on, the backup is named by it until the backup is released. The copy makes a new file
            // only, so a name taken in between is left alone, and the write takes another.
            var begun: WriteIntent? = null
            var copied: IOException? = null
            for (attempt in 1..NAME_TRIES) {
                val id = "${clock()}-${song.mediaStoreId}-${++intents}"
                val taken = try {
                    attributesOf(File(dirs.backups, "$id.$extension")) != null || attributesOf(File(dirs.work, "$id.$extension")) != null || journal.has(id)
                } catch (e: IOException) {
                    return couldNot("check", e)
                }
                if (taken) continue
                val intent = WriteIntent(id, path, song.mediaStoreId, "$id.$extension", original)
                if (!journal.open(intent)) return couldNot("journal")
                begun = intent
                copied = try {
                    copy(file, File(dirs.backups, intent.backup))
                    null
                } catch (e: FileAlreadyExistsException) {
                    // Not this write's backup: the intent goes, the file stays as it is.
                    try {
                        journal.close(intent.id)
                    } catch (closed: IOException) {
                        return couldNot("journal", closed)
                    }
                    begun = null
                    continue
                } catch (e: IOException) {
                    e
                }
                break
            }
            val intentBegun = begun ?: return couldNot("backup")
            val id = intentBegun.id
            val backup = File(dirs.backups, intentBegun.backup)
            val work = File(dirs.work, "$id.$extension")

            /**
             * [outcome] once the backup is released on proof that the song is at its path as [proof]; set aside, with the
             * backup, when the proof fails; left for the start-up recovery when the song can't be read for it.
             */
            fun release(proof: FileState, outcome: SongOutcome): SongOutcome = try {
                when (backups.releaseBackup(intentBegun, proof, song.mediaStoreId)) {
                    Backups.Release.RELEASED -> outcome
                    Backups.Release.SET_ASIDE -> SongOutcome.SET_ASIDE
                    // Settled, its intent still open: the start-up recovery closes it.
                    Backups.Release.NOT_CLOSED -> outcome.also { pin = true }
                    Backups.Release.NOT_SET_ASIDE -> SongOutcome.PAUSED.also { pin = true }
                }
            } catch (e: IOException) {
                log("tags: the song could not be checked, its backup left for the start-up recovery (${e.javaClass.simpleName})")
                pin = true
                SongOutcome.RECOVERY_PENDING
            }

            /** Nothing was written: [outcome], once the song is proven at its path as it was. */
            fun nothingWritten(outcome: SongOutcome): SongOutcome {
                work.delete()
                return release(original, outcome)
            }

            copied?.let { return nothingWritten(couldNot("backup", it)) }
            try {
                // A backup that is not the song, byte for byte: the song is never opened.
                if (fileState(backup) != original) return nothingWritten(couldNot("backup"))
            } catch (e: IOException) {
                return nothingWritten(couldNot("backup", e))
            }

            val made = try {
                makeContent(make, backup, work, original)
            } catch (e: IOException) {
                return nothingWritten(couldNot("copy", e))
            }
            if (made !is Made.Content) return nothingWritten(if (made == Made.Nothing) SongOutcome.UNCHANGED else couldNot("copy"))
            val written = Written(original, made.state, made.applied, backup)
            // The record can't be made (an old cover not kept, Undo's list unreadable): nothing is written.
            val undoRecord = try {
                record(written)
            } catch (e: IOException) {
                return nothingWritten(couldNot("undo", e))
            }

            when (busy.check(path)) {
                Busy.IN_USE -> return nothingWritten(SongOutcome.BUSY)
                Busy.UNKNOWN -> return nothingWritten(SongOutcome.NOT_TRIED)
                Busy.FREE -> Unit
            }
            // The last look before the whole intent and the first byte: still the song that was backed up, still its row,
            // and the file at the path now (lstat), which the intent records: nothing is written once another is there.
            val pathId = try {
                if (fileState(file) != original || !songs.sameFile(song.mediaStoreId, path)) return nothingWritten(couldNot("changed"))
                songs.fileId(path)
            } catch (e: IOException) {
                return nothingWritten(couldNot("check", e))
            }
            val intent = intentBegun.copy(expected = made.state, record = undoRecord, source = made.source.path, fileId = pathId)
            if (!journal.update(intent)) return nothingWritten(couldNot("journal"))

            val open = try {
                songs.openInPlace(song) ?: return nothingWritten(couldNot("open"))
            } catch (e: IOException) {
                return nothingWritten(couldNot("open", e))
            }
            // Once more with the descriptor open, right before the first byte: the same file at the path, still the song
            // backed up, still its row, and the descriptor reading exactly the song's bytes, so the row and the path
            // name the same file. A read that fails decides nothing: nothing is written.
            val openId = try {
                open.fileId().takeIf {
                    open.holds(original) && songs.fileId(path) == pathId && fileState(file) == original && songs.sameFile(song.mediaStoreId, path)
                }
            } catch (e: IOException) {
                open.close()
                return nothingWritten(couldNot("check", e))
            }
            if (openId == null) {
                open.close()
                return nothingWritten(couldNot("changed"))
            }
            // One descriptor from the first byte to the last, a put-back included: it names the file written, never
            // whatever another app may have put at the path meanwhile.
            val step = open.use {
                val threw = try {
                    writeInPlace(open.channel, made.source, made.state.length)
                    false
                } catch (e: IOException) {
                    log("tags: write failed (${e.javaClass.simpleName})")
                    true
                }
                if (!threw) {
                    // Verified through the same descriptor. A read that fails decides nothing: nothing is written back.
                    val right = try {
                        open.holds(made.state)
                    } catch (e: IOException) {
                        log("tags: the written song could not be read, left for the start-up recovery (${e.javaClass.simpleName})")
                        return@use Step.LEFT
                    }
                    if (right) {
                        // Written and verified: committed, so its backup is never written back over it.
                        if (!journal.commit(intent)) log("tags: intent not committed")
                        if (songs.scan(path) == song.mediaStoreId) return@use Step.WRITTEN
                        log("tags: the rescan did not name the song, left for the start-up recovery")
                        return@use Step.LEFT
                    }
                }
                putBack(open, openId, pathId, song, intent, backup)
            }
            when (step) {
                Step.WRITTEN -> Unit
                Step.RESTORED -> {
                    songs.scan(path)
                    work.delete()
                    return release(original, SongOutcome.LEFT_AS_IT_WAS)
                }
                Step.REPLACED -> {
                    // Not this song any more: nothing written over the file at the path, the write (and its backup) set
                    // aside.
                    work.delete()
                    log("tags: the song was replaced during its write, set aside")
                    if (!journal.setAside(id)) {
                        pin = true
                        return SongOutcome.PAUSED
                    }
                    return SongOutcome.SET_ASIDE
                }
                Step.LEFT -> {
                    log("tags: backup left for the start-up recovery")
                    pin = true
                    return SongOutcome.RECOVERY_PENDING
                }
            }

            // Undo is saved before the backup is released: nothing runs between the release's proof and its delete.
            work.delete()
            if (!undo.save(undoRecord, song.mediaStoreId)) {
                // Written, but no Undo yet: the intent and the backup stay, and the start-up recovery saves the record.
                log("tags: Undo not saved, left for the start-up recovery")
                pin = true
                return SongOutcome.UNDO_NOT_SAVED
            }
            return release(made.state, SongOutcome.WRITTEN)
        } finally {
            // The intent stays open: the song is held for good in this process (pinned before its lock is let go, so
            // nothing can take it in between), and the start-up recovery settles it.
            if (pin) locks.pin(path)
            locks.release(path)
        }
    }

    private sealed interface Made {
        data object Nothing : Made

        data object Failed : Made

        class Content(val source: File, val state: FileState, val applied: AppliedEdit?) : Made
    }

    /** The song's new content, made and verified from its [backup]. */
    private fun makeContent(make: Make, backup: File, work: File, original: FileState): Made {
        if (make is Make.Bytes) {
            val state = fileState(make.source)
            return if (state == null || state.sha256 != make.sha256 || state.payload != original.payload) Made.Failed else Made.Content(make.source, state, null)
        }
        make as Make.Tags
        copy(backup, work)
        val before = tags.read(work)
        if (before == null || before.durationMs <= 0) return Made.Failed.also { log("tags: no audio length") }
        val edit = make.edit(before)
        val asked = edit?.applyTo(before)
        if (asked == null || !asked.changesSomething) return Made.Nothing
        // A front cover goes only when the edit says so: Edit tags' Remove, or Undo taking back the picture an edit added.
        if (asked.frontBefore != null && asked.frontAfter == null && edit.cover !is CoverEdit.Remove && edit.cover !is CoverEdit.RemovePicture) {
            return Made.Failed.also { log("tags: the edit would drop the front cover, refused") }
        }
        // U+0000 would be written as a value separator, or cut the value short: refused, never written.
        val strings = asked.fields.flatMap { (key, values) -> values + key } + asked.pictures.flatMap { listOf(it.description, it.type, it.mime) }
        if (strings.any { '\u0000' in it }) return Made.Failed.also { log("tags: a value holds U+0000, refused") }
        // M4A keeps no picture type or description: what is written is what will read back.
        val applied = if (original.payload.format == AudioFormat.M4A) asked.copy(pictures = asked.pictures.map { it.copy(type = "", description = "") }) else asked
        val version = id3Version(before, id3)
        if (!tags.write(work, applied.fields, applied.pictures.takeIf { applied.coverChanged }, version)) {
            return Made.Failed.also { log("tags: TagLib could not write") }
        }
        val state = fileState(work)
        val after = tags.read(work)
        if (state == null || state.payload != original.payload || after == null || !verifies(applied, before, after)) {
            return Made.Failed.also { log("tags: private copy did not verify") }
        }
        return Made.Content(work, state, applied)
    }

    /**
     * Where a write in place ended: [WRITTEN] and verified; the backup [RESTORED] over it; the song [REPLACED] by another
     * file meanwhile; or [LEFT] for the start-up recovery (a read, an `lstat` or a put-back that failed).
     */
    private enum class Step { WRITTEN, RESTORED, REPLACED, LEFT }

    /**
     * Writes the backup back, through [open], the descriptor the write went through, only while it is still the file
     * written: [open] still names the file it named at the first byte ([openId], `fstat`) and the path still names the
     * file it named then ([pathId], `lstat`, a link never followed). Otherwise the song was replaced and nothing is
     * written; replaced while it was written back, the backup is kept. Verified through [open]. The caller closes
     * [open], then rescans.
     */
    private fun putBack(open: OpenSong, openId: FileId, pathId: FileId, song: SongTarget, intent: WriteIntent, backup: File): Step = try {
        when {
            open.fileId() != openId || songs.fileId(song.path) != pathId -> Step.REPLACED
            !writes.allowsOrThrow(SharedWrite.TAG_RESTORE, song.path) -> Step.LEFT
            else -> {
                writeInPlace(open.channel, backup, intent.original.length)
                when {
                    open.fileId() != openId || songs.fileId(song.path) != pathId -> Step.REPLACED
                    open.holds(intent.original) -> Step.RESTORED
                    else -> Step.LEFT
                }
            }
        }
    } catch (e: IOException) {
        log("tags: backup not written back (${e.javaClass.simpleName})")
        Step.LEFT
    }

    /**
     * The History record of a write: the edit, with its backup as the song's snapshot if it has none yet. Throws when
     * the old front cover can't be kept for Undo, or Undo's list read: then the song is not written.
     */
    private fun record(entry: EntryInfo, song: SongTarget, done: Written): UndoRecord {
        val applied = done.applied ?: return UndoRecord()
        val coverBefore = if (applied.coverChanged) applied.frontBefore?.let { undo.savePicture(it) } else null
        val edit = SongEdit(
            mediaStoreId = song.mediaStoreId,
            path = song.path,
            payload = done.original.payload,
            before = done.original.sha256,
            after = done.written.sha256,
            fields = applied.changes,
            coverChanged = applied.coverChanged,
            coverBefore = coverBefore,
            coverAfter = if (applied.coverChanged) applied.frontAfter?.let { PictureRef(it.sha256, it.description, it.type, it.mime) } else null,
        )
        val snapshot = if (undo.snapshot(song.mediaStoreId) == null) {
            Snapshot(song.mediaStoreId, song.path, done.backup.name, done.original.length, done.original.sha256, done.original.payload, entry.id, clock())
        } else {
            null
        }
        return UndoRecord(entry, edit, snapshot)
    }

    /**
     * Whether [edit] is refused before its song is opened: an empty picture as the front cover, whoever asks; and from
     * Covers & tags, anything taken away (the front cover removed, a field emptied or removed), which it never does.
     */
    private fun refused(edit: TagEdit, kind: EditKind): Boolean {
        if ((edit.cover as? CoverEdit.Replace)?.picture?.data?.isEmpty() == true) return true
        val removes = edit.cover == CoverEdit.Remove || edit.cover is CoverEdit.RemovePicture
        return kind == EditKind.COVERS_TAGS && (removes || edit.fields.values.any { values -> values.isEmpty() || values.any(String::isBlank) })
    }

    /** The SHA-256 of the song's bytes now. Throws when it can't be read. */
    private fun currentSha(song: SongTarget): String = PayloadHash.fileSha256(File(song.path).toPath())

    private fun couldNot(step: String, e: Exception? = null): SongOutcome {
        log("tags: couldn't change, $step" + (e?.let { " (${it.javaClass.simpleName})" } ?: ""))
        return SongOutcome.COULD_NOT_CHANGE
    }

    companion object {
        /** Room left over besides the backups and the second copy, so a write never fills the phone. */
        const val SPACE_MARGIN_BYTES: Long = 64L * 1024 * 1024

        /** The new names a write tries for its backup when one it chose is taken meanwhile. */
        private const val NAME_TRIES: Int = 3

        /** The ID3v2.4 frames TagLib drops when it writes ID3v2.3 (id3v2tag.cpp, downgradeFrames, iTunes hacks on). */
        val DROPPED_BY_V23: Set<String> = setOf("ASPI", "EQU2", "RVA2", "SEEK", "SIGN", "TDRL", "TDTG", "TMOO", "TPRO", "TSST")

        /**
         * The ID3v2.4 frames TagLib converts when it writes ID3v2.3, losing detail: full dates (TDRC, TDOR to TYER,
         * TDAT, TIME, TORY), people lists (TIPL, TMCL to IPLS), genres (TCON).
         */
        val CONVERTED_BY_V23: Set<String> = setOf("TDRC", "TDOR", "TIPL", "TMCL", "TCON")

        /**
         * The ID3v2 version a song is written as: [default], except that an ID3v2.4 tag holding frames v2.3 would drop
         * or convert stays v2.4, so nothing is lost and no full date is refused or cut to a year.
         */
        fun id3Version(song: SongTags, default: Id3Version): Id3Version =
            if (song.id3v2Version == 4 && song.frames.any { it in DROPPED_BY_V23 || it in CONVERTED_BY_V23 }) Id3Version.V2_4 else default

        /**
         * Whether the private copy reads back as intended ([before] is the copy before TagLib wrote it): every field the
         * edit changed holds its new value, every other field is exactly as before and none is new; the pictures are the
         * intended ones (unchanged when the cover is) by bytes, type, description and MIME, in order; no ID3v2 frame the
         * property map can't show went missing or came along; and the audio length is the same. Pure.
         */
        fun verifies(applied: AppliedEdit, before: SongTags, after: SongTags): Boolean {
            val changed = applied.changes.associateBy { it.key }
            val fields = after.fields.keys.all { it in before.fields || it in changed } &&
                changed.values.all { after.fields[it.key].orEmpty() == it.new } &&
                before.fields.keys.filter { it !in changed }.all { after.fields[it] == before.fields[it] }
            val intended = if (applied.coverChanged) applied.pictures else before.pictures
            val pictures = after.pictures.map(::pictureKey) == intended.map(::pictureKey)
            val frames = hiddenFrames(after.frames) == hiddenFrames(before.frames)
            return fields && pictures && frames && after.durationMs == before.durationMs
        }

        private fun pictureKey(picture: TagPicture) = listOf(picture.sha256, picture.type, picture.description, picture.mime)

        /**
         * The ID3v2 frames the property map can't show (RVA2, PRIV, POPM, ...), sorted: text and URL frames, comments,
         * lyrics, UFID, pictures and IPLS are checked as fields and pictures instead.
         */
        private fun hiddenFrames(frames: List<String>): List<String> =
            frames.filterNot { it.startsWith('T') || it.startsWith('W') || it in SHOWN_FRAMES }.sorted()

        private val SHOWN_FRAMES = setOf("COMM", "USLT", "UFID", "APIC", "IPLS")
    }
}
