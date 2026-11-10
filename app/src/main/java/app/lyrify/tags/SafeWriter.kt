package app.lyrify.tags

import android.content.ContentUris
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.system.ErrnoException
import android.system.Os
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
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.channels.FileChannel
import kotlin.coroutines.resume

/** A song to change: its absolute path, and its MediaStore id, which must still be the file at that path. */
data class SongTarget(val path: String, val mediaStoreId: Long)

/** The songs in shared storage, as the writer reaches them: MediaStore on the phone ([PhoneSongFiles]), a folder in tests. */
interface SongFiles {
    /** Whether the volume holding [path] is mounted. */
    fun mounted(path: String): Boolean

    /** Whether MediaStore's row [mediaStoreId] is the file at [path] now (ids follow files that move). */
    fun sameFile(mediaStoreId: Long, path: String): Boolean

    /**
     * [song] opened for writing in place through MediaStore, "rw" (never "w" or "rwt", which may cut it), or null when
     * it can't be. Closing it lets MediaStore rescan the song and drop its cached cover.
     */
    fun openInPlace(song: SongTarget): OpenSong?

    /** Rescans [path]: the song's MediaStore id afterwards, or null when the scan failed. */
    suspend fun scan(path: String): Long?

    /** The MediaStore id of the file at [path] now, or null when no row names it. */
    fun idOf(path: String): Long?

    /** The identity of the file at [path] itself, a link never followed (`lstat`); null when there is none. */
    fun fileId(path: String): FileId?
}

/** A file's identity: its device and inode numbers (`st_dev`, `st_ino`). */
@kotlinx.serialization.Serializable
data class FileId(val dev: Long, val ino: Long)

/**
 * A song opened for writing in place: its [channel], and [fileId], the identity of the file its descriptor names now
 * (`fstat`). Closing it closes the descriptor.
 */
class OpenSong(val channel: FileChannel, private val identify: () -> FileId?) : java.io.Closeable {
    fun fileId(): FileId? = identify()

    override fun close() = channel.close()
}

/**
 * [SongFiles] on the phone. Needs All files access (MANAGE_EXTERNAL_STORAGE), which lets MediaStore open any song for
 * writing without asking (no createWriteRequest). Blocking, except [scan].
 */
class PhoneSongFiles(context: Context) : SongFiles {
    private val app = context.applicationContext

    override fun mounted(path: String): Boolean = Environment.getExternalStorageState(File(path)) == Environment.MEDIA_MOUNTED

    override fun sameFile(mediaStoreId: Long, path: String): Boolean = try {
        app.contentResolver.query(uri(mediaStoreId), arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { cursor ->
            cursor.moveToFirst() && cursor.getString(0) == path
        } == true
    } catch (_: SecurityException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    }

    override fun fileId(path: String): FileId? = try {
        Os.lstat(path).let { FileId(it.st_dev, it.st_ino) }
    } catch (_: ErrnoException) {
        null
    }

    override fun idOf(path: String): Long? = try {
        app.contentResolver.query(
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DATA} = ?",
            arrayOf(path),
            null,
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
    } catch (_: SecurityException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    override fun openInPlace(song: SongTarget): OpenSong? = try {
        app.contentResolver.openFileDescriptor(uri(song.mediaStoreId), "rw")?.let { pfd ->
            OpenSong(ParcelFileDescriptor.AutoCloseOutputStream(pfd).channel) {
                try {
                    Os.fstat(pfd.fileDescriptor).let { FileId(it.st_dev, it.st_ino) }
                } catch (_: ErrnoException) {
                    null
                }
            }
        }
    } catch (_: FileNotFoundException) {
        null
    } catch (_: SecurityException) {
        null
    } catch (_: IllegalArgumentException) {
        null
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
     * it is left alone until then. Also: a step failed now and the backup could not be written back, or MediaStore's id
     * no longer names the song's path, so it was not.
     */
    RECOVERY_PENDING,

    /**
     * A step failed after the write began, and the file at the song's path is no longer the one written: nothing was
     * written over it, and the write was set aside (its backup kept) for a person to look at.
     */
    SET_ASIDE,

    /**
     * Written and verified, but its Undo record could not be saved ("Couldn't save Undo"): the journal intent and the
     * backup stay, the song is left alone, and the start-up recovery saves the record.
     */
    UNDO_NOT_SAVED,

    /** The media sessions could no longer be listed, so the rest of the batch was not tried. */
    NOT_TRIED,
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
}

/**
 * The tag writer: one song at a time, crash-safe, in the steps of covers-tags-spec "Writing safely". For each song:
 * 1. skip it while it is busy ([busy], [locks]);
 * 2. check it: mounted, still MediaStore's row at its path, a regular file of an allowed format ([PayloadHash]);
 * 3. copy it to `tag-backups/` durably (the copy and its folder fsynced) and check the copy's length and hashes; never
 *    open the song otherwise;
 * 4. make the new song in a second private copy ([TagFile] runs only there) and verify it ([verifies]): the fields
 *    asked for read back, every other field and no new one as before, the pictures as intended (bytes, type,
 *    description, MIME, in order), no ID3v2 frame the property map can't show lost, the same audio length, the same
 *    audio payload (M4A: chunk offsets included). An ID3v2.4 tag holding frames v2.3 can't keep stays v2.4;
 * 5. check [busy] once more, and that the song is still exactly what was backed up and still MediaStore's row; then
 *    record the intent in the [journal], with the History record the write makes, fsynced with its folder;
 * 6. write the copy into the song in place through MediaStore ("rw": seek 0, write, truncate, fsync, close), never by
 *    renaming a file over it and never touching its modified time;
 * 7. verify the song (every byte is the verified copy's) and rescan it: no id, or another id, is a failure;
 * 8. on any failure from step 6 on, write the backup back the same way (only over the same file: MediaStore's row
 *    still at that path, a regular file), rescan, and close the intent;
 * 9. save the History record, close the intent, keep the backup when it is the song's first snapshot. When the record
 *    can't be saved, the intent and the backup stay for the start-up recovery ([SongOutcome.UNDO_NOT_SAVED]).
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
    private var intents = 0L

    /** Whether the writer's folders were made and fsynced into the app's folder ([TagDirs.ensure]) in this process. */
    private var foldersReady = false

    /** Applies each job's edit to its song, and records them as one History entry of [kind], shown as [label]. */
    suspend fun write(jobs: List<WriteJob>, kind: EditKind, label: String): BatchResult = batch(jobs.map { it.song }) {
        val entry = EntryInfo(undo.newEntryId(clock()), kind, label, clock())
        val outcomes = run(jobs.map { it.song }) { song ->
            val edit = jobs.first { it.song == song }.edit
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
        val entry = undo.memory().entries.firstOrNull { it.id == entryId } ?: return BatchResult.Done(emptyList(), null)
        val edits = entry.songs.filterNot { it.undone }.associateBy { SongTarget(it.path, it.mediaStoreId) }
        val skipped = mutableMapOf<SongTarget, Int>()
        return batch(edits.keys.toList()) {
            val outcomes = run(edits.keys.toList()) { song ->
                val edit = edits.getValue(song)
                val snapshot = undo.snapshot(edit.mediaStoreId)?.takeIf { it.sha256 == edit.before }
                val make = if (snapshot != null && currentSha(song) == edit.after) {
                    Make.Bytes(File(dirs.backups, snapshot.file), snapshot.sha256, expected = edit.payload, from = edit.after)
                } else {
                    Make.Tags(expected = edit.payload) { tags ->
                        edit.revert(tags) { undo.picture(it) }.also { skipped[song] = it.skipped }.edit
                    }
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
                val sizes = songs.map { File(it.path).length() }
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
     * the rest is not tried. The outcomes come back in [songs]' order, and the counts go to the log.
     */
    private suspend fun run(songs: List<SongTarget>, one: suspend (SongTarget) -> SongOutcome): List<SongResult> {
        val outcomes = linkedMapOf<SongTarget, SongOutcome>()
        var stopped = false
        for (round in 0..1) {
            for (song in songs) {
                if (round == 1 && outcomes[song] != SongOutcome.BUSY) continue
                outcomes[song] = if (stopped) SongOutcome.NOT_TRIED else withContext(NonCancellable) { one(song) }
                if (outcomes[song] == SongOutcome.NOT_TRIED) stopped = true
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
    private suspend fun change(song: SongTarget, kind: SharedWrite, make: Make, record: (Written) -> UndoRecord?): SongOutcome {
        val path = song.path
        // An earlier write of this song still waits for the start-up recovery: never edited, nor snapshotted, now.
        if (locks.pinned(path)) return SongOutcome.RECOVERY_PENDING
        if (!locks.tryHold(path)) return SongOutcome.BUSY
        var pin = false
        try {
            if (path in journal.openPaths()) return SongOutcome.RECOVERY_PENDING.also { pin = true }
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
            if (!songs.mounted(path) || !regularFile(file) || !songs.sameFile(song.mediaStoreId, path)) {
                return couldNot("check")
            }
            val original = fileState(file) ?: return couldNot("format")
            if (make.expected != null && original.payload != make.expected) return SongOutcome.DIFFERENT_FILE
            // Changed in the moment since Undo chose the full copy: not written over.
            if (make is Make.Bytes && original.sha256 != make.from) return couldNot("changed")

            val extension = file.extension.lowercase()
            // Never a name already used: a snapshot or an earlier process's backup is never written over.
            var id: String
            do {
                id = "${clock()}-${song.mediaStoreId}-${++intents}"
            } while (File(dirs.backups, "$id.$extension").exists() || File(dirs.work, "$id.$extension").exists() || journal.has(id))
            val backup = File(dirs.backups, "$id.$extension")
            val work = File(dirs.work, "$id.$extension")
            fun dropCopies() {
                backup.delete()
                work.delete()
            }
            try {
                copy(file, backup)
            } catch (e: IOException) {
                backup.delete()
                return couldNot("backup", e)
            }
            if (fileState(backup) != original) {
                // A backup that is not the song, byte for byte: the song is never opened.
                backup.delete()
                return couldNot("backup")
            }

            val made = try {
                makeContent(make, backup, work, original)
            } catch (e: IOException) {
                null.also { log("tags: private copy failed (${e.javaClass.simpleName})") }
            }
            if (made !is Made.Content) {
                dropCopies()
                return when (made) {
                    Made.Nothing -> SongOutcome.UNCHANGED
                    else -> couldNot("copy")
                }
            }
            val written = Written(original, made.state, made.applied, backup)
            val undoRecord = record(written)
            if (undoRecord == null) {
                dropCopies()
                return couldNot("undo")
            }

            when (busy.check(path)) {
                Busy.IN_USE -> return SongOutcome.BUSY.also { dropCopies() }
                Busy.UNKNOWN -> return SongOutcome.NOT_TRIED.also { dropCopies() }
                Busy.FREE -> Unit
            }
            // The last look before the intent and the first byte: still the song that was backed up, still its row.
            if (fileState(file) != original || !songs.sameFile(song.mediaStoreId, path)) {
                dropCopies()
                return couldNot("changed")
            }
            // The file at the path now (lstat): the intent records it, and nothing is written once another is there.
            val pathId = songs.fileId(path)
            if (pathId == null) {
                dropCopies()
                return couldNot("check")
            }
            val intent = WriteIntent(id, path, song.mediaStoreId, backup.name, original, made.state, undoRecord, made.source.path, fileId = pathId)
            if (!journal.open(intent)) {
                dropCopies()
                return couldNot("journal")
            }

            val open = songs.openInPlace(song)
            val openId = open?.fileId()
            // Once more with the descriptor open, right before the first byte: the same file at the path, still the song
            // backed up, still its row.
            if (open == null || openId == null || songs.fileId(path) != pathId || fileState(file) != original || !songs.sameFile(song.mediaStoreId, path)) {
                // Nothing was written: the song is as it was.
                open?.close()
                journal.close(id)
                dropCopies()
                return couldNot(if (open == null) "open" else "changed")
            }
            // One descriptor from the first byte to the last, a put-back included: it names the file written, never
            // whatever another app may have put at the path meanwhile.
            val putBack = open.use {
                val done = try {
                    writeInPlace(open.channel, made.source, made.state.length)
                    fileState(file) == made.state && songs.scan(path) == song.mediaStoreId
                } catch (e: IOException) {
                    log("tags: write failed (${e.javaClass.simpleName})")
                    false
                }
                if (done) null else putBack(open, openId, pathId, song, intent, backup)
            }
            when (putBack) {
                null -> Unit
                PutBack.RESTORED -> {
                    songs.scan(path)
                    journal.close(id)
                    dropCopies()
                    return SongOutcome.LEFT_AS_IT_WAS
                }
                PutBack.REPLACED -> {
                    // Not this song any more: nothing written over it, the write (and its backup) set aside.
                    journal.setAside(id)
                    work.delete()
                    log("tags: the song was replaced during its write, set aside")
                    return SongOutcome.SET_ASIDE
                }
                PutBack.FAILED -> {
                    log("tags: backup left for the start-up recovery")
                    pin = true
                    return SongOutcome.RECOVERY_PENDING
                }
            }

            // Written and verified: committed, so its backup is never written back over it, then Undo is saved.
            work.delete()
            if (!journal.commit(intent)) log("tags: intent not committed")
            if (!undo.save(undoRecord, song.mediaStoreId)) {
                // Written, but no Undo yet: the intent and the backup stay, and the start-up recovery saves the record.
                log("tags: Undo not saved, left for the start-up recovery")
                pin = true
                return SongOutcome.UNDO_NOT_SAVED
            }
            journal.close(id)
            if (backup.name !in undo.keptBackups()) backup.delete()
            return SongOutcome.WRITTEN
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
        val asked = make.edit(before)?.applyTo(before)
        if (asked == null || !asked.changesSomething) return Made.Nothing
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

    private enum class PutBack { RESTORED, REPLACED, FAILED }

    /**
     * Writes the backup back after a failure, through [open], the descriptor the write went through, only while it is
     * still the file written: [open] still names the file it named at the first byte ([openId], `fstat`) and the path
     * still names the file it named then ([pathId], `lstat`, a link never followed). Otherwise the song was replaced
     * and nothing is written. The caller closes [open], then rescans.
     */
    private fun putBack(open: OpenSong, openId: FileId, pathId: FileId, song: SongTarget, intent: WriteIntent, backup: File): PutBack {
        if (open.fileId() != openId || songs.fileId(song.path) != pathId) return PutBack.REPLACED
        if (!writes.allows(SharedWrite.TAG_RESTORE, song.path)) return PutBack.FAILED
        return try {
            writeInPlace(open.channel, backup, intent.original.length)
            if (fileState(File(song.path)) == intent.original) PutBack.RESTORED else PutBack.FAILED
        } catch (e: IOException) {
            log("tags: backup not written back (${e.javaClass.simpleName})")
            PutBack.FAILED
        }
    }

    /**
     * The History record of a write: the edit, with its backup as the song's snapshot if it has none yet. Null when the
     * old front cover can't be kept for Undo: then the song is not written.
     */
    private fun record(entry: EntryInfo, song: SongTarget, done: Written): UndoRecord? {
        val applied = done.applied ?: return UndoRecord()
        val coverBefore = if (applied.coverChanged) applied.frontBefore?.let { undo.savePicture(it) ?: return null } else null
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

    private fun currentSha(song: SongTarget): String? = try {
        PayloadHash.fileSha256(File(song.path).toPath())
    } catch (_: IOException) {
        null
    }

    private fun couldNot(step: String, e: Exception? = null): SongOutcome {
        log("tags: couldn't change, $step" + (e?.let { " (${it.javaClass.simpleName})" } ?: ""))
        return SongOutcome.COULD_NOT_CHANGE
    }

    companion object {
        /** Room left over besides the backups and the second copy, so a write never fills the phone. */
        const val SPACE_MARGIN_BYTES: Long = 64L * 1024 * 1024

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
