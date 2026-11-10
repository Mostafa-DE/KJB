package app.lyrify.player

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The sleep timer: by time and by songs, the fade over the last 10 s, and the driver on a fake player with virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class SleepTest {
    private val song = 200_000L

    // The rules

    @Test
    fun `a timer by time runs from when it is set, and the sheet's songs choices count the song playing`() {
        assertEquals(SleepTimer.ByTime(endsAt = 1_000 + 15 * 60_000, minutes = 15), sleepFor(15, now = 1_000))
        assertEquals(SleepTimer.ByTime(endsAt = 20 * 60_000, minutes = 20, custom = true), sleepFor(20, now = 0, custom = true))
        assertEquals(SleepTimer.BySongs(songs = 1, left = 1), sleepAfter(1))
        assertEquals(SleepTimer.BySongs(songs = 3, left = 3), sleepAfter(3))
        assertEquals(listOf(15, 30, 45, 60), SLEEP_MINUTES)
        assertEquals(listOf(1, 3, 5), SLEEP_SONGS)
    }

    @Test
    fun `by time, full volume until the last 10 s, then a fade while playing, and the stop when the time is up`() {
        val timer = sleepFor(15, now = 0) as SleepTimer.ByTime
        // Well before: nothing to do until the fade starts.
        assertEquals(SleepStep(1f, stops = false, nextMs = 15 * 60_000 - SLEEP_FADE_MS), sleepStep(timer, 0, playing = true, 0, song, 1f))
        val half = sleepStep(timer, timer.endsAt - 5_000, playing = true, 0, song, 1f)
        assertEquals(0.5f, half.volume, 0.001f)
        assertFalse(half.stops)
        assertTrue(half.nextMs!! in 1..1_000)
        assertEquals(0.1f, sleepStep(timer, timer.endsAt - 1_000, playing = true, 0, song, 1f).volume, 0.001f)
        // Paused in the fade: the volume waits at full; the time runs on.
        assertEquals(1f, sleepStep(timer, timer.endsAt - 5_000, playing = false, 0, song, 1f).volume, 0f)
        assertTrue(sleepStep(timer, timer.endsAt, playing = true, 0, song, 1f).stops)
        assertTrue(sleepStep(timer, timer.endsAt + 60_000, playing = false, 0, song, 1f).stops)
        assertEquals(5_000L, sleepLeftMs(timer, timer.endsAt - 5_000, 0, song, 1f))
        assertEquals(0L, sleepLeftMs(timer, timer.endsAt + 9, 0, song, 1f))
    }

    @Test
    fun `by songs, only the last song fades, over its last 10 s at the speed it plays`() {
        val three = sleepAfter(3)
        assertNull(sleepLeftMs(three, 0, 195_000, song, 1f))
        assertEquals(SleepStep(1f, stops = false, nextMs = null), sleepStep(three, 0, playing = true, 195_000, song, 1f))
        val last = SleepTimer.BySongs(songs = 3, left = 1)
        assertEquals(30_000L, sleepLeftMs(last, 0, 170_000, song, 1f))
        assertEquals(1f, sleepStep(last, 0, playing = true, 170_000, song, 1f).volume, 0f)
        assertEquals(0.5f, sleepStep(last, 0, playing = true, 195_000, song, 1f).volume, 0.001f)
        // Twice as fast, 10 s of the song are 5 s of time.
        assertEquals(5_000L, sleepLeftMs(last, 0, 190_000, song, 2f))
        assertEquals(0.5f, sleepStep(last, 0, playing = true, 190_000, song, 2f).volume, 0.001f)
        // A length not known yet: full volume, and a look again soon.
        val unknown = sleepStep(last, 0, playing = true, 0, 0, 1f)
        assertEquals(1f, unknown.volume, 0f)
        assertTrue(unknown.nextMs!! in 1..1_000)
        // The end of the song is not a stop here: the song ending is ([sleepAfterSong]).
        assertFalse(sleepStep(last, 0, playing = true, song, song, 1f).stops)
    }

    @Test
    fun `a song played to its end counts one, and the last one ends the timer, by time songs do not count`() {
        assertEquals(SleepTimer.BySongs(songs = 3, left = 2), sleepAfterSong(sleepAfter(3)))
        assertEquals(SleepTimer.BySongs(songs = 3, left = 1, custom = true), sleepAfterSong(SleepTimer.BySongs(3, 2, custom = true)))
        assertNull(sleepAfterSong(sleepAfter(1)))
        val byTime = sleepFor(30, now = 5)
        assertEquals(byTime, sleepAfterSong(byTime))
    }

    // The driver, on a fake player

    private class Run(val port: FakeExtrasPort, val timer: MutableStateFlow<SleepTimer?>, val driver: SleepDriver, var stops: Int = 0)

    private fun TestScope.driving(timer: SleepTimer?, setup: FakeExtrasPort.() -> Unit = {}): Run {
        val port = FakeExtrasPort().apply(setup)
        val flow = MutableStateFlow(timer)
        lateinit var run: Run
        val driver = SleepDriver(port, flow, { testScheduler.currentTime }) { run.stops++ }
        run = Run(port, flow, driver)
        backgroundScope.launch { driver.run() }
        runCurrent()
        return run
    }

    @Test
    fun `by time, the driver fades the last 10 s, pauses at the end, and gives the volume back`() = runTest {
        val run = driving(sleepFor(1, now = testScheduler.currentTime))
        advanceTimeBy(50_000)
        runCurrent()
        assertEquals(1f, run.port.volume, 0f)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(0.5f, run.port.volume, 0.05f)
        assertTrue(run.port.playing)
        advanceTimeBy(5_200)
        runCurrent()
        assertFalse(run.port.playing)
        assertEquals(1f, run.port.volume, 0f)
        assertNull(run.timer.value)
        assertEquals(1, run.stops)
    }

    @Test
    fun `turned off in the fade, the music plays on at full volume`() = runTest {
        val run = driving(sleepFor(1, now = testScheduler.currentTime))
        advanceTimeBy(57_000)
        runCurrent()
        assertTrue(run.port.volume < 0.5f)
        run.timer.value = null
        runCurrent()
        assertEquals(1f, run.port.volume, 0f)
        advanceTimeBy(10_000)
        runCurrent()
        assertTrue(run.port.playing)
        assertEquals(0, run.stops)
    }

    @Test
    fun `a new timer replaces the one running`() = runTest {
        val run = driving(sleepFor(1, now = testScheduler.currentTime))
        advanceTimeBy(30_000)
        run.timer.value = sleepFor(15, now = testScheduler.currentTime)
        advanceTimeBy(60_000)
        runCurrent()
        assertTrue(run.port.playing)
        assertEquals(1f, run.port.volume, 0f)
        assertEquals(0, run.stops)
    }

    @Test
    fun `by songs, the driver fades the last song and stops the music at the start of the next one`() = runTest {
        val run = driving(sleepAfter(2))
        run.driver.songEnded()
        assertEquals(SleepTimer.BySongs(songs = 2, left = 1), run.timer.value)
        runCurrent()
        assertTrue(run.port.playing)
        run.port.positionMs = 195_000
        advanceTimeBy(1_100)
        runCurrent()
        assertEquals(0.5f, run.port.volume, 0.05f)
        // The song ended: the next one has started, and stops there, at its start.
        run.port.index = 1
        run.port.positionMs = 120
        run.driver.songEnded()
        runCurrent()
        assertFalse(run.port.playing)
        assertEquals(1 to 0L, run.port.seeks.last())
        assertEquals(1f, run.port.volume, 0f)
        assertNull(run.timer.value)
        assertEquals(1, run.stops)
    }

    @Test
    fun `the end of the queue ends a timer by songs, with no timer a song's end changes nothing`() = runTest {
        val run = driving(sleepAfter(5))
        run.driver.queueEnded()
        runCurrent()
        assertNull(run.timer.value)
        assertEquals(1f, run.port.volume, 0f)
        assertFalse(run.port.playing)
        run.port.playing = true
        run.driver.songEnded()
        runCurrent()
        assertTrue(run.port.playing)
        assertTrue(run.port.seeks.isEmpty())
    }
}
