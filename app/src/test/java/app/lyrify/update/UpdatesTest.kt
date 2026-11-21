package app.lyrify.update

import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import app.lyrify.addon.AddonApk
import app.lyrify.addon.ApkSigning
import app.lyrify.addon.InstallOutcome
import app.lyrify.addon.InstallVerdict
import app.lyrify.addon.KjbAddon
import app.lyrify.addon.KjbAddons
import app.lyrify.addon.archiveSigningFlags
import app.lyrify.addon.confirmFrom
import app.lyrify.addon.signerDigests
import app.lyrify.settings.Settings
import app.lyrify.settings.SettingsJson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

/** KJB's update check: GitHub's latest release read, compared, checked and asked at most once an hour, against fakes. */
@OptIn(ExperimentalCoroutinesApi::class)
class UpdatesTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val sha = "AB".repeat(32)

    private fun asset(name: String, size: Long = 81_000_000, digest: String? = "sha256:$sha", state: String = "uploaded", url: String? = null): String {
        val address = url ?: "https://github.com/Mostafa-DE/KJB/releases/download/v3.0.1/$name"
        val digestJson = digest?.let { "\"$it\"" } ?: "null"
        return """{"name":"$name","size":$size,"state":"$state","digest":$digestJson,"browser_download_url":"$address","content_type":"application/vnd.android.package-archive"}"""
    }

    private fun release(tag: String = "v3.0.1", assets: List<String> = listOf(asset("KJB-AI-3.0.1.apk"), asset("KJB-3.0.1.apk")), draft: Boolean = false, prerelease: Boolean = false) =
        """{"tag_name":"$tag","name":"KJB $tag","draft":$draft,"prerelease":$prerelease,"body":"## New\n- Made-up change one\n* Made-up change two","assets":[${assets.joinToString(",")}],"future":1}"""

    // Versions

    @Test
    fun `a version is its numbers, with a v before and a suffix after left out`() {
        assertEquals(listOf(3, 0, 1), Releases.version("v3.0.1"))
        assertEquals(listOf(3, 0, 0), Releases.version("3.0.0-dev"))
        assertEquals(listOf(3), Releases.version("3"))
        assertNull(Releases.version("release"))
        assertNull(Releases.version(""))
        assertNull(Releases.version("v"))
    }

    @Test
    fun `only a higher version is newer, number by number`() {
        assertTrue(Releases.newer("3.0.1", "3.0.0"))
        assertTrue(Releases.newer("3.0.10", "3.0.9"))
        assertTrue(Releases.newer("3.1", "3.0.9"))
        assertTrue(Releases.newer("v4.0.0", "3.0.0-dev"))
        assertFalse(Releases.newer("3.0", "3.0.0"))
        assertFalse(Releases.newer("3.0.0", "3.0.0-dev"))
        assertFalse(Releases.newer("2.9.9", "3.0.0"))
        assertFalse(Releases.newer("garbage", "3.0.0"))
        assertFalse(Releases.newer("4.0.0", "garbage"))
    }

    // The release

    @Test
    fun `the latest release is read with KJB's own APK, its size and its SHA-256`() {
        val read = Releases.parse(release(), REPO)!!
        assertEquals("3.0.1", read.version)
        assertEquals(ReleaseApk("https://github.com/Mostafa-DE/KJB/releases/download/v3.0.1/KJB-3.0.1.apk", 81_000_000, sha.lowercase()), read.apk)
        assertEquals("New\n• Made-up change one\n• Made-up change two", read.notes)
    }

    @Test
    fun `a draft, a pre-release, a tag that is no version or text that is no release is no release`() {
        assertNull(Releases.parse(release(draft = true), REPO))
        assertNull(Releases.parse(release(prerelease = true), REPO))
        assertNull(Releases.parse(release(tag = "nightly"), REPO))
        assertNull(Releases.parse("<html>", REPO))
        assertNull(Releases.parse("[]", REPO))
    }

    @Test
    fun `an APK KJB cannot check is no APK to install`() {
        fun apkOf(vararg assets: String) = Releases.parse(release(assets = assets.toList()), REPO)!!.apk
        assertNull("another repository's", apkOf(asset("KJB-3.0.1.apk", url = "https://github.com/Someone/Else/releases/download/v3.0.1/KJB-3.0.1.apk")))
        assertNull("not a release download", apkOf(asset("KJB-3.0.1.apk", url = "https://github.com/Mostafa-DE/KJB/raw/main/releases/download/KJB-3.0.1.apk")))
        assertNull("no SHA-256 published", apkOf(asset("KJB-3.0.1.apk", digest = null)))
        assertNull("another digest", apkOf(asset("KJB-3.0.1.apk", digest = "sha512:$sha")))
        assertNull("too large", apkOf(asset("KJB-3.0.1.apk", size = Releases.MAX_APK_BYTES + 1)))
        assertNull("empty", apkOf(asset("KJB-3.0.1.apk", size = 0)))
        assertNull("still uploading", apkOf(asset("KJB-3.0.1.apk", state = "open")))
        assertNull("not over https", apkOf(asset("KJB-3.0.1.apk", url = "http://github.com/Mostafa-DE/KJB/releases/download/v3.0.1/KJB-3.0.1.apk")))
        assertNull("not GitHub's", apkOf(asset("KJB-3.0.1.apk", url = "https://example.com/KJB-3.0.1.apk")))
        assertNull("another version's", apkOf(asset("KJB-3.0.0.apk")))
        assertNull("the add-on only", apkOf(asset("KJB-AI-3.0.1.apk")))
    }

    @Test
    fun `a published digest is a SHA-256 in hex or nothing`() {
        assertEquals(sha.lowercase(), Releases.sha256("sha256:$sha"))
        assertNull(Releases.sha256("sha256:abc"))
        assertNull(Releases.sha256("sha256:" + "zz".repeat(32)))
        assertNull(Releases.sha256(null))
    }

    private fun pin(stem: String, file: String, sha256: String = sha, bytes: Long = 54_000_000) =
        AddonApk(stem, "https://github.com/Mostafa-DE/KJB/releases/download/v3.0.1/$file", sha256, bytes)

    @Test
    fun `an add-on's version comes from the file this KJB pins for it`() {
        assertEquals("3.0.0", Releases.pinnedVersion(pin("KJB-AI", "KJB-AI-3.0.0.apk")))
        assertEquals("3.0.1", Releases.pinnedVersion(pin("KJB-Downloads", "KJB-Downloads-3.0.1.apk")))
        assertNull(Releases.pinnedVersion(pin("KJB-AI", "other.apk")))
        assertNull("the other add-on's file", Releases.pinnedVersion(pin("KJB-Downloads", "KJB-AI-3.0.1.apk")))
        assertNull("KJB's own", Releases.pinnedVersion(pin("KJB-Downloads", "KJB-3.0.1.apk")))
    }

    @Test
    fun `each add-on whose file this KJB pins is offered at that file's version, in order`() {
        val ai = KjbAddons.AI.copy(apk = pin("KJB-AI", "KJB-AI-3.0.1.apk"))
        val downloads = KjbAddons.DOWNLOADS.copy(apk = pin("KJB-Downloads", "KJB-Downloads-3.0.2.apk"))
        assertEquals(listOf(AddonUpdate(ai, "3.0.1"), AddonUpdate(downloads, "3.0.2")), offeredAddons(listOf(ai, downloads)))

        // The placeholders a build keeps until the release pins the file: nothing to offer.
        val noSha = downloads.copy(apk = pin("KJB-Downloads", "KJB-Downloads-3.0.2.apk", sha256 = ""))
        val noSize = downloads.copy(apk = pin("KJB-Downloads", "KJB-Downloads-3.0.2.apk", bytes = 0))
        val noVersion = downloads.copy(apk = pin("KJB-Downloads", "downloads.apk"))
        assertEquals(listOf(AddonUpdate(ai, "3.0.1")), offeredAddons(listOf(ai, noSha, noSize, noVersion)))
    }

    // The APK's signing

    @Test
    fun `an update is KJB's own package, a higher version code and exactly KJB's signing certificates`() {
        val own = ApkSigning("app.lyrify", 10, setOf("aa"))
        assertTrue(isKjbUpdate(own, ApkSigning("app.lyrify", 11, setOf("aa"))))
        assertFalse("not read", isKjbUpdate(own, null))
        assertFalse("another app", isKjbUpdate(own, ApkSigning("app.other", 11, setOf("aa"))))
        assertFalse("the same version", isKjbUpdate(own, ApkSigning("app.lyrify", 10, setOf("aa"))))
        assertFalse("an older version", isKjbUpdate(own, ApkSigning("app.lyrify", 9, setOf("aa"))))
        assertFalse("another key", isKjbUpdate(own, ApkSigning("app.lyrify", 11, setOf("bb"))))
        assertFalse("another key besides", isKjbUpdate(own, ApkSigning("app.lyrify", 11, setOf("aa", "bb"))))
        assertFalse("unsigned", isKjbUpdate(ApkSigning("app.lyrify", 10, emptySet()), ApkSigning("app.lyrify", 11, emptySet())))
    }

    @Suppress("DEPRECATION")
    @Test
    fun `before Android 13 the archive's certificates are asked for the older way too`() {
        assertEquals(PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES, archiveSigningFlags(32))
        assertEquals(PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES, archiveSigningFlags(30))
        assertEquals(PackageManager.GET_SIGNING_CERTIFICATES, archiveSigningFlags(33))
    }

    @Test
    fun `the signers are the current certificates, else the older field's, as SHA-256`() {
        val a = byteArrayOf(1, 2)
        val b = byteArrayOf(3)
        val aHex = MessageDigest.getInstance("SHA-256").digest(a).joinToString("") { "%02x".format(it) }
        val bHex = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
        assertEquals(setOf(aHex), signerDigests(current = listOf(a), legacy = listOf(b)))
        assertEquals("no signing info: the signatures field", setOf(bHex), signerDigests(current = null, legacy = listOf(b)))
        assertEquals(setOf(bHex), signerDigests(current = emptyList(), legacy = listOf(b)))
        assertEquals(emptySet<String>(), signerDigests(current = null, legacy = null))
    }

    @Test
    fun `an APK is checked whole against the release's SHA-256 before its signing, downloaded now or before`() {
        val file = temp.newFile("KJB.apk").apply { writeBytes(byteArrayOf(7, 7, 7)) }
        val right = MessageDigest.getInstance("SHA-256").digest(byteArrayOf(7, 7, 7)).joinToString("") { "%02x".format(it) }
        val own = ApkSigning("app.lyrify", 10, setOf("aa"))
        var read = 0
        val update: (File) -> ApkSigning? = { read++; ApkSigning("app.lyrify", 11, setOf("aa")) }

        assertFalse("same size, other bytes", verifiedUpdate(file, "00".repeat(32), own, update))
        assertEquals("the signing is not even read", 0, read)
        assertTrue(verifiedUpdate(file, right, own, update))
        assertTrue(verifiedUpdate(file, right.uppercase(), own, update))
        assertFalse("signed by another key", verifiedUpdate(file, right, own) { ApkSigning("app.lyrify", 11, setOf("bb")) })
        assertFalse("gone", verifiedUpdate(File(temp.root, "gone.apk"), right, own, update))
    }

    // One update at a time

    @Test
    fun `one update install at a time, the one before cancelled when another release is asked for`() {
        val cancelled = mutableListOf<String>()
        val made = mutableListOf<String>()
        val one = SingleInstall(make = { key: String -> made += key; "installer $key" }, cancel = { cancelled += it })

        assertEquals("installer a", one.get("a"))
        assertEquals("installer a", one.get("a"))
        assertEquals("installer b", one.get("b"))

        assertEquals(listOf("a", "b"), made)
        assertEquals(listOf("installer a"), cancelled)
    }

    // The confirmation

    @Test
    fun `the install dialog opens only when the phone's own installer asks`() {
        val asks = InstallVerdict.Confirm("com.example.installer", 7)
        assertEquals(asks, confirmFrom(asks) { it == "com.example.installer" })
        assertEquals(InstallVerdict.End(InstallOutcome.Failed(PackageInstaller.STATUS_FAILURE)), confirmFrom(asks) { false })
        val ends = InstallVerdict.End(InstallOutcome.Installed)
        assertEquals(ends, confirmFrom(ends) { false })
    }

    // Every hour

    @Test
    fun `the check is due when never done, or an hour after the last one`() {
        val now = 10 * DAY
        assertTrue(checkDue(lastCheck = null, now = now))
        assertFalse(checkDue(lastCheck = now - MINUTE, now = now))
        assertFalse(checkDue(lastCheck = now - HOUR + 1, now = now))
        assertTrue(checkDue(lastCheck = now - HOUR, now = now))
        assertTrue(checkDue(lastCheck = now - DAY, now = now))
        assertTrue("the clock went back", checkDue(lastCheck = now + MINUTE, now = now))
    }

    // GitHub's answer

    private class Answer(val code: Int, val body: String = "", val location: String? = null)

    private class FakeConnection(uri: URI, private val answer: Answer) : HttpURLConnection(uri.toURL()) {
        val headers = mutableMapOf<String, String>()

        override fun setRequestProperty(key: String, value: String) {
            headers[key] = value
        }

        override fun getResponseCode(): Int = answer.code

        override fun getHeaderField(name: String): String? = if (name.equals("Location", ignoreCase = true)) answer.location else null

        override fun getInputStream(): InputStream = ByteArrayInputStream(answer.body.toByteArray())

        override fun getContentLengthLong(): Long = answer.body.length.toLong()

        override fun disconnect() = Unit

        override fun usingProxy(): Boolean = false

        override fun connect() = Unit
    }

    private val opened = mutableListOf<FakeConnection>()

    private fun github(vararg answers: Answer): GitHubLatest {
        val queue = answers.toMutableList()
        return GitHubLatest("Mostafa-DE/KJB", "KJB/test") { uri -> FakeConnection(uri, queue.removeAt(0)).also { opened += it } }
    }

    @Test
    fun `the latest release is asked of GitHub's API with KJB's name`() {
        val fetched = github(Answer(200, release())).fetch()

        assertEquals("3.0.1", (fetched as Fetched.Answer).release.version)
        val request = opened.single()
        assertEquals("https://api.github.com/repos/Mostafa-DE/KJB/releases/latest", request.url.toString())
        assertEquals("KJB/test", request.headers["User-Agent"])
        assertEquals("application/vnd.github+json", request.headers["Accept"])
    }

    @Test
    fun `a missing repository or release is no answer, as a busy or refusing server is, never up to date`() {
        // A renamed or missing repository, or one with no release yet, must not read as "KJB is the latest" for good.
        assertEquals(Fetched.NoAnswer, github(Answer(404)).fetch())
        assertEquals(Fetched.NoAnswer, github(Answer(403)).fetch())
        // GitHub's limit for asking without an account (60 an hour from one address) answers 403 or 429.
        assertEquals(Fetched.NoAnswer, github(Answer(429)).fetch())
        assertEquals(Fetched.NoAnswer, github(Answer(200, "not json")).fetch())
        assertEquals(Fetched.NoAnswer, GitHubLatest("Mostafa-DE/KJB", "KJB/test") { throw IOException("offline") }.fetch())
    }

    @Test
    fun `a renamed repository's redirect within GitHub's API is followed, any other is refused`() {
        val moved = github(Answer(301, location = "https://api.github.com/repositories/1/releases/latest"), Answer(200, release())).fetch()
        assertEquals("3.0.1", (moved as Fetched.Answer).release.version)

        opened.clear()
        assertEquals(Fetched.NoAnswer, github(Answer(302, location = "https://example.com/latest")).fetch())
        assertEquals(1, opened.size)
        opened.clear()
        assertEquals(Fetched.NoAnswer, github(Answer(302, location = "http://api.github.com/repositories/1/releases/latest")).fetch())
        assertEquals(1, opened.size)
    }

    @Test
    fun `an answer larger than a release can be is not read`() {
        val huge = release().dropLast(1) + ",\"pad\":\"" + "x".repeat(GitHubLatest.MAX_ANSWER_BYTES) + "\"}"
        assertEquals(Fetched.NoAnswer, github(Answer(200, huge)).fetch())
    }

    // The checker

    private class FakePort(var answer: Fetched) : UpdatePort {
        var fetches = 0
        var last: Long? = null
        var dismissedOffer: String? = null

        /** GitHub answers once this completes; null: at once. */
        var answers: CompletableDeferred<Unit>? = null

        /** The version of each add-on installed that is KJB's own. */
        val addons = mutableMapOf<KjbAddon, String>()

        override suspend fun latest(): Fetched {
            fetches++
            answers?.await()
            return answer
        }

        override suspend fun lastCheck(): Long? = last

        override suspend fun setLastCheck(at: Long) {
            last = at
        }

        override suspend fun dismissed(): String? = dismissedOffer

        override suspend fun setDismissed(offer: String) {
            dismissedOffer = offer
        }

        override fun addonInstalled(addon: KjbAddon): String? = addons[addon]
    }

    private var now = 100 * DAY
    private val newer = Releases.parse(release(), REPO)!!

    /** GitHub's latest release, no newer than the KJB of these tests (3.0.0 or 3.0.1). */
    private val older = Releases.parse(release(tag = "v3.0.0"), REPO)!!

    /** The checker's clock: [now], moved on by the test's own time as KJB stays in front. */
    private fun TestScope.checker(port: FakePort, installed: String = "3.0.0", addonsOffered: List<AddonUpdate> = emptyList()) =
        UpdateChecker(port, installed, addonsOffered, { now + testScheduler.currentTime }, backgroundScope)

    /** KJB comes to the front, and goes to the back again before an hour is up. */
    private fun TestScope.cameToFront(checker: UpdateChecker) {
        val front = checker.checkWhileInFront()
        runCurrent()
        front.cancel()
    }

    private val none = emptyList<AddonUpdate>()
    private val ai = KjbAddons.AI
    private val downloads = KjbAddons.DOWNLOADS

    @Test
    fun `coming to the front checks at most once an hour`() = runTest {
        val port = FakePort(Fetched.Answer(newer))
        val checker = checker(port)

        cameToFront(checker)
        now += 59 * MINUTE
        cameToFront(checker)
        assertEquals(1, port.fetches)

        now += MINUTE
        cameToFront(checker)
        assertEquals(2, port.fetches)
    }

    @Test
    fun `while KJB stays in front it checks again each hour, and stops when it goes`() = runTest {
        val port = FakePort(Fetched.Answer(older))
        val checker = checker(port)

        val front = checker.checkWhileInFront()
        runCurrent()
        assertEquals(1, port.fetches)
        advanceTimeBy(HOUR - 1)
        runCurrent()
        assertEquals(1, port.fetches)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, port.fetches)
        advanceTimeBy(HOUR)
        runCurrent()
        assertEquals(3, port.fetches)

        front.cancel()
        advanceTimeBy(3 * HOUR)
        runCurrent()
        assertEquals(3, port.fetches)
    }

    @Test
    fun `coming to the front after a recent check waits only for the rest of its hour`() = runTest {
        val port = FakePort(Fetched.Answer(older)).apply { last = now - 40 * MINUTE }
        val checker = checker(port)

        checker.checkWhileInFront()
        runCurrent()
        assertEquals(0, port.fetches)
        advanceTimeBy(20 * MINUTE - 1)
        runCurrent()
        assertEquals(0, port.fetches)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, port.fetches)
    }

    @Test
    fun `Check now works at any time, and the check in front waits an hour after it`() = runTest {
        val port = FakePort(Fetched.Answer(older))
        val checker = checker(port)

        checker.checkWhileInFront()
        runCurrent()
        advanceTimeBy(30 * MINUTE)
        checker.checkNow()
        assertEquals(2, port.fetches)

        advanceTimeBy(30 * MINUTE)
        runCurrent()
        assertEquals("an hour after the first check, half an hour after Check now", 2, port.fetches)
        advanceTimeBy(30 * MINUTE)
        runCurrent()
        assertEquals(3, port.fetches)
    }

    @Test
    fun `a check under way when KJB goes to the back still finishes, and shows what it found`() = runTest {
        val port = FakePort(Fetched.Answer(newer)).apply { answers = CompletableDeferred() }
        val checker = checker(port)

        val front = checker.checkWhileInFront()
        runCurrent()
        front.cancel()
        port.answers!!.complete(Unit)
        runCurrent()

        assertEquals(UpdateState.Checked(newer, addons = none, ask = true), checker.state.value)
    }

    @Test
    fun `two ways into the front at once still ask once an hour`() = runTest {
        val port = FakePort(Fetched.Answer(older))
        val checker = checker(port)

        checker.checkWhileInFront()
        checker.checkWhileInFront()
        runCurrent()
        assertEquals(1, port.fetches)
        advanceTimeBy(HOUR)
        runCurrent()
        assertEquals(2, port.fetches)
    }

    @Test
    fun `the check in front is quiet, no answer (GitHub's rate limit too) keeps what it last found and the sheet open`() = runTest {
        val port = FakePort(Fetched.Answer(newer))
        val checker = checker(port)
        val states = mutableListOf<UpdateState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { checker.state.toList(states) }

        cameToFront(checker)
        val offered = UpdateState.Checked(newer, addons = none, ask = true)
        assertEquals(offered, checker.state.value)

        port.answer = Fetched.NoAnswer
        now += HOUR
        cameToFront(checker)
        assertEquals(2, port.fetches)
        assertEquals(offered, checker.state.value)

        port.answer = Fetched.Answer(newer)
        now += HOUR
        cameToFront(checker)
        assertEquals(3, port.fetches)
        // Never "Checking" or "Couldn't check" in between: the update sheet would close under the user, even mid-download.
        assertEquals(listOf(UpdateState.Idle, offered), states)
    }

    @Test
    fun `a check without an answer still counts for the hour`() = runTest {
        val port = FakePort(Fetched.NoAnswer)
        val checker = checker(port)

        cameToFront(checker)
        cameToFront(checker)

        assertEquals(1, port.fetches)
        assertEquals(UpdateState.Failed, checker.state.value)
    }

    @Test
    fun `a newer KJB is offered once, after Not now the hourly check keeps quiet and Check now still shows it`() = runTest {
        val port = FakePort(Fetched.Answer(newer))
        val checker = checker(port)

        cameToFront(checker)
        assertEquals(UpdateState.Checked(newer, addons = none, ask = true), checker.state.value)

        checker.dismiss()
        runCurrent()
        assertEquals(UpdateState.Checked(newer, addons = none, ask = false), checker.state.value)
        now += HOUR
        cameToFront(checker)
        assertEquals(2, port.fetches)
        assertEquals(UpdateState.Checked(newer, addons = none, ask = false), checker.state.value)

        assertEquals(UpdateState.Checked(newer, addons = none, ask = true), checker.checkNow())
    }

    @Test
    fun `the same or an older release offers nothing`() = runTest {
        val port = FakePort(Fetched.Answer(newer))
        assertEquals(UpdateState.Checked(null, addons = none, ask = false), checker(port, installed = "3.0.1").checkNow())
        assertEquals(UpdateState.Checked(null, addons = none, ask = false), checker(FakePort(Fetched.Answer(older))).checkNow())
    }

    @Test
    fun `an installed add-on older than the one this KJB brings is offered too`() = runTest {
        val offered = listOf(AddonUpdate(ai, "3.0.1"))
        val port = FakePort(Fetched.Answer(older)).apply { addons[ai] = "3.0.0" }
        assertEquals(UpdateState.Checked(null, addons = offered, ask = true), checker(port, installed = "3.0.1", addonsOffered = offered).checkNow())

        port.addons[ai] = "3.0.1"
        assertEquals(UpdateState.Checked(null, addons = none, ask = false), checker(port, installed = "3.0.1", addonsOffered = offered).checkNow())
        port.addons.remove(ai)
        assertEquals(UpdateState.Checked(null, addons = none, ask = false), checker(port, installed = "3.0.1", addonsOffered = offered).checkNow())
    }

    @Test
    fun `the Downloads add-on is offered the AI add-on's way, each by its own installed version`() = runTest {
        val offered = listOf(AddonUpdate(ai, "3.0.1"), AddonUpdate(downloads, "3.0.1"))
        val port = FakePort(Fetched.Answer(older)).apply {
            addons[ai] = "3.0.1"
            addons[downloads] = "3.0.0-dev"
        }
        assertEquals(UpdateState.Checked(null, addons = listOf(AddonUpdate(downloads, "3.0.1")), ask = true), checker(port, installed = "3.0.1", addonsOffered = offered).checkNow())

        port.addons[ai] = "3.0.0"
        assertEquals(UpdateState.Checked(null, addons = offered, ask = true), checker(port, installed = "3.0.1", addonsOffered = offered).checkNow())

        // Not installed, or not KJB's (the port reads no version for an add-on signed with another key): nothing to update.
        port.addons.remove(downloads)
        assertEquals(UpdateState.Checked(null, addons = listOf(AddonUpdate(ai, "3.0.1")), ask = true), checker(port, installed = "3.0.1", addonsOffered = offered).checkNow())
    }

    @Test
    fun `Not now puts off the add-ons offered, and another add-on's update asks again`() = runTest {
        val offered = listOf(AddonUpdate(ai, "3.0.1"), AddonUpdate(downloads, "3.0.1"))
        val port = FakePort(Fetched.Answer(older)).apply { addons[ai] = "3.0.0" }
        val checker = checker(port, installed = "3.0.1", addonsOffered = offered)

        cameToFront(checker)
        checker.dismiss()
        runCurrent()
        now += HOUR
        cameToFront(checker)
        assertEquals(UpdateState.Checked(null, addons = listOf(AddonUpdate(ai, "3.0.1")), ask = false), checker.state.value)

        port.addons[downloads] = "3.0.0"
        now += HOUR
        cameToFront(checker)
        assertEquals(UpdateState.Checked(null, addons = offered, ask = true), checker.state.value)
    }

    @Test
    fun `Check now without an answer fails, and counts for the hour`() = runTest {
        val port = FakePort(Fetched.NoAnswer)
        val checker = checker(port)

        assertEquals(UpdateState.Failed, checker.checkNow())
        cameToFront(checker)

        assertEquals(1, port.fetches)
    }

    // No switch, and the privacy line

    @Test
    fun `the check has no switch, an old settings_json with it off reads as the defaults and nothing of it is written`() {
        // 3.1 (user): the check is always on; a switch turned off before is left behind in the file and never read.
        assertEquals(Settings(), SettingsJson.decode("""{"version":4,"about":{"checkForUpdates":false}}"""))
        assertFalse("checkForUpdates" in SettingsJson.encode(Settings()))
    }

    @Test
    fun `the privacy line says KJB asks github_com every hour, and Settings has Check for updates and no switch`() {
        val strings = File("src/main/res/values/strings.xml").readText()
        val privacy = Regex("""<string name="setup_privacy">(.*?)</string>""").find(strings)!!.groupValues[1]
        assertTrue(privacy, "github.com every hour" in privacy)
        assertFalse(privacy, "turn that off" in privacy)
        val system = File("src/main/res/values/strings_system.xml").readText()
        assertEquals("Check for updates", Regex("""<string name="set_updates_now">(.*?)</string>""").find(system)!!.groupValues[1])
        assertFalse("name=\"set_updates_detail\"" in system)
    }

    private companion object {
        const val REPO: String = "Mostafa-DE/KJB"
        const val MINUTE: Long = 60_000L
        const val HOUR: Long = 60 * MINUTE
        const val DAY: Long = 24 * HOUR
    }
}
