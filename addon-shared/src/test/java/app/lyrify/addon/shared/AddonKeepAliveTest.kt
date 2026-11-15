package app.lyrify.addon.shared

import android.content.pm.ServiceInfo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What keeps an add-on at work, for both add-ons: its own foreground service while it works ([JobForeground]), and the
 * battery exemption it asks for itself, KJB only ([exemptionStep]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AddonKeepAliveTest {
    // A job's beats

    @Test
    fun `a job beats at once, then every 5 s on its own context, until it is stopped`() = runTest {
        var beats = 0
        val beating = backgroundScope.beating(StandardTestDispatcher(testScheduler)) { beats++ }
        runCurrent()
        assertEquals(1, beats)

        advanceTimeBy(60_000)
        runCurrent()
        assertEquals("one at once, then one every 5 s", 1 + (60_000 / AddonKeepAlive.BEAT_EVERY_MS).toInt(), beats)
        beating.cancel()
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(13, beats)
    }

    // The foreground service while a job works

    /** What a tap on the notification opens, as its creator's uid: KJB's PendingIntent on the phone. */
    private data class Tap(val uid: Int)

    private class FakeForeground(private val refuses: Boolean = false) : ForegroundPort<Tap> {
        val calls = mutableListOf<String>()

        override fun startForeground(type: Int, tap: Tap?) {
            val name = when (type) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING -> "mediaProcessing"
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC -> "dataSync"
                else -> "type $type"
            }
            calls += "start $name ${tap?.uid}"
            // ForegroundServiceStartNotAllowedException is one: Android 12+ refusing it now, or Android 15+ after the day's time ran out.
            if (refuses) throw IllegalStateException("not allowed now")
        }

        override fun stopForeground() {
            calls += "stopForeground"
        }

        override fun stopSelf(startId: Int?) {
            calls += "stopSelf ${startId ?: "all"}"
        }
    }

    private val kjb = Tap(KJB_UID)
    private var busy = true
    private var jobsEnded = 0

    private fun foreground(port: FakeForeground) =
        JobForeground(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING, port, busy = { busy }, fromKjb = { it.uid == KJB_UID }, endJob = { jobsEnded++ })

    @Test
    fun `KJB's start while a job runs goes into the foreground at once, of the add-on's type, with KJB's tap`() {
        val port = FakeForeground()
        foreground(port).start(startId = 1, tap = kjb)

        assertEquals(listOf("start mediaProcessing $KJB_UID"), port.calls)
        val dataSync = FakeForeground()
        JobForeground(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, dataSync, busy = { true }, fromKjb = { it.uid == KJB_UID }, endJob = {}).start(1, kjb)
        assertEquals(listOf("start dataSync $KJB_UID"), dataSync.calls)
    }

    @Test
    fun `the foreground is left once no job is left, not while the next one runs`() {
        val port = FakeForeground()
        val foreground = foreground(port)
        foreground.start(1, kjb)

        foreground.jobEnded() // the next song's job already started
        assertEquals(listOf("start mediaProcessing $KJB_UID"), port.calls)
        busy = false
        foreground.jobEnded()
        foreground.jobEnded()

        assertEquals(listOf("start mediaProcessing $KJB_UID", "stopForeground", "stopSelf 1"), port.calls)
        busy = true
        foreground.start(2, kjb) // the next song
        busy = false
        foreground.jobEnded()
        assertEquals(listOf("start mediaProcessing $KJB_UID", "stopForeground", "stopSelf 2"), port.calls.takeLast(3))
    }

    @Test
    fun `every start ends in a defined state, a start while in the foreground needing no answer`() {
        // In the foreground for job 1: a second start (KJB's, its job ended and jobEnded on its way; or anyone's) is
        // one Android asks nothing of, as the service is in the foreground already (AOSP sendServiceArgsLocked).
        for (second in listOf(kjb, Tap(OTHER_UID), null)) {
            val port = FakeForeground()
            val foreground = foreground(port)
            foreground.start(1, kjb)
            busy = false

            foreground.start(2, second)
            assertEquals("$second: still in the foreground, nothing more asked", listOf("start mediaProcessing $KJB_UID"), port.calls)
            foreground.jobEnded()
            assertEquals("$second: out once the job's end comes", listOf("start mediaProcessing $KJB_UID", "stopForeground", "stopSelf 2"), port.calls)
            busy = true
        }
        // Out of the foreground: every start, KJB's or not, job or no job, goes in, and out unless it holds a job of KJB's.
        for ((tap, running) in listOf(kjb to true, kjb to false, Tap(OTHER_UID) to true, null to false)) {
            val port = FakeForeground()
            busy = running
            foreground(port).start(3, tap)
            val holds = tap == kjb && running
            assertEquals("$tap busy=$running", if (holds) 1 else 3, port.calls.size)
            assertTrue("$tap busy=$running", port.calls.first().startsWith("start"))
        }
    }

    @Test
    fun `a start that comes after its job ended goes into the foreground, as Android requires, and out at once`() {
        val port = FakeForeground()
        val foreground = foreground(port)
        busy = false

        foreground.start(3, kjb)
        foreground.jobEnded()
        foreground.unbound()

        assertEquals(listOf("start mediaProcessing $KJB_UID", "stopForeground", "stopSelf 3"), port.calls)
    }

    @Test
    fun `a start from anyone but KJB is refused, answered so that Android does not end the add-on, and shows none of theirs`() {
        val port = FakeForeground()
        val foreground = foreground(port)

        foreground.start(1, Tap(OTHER_UID))
        foreground.start(2, null) // no tap at all
        assertEquals(
            listOf("start mediaProcessing null", "stopForeground", "stopSelf 1", "start mediaProcessing null", "stopForeground", "stopSelf 2"),
            port.calls,
        )

        // While KJB's job holds the foreground, a refused start changes nothing.
        port.calls.clear()
        foreground.start(3, kjb)
        foreground.start(4, Tap(OTHER_UID))
        assertEquals(listOf("start mediaProcessing $KJB_UID"), port.calls)
        busy = false
        foreground.jobEnded()
        assertEquals(listOf("start mediaProcessing $KJB_UID", "stopForeground", "stopSelf 4"), port.calls)
    }

    @Test
    fun `a phone that refuses the foreground service leaves the job to run without it, and nothing throws`() {
        val port = FakeForeground(refuses = true)
        val foreground = foreground(port)

        foreground.start(1, kjb)
        busy = false
        foreground.jobEnded()
        foreground.unbound()

        assertEquals(listOf("start mediaProcessing $KJB_UID", "stopSelf 1"), port.calls)
    }

    @Test
    fun `KJB letting go leaves the foreground`() {
        val port = FakeForeground()
        val foreground = foreground(port)
        foreground.start(1, kjb)

        foreground.unbound()

        assertEquals(listOf("start mediaProcessing $KJB_UID", "stopForeground", "stopSelf 1"), port.calls)
    }

    @Test
    fun `the time running out leaves the foreground there and then, and ends the work under way`() {
        val port = FakeForeground()
        val foreground = foreground(port)
        foreground.start(1, kjb)

        foreground.timeUp()

        assertEquals(1, jobsEnded)
        assertEquals(listOf("start mediaProcessing $KJB_UID", "stopForeground", "stopSelf all"), port.calls)
        busy = false
        foreground.jobEnded()
        assertEquals("left already", 3, port.calls.size)
    }

    // The battery exemption, asked by the add-on for itself, for KJB only

    private val pin = "AB".repeat(32)

    /** KJB's uid holds KJB's package and certificate; [OTHER_UID] another app's, and [SAME_KEY_UID] an app signed with KJB's key that is not KJB. */
    private val callers = CallerCheck(
        addonPackage = "app.lyrify.ai",
        kjbPackage = "app.lyrify",
        certSha256 = pin,
        packagesFor = { uid -> mapOf(KJB_UID to arrayOf("app.lyrify"), OTHER_UID to arrayOf("com.example.other"), SAME_KEY_UID to arrayOf("app.lyrify.dl"))[uid] },
        hasCert = { uid, sha -> uid != OTHER_UID && sha.contentEquals(AddonPins.bytes(pin)) },
    )

    @Test
    fun `the exemption is asked for KJB only, and an add-on already exempt answers at once`() {
        assertEquals(ExemptionStep.ASK, exemptionStep(KJB_UID, callers, exempt = false))
        assertEquals(ExemptionStep.DONE, exemptionStep(KJB_UID, callers, exempt = true))
    }

    @Test
    fun `a caller that is not KJB is refused, signed alike or not, and so is one Android does not name`() {
        for (exempt in listOf(false, true)) {
            assertEquals(ExemptionStep.REFUSE, exemptionStep(OTHER_UID, callers, exempt))
            assertEquals(ExemptionStep.REFUSE, exemptionStep(SAME_KEY_UID, callers, exempt))
            // Started without asking for a result: Android names no caller.
            assertEquals(ExemptionStep.REFUSE, exemptionStep(null, callers, exempt))
        }
    }

    private companion object {
        const val KJB_UID = 10_279
        const val OTHER_UID = 10_301
        const val SAME_KEY_UID = 10_302
    }
}
