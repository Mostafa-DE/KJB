package app.lyrify.ui

import app.lyrify.data.LedgerEntry
import app.lyrify.data.LedgerState
import app.lyrify.data.LyricsStatus
import app.lyrify.media.CurrentTrack
import app.lyrify.media.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private const val PATH = "/music/Paper Lanterns.mp3"

class NowBarTest {
    private val samsung = Player(Player.SAMSUNG_MUSIC, "Samsung Music")
    private val other = CurrentTrack(title = "Harbour Glass", artist = "Paper Lanterns", paths = listOf(PATH), resolved = true, player = samsung)

    /** A fake of what KJB's own player supplies (milestone P1). */
    private fun own(playing: Boolean) = OwnNow(title = "Quiet Engines", artist = "The Lamplighters", cover = null, playing = playing, progress = 0.25f)

    // Own, companion or hidden

    @Test
    fun `nothing loaded anywhere hides the bar`() {
        assertEquals(NowBarShows.Hidden, nowBarShows(own = null, other = null, otherPlaying = false))
    }

    @Test
    fun `KJB's own song shows while it plays, even when another app has a song too`() {
        assertEquals(NowBarShows.Own(own(playing = true)), nowBarShows(own(playing = true), other, otherPlaying = true))
        assertEquals(NowBarShows.Own(own(playing = true)), nowBarShows(own(playing = true), null, otherPlaying = false))
    }

    @Test
    fun `another app that plays takes the bar from KJB's paused queue`() {
        assertEquals(NowBarShows.Companion(other, playing = true), nowBarShows(own(playing = false), other, otherPlaying = true))
    }

    @Test
    fun `KJB's paused queue shows when no other app plays`() {
        assertEquals(NowBarShows.Own(own(playing = false)), nowBarShows(own(playing = false), other, otherPlaying = false))
        assertEquals(NowBarShows.Own(own(playing = false)), nowBarShows(own(playing = false), null, otherPlaying = false))
    }

    @Test
    fun `another app's paused song shows when KJB has nothing loaded`() {
        assertEquals(NowBarShows.Companion(other, playing = false), nowBarShows(null, other, otherPlaying = false))
    }

    // The orb

    @Test
    fun `the companion orb shows the song's cover, the status of the first copy with one, and its title`() {
        val entry = LedgerEntry(path = PATH, title = "Harbour Glass", durationMs = 200_000, status = LyricsStatus.SYNCED, updatedAt = 0)
        val ledger = LedgerState(entries = mapOf(PATH to entry))
        val track = CurrentTrack(title = "Harbour Glass", artist = null, paths = listOf("/music/other.mp3", PATH), resolved = true)
        assertEquals(NowOrb(SongArt("/music/other.mp3"), LyricsStatus.SYNCED, "Harbour Glass"), nowOrb(track, ledger))
        assertEquals(NowOrb(null, null, "Radio"), nowOrb(CurrentTrack(title = "Radio", artist = null), ledger))
    }

    // The other app's progress ring

    @Test
    fun `a playing session moves on from its last report at its speed, a paused one stays`() {
        assertEquals(13_000L, sessionPosition(positionMs = 10_000, reportedAt = 1_000, speed = 1f, playing = true, now = 4_000, durationMs = 200_000))
        assertEquals(16_000L, sessionPosition(positionMs = 10_000, reportedAt = 1_000, speed = 2f, playing = true, now = 4_000, durationMs = 200_000))
        assertEquals(10_000L, sessionPosition(positionMs = 10_000, reportedAt = 1_000, speed = 1f, playing = false, now = 4_000, durationMs = 200_000))
    }

    @Test
    fun `the position never runs past the song's end or before its start`() {
        assertEquals(200_000L, sessionPosition(positionMs = 199_000, reportedAt = 1_000, speed = 1f, playing = true, now = 10_000, durationMs = 200_000))
        assertEquals(0L, sessionPosition(positionMs = -5, reportedAt = 0, speed = 1f, playing = false, now = 0, durationMs = 200_000))
    }

    @Test
    fun `a session that never said when it reported its position is not moved on`() {
        assertEquals(10_000L, sessionPosition(positionMs = 10_000, reportedAt = 0, speed = 1f, playing = true, now = 4_000_000, durationMs = 200_000))
        assertEquals(0.05f, ringShare(sessionPosition(10_000, 0, 1f, true, 4_000_000, 200_000), 200_000), 0.0001f)
    }

    // Controlling the other app

    @Test
    fun `the bar controls the session the listener follows, never another session of the same app`() {
        val first = FakeSession("com.example.player", token = "first")
        val second = FakeSession("com.example.player", token = "second")
        assertEquals(second, followedSession(listOf(first, second), followed = "second", token = FakeSession::token))
        assertEquals(null, followedSession(listOf(first, second), followed = null, token = FakeSession::token))
        // The followed session ended: no other session of its app stands in.
        assertEquals(null, followedSession(listOf(first), followed = "second", token = FakeSession::token))
    }

    @Test
    fun `a session that ended or never said its state gives the bar no Play or Pause`() {
        val toggled = mutableListOf<Boolean>()
        val companion = NowBarShows.Companion(other, playing = true)
        val known = OtherPlayback(playing = true, positionMs = 0, reportedAt = 1, speed = 1f, durationMs = 200_000)
        assertNull(barPlayPause(companion, ownPlayPause = {}, otherPlayback = known, otherToggle = null))
        assertNull(barPlayPause(companion, ownPlayPause = {}, otherPlayback = null, otherToggle = { toggled += it }))
        barPlayPause(companion, ownPlayPause = {}, otherPlayback = known, otherToggle = { toggled += it })!!.invoke()
        assertEquals(listOf(true), toggled)
        assertNull(barPlayPause(NowBarShows.Hidden, ownPlayPause = {}, otherPlayback = known, otherToggle = { toggled += it }))
    }

    @Test
    fun `KJB's own song plays and pauses KJB's own player`() {
        var pressed = 0
        barPlayPause(NowBarShows.Own(own(playing = true)), ownPlayPause = { pressed++ }, otherPlayback = null, otherToggle = null)!!.invoke()
        assertEquals(1, pressed)
    }

    private data class FakeSession(val packageName: String, val token: String)

    @Test
    fun `the ring's share is the position over the length, empty when the length is unknown`() {
        assertEquals(0.5f, ringShare(positionMs = 100_000, durationMs = 200_000), 0.0001f)
        assertEquals(0f, ringShare(positionMs = 100_000, durationMs = null), 0.0001f)
        assertEquals(0f, ringShare(positionMs = 100_000, durationMs = 0), 0.0001f)
    }
}
