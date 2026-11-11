package app.lyrify.backup

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.Environment
import android.provider.BaseColumns
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.lyrify.BuildConfig
import app.lyrify.LOG_TAG
import app.lyrify.Lyrify
import app.lyrify.audio.EqProfiles
import app.lyrify.audio.EqProfilesStore
import app.lyrify.data.LedgerRepository
import app.lyrify.data.LedgerState
import app.lyrify.data.SharedWrite
import app.lyrify.data.SharedWrites
import app.lyrify.dupes.duplicates
import app.lyrify.library.LibraryGraph
import app.lyrify.library.LibraryState
import app.lyrify.library.PlaylistsState
import app.lyrify.library.search.SearchGraph
import app.lyrify.overnight.AiLyrics
import app.lyrify.player.PlayerExtras
import app.lyrify.player.PlayerGraph
import app.lyrify.player.QueueBook
import app.lyrify.player.Repeat
import app.lyrify.player.SongState
import app.lyrify.settings.BackupSettings
import app.lyrify.settings.Settings
import app.lyrify.stats.LoggedPlay
import app.lyrify.stats.StatsGraph
import app.lyrify.tags.AlbumRecord
import app.lyrify.tags.CoversTagsMemory
import app.lyrify.tags.FileMark
import app.lyrify.tags.NamePair
import app.lyrify.tags.TagsGraph
import app.lyrify.tags.fileMark as markOf
import app.lyrify.tones.SavedTone
import app.lyrify.tones.ToneListStore
import app.lyrify.tones.TonesState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID
import java.util.concurrent.TimeUnit

/** A file in the automatic backup folder: its document id and its name. */
data class FolderFile(val id: String, val name: String)

/** What a run of the automatic backup does. */
enum class AutoStep {
    /** Automatic backup is off, or has no folder. */
    OFF,

    /** The folder cannot be reached (its permission was taken back): nothing is written. */
    FOLDER_GONE,

    /** The phone is not plugged in: the next run looks again. */
    WAIT_FOR_POWER,

    /** The newest backup in the folder is less than a week old. */
    NOT_DUE,

    BACK_UP,
}

/** The automatic backup's rules: weekly while plugged in, into the folder the user picked, the last [KEEP] kept. */
object AutoBackups {
    const val KEEP: Int = 5
    const val EVERY_DAYS: Long = 7

    /** The day in a backup's file name, `KJB backup 2026-11-10.zip` (or a provider's `... (1).zip`); null for any other file. */
    fun dateOf(name: String): LocalDate? = NAME.matchEntire(name)?.let { match ->
        try {
            LocalDate.parse(match.groupValues[1])
        } catch (_: DateTimeParseException) {
            null
        }
    }

    /**
     * What a run on [today] does, with the folder's files [inFolder] (null when it cannot be listed): a backup when the
     * phone is [plugged] in and the newest backup there (one made by hand counts too) is [EVERY_DAYS] days old. A backup
     * dated after today, from a clock that was wrong, counts for nothing.
     */
    fun step(settings: BackupSettings, plugged: Boolean, today: LocalDate, inFolder: List<FolderFile>?): AutoStep {
        if (!settings.automatic || settings.folderUri == null) return AutoStep.OFF
        if (inFolder == null) return AutoStep.FOLDER_GONE
        if (!plugged) return AutoStep.WAIT_FOR_POWER
        val newest = inFolder.mapNotNull { dateOf(it.name) }.filter { !it.isAfter(today) }.maxOrNull()
        return if (newest != null && newest.plusDays(EVERY_DAYS).isAfter(today)) AutoStep.NOT_DUE else AutoStep.BACK_UP
    }

    /**
     * The backups in [files] past the [KEEP] newest, by the day in their names, then a provider's numbered copy (made
     * after the first of that name) before the first; other files are never deleted.
     */
    fun toDelete(files: List<FolderFile>): List<FolderFile> = files.mapNotNull { file -> dateOf(file.name)?.let { Dated(file, it, copyOf(file.name)) } }
        .sortedWith(compareByDescending(Dated::date).thenByDescending(Dated::copy))
        .drop(KEEP)
        .map { it.file }

    private data class Dated(val file: FolderFile, val date: LocalDate, val copy: Int)

    /** A provider's number for a copy of a name already there, `KJB backup 2026-11-10 (1).zip`; 0 for the first. */
    private fun copyOf(name: String): Int = NAME.matchEntire(name)?.groupValues?.get(2)?.toIntOrNull() ?: 0

    private val NAME = Regex("""KJB backup (\d{4}-\d{2}-\d{2})(?: \((\d{1,4})\))?\.zip""")
}

// The app's side: its stores, the system's documents, and the weekly run.

/**
 * The app's stores as a backup and a restore see them, each through its own API (Duplicates and Covers & tags through
 * their engines, so a restore never writes under them). The queues, Repeat and the song state live on the main thread
 * and are read once they are loaded; the rest are read and written where their stores say.
 */
class AppBackupStores(context: Context) : BackupStores {
    private val app = context.applicationContext
    private val ledger = LedgerRepository(app)

    override suspend fun playlists(): PlaylistsState = LibraryGraph.playlists(app).state.first()

    override suspend fun updatePlaylists(change: (PlaylistsState) -> PlaylistsState): Boolean = LibraryGraph.playlists(app).update(change)

    /** The book with the place of the queue playing now folded in, so a backup holds its song and second. */
    override suspend fun queues(): QueueBook = withContext(Dispatchers.Main) {
        val core = PlayerGraph.core(app)
        core.ready.first { it }
        core.edit { it to it } ?: core.book.value
    }

    override suspend fun updateQueues(change: (QueueBook) -> QueueBook): Boolean = withContext(Dispatchers.Main) {
        val core = PlayerGraph.core(app)
        core.ready.first { it }
        core.edit { change(it) to Unit } != null
    }

    override suspend fun plays(): List<LoggedPlay>? = StatsGraph.log(app).all()

    override suspend fun addPlays(plays: List<LoggedPlay>): Boolean = StatsGraph.log(app).putAll(plays)

    override suspend fun songState(): SongState = withContext(Dispatchers.Main) {
        val extras = PlayerExtras.of(app)
        extras.ready.first { it }
        extras.state.value
    }

    override suspend fun updateSongState(change: (SongState) -> SongState): Boolean = withContext(Dispatchers.Main) {
        val extras = PlayerExtras.of(app)
        extras.ready.first { it }
        extras.edit { change(it) to Unit } != null
    }

    override suspend fun equalizer(): EqProfiles = withContext(Dispatchers.IO) { EqProfilesStore.of(app).load() }

    override suspend fun replaceEqualizer(profiles: EqProfiles): Boolean = withContext(Dispatchers.IO) { EqProfilesStore.of(app).save(profiles) }

    /** None while the ledger cannot be read: a backup leaves it out rather than save it empty. */
    override suspend fun ledger(): LedgerState? = ledger.ledger.first().takeIf { it.readable }

    override suspend fun updateLedger(change: (LedgerState) -> LedgerState): Boolean = ledger.update(change)

    override suspend fun settings(): Settings = Lyrify.settings(app).settings.first()

    override suspend fun updateSettings(change: (Settings) -> Settings): Boolean = Lyrify.settings(app).update(change)

    override suspend fun repeat(): Repeat = withContext(Dispatchers.Main) {
        val core = PlayerGraph.core(app)
        core.ready.first { it }
        core.repeat.value
    }

    override suspend fun replaceRepeat(repeat: Repeat): Boolean = withContext(Dispatchers.Main) {
        val core = PlayerGraph.core(app)
        core.ready.first { it }
        core.setRepeat(repeat)
        true
    }

    override suspend fun aiLyrics(): Boolean = AiLyrics.enabled(app).value

    override suspend fun replaceAiLyrics(on: Boolean): Boolean {
        if (AiLyrics.enabled(app).value != on) if (on) AiLyrics.turnOn(app) else AiLyrics.turnOff(app)
        return true
    }

    override suspend fun tones(): TonesState = ToneListStore.of(app).state.first()

    override suspend fun addTones(tones: List<SavedTone>): Boolean {
        val list = ToneListStore.of(app)
        return tones.map { list.put(it) }.all { it }
    }

    override suspend fun findTone(folder: String, fileName: String, size: Long): Long? = withContext(Dispatchers.IO) {
        try {
            val audio = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.SIZE} = ?"
            app.contentResolver.query(audio, arrayOf(BaseColumns._ID), selection, arrayOf(folder, fileName, size.toString()), null)?.use { rows ->
                buildList { while (rows.moveToNext()) add(rows.getLong(0)) }
            }?.singleOrNull()
        } catch (e: Exception) {
            // SecurityException, or the provider's own.
            Log.w(LOG_TAG, "restore: tone not looked up: ${e.javaClass.simpleName}")
            null
        }
    }

    override suspend fun duplicates(): DuplicateChoices = duplicates(app).state.first { it.loaded }.memory.let { DuplicateChoices(it.notDuplicates, it.reviewed) }

    override suspend fun addDuplicates(choices: DuplicateChoices): Boolean = duplicates(app).restoreChoices(choices.notDuplicates, choices.reviewed)

    override suspend fun coversTags(): CoversTagsMemory = TagsGraph.coversTags(app).state.first { it.loaded }.memory

    override suspend fun addSettledAlbums(albums: List<AlbumRecord>, checkedOnce: Boolean, noted: Boolean): Boolean =
        TagsGraph.coversTags(app).restoreSettled(albums, checkedOnce, noted)

    override suspend fun keptApart(): Set<NamePair> = TagsGraph.keptApart(app).load()

    override suspend fun addKeptApart(pairs: Set<NamePair>): Boolean {
        val names = TagsGraph.keptApart(app)
        val there = names.load()
        return (pairs - there).map { names.add(it) }.all { it }
    }

    override suspend fun searches(): List<String> = SearchGraph.recent(app).state.first { it != null }.orEmpty()

    /** Added the oldest first, so the newest ends on top, as typed. */
    override suspend fun addSearches(searches: List<String>): Boolean {
        val recent = SearchGraph.recent(app)
        val there = recent.state.first { it != null }.orEmpty().toSet()
        searches.filterNot { it in there }.asReversed().forEach(recent::add)
        return true
    }

    override suspend fun fileMark(path: String): FileMark? = withContext(Dispatchers.IO) { markOf(path) }
}

/** Making a backup of the app and reading one back. The log gets counts and section names only. */
object Backups {
    /** A backup of every store now, as the zip's bytes. */
    suspend fun make(context: Context): ByteArray = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val snapshot = AppBackupStores(app).snapshot()
        val songs = library(app)?.all.orEmpty()
        val bytes = ByteArrayOutputStream().also { BackupBundle.write(snapshot, songs, BuildConfig.VERSION_NAME, System.currentTimeMillis(), it) }.toByteArray()
        val read = listOfNotNull(snapshot.playlists, snapshot.queues, snapshot.plays, snapshot.songState, snapshot.equalizer, snapshot.ledger, snapshot.settings, snapshot.tones, snapshot.duplicates, snapshot.coversTags, snapshot.searches)
        Log.i(LOG_TAG, "backup: ${bytes.size} bytes, ${read.size} stores read, ${songs.size} songs listed")
        bytes
    }

    /** The backup in [document], picked in the open dialog; unreadable when it cannot be opened at all. */
    suspend fun read(context: Context, document: Uri): BackupRead = withContext(Dispatchers.IO) {
        val read = try {
            context.contentResolver.openInputStream(document)?.let(BackupBundle::read) ?: BackupRead.Unreadable(BackupRead.Why.CORRUPT)
        } catch (e: Exception) {
            // FileNotFoundException, SecurityException, or the provider's own.
            Log.w(LOG_TAG, "restore: file not opened: ${e.javaClass.simpleName}")
            BackupRead.Unreadable(BackupRead.Why.CORRUPT)
        }
        when (read) {
            is BackupRead.Read -> Log.i(LOG_TAG, "restore: read ${read.contents.sections.joinToString { it.key }}; ${read.contents.tooNew.size} too new, ${read.contents.unknown} unknown")
            is BackupRead.Unreadable -> Log.w(LOG_TAG, "restore: unreadable (${read.why.name.lowercase()})")
        }
        read
    }

    /** [contents] matched against the library as it is now (no song is found while it cannot be read). */
    suspend fun plan(context: Context, contents: BackupContents): RestorePlan = withContext(Dispatchers.Default) {
        RestorePlan(contents, library(context.applicationContext)?.all.orEmpty())
    }

    /** Restores the [chosen] sections of [plan] into the app's stores; the queues and the song state on the main thread. */
    suspend fun restore(context: Context, plan: RestorePlan, chosen: Set<Section>): RestoreResult = withContext(Dispatchers.Default) {
        plan.apply(chosen, AppBackupStores(context), newId = { UUID.randomUUID().toString() }) { Log.i(LOG_TAG, it) }
    }

    private suspend fun library(app: Context) = (LibraryGraph.repository(app).state.first { it !is LibraryState.Loading } as? LibraryState.Ready)?.library
}

/**
 * Backups in the places the user picks in the system's dialogs, through the Storage Access Framework: no permission
 * needed, and a persisted one for the automatic backup's folder. Each change asks [writes] first, with the place's path
 * when its provider gives one ([M3u.documentPath]), so a debug build writes and deletes only in its test folder. Only
 * documents KJB made are deleted. The log gets counts and exception names only.
 */
class BackupDocuments(private val resolver: ContentResolver, private val writes: SharedWrites, private val log: (String) -> Unit) {
    /**
     * Whether a backup goes through a folder the user picks (checked before any file exists, then a new document in it)
     * rather than the save dialog, which makes the document before KJB can check it: a debug build, as for m3u exports.
     */
    val byFolder: Boolean get() = writes.testFolderOnly

    /** Writes [bytes] into [document], made in the save dialog; a document left empty is deleted. False when refused or failed. */
    fun write(document: Uri, bytes: ByteArray): Boolean {
        val path = documentPath(document)
        if (!writes.allows(SharedWrite.BACKUP_SAVE, path ?: "")) return false
        if (writeBytes(document, bytes)) return true
        delete(document, path)
        return false
    }

    /** Whether backups may be saved in the folder [tree] (a debug build: only its test folder). */
    fun allowsFolder(tree: Uri, name: String): Boolean = folder(tree)?.let { (_, path) -> writes.allows(SharedWrite.BACKUP_SAVE, path?.let { "$it/$name" } ?: "") } == true

    /** A new document [name] in the folder [tree], holding [bytes]; false when refused or failed, and then no document is left. */
    fun create(tree: Uri, name: String, bytes: ByteArray): Boolean {
        val (folder, path) = folder(tree) ?: return false
        val file = path?.let { "$it/$name" }
        if (!writes.allows(SharedWrite.BACKUP_SAVE, file ?: "")) return false
        val document = try {
            DocumentsContract.createDocument(resolver, folder, BackupBundle.MIME_TYPE, name)
        } catch (e: Exception) {
            // FileNotFoundException, SecurityException, or the provider's own.
            log("backup: document not made: ${e.javaClass.simpleName}")
            null
        } ?: return false
        if (writeBytes(document, bytes)) return true
        delete(document, file)
        return false
    }

    /** The files in the folder [tree]; null when it cannot be listed (its permission was taken back, say). */
    fun list(tree: Uri): List<FolderFile>? = try {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { rows ->
            buildList { while (rows.moveToNext()) if (!rows.isNull(0) && !rows.isNull(1)) add(FolderFile(rows.getString(0), rows.getString(1))) }
        }
    } catch (e: Exception) {
        log("backup: folder not listed: ${e.javaClass.simpleName}")
        null
    }

    /** Deletes [file], a backup in the folder [tree] past the ones kept; false when refused or failed. */
    fun delete(tree: Uri, file: FolderFile): Boolean {
        val path = folder(tree)?.second?.let { "$it/${file.name}" }
        return delete(DocumentsContract.buildDocumentUriUsingTree(tree, file.id), path)
    }

    /** Whether KJB still holds its permission to write in the folder [tree]. */
    fun reachable(tree: String?): Boolean = tree != null && resolver.persistedUriPermissions.any { it.uri.toString() == tree && it.isWritePermission }

    private fun delete(document: Uri, path: String?): Boolean {
        if (!writes.allows(SharedWrite.BACKUP_DELETE, path ?: "")) return false
        return try {
            DocumentsContract.deleteDocument(resolver, document)
        } catch (e: Exception) {
            log("backup: document not deleted: ${e.javaClass.simpleName}")
            false
        }
    }

    private fun writeBytes(document: Uri, bytes: ByteArray): Boolean = try {
        // "wt": a file picked to be replaced loses its old end.
        val output = resolver.openOutputStream(document, "wt") ?: throw IOException("no stream")
        output.use { it.write(bytes) }
        true
    } catch (e: Exception) {
        log("backup: not written: ${e.javaClass.simpleName}")
        false
    }

    /** The folder document of [tree] and its path, when its provider gives one; null when [tree] is no folder. */
    private fun folder(tree: Uri): Pair<Uri, String?>? = try {
        val id = DocumentsContract.getTreeDocumentId(tree)
        DocumentsContract.buildDocumentUriUsingTree(tree, id) to M3u.documentPath(tree.authority, id, primaryRoot())
    } catch (_: IllegalArgumentException) {
        log("backup: not a folder")
        null
    }

    private fun documentPath(document: Uri): String? = try {
        M3u.documentPath(document.authority, DocumentsContract.getDocumentId(document), primaryRoot())
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun primaryRoot(): String = Environment.getExternalStorageDirectory().absolutePath
}

/**
 * Automatic backup's run, looked at every [LOOK_EVERY_HOURS] hours while the battery and storage are not low: once a
 * week while the phone is plugged in ([AutoBackups.step]), a new backup in the folder, then the oldest past [AutoBackups.KEEP]
 * deleted. "Plugged in" is the cable itself (`EXTRA_PLUGGED`), not WorkManager's charging, which Samsung's charge pause
 * makes come and go (overnight/AiWorker.kt). A folder it cannot reach writes nothing; Settings turns the switch off.
 */
class AutoBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext
        val settings = Lyrify.settings(app).settings.first().backup
        val documents = BackupDocuments(app.contentResolver, Lyrify.writes) { Log.w(LOG_TAG, it) }
        val tree = settings.folderUri?.takeIf { settings.automatic }?.toUri()
        val today = LocalDate.now()
        val step = AutoBackups.step(settings, pluggedIn(app), today, tree?.let(documents::list))
        Log.i(LOG_TAG, "auto backup: ${step.name.lowercase()}")
        if (step != AutoStep.BACK_UP || tree == null) return Result.success()
        if (!documents.create(tree, BackupBundle.fileName(today), Backups.make(app))) return Result.success()
        val old = AutoBackups.toDelete(documents.list(tree).orEmpty())
        Log.i(LOG_TAG, "auto backup: saved, ${old.count { documents.delete(tree, it) }} of ${old.size} old ones deleted")
        return Result.success()
    }

    companion object {
        private const val WORK: String = "auto-backup"
        private const val LOOK_EVERY_HOURS: Long = 6

        /** On: the run is looked at from now on. The folder is in the settings already. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(LOOK_EVERY_HOURS, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK)
        }

        /** Whether the phone is plugged in now (any `EXTRA_PLUGGED` source), charging or not; the battery's sticky broadcast. */
        private fun pluggedIn(context: Context): Boolean =
            ContextCompat.registerReceiver(context, null, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
                ?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)?.let { it != 0 } == true
    }
}
