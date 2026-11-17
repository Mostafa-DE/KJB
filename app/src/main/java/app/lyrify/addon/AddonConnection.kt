package app.lyrify.addon

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.util.Log
import app.lyrify.LOG_TAG
import app.lyrify.ai.AiResult
import app.lyrify.addon.shared.AddonKeepAlive
import app.lyrify.ai.api.AddonStatus
import app.lyrify.ai.api.IAiAddon
import app.lyrify.ai.api.IAiDownloadCallback
import app.lyrify.ai.api.IAiRunCallback
import app.lyrify.ai.api.IAiStatusCallback
import app.lyrify.ai.api.ImportReply
import app.lyrify.ai.api.Wire
import app.lyrify.overnight.AiLyrics
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * KJB's AI add-on on this phone, process-wide: the add-on of this build type ([KjbAddons.AI]: app.lyrify.ai, or
 * app.lyrify.ai.debug beside KJB dev), trusted only when signed with the pinned certificate (KJB's own key), and the
 * [AddonClient] over it.
 */
object Addon {
    @Volatile
    private var client: AddonClient? = null

    fun client(context: Context): AddonClient = client ?: synchronized(this) {
        client ?: AddonClient(AndroidAddonHost(context.applicationContext, KjbAddons.AI)).also { client = it }
    }

    /** The add-on for the AI page while collected ([AddonClient.views]), looked at again when it is installed, updated or removed. */
    fun views(context: Context): Flow<AddonView> = client(context).views(packageChanges(context.applicationContext, KjbAddons.AI.packageName))

    /**
     * [block] on the add-on, bound for the call and let go after it: null when it is not there or not ready (missing,
     * untrusted, outdated, unreachable) or when it died meanwhile.
     */
    suspend fun <T> withPort(context: Context, block: suspend (AddonPort) -> T): T? {
        val connection = client(context).connect() as? Connection.Open ?: return null
        return connection.session.use { session ->
            try {
                block(session.port)
            } catch (e: AddonDiedException) {
                Log.w(LOG_TAG, "AI add-on: gone during a call (${e.cause?.javaClass?.simpleName})")
                null
            }
        }
    }
}

/**
 * The AI add-on through Android ([AndroidAddon]): its binder as an [AddonPort], and its service started for the foreground
 * for a job or the models' download with KJB's AI page as the tap on its notice ([AddonKeepAlive.EXTRA_TAP]), which also
 * tells the add-on the start is KJB's.
 */
internal class AndroidAddonHost(private val context: Context, addon: KjbAddon) : AddonHost {
    private val android = AndroidAddon(context, addon)

    override fun installed(): Boolean = android.installed()

    override fun trusted(): Boolean = android.trusted()

    override suspend fun bind(): AddonBinding? {
        val bound = android.bind() ?: return null
        val port = AidlPort(IAiAddon.Stub.asInterface(bound.binder))
        return object : AddonBinding {
            override val port: AddonPort = port
            override val death: Deferred<Unit> = bound.death

            override fun foreground(): Boolean = android.startForeground { it.putExtra(AddonKeepAlive.EXTRA_TAP, AiLyrics.page(context)) }

            override fun stopService() = android.stopService()

            override fun close() = bound.close()
        }
    }
}

/** [AddonPort] over the add-on's binder: JSON in and out ([Wire]), and DeadObjectException as [AddonDiedException]. */
private class AidlPort(private val addon: IAiAddon) : AddonPort {
    override fun apiVersion(): Int = call { addon.apiVersion() }

    override fun status(): AddonStatus? = call { Wire.status(addon.status()) }

    override fun watchStatus(listener: (AddonStatus) -> Unit): AutoCloseable {
        val callback = object : IAiStatusCallback.Stub() {
            override fun onStatus(status: String) {
                Wire.status(status)?.let(listener)
            }
        }
        call { addon.watchStatus(callback) }
        return AutoCloseable { runCatching { addon.unwatchStatus(callback) } }
    }

    override fun download(events: DownloadEvents): Long {
        val callback = object : IAiDownloadCallback.Stub() {
            override fun onProgress(done: Long, total: Long) = events.onProgress(done, total)

            override fun onEnd(end: String) = events.onEnd(Wire.download(end))

            override fun onBeat() = events.onBeat()
        }
        return call { addon.download(callback) }
    }

    override fun cancelDownload(run: Long) = call { addon.cancelDownload(run) }

    override fun abandonDownload(run: Long) = call { addon.abandonDownload(run) }

    override fun deleteModels(): Boolean = call { addon.deleteModels() }

    override fun discardModel(name: String): Boolean = call { addon.discardModel(name) }

    override fun run(song: File, durationMs: Long, plainLyrics: String?, events: RunEvents): Long {
        val callback = object : IAiRunCallback.Stub() {
            override fun onStage(job: Long, stage: String) = events.onStage(stage)

            override fun onNote(job: Long, line: String) = events.onNote(line)

            override fun onChunk(job: Long) = events.onChunk()

            override fun onResult(job: Long, result: String?) = events.onResult(result?.let(Wire::result))

            override fun onStopped(job: Long) = events.onStopped()

            override fun onProgress(job: Long, done: Int, total: Int) = events.onProgress(done, total)

            override fun onBeat(job: Long) = events.onBeat()
        }
        // Binder hands the add-on its own descriptor during the call: KJB's is closed right after.
        return ParcelFileDescriptor.open(song, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            call { addon.run(pfd, durationMs, plainLyrics, callback) }
        }
    }

    override fun proceed(job: Long) = call { addon.proceed(job) }

    override fun cancel(job: Long) = call { addon.cancel(job) }

    override fun result(job: Long): AiResult? = call { Wire.joined { part -> addon.result(job, part) }?.let(Wire::result) }

    override fun closeEngine() = call { addon.closeEngine() }

    override fun importModel(name: String, file: File, sha256: String): ImportReply =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            call { Wire.reply(addon.importModel(name, pfd, file.length(), sha256)) }
        }

    private inline fun <T> call(block: () -> T): T = try {
        block()
    } catch (e: RemoteException) {
        // DeadObjectException is one: the add-on's process is gone. A call the add-on refuses is a SecurityException.
        throw AddonDiedException(e)
    }
}
