package app.lyrify.dupes

import android.app.PendingIntent
import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import app.lyrify.data.SharedWrite
import app.lyrify.data.SharedWrites
import app.lyrify.library.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
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
}

/** What the phone's answer did. */
sealed interface Trashed {
    /** [count] songs are in the phone's trash; KJB's own lyrics for them are gone too. */
    data class Moved(val count: Int) : Trashed

    /** The phone's dialog was cancelled, or nothing waited for an answer: nothing changed. */
    data object Unchanged : Trashed

    /** The phone said OK, but MediaStore could not say what is in its trash: nothing changed yet; the next scan settles it. */
    data object NotConfirmed : Trashed
}

/** What "Move to trash" in KJB's dialog did ([SongTrash.confirm]). */
sealed interface Confirmation {
    /** The phone's own dialog is to open for these MediaStore [ids]. */
    data class Ask(val ids: List<Long>) : Confirmation

    /** A file changed since it was listed: nothing is asked (Duplicates: its group waits for the next scan). */
    data object Changed : Confirmation

    /** MediaStore could not be read: nothing is asked; the user can try again. */
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
 * What happens to a song once the phone moved its audio file to its trash: the `.lrc` KJB wrote for it goes (only
 * while byte for byte what KJB wrote), its ledger entry is updated, and it leaves the playlists and Favourites. A `.lrc`
 * KJB did not write stays. The lyrics pipeline and the playlists store in the app.
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
 * ([settlePending]). The log gets counts only, never titles or paths.
 */
class SongTrash(
    private val host: TrashHost,
    /** The files with these MediaStore ids as MediaStore has them now; null when it could not be read. Blocking. */
    private val rows: (Collection<Long>) -> Map<Long, AudioFile>?,
    private val cleanup: TrashCleanup,
    private val phoneTrash: PhoneTrash,
    private val io: CoroutineDispatcher,
    private val clock: () -> Long,
    private val log: (String) -> Unit = {},
) {
    /** Which dialog is open: KJB's, the phone's to trash, or the phone's to restore. */
    val step: TrashStep get() = host.step

    /** Songs KJB moved to the phone's trash and can still restore, the latest first. */
    val trashed: List<TrashedSong> get() = host.memory.trashed.sortedByDescending { it.trashedAt }

    /**
     * "Move to trash" for [files]: KJB's own dialog asks first ([TrashStep.Confirm]). False while a dialog is open (KJB's,
     * or the phone's to trash or restore), another answer is awaited, or nothing is given: nothing is asked.
     */
    fun ask(files: List<AudioFile>): Boolean {
        if (files.isEmpty() || host.memory.pending.isNotEmpty() || host.step != TrashStep.None) return false
        host.step { TrashStep.Confirm(files) }
        return true
    }

    /** "Cancel" in KJB's dialog: nothing changes. */
    fun cancel() {
        host.step { if (it is TrashStep.Confirm) TrashStep.None else it }
    }

    /**
     * "Move to trash" in KJB's dialog, which shows [shown]: the files are remembered (so an answer the app does not live
     * to hear is still settled), then their MediaStore ids are returned for the phone's own dialog
     * ([MediaStoreTrash.request]). Nothing is asked unless that dialog is open on [shown], and a file that changed since it
     * was listed blocks the request. Cancelled before it returns (the page went), the phone's dialog never opens, so
     * nothing is left waiting for its answer and a later trash can ask.
     */
    suspend fun confirm(shown: List<AudioFile>): Confirmation {
        if (host.step != TrashStep.Confirm(shown)) return Confirmation.NotAsked
        try {
            val now = withContext(io) { rows(shown.map { it.id }) }
            if (now == null) {
                host.step { TrashStep.None }
                return Confirmation.Unavailable
            }
            val changed = shown.any { file ->
                val row = now[file.id]
                row == null || row.path != file.path || row.size != file.size || row.displayName != file.displayName
            }
            if (changed) {
                host.step { TrashStep.None }
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
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                host.step { if (it == TrashStep.Confirm(shown) || it == TrashStep.System(shown)) TrashStep.None else it }
                if (host.memory.pending == shown) host.commit(emptySet()) { it.copy(pending = emptyList()) }
            }
            throw e
        }
    }

    /**
     * The phone's dialog answered. [approved] (RESULT_OK): for each file MediaStore confirms is trashed, the lyrics KJB
     * wrote go ([cleanup]). Not approved: nothing changes. Finishes even if the screen goes.
     */
    suspend fun answered(approved: Boolean): Trashed = withContext(NonCancellable + io) {
        host.loaded()
        val pending = host.memory.pending
        host.step { TrashStep.None }
        when {
            pending.isEmpty() -> Trashed.Unchanged
            !approved -> {
                host.commit(emptySet()) { it.copy(pending = emptyList()) }
                Trashed.Unchanged
            }
            else -> settle(pending)?.let(Trashed::Moved) ?: Trashed.NotConfirmed
        }
    }

    /**
     * "Restore" on a song KJB trashed: its MediaStore id for the phone's own dialog ([MediaStoreTrash.request] with
     * trash false). Empty while another dialog is open or the song is not listed.
     */
    fun restore(song: TrashedSong): List<Long> {
        val listed = host.memory.trashed.any { it.file.id == song.file.id }
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
        host.commit(emptySet()) { memory -> memory.copy(trashed = memory.trashed.filter { it.file.id != song.file.id }) }
        log("trash: 1 song restored")
        Restored.Back
    }

    /** Drops the trashed songs the phone no longer has in its trash (restored elsewhere, deleted for good) or that expired. */
    suspend fun refresh(): Unit = withContext(io) {
        host.loaded()
        val songs = host.memory.trashed
        if (songs.isEmpty()) return@withContext
        val ids = songs.map { it.file.id }.toSet()
        val kept = stillTrashed(songs, phoneTrash.inTrash(ids), clock()).associateBy { it.file.id }
        if (kept.values.toList() == songs) return@withContext
        withContext(NonCancellable) {
            // Songs listed meanwhile stay as they are.
            host.commit(emptySet()) { memory -> memory.copy(trashed = memory.trashed.mapNotNull { if (it.file.id in ids) kept[it.file.id] else it }) }
        }
        log("trash: ${songs.size - kept.size} trashed songs off the list")
    }

    /** A trash answer the app did not live to hear (no phone dialog open now): MediaStore says which files are trashed. */
    suspend fun settlePending() {
        val pending = host.memory.pending
        if (pending.isNotEmpty() && host.step !is TrashStep.System) withContext(NonCancellable) { settle(pending) }
    }

    /**
     * The [pending] files the phone moved to its trash: their lyrics cleaned up, gone from the host's lists, and listed
     * as trashed until the phone deletes them. Returns how many; null when MediaStore could not say.
     */
    private suspend fun settle(pending: List<AudioFile>): Int? {
        val expiries = phoneTrash.inTrash(pending.map { it.id }) ?: return null
        val trashed = pending.filter { it.id in expiries }
        for (file in trashed) cleanup.trashed(file.path)
        val ids = trashed.map { it.id }.toSet()
        val now = clock()
        val listed = trashed.map { TrashedSong(it, now, expiries[it.id] ?: (now + TRASH_KEPT_MS)) }
        host.commit(ids) { memory -> TrashMemory(pending = emptyList(), trashed = memory.trashed.filter { it.file.id !in ids } + listed) }
        log("trash: ${trashed.size} of ${pending.size} files moved to the trash")
        return trashed.size
    }

    private companion object {
        /** How long the phone keeps trashed files when MediaStore does not say (AOSP MediaProvider: 30 days). */
        const val TRASH_KEPT_MS: Long = 30 * 24 * 60 * 60_000L
    }
}

/**
 * The phone's trash through MediaStore (Android 11 and later). [request] makes the phone's own dialog that moves songs to
 * its trash or back (`MediaStore.createTrashRequest`), launched by the screen with
 * `ActivityResultContracts.StartIntentSenderForResult`; RESULT_OK means it is done. The phone shows that dialog to every
 * app without the Media management permission (MANAGE_MEDIA), which KJB never asks for: the phone always asks the user
 * itself. Trashed files keep their MediaStore id and can be restored for about 30 days; then the phone deletes them.
 * Every request asks [writes] first; a debug build asks only for songs in its test folder.
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

    private companion object {
        /** Well under SQLite's limit of query arguments. */
        const val MAX_IDS_PER_QUERY: Int = 500

        /** A file's absolute path (MediaStore's `_data`), for [writes]. */
        const val DATA: String = "_data"

        val PROJECTION: Array<String> = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.IS_TRASHED, MediaStore.Audio.Media.DATE_EXPIRES)
    }
}
