package app.lyrify.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import androidx.core.content.edit
import app.lyrify.BuildConfig
import app.lyrify.LOG_TAG
import app.lyrify.USER_AGENT
import app.lyrify.addon.AddonApk
import app.lyrify.addon.AddonInstaller
import app.lyrify.addon.ApkSigning
import app.lyrify.addon.GitHubReleases
import app.lyrify.addon.InstallOutcome
import app.lyrify.addon.InstallPort
import app.lyrify.addon.InstallReceiver
import app.lyrify.addon.InstallTarget
import app.lyrify.addon.KjbAddon
import app.lyrify.addon.KjbAddons
import app.lyrify.addon.archiveSigning
import app.lyrify.addon.signed
import app.lyrify.addon.signers
import app.lyrify.addon.wholeFileIs
import app.lyrify.logFailures
import app.lyrify.models.Download
import app.lyrify.models.ModelDownloader
import app.lyrify.models.ModelSpec
import app.lyrify.models.ModelStore
import kotlinx.coroutines.CancellationException
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

    /** The add-on version this KJB pins in [apk], from its file's name (`<stem>-<version>.apk`); null for another name. */
    fun pinnedVersion(apk: AddonApk): String? = Regex("""/${Regex.escape(apk.stem)}-(\d+(?:\.\d+)*)\.apk$""").find(apk.url)?.groupValues?.get(1)

    /** Release notes (Markdown) as the sheet shows them: headings without their marks, bullets as "•", short. */
    fun plainNotes(body: String): String = body.replace("\r\n", "\n").lines()
        .joinToString("\n") { line -> line.replace(HEADING, "").replace(BULLET, "• ").trimEnd() }
        .replace(Regex("\n{3,}"), "\n\n").trim().take(MAX_NOTES)

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.flag(key: String): Boolean = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull == true

    private const val HTTPS_PORT = 443
}

/**
 * The downloaded [file] may be installed over the KJB running ([own]): all of it is the release's [sha256] (hex, any case),
 * whether it was downloaded now or found from before ([wholeFileIs]), and only then its signing, read by [archive], is
 * checked ([isKjbUpdate]). A file that cannot be read is no update. Blocking.
 */
fun verifiedUpdate(file: File, sha256: String, own: ApkSigning, archive: (File) -> ApkSigning?): Boolean =
    wholeFileIs(file, sha256) && isKjbUpdate(own, archive(file))

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

/** A day, the daily check's spacing. */
private const val DAY_MS: Long = 24 * 60 * 60_000L

/** The daily check is due: switched on, and never done, or done a day ago or more (or the clock went back since). */
fun checkDue(enabled: Boolean, lastCheck: Long?, now: Long): Boolean = enabled && (lastCheck == null || now < lastCheck || now - lastCheck >= DAY_MS)

/**
 * [apk] may replace the KJB running ([own]): the same package, a higher version code, and exactly the same signing
 * certificates. Android refuses another key anyway; KJB checks first, so a file that is not its own is never offered to
 * the installer.
 */
fun isKjbUpdate(own: ApkSigning, apk: ApkSigning?): Boolean =
    apk != null && own.signers.isNotEmpty() && apk.packageName == own.packageName && apk.versionCode > own.versionCode && apk.signers == own.signers

/** An add-on update this KJB brings: the [version] of the file it pins for [addon] (its [KjbAddon.apk]). */
data class AddonUpdate(val addon: KjbAddon, val version: String)

/** The add-on updates this KJB brings: each of [addons] whose file it pins ([AddonApk.pinned]), at that file's version. */
fun offeredAddons(addons: List<KjbAddon>): List<AddonUpdate> =
    addons.mapNotNull { addon -> Releases.pinnedVersion(addon.apk)?.takeIf { addon.apk.pinned }?.let { AddonUpdate(addon, it) } }

/** What asking GitHub gave. */
sealed interface Fetched {
    /** GitHub answered with the latest [release]. */
    data class Answer(val release: Release) : Fetched

    /**
     * No answer: no network, a busy or refusing server, an answer that is not a release, or no release at all (a 404:
     * the repository is missing or renamed, or has none yet), which must never read as KJB being up to date.
     */
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
     * Checked: [release] is a newer KJB (null: KJB is up to date), [addons] the add-ons this KJB brings newer ones of than
     * those installed, in [UpdateChecker]'s order. [ask]: the update sheet shows them now.
     */
    data class Checked(val release: Release?, val addons: List<AddonUpdate>, val ask: Boolean) : UpdateState

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

    /** The version of [addon] installed, when it is KJB's own (signed with its pinned certificate); null when there is none. */
    fun addonInstalled(addon: KjbAddon): String?
}

/**
 * The update check, one at a time in [scope]: [checkIfDue] when KJB opens (at most once a day, and quietly: an offer the
 * user put off is not shown again), [checkNow] from Settings (always asks, and shows any offer). [installed] is this KJB's
 * version, [addonsOffered] the add-on versions it pins ([offeredAddons]). The time of a check is kept before it asks, so a
 * check without an answer still counts for the day.
 */
class UpdateChecker(
    private val port: UpdatePort,
    private val installed: String,
    private val addonsOffered: List<AddonUpdate>,
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
                val release = fetched.release.takeIf { Releases.newer(it.version, installed) }
                val addons = addonsOffered.filter { offered -> port.addonInstalled(offered.addon)?.let { Releases.newer(offered.version, it) } == true }
                val checked = UpdateState.Checked(release, addons, ask = false)
                val offer = offerOf(checked)
                checked.copy(ask = offer != null && (manual || offer != port.dismissed()))
            }
        }
        _state.value = result
        return result
    }

    /** What [checked] offers, as one key: what Not now puts off. */
    private fun offerOf(checked: UpdateState.Checked): String? =
        if (checked.release == null && checked.addons.isEmpty()) null else "kjb:${checked.release?.version}" + checked.addons.joinToString("") { "/${it.addon.packageName}:${it.version}" }
}

/** The update check through Android: GitHub on the network, its times in app-private preferences, the add-ons' packages. */
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
    override fun addonInstalled(addon: KjbAddon): String? {
        val pin = addon.certSha256.lowercase()
        val info = context.packageManager.signed(addon.packageName) ?: return null
        return info.versionName?.takeIf { pin.isNotEmpty() && pin in signers(info) }
    }

    private companion object {
        const val LAST_CHECK = "lastCheck"
        const val DISMISSED = "dismissed"
    }
}

/**
 * A newer KJB's APK through Android, the add-on's way: downloaded into KJB's cache with the models' downloader (the
 * release's size and SHA-256, resumed with HTTP Range, github.com and its asset host only), checked against the KJB
 * running ([isKjbUpdate]), and installed with a PackageInstaller session; Android's word comes back to [InstallReceiver].
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
        return verifiedUpdate(file, apk.sha256, ApkSigning(own.packageName, own.longVersionCode, signers(own)), packages::archiveSigning)
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
        val outcome = InstallReceiver.expect(session, InstallTarget.Kjb)
        try {
            installer.openSession(session).use { open ->
                open.openWrite(INSTALL_NAME, 0, file.length()).use { out ->
                    file.inputStream().use { it.copyTo(out) }
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
            Log.w(LOG_TAG, "update: install not written (${e.javaClass.simpleName})")
            runCatching { installer.abandonSession(session) }
            InstallOutcome.Failed(PackageInstaller.STATUS_FAILURE)
        } catch (e: SecurityException) {
            Log.w(LOG_TAG, "update: install refused (${e.javaClass.simpleName})")
            runCatching { installer.abandonSession(session) }
            InstallOutcome.Failed(PackageInstaller.STATUS_FAILURE_BLOCKED)
        } finally {
            InstallReceiver.forget(session)
        }
    }

    private companion object {
        const val FOLDER = "kjb-update"
        const val APK = "KJB.apk"
        const val INSTALL_NAME = "kjb.apk"
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
        checker ?: UpdateChecker(AndroidUpdatePort(context.applicationContext), BuildConfig.VERSION_NAME, offeredAddons(KjbAddons.ALL), System::currentTimeMillis, scope)
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
}
