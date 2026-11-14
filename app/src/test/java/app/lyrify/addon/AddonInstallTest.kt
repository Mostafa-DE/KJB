package app.lyrify.addon

import android.content.pm.PackageInstaller
import app.lyrify.models.Download
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Get AI: the add-on's APK downloaded, checked and installed, against a fake phone. */
@OptIn(ExperimentalCoroutinesApi::class)
class AddonInstallTest {
    private class FakePhone : InstallPort {
        val calls = mutableListOf<String>()
        var downloaded: Download = Download.Done
        var progress: List<Long> = listOf(0, 30, 69)
        var verified = true
        var allowed = false
        var outcome = CompletableDeferred<InstallOutcome>()
        var downloadWaits: CompletableDeferred<Unit>? = null
        var onDisk = false

        override suspend fun download(progress: suspend (Long, Long) -> Unit): Download {
            calls += "download"
            this.progress.forEach { progress(it, 69) }
            downloadWaits?.await()
            if (downloaded == Download.Done) onDisk = true
            return downloaded
        }

        override fun verified(): Boolean = verified.also { calls += "verify" }

        override fun discard() {
            calls += "discard"
            onDisk = false
        }

        override fun canInstall(): Boolean = allowed

        override suspend fun install(): InstallOutcome {
            calls += "install"
            return outcome.await()
        }
    }

    private val phone = FakePhone()
    private val states = mutableListOf<InstallState>()

    /** The installer in the test's background scope, with every state it passes through gathered in [states]. */
    private fun TestScope.installer(pinned: Boolean = true) = AddonInstaller(phone, pinned, backgroundScope).also { installer ->
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { installer.state.collect { states += it } }
    }

    @Test
    fun `a build without a pinned add-on downloads nothing and says so`() = runTest {
        val installer = installer(pinned = false)

        installer.start()
        runCurrent()

        assertEquals(InstallState.Failed(InstallFailure.NOT_PUBLISHED), installer.state.value)
        assertTrue(phone.calls.isEmpty())
    }

    @Test
    fun `downloads with its progress, checks the file, then asks to allow installs from KJB`() = runTest {
        val installer = installer()

        installer.start()
        runCurrent()

        assertTrue(states.toString(), InstallState.Downloading(30, 69) in states)
        assertEquals(InstallState.NeedsPermission, installer.state.value)
        assertEquals(listOf("download", "verify"), phone.calls)
    }

    @Test
    fun `back from the phone's settings with installs allowed, it installs, and the file goes once installed`() = runTest {
        val installer = installer()
        installer.start()
        runCurrent()

        installer.resume() // not allowed yet: stays
        runCurrent()
        assertEquals(InstallState.NeedsPermission, installer.state.value)
        phone.allowed = true
        installer.resume()
        runCurrent()
        assertEquals(InstallState.Installing, installer.state.value)

        phone.outcome.complete(InstallOutcome.Installed)
        runCurrent()

        assertEquals(InstallState.Idle, installer.state.value)
        assertEquals(listOf("download", "verify", "download", "verify", "install", "discard"), phone.calls)
    }

    @Test
    fun `installs at once when KJB may already install apps`() = runTest {
        phone.allowed = true
        val installer = installer()

        installer.start()
        runCurrent()

        assertEquals(InstallState.Installing, installer.state.value)
    }

    @Test
    fun `a download that stops is a failure to try again, and the part stays`() = runTest {
        phone.downloaded = Download.Interrupted("connection closed")
        val installer = installer()

        installer.start()
        runCurrent()

        assertEquals(InstallState.Failed(InstallFailure.NETWORK), installer.state.value)
        assertFalse("discard" in phone.calls)
        phone.downloaded = Download.Done
        phone.allowed = true
        installer.start()
        runCurrent()
        assertEquals(InstallState.Installing, installer.state.value)
    }

    @Test
    fun `a file that is not the pinned one is a failure, and nothing is installed`() = runTest {
        phone.downloaded = Download.Failed("checksum mismatch, download deleted")
        val installer = installer()

        installer.start()
        runCurrent()

        assertEquals(InstallState.Failed(InstallFailure.DOWNLOAD), installer.state.value)
        assertFalse("install" in phone.calls)
    }

    @Test
    fun `an APK that is not KJB's add-on (its package or certificate) is deleted, never installed`() = runTest {
        phone.verified = false
        phone.allowed = true
        val installer = installer()

        installer.start()
        runCurrent()

        assertEquals(InstallState.Failed(InstallFailure.NOT_KJBS), installer.state.value)
        assertEquals(listOf("download", "verify", "discard"), phone.calls)
    }

    @Test
    fun `the user saying no in Android's dialog goes back to the start, and the file stays for the next try`() = runTest {
        phone.allowed = true
        val installer = installer()
        installer.start()
        runCurrent()

        phone.outcome.complete(InstallOutcome.Aborted)
        runCurrent()

        assertEquals(InstallState.Idle, installer.state.value)
        assertFalse("discard" in phone.calls)
    }

    @Test
    fun `Android's installer failing is a failure to try again`() = runTest {
        phone.allowed = true
        val installer = installer()
        installer.start()
        runCurrent()

        phone.outcome.complete(InstallOutcome.Failed(status = 4))
        runCurrent()

        assertEquals(InstallState.Failed(InstallFailure.INSTALL), installer.state.value)
    }

    @Test
    fun `Cancel during the download stops it and goes back to the start`() = runTest {
        phone.downloadWaits = CompletableDeferred()
        val installer = installer()
        installer.start()
        runCurrent()
        assertTrue(installer.state.value is InstallState.Downloading)

        installer.cancel()
        runCurrent()

        assertEquals(InstallState.Idle, installer.state.value)
        assertFalse("discard" in phone.calls) // what it got stays, for the next try to go on from
    }

    @Test
    fun `Cancel while Android asks to confirm goes back to the start`() = runTest {
        phone.allowed = true
        val installer = installer()
        installer.start()
        runCurrent()

        installer.cancel()
        runCurrent()

        assertEquals(InstallState.Idle, installer.state.value)
    }

    @Test
    fun `Android never answering does not hang the page, after a while the install is abandoned as a failure`() = runTest {
        phone.allowed = true
        val installer = installer()
        installer.start()
        runCurrent()
        assertEquals(InstallState.Installing, installer.state.value)

        advanceTimeBy(AddonInstaller.CONFIRM_TIMEOUT_MS + 1)
        runCurrent()

        assertEquals(InstallState.Failed(InstallFailure.INSTALL), installer.state.value)
    }

    // What Android's installer reports

    private val ours = 41

    private fun report(
        session: Int = ours,
        status: Int = PackageInstaller.STATUS_PENDING_USER_ACTION,
        packageName: String? = null,
        confirmAction: String? = InstallReport.CONFIRM_INSTALL,
        confirmPackage: String? = "com.android.packageinstaller",
        confirmSession: Int = ours,
    ) = InstallReport(ourSession = ours, session = session, status = status, packageName = packageName, confirmAction = confirmAction, confirmPackage = confirmPackage, confirmSession = confirmSession)

    private val waiting: (Int) -> Boolean = { it == ours }

    @Test
    fun `a report for a session KJB is not waiting on is ignored`() {
        assertEquals(InstallVerdict.Ignore, report(session = 7).verdict(waiting, ADDON))
        assertEquals(InstallVerdict.Ignore, report(session = -1).verdict(waiting, ADDON))
        assertEquals(InstallVerdict.Ignore, report().verdict({ false }, ADDON))
    }

    @Test
    fun `only the installer's own confirmation for this session is started, rebuilt from its action, package and session`() {
        assertEquals(InstallVerdict.Confirm("com.android.packageinstaller", ours), report().verdict(waiting, ADDON))
        // Anything else asking for the user ends the install as failed, so it never waits on a dialog that cannot come.
        val failed = InstallVerdict.End(InstallOutcome.Failed(PackageInstaller.STATUS_FAILURE))
        assertEquals(failed, report(confirmAction = "android.intent.action.VIEW").verdict(waiting, ADDON))
        assertEquals(failed, report(confirmSession = 7).verdict(waiting, ADDON))
        assertEquals(failed, report(confirmPackage = null).verdict(waiting, ADDON))
    }

    @Test
    fun `the installer's ends are the install's, and a result for another package is a failure`() {
        assertEquals(InstallVerdict.End(InstallOutcome.Installed), report(status = PackageInstaller.STATUS_SUCCESS, packageName = ADDON).verdict(waiting, ADDON))
        assertEquals(InstallVerdict.End(InstallOutcome.Aborted), report(status = PackageInstaller.STATUS_FAILURE_ABORTED).verdict(waiting, ADDON))
        assertEquals(InstallVerdict.End(InstallOutcome.Failed(PackageInstaller.STATUS_FAILURE_INVALID)), report(status = PackageInstaller.STATUS_FAILURE_INVALID).verdict(waiting, ADDON))
        assertEquals(
            InstallVerdict.End(InstallOutcome.Failed(PackageInstaller.STATUS_FAILURE)),
            report(status = PackageInstaller.STATUS_SUCCESS, packageName = "com.example.other").verdict(waiting, ADDON),
        )
    }

    @Test
    fun `a second tap while it works starts nothing more`() = runTest {
        phone.downloadWaits = CompletableDeferred()
        val installer = installer()

        installer.start()
        installer.start()
        runCurrent()

        assertEquals(listOf("download"), phone.calls)
    }

    @Test
    fun `a session KJB started for one add-on takes no result for the other`() {
        // The Downloads add-on's session hears an install of the AI add-on's package: a failure, never an install.
        assertEquals(
            InstallVerdict.End(InstallOutcome.Failed(PackageInstaller.STATUS_FAILURE)),
            report(status = PackageInstaller.STATUS_SUCCESS, packageName = ADDON).verdict(waiting, DOWNLOADS),
        )
        assertEquals(InstallVerdict.End(InstallOutcome.Installed), report(status = PackageInstaller.STATUS_SUCCESS, packageName = DOWNLOADS).verdict(waiting, DOWNLOADS))
    }

    // The APK this KJB pins for each add-on

    @Test
    fun `an add-on is pinned only with a whole SHA-256 and a size`() {
        val sha = "0123456789abcdef".repeat(4)
        assertTrue(AddonApk("KJB-Downloads", "https://github.com/x/y/releases/download/v1/KJB-Downloads-1.apk", sha, 54_000_000).pinned)
        assertFalse(AddonApk("KJB-Downloads", "https://github.com/x", "", 54_000_000).pinned)
        assertFalse(AddonApk("KJB-Downloads", "https://github.com/x", sha.drop(1), 54_000_000).pinned)
        assertFalse(AddonApk("KJB-Downloads", "https://github.com/x", sha, 0).pinned)
    }

    @Test
    fun `each add-on's download is kept under a name of its own pin, so neither is taken for the other or for an older one`() {
        val ai = AddonApk("KJB-AI", "https://github.com/x/a.apk", "AB".repeat(32), 70_000_000).spec
        val downloads = AddonApk("KJB-Downloads", "https://github.com/x/d.apk", "AB".repeat(32), 54_000_000).spec
        val newer = AddonApk("KJB-Downloads", "https://github.com/x/d.apk", "CD".repeat(32), 54_000_000).spec

        assertEquals("KJB-Downloads-abababababababab.apk", downloads.name)
        assertEquals("ab".repeat(32), downloads.sha256)
        assertEquals(54_000_000, downloads.bytes)
        assertEquals(3, setOf(ai.name, downloads.name, newer.name).size)
    }

    private companion object {
        const val ADDON = "app.lyrify.ai"
        const val DOWNLOADS = "app.lyrify.dl"
    }
}
