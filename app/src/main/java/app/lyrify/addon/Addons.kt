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
import android.os.PatternMatcher
import android.os.RemoteException
import android.util.Log
import androidx.core.content.ContextCompat
import app.lyrify.BuildConfig
import app.lyrify.LOG_TAG
import app.lyrify.addon.shared.AddonTrust
import app.lyrify.addon.shared.PermissionDefiner
import app.lyrify.addon.shared.TrustPort
import app.lyrify.ai.api.AddonNames
import app.lyrify.dl.api.DlNames
import app.lyrify.models.ModelSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The APK of an add-on that this KJB pins (app/build.gradle.kts): `<stem>-<version>.apk` on KJB's GitHub release, its
 * SHA-256 (hex) and size. Kept in KJB's cache under a name of its pin, so a file an earlier KJB downloaded is never taken
 * for this one.
 */
data class AddonApk(val stem: String, val url: String, val sha256: String, val bytes: Long) {
    /** This build pins a file: a whole SHA-256 in hex and a size (the release values may still be placeholders). */
    val pinned: Boolean get() = sha256.length == SHA256_HEX && sha256.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' } && bytes > 0

    /** The download, checked against the pin by the verified downloader (models/ModelDownloader). */
    val spec: ModelSpec get() = sha256.lowercase().let { sha -> ModelSpec("$stem-${sha.take(PIN_IN_NAME)}.apk", url, sha, bytes) }

    private companion object {
        const val PIN_IN_NAME = 16
        const val SHA256_HEX = 64
    }
}

/**
 * One of KJB's add-ons, as KJB gets it, checks it and binds it: its package and pinned certificate (this build type's:
 * app.lyrify.ai.debug beside KJB dev), its bound service and the bind permission it alone defines, its APK on the release,
 * and [folder], where KJB's cache keeps the APK while it is got. [label] names it in logcat. [exemption]: its activity
 * that asks to be left out of the phone's battery optimisation (AddonExemptions).
 */
data class KjbAddon(
    val label: String,
    val packageName: String,
    val certSha256: String,
    val service: String,
    val permission: String,
    val apk: AddonApk,
    val folder: String,
    val exemption: String,
)

/** KJB's add-ons, each signed with KJB's key: the AI (lyrify-bench handoff/ai-addon-design.md) and Downloads (downloads-addon-design.md). */
object KjbAddons {
    val AI: KjbAddon = KjbAddon(
        label = "AI add-on",
        packageName = BuildConfig.AI_ADDON_PACKAGE,
        certSha256 = BuildConfig.AI_CERT_SHA256,
        service = AddonNames.SERVICE,
        permission = AddonNames.permission(BuildConfig.AI_ADDON_PACKAGE),
        apk = AddonApk("KJB-AI", BuildConfig.AI_ADDON_URL, BuildConfig.AI_ADDON_SHA256, BuildConfig.AI_ADDON_BYTES),
        folder = "ai-addon",
        exemption = AddonNames.EXEMPTION,
    )

    val DOWNLOADS: KjbAddon = KjbAddon(
        label = "Downloads add-on",
        packageName = BuildConfig.DL_ADDON_PACKAGE,
        certSha256 = BuildConfig.DL_CERT_SHA256,
        service = DlNames.SERVICE,
        permission = DlNames.permission(BuildConfig.DL_ADDON_PACKAGE),
        apk = AddonApk("KJB-Downloads", BuildConfig.DL_ADDON_URL, BuildConfig.DL_ADDON_SHA256, BuildConfig.DL_ADDON_BYTES),
        folder = "dl-addon",
        exemption = DlNames.EXEMPTION,
    )

    /** Every add-on, in the order KJB lists them (the update sheet). */
    val ALL: List<KjbAddon> = listOf(AI, DOWNLOADS)
}

/**
 * How KJB binds an add-on: created for the bind, and at KJB's own priority (BIND_IMPORTANT: without it Android lifts a
 * bound process no higher than visible, even while KJB is in the foreground). No flag that would lower it. A phone's app
 * freezer goes by the add-on's own state, which no bind flag changes: the AI add-on's jobs run in its own foreground
 * service for that (AddonSession.foreground).
 */
internal const val ADDON_BIND_FLAGS: Int = Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT

/** One bind of an add-on's service: its [binder] while it lives; [death] completes when its process dies. [close] unbinds. */
internal class BoundAddon(val binder: IBinder, val death: Deferred<Unit>, private val unbind: () -> Unit) : AutoCloseable {
    override fun close() = unbind()
}

/**
 * An add-on through Android, the same way for each: its package, whether it is KJB's ([AddonTrust]), and the bind of its
 * service by explicit name. KJB's manifest asks to see the package (<queries>); without that it would look not installed.
 */
internal class AndroidAddon(private val context: Context, private val addon: KjbAddon) {
    private val packages: PackageManager get() = context.packageManager

    private val trust = AddonTrust(
        addon.packageName,
        addon.permission,
        addon.certSha256,
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

    fun installed(): Boolean = try {
        packages.getPackageInfo(addon.packageName, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    fun trusted(): Boolean = try {
        trust.trusted()
    } catch (e: IllegalArgumentException) {
        Log.w(LOG_TAG, "${addon.label}: certificate check failed (${e.javaClass.simpleName})")
        false
    }

    /** Binds the add-on's service; null when Android refuses or it does not connect within [CONNECT_TIMEOUT_MS]. */
    suspend fun bind(): BoundAddon? {
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
            context.bindService(service(), connection, ADDON_BIND_FLAGS)
        } catch (e: SecurityException) {
            // Not holding its signature permission: another key, or the add-on was installed in a way Android did not grant it.
            Log.w(LOG_TAG, "${addon.label}: bind refused (${e.javaClass.simpleName})")
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
        return BoundAddon(binder, death) { unbind() }
    }

    /**
     * Starts the add-on's service for the foreground (Context.startForegroundService on its explicit name, [extras]
     * added): Android allows it on KJB's own foreground (its screen, or a foreground service of its own that is not a
     * short one), and the add-on must then go into the foreground within seconds. False when Android refuses.
     */
    fun startForeground(extras: (Intent) -> Unit): Boolean = try {
        context.startForegroundService(service().also(extras)) != null
    } catch (e: IllegalStateException) {
        // ForegroundServiceStartNotAllowedException is one (Android 12+).
        Log.w(LOG_TAG, "${addon.label}: foreground start refused (${e.javaClass.simpleName})")
        false
    } catch (e: SecurityException) {
        Log.w(LOG_TAG, "${addon.label}: foreground start refused (${e.javaClass.simpleName})")
        false
    }

    /** Stops the add-on's service KJB started ([startForeground]); quiet when it does not run. */
    fun stopService() {
        try {
            context.stopService(service())
        } catch (e: RuntimeException) {
            Log.w(LOG_TAG, "${addon.label}: stop refused (${e.javaClass.simpleName})")
        }
    }

    private fun service(): Intent = Intent().setComponent(ComponentName(addon.packageName, addon.service))

    private companion object {
        const val CONNECT_TIMEOUT_MS = 30_000L
    }
}

/** A signal each time [packageName] is installed, updated, changed or removed. */
internal fun packageChanges(context: Context, packageName: String): Flow<Unit> = callbackFlow {
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
        addDataSchemeSpecificPart(packageName, PatternMatcher.PATTERN_LITERAL)
    }
    // System broadcasts reach a receiver that is not exported; KJB's <queries> makes the add-ons visible.
    ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    awaitClose { context.unregisterReceiver(receiver) }
}
