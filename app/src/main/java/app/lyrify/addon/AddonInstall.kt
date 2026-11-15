package app.lyrify.addon

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import app.lyrify.LOG_TAG
import app.lyrify.USER_AGENT
import app.lyrify.addon.shared.AddonPins
import app.lyrify.logFailures
import app.lyrify.models.AllowedHosts
import app.lyrify.models.Download
import app.lyrify.models.ModelDownloader
import app.lyrify.models.ModelHosts
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
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Where getting an add-on stands (Get AI or Get Downloads, Update, Try again). */
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
 * What Android's installer reported in a status broadcast, as KJB reads it: [ourSession] is the session KJB put in its
 * own pending intent, [session] the one the installer names (EXTRA_SESSION_ID), [packageName] the package it names, and
 * for STATUS_PENDING_USER_ACTION the confirmation it hands over (its action, package and session).
 */
internal data class InstallReport(
    val ourSession: Int,
    val session: Int,
    val status: Int,
    val packageName: String?,
    val confirmAction: String?,
    val confirmPackage: String?,
    val confirmSession: Int,
) {
    /**
     * Only a session KJB created and still waits on ([waiting]) is heard. A confirmation is started only when it is the
     * installer's own (PackageInstallerSession builds it from CONFIRM_INSTALL, the system installer's package and the
     * session id, nothing else), and it is rebuilt from those three; anything else asking for the user, or a result
     * for another package than [addonPackage], ends the install as failed, so it never waits on a dialog that cannot
     * come.
     */
    fun verdict(waiting: (Int) -> Boolean, addonPackage: String): InstallVerdict {
        if (session < 0 || session != ourSession || !waiting(session)) return InstallVerdict.Ignore
        val failed = InstallVerdict.End(InstallOutcome.Failed(PackageInstaller.STATUS_FAILURE))
        if (packageName != null && packageName != addonPackage) return failed
        return when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION ->
                if (confirmAction == CONFIRM_INSTALL && confirmSession == session && !confirmPackage.isNullOrEmpty()) {
                    InstallVerdict.Confirm(confirmPackage, session)
                } else {
                    failed
                }
            PackageInstaller.STATUS_SUCCESS -> InstallVerdict.End(InstallOutcome.Installed)
            PackageInstaller.STATUS_FAILURE_ABORTED -> InstallVerdict.End(InstallOutcome.Aborted)
            else -> InstallVerdict.End(InstallOutcome.Failed(status))
        }
    }

    companion object {
        /** PackageInstaller.ACTION_CONFIRM_INSTALL, a system API (so not in the SDK), unchanged since Android 5. */
        const val CONFIRM_INSTALL: String = "android.content.pm.action.CONFIRM_INSTALL"
    }
}

/** What KJB does with an installer's report. */
internal sealed interface InstallVerdict {
    data object Ignore : InstallVerdict

    data class Confirm(val installer: String, val session: Int) : InstallVerdict

    data class End(val outcome: InstallOutcome) : InstallVerdict
}

// An APK's identity, read the same way for KJB's own update (update/Updates.kt) and for each add-on

/** An APK as KJB checks it before an install: its package, version code and signing certificates (SHA-256, hex). */
data class ApkSigning(val packageName: String, val versionCode: Long, val signers: Set<String>)

/**
 * The flags that read an APK file's signing certificates ([PackageManager.getPackageArchiveInfo]) on [sdk]. Before
 * Android 13 the deprecated GET_SIGNATURES is asked for too, so the certificates are there for [signerDigests]' older
 * field on any build of those versions. (AOSP's own Android 11 and 12 collect them for either flag and fill signingInfo
 * for GET_SIGNING_CERTIFICATES, android11-release PackageManager.getPackageArchiveInfo and android12-release
 * ApplicationPackageManager.getPackageArchiveInfo; the review of lane F1 found builds that do not, 2026-11-15.)
 */
fun archiveSigningFlags(sdk: Int): Int =
    @Suppress("DEPRECATION")
    if (sdk < Build.VERSION_CODES.TIRAMISU) PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES else PackageManager.GET_SIGNING_CERTIFICATES

/**
 * The SHA-256 (hex) of each signing certificate: [current] (SigningInfo's, the certificates the APK is signed with now),
 * else, when a build leaves SigningInfo empty, [legacy] (PackageInfo.signatures). Empty without either, which no check passes.
 */
fun signerDigests(current: List<ByteArray>?, legacy: List<ByteArray>?): Set<String> =
    (current?.takeIf { it.isNotEmpty() } ?: legacy.orEmpty()).map { cert -> hex(MessageDigest.getInstance("SHA-256").digest(cert)) }.toSet()

/**
 * All of [file] is [sha256] (hex, any case): hashed whole, whether it was downloaded now or found from before. False when
 * it cannot be read. Blocking.
 */
fun wholeFileIs(file: File, sha256: String): Boolean {
    val whole = try {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(HASH_BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        hex(digest.digest())
    } catch (_: IOException) {
        return false
    }
    return whole.equals(sha256, ignoreCase = true)
}

/**
 * The downloaded [file] is [addon]'s APK: all of it is the SHA-256 this KJB pins for it ([wholeFileIs]), and only then its
 * signing, read by [archive], is checked: [addon]'s package, signed with its pinned certificate and no other. Blocking.
 */
fun verifiedAddon(file: File, addon: KjbAddon, archive: (File) -> ApkSigning?): Boolean {
    val pin = AddonPins.bytes(addon.certSha256)?.let(::hex) ?: return false
    if (!wholeFileIs(file, addon.apk.sha256)) return false
    val apk = archive(file) ?: return false
    return apk.packageName == addon.packageName && apk.signers == setOf(pin)
}

private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

private const val HASH_BUFFER_BYTES = 1024 * 1024

/** The SHA-256 (hex) of each of [info]'s signing certificates ([signerDigests]); empty without them. */
internal fun signers(info: PackageInfo?): Set<String> =
    @Suppress("DEPRECATION")
    signerDigests(info?.signingInfo?.apkContentsSigners?.map { it.toByteArray() }, info?.signatures?.map { it.toByteArray() })

/** An installed package with its signing certificates, read the same way as an APK file's ([archiveSigningFlags]). */
internal fun PackageManager.signed(packageName: String): PackageInfo? = try {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(archiveSigningFlags(Build.VERSION.SDK_INT).toLong()))
    } else {
        @Suppress("DEPRECATION")
        getPackageInfo(packageName, archiveSigningFlags(Build.VERSION.SDK_INT))
    }
} catch (_: PackageManager.NameNotFoundException) {
    null
}

/** The APK [file]'s package, version code and signers ([archiveSigningFlags], [signers]); null when Android cannot read it. */
internal fun PackageManager.archiveSigning(file: File): ApkSigning? {
    val archive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getPackageArchiveInfo(file.path, PackageManager.PackageInfoFlags.of(archiveSigningFlags(Build.VERSION.SDK_INT).toLong()))
    } else {
        @Suppress("DEPRECATION")
        getPackageArchiveInfo(file.path, archiveSigningFlags(Build.VERSION.SDK_INT))
    }
    return archive?.let { ApkSigning(it.packageName, it.longVersionCode, signers(it)) }
}

/**
 * Get AI or Get Downloads, Update, Try again: download, check, install, one at a time, in [scope]; [pinned]: this build
 * pins the add-on.
 * The APK is checked twice before Android sees it: the download against the pinned size and SHA-256, then the file's
 * package and signing certificate. What fails a check is deleted; a download that stops keeps its part.
 */
class AddonInstaller(private val port: InstallPort, private val pinned: Boolean, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow<InstallState>(InstallState.Idle)
    val state: StateFlow<InstallState> = _state.asStateFlow()

    private var work: Job? = null

    companion object {
        /**
         * How long Android's install dialog may wait for the user before the install is abandoned as failed (Cancel
         * ends it sooner): a dialog Android never showed (KJB was not in front) must not hold the page for good.
         */
        const val CONFIRM_TIMEOUT_MS: Long = 10 * 60_000
    }

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
        // Past the timeout the install is cancelled, which abandons Android's session.
        return when (withTimeoutOrNull(CONFIRM_TIMEOUT_MS) { port.install() }) {
            null -> InstallState.Failed(InstallFailure.INSTALL)
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
 * Getting KJB's add-ons on this phone, process-wide: one [AddonInstaller] per add-on over Android ([AndroidInstallPort]),
 * for the release this KJB pins ([KjbAddon.apk]: its URL, SHA-256 and size). A build whose pin is still the placeholder
 * downloads nothing.
 */
object AddonInstalls {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + logFailures)

    private val installers = ConcurrentHashMap<String, AddonInstaller>()

    fun installer(context: Context, addon: KjbAddon = KjbAddons.AI): AddonInstaller = installers.getOrPut(addon.packageName) {
        AddonInstaller(AndroidInstallPort(context.applicationContext, addon), pinned = addon.apk.pinned, scope)
    }

    /** The phone's "Install unknown apps" switch for KJB. */
    fun installSettings(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.fromParts("package", context.packageName, null))

    /** The add-on's app info, where one that is not KJB's can be uninstalled. */
    fun addonInfo(addon: KjbAddon = KjbAddons.AI): Intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", addon.packageName, null))
}

/**
 * GitHub's release downloads only, over https on the default port: github.com, which redirects to its asset host
 * (ModelStore's [AllowedHosts] for the same two).
 */
internal object GitHubReleases : ModelHosts {
    override fun isSource(uri: URI): Boolean = AllowedHosts.isSource(uri) && uri.host.equals(AllowedHosts.GITHUB, ignoreCase = true)

    override fun isRedirect(uri: URI): Boolean =
        AllowedHosts.isRedirect(uri) && uri.host?.lowercase() in setOf(AllowedHosts.GITHUB, AllowedHosts.GITHUB_ASSETS)
}

/**
 * An add-on's APK through Android, the same way for each add-on: downloaded into KJB's cache with the models' verified
 * downloader ([ModelDownloader]: the pinned size and SHA-256, resumed with HTTP Range, GitHub only), checked again whole
 * and for the add-on's package and KJB's certificate ([verifiedAddon], the older signatures field too before Android 13),
 * and installed with a PackageInstaller session. The result comes back to [InstallReceiver]. Get AI and Get Downloads
 * install this way, and so does the update sheet's Update for either add-on.
 */
internal class AndroidInstallPort(private val context: Context, private val addon: KjbAddon) : InstallPort {
    private val folder = File(context.cacheDir, addon.folder)
    private val store get() = ModelStore(folder, listOf(addon.apk.spec), folder)
    private val apk: File get() = store.file(addon.apk.spec)

    override suspend fun download(progress: suspend (Long, Long) -> Unit): Download {
        // What an earlier KJB downloaded for another pin goes first.
        val keep = setOf(apk.name, store.part(addon.apk.spec).name)
        withContext(Dispatchers.IO) { folder.listFiles()?.filter { it.name !in keep }?.forEach { it.delete() } }
        return ModelDownloader(store, USER_AGENT, hosts = GitHubReleases, log = { Log.d(LOG_TAG, "${addon.label}: $it") }).downloadAll(progress)
    }

    override fun verified(): Boolean = verifiedAddon(apk, addon, context.packageManager::archiveSigning)

    override fun discard() {
        if (!store.delete()) Log.w(LOG_TAG, "${addon.label}: the downloaded file could not be deleted")
    }

    override fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    override suspend fun install(): InstallOutcome = withContext(Dispatchers.IO) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(addon.packageName)
            setSize(apk.length())
            // An update of the add-on KJB installed may go through without asking (Android 12+, with
            // UPDATE_PACKAGES_WITHOUT_USER_ACTION); the first install always asks, and so may any other.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val session = try {
            installer.createSession(params)
        } catch (e: IOException) {
            Log.w(LOG_TAG, "${addon.label}: no install session (${e.javaClass.simpleName})")
            return@withContext InstallOutcome.Failed(PackageInstaller.STATUS_FAILURE)
        }
        val outcome = InstallReceiver.expect(session, addon)
        try {
            installer.openSession(session).use { open ->
                open.openWrite(INSTALL_NAME, 0, apk.length()).use { out ->
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
            Log.w(LOG_TAG, "${addon.label}: install not written (${e.javaClass.simpleName})")
            runCatching { installer.abandonSession(session) }
            InstallOutcome.Failed(PackageInstaller.STATUS_FAILURE)
        } catch (e: SecurityException) {
            Log.w(LOG_TAG, "${addon.label}: install refused (${e.javaClass.simpleName})")
            runCatching { installer.abandonSession(session) }
            InstallOutcome.Failed(PackageInstaller.STATUS_FAILURE_BLOCKED)
        } finally {
            InstallReceiver.forget(session)
        }
    }

    private companion object {
        /** The APK's name in the install session. */
        const val INSTALL_NAME = "addon.apk"
    }
}

/**
 * Android's installer's word on an add-on install (through [AndroidInstallPort]'s pending intent; not exported, so only
 * that reaches it): it asks the user (its dialog is started from here), or it is done, refused or failed. Each session
 * is heard only for the add-on KJB started it for.
 */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }
        val report = InstallReport(
            ourSession = intent.getIntExtra(EXTRA_SESSION, -1),
            session = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1),
            status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE),
            packageName = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME),
            confirmAction = confirm?.action,
            confirmPackage = confirm?.`package`,
            confirmSession = confirm?.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1) ?: -1,
        )
        val pending = outcomes[report.ourSession]
        when (val verdict = report.verdict(outcomes::containsKey, pending?.addon?.packageName.orEmpty())) {
            InstallVerdict.Ignore -> Log.w(LOG_TAG, "add-on: an install report for no session of KJB's, ignored")
            is InstallVerdict.Confirm -> {
                // Rebuilt from what was checked, nothing else. KJB is in front (the user just tapped Get AI), so Android
                // lets it show the dialog; when it cannot, the install ends here instead of waiting for it.
                val dialog = Intent(InstallReport.CONFIRM_INSTALL).setPackage(verdict.installer)
                    .putExtra(PackageInstaller.EXTRA_SESSION_ID, verdict.session).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(dialog)
                } catch (e: ActivityNotFoundException) {
                    end(verdict.session, InstallOutcome.Failed(PackageInstaller.STATUS_FAILURE), e)
                } catch (e: SecurityException) {
                    end(verdict.session, InstallOutcome.Failed(PackageInstaller.STATUS_FAILURE), e)
                }
            }
            is InstallVerdict.End -> {
                val addon = pending?.addon ?: return
                if (verdict.outcome is InstallOutcome.Failed) Log.w(LOG_TAG, "${addon.label}: not installed, status ${verdict.outcome.status}")
                outcomes.remove(report.session)?.outcome?.complete(verdict.outcome)
                if (verdict.outcome == InstallOutcome.Installed && addon == KjbAddons.AI) AiLyrics.addonInstalled(context)
            }
        }
    }

    private fun end(session: Int, outcome: InstallOutcome, e: Exception) {
        Log.w(LOG_TAG, "add-on: the install dialog did not open (${e.javaClass.simpleName})")
        outcomes.remove(session)?.outcome?.complete(outcome)
    }

    companion object {
        const val EXTRA_SESSION: String = "app.lyrify.addon.session"

        /** An install waiting for the installer's word: the add-on it is for, and where its outcome goes. */
        private class Pending(val addon: KjbAddon, val outcome: CompletableDeferred<InstallOutcome>)

        /** The installs waiting for the installer's word, by session. */
        private val outcomes = ConcurrentHashMap<Int, Pending>()

        internal fun expect(session: Int, addon: KjbAddon): CompletableDeferred<InstallOutcome> =
            CompletableDeferred<InstallOutcome>().also { outcomes[session] = Pending(addon, it) }

        internal fun forget(session: Int) {
            outcomes.remove(session)
        }
    }
}
