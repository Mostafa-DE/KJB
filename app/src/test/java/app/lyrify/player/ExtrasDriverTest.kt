package app.lyrify.player

import app.lyrify.settings.RememberPlace
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared extras ([PlayerExtras], stored on change) and their driver on a fake player: each song at its speed, the
 * part looping, and Remember my place. Made-up names only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExtrasDriverTest {
    private val long = Playing(1, "/storage/emulated/0/Music/Talks/Night Ferry Talk.mp3", 1_800_000)
    private val short = Playing(2, "/storage/emulated/0/Music/Harbour Glass/02 Paper Lantern.flac", 200_000)
    private val other = Playing(3, "/storage/emulated/0/Music/Rips/Loose Thread.mp3", 240_000)
    private val songs = listOf(long, short, other).associateBy { it.uid }

    private class MemoryStorage(var stored: SongState = SongState()) : SongStateStorage {
        var saves = 0

        override suspend fun load(): SongState = stored

        override suspend fun save(state: SongState) {
            stored = state
            saves++
        }
    }

    private class Run(val port: FakeExtrasPort, val storage: MemoryStorage, val extras: PlayerExtras, val driver: ExtrasDriver, val said: MutableList<ExtrasSaid>)

    private fun TestScope.driving(stored: SongState = SongState(), mode: RememberPlace = RememberPlace.LONG_SONGS): Run {
        val port = FakeExtrasPort()
        val storage = MemoryStorage(stored)
        val extras = PlayerExtras(storage, backgroundScope)
        val driver = ExtrasDriver(port, extras, songs::get) { mode }
        val said = mutableListOf<ExtrasSaid>()
        backgroundScope.launch { driver.run() }
        backgroundScope.launch { extras.said.collect { said += it } }
        runCurrent()
        return Run(port, storage, extras, driver, said)
    }

    /** The player moves to [song] at [atMs], as the engine says it. */
    private fun Run.start(song: Playing, atMs: Long = 0, lap: Boolean = false, natural: Boolean = false) {
        port.index = song.uid.toInt() - 1
        port.positionMs = atMs
        port.durationMs = song.durationMs
        driver.songStarted(song.uid, lap, natural)
    }

    // The shared extras

    @Test
    fun `the extras are read once, then every change is stored, and a change asked for early waits for the read`() = runTest {
        val storage = MemoryStorage(SongState(usual = Speed(1.5f)))
        val extras = PlayerExtras(storage, backgroundScope)
        extras.change { it.withSpeed(null) { speed -> speed.copy(semitones = 2, keepPitch = false) } }
        assertNull(extras.edit { it to 1 })
        runCurrent()
        assertTrue(extras.ready.value)
        assertEquals(Speed(1.5f, keepPitch = false, semitones = 2), extras.state.value.usual)
        assertEquals(extras.state.value, storage.stored)
        val mark = extras.edit { it.withBookmark(short.path, 61_000, "Bridge") }
        runCurrent()
        assertEquals(Bookmark(1, 61_000, "Bridge"), mark)
        assertEquals(listOf(mark), storage.stored.bookmarksOf(short.path))
    }

    // Speeds

    @Test
    fun `each song plays at its own speed or the usual one, and a change applies at once`() = runTest {
        val run = driving(SongState(usual = Speed(1.25f), speeds = mapOf(short.path to Speed(0.75f, keepPitch = false, semitones = 12))))
        run.start(long)
        runCurrent()
        assertEquals(1.25f, run.port.speed, 0f)
        assertEquals(1f, run.port.pitch, 0f)
        run.start(short)
        runCurrent()
        assertEquals(0.75f, run.port.speed, 0f)
        assertEquals(1.5f, run.port.pitch, 0.0001f)
        run.extras.change { it.withSpeed(short.path) { speed -> speed.copy(speed = 1f, keepPitch = true) } }
        runCurrent()
        assertEquals(1f, run.port.speed, 0f)
        assertEquals(1f, run.port.pitch, 0f)
    }

    // Repeat a part

    @Test
    fun `the part loops from B back to A while its song plays`() = runTest {
        val run = driving()
        run.start(short, atMs = 30_000)
        run.driver.playingChanged(true)
        run.extras.change { it.copy(part = AbPart(short.path, 30_000, 40_000)) }
        runCurrent()
        assertTrue(run.port.seeks.isEmpty())
        run.port.positionMs = 39_000
        advanceTimeBy(600)
        runCurrent()
        assertTrue(run.port.seeks.isEmpty())
        run.port.positionMs = 40_010
        advanceTimeBy(600)
        runCurrent()
        assertEquals(1 to 30_000L, run.port.seeks.lastOrNull())
    }

    @Test
    fun `a part moved past the player takes it to A, and another song ends the part`() = runTest {
        val run = driving()
        run.start(short, atMs = 50_000)
        run.extras.change { it.copy(part = AbPart(short.path, 30_000, 40_000)) }
        runCurrent()
        assertEquals(1 to 30_000L, run.port.seeks.lastOrNull())
        run.start(other)
        runCurrent()
        assertNull(run.extras.state.value.part)
        assertNull(run.storage.stored.part)
    }

    @Test
    fun `a part stored for the song playing loops again after the app comes back`() = runTest {
        val run = driving(SongState(part = AbPart(short.path, 30_000, 40_000)))
        run.start(short, atMs = 35_000)
        run.driver.playingChanged(true)
        run.port.positionMs = 41_000
        advanceTimeBy(600)
        runCurrent()
        assertEquals(1 to 30_000L, run.port.seeks.lastOrNull())
        assertEquals(AbPart(short.path, 30_000, 40_000), run.extras.state.value.part)
    }

    // Remember my place

    @Test
    fun `a long song left half way keeps its place, and resumes there when it starts from its start`() = runTest {
        val run = driving()
        run.start(long)
        run.driver.playingChanged(true)
        run.port.positionMs = 1_390_000
        advanceTimeBy(1_100)
        runCurrent()
        // Next: the place where it was left.
        run.start(short)
        runCurrent()
        assertEquals(1_390_000L, run.extras.state.value.places[long.path])
        // A short song is no long song: nothing kept.
        run.port.positionMs = 60_000
        advanceTimeBy(1_100)
        run.start(long)
        runCurrent()
        assertNull(run.extras.state.value.places[short.path])
        assertEquals(0 to 1_390_000L, run.port.seeks.lastOrNull())
        assertEquals(listOf<ExtrasSaid>(ExtrasSaid.Resumed(long.path, 1_390_000)), run.said)
    }

    @Test
    fun `a pause keeps the place, the end of the song forgets it, and so does a lap of Repeat one`() = runTest {
        val run = driving()
        run.start(long)
        run.driver.playingChanged(true)
        run.port.positionMs = 600_000
        run.driver.playingChanged(false)
        runCurrent()
        assertEquals(600_000L, run.extras.state.value.places[long.path])
        // It played to its end: the next one starts by itself.
        run.driver.playingChanged(true)
        run.start(short, natural = true)
        runCurrent()
        assertNull(run.extras.state.value.places[long.path])
        // Repeat one: each lap starts from the start.
        run.start(long, atMs = 0)
        run.port.positionMs = 700_000
        run.driver.playingChanged(false)
        run.driver.playingChanged(true)
        run.start(long, atMs = 0, lap = true, natural = true)
        runCurrent()
        assertNull(run.extras.state.value.places[long.path])
    }

    @Test
    fun `a song the queue put at its own place, or a bookmark's jump, does not resume, and Off keeps nothing`() = runTest {
        val run = driving(SongState(places = mapOf(long.path to 1_390_000L)))
        run.start(long, atMs = 300_000)
        runCurrent()
        assertTrue(run.port.seeks.isEmpty())
        run.start(short)
        run.extras.jumpingTo = long.path
        run.start(long, atMs = 0)
        runCurrent()
        assertTrue(run.port.seeks.isEmpty())
        assertNull(run.extras.jumpingTo)
        assertEquals(emptyList<ExtrasSaid>(), run.said)

        val off = driving(SongState(places = mapOf(long.path to 1_390_000L)), mode = RememberPlace.OFF)
        off.start(long)
        runCurrent()
        assertTrue(off.port.seeks.isEmpty())
        off.port.positionMs = 600_000
        off.driver.stopped()
        runCurrent()
        assertNull(off.extras.state.value.places[long.path])
    }
}
