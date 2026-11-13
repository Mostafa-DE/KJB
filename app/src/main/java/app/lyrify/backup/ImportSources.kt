package app.lyrify.backup

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import app.lyrify.LOG_TAG
import app.lyrify.library.LibraryGraph
import app.lyrify.library.LibraryState
import app.lyrify.library.PlaylistsState
import app.lyrify.player.PlayerGraph
import app.lyrify.player.QueueBook
import app.lyrify.stats.LoggedPlay
import app.lyrify.stats.StatsGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

// Where an import's files come from (lane IM1): the files and folders the user picks in the system's dialogs, and the
// playlists other apps saved in MediaStore; and the app's stores an import writes.

/** A document a picked folder lists: its id with the provider, its name, whether it is a folder, and its size when known. */
data class FolderChild(val id: String, val name: String?, val folder: Boolean, val sizeBytes: Long? = null)

/** The playlist files of a picked folder ("Music", "Playlists", "Musicolet/Backups"), found without reading any other file. */
object ImportFolder {
    /** Folders below the one picked that are looked in: "Music/Playlists/Old". */
    const val MAX_DEPTH: Int = 2

    /** Documents listed at most, files and folders together, so a folder of thousands of songs costs little. */
    const val MAX_LISTED: Int = 5_000

    /** How Musicolet names a backup: "2026-11-13 09-30-00.zip". */
    private val BACKUP_NAME = Regex("""\d{4}-\d{2}-\d{2} \d{2}-\d{2}-\d{2}\.zip""", RegexOption.IGNORE_CASE)

    /**
     * The playlist files ([ImportFormats.isPlaylistFile]) in the folder [root] and in its folders [MAX_DEPTH] deep, each
     * folder's in name order before its folders', at most [ImportFormats.MAX_FILES]; then, of the zips in the folder
     * itself ("Musicolet/Backups"), only the newest: Musicolet names its backups by their date and time, so the last of
     * those by name (else the last zip by name). [children] lists a folder (null when it cannot), and is asked for at
     * most [MAX_LISTED] documents in all.
     */
    fun walk(root: String, children: (String) -> List<FolderChild>?): List<FolderChild> {
        val found = mutableListOf<FolderChild>()
        var newest: FolderChild? = null
        var listed = 0
        val byName = compareBy(String.CASE_INSENSITIVE_ORDER) { child: FolderChild -> child.name.orEmpty() }
        fun visit(id: String, depth: Int) {
            if (found.size >= ImportFormats.MAX_FILES || listed >= MAX_LISTED) return
            val all = children(id) ?: return
            val listing = all.take(MAX_LISTED - listed)
            listed += listing.size
            listing.filter { !it.folder && ImportFormats.isPlaylistFile(it.name) }.sortedWith(byName).forEach { if (found.size < ImportFormats.MAX_FILES) found += it }
            if (depth == 0) {
                val zips = listing.filter { !it.folder && ImportFormats.isArchive(it.name) }
                newest = zips.filter { BACKUP_NAME.matches(it.name.orEmpty()) }.maxWithOrNull(byName) ?: zips.maxWithOrNull(byName)
            }
            if (depth < MAX_DEPTH) listing.filter { it.folder }.sortedWith(byName).forEach { visit(it.id, depth + 1) }
        }
        visit(root, 0)
        return found + listOfNotNull(newest?.takeIf { found.size < ImportFormats.MAX_FILES })
    }
}

/** A song of a playlist another app saved on the phone, as MediaStore lists it: its file and tags. */
data class PhoneMember(val path: String?, val title: String?, val artist: String?, val album: String?, val durationMs: Long?, val sizeBytes: Long?)

/** MediaStore's playlists, read only: each playlist's id and name, and its songs in order. A fake in tests. */
interface PhonePlaylistRows {
    fun playlists(): List<Pair<Long, String?>>

    fun members(id: Long): List<PhoneMember>
}

/**
 * "On your phone": the playlists other apps saved in MediaStore. Its playlists are deprecated since API 31 and
 * unverified on Android 17, so every read is fail-safe: one that throws leaves out that playlist, or all of them, and
 * the log gets the exception's class only. Never written (KJB keeps its own playlists, [M3u]).
 */
object PhonePlaylists {
    /** How many playlists other apps saved; 0 when there are none or they cannot be read. */
    fun count(rows: PhonePlaylistRows, log: (String) -> Unit = {}): Int = lists(rows, log)?.size ?: 0

    /** The playlists other apps saved, each with its songs, within the import caps; empty when they cannot be read. */
    fun bundle(rows: PhonePlaylistRows, log: (String) -> Unit = {}): ImportBundle {
        var total = 0
        val playlists = lists(rows, log).orEmpty().take(ImportFormats.MAX_FILES).mapNotNull { (id, name) ->
            if (total >= ImportFormats.MAX_TOTAL_ENTRIES) return@mapNotNull null
            val members = try {
                rows.members(id)
            } catch (e: Exception) {
                // SecurityException, IllegalArgumentException, IllegalStateException: whatever this Android does now.
                log("phone playlists: one not read (${e.javaClass.simpleName})")
                return@mapNotNull null
            }
            val songs = members.take(minOf(ImportFormats.MAX_ENTRIES, ImportFormats.MAX_TOTAL_ENTRIES - total)).mapNotNull(::song)
            total += songs.size
            ImportPlaylist(ImportFormats.cleanName(name), songs)
        }
        return ImportBundle(playlists)
    }

    private fun lists(rows: PhonePlaylistRows, log: (String) -> Unit): List<Pair<Long, String?>>? = try {
        rows.playlists()
    } catch (e: Exception) {
        log("phone playlists: not read (${e.javaClass.simpleName})")
        null
    }

    /** A member's song by its file (MediaStore's absolute path) and tags; null with neither a path nor a title. */
    private fun song(member: PhoneMember): ImportSong? {
        val path = ImportFormats.clean(member.path)?.takeIf { it.startsWith('/') }
        val title = ImportFormats.clean(member.title)
        if (path == null && title == null) return null
        return ImportSong(
            location = path.orEmpty(),
            path = path,
            title = title,
            artist = ImportFormats.clean(member.artist),
            album = ImportFormats.clean(member.album),
            durationMs = member.durationMs?.takeIf { it > 0 },
            sizeBytes = member.sizeBytes?.takeIf { it > 0 },
        )
    }
}

/**
 * MediaStore's playlists through the deprecated `MediaStore.Audio.Playlists` (kept for compatibility, "no new usages
 * advised"): every column read by name, a missing one as none, so a column a newer Android drops costs only that clue.
 */
@Suppress("DEPRECATION")
class MediaStorePlaylistRows(private val resolver: ContentResolver) : PhonePlaylistRows {
    override fun playlists(): List<Pair<Long, String?>> = resolver.query(
        MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI,
        arrayOf(MediaStore.Audio.Playlists._ID, MediaStore.Audio.Playlists.NAME),
        null,
        null,
        MediaStore.Audio.Playlists.NAME,
    )?.use { rows ->
        buildList { while (size < ImportFormats.MAX_FILES && rows.moveToNext()) add(rows.getLong(0) to rows.text(1)) }
    }.orEmpty()

    override fun members(id: Long): List<PhoneMember> = resolver.query(
        MediaStore.Audio.Playlists.Members.getContentUri(MediaStore.VOLUME_EXTERNAL, id),
        null,
        null,
        null,
        MediaStore.Audio.Playlists.Members.PLAY_ORDER,
    )?.use { rows ->
        val path = rows.getColumnIndex(DATA)
        val title = rows.getColumnIndex(MediaStore.MediaColumns.TITLE)
        val artist = rows.getColumnIndex(MediaStore.MediaColumns.ARTIST)
        val album = rows.getColumnIndex(MediaStore.MediaColumns.ALBUM)
        val duration = rows.getColumnIndex(MediaStore.MediaColumns.DURATION)
        val bytes = rows.getColumnIndex(MediaStore.MediaColumns.SIZE)
        buildList {
            while (size < ImportFormats.MAX_ENTRIES && rows.moveToNext()) {
                add(PhoneMember(rows.text(path), rows.text(title), rows.text(artist), rows.text(album), rows.number(duration), rows.number(bytes)))
            }
        }
    }.orEmpty()

    private fun Cursor.text(column: Int): String? = if (column < 0 || isNull(column)) null else getString(column)

    private fun Cursor.number(column: Int): Long? = if (column < 0 || isNull(column)) null else getLong(column)

    private companion object {
        /** The file's absolute path, which KJB's library goes by (All files access). */
        const val DATA: String = "_data"
    }
}

/**
 * Playlist files and Musicolet backups through the Storage Access Framework, from the files and folders the user picks
 * in the system's dialogs; no permission needed, and nothing is ever written there. A file is read whole into memory,
 * capped ([ImportFormats.readBounded]), and no name a file gives ever becomes a path. A Musicolet backup's songs table
 * goes through [musicolet]. The log gets counts and exception names only.
 */
class ImportDocuments(private val resolver: ContentResolver, private val musicolet: MusicoletDb, private val log: (String) -> Unit) {
    /** Each of the picked [documents] read, in the order picked; those past [ImportFormats.MAX_FILES] are too many to read. */
    fun files(documents: List<Uri>): List<ImportRead> = documents.mapIndexed { at, document ->
        if (at < ImportFormats.MAX_FILES) read(document) else ImportRead.Failed(displayName(document), ImportFailure.TOO_BIG)
    }

    /** The playlist files of the picked folder [tree] read ([ImportFolder.walk]); null when it is no folder KJB can list. */
    fun folder(tree: Uri): List<ImportRead>? {
        val root = try {
            DocumentsContract.getTreeDocumentId(tree)
        } catch (_: IllegalArgumentException) {
            log("import: not a folder")
            return null
        }
        return ImportFolder.walk(root) { id -> children(tree, id) }.map { child ->
            read(DocumentsContract.buildDocumentUriUsingTree(tree, child.id), child.name, child.sizeBytes)
        }
    }

    private fun read(document: Uri, knownName: String? = null, knownSize: Long? = null): ImportRead {
        var name = knownName
        return try {
            if (name == null) name = displayName(document)
            if (knownSize != null && knownSize > ImportFormats.MAX_ARCHIVE_BYTES) return ImportRead.Failed(ImportFormats.cleanName(name), ImportFailure.TOO_BIG)
            val folder = try {
                M3u.documentPath(document.authority, DocumentsContract.getDocumentId(document), primaryRoot())?.substringBeforeLast('/')
            } catch (_: IllegalArgumentException) {
                null
            }
            val input = resolver.openInputStream(document) ?: throw IOException("no stream")
            val bytes = input.use(ImportFormats::readBounded) ?: return ImportRead.Failed(ImportFormats.cleanName(name), ImportFailure.TOO_BIG)
            ImportFormats.read(bytes, name, folder, musicolet)
        } catch (e: Exception) {
            // IOException, SecurityException, or the provider's own.
            log("import: file not read: ${e.javaClass.simpleName}")
            ImportRead.Failed(ImportFormats.cleanName(name), ImportFailure.UNREADABLE)
        }
    }

    private fun displayName(document: Uri): String? = try {
        resolver.query(document, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        }
    } catch (e: Exception) {
        log("import: name not read: ${e.javaClass.simpleName}")
        null
    }

    /** The folder [id] of [tree]'s children; null when the provider cannot list it. */
    private fun children(tree: Uri, id: String): List<FolderChild>? = try {
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        resolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, id), columns, null, null, null)?.use { rows ->
            buildList {
                while (size < ImportFolder.MAX_LISTED && rows.moveToNext()) {
                    val child = rows.getString(0) ?: continue
                    add(FolderChild(child, rows.getString(1), rows.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR, if (rows.isNull(3)) null else rows.getLong(3)))
                }
            }
        }
    } catch (e: Exception) {
        log("import: folder not listed: ${e.javaClass.simpleName}")
        null
    }

    private fun primaryRoot(): String = Environment.getExternalStorageDirectory().absolutePath
}

/** [ImportStores] on the app's own: `playlists.json` through the playlists' store, and `plays.db`. */
class AppImportStores(context: Context) : ImportStores {
    private val app = context.applicationContext

    override suspend fun updatePlaylists(change: (PlaylistsState) -> PlaylistsState): Boolean = LibraryGraph.playlists(app).update(change)

    /** The queues on the main thread, as the player keeps them (AppBackupStores' way). */
    override suspend fun updateQueues(change: (QueueBook) -> QueueBook): Boolean = withContext(Dispatchers.Main) {
        val core = PlayerGraph.core(app)
        core.ready.first { it }
        core.edit { change(it) to Unit } != null
    }

    override suspend fun plays(): List<LoggedPlay>? = StatsGraph.log(app).all()

    override suspend fun addPlays(plays: List<LoggedPlay>): Boolean = StatsGraph.log(app).putAll(plays)

    override suspend fun removePlays(plays: List<LoggedPlay>): Boolean = StatsGraph.log(app).removeAll(plays)
}

/** Import from app's steps for its sheet, each off the main thread; the log gets counts and kinds only. */
object Imports {
    /** The picked files, read. */
    suspend fun files(context: Context, documents: List<Uri>): ImportPick = withContext(Dispatchers.IO) {
        ImportFormats.combine(documents(context).files(documents)).also(::logged)
    }

    /** The picked folder's playlist files, read; null when it is no folder KJB can list. */
    suspend fun folder(context: Context, tree: Uri): ImportPick? = withContext(Dispatchers.IO) {
        documents(context).folder(tree)?.let(ImportFormats::combine)?.also(::logged)
    }

    /** How many playlists other apps saved on the phone; 0 when none or they cannot be read. */
    suspend fun phoneCount(context: Context): Int = withContext(Dispatchers.IO) {
        PhonePlaylists.count(MediaStorePlaylistRows(context.contentResolver)) { Log.w(LOG_TAG, it) }
    }

    /** The playlists other apps saved on the phone. */
    suspend fun phone(context: Context): ImportPick = withContext(Dispatchers.IO) {
        val bundle = PhonePlaylists.bundle(MediaStorePlaylistRows(context.contentResolver)) { Log.w(LOG_TAG, it) }
        ImportPick(bundle, bundle.playlists.size, emptyList()).also(::logged)
    }

    /** [bundle] matched against the library as it is now (no song is found while it cannot be read). */
    suspend fun plan(context: Context, bundle: ImportBundle): ImportPlan = withContext(Dispatchers.Default) {
        val library = (LibraryGraph.repository(context.applicationContext).state.first { it !is LibraryState.Loading } as? LibraryState.Ready)?.library
        ImportPlan(bundle, library?.all.orEmpty()).also { plan ->
            Log.i(LOG_TAG, "import: ${bundle.size} songs named, ${plan.missing(ImportKind.entries.toSet()).size} not found")
        }
    }

    /** Writes [chosen] of [plan]; [untitled] names a playlist without a name, [queueName] the queues, [reserved] are KJB's own lists' names. */
    suspend fun apply(context: Context, plan: ImportPlan, chosen: Set<ImportKind>, untitled: String, queueName: String, reserved: Set<String>): ImportOutcome? =
        withContext(Dispatchers.Default) {
            plan.apply(
                chosen,
                AppImportStores(context),
                newId = { UUID.randomUUID().toString() },
                now = System.currentTimeMillis(),
                untitled = untitled,
                reserved = reserved,
                log = { Log.i(LOG_TAG, it) },
                queueName = queueName,
            )
        }

    suspend fun undo(context: Context, undo: ImportUndo): Boolean = withContext(Dispatchers.Default) {
        ImportPlan.undo(undo, AppImportStores(context)) { Log.i(LOG_TAG, it) }
    }

    private fun documents(context: Context): ImportDocuments {
        val log: (String) -> Unit = { Log.w(LOG_TAG, it) }
        return ImportDocuments(context.contentResolver, MusicoletSqlite(context.cacheDir, log), log)
    }

    private fun logged(pick: ImportPick) {
        val kinds = pick.failed.groupingBy { it.why }.eachCount().entries.joinToString { "${it.value} ${it.key.name.lowercase()}" }
        Log.i(LOG_TAG, "import: ${pick.read} files read, ${pick.bundle.playlists.size} playlists, ${pick.bundle.size} songs; not read: ${kinds.ifEmpty { "none" }}")
    }
}
