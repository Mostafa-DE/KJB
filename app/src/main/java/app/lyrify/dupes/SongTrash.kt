package app.lyrify.dupes

import android.app.PendingIntent
import android.content.ContentResolver
import android.content.ContentUris
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import app.lyrify.data.SharedWrite
import app.lyrify.data.SharedWrites
import app.lyrify.library.LibraryReader
import app.lyrify.library.Song
import app.lyrify.tags.PayloadHash
import app.lyrify.tags.SongEdit
import app.lyrify.tags.UndoMemory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.TimeUnit

/** Moving songs to the phone's trash: two confirmations, KJB's then the phone's. */
sealed interface TrashStep {
    data object None : TrashStep

    /** KJB's own dialog asks: "Move 2 songs to trash?" */
    data class Confirm(val files: List<AudioFile>) : TrashStep

    /** The phone's dialog is open for [files] ([MediaStoreTrash]); its answer goes to [SongTrash.answered]. */
    data class System(val files: List<AudioFile>) : TrashStep

    /** The phone's dialog is open to take [song] back out of its trash; its answer goes to [SongTrash.restoreAnswered]. */
    data class Restoring(val song: TrashedSong) : TrashStep

    /** KJB's own dialog asks: "Delete 2 songs forever?" (Tools > Trash: Delete forever, Empty trash). */
    data class ConfirmDelete(val songs: List<TrashedSong>) : TrashStep

    /** The phone's dialog is open to delete [songs] for good ([MediaStoreTrash.deleteRequest]); its answer goes to [SongTrash.deleteAnswered]. */
    data class Deleting(val songs: List<TrashedSong>) : TrashStep
}

/** What the phone's answer did. */
sealed interface Trashed {
    /**
     * [count] of the [of] songs the phone was asked to move are in its trash; KJB's own lyrics for them are gone too. The
     * others the phone did not move, though it said OK: they stay as they were.
     */
    data class Moved(val count: Int, val of: Int = count) : Trashed

    /** The phone's dialog was cancelled, or nothing waited for an answer: nothing changed. */
    data object Unchanged : Trashed

    /**
     * The phone said OK, but MediaStore could not say what is in its trash: nothing changed yet; the files wait, and the
     * next Move, scan or visit to Duplicates settles them.
     */
    data object NotConfirmed : Trashed
}

/** What the phone's answer to "Delete forever" did. */
sealed interface Deleted {
    /** [count] of the [of] songs asked for are gone for good, freeing [bytes]; the others are where they were. */
    data class Gone(val count: Int, val of: Int, val bytes: Long) : Deleted

    /** The dialog was cancelled, or nothing waited for an answer: nothing changed. */
    data object Unchanged : Deleted

    /** The phone said OK, but MediaStore could not say what is left: the list catches up on its next look. */
    data object NotConfirmed : Deleted
}

/** What "Move to trash" in KJB's dialog did ([SongTrash.confirm]), and "Delete forever" in it ([SongTrash.confirmDelete]). */
sealed interface Confirmation {
    /** The phone's own dialog is to open for these MediaStore [ids]. */
    data class Ask(val ids: List<Long>) : Confirmation

    /**
     * A file changed since it was listed: nothing is asked (Duplicates: its group waits for the next scan). For a delete,
     * a song is no longer in the phone's trash.
     */
    data object Changed : Confirmation

    /**
     * MediaStore could not be read, or could not say what an earlier request moved (its files still wait): nothing is
     * asked; the user can try again.
     */
    data object Unavailable : Confirmation

    /** KJB's dialog was not open: nothing is asked. */
    data object NotAsked : Confirmation
}

/** What the phone's answer to "Restore" did. */
sealed interface Restored {
    /** The song is back in its place; KJB sees it as a new song, so its lyrics come back with the next lookup. */
    data object Back : Restored

    /** The dialog was cancelled, or the song is still in the trash: nothing changed. */
    data object Unchanged : Restored
}

/**
 * What happens to a song once the phone moved its audio file to its trash, or deleted for good a song another app had
 * moved there ([SongTrash.deleteAnswered]), by the path it had before: the `.lrc` KJB wrote for it goes (only while byte
 * for byte what KJB wrote), its ledger entry is updated, and it leaves the playlists and Favourites. A `.lrc` KJB did
 * not write stays. The lyrics pipeline and the playlists store in the app. It has left the queues already ([SongTrash]'s
 * leaveQueues).
 */
fun interface TrashCleanup {
    suspend fun trashed(audioPath: String)
}

/** The phone's trash as MediaStore shows it: [MediaStoreTrash] in the app, made up in tests. */
fun interface PhoneTrash {
    /**
     * Which of the MediaStore [ids] are in the phone's trash now, each with when the phone deletes it for good (wall-clock
     * millis; null when MediaStore does not say). Null when MediaStore could not be read. Blocking.
     */
    fun inTrash(ids: Collection<Long>): Map<Long, Long?>?

    /**
     * Every song in the phone's trash that KJB's library would list were it not trashed, whichever app moved it there
     * ([trashedSongs]); null when MediaStore could not be read. None unless a test says. Blocking.
     */
    fun songs(): List<TrashedSong>? = emptyList()
}

/**
 * [song] as the trash flow names it: its MediaStore id (the item the phone's trash request names), path, name and size,
 * which [SongTrash.confirm] checks again. Null for a song without a MediaStore id.
 */
fun trashFile(song: Song): AudioFile? = song.ref.mediaStoreId?.let { id ->
    AudioFile(
        id = id,
        path = song.path,
        relativePath = song.folder,
        displayName = song.fileName,
        title = song.title,
        artist = song.artist,
        album = song.album,
        durationMs = song.durationMs,
        size = song.sizeBytes,
        mimeType = song.mimeType,
        addedAt = song.addedAt,
    )
}

/** What the trash flow keeps between runs of the app: the files the phone's dialog was opened for, and the songs in its trash. */
data class TrashMemory(val pending: List<AudioFile> = emptyList(), val trashed: List<TrashedSong> = emptyList())

/**
 * Where [SongTrash] keeps its step and its memory: the Duplicates engine, whose `duplicates.json` holds the trash memory
 * with the scan results, so one write settles both.
 */
interface TrashHost {
    val step: TrashStep

    val memory: TrashMemory

    fun step(change: (TrashStep) -> TrashStep)

    /** Returns once the stored memory is read. */
    suspend fun loaded()

    /** Changes the memory and stores it before returning; the files with the MediaStore ids [moved] are in the phone's trash now. */
    suspend fun commit(moved: Set<Long>, change: (TrashMemory) -> TrashMemory)

    /** The files with [ids] changed since they were listed, so nothing went to the trash. */
    fun changed(ids: Set<Long>)
}

/**
 * Moving songs to the phone's trash and back, for Duplicates and the song sheet alike. Nothing is deleted by KJB: the
 * only way a file goes is the phone's own trash request ([MediaStoreTrash]), opened after KJB's own dialog ([ask],
 * [confirm]) and settled after the phone's answer ([answered]): the lyrics KJB wrote go ([cleanup]) and the song is
 * listed as trashed until the phone deletes it. A listed song can be taken back out through the phone's dialog too
 * ([restore]). The files waiting for an answer are kept, so an answer the app did not live to hear is still settled
 * ([settlePending]), and the next Move settles them before it asks ([settleLeftover], [confirm]): a request whose
 * answer never came never blocks another. The log gets counts only, never titles or paths.
 */
class SongTrash(
    private val host: TrashHost,
    /** The files with these MediaStore ids as MediaStore has them now; null when it could not be read. Blocking. */
    private val rows: (Collection<Long>) -> Map<Long, AudioFile>?,
    private val cleanup: TrashCleanup,
    private val phoneTrash: PhoneTrash,
    private val io: CoroutineDispatcher,
    private val clock: () -> Long,
    /**
     * Whether a file listed as `listed` and in MediaStore now as `now`, in the same place under the same name but of
     * another size, was changed since only by KJB's own tag writes ([kjbTagWrites]): it is still the file listed.
     * Blocking.
     */
    private val ownWrite: (listed: AudioFile, now: AudioFile) -> Boolean = { _, _ -> false },
    /**
     * The songs at these paths are in the phone's trash now, all of one answer, or gone for good from it
     * ([deleteAnswered]): they leave KJB's queues, the song playing among them giving way to the next
     * ([app.lyrify.player.PlayerCore.trashed]).
     */
    private val leaveQueues: suspend (Set<String>) -> Unit = {},
    private val log: (String) -> Unit = {},
) {
    /** One settling of the files waiting for an answer at a time: the phone's answer, a Move's, a scan's or the tab's. */
    private val settling = Mutex()

    /** Which dialog is open: KJB's, the phone's to trash, or the phone's to restore. */
    val step: TrashStep get() = host.step

    /** Songs KJB moved to the phone's trash and can still restore, the latest first. */
    val trashed: List<TrashedSong> get() = host.memory.trashed.sortedByDescending { it.trashedAt }

    private val others = MutableStateFlow<List<TrashedSong>?>(null)

    /**
     * Songs another app moved to the phone's trash that KJB's library would list, as last read ([refresh]); null until
     * then. Not kept between runs: MediaStore has them.
     */
    val elsewhere: StateFlow<List<TrashedSong>?> = others.asStateFlow()

    /**
     * "Move to trash" for [files]: KJB's own dialog asks first ([TrashStep.Confirm]). False only when nothing is given. A
     * dialog step still open gives way: KJB's dialogs and the phone's cover the screen, so a Move the user could tap
     * means none of them is up, and the answer that step waited for is not coming (its screen went, or the app died with
     * the dialog open). The files such a step left waiting are settled before the phone is asked again ([confirm]).
     */
    fun ask(files: List<AudioFile>): Boolean {
        if (files.isEmpty()) return false
        host.step { TrashStep.Confirm(files) }
        return true
    }

    /** "Cancel" in KJB's dialog, to move or to delete: nothing changes. */
    fun cancel() {
        host.step { if (it is TrashStep.Confirm || it is TrashStep.ConfirmDelete) TrashStep.None else it }
    }

    /**
     * "Move to trash" in KJB's dialog, which shows [shown]: what an earlier request left waiting is settled first, then
     * the files are remembered (so an answer the app does not live to hear is still settled), and their MediaStore ids
     * are returned for the phone's own dialog ([MediaStoreTrash.request]). Nothing is asked unless that dialog is open on
     * [shown], and a file that changed since it was listed blocks the request ([changed]). Cancelled before it returns
     * (the page went), the phone's dialog never opens, so nothing is left waiting for its answer and a later trash can
     * ask.
     */
    suspend fun confirm(shown: List<AudioFile>): Confirmation {
        if (host.step != TrashStep.Confirm(shown)) return Confirmation.NotAsked
        try {
            host.loaded()
            return settling.withLock { confirmSettled(shown) }
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                // Only what this confirm remembered goes (its step is the phone's dialog on [shown]): files an earlier
                // request left waiting, the same ones or not, stay for the next settling.
                val remembered = host.step == TrashStep.System(shown)
                host.step { if (it == TrashStep.Confirm(shown) || it == TrashStep.System(shown)) TrashStep.None else it }
                if (remembered) settling.withLock { host.commit(emptySet()) { if (it.pending == shown) it.copy(pending = emptyList()) else it } }
            }
            throw e
        }
    }

    /**
     * [confirm] with no other settling running: what an earlier request left waiting goes first (its answer never came,
     * or MediaStore could not say), so remembering [shown] in its place forgets none of it. The step changes to the
     * phone's dialog with the memory, before any other settling can take [shown] for files whose answer was lost.
     */
    private suspend fun confirmSettled(shown: List<AudioFile>): Confirmation {
        val settled = withContext(NonCancellable + io) { settleWaiting() }
        val now = if (settled) withContext(io) { rows(shown.map { it.id }) } else null
        if (now == null) {
            close(shown)
            return Confirmation.Unavailable
        }
        if (withContext(io) { shown.any { changed(it, now[it.id]) } }) {
            close(shown)
            host.changed(shown.map { it.id }.toSet())
            return Confirmation.Changed
        }
        if (host.step != TrashStep.Confirm(shown)) return Confirmation.NotAsked
        // The memory and the step change together, whatever happens to the caller meanwhile.
        withContext(NonCancellable) {
            host.commit(emptySet()) { it.copy(pending = shown) }
            host.step { TrashStep.System(shown) }
        }
        currentCoroutineContext().ensureActive()
        return Confirmation.Ask(shown.map { it.id })
    }

    /**
     * Whether [file] is not the one MediaStore has now ([row]; null: no longer listed): another place, name or size. A
     * size that only KJB's own tag writes changed ([ownWrite]) is still the same file. Blocking.
     */
    private fun changed(file: AudioFile, row: AudioFile?): Boolean =
        row == null || row.path != file.path || row.displayName != file.displayName || row.size != file.size && !ownWrite(file, row)

    /** KJB's dialog on [shown] closes, unless a newer step took its place. */
    private fun close(shown: List<AudioFile>) = host.step { if (it == TrashStep.Confirm(shown)) TrashStep.None else it }

    /**
     * The phone's dialog answered. [approved] (RESULT_OK): for each file MediaStore confirms is trashed, the lyrics KJB
     * wrote go ([cleanup]). Not approved: nothing changes. Only the phone's dialog step closes: KJB's own, open for a
     * newer Move, stays. Finishes even if the screen goes.
     */
    suspend fun answered(approved: Boolean): Trashed = withContext(NonCancellable + io) {
        host.loaded()
        host.step { if (it is TrashStep.System) TrashStep.None else it }
        settling.withLock {
            val pending = host.memory.pending
            when {
                pending.isEmpty() -> Trashed.Unchanged
                !approved -> {
                    host.commit(emptySet()) { it.copy(pending = emptyList()) }
                    Trashed.Unchanged
                }
                else -> settle(pending)?.let { Trashed.Moved(it, of = pending.size) } ?: Trashed.NotConfirmed
            }
        }
    }

    /**
     * "Restore" on a song in the phone's trash, KJB's or another app's ([elsewhere]): its MediaStore id for the phone's
     * own dialog ([MediaStoreTrash.request] with trash false). Empty while another dialog is open or the song is not
     * listed.
     */
    fun restore(song: TrashedSong): List<Long> {
        val listed = (host.memory.trashed + others.value.orEmpty()).any { it.file.id == song.file.id }
        if (!listed || host.step != TrashStep.None) return emptyList()
        host.step { TrashStep.Restoring(song) }
        return listOf(song.file.id)
    }

    /**
     * The phone's restore dialog answered: when [approved] (RESULT_OK) and MediaStore no longer has the song in its trash,
     * the song leaves the list. Its ledger entry went when it was trashed, so KJB sees it as a new song.
     */
    suspend fun restoreAnswered(approved: Boolean): Restored = withContext(NonCancellable + io) {
        val song = (host.step as? TrashStep.Restoring)?.song
        host.step { if (it is TrashStep.Restoring) TrashStep.None else it }
        val back = song != null && approved && phoneTrash.inTrash(listOf(song.file.id))?.let { song.file.id !in it } == true
        if (song == null || !back) return@withContext Restored.Unchanged
        forget(setOf(song.file.id))
        log("trash: 1 song restored")
        Restored.Back
    }

    /**
     * "Delete forever" on [songs] (one, or every song listed: Empty trash): KJB's own dialog asks first
     * ([TrashStep.ConfirmDelete]). False only when none is given. A dialog step still open gives way, as for [ask].
     */
    fun askDelete(songs: List<TrashedSong>): Boolean {
        if (songs.isEmpty()) return false
        host.step { TrashStep.ConfirmDelete(songs) }
        return true
    }

    /**
     * "Delete forever" in KJB's dialog, which shows [shown]: their MediaStore ids for the phone's own dialog
     * ([MediaStoreTrash.deleteRequest]), once MediaStore says each one is still in its trash. One that is not (restored
     * meanwhile, or gone) asks nothing ([Confirmation.Changed]), so a delete never reaches a song out of the trash. Nothing
     * is asked unless that dialog is open on [shown].
     */
    suspend fun confirmDelete(shown: List<TrashedSong>): Confirmation {
        if (host.step != TrashStep.ConfirmDelete(shown)) return Confirmation.NotAsked
        val ids = shown.map { it.file.id }
        val inTrash = try {
            withContext(io) { phoneTrash.inTrash(ids) }
        } catch (e: CancellationException) {
            closeDelete(shown)
            throw e
        }
        val answer = when {
            host.step != TrashStep.ConfirmDelete(shown) -> return Confirmation.NotAsked
            inTrash == null -> Confirmation.Unavailable
            ids.any { it !in inTrash } -> Confirmation.Changed
            else -> Confirmation.Ask(ids)
        }
        // Nothing suspends from here on: the step is the phone's dialog only for a caller that opens it.
        if (answer is Confirmation.Ask) host.step { TrashStep.Deleting(shown) } else closeDelete(shown)
        return answer
    }

    /** KJB's delete dialog on [shown] closes, unless a newer step took its place. */
    private fun closeDelete(shown: List<TrashedSong>) = host.step { if (it == TrashStep.ConfirmDelete(shown)) TrashStep.None else it }

    /**
     * The phone's delete dialog answered. [approved] (RESULT_OK): the songs MediaStore lists nowhere now are gone for good,
     * with the room they freed; every song asked for that is out of the trash leaves the lists (one restored meanwhile is
     * not counted). Another app's song gone for good gets what KJB's own got as they went to the trash: it leaves the
     * queues ([leaveQueues]), then the lyrics KJB wrote for it and its playlists go ([cleanup]), unless a file is at its
     * place again. Not approved: nothing changes. Finishes even if the screen goes.
     */
    suspend fun deleteAnswered(approved: Boolean): Deleted = withContext(NonCancellable + io) {
        host.loaded()
        val songs = (host.step as? TrashStep.Deleting)?.songs
        host.step { if (it is TrashStep.Deleting) TrashStep.None else it }
        if (songs == null || !approved) return@withContext Deleted.Unchanged
        val ids = songs.map { it.file.id }
        val left = phoneTrash.inTrash(ids)
        // Out of the trash and listed nowhere else: gone for good. One MediaStore lists again was restored meanwhile.
        val restored = left?.let { rows(ids.filter { id -> id !in it }) }
        if (left == null || restored == null) {
            log("trash: MediaStore could not say what is left in its trash, ${songs.size} songs unconfirmed")
            return@withContext Deleted.NotConfirmed
        }
        val out = ids.filterTo(HashSet()) { it !in left }
        val gone = songs.filter { it.file.id in out && it.file.id !in restored }
        // Another app's songs gone for good, by the place each had: what KJB's own got as they went to the trash.
        val own = host.memory.trashed.mapTo(HashSet()) { it.file.id }
        val places = gone.filter { it.file.id !in own }.mapNotNullTo(LinkedHashSet()) { vacated(it.file.path) }
        if (places.isNotEmpty()) leaveQueues(places)
        for (place in places) cleanup.trashed(place)
        forget(out)
        log("trash: ${gone.size} of ${songs.size} songs deleted for good")
        Deleted.Gone(gone.size, of = songs.size, bytes = gone.sumOf { it.file.size })
    }

    /** The songs with MediaStore [ids] are out of the phone's trash (restored, or deleted for good): they leave the lists. */
    private suspend fun forget(ids: Set<Long>) {
        others.update { songs -> songs?.filter { it.file.id !in ids } }
        if (host.memory.trashed.none { it.file.id in ids }) return
        host.commit(emptySet()) { memory -> memory.copy(trashed = memory.trashed.filter { it.file.id !in ids }) }
    }

    /**
     * Drops the trashed songs the phone no longer has in its trash (restored elsewhere, deleted for good) or that expired,
     * then reads what other apps moved there ([elsewhere]; as it was while MediaStore cannot be read).
     */
    suspend fun refresh(): Unit = withContext(io) {
        host.loaded()
        refreshOwn()
        val phone = phoneTrash.songs()
        val own = host.memory.trashed.mapTo(HashSet()) { it.file.id }
        val now = clock()
        others.update { before -> phone?.filter { it.file.id !in own && it.expiresAt > now } ?: before.orEmpty() }
    }

    /** [refresh] of the songs KJB moved there. */
    private suspend fun refreshOwn() {
        val songs = host.memory.trashed
        if (songs.isEmpty()) return
        val ids = songs.map { it.file.id }.toSet()
        val kept = stillTrashed(songs, phoneTrash.inTrash(ids), clock()).associateBy { it.file.id }
        if (kept.values.toList() == songs) return
        withContext(NonCancellable) {
            // Songs listed meanwhile stay as they are.
            host.commit(emptySet()) { memory -> memory.copy(trashed = memory.trashed.mapNotNull { if (it.file.id in ids) kept[it.file.id] else it }) }
        }
        log("trash: ${songs.size - kept.size} trashed songs off the list")
    }

    /**
     * A trash answer the app did not live to hear, unless the phone's dialog may still be open: MediaStore says which
     * files are trashed. Before a scan, and as Duplicates opens.
     */
    suspend fun settlePending(): Unit = withContext(NonCancellable + io) {
        host.loaded()
        settling.withLock { if (host.step !is TrashStep.System) settleWaiting() }
    }

    /**
     * What earlier requests left waiting, settled whatever dialog step is open: for a Move the user just tapped, when no
     * dialog of the phone can be up ([ask]). False when MediaStore could not say, so the files still wait. Duplicates
     * runs it before it checks a group's copies: one the phone did move is gone.
     */
    suspend fun settleLeftover(): Boolean = withContext(NonCancellable + io) {
        host.loaded()
        settling.withLock { settleWaiting() }
    }

    /** Settles the files waiting for an answer, if any; false when MediaStore could not say, so they still wait. Under [settling]. */
    private suspend fun settleWaiting(): Boolean {
        val pending = host.memory.pending
        return pending.isEmpty() || settle(pending) != null
    }

    /**
     * The [pending] files the phone moved to its trash: their lyrics cleaned up, gone from the host's lists, and listed
     * as trashed until the phone deletes them. Returns how many; null when MediaStore could not say.
     */
    private suspend fun settle(pending: List<AudioFile>): Int? {
        val expiries = phoneTrash.inTrash(pending.map { it.id })
        if (expiries == null) {
            log("trash: MediaStore could not say what is in its trash, ${pending.size} files wait")
            return null
        }
        val trashed = pending.filter { it.id in expiries }
        // The queues first, all at once: a song playing among them gives way to the next one left straight away.
        if (trashed.isNotEmpty()) leaveQueues(trashed.mapTo(LinkedHashSet()) { it.path })
        for (file in trashed) cleanup.trashed(file.path)
        val ids = trashed.map { it.id }.toSet()
        val now = clock()
        val listed = trashed.map { TrashedSong(it, now, expiries[it.id] ?: (now + TRASH_KEPT_MS)) }
        host.commit(ids) { memory -> TrashMemory(pending = emptyList(), trashed = memory.trashed.filter { it.file.id !in ids } + listed) }
        log("trash: ${trashed.size} of ${pending.size} files moved to the trash")
        return trashed.size
    }
}

/**
 * Where the file at [trashedPath] (`.trashed-<expiry>-<name>`, as the phone's trash keeps it) was before it went to the
 * trash, which KJB's lyrics and playlists name it by; null while a file, or a link, is there again: the lyrics there may
 * be its own. Blocking.
 */
private fun vacated(trashedPath: String): String? {
    val file = File(trashedPath)
    val place = File(file.parentFile, file.name.replace(TRASHED_PREFIX, ""))
    return place.path.takeIf { Files.notExists(place.toPath(), LinkOption.NOFOLLOW_LINKS) }
}

/** How long the phone keeps trashed files when MediaStore does not say (AOSP MediaProvider: 30 days). */
private const val TRASH_KEPT_MS: Long = 30 * 24 * 60 * 60_000L

/**
 * Whether the song [listed] names, listed at one size and in MediaStore [now] at another (same place, same name), was
 * changed since only by KJB's own tag writes: [edits] are History's records of it ([kjbEdits]), oldest first. The file
 * must be byte for byte what the latest one wrote ([sha256]: of the file now; null when it can't be read), and, going
 * back through the writes that follow one another (each from what the one before wrote), one must have started from a
 * file of the listed size. That is the proof the size check gives, carried through KJB's own writes: a write by anyone
 * else breaks the chain, and a record without sizes (made before KJB kept them) proves nothing.
 */
fun changedOnlyByKjb(listed: AudioFile, now: AudioFile, edits: List<SongEdit>, sha256: () -> String?): Boolean {
    val latest = edits.lastOrNull() ?: return false
    // The file is read only when MediaStore's size is the one KJB wrote.
    if (latest.lengthAfter != now.size) return false
    var bytes = sha256() ?: return false
    for (edit in edits.asReversed()) {
        if (edit.after != bytes) return false
        if (edit.lengthBefore == listed.size) return true
        bytes = edit.before
    }
    return false
}

/**
 * History's records of the song [file] names (its MediaStore id and path), oldest first, without the writes KJB undid
 * since: the file holds what the write before such a one left, or [changedOnlyByKjb]'s hash check says it does not.
 */
fun kjbEdits(history: UndoMemory, file: AudioFile): List<SongEdit> =
    history.entries.sortedBy { it.id }.flatMap { entry -> entry.songs.filter { it.mediaStoreId == file.id && it.path == file.path && !it.undone } }

/**
 * [SongTrash]'s check for a file changed since it was listed only by KJB's own tag writes ([changedOnlyByKjb]), from
 * History as [history] reads it then and the file's bytes on disk. Blocking.
 */
fun kjbTagWrites(history: () -> UndoMemory): (listed: AudioFile, now: AudioFile) -> Boolean = { listed, now ->
    changedOnlyByKjb(listed, now, kjbEdits(history(), listed)) {
        try {
            PayloadHash.fileSha256(File(listed.path).toPath())
        } catch (_: IOException) {
            null // safe: a file that can't be read proves nothing, so it counts as changed
        } catch (_: SecurityException) {
            null // safe: as above
        }
    }
}

/**
 * The phone's trash through MediaStore (Android 11 and later). [request] makes the phone's own dialog that moves songs to
 * its trash or back (`MediaStore.createTrashRequest`), and [deleteRequest] the one that deletes songs in it for good
 * (`MediaStore.createDeleteRequest`), each launched by the screen with
 * `ActivityResultContracts.StartIntentSenderForResult`; RESULT_OK means it is done. The phone shows those dialogs to every
 * app without the Media management permission (MANAGE_MEDIA), which KJB never asks for: the phone always asks the user
 * itself. Trashed files keep their MediaStore id and can be restored for about 30 days; then the phone deletes them.
 * [songs] lists them, whichever app moved them there. Every request asks [writes] first; a debug build asks only for
 * songs in its test folder.
 */
class MediaStoreTrash(private val resolver: ContentResolver, private val writes: SharedWrites) : PhoneTrash {
    sealed interface Request {
        data class Ready(val intent: PendingIntent) : Request

        /** No dialog could be made (an id MediaStore does not know, too many at once); [cause] is for the log. */
        data class Unavailable(val cause: String) : Request
    }

    /** The phone's dialog moving the songs with MediaStore [ids] to its trash, or with [trash] false back out of it. */
    fun request(ids: List<Long>, trash: Boolean = true): Request = try {
        if (!writes.allowsIds(if (trash) SharedWrite.TRASH else SharedWrite.UNTRASH, ids, ::paths)) {
            Request.Unavailable("refused by this build")
        } else {
            Request.Ready(MediaStore.createTrashRequest(resolver, ids.map(::uri), trash))
        }
    } catch (e: IllegalArgumentException) {
        Request.Unavailable(e.javaClass.simpleName)
    } catch (e: SecurityException) {
        Request.Unavailable(e.javaClass.simpleName)
    }

    /**
     * The phone's dialog deleting the songs with MediaStore [ids] for good (`MediaStore.createDeleteRequest`), asked only
     * for songs in its trash ([SongTrash.confirmDelete]). It names each by its id, which MediaProvider matches trashed or
     * not; RESULT_OK means it is done.
     */
    fun deleteRequest(ids: List<Long>): Request = try {
        if (!writes.allowsIds(SharedWrite.TRASH_DELETE, ids, ::paths)) {
            Request.Unavailable("refused by this build")
        } else {
            Request.Ready(MediaStore.createDeleteRequest(resolver, ids.map(::uri)))
        }
    } catch (e: IllegalArgumentException) {
        Request.Unavailable(e.javaClass.simpleName)
    } catch (e: SecurityException) {
        Request.Unavailable(e.javaClass.simpleName)
    }

    override fun songs(): List<TrashedSong>? = try {
        val only = Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY) }
        resolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, TRASHED_PROJECTION, only, null)?.use { trashedSongs(it, writes) }
    } catch (_: SecurityException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    /** The absolute paths of the songs with MediaStore [ids], trashed ones included; null when MediaStore gives no answer. */
    private fun paths(ids: Collection<Long>): Map<Long, String>? {
        val found = HashMap<Long, String>()
        for (chunk in ids.distinct().chunked(MAX_IDS_PER_QUERY)) {
            val cursor = resolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Audio.Media._ID, DATA), selection(chunk), null) ?: return null
            cursor.use { while (it.moveToNext()) it.getString(1)?.let { path -> found[it.getLong(0)] = path } }
        }
        return found
    }

    override fun inTrash(ids: Collection<Long>): Map<Long, Long?>? = try {
        val found = HashMap<Long, Long?>()
        val read = ids.distinct().chunked(MAX_IDS_PER_QUERY).all { chunk -> query(chunk, found) }
        if (read) found else null
    } catch (_: SecurityException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    /** Adds the trashed ones of [ids] to [found]; false when MediaStore gave no answer. Trashed items are listed too (MATCH_INCLUDE). */
    private fun query(ids: List<Long>, found: MutableMap<Long, Long?>): Boolean {
        val cursor = resolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, PROJECTION, selection(ids), null) ?: return false
        cursor.use {
            val id = it.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val trashed = it.getColumnIndexOrThrow(MediaStore.Audio.Media.IS_TRASHED)
            val expires = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_EXPIRES)
            while (it.moveToNext()) {
                if (it.isNull(trashed)) return false
                if (it.getInt(trashed) == 1) found[it.getLong(id)] = if (it.isNull(expires)) null else TimeUnit.SECONDS.toMillis(it.getLong(expires))
            }
        }
        return true
    }

    /** The rows with [ids], trashed ones included (MATCH_INCLUDE). */
    private fun selection(ids: List<Long>): Bundle = Bundle().apply {
        putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.Audio.Media._ID} IN (${ids.joinToString(",") { "?" }})")
        putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, ids.map(Long::toString).toTypedArray())
        putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
    }

    private fun uri(id: Long): Uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)

    internal companion object {
        /** Well under SQLite's limit of query arguments. */
        private const val MAX_IDS_PER_QUERY: Int = 500

        /** A file's absolute path (MediaStore's `_data`), for [writes]. */
        const val DATA: String = "_data"

        private val PROJECTION: Array<String> = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.IS_TRASHED, MediaStore.Audio.Media.DATE_EXPIRES)

        /** What [trashedSongs] reads of each trashed song, the library's own columns for whether it would list it among them. */
        val TRASHED_PROJECTION: Array<String> = arrayOf(
            MediaStore.Audio.Media._ID,
            DATA,
            MediaStore.Audio.Media.RELATIVE_PATH,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.DATE_EXPIRES,
            MediaStore.Audio.Media.IS_MUSIC,
            MediaStore.Audio.Media.IS_AUDIOBOOK,
        )
    }
}

/**
 * The songs of [cursor]'s rows, MediaStore's trashed audio ([MediaStoreTrash.TRASHED_PROJECTION]), that KJB's library
 * would list were they not trashed: by the library's own rule ([LibraryReader.inScope]: marked as music, in a Music
 * folder, or an audiobook; never a ringtone, a recording or other sounds) and only those [writes] lists (a debug build:
 * its test folders). Each as the phone's trash has it: its path is the file's there (`.trashed-<expiry>-<name>`), its
 * name and size as it was, gone for good at its expiry. A row without an expiry is left out: no date to show.
 */
fun trashedSongs(cursor: Cursor, writes: SharedWrites): List<TrashedSong> {
    val id = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
    val data = cursor.getColumnIndexOrThrow(MediaStoreTrash.DATA)
    val relativePath = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH)
    val displayName = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
    val title = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
    val artist = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
    val album = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
    val duration = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
    val size = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
    val mimeType = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
    val expires = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_EXPIRES)
    val isMusic = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.IS_MUSIC)
    val isAudiobook = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.IS_AUDIOBOOK)
    fun text(column: Int): String? = cursor.getString(column)?.trim()?.takeIf { it.isNotEmpty() && it != UNKNOWN }
    return buildList {
        while (cursor.moveToNext()) {
            val path = cursor.getString(data) ?: continue
            val folder = cursor.getString(relativePath)
            if (cursor.isNull(expires) || !writes.lists(path)) continue
            if (!LibraryReader.inScope(cursor.getInt(isMusic) != 0, cursor.getInt(isAudiobook) != 0, folder)) continue
            val name = text(displayName) ?: File(path).name.replace(TRASHED_PREFIX, "")
            val expiresAt = TimeUnit.SECONDS.toMillis(cursor.getLong(expires))
            val file = AudioFile(
                id = cursor.getLong(id),
                path = path,
                relativePath = folder,
                displayName = name,
                title = text(title) ?: name.substringBeforeLast('.'),
                artist = text(artist),
                album = text(album),
                durationMs = cursor.getLong(duration),
                size = cursor.getLong(size),
                mimeType = cursor.getString(mimeType),
            )
            // When it was moved there is not kept: about when, from its expiry.
            add(TrashedSong(file, trashedAt = expiresAt - TRASH_KEPT_MS, expiresAt = expiresAt))
        }
    }
}

/** MediaStore.UNKNOWN_STRING, its artist for a song without one (the stubs give no constant to test with). */
private const val UNKNOWN: String = "<unknown>"

/** How a trashed file's name starts on disk (AOSP MediaProvider): `.trashed-<expiry>-`. */
private val TRASHED_PREFIX = Regex("""^\.trashed-\d+-""")
