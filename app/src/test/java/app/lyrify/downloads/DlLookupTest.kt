package app.lyrify.downloads

import app.lyrify.addon.AddonDiedException
import app.lyrify.addon.AddonState
import app.lyrify.dl.api.DlEnd
import app.lyrify.dl.api.DlFailure
import app.lyrify.dl.api.DlInfo
import app.lyrify.dl.api.DlRequest
import app.lyrify.dl.api.DlSearch
import app.lyrify.dl.api.DlSearchResults
import app.lyrify.dl.api.DlStatus
import app.lyrify.dl.api.DlUpdate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The save sheet's lookup in the Downloads add-on ([DlLookup]): never a call into an add-on the phone may have frozen when the sheet goes. */
@OptIn(ExperimentalCoroutinesApi::class)
class DlLookupTest {
    /** What KJB did, in order: calls into the add-on, and what it asked of Android (the foreground start, the unbind, the stop). */
    private val calls = mutableListOf<String>()

    private inner class FakePort(private val dead: Boolean = false) : DlPort {
        override fun apiVersion(): Int = 1

        override fun status(): DlStatus = DlStatus()

        override fun info(url: String, events: DlLookupEvents): Long {
            if (dead) throw AddonDiedException()
            calls += "info"
            return 3
        }

        override fun cancelInfo(lookup: Long) {
            calls += "cancelInfo $lookup"
        }

        override fun search(search: DlSearch, events: DlSearchEvents): Long {
            if (dead) throw AddonDiedException()
            calls += "search ${search.query}"
            events.onResults(DlSearchResults(emptyList()))
            return 4
        }

        override fun start(request: DlRequest, events: DlRunEvents): Long = error("not a run")

        override fun deliver(run: Long, media: File, cover: File?) = error("not a run")

        override fun cancel(run: Long, keepParts: Boolean) = error("not a run")

        override fun discard(key: String) = error("not a run")

        override fun update(): DlUpdate = error("not an update")
    }

    /** A session with an add-on that answered [api] in the handshake. */
    private fun session(port: DlPort = FakePort(), api: Int = MIN_DL_API) = DlSession(
        port,
        CompletableDeferred(),
        unbind = { calls += "unbind" },
        startForeground = { true.also { calls += "foreground" } },
        stopService = { calls += "stopService" },
        api = api,
    )

    private val failures = mutableListOf<DlFailure>()

    private val events = object : DlLookupEvents {
        override fun onInfo(info: DlInfo) {}

        override fun onFailed(end: DlEnd.Failed) {
            failures += end.failure
        }
    }

    @Test
    fun `a lookup runs in the add-on's foreground, and the sheet going asks nothing of the add-on`() = runTest {
        val lookup = DlLookup { DlConnection.Open(session()) }

        assertEquals(AddonState.Ready, lookup.start("https://video.example.com/w", events))
        lookup.close()

        // A call into an add-on the phone froze can wait for as long as it stays frozen: KJB lets go of it and stops the
        // service it started; the add-on, KJB gone, stops the lookup itself (onUnbind).
        assertEquals(listOf("info", "foreground", "unbind", "stopService"), calls)
        lookup.close()
        assertEquals("once", 4, calls.size)
    }

    @Test
    fun `an add-on bound after the sheet went is let go of at once, never asked to look up`() = runTest {
        val bound = CompletableDeferred<DlConnection>()
        val lookup = DlLookup { bound.await() }
        val started = backgroundScope.async { lookup.start("https://video.example.com/w", events) }
        runCurrent()

        lookup.close()
        bound.complete(DlConnection.Open(session()))
        runCurrent()

        assertEquals(listOf("unbind", "stopService"), calls)
        assertEquals(AddonState.Ready, started.await())
    }

    @Test
    fun `an add-on gone as it is asked fails the lookup as the engine, and is let go of`() = runTest {
        val lookup = DlLookup { DlConnection.Open(session(FakePort(dead = true))) }

        lookup.start("https://video.example.com/w", events)
        lookup.close()

        assertEquals(listOf(DlFailure.ENGINE), failures)
        assertEquals(listOf("unbind", "stopService"), calls)
    }

    // The add-on's service, stopped only by the last of KJB's sessions to let go of it

    @Test
    fun `a session let go of while another of KJB's holds the add-on never stops its service, and the last one does`() {
        val holds = DlHolds()
        fun session(name: String) =
            DlSession(FakePort(), CompletableDeferred(), { calls += "unbind $name" }, { true }, { calls += "stopService" }, holds.also { it.take() })
        val run = session("run")
        val sheet = session("sheet")

        // The sheet goes while a download runs: a stop now would leave the add-on in its foreground service, bound by the
        // run, and Android would count that service's time again, as the phone's whole uptime, when the run ends.
        sheet.abandon()
        assertEquals(listOf("unbind sheet"), calls)
        run.abandon()
        assertEquals(listOf("unbind sheet", "unbind run", "stopService"), calls)
    }

    /** A session of KJB's with the add-on among [holds], heard in [calls] by [name]; [take]: false when [DlHolds.held] took its hold. */
    private fun session(name: String, holds: DlHolds, take: Boolean = true) =
        DlSession(FakePort(), CompletableDeferred(), { calls += "unbind $name" }, { true }, { calls += "stopService" }, holds.also { if (take) it.take() })

    @Test
    fun `a stop asked for while another of KJB's sessions held the add-on is done by the last to let go, a plain close too`() = runTest {
        val holds = DlHolds()
        val run = session("run", holds)
        val page = (holds.held({ DlConnection.Open(session("page", holds, take = false)) }, { calls += "stopService" }) as DlConnection.Open).session

        // The phone froze the add-on during a run while the Downloads page asked it something. An add-on the phone froze
        // cannot leave its foreground service by itself, so the page's close, the last, stops the service KJB started.
        run.abandon()
        assertEquals(listOf("unbind run"), calls)
        page.close()
        assertEquals(listOf("unbind run", "unbind page", "stopService"), calls)

        // Done once: the next session's close owes nothing.
        session("next", holds).close()
        assertEquals(listOf("unbind run", "unbind page", "stopService", "unbind next"), calls)
    }

    @Test
    fun `a stop asked for while KJB was binding the add-on is done when that bind opens no session`() = runTest {
        val holds = DlHolds()
        val run = session("run", holds)
        val bound = CompletableDeferred<DlConnection>()
        val page = backgroundScope.async { holds.held({ bound.await() }, { calls += "stopService" }) }
        runCurrent()

        run.abandon()
        assertEquals(listOf("unbind run"), calls)
        bound.complete(DlConnection.Closed(AddonState.Unreachable))
        page.await()

        assertEquals(listOf("unbind run", "stopService"), calls)
    }

    @Test
    fun `a stop asked for while KJB was binding the add-on is done when that bind is cancelled`() = runTest {
        val holds = DlHolds()
        val run = session("run", holds)
        val page = backgroundScope.async { holds.held({ awaitCancellation() }, { calls += "stopService" }) }
        runCurrent()

        run.abandon()
        assertEquals(listOf("unbind run"), calls)
        page.cancel()
        runCurrent()

        assertEquals(listOf("unbind run", "stopService"), calls)
    }

    @Test
    fun `a session lets go once, and one closed is never stopped after`() {
        val session = session()

        session.close()
        session.abandon()
        session.close()

        assertEquals(listOf("unbind"), calls)
    }

    // YouTube search: only of an add-on new enough to know it

    private val found = mutableListOf<DlSearchResults>()

    private val searchEvents = object : DlSearchEvents {
        override fun onResults(results: DlSearchResults) {
            found += results
        }

        override fun onFailed(end: DlEnd.Failed) {
            failures += end.failure
        }
    }

    @Test
    fun `a search runs in the add-on's foreground, and the page going asks nothing of the add-on`() = runTest {
        val lookup = DlLookup { DlConnection.Open(session(api = DL_SEARCH_API)) }

        assertEquals(AddonState.Ready, lookup.search(DlSearch("paper lantern"), searchEvents))
        lookup.close()

        assertEquals(listOf("search paper lantern", "foreground", "unbind", "stopService"), calls)
        assertEquals("the add-on's answer reaches the page", listOf(DlSearchResults(emptyList())), found)
    }

    @Test
    fun `an add-on too old to search is never asked, says it needs an update, and is let go of`() = runTest {
        val lookup = DlLookup { DlConnection.Open(session(api = 1)) }

        assertEquals(AddonState.Outdated(1), lookup.search(DlSearch("paper lantern"), searchEvents))
        // It would answer the unknown call with nothing, and the search would wait for good. It answered the handshake,
        // so it is not frozen: a plain unbind, no stop.
        assertEquals(listOf("unbind"), calls)
        lookup.close()
        assertEquals("let go of once", listOf("unbind"), calls)
        assertEquals(emptyList<DlFailure>(), failures)
    }

    @Test
    fun `an add-on gone as it is asked to search fails the search as the engine, and is let go of`() = runTest {
        val lookup = DlLookup { DlConnection.Open(session(FakePort(dead = true), api = DL_SEARCH_API)) }

        lookup.search(DlSearch("paper lantern"), searchEvents)
        lookup.close()

        assertEquals(listOf(DlFailure.ENGINE), failures)
        assertEquals(listOf("unbind", "stopService"), calls)
    }

    @Test
    fun `the Downloads page offers search only for a ready add-on new enough to search`() {
        assertTrue(DlAddonState(AddonState.Ready, DL_SEARCH_API).searches)
        assertTrue(DlAddonState(AddonState.Ready, DL_SEARCH_API + 1).searches)
        assertFalse(DlAddonState(AddonState.Ready, MIN_DL_API).searches)
        assertFalse(DlAddonState(AddonState.Missing).searches)
        assertFalse(DlAddonState(AddonState.Unreachable, DL_SEARCH_API).searches)
    }

    @Test
    fun `an add-on that cannot be bound says why`() = runTest {
        val lookup = DlLookup { DlConnection.Closed(AddonState.Missing) }

        assertEquals(AddonState.Missing, lookup.start("https://video.example.com/w", events))
        lookup.close()

        assertEquals(emptyList<String>(), calls)
    }
}
