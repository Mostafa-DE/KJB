package app.lyrify.addon

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import app.lyrify.BuildConfig
import app.lyrify.LOG_TAG
import app.lyrify.USER_AGENT
import app.lyrify.ai.api.AddonNames
import app.lyrify.logFailures
import app.lyrify.models.AllowedHosts
import app.lyrify.models.Download
import app.lyrify.models.ModelDownloader
import app.lyrify.models.ModelHosts
import app.lyrify.models.ModelSpec
import app.lyrify.models.ModelStore
import app.lyrify.overnight.AiLyrics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Where getting the AI add-on stands (Get AI, Update, Try again). */
sealed interface InstallState {
    data object Idle : InstallState

    data class Downloading(val doneBytes: Long, val totalBytes: Long) : InstallState

    /** The phone does not let KJB install apps yet: the user turns on "Install unknown apps" for KJB, once. */
    data object NeedsPermission : InstallState

    /** Waiting for the user to confirm in Android's install dialog. */
    data object Installing : InstallState

    data class Failed(val reason: InstallFailure) : InstallState
}

enum class InstallFailure {
    /** This build pins no add-on to download (the release value is still a placeholder). */
    NOT_PUBLISHED,

    /** The download stopped (the network, a busy server, no room yet); trying again goes on from where it stopped. */
    NETWORK,

    /** The file is gone from the release, or is not the pinned one (its size or SHA-256): deleted. */
    DOWNLOAD,

    /** The file is not KJB's add-on (its package or signing certificate): deleted, never installed. */
    NOT_KJBS,

    /** Android's installer did not install it. */
    INSTALL,
}

/** What Android's installer said. */
sealed interface InstallOutcome {
    data object Installed : InstallOutcome

    /** The user said no in the dialog. */
    data object Aborted : InstallOutcome

    /** A PackageInstaller status. */
    data class Failed(val status: Int) : InstallOutcome
}

/** What getting the add-on needs from the phone. */
interface InstallPort {
    /** Downloads the pinned APK unless it is on the phone already, going on from a part; [progress] hears the bytes. */
    suspend fun download(progress: suspend (Long, Long) -> Unit): Download

    /** The downloaded APK is KJB's add-on: its package and signing certificate. */
    fun verified(): Boolean

    /** Deletes the downloaded APK and its part. */
    fun discard()

    /** Android lets KJB install apps ("Install unknown apps" on for KJB). */
    fun canInstall(): Boolean

    /** Installs the downloaded APK and waits for Android's installer; cancelled, the install is abandoned. */
    suspend fun install(): InstallOutcome
}

/**
 * Get AI, Update, Try again: download, check, install, one at a time, in [scope]; [pinned]: this build pins an add-on.
 * The APK is checked twice before Android sees it: the download against the pinned size and SHA-256, then the file's
 * package and signing certificate. What fails a check is deleted; a download that stops keeps its part.
 */
class AddonInstaller(private val port: InstallPort, private val pinned: Boolean, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow<InstallState>(InstallState.Idle)
    val state: StateFlow<InstallState> = _state.asStateFlow()

    private var work: Job? = null

    /** Starts getting the add-on, unless that is under way. */
    @Synchronized
    fun start() {
        if (work?.isActive == true) return
        work = scope.launch { run() }
    }

    /** Back from the phone's settings: goes on to the install once KJB may install apps. */
    fun resume() {
        if (state.value == InstallState.NeedsPermission && port.canInstall()) start()
    }

    /** Cancel: stops the download (what it got stays) or abandons the install; back to the start. */
    @Synchronized
    fun cancel() {
        work?.cancel()
        work = null
        _state.value = InstallState.Idle
    }

    private suspend fun run() {
        try {
            _state.value = next()
        } catch (e: CancellationException) {
            _state.value = InstallState.Idle
            throw e
        }
    }

    /** The whole way, to where it stops: the state it ends in. */
    private suspend fun next(): InstallState {
        if (!pinned) return InstallState.Failed(InstallFailure.NOT_PUBLISHED)
        _state.value = InstallState.Downloading(0, 0)
        when (port.download { done, total -> _state.value = InstallState.Downloading(done, total) }) {
            Download.Done -> Unit
            is Download.Interrupted -> return InstallState.Failed(InstallFailure.NETWORK)
            // The downloader deleted a file that was not the pinned one.
            is Download.Failed -> return InstallState.Failed(InstallFailure.DOWNLOAD)
        }
        if (!port.verified()) {
            port.discard()
            return InstallState.Failed(InstallFailure.NOT_KJBS)
        }
        if (!port.canInstall()) return InstallState.NeedsPermission
        _state.value = InstallState.Installing
        return when (port.install()) {
            InstallOutcome.Installed -> {
                port.discard()
                InstallState.Idle
            }
            // The user said no: the file stays for the next try.
            InstallOutcome.Aborted -> InstallState.Idle
            is InstallOutcome.Failed -> InstallState.Failed(InstallFailure.INSTALL)
        }
    }
}

/**
 * Getting the add-on on this phone, process-wide: one [AddonInstaller] over Android ([AndroidInstallPort]), for the
 * release this KJB pins ([BuildConfig.AI_ADDON_URL], its SHA-256 and size). A build whose pin is still the placeholder
 * downloads nothing.
 */
object AddonInstalls {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + logFailures)

    @Volatile
    private var installer: AddonInstaller? = null

    fun installer(context: Context): AddonInstaller = installer ?: synchronized(this) {
        installer ?: AddonInstaller(AndroidInstallPort(context.applicationContext), pinned = AddonRelease.pinned, scope).also { installer = it }
    }

    /** The phone's "Install unknown apps" switch for KJB. */
    fun installSettings(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.fromParts("package", context.packageName, null))

    /** The add-on's app info, where one that is not KJB's can be uninstalled. */
    fun addonInfo(): Intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", BuildConfig.AI_ADDON_PACKAGE, null))
}

/**
 * The add-on's APK this KJB pins: KJB-AI-<version>.apk on the GitHub release, its SHA-256 and size (app/build.gradle.kts).
 * Kept in KJB's cache under a name of its pin, so a file an earlier KJB downloaded is never taken for this one.
 */
private object AddonRelease {
    const val APK: String = "KJB-AI.apk"

    val pinned: Boolean get() = BuildConfig.AI_ADDON_SHA256.length == SHA256_HEX && BuildConfig.AI_ADDON_BYTES > 0

    val spec: ModelSpec
        get() = BuildConfig.AI_ADDON_SHA256.lowercase().let { sha ->
            ModelSpec("KJB-AI-${sha.take(PIN_IN_NAME)}.apk", BuildConfig.AI_ADDON_URL, sha, BuildConfig.AI_ADDON_BYTES)
        }

    private const val PIN_IN_NAME = 16

    private const val SHA256_HEX = 64
}

/**
 * GitHub's release downloads only, over https on the default port: github.com, which redirects to its asset host
 * (ModelStore's [AllowedHosts] for the same two).
 */
private object GitHubReleases : ModelHosts {
    override fun isSource(uri: URI): Boolean = AllowedHosts.isSource(uri) && uri.host.equals(AllowedHosts.GITHUB, ignoreCase = true)

    override fun isRedirect(uri: URI): Boolean =
        AllowedHosts.isRedirect(uri) && uri.host?.lowercase() in setOf(AllowedHosts.GITHUB, AllowedHosts.GITHUB_ASSETS)
}

/**
 * The add-on's APK through Android: downloaded into KJB's cache with the models' downloader ([ModelDownloader]: the pinned
 * size and SHA-256, resumed with HTTP Range, GitHub only), checked for the add-on's package and KJB's certificate, and
 * installed with a PackageInstaller session. The result comes back to [InstallReceiver].
 */
internal class AndroidInstallPort(private val context: Context) : InstallPort {
    private val folder = File(context.cacheDir, FOLDER)
    private val store get() = ModelStore(folder, listOf(AddonRelease.spec), folder)
    private val apk: File get() = store.file(AddonRelease.spec)

    override suspend fun download(progress: suspend (Long, Long) -> Unit): Download {
        // What an earlier KJB downloaded for another pin goes first.
        val keep = setOf(apk.name, store.part(AddonRelease.spec).name)
        withContext(Dispatchers.IO) { folder.listFiles()?.filter { it.name !in keep }?.forEach { it.delete() } }
        return ModelDownloader(store, USER_AGENT, hosts = GitHubReleases, log = { Log.d(LOG_TAG, "AI add-on: $it") }).downloadAll(progress)
    }

    override fun verified(): Boolean {
        val pin = AddonNames.pinBytes(BuildConfig.AI_CERT_SHA256) ?: return false
        val packages = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packages.getPackageArchiveInfo(apk.path, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        } else {
            @Suppress("DEPRECATION")
            packages.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES)
        } ?: return false
        val signers = info.signingInfo?.apkContentsSigners ?: return false
        return info.packageName == BuildConfig.AI_ADDON_PACKAGE &&
            signers.any { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).contentEquals(pin) }
    }

    override fun discard() {
        if (!store.delete()) Log.w(LOG_TAG, "AI add-on: the downloaded file could not be deleted")
    }

    override fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    override suspend fun install(): InstallOutcome = withContext(Dispatchers.IO) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(BuildConfig.AI_ADDON_PACKAGE)
            setSize(apk.length())
            // An update of the add-on KJB installed may go through without asking (Android 12+, with
            // UPDATE_PACKAGES_WITHOUT_USER_ACTION); the first install always asks, and so may any other.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val session = try {
            installer.createSession(params)
        } catch (e: IOException) {
            Log.w(LOG_TAG, "AI add-on: no install session (${e.javaClass.simpleName})")
            return@withContext InstallOutcome.Failed(PackageInstaller.STATUS_FAILURE)
        }
        val outcome = InstallReceiver.expect(session)
        try {
            installer.openSession(session).use { open ->
                open.openWrite(AddonRelease.APK, 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    open.fsync(out)
                }
                val status = Intent(context, InstallReceiver::class.java).putExtra(InstallReceiver.EXTRA_SESSION, session)
                // Mutable: the installer adds its status. Explicit, to KJB's own receiver, which is not exported.
                val pending = PendingIntent.getBroadcast(context, session, status, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                open.commit(pending.intentSender)
            }
            outcome.await()
        } catch (e: CancellationException) {
            runCatching { installer.abandonSession(session) }
            throw e
        } catch (e: IOException) {
            Log.w(LOG_TAG, "AI add-on: install not written (${e.javaClass.simpleName})")
            runCatching { installer.abandonSession(session) }
            InstallOutcome.Failed(PackageInstaller.STATUS_FAILURE)
        } catch (e: SecurityException) {
            Log.w(LOG_TAG, "AI add-on: install refused (${e.javaClass.simpleName})")
            runCatching { installer.abandonSession(session) }
            InstallOutcome.Failed(PackageInstaller.STATUS_FAILURE_BLOCKED)
        } finally {
            InstallReceiver.forget(session)
        }
    }

    private companion object {
        const val FOLDER = "ai-addon"
    }
}

/**
 * Android's installer's word on an add-on install (through [AndroidInstallPort]'s pending intent; not exported, so only
 * that reaches it): it asks the user (its dialog is started from here), or it is done, refused or failed.
 */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val session = intent.getIntExtra(EXTRA_SESSION, -1)
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                // KJB is in front (the user just tapped Get AI), so Android lets it show its own dialog.
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let(context::startActivity)
            }
            PackageInstaller.STATUS_SUCCESS -> {
                outcomes.remove(session)?.complete(InstallOutcome.Installed)
                AiLyrics.addonInstalled(context)
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> outcomes.remove(session)?.complete(InstallOutcome.Aborted)
            else -> {
                Log.w(LOG_TAG, "AI add-on: not installed, status $status")
                outcomes.remove(session)?.complete(InstallOutcome.Failed(status))
            }
        }
    }

    companion object {
        const val EXTRA_SESSION: String = "app.lyrify.addon.session"

        /** The installs waiting for the installer's word, by session. */
        private val outcomes = ConcurrentHashMap<Int, CompletableDeferred<InstallOutcome>>()

        internal fun expect(session: Int): CompletableDeferred<InstallOutcome> = CompletableDeferred<InstallOutcome>().also { outcomes[session] = it }

        internal fun forget(session: Int) {
            outcomes.remove(session)
        }
    }
}
