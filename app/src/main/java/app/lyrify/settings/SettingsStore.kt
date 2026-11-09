package app.lyrify.settings

import android.content.Context
import android.util.Log
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import app.lyrify.LOG_TAG
import app.lyrify.data.writeWithRetries
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardOpenOption

/** Where the settings are kept: a DataStore file in the app ([SettingsRepository]), in memory in tests. */
interface SettingsStore {
    /** The settings now and after every change; the defaults while the file cannot be read. */
    val settings: Flow<Settings>

    /** Stores [change] applied to the settings; false when it could not be written, and the settings stay as they were. */
    suspend fun update(change: (Settings) -> Settings): Boolean
}

/**
 * Reads and writes `settings.json`. A field this version cannot use (unknown, of another type, out of range) reads as
 * its default and the rest as stored ([SettingsJson.decode]), so only a file that is not settings at all is corrupt: it
 * is first copied to [backupDir] as `settings-corrupt-<epochMillis>.json` (the newest copy only), then reported, so the
 * store starts over from the defaults without losing it. When the copy cannot be made, the read fails with a plain
 * IOException instead and the file stays as it is. DataStore reads a corrupt file twice before it starts over: the
 * second read finds the copy of the first. The log gets the copy's name and size only.
 */
internal class SettingsSerializer(private val backupDir: File, private val clock: () -> Long, private val log: (String) -> Unit) : Serializer<Settings> {
    override val defaultValue: Settings = Settings()

    override suspend fun readFrom(input: InputStream): Settings {
        val bytes = input.readBytes()
        return SettingsJson.decode(bytes.decodeToString()) ?: throw CorruptionException("Unreadable settings, kept as ${keep(bytes)}")
    }

    override suspend fun writeTo(t: Settings, output: OutputStream) {
        output.write(SettingsJson.encode(t).encodeToByteArray())
    }

    /** The name of the copy of [bytes] in [backupDir]: the one there when it holds them, else a new one replacing it. */
    private fun keep(bytes: ByteArray): String {
        val copies = backupDir.listFiles().orEmpty().filter { it.name.startsWith(BACKUP_PREFIX) && it.name.endsWith(BACKUP_SUFFIX) }
        copies.firstOrNull { it.length() == bytes.size.toLong() && it.readBytes().contentEquals(bytes) }?.let { return it.name }
        val copy = File(backupDir, "$BACKUP_PREFIX${clock()}$BACKUP_SUFFIX")
        // On storage before the store starts over.
        Files.write(copy.toPath(), bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.SYNC)
        log("settings unreadable: kept as ${copy.name} (${bytes.size} bytes), starting from the defaults")
        copies.forEach { old ->
            try {
                Files.deleteIfExists(old.toPath())
            } catch (e: IOException) {
                log("settings: old copy ${old.name} not removed: ${e.javaClass.simpleName}")
            }
        }
        return copy.name
    }

    private companion object {
        const val BACKUP_PREFIX: String = "settings-corrupt-"
        const val BACKUP_SUFFIX: String = ".json"
    }
}

/** The settings' DataStore at [file]: one per file and process. It starts over only once [SettingsSerializer] kept a copy in [backupDir]. */
internal fun settingsStore(file: File, backupDir: File, scope: CoroutineScope, clock: () -> Long, log: (String) -> Unit): DataStore<Settings> =
    DataStoreFactory.create(
        serializer = SettingsSerializer(backupDir, clock, log),
        corruptionHandler = ReplaceFileCorruptionHandler { Settings() },
        scope = scope,
        produceFile = { file },
    )

/** The settings as [data] gives them, or the defaults when the file cannot be read (storage fails): the app keeps working. */
internal fun readableSettings(data: Flow<Settings>, log: (String) -> Unit): Flow<Settings> = data.catch { e ->
    if (e !is IOException) throw e
    log("settings unreadable: ${e.javaClass.simpleName}, using the defaults")
    emit(Settings())
}

/**
 * The app's settings: app-private `files/datastore/settings.json`, copies of an unreadable one in `files`; never backed
 * up by the phone (allowBackup is off; Lyrify's own backup holds them from 3.0). One store per process.
 */
private object SettingsFile {
    private var store: DataStore<Settings>? = null

    @Synchronized
    fun store(context: Context): DataStore<Settings> = store ?: settingsStore(
        file = context.applicationContext.dataStoreFile("settings.json"),
        backupDir = context.applicationContext.filesDir,
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
        clock = System::currentTimeMillis,
        log = { Log.w(LOG_TAG, it) },
    ).also { store = it }
}

/** The settings in a typed DataStore; one file per process however many repositories are made. */
class SettingsRepository(context: Context) : SettingsStore {
    private val store: DataStore<Settings> = SettingsFile.store(context)

    override val settings: Flow<Settings> = readableSettings(store.data) { Log.w(LOG_TAG, it) }

    /** A failed write is retried a few times, then reported. */
    override suspend fun update(change: (Settings) -> Settings): Boolean =
        writeWithRetries(onFailure = { Log.w(LOG_TAG, "settings not written: ${it.javaClass.simpleName}") }) {
            store.updateData(change)
        }
}
