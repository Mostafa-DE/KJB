package app.lyrify.update

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.edit
import app.lyrify.BuildConfig
import app.lyrify.LOG_TAG
import app.lyrify.USER_AGENT
import app.lyrify.addon.AddonInstaller
import app.lyrify.addon.GitHubReleases
import app.lyrify.addon.InstallOutcome
import app.lyrify.addon.InstallPort
import app.lyrify.addon.InstallReport
import app.lyrify.addon.InstallVerdict
import app.lyrify.logFailures
import app.lyrify.models.Download
import app.lyrify.models.ModelDownloader
import app.lyrify.models.ModelSpec
import app.lyrify.models.ModelStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URISyntaxException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

// KJB's own update check (features-2026-11-15, item 2): once a day when KJB opens, and from Settings > About > Check now,
// one small request to KJB's latest release on GitHub. A newer KJB shows "KJB <ver> is out" with its notes; Install
// downloads its APK (GitHub only, https, its published size and SHA-256), checks it is KJB's own package, a higher version
// and signed exactly like the KJB running, then installs it with Android's installer, the add-on's way (addon/). Installed
// add-ons older than the one this KJB brings are offered too. "Check for updates" (on by default) turns the daily check off.

/** KJB's latest release on GitHub: its [version] ("3.0.1"), its notes as plain text, and its APK (null: none KJB can check). */
data class Release(val version: String, val notes: String, val apk: ReleaseApk?)

/** The release's `KJB-<version>.apk`: its address on github.com, its size, and the SHA-256 GitHub publishes for it (hex). */
data class ReleaseApk(val url: String, val bytes: Long, val sha256: String)

/** GitHub's answer about a release, read leniently, and the versions compared. */
object Releases {
    /** No KJB is anywhere near this; a bigger file is not taken for one. */
    const val MAX_APK_BYTES: Long = 300L * 1024 * 1024

    /** Release notes past this are cut: the sheet is no place for more. */
    private const val MAX_NOTES: Int = 4000

    private val JSON = Json { ignoreUnknownKeys = true }
    private val TAG = Regex("""v?(\d+(?:\.\d+)*)""")
    private val LEADING = Regex("""^v?(\d+(?:\.\d+)*)""")
    private val HEX64 = Regex("[0-9a-fA-F]{64}")
    private val ADDON_FILE = Regex("""/KJB-AI-(\d+(?:\.\d+)*)\.apk$""")
    private val BULLET = Regex("""^\s*[-*+]\s+""")
    private val HEADING = Regex("""^\s*#{1,6}\s*""")

    /**
     * The release in [json] (GitHub's `releases/latest`); null when it is not one, a draft or a pre-release, or its tag
     * is not a version ("v3.0.1"). Its APK is the asset named `KJB-<version>.apk` (the build's own name for it), uploaded,
     * of a size up to [MAX_APK_BYTES], with a published SHA-256, a release download of [repo] ("owner/name") on github.com
     * over https; else none.
     */
    fun parse(json: String, repo: String): Release? {
        val root = try {
            JSON.parseToJsonElement(json) as? JsonObject
        } catch (_: SerializationException) {
            null
        } ?: return null
        if (root.flag("draft") || root.flag("prerelease")) return null
        val version = root.text("tag_name")?.let(TAG::matchEntire)?.groupValues?.get(1) ?: return null
        val assets = (root["assets"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val apk = assets.firstOrNull { it.text("name") == "KJB-$version.apk" }?.let { apkOf(it, repo) }
        return Release(version, plainNotes(root.text("body").orEmpty()), apk)
    }

    private fun apkOf(asset: JsonObject, repo: String): ReleaseApk? {
        if (asset.text("state") != "uploaded") return null
        val bytes = (asset["size"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it in 1..MAX_APK_BYTES } ?: return null
        val sha = sha256(asset.text("digest")) ?: return null
        val url = asset.text("browser_download_url")?.takeIf { onGitHub(it, repo) } ?: return null
        return ReleaseApk(url, bytes, sha)
    }

    /** A release download of [repo] on github.com over https (the downloader checks every redirect again). */
    private fun onGitHub(url: String, repo: String): Boolean = try {
        val uri = URI(url)
        uri.scheme == "https" && uri.host.equals("github.com", ignoreCase = true) && (uri.port == -1 || uri.port == HTTPS_PORT) &&
            uri.rawUserInfo == null && uri.rawPath.orEmpty().startsWith("/$repo/releases/download/", ignoreCase = true) && "/../" !in uri.rawPath
    } catch (_: URISyntaxException) {
        false
    }

    /** The numbers of [name]: "v3.0.1" and "3.0.1-dev" are 3, 0, 1; null when it does not start with one. */
    fun version(name: String): List<Int>? = LEADING.find(name)?.groupValues?.get(1)?.split('.')?.map { it.toIntOrNull() ?: return null }

    /** [candidate] is a higher version than [installed], number by number (3.0 is 3.0.0); never when either is no version. */
    fun newer(candidate: String, installed: String): Boolean {
        val new = version(candidate) ?: return false
        val old = version(installed) ?: return false
        for (i in 0 until maxOf(new.size, old.size)) {
            val a = new.getOrElse(i) { 0 }
            val b = old.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    /** GitHub's asset digest ("sha256:<hex>") as lowercase hex; null for none or any other kind. */
    fun sha256(digest: String?): String? = digest?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")?.takeIf(HEX64::matches)?.lowercase()

    /** The AI add-on version this KJB pins, from its file's name (`KJB-AI-<version>.apk`); null for another name. */
    fun pinnedVersion(url: String): String? = ADDON_FILE.find(url)?.groupValues?.get(1)

    /** Release notes (Markdown) as the sheet shows them: headings without their marks, bullets as "•", short. */
    fun plainNotes(body: String): String = body.replace("\r\n", "\n").lines()
        .joinToString("\n") { line -> line.replace(HEADING, "").replace(BULLET, "• ").trimEnd() }
        .replace(Regex("\n{3,}"), "\n\n").trim().take(MAX_NOTES)

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.flag(key: String): Boolean = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull == true

    private const val HTTPS_PORT = 443
}

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
 * The downloaded [file] may be installed over the KJB running ([own]): all of it is the release's [sha256] (hex, any case),
 * whether it was downloaded now or found from before, and only then its signing, read by [archive], is checked
 * ([isKjbUpdate]). A file that cannot be read is no update. Blocking.
 */
fun verifiedUpdate(file: File, sha256: String, own: ApkSigning, archive: (File) -> ApkSigning?): Boolean {
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
    return whole.equals(sha256, ignoreCase = true) && isKjbUpdate(own, archive(file))
}

private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

private const val HASH_BUFFER_BYTES = 1024 * 1024

/**
 * One update install at a time: the one for [key] (a release's APK), the same while it is asked for again; another
 * release's cancels the one before ([cancel]), so two never share the download folder.
 */
internal class SingleInstall<K : Any, T : Any>(private val make: (K) -> T, private val cancel: (T) -> Unit) {
    private var current: Pair<K, T>? = null

    @Synchronized
    fun get(key: K): T {
        current?.let { (held, installer) -> if (held == key) return installer else cancel(installer) }
        return make(key).also { current = key to it }
    }
}

/**
 * [verdict] as KJB acts on it: a confirmation ([InstallReport.verdict] has checked its action and session) starts only
 * when [isSystemInstaller] says its package is the phone's own installer; any other ends the install as failed.
 */
internal fun confirmFrom(verdict: InstallVerdict, isSystemInstaller: (String) -> Boolean): InstallVerdict =
    if (verdict is InstallVerdict.Confirm && !isSystemInstaller(verdict.installer)) InstallVerdict.End(InstallOutcome.Failed(PackageInstaller.STATUS_FAILURE)) else verdict

/** A day, the daily check's spacing. */
private const val DAY_MS: Long = 24 * 60 * 60_000L

/** The daily check is due: switched on, and never done, or done a day ago or more (or the clock went back since). */
fun checkDue(enabled: Boolean, lastCheck: Long?, now: Long): Boolean = enabled && (lastCheck == null || now < lastCheck || now - lastCheck >= DAY_MS)

/** An APK as its update check sees it: its package, version code and signing certificates (SHA-256, hex). */
data class ApkSigning(val packageName: String, val versionCode: Long, val signers: Set<String>)

/**
 * [apk] may replace the KJB running ([own]): the same package, a higher version code, and exactly the same signing
 * certificates. Android refuses another key anyway; KJB checks first, so a file that is not its own is never offered to
 * the installer.
 */
fun isKjbUpdate(own: ApkSigning, apk: ApkSigning?): Boolean =
    apk != null && own.signers.isNotEmpty() && apk.packageName == own.packageName && apk.versionCode > own.versionCode && apk.signers == own.signers

/** What asking GitHub gave. */
sealed interface Fetched {
    /** GitHub answered: the latest [release], or null when there is none (yet) KJB can read as one. */
    data class Answer(val release: Release?) : Fetched

    /** No answer: no network, a busy or refusing server, an answer that is not a release. */
    data object NoAnswer : Fetched
}

/**
 * KJB's latest release from GitHub's API (`api.github.com/repos/<repo>/releases/latest`): one GET, https only, redirects
 * followed only within api.github.com (a renamed repository answers with one), at most [MAX_ANSWER_BYTES] read. Blocking.
 */
class GitHubLatest(
    private val repo: String,
    private val userAgent: String,
    private val open: (URI) -> HttpURLConnection = { it.toURL().openConnection() as HttpURLConnection },
) {
    fun fetch(): Fetched {
        var uri = try {
            URI("https", API, "/repos/$repo/releases/latest", null)
        } catch (_: URISyntaxException) {
            return Fetched.NoAnswer
        }
        repeat(MAX_REDIRECTS + 1) {
            if (!allowed(uri)) return Fetched.NoAnswer
            val connection = try {
                open(uri)
            } catch (_: IOException) {
                return Fetched.NoAnswer
            }
            try {
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.useCaches = false
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("User-Agent", userAgent)
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                connection.setRequestProperty("X-GitHub-Api-Version", API_VERSION)
                when (connection.responseCode) {
                    HttpURLConnection.HTTP_OK -> return body(connection)?.let { Releases.parse(it, repo) }?.let { Fetched.Answer(it) } ?: Fetched.NoAnswer
                    HttpURLConnection.HTTP_NOT_FOUND -> return Fetched.Answer(null)
                    HttpURLConnection.HTTP_MOVED_PERM, HttpURLConnection.HTTP_MOVED_TEMP, TEMPORARY_REDIRECT, PERMANENT_REDIRECT -> {
                        uri = connection.getHeaderField("Location")?.let { resolve(uri, it) } ?: return Fetched.NoAnswer
                    }
                    else -> return Fetched.NoAnswer
                }
            } catch (_: IOException) {
                return Fetched.NoAnswer
            } finally {
                connection.disconnect()
            }
        }
        return Fetched.NoAnswer
    }

    /** The answer's text; null when it is longer than [MAX_ANSWER_BYTES]. */
    private fun body(connection: HttpURLConnection): String? {
        if (connection.contentLengthLong > MAX_ANSWER_BYTES) return null
        val out = ByteArrayOutputStream()
        connection.inputStream.use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (out.size() + read > MAX_ANSWER_BYTES) return null
                out.write(buffer, 0, read)
            }
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private fun allowed(uri: URI): Boolean =
        uri.scheme == "https" && uri.host.equals(API, ignoreCase = true) && (uri.port == -1 || uri.port == HTTPS_PORT) && uri.rawUserInfo == null

    private fun resolve(base: URI, location: String): URI? = try {
        base.resolve(location)
    } catch (_: IllegalArgumentException) {
        null
    }

    companion object {
        /** A release's JSON is a few kB; its notes would have to be a book to come near this. */
        const val MAX_ANSWER_BYTES: Int = 1024 * 1024
        private const val API = "api.github.com"
        private const val API_VERSION = "2022-11-28"
        private const val MAX_REDIRECTS = 3
        private const val TIMEOUT_MS = 15_000
        private const val BUFFER_BYTES = 16 * 1024
        private const val HTTPS_PORT = 443
        private const val TEMPORARY_REDIRECT = 307
        private const val PERMANENT_REDIRECT = 308
    }
}

/** Where KJB's update check stands. */
sealed interface UpdateState {
    /** Not checked since KJB started. */
    data object Idle : UpdateState

    data object Checking : UpdateState

    /**
     * Checked: [release] is a newer KJB (null: KJB is up to date), [addon] the AI add-on version this KJB brings when the
     * one installed is older. [ask]: the update sheet shows them now.
     */
    data class Checked(val release: Release?, val addon: String?, val ask: Boolean) : UpdateState

    /** GitHub did not answer. */
    data object Failed : UpdateState
}

/** What the update check needs from the phone. */
interface UpdatePort {
    /** KJB's latest release, from GitHub. */
    suspend fun latest(): Fetched

    /** When KJB last asked GitHub; null never. */
    suspend fun lastCheck(): Long?

    suspend fun setLastCheck(at: Long)

    /** The offer the user last put off with Not now. */
    suspend fun dismissed(): String?

    suspend fun setDismissed(offer: String)

    /** The version of KJB's own AI add-on installed; null when there is none. */
    fun addonInstalled(): String?
}

/**
 * The update check, one at a time in [scope]: [checkIfDue] when KJB opens (at most once a day, and quietly: an offer the
 * user put off is not shown again), [checkNow] from Settings (always asks, and shows any offer). [installed] is this KJB's
 * version, [addonOffered] the AI add-on version it pins (null: none). The time of a check is kept before it asks, so a
 * check without an answer still counts for the day.
 */
class UpdateChecker(
    private val port: UpdatePort,
    private val installed: String,
    private val addonOffered: String?,
    private val clock: () -> Long,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val one = Mutex()

    /** KJB opened: checks when [enabled] and due ([checkDue]). */
    fun checkIfDue(enabled: Boolean) {
        if (!enabled) return
        scope.launch {
            one.withLock { if (checkDue(enabled = true, port.lastCheck(), clock())) check(manual = false) }
        }
    }

    /** Check now: asks GitHub whatever the time; the sheet shows any offer. It runs on when the screen that asked closes. */
    suspend fun checkNow(): UpdateState = scope.async { one.withLock { check(manual = true) } }.await()

    /** Not now, or the sheet put away: it closes, and the daily check does not show this offer again. */
    fun dismiss() {
        val shown = _state.value as? UpdateState.Checked ?: return
        _state.value = shown.copy(ask = false)
        offerOf(shown)?.let { offer -> scope.launch { port.setDismissed(offer) } }
    }

    private suspend fun check(manual: Boolean): UpdateState {
        _state.value = UpdateState.Checking
        port.setLastCheck(clock())
        val result = when (val fetched = port.latest()) {
            Fetched.NoAnswer -> UpdateState.Failed
            is Fetched.Answer -> {
                val release = fetched.release?.takeIf { Releases.newer(it.version, installed) }
                val addon = addonOffered?.takeIf { offered -> port.addonInstalled()?.let { Releases.newer(offered, it) } == true }
                val checked = UpdateState.Checked(release, addon, ask = false)
                val offer = offerOf(checked)
                checked.copy(ask = offer != null && (manual || offer != port.dismissed()))
            }
        }
        _state.value = result
        return result
    }

    /** What [checked] offers, as one key: what Not now puts off. */
    private fun offerOf(checked: UpdateState.Checked): String? =
        if (checked.release == null && checked.addon == null) null else "kjb:${checked.release?.version}/addon:${checked.addon}"
}

/** The SHA-256 (hex) of each of [info]'s signing certificates ([signerDigests]); empty without them. */
private fun signers(info: PackageInfo?): Set<String> =
    @Suppress("DEPRECATION")
    signerDigests(info?.signingInfo?.apkContentsSigners?.map { it.toByteArray() }, info?.signatures?.map { it.toByteArray() })

/** An installed package with its signing certificates, read the same way as an APK file's ([archiveSigningFlags]). */
private fun PackageManager.signed(packageName: String): PackageInfo? = try {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(archiveSigningFlags(Build.VERSION.SDK_INT).toLong()))
    } else {
        @Suppress("DEPRECATION")
        getPackageInfo(packageName, archiveSigningFlags(Build.VERSION.SDK_INT))
    }
} catch (_: PackageManager.NameNotFoundException) {
    null
}

/** The update check through Android: GitHub on the network, its times in app-private preferences, the add-on's package. */
private class AndroidUpdatePort(private val context: Context) : UpdatePort {
    private val prefs by lazy { context.getSharedPreferences("updates", Context.MODE_PRIVATE) }

    override suspend fun latest(): Fetched = withContext(Dispatchers.IO) {
        GitHubLatest(BuildConfig.UPDATE_REPO, USER_AGENT).fetch().also { if (it == Fetched.NoAnswer) Log.w(LOG_TAG, "update: no answer from GitHub") }
    }

    override suspend fun lastCheck(): Long? = withContext(Dispatchers.IO) { prefs.getLong(LAST_CHECK, -1).takeIf { it >= 0 } }

    override suspend fun setLastCheck(at: Long) = withContext(Dispatchers.IO) { prefs.edit { putLong(LAST_CHECK, at) } }

    override suspend fun dismissed(): String? = withContext(Dispatchers.IO) { prefs.getString(DISMISSED, null) }

    override suspend fun setDismissed(offer: String) = withContext(Dispatchers.IO) { prefs.edit { putString(DISMISSED, offer) } }

    /** The add-on's version, when it is installed and signed with KJB's key (an add-on that is not KJB's is never updated). */
    override fun addonInstalled(): String? {
        val pin = BuildConfig.AI_CERT_SHA256.lowercase()
        val info = context.packageManager.signed(BuildConfig.AI_ADDON_PACKAGE) ?: return null
        return info.versionName?.takeIf { pin in signers(info) }
    }

    private companion object {
        const val LAST_CHECK = "lastCheck"
        const val DISMISSED = "dismissed"
    }
}

/**
 * A newer KJB's APK through Android, the add-on's way: downloaded into KJB's cache with the models' downloader (the
 * release's size and SHA-256, resumed with HTTP Range, github.com and its asset host only), checked against the KJB
 * running ([isKjbUpdate]), and installed with a PackageInstaller session; Android's word comes back to [UpdateReceiver].
 * The install replaces KJB, so a successful one ends this process.
 */
internal class SelfInstallPort(private val context: Context, private val apk: ReleaseApk) : InstallPort {
    /** Each release in a folder of its own, named by its whole SHA-256 ([Updates.installer] runs one at a time). */
    private val root = File(context.cacheDir, FOLDER)
    private val folder = File(root, apk.sha256)
    private val spec = ModelSpec(APK, apk.url, apk.sha256, apk.bytes)
    private val store = ModelStore(folder, listOf(spec), folder)
    private val file: File get() = store.file(spec)

    override suspend fun download(progress: suspend (Long, Long) -> Unit): Download {
        // What was downloaded for another release goes first: its install was cancelled when this one began.
        withContext(Dispatchers.IO) { root.listFiles()?.filter { it != folder }?.forEach { it.deleteRecursively() } }
        return ModelDownloader(store, USER_AGENT, hosts = GitHubReleases, log = { Log.d(LOG_TAG, "update: $it") }).downloadAll(progress)
    }

    override fun verified(): Boolean {
        val packages = context.packageManager
        val own = packages.signed(context.packageName) ?: return false
        return verifiedUpdate(file, apk.sha256, ApkSigning(own.packageName, own.longVersionCode, signers(own))) { apkFile ->
            val archive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packages.getPackageArchiveInfo(apkFile.path, PackageManager.PackageInfoFlags.of(archiveSigningFlags(Build.VERSION.SDK_INT).toLong()))
            } else {
                @Suppress("DEPRECATION")
                packages.getPackageArchiveInfo(apkFile.path, archiveSigningFlags(Build.VERSION.SDK_INT))
            }
            archive?.let { ApkSigning(it.packageName, it.longVersionCode, signers(it)) }
        }
    }

    override fun discard() {
        if (!store.delete()) Log.w(LOG_TAG, "update: the downloaded file could not be deleted")
    }

    override fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    override suspend fun install(): InstallOutcome = withContext(Dispatchers.IO) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(file.length())
            // Once KJB installed itself, its next update may go through without asking (Android 12+, with
            // UPDATE_PACKAGES_WITHOUT_USER_ACTION); else Android asks the user to confirm.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val session = try {
            installer.createSession(params)
        } catch (e: IOException) {
            Log.w(LOG_TAG, "update: no install session (${e.javaClass.simpleName})")
            return@withContext InstallOutcome.Failed(PackageInstaller.STATUS_FAILURE)
        }
        val outcome = UpdateReceiver.expect(session)
        try {
            installer.openSession(session).use { open ->
                open.openWrite(INSTALL_NAME, 0, file.length()).use { out ->
                    file.inputStream().use { it.copyTo(out) }
                    open.fsync(out)
                }
                val status = Intent(context, UpdateReceiver::class.java).putExtra(UpdateReceiver.EXTRA_SESSION, session)
                // Mutable: the installer adds its status. Explicit, to KJB's own receiver, which is not exported.
                val pending = PendingIntent.getBroadcast(context, session, status, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                open.commit(pending.intentSender)
            }
            outcome.await()
        } catch (e: CancellationException) {
            runCatching { installer.abandonSession(session) }
            throw e
        } catch (e: IOException) {
            Log.w(LOG_TAG, "update: install not written (${e.javaClass.simpleName})")
            runCatching { installer.abandonSession(session) }
            InstallOutcome.Failed(PackageInstaller.STATUS_FAILURE)
        } catch (e: SecurityException) {
            Log.w(LOG_TAG, "update: install refused (${e.javaClass.simpleName})")
            runCatching { installer.abandonSession(session) }
            InstallOutcome.Failed(PackageInstaller.STATUS_FAILURE_BLOCKED)
        } finally {
            UpdateReceiver.forget(session)
        }
    }

    private companion object {
        const val FOLDER = "kjb-update"
        const val APK = "KJB.apk"
        const val INSTALL_NAME = "kjb.apk"
    }
}

/**
 * Android's installer's word on a KJB update (through [SelfInstallPort]'s pending intent; not exported), read as the
 * add-on's receiver reads it ([InstallReport]) but for KJB's own package. One receiver for both once the add-on code is
 * shared (lane DL1).
 */
class UpdateReceiver : BroadcastReceiver() {
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
        when (val verdict = confirmFrom(report.verdict(outcomes::containsKey, context.packageName)) { systemInstaller(context, it) }) {
            InstallVerdict.Ignore -> Log.w(LOG_TAG, "update: an install report for no session of KJB's, ignored")
            is InstallVerdict.Confirm -> {
                // Rebuilt from what was checked, nothing else; KJB is in front (the user just tapped Install).
                val dialog = Intent(InstallReport.CONFIRM_INSTALL).setPackage(verdict.installer)
                    .putExtra(PackageInstaller.EXTRA_SESSION_ID, verdict.session).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(dialog)
                } catch (e: ActivityNotFoundException) {
                    end(verdict.session, e)
                } catch (e: SecurityException) {
                    end(verdict.session, e)
                }
            }
            is InstallVerdict.End -> {
                if (verdict.outcome is InstallOutcome.Failed) Log.w(LOG_TAG, "update: not installed, status ${verdict.outcome.status}")
                outcomes.remove(report.session)?.complete(verdict.outcome)
            }
        }
    }

    /**
     * [packageName] is the phone's own package installer: a system app with the activity for Android's confirm action
     * (KJB's manifest queries it, so the installer is visible whatever its name on this phone).
     */
    private fun systemInstaller(context: Context, packageName: String): Boolean {
        val confirm = Intent(InstallReport.CONFIRM_INSTALL).setPackage(packageName)
        val found = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.resolveActivity(confirm, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_SYSTEM_ONLY.toLong()))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.resolveActivity(confirm, PackageManager.MATCH_SYSTEM_ONLY)
        }
        return found?.activityInfo?.packageName == packageName
    }

    private fun end(session: Int, e: Exception) {
        Log.w(LOG_TAG, "update: the install dialog did not open (${e.javaClass.simpleName})")
        outcomes.remove(session)?.complete(InstallOutcome.Failed(PackageInstaller.STATUS_FAILURE))
    }

    companion object {
        const val EXTRA_SESSION: String = "app.lyrify.update.session"

        /** The installs waiting for the installer's word, by session. */
        private val outcomes = ConcurrentHashMap<Int, CompletableDeferred<InstallOutcome>>()

        internal fun expect(session: Int): CompletableDeferred<InstallOutcome> = CompletableDeferred<InstallOutcome>().also { outcomes[session] = it }

        internal fun forget(session: Int) {
            outcomes.remove(session)
        }
    }
}

/**
 * KJB's updates, process-wide: one [UpdateChecker] for KJB's repository ([BuildConfig.UPDATE_REPO]), and one installer
 * per release's APK, each the add-on's [AddonInstaller] over [SelfInstallPort].
 */
object Updates {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + logFailures)

    @Volatile
    private var checker: UpdateChecker? = null

    @Volatile
    private var installs: SingleInstall<ReleaseApk, AddonInstaller>? = null

    fun checker(context: Context): UpdateChecker = checker ?: synchronized(this) {
        checker ?: UpdateChecker(AndroidUpdatePort(context.applicationContext), BuildConfig.VERSION_NAME, addonOffered(), System::currentTimeMillis, scope)
            .also { checker = it }
    }

    /**
     * Installs [apk], a newer KJB: the same installer for the same file, so its download and state carry on; another
     * release's cancels the one before.
     */
    fun installer(context: Context, apk: ReleaseApk): AddonInstaller {
        val one = installs ?: synchronized(this) {
            installs ?: SingleInstall<ReleaseApk, AddonInstaller>(
                make = { release -> AddonInstaller(SelfInstallPort(context.applicationContext, release), pinned = true, scope) },
                cancel = AddonInstaller::cancel,
            ).also { installs = it }
        }
        return one.get(apk)
    }

    /** The AI add-on version this KJB brings: its pinned file's, when this build pins one. */
    private fun addonOffered(): String? =
        Releases.pinnedVersion(BuildConfig.AI_ADDON_URL).takeIf { BuildConfig.AI_ADDON_SHA256.length == SHA256_HEX && BuildConfig.AI_ADDON_BYTES > 0 }

    private const val SHA256_HEX = 64
}
