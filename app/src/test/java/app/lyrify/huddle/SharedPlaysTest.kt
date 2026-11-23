package app.lyrify.huddle

import app.lyrify.library.SongRef
import app.lyrify.stats.PlayEnd
import app.lyrify.stats.PlayEvent
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which of the player's plays Friends sharing passes on: one heard long enough (the time played, not where the song
 * stands), once per play, never a skip. And what friends are told the player does now: at once at each play, pause,
 * resume, skip, seek, end and stop, and every minute while it plays; never a pause they did not see playing.
 */
class SharedPlaysTest {
    private val shared = ArrayList<Pair<String, Long>>()

    /** What friends were told: the song, the state, the place and when. */
    private val told = ArrayList<Told>()
    private val plays = SharedPlays({ path, at -> shared += path to at }) { path, state, positionMs, at -> told += Told(path, state, positionMs, at) }
    private val song = SongRef("/storage/emulated/0/Music/HG/01 Paper Lantern.mp3")
    private val next = SongRef("/storage/emulated/0/Music/HG/02 Brass Bell.mp3")

    private data class Told(val path: String, val state: NowState, val positionMs: Long, val at: Long)

    /** Each play's start on the wall clock, so a position reads at the time it was reached by playing. */
    private val starts = HashMap<Long, Long>()

    private fun started(id: Long, at: Long, durationMs: Long = 200_000) {
        starts[id] = at
        plays.onEvent(PlayEvent.Started(id, song, at, durationMs))
    }

    /** Play [id] at [positionMs], reached [heardMs] after it started (by default, playing from its start). */
    private fun at(id: Long, positionMs: Long, playing: Boolean = true, heardMs: Long = positionMs) =
        plays.onEvent(PlayEvent.Position(id, song, starts.getValue(id) + heardMs, positionMs, playing))

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
        at(2, 0)
        at(2, 4_000)
        assertEquals(listOf(song.path to 7_000L), shared)
    }

    @Test
    fun `a seek past ten seconds shares nothing until ten seconds are heard`() {
        started(1, at = 5_000)
        at(1, 0)
        plays.onEvent(PlayEvent.Seeked(1, song, 6_000, 1_000, 60_000))
        at(1, 61_000, heardMs = 2_000)
        assertEquals(emptyList<Pair<String, Long>>(), shared)
        at(1, 70_000, heardMs = 11_000)
        assertEquals(listOf(song.path to 5_000L), shared)
    }

    @Test
    fun `a play resumed at its place shares only once ten seconds are heard from there`() {
        started(1, at = 5_000)
        at(1, 120_000, heardMs = 0)
        at(1, 125_000, heardMs = 5_000)
        assertEquals(emptyList<Pair<String, Long>>(), shared)
        at(1, 130_000, heardMs = 10_000)
        assertEquals(listOf(song.path to 5_000L), shared)
    }

    @Test
    fun `each lap of Repeat one is a play of its own`() {
        started(1, at = 1_000)
        at(1, 0)
        at(1, 11_000)
        started(2, at = 201_000)
        at(2, 0)
        at(2, 11_000)
        assertEquals(listOf(song.path to 1_000L, song.path to 201_000L), shared)
    }

    @Test
    fun `friends are told at once of a play, a pause, a resume, a skip and a stop`() {
        started(1, at = 5_000)
        at(1, 0)
        at(1, 30_000, playing = false)
        at(1, 30_000, heardMs = 50_000)
        plays.onEvent(PlayEvent.Ended(1, song, 60_000, 40_000, PlayEnd.NEXT))
        plays.onEvent(PlayEvent.Started(2, next, 60_000, 187_000))
        plays.onEvent(PlayEvent.Position(2, next, 60_000, 0, true))
        plays.onEvent(PlayEvent.Ended(2, next, 70_000, 10_000, PlayEnd.STOPPED))
        assertEquals(
            listOf(
                Told(song.path, NowState.PLAYING, 0, 5_000),
                Told(song.path, NowState.PAUSED, 30_000, 35_000),
                Told(song.path, NowState.PLAYING, 30_000, 55_000),
                // The skip: its stop and the next song in the same instant, of which friends get the last (Huddle.listening).
                Told(song.path, NowState.STOPPED, 40_000, 60_000),
                Told(next.path, NowState.PLAYING, 0, 60_000),
                Told(next.path, NowState.STOPPED, 10_000, 70_000),
            ),
            told,
        )
    }

    @Test
    fun `a switch to a book, whose plays never come here, tells friends the song stopped`() {
        started(1, at = 0)
        at(1, 0)
        plays.onEvent(PlayEvent.Ended(1, song, 30_000, 30_000, PlayEnd.JUMP))
        assertEquals(listOf(NowState.PLAYING, NowState.STOPPED), told.map { it.state })
    }

    @Test
    fun `a song KJB opened with, paused, tells nothing until it plays, and after a skip while paused the next shows paused`() {
        started(1, at = 0)
        at(1, 50_000, playing = false, heardMs = 0)
        assertEquals(emptyList<Told>(), told)
        at(1, 50_000, heardMs = 1_000)
        at(1, 60_000, playing = false, heardMs = 11_000)
        plays.onEvent(PlayEvent.Ended(1, song, 20_000, 60_000, PlayEnd.NEXT))
        plays.onEvent(PlayEvent.Started(2, next, 20_000, 187_000))
        plays.onEvent(PlayEvent.Position(2, next, 20_000, 0, false))
        assertEquals(
            listOf(
                Told(song.path, NowState.PLAYING, 50_000, 1_000),
                Told(song.path, NowState.PAUSED, 60_000, 11_000),
                Told(song.path, NowState.STOPPED, 60_000, 20_000),
                Told(next.path, NowState.PAUSED, 0, 20_000),
            ),
            told,
        )
    }

    @Test
    fun `while it plays friends are told again every minute, never while paused, and a seek tells the new place`() {
        started(1, at = 0)
        at(1, 0)
        (1..24).forEach { tick -> at(1, tick * 5_000L) }
        assertEquals(listOf(0L, SharedPlays.HEARTBEAT_MS, 2 * SharedPlays.HEARTBEAT_MS), told.map { it.at })
        at(1, 121_000, playing = false)
        (1..30).forEach { tick -> at(1, 121_000, playing = false, heardMs = 121_000 + tick * 5_000L) }
        assertEquals(listOf(NowState.PLAYING, NowState.PLAYING, NowState.PLAYING, NowState.PAUSED), told.map { it.state })
        plays.onEvent(PlayEvent.Seeked(1, song, 300_000, 116_000, 20_000))
        assertEquals(Told(song.path, NowState.PAUSED, 20_000, 300_000), told.last())
    }

    @Test
    fun `the queue's end tells friends it stopped, and its first song waiting paused after it tells nothing`() {
        started(1, at = 0)
        at(1, 0)
        at(1, 5_000)
        plays.onEvent(PlayEvent.Ended(1, song, 200_000, 200_000, PlayEnd.FINISHED))
        plays.onEvent(PlayEvent.Started(2, next, 200_000, 187_000))
        plays.onEvent(PlayEvent.Position(2, next, 200_000, 0, false))
        plays.onEvent(PlayEvent.Ended(2, next, 900_000, 0, PlayEnd.STOPPED))
        assertEquals(listOf(NowState.PLAYING, NowState.STOPPED), told.map { it.state })
    }
}
