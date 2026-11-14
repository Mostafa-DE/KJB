package app.lyrify.update

import app.lyrify.settings.AboutSettings
import app.lyrify.settings.Settings
import app.lyrify.settings.SettingsJson
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI

/** KJB's update check: GitHub's latest release read, compared, checked and asked at most once a day, against fakes. */
@OptIn(ExperimentalCoroutinesApi::class)
class UpdatesTest {
    private val sha = "AB".repeat(32)

    private fun asset(name: String, size: Long = 81_000_000, digest: String? = "sha256:$sha", state: String = "uploaded", url: String? = null): String {
        val address = url ?: "https://github.com/Mostafa-DE/Lyrify/releases/download/v3.0.1/$name"
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
        val read = Releases.parse(release())!!
        assertEquals("3.0.1", read.version)
        assertEquals(ReleaseApk("https://github.com/Mostafa-DE/Lyrify/releases/download/v3.0.1/KJB-3.0.1.apk", 81_000_000, sha.lowercase()), read.apk)
        assertEquals("New\n• Made-up change one\n• Made-up change two", read.notes)
    }

    @Test
    fun `a draft, a pre-release, a tag that is no version or text that is no release is no release`() {
        assertNull(Releases.parse(release(draft = true)))
        assertNull(Releases.parse(release(prerelease = true)))
        assertNull(Releases.parse(release(tag = "nightly")))
        assertNull(Releases.parse("<html>"))
        assertNull(Releases.parse("[]"))
    }

    @Test
    fun `an APK KJB cannot check is no APK to install`() {
        fun apkOf(vararg assets: String) = Releases.parse(release(assets = assets.toList()))!!.apk
        assertNull("no SHA-256 published", apkOf(asset("KJB-3.0.1.apk", digest = null)))
        assertNull("another digest", apkOf(asset("KJB-3.0.1.apk", digest = "sha512:$sha")))
        assertNull("too large", apkOf(asset("KJB-3.0.1.apk", size = Releases.MAX_APK_BYTES + 1)))
        assertNull("empty", apkOf(asset("KJB-3.0.1.apk", size = 0)))
        assertNull("still uploading", apkOf(asset("KJB-3.0.1.apk", state = "open")))
        assertNull("not over https", apkOf(asset("KJB-3.0.1.apk", url = "http://github.com/Mostafa-DE/Lyrify/releases/download/v3.0.1/KJB-3.0.1.apk")))
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

    @Test
    fun `the add-on's version comes from the file this KJB pins`() {
        assertEquals("3.0.0", Releases.pinnedVersion("https://github.com/Mostafa-DE/Lyrify/releases/download/v3.0.0/KJB-AI-3.0.0.apk"))
        assertNull(Releases.pinnedVersion("https://github.com/Mostafa-DE/Lyrify/releases/download/v3.0.0/other.apk"))
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

    // Once a day

    @Test
    fun `the check is due when on and never done, or a day after the last one`() {
        val now = 10 * DAY
        assertFalse(checkDue(enabled = false, lastCheck = null, now = now))
        assertTrue(checkDue(enabled = true, lastCheck = null, now = now))
        assertFalse(checkDue(enabled = true, lastCheck = now - HOUR, now = now))
        assertTrue(checkDue(enabled = true, lastCheck = now - DAY, now = now))
        assertTrue("the clock went back", checkDue(enabled = true, lastCheck = now + HOUR, now = now))
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
        return GitHubLatest("Mostafa-DE/Lyrify", "KJB/test") { uri -> FakeConnection(uri, queue.removeAt(0)).also { opened += it } }
    }

    @Test
    fun `the latest release is asked of GitHub's API with KJB's name`() {
        val fetched = github(Answer(200, release())).fetch()

        assertEquals("3.0.1", ((fetched as Fetched.Answer).release)?.version)
        val request = opened.single()
        assertEquals("https://api.github.com/repos/Mostafa-DE/Lyrify/releases/latest", request.url.toString())
        assertEquals("KJB/test", request.headers["User-Agent"])
        assertEquals("application/vnd.github+json", request.headers["Accept"])
    }

    @Test
    fun `no release yet is an answer, a busy or refusing server is none`() {
        assertEquals(Fetched.Answer(null), github(Answer(404)).fetch())
        assertEquals(Fetched.NoAnswer, github(Answer(403)).fetch())
        assertEquals(Fetched.NoAnswer, github(Answer(200, "not json")).fetch())
        assertEquals(Fetched.NoAnswer, GitHubLatest("Mostafa-DE/Lyrify", "KJB/test") { throw IOException("offline") }.fetch())
    }

    @Test
    fun `a renamed repository's redirect within GitHub's API is followed, any other is refused`() {
        val moved = github(Answer(301, location = "https://api.github.com/repositories/1/releases/latest"), Answer(200, release())).fetch()
        assertEquals("3.0.1", (moved as Fetched.Answer).release?.version)

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
        var addon: String? = null

        override suspend fun latest(): Fetched {
            fetches++
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

        override fun addonInstalled(): String? = addon
    }

    private var now = 100 * DAY
    private val newer = Releases.parse(release())!!

    private fun TestScope.checker(port: FakePort, installed: String = "3.0.0", addonOffered: String? = null) =
        UpdateChecker(port, installed, addonOffered, { now }, backgroundScope)

    @Test
    fun `opening KJB checks at most once a day`() = runTest {
        val port = FakePort(Fetched.Answer(newer))
        val checker = checker(port)

        checker.checkIfDue(enabled = true)
        runCurrent()
        now += HOUR
        checker.checkIfDue(enabled = true)
        runCurrent()
        assertEquals(1, port.fetches)

        now += DAY
        checker.checkIfDue(enabled = true)
        runCurrent()
        assertEquals(2, port.fetches)
    }

    @Test
    fun `with the setting off, opening KJB asks nothing`() = runTest {
        val port = FakePort(Fetched.Answer(newer))
        checker(port).checkIfDue(enabled = false)
        runCurrent()
        assertEquals(0, port.fetches)
    }

    @Test
    fun `a check without an answer still counts for the day`() = runTest {
        val port = FakePort(Fetched.NoAnswer)
        val checker = checker(port)

        checker.checkIfDue(enabled = true)
        runCurrent()
        checker.checkIfDue(enabled = true)
        runCurrent()

        assertEquals(1, port.fetches)
        assertEquals(UpdateState.Failed, checker.state.value)
    }

    @Test
    fun `a newer KJB is offered once, after Not now the daily check keeps quiet and Check now still shows it`() = runTest {
        val port = FakePort(Fetched.Answer(newer))
        val checker = checker(port)

        checker.checkIfDue(enabled = true)
        runCurrent()
        assertEquals(UpdateState.Checked(newer, addon = null, ask = true), checker.state.value)

        checker.dismiss()
        runCurrent()
        assertEquals(UpdateState.Checked(newer, addon = null, ask = false), checker.state.value)
        now += DAY
        checker.checkIfDue(enabled = true)
        runCurrent()
        assertEquals(UpdateState.Checked(newer, addon = null, ask = false), checker.state.value)

        assertEquals(UpdateState.Checked(newer, addon = null, ask = true), checker.checkNow())
    }

    @Test
    fun `the same or an older release offers nothing`() = runTest {
        val port = FakePort(Fetched.Answer(newer))
        assertEquals(UpdateState.Checked(null, addon = null, ask = false), checker(port, installed = "3.0.1").checkNow())
        assertEquals(UpdateState.Checked(null, addon = null, ask = false), checker(FakePort(Fetched.Answer(null))).checkNow())
    }

    @Test
    fun `an installed add-on older than the one this KJB brings is offered too`() = runTest {
        val port = FakePort(Fetched.Answer(null)).apply { addon = "3.0.0" }
        assertEquals(UpdateState.Checked(null, addon = "3.0.1", ask = true), checker(port, installed = "3.0.1", addonOffered = "3.0.1").checkNow())

        port.addon = "3.0.1"
        assertEquals(UpdateState.Checked(null, addon = null, ask = false), checker(port, installed = "3.0.1", addonOffered = "3.0.1").checkNow())
        port.addon = null
        assertEquals(UpdateState.Checked(null, addon = null, ask = false), checker(port, installed = "3.0.1", addonOffered = "3.0.1").checkNow())
    }

    @Test
    fun `Check now without an answer fails, and counts for the day`() = runTest {
        val port = FakePort(Fetched.NoAnswer)
        val checker = checker(port)

        assertEquals(UpdateState.Failed, checker.checkNow())
        checker.checkIfDue(enabled = true)
        runCurrent()

        assertEquals(1, port.fetches)
    }

    // The setting and the privacy line

    @Test
    fun `Check for updates is on by default, and kept in settings_json when switched off`() {
        assertTrue(Settings().about.checkForUpdates)
        val off = Settings().copy(about = AboutSettings(checkForUpdates = false))
        assertEquals(off, SettingsJson.decode(SettingsJson.encode(off)))
        assertTrue(SettingsJson.decode("{\"version\":4}")!!.about.checkForUpdates)
    }

    @Test
    fun `the privacy line says KJB asks github_com once a day`() {
        val strings = File("src/main/res/values/strings.xml").readText()
        val privacy = Regex("""<string name="setup_privacy">(.*?)</string>""").find(strings)!!.groupValues[1]
        assertTrue(privacy, "github.com once a day" in privacy)
    }

    private companion object {
        const val HOUR: Long = 60 * 60_000L
        const val DAY: Long = 24 * HOUR
    }
}
