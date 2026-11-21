package app.lyrify.addon.shared

import android.content.pm.ServiceInfo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /** Android's side; [refuses] the types it will not have the service in the foreground as. */
    private class FakeForeground(private val refuses: (Int) -> Boolean = { false }) : ForegroundPort<Tap> {
        val calls = mutableListOf<String>()

        override fun startForeground(type: Int, tap: Tap?) {
            calls += "start ${typeName(type)} ${tap?.uid}"
            // ForegroundServiceStartNotAllowedException is one: Android 12+ refusing it now, or Android 15+ after the day's time ran out.
            if (refuses(type)) throw IllegalStateException("not allowed now")
        }

        override fun stopForeground() {
            calls += "stopForeground"
        }

        override fun stopSelf(startId: Int?) {
            calls += "stopSelf ${startId ?: "all"}"
        }

        override fun updateNotice(tap: Tap?) {
            calls += "notice ${tap?.uid}"
        }
    }

    private val kjb = Tap(KJB_UID)
    private var busy = true
    private var jobsEnded = 0

    /** An add-on with one kind of work, mediaProcessing (a song's job on Android 15+). */
    private fun foreground(port: FakeForeground) =
        JobForeground(listOf(ForegroundWork(MEDIA_PROCESSING, busy = { busy }, timeUp = { jobsEnded++ })), port, fromKjb = { it.uid == KJB_UID })

    @Test
    fun `KJB's start while a job runs goes into the foreground at once, of the add-on's type, with KJB's tap`() {
        val port = FakeForeground()
        foreground(port).start(startId = 1, tap = kjb)

        assertEquals(listOf("start mediaProcessing $KJB_UID"), port.calls)
        val dataSync = FakeForeground()
        JobForeground(listOf(ForegroundWork(DATA_SYNC, busy = { true }, timeUp = {})), dataSync, fromKjb = { it.uid == KJB_UID }).start(1, kjb)
        assertEquals(listOf("start dataSync $KJB_UID"), dataSync.calls)
    }

    // Two kinds of work, each of its own type: the AI add-on's song's job (mediaProcessing) and models' download (dataSync)

    private var jobBusy = false
    private var downloadBusy = false
    private val timedOut = mutableListOf<String>()

    private fun twoKinds(port: FakeForeground) = JobForeground(
        listOf(
            ForegroundWork(MEDIA_PROCESSING, busy = { jobBusy }, timeUp = { timedOut += "job" }),
            ForegroundWork(DATA_SYNC, busy = { downloadBusy }, timeUp = { timedOut += "download" }),
        ),
        port,
        fromKjb = { it.uid == KJB_UID },
    )

    @Test
    fun `the type is the work's under way as each start goes into the foreground`() {
        val port = FakeForeground()
        val foreground = twoKinds(port)

        downloadBusy = true
        foreground.start(1, kjb)
        downloadBusy = false
        foreground.jobEnded()
        jobBusy = true
        foreground.start(2, kjb)

        assertEquals(listOf("start dataSync $KJB_UID", "stopForeground", "stopSelf 1", "start mediaProcessing $KJB_UID"), port.calls)
    }

    @Test
    fun `work that joins work under way adds its type, and the one that ends first takes its type away, in either order`() {
        // startForeground replaces the types: each change is told to Android again, with the types of all the work left.
        for (first in listOf("job", "download")) {
            for (endsFirst in listOf("job", "download")) {
                val case = "$first first, $endsFirst ends first"
                val port = FakeForeground()
                val foreground = twoKinds(port)
                fun runs(kind: String, now: Boolean) {
                    if (kind == "job") jobBusy = now else downloadBusy = now
                }
                val second = if (first == "job") "download" else "job"
                val left = if (endsFirst == "job") "dataSync" else "mediaProcessing"

                runs(first, true)
                foreground.start(1, kjb)
                runs(second, true)
                foreground.start(2, kjb) // KJB's start for the work that joined
                foreground.noticeChanged()
                runs(endsFirst, false)
                foreground.jobEnded()
                runs(if (endsFirst == "job") "download" else "job", false)
                foreground.jobEnded()

                assertEquals(
                    case,
                    listOf(
                        "start ${if (first == "job") "mediaProcessing" else "dataSync"} $KJB_UID",
                        "start mediaProcessing|dataSync $KJB_UID",
                        "notice $KJB_UID",
                        "start $left $KJB_UID",
                        "stopForeground",
                        "stopSelf 2",
                    ),
                    port.calls,
                )
            }
        }
    }

    @Test
    fun `a start or an end that leaves the types as they are tells Android nothing`() {
        val port = FakeForeground()
        val foreground = twoKinds(port)
        jobBusy = true
        foreground.start(1, kjb)

        foreground.start(2, kjb) // the next song's start, its job already running
        foreground.jobEnded() // the song before it ended, the next one runs

        assertEquals(listOf("start mediaProcessing $KJB_UID"), port.calls)
    }

    @Test
    fun `mediaProcessing's time running out ends the job only, and the download goes on in the foreground as dataSync`() {
        val port = FakeForeground()
        val foreground = twoKinds(port)
        jobBusy = true
        downloadBusy = true
        foreground.start(1, kjb)
        foreground.start(2, kjb)
        port.calls.clear()

        foreground.timeUp(MEDIA_PROCESSING)
        assertEquals(listOf("job"), timedOut)
        assertEquals("the type dropped there and then, the service kept", listOf("start dataSync $KJB_UID"), port.calls)

        // The job winds down after its step: its type stays out, and the download's end takes the service out.
        foreground.noticeChanged()
        downloadBusy = false
        foreground.jobEnded()
        assertEquals(listOf("start dataSync $KJB_UID", "notice $KJB_UID", "stopForeground", "stopSelf 2"), port.calls)
        jobBusy = false
        foreground.jobEnded()
        assertEquals("left already", 4, port.calls.size)
    }

    @Test
    fun `dataSync's time running out ends the download only, and the job goes on in the foreground as mediaProcessing`() {
        val port = FakeForeground()
        val foreground = twoKinds(port)
        downloadBusy = true
        jobBusy = true
        foreground.start(1, kjb)
        foreground.start(2, kjb)
        port.calls.clear()

        foreground.timeUp(DATA_SYNC)
        assertEquals(listOf("download"), timedOut)
        assertEquals(listOf("start mediaProcessing $KJB_UID"), port.calls)

        downloadBusy = false
        foreground.jobEnded() // the download wound down: nothing changes
        jobBusy = false
        foreground.jobEnded()
        assertEquals(listOf("start mediaProcessing $KJB_UID", "stopForeground", "stopSelf 2"), port.calls)
    }

    @Test
    fun `the time running out for the only type in use leaves the foreground there and then, and a type none has ends all`() {
        val port = FakeForeground()
        val foreground = twoKinds(port)
        downloadBusy = true
        foreground.start(1, kjb)

        foreground.timeUp(DATA_SYNC)
        assertEquals(listOf("download"), timedOut)
        assertEquals(listOf("start dataSync $KJB_UID", "stopForeground", "stopSelf all"), port.calls)

        downloadBusy = false
        foreground.jobEnded() // the download wound down
        timedOut.clear()
        jobBusy = true
        downloadBusy = true
        foreground.start(2, kjb)
        foreground.timeUp(0)
        assertEquals(listOf("job", "download"), timedOut)
        assertEquals(listOf("start mediaProcessing|dataSync $KJB_UID", "stopForeground", "stopSelf all"), port.calls.drop(3))
    }

    @Test
    fun `a type change Android refuses leaves the service in the foreground as it was, until no work is left`() {
        // The joining job's mediaProcessing refused (its day's time used up): the download keeps dataSync, the job runs without.
        val joined = FakeForeground(refuses = { it and MEDIA_PROCESSING != 0 })
        val foreground = twoKinds(joined)
        downloadBusy = true
        foreground.start(1, kjb)
        jobBusy = true
        foreground.start(2, kjb)
        foreground.noticeChanged()
        jobBusy = false
        foreground.jobEnded()
        assertEquals(
            listOf("start dataSync $KJB_UID", "start mediaProcessing|dataSync $KJB_UID", "notice $KJB_UID"),
            joined.calls,
        )

        // Both held, then dataSync alone refused as the job ends: the download keeps the foreground it is in, both types,
        // rather than run without it (the reason for it: a phone's app freezer), until it ends too.
        jobBusy = false
        downloadBusy = false
        val ended = FakeForeground(refuses = { it == DATA_SYNC })
        val other = twoKinds(ended)
        jobBusy = true
        other.start(1, kjb)
        downloadBusy = true
        other.start(2, kjb)
        jobBusy = false
        other.jobEnded()
        other.noticeChanged()
        downloadBusy = false
        other.jobEnded()
        assertEquals(
            listOf(
                "start mediaProcessing $KJB_UID",
                "start mediaProcessing|dataSync $KJB_UID",
                "start dataSync $KJB_UID",
                "notice $KJB_UID",
                "stopForeground",
                "stopSelf 2",
            ),
            ended.calls,
        )
    }

    @Test
    fun `a start with no work under way goes in and out as the first kind's type`() {
        for ((first, name) in listOf(MEDIA_PROCESSING to "mediaProcessing", DATA_SYNC to "dataSync")) {
            val port = FakeForeground()
            val other = if (first == MEDIA_PROCESSING) DATA_SYNC else MEDIA_PROCESSING
            JobForeground(listOf(ForegroundWork(first, { false }, {}), ForegroundWork(other, { false }, {})), port, fromKjb = { it.uid == KJB_UID })
                .start(1, kjb)
            assertEquals(listOf("start $name $KJB_UID", "stopForeground", "stopSelf 1"), port.calls)
        }
    }

    @Test
    fun `the notice is posted again as the work moves on, with KJB's tap, only while in the foreground`() {
        val port = FakeForeground()
        val foreground = foreground(port)

        foreground.noticeChanged() // not in the foreground yet: a notice posted now would stay behind
        foreground.start(1, kjb)
        foreground.noticeChanged()
        foreground.noticeChanged()
        busy = false
        foreground.jobEnded()
        foreground.noticeChanged() // left: nothing more

        assertEquals(listOf("start mediaProcessing $KJB_UID", "notice $KJB_UID", "notice $KJB_UID", "stopForeground", "stopSelf 1"), port.calls)
        val refused = FakeForeground(refuses = { true })
        busy = true
        foreground(refused).apply {
            start(2, kjb)
            noticeChanged()
        }
        assertEquals("refused, the work runs without it, and nothing is posted", listOf("start mediaProcessing $KJB_UID", "stopSelf 2"), refused.calls)
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
        val port = FakeForeground(refuses = { true })
        val foreground = foreground(port)

        foreground.start(1, kjb)
        busy = false
        foreground.jobEnded()
        foreground.unbound()

        assertEquals(listOf("start mediaProcessing $KJB_UID", "stopSelf 1"), port.calls)
    }

    @Test
    fun `each answer Android gives the service's foreground is told - refused, let in, and its time run out`() {
        var refusing = true
        val port = FakeForeground(refuses = { refusing })
        val answers = mutableListOf<Boolean>()
        val foreground = JobForeground(
            listOf(ForegroundWork(DATA_SYNC, busy = { busy }, timeUp = {})),
            port,
            fromKjb = { it.uid == KJB_UID },
            answered = { refused -> answers += refused },
        )

        foreground.start(1, kjb)
        refusing = false
        foreground.start(2, kjb)
        foreground.timeUp(DATA_SYNC)

        assertEquals("refused, let in, its time run out", listOf(true, false, true), answers)
    }

    @Test
    fun `work that goes on past its time leaves the foreground there and then, and KJB's next start holds it again when Android lets it in`() {
        var refusing = false
        val port = FakeForeground(refuses = { refusing })
        val answers = mutableListOf<Boolean>()
        val foreground = JobForeground(
            listOf(ForegroundWork(DATA_SYNC, busy = { busy }, timeUp = {}, goesOn = true)),
            port,
            fromKjb = { it.uid == KJB_UID },
            answered = { refused -> answers += refused },
        )
        foreground.start(1, kjb)

        foreground.timeUp(DATA_SYNC)
        assertEquals(listOf("start dataSync $KJB_UID", "stopForeground", "stopSelf all"), port.calls)

        // The work goes on. Android refuses the next start (the day's time used up): it goes on without the foreground.
        refusing = true
        foreground.start(2, kjb)
        assertEquals(listOf("start dataSync $KJB_UID", "stopSelf 2"), port.calls.drop(3))

        // Android lets it in again (the add-on was on screen since, or the day's count started over): held, not in and out.
        refusing = false
        foreground.start(3, kjb)
        assertEquals(listOf("start dataSync $KJB_UID"), port.calls.drop(5))
        assertEquals("let in, its time run out, refused, let in", listOf(false, true, true, false), answers)

        busy = false
        foreground.jobEnded()
        assertEquals(listOf("start dataSync $KJB_UID", "stopForeground", "stopSelf 3"), port.calls.drop(5))
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

        foreground.timeUp(MEDIA_PROCESSING)

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

    @Test
    fun `a start's tap is KJB's only when KJB made it and, from Android 12, nobody can fill it in`() {
        assertTrue(tapFromKjb(KJB_UID, immutable = true, callers))
        assertFalse("mutable: whoever it passes through could fill it in", tapFromKjb(KJB_UID, immutable = false, callers))
        assertTrue("before Android 12, which cannot tell", tapFromKjb(KJB_UID, immutable = null, callers))
        assertFalse(tapFromKjb(OTHER_UID, immutable = true, callers))
        assertFalse(tapFromKjb(SAME_KEY_UID, immutable = true, callers))
    }

    @Test
    fun `the question goes to the phone's own settings, never to another app that claims it`() {
        val settings = IntentHandler("com.android.settings", system = true)
        val other = IntentHandler("com.example.claims", system = false)

        assertEquals("com.android.settings", systemHandler(listOf(other, settings)))
        assertEquals("com.android.settings", systemHandler(listOf(settings, other)))
        assertEquals("only another app answers: not sent", null, systemHandler(listOf(other)))
        assertEquals(null, systemHandler(emptyList()))
    }

    private companion object {
        const val MEDIA_PROCESSING = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        const val DATA_SYNC = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC

        /** The types, as AOSP's dumpsys names them, joined. */
        fun typeName(type: Int): String = listOfNotNull(
            "mediaProcessing".takeIf { type and MEDIA_PROCESSING != 0 },
            "dataSync".takeIf { type and DATA_SYNC != 0 },
        ).joinToString("|").ifEmpty { "type $type" }

        const val KJB_UID = 10_279
        const val OTHER_UID = 10_301
        const val SAME_KEY_UID = 10_302
    }
}
