package app.lyrify.huddle

import app.lyrify.library.SongRef
import app.lyrify.stats.PlayEnd
import app.lyrify.stats.PlayEvent
import org.junit.Assert.assertEquals
import org.junit.Test

/** Which of the player's plays Friends sharing passes on: one that sounded long enough, once per play, never a skip. */
class SharedPlaysTest {
    private val shared = ArrayList<Pair<String, Long>>()
    private val plays = SharedPlays { path, at -> shared += path to at }
    private val song = SongRef("/storage/emulated/0/Music/HG/01 Paper Lantern.mp3")

    private fun started(id: Long, at: Long, durationMs: Long = 200_000) = plays.onEvent(PlayEvent.Started(id, song, at, durationMs))

    private fun at(id: Long, positionMs: Long, playing: Boolean = true) = plays.onEvent(PlayEvent.Position(id, song, 0, positionMs, playing))

    @Test
    fun `a play is shared once it has sounded ten seconds, and only once`() {
        started(1, at = 5_000)
        at(1, 0)
        at(1, 9_999)
        assertEquals(emptyList<Pair<String, Long>>(), shared)
        at(1, SharedPlays.SHARE_AFTER_MS)
        at(1, 15_000)
        assertEquals(listOf(song.path to 5_000L), shared)
    }

    @Test
    fun `a skip before ten seconds shares nothing`() {
        started(1, at = 5_000)
        at(1, 4_000)
        plays.onEvent(PlayEvent.Ended(1, song, 9_000, 4_000, PlayEnd.NEXT))
        at(1, 12_000)
        assertEquals(emptyList<Pair<String, Long>>(), shared)
    }

    @Test
    fun `a paused play is not shared by where it stands, and a short song at half its length`() {
        started(1, at = 5_000)
        at(1, 12_000, playing = false)
        assertEquals(emptyList<Pair<String, Long>>(), shared)
        started(2, at = 7_000, durationMs = 8_000)
        at(2, 4_000)
        assertEquals(listOf(song.path to 7_000L), shared)
    }

    @Test
    fun `each lap of Repeat one is a play of its own`() {
        started(1, at = 1_000)
        at(1, 11_000)
        started(2, at = 201_000)
        at(2, 11_000)
        assertEquals(listOf(song.path to 1_000L, song.path to 201_000L), shared)
    }
}
