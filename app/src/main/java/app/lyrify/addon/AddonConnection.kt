package app.lyrify.addon

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.PatternMatcher
import android.os.RemoteException
import android.util.Log
import androidx.core.content.ContextCompat
import app.lyrify.BuildConfig
import app.lyrify.LOG_TAG
import app.lyrify.ai.api.AddonNames
import app.lyrify.ai.api.AddonStatus
import app.lyrify.ai.api.IAiAddon
import app.lyrify.ai.api.IAiDownloadCallback
import app.lyrify.ai.api.IAiRunCallback
import app.lyrify.ai.api.IAiStatusCallback
import app.lyrify.ai.api.ImportReply
import app.lyrify.ai.api.Wire
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * KJB's AI add-on on this phone, process-wide: the add-on of this build type ([BuildConfig.AI_ADDON_PACKAGE]: app.lyrify.ai,
 * or app.lyrify.ai.debug beside KJB dev), trusted only when signed with the pinned certificate
 * ([BuildConfig.AI_CERT_SHA256], KJB's own key), and the [AddonClient] over it.
 */
object Addon {
    @Volatile
    private var client: AddonClient? = null

    fun client(context: Context): AddonClient = client ?: synchronized(this) {
        client ?: AddonClient(AndroidAddonHost(context.applicationContext, BuildConfig.AI_ADDON_PACKAGE, BuildConfig.AI_CERT_SHA256)).also { client = it }
    }

    /** The add-on for the AI page while collected ([AddonClient.views]), looked at again when it is installed, updated or removed. */
    fun views(context: Context): Flow<AddonView> = client(context).views(packageChanges(context.applicationContext))

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

    /** A signal each time the add-on's package is installed, updated, changed or removed. */
    private fun packageChanges(context: Context): Flow<Unit> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                trySend(Unit)
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
            addDataSchemeSpecificPart(BuildConfig.AI_ADDON_PACKAGE, PatternMatcher.PATTERN_LITERAL)
        }
        // System broadcasts reach a receiver that is not exported; KJB's <queries> makes the add-on's visible.
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        awaitClose { context.unregisterReceiver(receiver) }
    }
}

/** Who defines a permission, and whether at signature level. */
data class PermissionDefiner(val packageName: String, val signature: Boolean)

/** What KJB asks Android about the installed add-on's identity. */
interface TrustPort {
    fun hasCertificate(packageName: String, sha256: ByteArray): Boolean

    /** Signed with the same certificate as KJB (`PackageManager.checkSignatures`). */
    fun signedLikeKjb(packageName: String): Boolean

    /** Who defines [permission]; null when no installed app does. */
    fun definer(permission: String): PermissionDefiner?
}

/**
 * Whether the installed [addonPackage] is KJB's add-on: signed with the pinned certificate ([certSha256], hex) and with
 * KJB's own (both checks, as the research advises: the pin also covers a pair of look-alike apps signed alike), and the
 * definer of its bind permission. Android lets the first app that defines a permission set its level, so another app
 * defining it first could open it to any app; the bind goes through only when the add-on defines it, at signature level.
 */
class AddonTrust(private val addonPackage: String, private val certSha256: String, private val packages: TrustPort) {
    fun trusted(): Boolean {
        val pin = AddonNames.pinBytes(certSha256) ?: return false
        val definer = packages.definer(AddonNames.permission(addonPackage)) ?: return false
        return definer.packageName == addonPackage && definer.signature &&
            packages.hasCertificate(addonPackage, pin) && packages.signedLikeKjb(addonPackage)
    }
}

/**
 * The add-on through Android: its package, whether it is KJB's ([AddonTrust]), and the bind of its service by explicit
 * name. KJB's manifest asks to see the package (<queries>); without that it would look not installed.
 */
internal class AndroidAddonHost(private val context: Context, private val addonPackage: String, private val certSha256: String) : AddonHost {
    private val packages: PackageManager get() = context.packageManager

    private val trust = AddonTrust(
        addonPackage,
        certSha256,
        object : TrustPort {
            override fun hasCertificate(packageName: String, sha256: ByteArray): Boolean =
                packages.hasSigningCertificate(packageName, sha256, PackageManager.CERT_INPUT_SHA256)

            override fun signedLikeKjb(packageName: String): Boolean =
                packages.checkSignatures(context.packageName, packageName) == PackageManager.SIGNATURE_MATCH

            // A definer KJB cannot see (not the add-on, which its <queries> shows) is no definer it trusts.
            override fun definer(permission: String): PermissionDefiner? = try {
                packages.getPermissionInfo(permission, 0).let { PermissionDefiner(it.packageName, it.protection == PermissionInfo.PROTECTION_SIGNATURE) }
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
        },
    )

    override fun installed(): Boolean = try {
        packages.getPackageInfo(addonPackage, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    override fun trusted(): Boolean = try {
        trust.trusted()
    } catch (e: IllegalArgumentException) {
        Log.w(LOG_TAG, "AI add-on: certificate check failed (${e.javaClass.simpleName})")
        false
    }

    override suspend fun bind(): AddonBinding? {
        val connected = CompletableDeferred<IBinder?>()
        val death = CompletableDeferred<Unit>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                connected.complete(service)
            }

            // Its process died; Android may bind it again by itself, but this binding is over: KJB binds anew.
            override fun onServiceDisconnected(name: ComponentName) {
                death.complete(Unit)
            }

            override fun onBindingDied(name: ComponentName) {
                death.complete(Unit)
                connected.complete(null)
            }

            override fun onNullBinding(name: ComponentName) {
                connected.complete(null)
            }
        }
        val unbound = AtomicBoolean(false)
        val unbind = { if (unbound.compareAndSet(false, true)) runCatching { context.unbindService(connection) } }
        val bound = try {
            context.bindService(Intent().setComponent(ComponentName(addonPackage, AddonNames.SERVICE)), connection, Context.BIND_AUTO_CREATE)
        } catch (e: SecurityException) {
            // Not holding its signature permission: another key, or the add-on was installed in a way Android did not grant it.
            Log.w(LOG_TAG, "AI add-on: bind refused (${e.javaClass.simpleName})")
            false
        }
        val binder = try {
            if (bound) withTimeoutOrNull(CONNECT_TIMEOUT_MS) { connected.await() } else null
        } catch (e: CancellationException) {
            unbind()
            throw e
        }
        if (binder == null) {
            // The documentation asks for an unbind even when bindService returned false.
            unbind()
            return null
        }
        try {
            binder.linkToDeath({ death.complete(Unit) }, 0)
        } catch (_: RemoteException) {
            death.complete(Unit)
        }
        val port = AidlPort(IAiAddon.Stub.asInterface(binder))
        return object : AddonBinding {
            override val port: AddonPort = port
            override val death: Deferred<Unit> = death

            override fun close() {
                unbind()
            }
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 30_000L
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
        }
        return call { addon.download(callback) }
    }

    override fun cancelDownload(run: Long) = call { addon.cancelDownload(run) }

    override fun deleteModels(): Boolean = call { addon.deleteModels() }

    override fun discardModel(name: String): Boolean = call { addon.discardModel(name) }

    override fun run(song: File, durationMs: Long, plainLyrics: String?, events: RunEvents): Long {
        val callback = object : IAiRunCallback.Stub() {
            override fun onStage(job: Long, stage: String) = events.onStage(stage)

            override fun onNote(job: Long, line: String) = events.onNote(line)

            override fun onChunk(job: Long) = events.onChunk()

            override fun onResult(job: Long, result: String) = events.onResult(Wire.result(result))

            override fun onStopped(job: Long) = events.onStopped()

            override fun onProgress(job: Long, done: Int, total: Int) = events.onProgress(done, total)
        }
        // Binder hands the add-on its own descriptor during the call: KJB's is closed right after.
        return ParcelFileDescriptor.open(song, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            call { addon.run(pfd, durationMs, plainLyrics, callback) }
        }
    }

    override fun proceed(job: Long) = call { addon.proceed(job) }

    override fun cancel(job: Long) = call { addon.cancel(job) }

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
