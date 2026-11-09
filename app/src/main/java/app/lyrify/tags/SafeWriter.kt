package app.lyrify.tags

import android.content.ContentUris
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
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
import java.nio.file.Files
import java.nio.file.LinkOption
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
    fun openInPlace(song: SongTarget): FileChannel?

    /** Rescans [path]: the song's MediaStore id afterwards, or null when the scan failed. */
    suspend fun scan(path: String): Long?
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

    override fun openInPlace(song: SongTarget): FileChannel? = try {
        app.contentResolver.openFileDescriptor(uri(song.mediaStoreId), "rw")?.let { ParcelFileDescriptor.AutoCloseOutputStream(it).channel }
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

    /** A step failed and the backup could not be written back yet: the start-up recovery will. */
    RECOVERY_PENDING,

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
 * 3. copy it to `tag-backups/` durably and check the copy's length and hashes; never open the song otherwise;
 * 4. make the new song in a second private copy ([TagFile] runs only there) and verify it: the fields asked for read
 *    back, every other field still there, the pictures as intended, the same audio length, the same audio payload (M4A:
 *    chunk offsets included);
 * 5. record the intent in the [journal], fsynced, and check [busy] once more;
 * 6. write the copy into the song in place through MediaStore ("rw": seek 0, write, truncate, fsync, close), never by
 *    renaming a file over it and never touching its modified time;
 * 7. verify the song (every byte is the verified copy's) and rescan it: no id, or another id, is a failure;
 * 8. on any failure from step 6 on, write the backup back the same way, rescan, and close the intent;
 * 9. record the edit for History and Undo, close the intent, keep the backup when it is the song's first snapshot.
 * A kill at any step is settled at the next start by [JournalRecovery]. Every song is asked of [writes] first.
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
    private val copy: (from: File, to: File) -> Unit = ::durableCopy,
) {
    /** One batch at a time, process-wide (TagsGraph builds one writer). */
    private val oneAtATime = Mutex()
    private var intents = 0L

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
                val outcome = change(song, SharedWrite.TAG_RESTORE, make) { undo.markUndone(entryId, edit.mediaStoreId) }
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

    /** What a written song leaves for the record: its state before and after, and the edit TagLib made (if any). */
    private class Written(val original: FileState, val written: FileState, val applied: AppliedEdit?, val backup: File)

    /** The steps for one song (see the class); [onWritten] records it before its intent is closed. */
    private suspend fun change(song: SongTarget, kind: SharedWrite, make: Make, onWritten: (Written) -> Unit): SongOutcome {
        val path = song.path
        if (!locks.tryHold(path)) return SongOutcome.BUSY
        try {
            when (busy.check(path)) {
                Busy.IN_USE -> return SongOutcome.BUSY
                Busy.UNKNOWN -> return SongOutcome.NOT_TRIED
                Busy.FREE -> Unit
            }
            if (!writes.allows(kind, path)) return SongOutcome.NOT_ALLOWED
            val file = File(path)
            if (!songs.mounted(path) || !Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) || !songs.sameFile(song.mediaStoreId, path)) {
                return couldNot("check")
            }
            val original = fileState(file) ?: return couldNot("format")
            if (make.expected != null && original.payload != make.expected) return SongOutcome.DIFFERENT_FILE
            // Changed in the moment since Undo chose the full copy: not written over.
            if (make is Make.Bytes && original.sha256 != make.from) return couldNot("changed")

            val id = "${clock()}-${song.mediaStoreId}-${++intents}"
            val extension = file.extension.lowercase()
            val backup = File(dirs.backups, "$id.$extension")
            val work = File(dirs.work, "$id.$extension")
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
                backup.delete()
                work.delete()
                return when (made) {
                    Made.Nothing -> SongOutcome.UNCHANGED
                    else -> couldNot("copy")
                }
            }

            when (busy.check(path)) {
                Busy.IN_USE -> return SongOutcome.BUSY.also { backup.delete(); work.delete() }
                Busy.UNKNOWN -> return SongOutcome.NOT_TRIED.also { backup.delete(); work.delete() }
                Busy.FREE -> Unit
            }
            val intent = WriteIntent(id, path, song.mediaStoreId, backup.name, original, made.state)
            if (!journal.open(intent)) {
                backup.delete()
                work.delete()
                return couldNot("journal")
            }

            val channel = songs.openInPlace(song)
            if (channel == null) {
                // Nothing was written: the song is as it was.
                journal.close(id)
                backup.delete()
                work.delete()
                return couldNot("open")
            }
            val written = try {
                channel.use { writeInPlace(it, made.source, made.state.length) }
                fileState(file) == made.state && songs.scan(path) == song.mediaStoreId
            } catch (e: IOException) {
                log("tags: write failed (${e.javaClass.simpleName})")
                false
            }
            if (!written) return putBack(song, intent, backup, work)

            onWritten(Written(original, made.state, made.applied, backup))
            journal.close(id)
            work.delete()
            if (backup.name !in undo.keptBackups()) backup.delete()
            return SongOutcome.WRITTEN
        } finally {
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
        val applied = make.edit(before)?.applyTo(before)
        if (applied == null || !applied.changesSomething) return Made.Nothing
        if (!tags.write(work, applied.fields, applied.pictures, id3)) return Made.Failed.also { log("tags: TagLib could not write") }
        val state = fileState(work)
        val after = tags.read(work)
        if (state == null || state.payload != original.payload || after == null || !verifies(applied, before, after)) {
            return Made.Failed.also { log("tags: private copy did not verify") }
        }
        return Made.Content(work, state, applied)
    }

    /**
     * Writes the backup back after a failure (the same way: in place, then a rescan), and closes the intent. When that
     * fails too, the intent and the backup stay for the start-up recovery.
     */
    private suspend fun putBack(song: SongTarget, intent: WriteIntent, backup: File, work: File): SongOutcome {
        work.delete()
        val restored = writes.allows(SharedWrite.TAG_RESTORE, song.path) && try {
            songs.openInPlace(song)?.use { writeInPlace(it, backup, intent.original.length) } != null &&
                fileState(File(song.path)) == intent.original
        } catch (e: IOException) {
            log("tags: backup not written back (${e.javaClass.simpleName})")
            false
        }
        if (!restored) {
            log("tags: backup left for the start-up recovery")
            return SongOutcome.RECOVERY_PENDING
        }
        songs.scan(song.path)
        journal.close(intent.id)
        backup.delete()
        return SongOutcome.LEFT_AS_IT_WAS
    }

    /** Records a written song in History, with its backup as its snapshot if it has none yet. */
    private fun record(entry: EntryInfo, song: SongTarget, done: Written) {
        val applied = done.applied ?: return
        val first = undo.snapshot(song.mediaStoreId) == null
        val edit = SongEdit(
            mediaStoreId = song.mediaStoreId,
            path = song.path,
            payload = done.original.payload,
            before = done.original.sha256,
            after = done.written.sha256,
            fields = applied.changes,
            coverChanged = applied.coverChanged,
            coverBefore = if (applied.coverChanged) applied.frontBefore?.let(undo::savePicture) else null,
            coverAfter = if (applied.coverChanged) applied.frontAfter?.let { PictureRef(it.sha256, it.description, it.type, it.mime) } else null,
        )
        val snapshot = if (first) {
            Snapshot(song.mediaStoreId, song.path, done.backup.name, done.original.length, done.original.sha256, done.original.payload, entry.id, clock())
        } else {
            null
        }
        if (!undo.record(entry, edit, snapshot)) log("tags: edit not recorded for Undo")
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

        /**
         * Whether the private copy reads back as intended: every field the edit changed holds its new value, every other
         * field is still there, the pictures are the intended ones in order (by their bytes: M4A keeps no types), and
         * the audio length is the same. Pure.
         */
        fun verifies(applied: AppliedEdit, before: SongTags, after: SongTags): Boolean {
            val changed = applied.changes.associateBy { it.key }
            val fields = changed.values.all { after.fields[it.key].orEmpty() == it.new } &&
                before.fields.keys.filter { it !in changed }.all { after.fields[it].orEmpty().isNotEmpty() }
            val pictures = after.pictures.map { it.sha256 } == applied.pictures.map { it.sha256 }
            return fields && pictures && after.durationMs == before.durationMs
        }
    }
}
