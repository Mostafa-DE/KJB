package app.lyrify.addon

import app.lyrify.models.Download
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
    fun `a second tap while it works starts nothing more`() = runTest {
        phone.downloadWaits = CompletableDeferred()
        val installer = installer()

        installer.start()
        installer.start()
        runCurrent()

        assertEquals(listOf("download"), phone.calls)
    }
}
