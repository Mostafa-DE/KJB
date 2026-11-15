package app.lyrify.downloads

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import app.lyrify.addon.AddonDiedException
import app.lyrify.addon.AddonState
import app.lyrify.addon.AndroidAddon
import app.lyrify.addon.BoundAddon
import app.lyrify.addon.KjbAddons
import app.lyrify.addon.packageChanges
import app.lyrify.addon.shared.AddonKeepAlive
import app.lyrify.dl.api.DlEnd
import app.lyrify.dl.api.DlFailure
import app.lyrify.dl.api.DlFile
import app.lyrify.dl.api.DlInfo
import app.lyrify.dl.api.DlProgress
import app.lyrify.dl.api.DlRequest
import app.lyrify.dl.api.DlStatus
import app.lyrify.dl.api.DlUpdate
import app.lyrify.dl.api.DlWire
import app.lyrify.dl.api.IDlAddon
import app.lyrify.dl.api.IDlInfoCallback
import app.lyrify.dl.api.IDlRunCallback
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart
import java.io.File
import java.io.IOException

/** The lowest Downloads add-on API (IDlAddon.VERSION) this KJB speaks; an add-on that answers less needs an update. */
const val MIN_DL_API: Int = 1

/** One download run as the add-on tells it (IDlRunCallback, as Kotlin). Called on binder threads. */
interface DlRunEvents {
    fun onProgress(progress: DlProgress)

    fun onFile(file: DlFile)

    fun onEnd(end: DlEnd)
}

/** One lookup as the add-on tells it (IDlInfoCallback, as Kotlin). Called on binder threads. */
interface DlLookupEvents {
    fun onInfo(info: DlInfo)

    fun onFailed(end: DlEnd.Failed)
}

/**
 * The Downloads add-on's calls (IDlAddon), as Kotlin: each throws [AddonDiedException] once its process is gone, and
 * SecurityException when it refuses KJB.
 */
interface DlPort {
    fun apiVersion(): Int

    fun status(): DlStatus?

    fun info(url: String, events: DlLookupEvents): Long

    fun cancelInfo(lookup: Long)

    fun start(request: DlRequest, events: DlRunEvents): Long

    /** Opens [media] and [cover] here for writing and hands them to run [run]; IOException when they cannot be opened. */
    @Throws(IOException::class)
    fun deliver(run: Long, media: File, cover: File?)

    fun cancel(run: Long, keepParts: Boolean)

    fun discard(key: String)

    /** Blocks for the update: off the main thread. */
    fun update(): DlUpdate
}

/** A bound Downloads add-on after the handshake, until [close] unbinds it; [death] completes when its process dies. */
class DlSession internal constructor(private val bound: BoundAddon, val port: DlPort, private val startForeground: () -> Boolean) : AutoCloseable {
    val death: Deferred<Unit> get() = bound.death

    /**
     * Has the add-on work in its own foreground service, which a phone's app freezer leaves alone (a service KJB binds
     * counts for nothing there), from the link it took to the file it hands back: KJB starts it right after a lookup or a
     * run started, and KJB's own foreground (its screen, or its downloads service) is what lets it. False when the phone
     * refuses; the add-on then works without it.
     */
    fun foreground(): Boolean = startForeground()

    override fun close() = bound.close()
}

/** What [DlAddon.connect] got: a [session], or why there is none (the AI add-on's states, the same for both). */
sealed interface DlConnection {
    data class Open(val session: DlSession) : DlConnection

    data class Closed(val state: AddonState) : DlConnection
}

/**
 * KJB's Downloads add-on on this phone ([KjbAddons.DOWNLOADS]: app.lyrify.dl, or app.lyrify.dl.debug beside KJB dev): the
 * same checks before a bind as the AI add-on's ([AndroidAddon]: installed, signed with KJB's key, its own bind
 * permission), the bind, and the handshake.
 */
object DlAddon {
    private fun android(context: Context) = AndroidAddon(context.applicationContext, KjbAddons.DOWNLOADS)

    /** Without a bind: missing or not KJB's; null when only a bind tells more. */
    fun peek(context: Context): AddonState? {
        val addon = android(context)
        return when {
            !addon.installed() -> AddonState.Missing
            !addon.trusted() -> AddonState.Untrusted
            else -> null
        }
    }

    /** Checks the add-on, binds it and asks its API: a session for one that speaks at least [MIN_DL_API], else why not. */
    suspend fun connect(context: Context): DlConnection {
        peek(context)?.let { return DlConnection.Closed(it) }
        val android = android(context)
        val bound = android.bind() ?: return DlConnection.Closed(AddonState.Unreachable)
        val port = AidlDlPort(IDlAddon.Stub.asInterface(bound.binder))
        val api = try {
            port.apiVersion()
        } catch (_: AddonDiedException) {
            bound.close()
            return DlConnection.Closed(AddonState.Unreachable)
        } catch (_: SecurityException) {
            bound.close()
            return DlConnection.Closed(AddonState.Untrusted)
        }
        if (api < MIN_DL_API) {
            bound.close()
            return DlConnection.Closed(AddonState.Outdated(api))
        }
        // KJB's Downloads page as the tap on the add-on's notice, which also tells the add-on the start is KJB's.
        return DlConnection.Open(DlSession(bound, port) { android.startForeground { it.putExtra(AddonKeepAlive.EXTRA_TAP, DownloadNotices.page(context.applicationContext)) } })
    }

    /** [block] on the add-on, bound for the call and let go after it; null when it is not ready or died meanwhile. */
    suspend fun <T> withPort(context: Context, block: suspend (DlPort) -> T): T? {
        val session = (connect(context) as? DlConnection.Open)?.session ?: return null
        return session.use {
            try {
                block(it.port)
            } catch (_: AddonDiedException) {
                null
            }
        }
    }

    /** The add-on's state for the Downloads page: now, and again each time its package is installed, updated or removed. */
    fun states(context: Context): Flow<AddonState> = flow {
        packageChanges(context.applicationContext, KjbAddons.DOWNLOADS.packageName).onStart { emit(Unit) }.collect { emit(state(context)) }
    }

    /** Ready only once a bind and the handshake worked; the bind is let go at once. */
    private suspend fun state(context: Context): AddonState = when (val connection = connect(context)) {
        is DlConnection.Open -> AddonState.Ready.also { connection.session.close() }
        is DlConnection.Closed -> connection.state
    }
}

/** [DlPort] over the add-on's binder: JSON in and out ([DlWire]), and DeadObjectException as [AddonDiedException]. */
private class AidlDlPort(private val addon: IDlAddon) : DlPort {
    override fun apiVersion(): Int = call { addon.apiVersion() }

    override fun status(): DlStatus? = call { DlWire.status(addon.status()) }

    override fun info(url: String, events: DlLookupEvents): Long {
        val callback = object : IDlInfoCallback.Stub() {
            override fun onInfo(lookup: Long, info: String) {
                DlWire.info(info)?.let(events::onInfo)
            }

            override fun onFailed(lookup: Long, end: String) {
                events.onFailed(DlWire.end(end) as? DlEnd.Failed ?: DlEnd.Failed(DlFailure.UNKNOWN))
            }
        }
        return call { addon.info(url, callback) }
    }

    override fun cancelInfo(lookup: Long) = call { addon.cancelInfo(lookup) }

    override fun start(request: DlRequest, events: DlRunEvents): Long {
        val callback = object : IDlRunCallback.Stub() {
            override fun onProgress(run: Long, progress: String) {
                DlWire.progress(progress)?.let(events::onProgress)
            }

            override fun onFile(run: Long, file: String) {
                // A file KJB cannot read cannot be saved: the run ends as failed when its hand-off never comes.
                DlWire.file(file)?.let(events::onFile)
            }

            override fun onEnd(run: Long, end: String) = events.onEnd(DlWire.end(end))
        }
        return call { addon.start(DlWire.encode(request), callback) }
    }

    override fun deliver(run: Long, media: File, cover: File?) {
        val mode = ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
        // Binder hands the add-on its own descriptors during the call: KJB's are closed right after.
        ParcelFileDescriptor.open(media, mode).use { out ->
            val art = cover?.let { ParcelFileDescriptor.open(it, mode) }
            try {
                call { addon.deliver(run, out, art) }
            } finally {
                art?.close()
            }
        }
    }

    override fun cancel(run: Long, keepParts: Boolean) = call { addon.cancel(run, keepParts) }

    override fun discard(key: String) = call { addon.discard(key) }

    override fun update(): DlUpdate = call { DlWire.update(addon.update()) }

    private inline fun <T> call(block: () -> T): T = try {
        block()
    } catch (e: RemoteException) {
        // DeadObjectException is one: the add-on's process is gone. A call the add-on refuses is a SecurityException.
        throw AddonDiedException(e)
    }
}
