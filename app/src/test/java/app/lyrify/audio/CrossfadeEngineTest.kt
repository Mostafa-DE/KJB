package app.lyrify.audio

import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import app.lyrify.library.song
import app.lyrify.player.AbPart
import app.lyrify.player.Edit
import app.lyrify.player.EngineExtras
import app.lyrify.player.EngineListener
import app.lyrify.player.ExtrasDriver
import app.lyrify.player.ExtrasSaid
import app.lyrify.player.FakeExtrasPort
import app.lyrify.player.POSITION_EVERY_MS
import app.lyrify.player.PlayTracker
import app.lyrify.player.PlayerExtras
import app.lyrify.player.Playing
import app.lyrify.player.Projected
import app.lyrify.player.QueueBook
import app.lyrify.player.Repeat
import app.lyrify.player.SleepDriver
import app.lyrify.player.SleepTimer
import app.lyrify.player.SongState
import app.lyrify.player.SongStateStorage
import app.lyrify.player.Speed
import app.lyrify.player.repeatWhilePlaying
import app.lyrify.player.sessionView
import app.lyrify.player.sleepAfter
import app.lyrify.player.sleepFadeVolume
import app.lyrify.settings.RememberPlace
import app.lyrify.stats.FakePlayLog
import app.lyrify.stats.PlayCounter
import app.lyrify.stats.PlayEnd
import app.lyrify.stats.PlayEvents
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The crossfade engine on fake players with virtual time: when the second player is made, the fades, what the session
 * sees, and every interaction the spec names (pause, seek, skip spam, plays, the sleep timer, A-B, speed, Repeat one,
 * the end of the queue, a song that fails, focus, the setting itself). Made-up names only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CrossfadeEngineTest {
    private fun squares(a: FakeSlot, b: FakeSlot): Float = a.volume * a.volume + b.volume * b.volume

    // Off

    @Test
    fun `crossfade Off is the old path - one player holding the queue with its own focus, gapless, never a second player`() = runTest {
        val rig = FadeRig(this, crossfadeSeconds = 0)
        val only = rig.slots.single()
        rig.engine.setOffload(true)
        rig.engine.setGapless(false)
        rig.engine.setRepeat(Repeat.ALL)
        rig.engine.volume = 0.5f
        rig.runToLeft(3_000)
        rig.run(4_000)
        rig.engine.seek(2, 0)
        rig.run(500)
        rig.engine.setPlayWhenReady(false)
        rig.engine.seek(2, 10_000)
        rig.engine.setPlayWhenReady(true)
        rig.run(1_000)
        assertEquals(1, rig.slots.size)
        assertEquals(
            listOf("load ${rig.uids} 0 0", "play true", "offload true", "gapless false", "repeat ALL", "volume 0.5", "seek 2 0", "play false", "seek 2 10000", "play true"),
            only.calls,
        )
        assertTrue(only.focusHandled)
        assertFalse(rig.focus.handled)
        assertEquals(0, rig.focus.requests)
        assertEquals(listOf(rig.start(0), rig.start(1, natural = true), rig.start(2)), rig.heard.starts)
        assertEquals(2, rig.engine.index)
        assertEquals(only.positionMs, rig.engine.positionMs)
        assertTrue(rig.engine.isPlaying)
    }

    // Natural changes

    @Test
    fun `a natural change fades in from the second player, where the next song waits, over the chosen seconds with equal power`() = runTest {
        val rig = FadeRig(this, crossfadeSeconds = 6)
        rig.runToLeft(6_000 + PREPARE_LEAD_MS + 500)
        // PW2: the next song waits from a moment after the music started, for a Next by hand as much as for the fade, on a
        // player holding it and the one after it, never the queue.
        val (first, second) = rig.two(rig.slots)
        assertEquals(rig.uids.subList(0, 3), first.loaded)
        assertEquals(rig.uids.subList(1, 4), second.loaded)
        assertEquals(0, second.index)
        assertFalse(second.playWhenReady)
        assertEquals(Player.STATE_READY, second.playbackState)
        assertEquals(0f, second.volume, 0f)
        assertEquals(listOf(rig.start(0)), rig.heard.starts)
        rig.runToLeft(6_000 + 10)
        assertFalse(second.playWhenReady)
        rig.run(20)
        // The fade started 6 s before the end: both play, and the song coming in is the one playing now.
        assertEquals(listOf(rig.start(0), rig.start(1, natural = true)), rig.heard.starts)
        assertTrue(first.isPlaying && second.isPlaying)
        assertTrue(first.pauseAtEnd)
        rig.run(2_990)
        assertEquals(0.7071f, second.volume, 0.02f)
        assertEquals(1f, squares(first, second), 0.02f)
        rig.run(1_500)
        assertEquals(fadeInGain(0.75f), second.volume, 0.02f)
        assertEquals(1f, squares(first, second), 0.02f)
        rig.run(1_600)
        // Done: the song going out reached its end, its player is let go, the song playing is at full volume, and the song
        // after it waits on a player made now, at the fade's end.
        assertTrue(first.released)
        assertEquals(listOf(second, rig.made(2)), rig.live)
        assertEquals(rig.uids.subList(1, 4), second.loaded)
        assertEquals(rig.uids.subList(2, 4), rig.made(2).loaded)
        assertEquals(1f, second.volume, 0f)
        assertEquals(listOf(rig.start(0), rig.start(1, natural = true)), rig.heard.starts)
    }

    @Test
    fun `through a fade the session shows the song coming in from the fade's start - its place, position and length`() = runTest {
        val mixed = listOf(60_000L, 90_000L, 60_000L).mapIndexed { i, ms -> song("Music/Fade Test/Mixed %d.flac".format(i + 1), durationMs = ms) }
        val rig = FadeRig(this, crossfadeSeconds = 6, songs = mixed)
        fun view() = sessionView(rig.engine, rig.engine.isPlaying, QueueBook(), Repeat.OFF)
        rig.runToLeft(6_000 + 10)
        assertEquals(rig.uids, view().uids)
        assertEquals(0, view().index)
        assertEquals(60_000L - 6_010, view().positionMs)
        assertEquals(60_000L, rig.engine.durationMs)
        rig.run(20)
        assertEquals(rig.uids, view().uids)
        assertEquals(1, view().index)
        assertEquals(10L, view().positionMs)
        assertTrue(view().playing)
        assertEquals(90_000L, rig.engine.durationMs)
        assertEquals(rig.uids, rig.engine.items.map { it.mediaId.toLong() })
        rig.run(3_000)
        assertEquals(1, view().index)
        assertEquals(3_010L, view().positionMs)
        rig.run(4_000)
        assertEquals(1, view().index)
        assertEquals(7_010L, view().positionMs)
        assertTrue(rig.made(0).released)
        assertEquals(rig.uids, rig.engine.loaded)
    }

    // Changes by hand

    @Test
    fun `a manual Next or Previous fades over 300 ms, the song going out as the next one comes in`() = runTest {
        val rig = FadeRig(this, crossfadeSeconds = 12)
        rig.run(20_000)
        rig.engine.seek(1, 0)
        assertEquals(listOf(rig.start(0), rig.start(1)), rig.heard.starts)
        assertEquals(1, rig.engine.index)
        val (first, second) = rig.two(rig.slots)
        assertEquals(rig.uids.subList(1, 4), second.loaded)
        // 150 ms on: the song going out has faded half way, as the next one, waiting ready, came in at once (PW2).
        rig.run(150)
        assertEquals(fadeOutGain(0.5f), first.volume, 0.05f)
        assertEquals(fadeInGain(0.5f), second.volume, 0.05f)
        rig.run(300)
        assertTrue(first.released)
        assertEquals(1f, second.volume, 0f)
        // Previous, back to song 1: the same short fade, never the 12 s one, on the player the song after waited on.
        rig.run(10_000)
        val waiting = rig.made(2)
        rig.engine.seek(0, 0)
        assertEquals(listOf(second, waiting), rig.live)
        assertTrue("load ${rig.uids.subList(0, 3)} 0 0" in waiting.calls)
        rig.run(FakeSlot.BUFFER_MS + SHORT_FADE_MS + 50)
        assertEquals(listOf(waiting, rig.made(3)), rig.live)
        assertEquals(0, rig.engine.index)
        assertEquals(1f, waiting.volume, 0f)
        assertEquals(listOf(rig.start(0), rig.start(1), rig.start(0)), rig.heard.starts)
    }

    @Test
    fun `a Next by hand while the next song waits plays it from the waiting player at once, so the tap makes no player`() = runTest {
        // The phone's case (CrossfadeDeviceTest): 8 s songs and a 6 s crossfade fade over 4 s, so the next song waits from the start.
        val rig = FadeRig(this, crossfadeSeconds = 6, songs = tones(2, lengthMs = 8_000))
        rig.run(500)
        val (first, waiting) = rig.two(rig.slots)
        // A queue this short is held whole, at the song waiting.
        assertEquals(rig.uids, waiting.loaded)
        assertEquals(1, waiting.index)
        assertFalse(waiting.playWhenReady)
        rig.engine.seek(1, 0)
        assertEquals("players made by the Next", 2, rig.slots.size)
        assertTrue(waiting.isPlaying)
        assertEquals(listOf(first, waiting), rig.live)
        rig.run(SHORT_FADE_MS + 50)
        assertTrue(first.released)
        // The song a Next goes to now (round to the first) waits on a player made once the music has played a moment.
        rig.run(NEXT_READY_AFTER_MS)
        assertEquals(listOf(waiting, rig.made(2)), rig.live)
        assertEquals(1, rig.engine.index)
        assertEquals(1f, waiting.volume, 0f)
        assertEquals(listOf(rig.start(0), rig.start(1)), rig.heard.starts)
    }

    @Test
    fun `each move to another song by hand is the engine's song at once and told, in a swap, in skip spam and in a fade`() = runTest {
        // The lock screen's details are the session's state, read from the engine as the seek returns and when it says
        // it changed: a move that left the engine on the song before, or said nothing, would keep the old title (CV1).
        val rig = FadeRig(this, crossfadeSeconds = 6, songs = tones(6))
        val told = mutableListOf<Int>()
        rig.engine.addListener(object : EngineListener {
            override fun onChanged() {
                told += rig.engine.index
            }
        })
        fun move(to: Int) {
            told.clear()
            rig.engine.seek(to, 0)
            assertEquals(to, rig.engine.index)
            assertEquals(rig.uids[to], rig.engine.loaded[rig.engine.index])
            assertEquals(to, told.firstOrNull())
        }
        rig.run(10_000)
        // A song sounding and nothing fading: the swap over the short fade.
        move(1)
        // Next again and again inside the short fade.
        listOf(2, 3, 4).forEach { to ->
            rig.run(50)
            move(to)
        }
        // Another song during a natural fade.
        rig.run(1_000)
        rig.runToLeft(6_000)
        rig.run(2_000)
        rig.two(rig.live)
        move(0)
    }

    @Test
    fun `pausing never fades, and a pause during a fade stops both players where they are until playing again`() = runTest {
        val rig = FadeRig(this, crossfadeSeconds = 6)
        rig.run(10_000)
        rig.engine.setPlayWhenReady(false)
        // The next song waiting is let go, at the next turn.
        rig.run(0)
        assertEquals(1, rig.live.size)
        assertFalse(rig.slots[0].playWhenReady)
        assertEquals(1f, rig.slots[0].volume, 0f)
        rig.engine.setPlayWhenReady(true)
        rig.runToLeft(6_000)
        rig.run(2_000)
        val (first, second) = rig.two(rig.live)
        val out = first.volume
        val incoming = second.volume
        val at = second.positionMs
        rig.engine.setPlayWhenReady(false)
        assertFalse(first.isPlaying)
        assertFalse(second.isPlaying)
        assertFalse(rig.engine.playWhenReady)
        rig.run(30_000)
        assertEquals(out, first.volume, 0.01f)
        assertEquals(incoming, second.volume, 0.01f)
        assertEquals(at, second.positionMs)
        assertEquals(2, rig.live.size)
        rig.engine.setPlayWhenReady(true)
        assertTrue(first.isPlaying && second.isPlaying)
        rig.run(2_000)
        assertEquals(fadeInGain(4f / 6), second.volume, 0.03f)
        rig.run(2_100)
        assertTrue(first.released)
        assertEquals(1f, second.volume, 0f)
    }

    @Test
    fun `a seek during a fade ends it within the short fade, with no third player`() = runTest {
        val rig = FadeRig(this, crossfadeSeconds = 6)
        rig.runToLeft(6_000)
        rig.run(2_000)
        val (first, second) = rig.two(rig.live)
        rig.engine.seek(1, 30_000)
        assertEquals(30_000L, rig.engine.positionMs)
        assertTrue("seeked 30000" in rig.heard.events)
        assertEquals(2, rig.slots.size)
        rig.run(SHORT_FADE_MS + 30)
        assertTrue(first.released)
        // The next song waits again, on a player made at the fade's end.
        assertEquals(listOf(second, rig.made(2)), rig.live)
        assertEquals(1f, second.volume, 0f)
        // Paused, a seek fades nothing: the song waits at its new place at full volume.
        rig.engine.setPlayWhenReady(false)
        rig.engine.seek(1, 5_000)
        rig.run(0)
        assertEquals(1, rig.live.size)
        assertEquals(5_000L, rig.engine.positionMs)
        assertEquals(1f, second.volume, 0f)
    }

    @Test
    fun `skip spam keeps at most two players, starts each song once, and ends on the last one pressed at full volume`() = runTest {
        val rig = FadeRig(this, crossfadeSeconds = 6, songs = tones(12))
        rig.run(10_000)
        var most = 0
        repeat(6) {
            rig.engine.seek(rig.engine.index + 1, 0)
            most = maxOf(most, rig.live.size)
            rig.run(40)
            most = maxOf(most, rig.live.size)
        }
        rig.run(2_000)
        // Into a natural fade too.
        rig.runToLeft(6_000)
        rig.run(1_000)
        most = maxOf(most, rig.live.size)
        repeat(3) {
            rig.engine.seek(rig.engine.index + 1, 0)
            most = maxOf(most, rig.live.size)
            rig.run(30)
        }
        rig.run(1_000)
        assertEquals(2, most)
        assertEquals(10, rig.engine.index)
        assertEquals((0..6).map { rig.start(it) } + rig.start(7, natural = true) + (8..10).map { rig.start(it) }, rig.heard.starts)
        // The song playing at full volume, the next one waiting silent.
        val (playing, waiting) = rig.two(rig.live)
        assertEquals(1f, playing.volume, 0f)
        assertEquals(0f, waiting.volume, 0f)
        assertEquals(rig.uids[11], waiting.uid)
        assertTrue(rig.engine.isPlaying)
        assertTrue(rig.slots.all { it.most <= SONGS_HELD })
    }

    @Test
    fun `a queue edit while the next song waits loads the new next song on the waiting player before the fade`() = runTest {
        val rig = FadeRig(this, crossfadeSeconds = 6)
        rig.runToLeft(6_000 + 2_000)
        val (playing, waiting) = rig.two(rig.slots)
        rig.engine.apply(Edit.Move(3, 1))
        rig.run(0)
        // PW2: the same player takes the new next song; none is made or let go.
        assertFalse(waiting.released)
        assertEquals("players made", 2, rig.slots.size)
        assertEquals(listOf(rig.uids[3], rig.uids[1], rig.uids[2]), waiting.loaded)
        assertEquals(listOf(rig.uids[0], rig.uids[3], rig.uids[1]), playing.loaded)
        assertEquals(listOf(rig.uids[0], rig.uids[3], rig.uids[1], rig.uids[2]), rig.engine.loaded)
        rig.run(3_000)
        assertEquals(rig.start(3, natural = true), rig.heard.starts.last())
    }

    /** CR1: the bridge hands one change as several edits; the song waiting loads again once for all of them, not once each. */
    @Test
    fun `several queue edits as one change while the next song waits load one new next song`() = runTest {
        val rig = FadeRig(this, crossfadeSeconds = 6, songs = tones(6))
        rig.runToLeft(6_000 + 2_000)
        val waiting = rig.made(1)
        val loads = waiting.calls.count { it.startsWith("load ") }
        rig.engine.applyAll(listOf(Edit.Move(3, 1), Edit.Move(5, 2), Edit.Remove(4)))
        rig.run(0)
        assertFalse(waiting.released)
        assertEquals("players made", 2, rig.slots.size)
        assertEquals("loads on the waiting player", loads + 1, waiting.calls.count { it.startsWith("load ") })
        assertEquals(listOf(rig.uids[3], rig.uids[5], rig.uids[1]), waiting.loaded)
        assertEquals(listOf(rig.uids[0], rig.uids[3], rig.uids[5], rig.uids[1], rig.uids[4]), rig.engine.loaded)
        rig.run(3_000)
        assertEquals(rig.start(3, natural = true), rig.heard.starts.last())
    }

    /** CR2: a change that leaves the songs as they are (a queue renamed) is nothing to the players; the song waiting stays. */
    @Test
    fun `an empty batch of edits while the next song waits keeps it waiting, and its fade comes as planned`() = runTest {
        val rig = FadeRig(this, crossfadeSeconds = 6)
        rig.runToLeft(6_000 + 2_000)
        val waiting = rig.made(1)
        rig.engine.applyAll(emptyList())
        rig.run(0)
        assertFalse(waiting.released)
        assertEquals("players made", 2, rig.slots.size)
        rig.run(3_000)
        assertEquals(rig.start(1, natural = true), rig.heard.starts.last())
        assertTrue(waiting.isPlaying)
    }

    /**
     * A cover Covers & tags changed (Edit.Refresh): the same songs at the same places, so the song waiting stays, given
     * the new details as the player playing is, and its fade comes as planned.
     */
    @Test
    fun `a cover refresh while the next song waits keeps it waiting with the new details, and its fade comes as planned`() = runTest {
        val rig = FadeRig(this, crossfadeSeconds = 6)
        rig.runToLeft(6_000 + 2_000)
        val playing = rig.made(0)
        val waiting = rig.made(1)
        val (first, second) = rig.uids.take(2).map { Projected(it, rig.songOf(it)) }
        rig.engine.applyAll(listOf(Edit.Refresh(0, listOf(first, second))))
        rig.run(0)
        assertFalse(waiting.released)
        assertEquals("players made", 2, rig.slots.size)
        // Each player takes the songs it holds, at their places on it.
        assertTrue("the playing player's refresh", "apply ${Edit.Refresh(0, listOf(first))}" in playing.calls)
        assertTrue("the playing player's refresh", "apply ${Edit.Refresh(1, listOf(second))}" in playing.calls)
        assertTrue("the waiting player's refresh", "apply ${Edit.Refresh(0, listOf(second))}" in waiting.calls)
        rig.run(3_000)
        assertEquals(rig.start(1, natural = true), rig.heard.starts.last())
        assertTrue(waiting.isPlaying)
    }

    // Plays

    /** As PlaybackService's Follower feeds the play counter. */
    private fun TestScope.follow(rig: FadeRig, events: PlayEvents): PlayTracker {
        val tracker = PlayTracker(events, rig.clock)
        val sounding = MutableStateFlow(false)
        rig.engine.addListener(object : EngineListener {
            override fun onSongStarted(uid: Long, lap: Boolean, natural: Boolean) =
                tracker.start(
                    rig.songOf(uid).ref, rig.songOf(uid).durationMs, rig.engine.positionMs, rig.engine.isPlaying,
                    ended = if (natural) PlayEnd.FINISHED else PlayEnd.UNKNOWN, leftAtMs = rig.engine.fadeFromMs,
                )

            override fun onPlayingChanged(playing: Boolean) {
                tracker.position(rig.engine.positionMs, playing)
                sounding.value = playing
            }

            override fun onSeeked(fromMs: Long, toMs: Long) = tracker.seeked(fromMs, toMs)

            override fun onEnded() = tracker.end(PlayEnd.FINISHED)
        })
        backgroundScope.launch { repeatWhilePlaying(sounding, POSITION_EVERY_MS) { tracker.position(rig.engine.positionMs, playing = true) } }
        return tracker
    }

    @Test
    fun `a play counts once per song through fades and skips, each heard from its start`() = runTest {
        // Count a play after 100%: only a song heard from its very start counts.
        val share = MutableStateFlow<Int?>(100)
        val log = FakePlayLog()
        val counter = PlayCounter(log, share, backgroundScope)
        lateinit var tracker: PlayTracker
        val rig = FadeRig(this, crossfadeSeconds = 6, songs = tones(4, lengthMs = 40_000)) { tracker = follow(it, counter) }
        rig.run(80_000)
        // Song 3 is skipped part way by hand; song 4 then plays to the end of the queue.
        tracker.position(rig.engine.positionMs, rig.engine.isPlaying)
        rig.engine.seek(3, 0)
        rig.run(41_000)
        runCurrent()
        // One row per song: counted, counted, skipped part way, counted.
        assertEquals(listOf(listOf(true), listOf(true), listOf(false), listOf(true)), rig.songs.map { s -> log.plays.filter { it.song.path == s.path }.map { it.counted } })
    }

    @Test
    fun `a natural fade tells where the song going out was as it began, only while the song coming in is heard of`() = runTest {
        val seen = mutableListOf<Pair<Boolean, Long?>>()
        val rig = FadeRig(this, crossfadeSeconds = 6, songs = tones(3, lengthMs = 40_000)) { rig ->
            rig.engine.addListener(object : EngineListener {
                override fun onSongStarted(uid: Long, lap: Boolean, natural: Boolean) {
                    seen += natural to rig.engine.fadeFromMs
                }
            })
        }
        rig.run(41_000)
        // The second song comes in over the first's last 6 s.
        val (natural, from) = seen.last()
        assertTrue(natural)
        assertTrue("$from", from != null && from in 33_000L..34_500L)
        assertNull(rig.engine.fadeFromMs)

        // A change by hand fades too, but is no song that played to its end.
        rig.engine.seek(2, 0)
        assertEquals(false to null, seen.last())
    }

    @Test
    fun `a song crossfaded out ends as finished where its fade began, and counts`() = runTest {
        val share = MutableStateFlow<Int?>(100)
        val log = FakePlayLog()
        val counter = PlayCounter(log, share, backgroundScope)
        val rig = FadeRig(this, crossfadeSeconds = 6, songs = tones(2, lengthMs = 40_000)) { follow(it, counter) }
        rig.run(41_000)
        runCurrent()

        val first = log.plays.single { it.song.path == rig.songs[0].path }
        assertTrue(first.counted)
        assertEquals(PlayEnd.FINISHED, first.end?.how)
        assertTrue("${first.end}", first.end!!.atMs in 33_000L..34_500L)
    }

    // Albums, the sleep timer, A-B

    @Test
    fun `an album in track order with Keep albums gapless plays gapless on one player`() = runTest {
        val rig = FadeRig(this, crossfadeSeconds = 6, songs = tones(3, album = "Fade Test"), source = ALBUM)
        rig.mayFade = { from, to -> mayCrossfade(rig.queue, from, to, keepAlbumsGapless = true, part = null, sleep = null) }
        rig.run(60_000 + 1_000)
        // The first player went on into song 2 by itself; the second only waits for a Next by hand, never heard.
        val (first, waiting) = rig.two(rig.live)
        assertEquals(rig.uids[1], first.uid)
        assertEquals(listOf(rig.start(0), rig.start(1, natural = true)), rig.heard.starts)
        assertEquals(1f, first.volume, 0f)
        assertFalse("play true" in waiting.calls)
        // With the switch off, the same album fades, in from the player waiting.
        rig.mayFade = { from, to -> mayCrossfade(rig.queue, from, to, keepAlbumsGapless = false, part = null, sleep = null) }
        rig.run(60_000)
        assertEquals(rig.uids[2], waiting.uid)
        assertTrue(waiting.isPlaying)
        assertEquals(rig.start(2, natural = true), rig.heard.starts.last())
    }

    @Test
    fun `inside an album kept gapless a song playing on stays gapless, and Next by hand still takes the short fade`() = runTest {
        val rig = FadeRig(this, crossfadeSeconds = 6, songs = tones(3, album = "Fade Test"), source = ALBUM)
        rig.mayFade = { from, to -> mayCrossfade(rig.queue, from, to, keepAlbumsGapless = true, part = null, sleep = null) }
        rig.runToLeft(3_000)
        rig.run(4_000)
        assertEquals(rig.uids[1], rig.made(0).uid)
        assertEquals(rig.start(1, natural = true), rig.heard.starts.last())
        rig.run(5_000)
        // The spec: "A manual Next or Previous uses a short fade of 300 ms", album or not.
        rig.engine.seek(2, 0)
        val (first, second) = rig.two(rig.live)
        rig.run(150)
        assertTrue(first.volume in 0.01f..0.99f)
        assertTrue(second.volume in 0.01f..0.99f)
        rig.run(400)
        assertTrue(first.released)
        assertEquals(1f, second.volume, 0f)
        assertEquals(rig.start(2), rig.heard.starts.last())
    }

    @Test
    fun `the sleep timer's fade lowers both players of a fade, and its stop pauses the fade where it is`() = runTest {
        val rig = FadeRig(this, crossfadeSeconds = 6)
        val timer = MutableStateFlow<SleepTimer?>(null)
        backgroundScope.launch { SleepDriver(EngineExtras(rig.engine), timer, rig.clock).run() }
        rig.runToLeft(6_000 + 7_000)
        timer.value = SleepTimer.ByTime(endsAt = rig.clock() + 10_000, minutes = 1)
        // The crossfade started 1 s ago; the timer has 2 s left, deep in its fade: both players carry its volume.
        rig.run(8_000)
        val (first, second) = rig.two(rig.live)
        val sleep = rig.engine.volume
        assertEquals(sleepFadeVolume(2_000), sleep, 0.001f)
        assertEquals(sleep * fadeOutGain(1f / 6), first.volume, 0.0001f)
        assertEquals(sleep * fadeInGain(1f / 6), second.volume, 0.0001f)
        rig.run(2_100)
        // Stopped: both wait paused, the fade where it was, at full volume again for later.
        assertNull(timer.value)
        assertFalse(first.isPlaying || second.isPlaying)
        assertFalse(rig.engine.playWhenReady)
        assertEquals(fadeOutGain(3f / 6), first.volume, 0.03f)
        assertEquals(fadeInGain(3f / 6), second.volume, 0.03f)
        rig.engine.setPlayWhenReady(true)
        rig.run(3_100)
        assertTrue(first.released)
        assertEquals(1f, second.volume, 0f)
    }

    @Test
    fun `the sleep timer's last song ends gapless into the next, which waits paused at its start`() = runTest {
        val timer = MutableStateFlow<SleepTimer?>(sleepAfter(2))
        lateinit var driver: SleepDriver
        val rig = FadeRig(this, crossfadeSeconds = 6, songs = tones(3)) { rig ->
            driver = SleepDriver(EngineExtras(rig.engine), timer, rig.clock)
            rig.engine.addListener(object : EngineListener {
                override fun onSongStarted(uid: Long, lap: Boolean, natural: Boolean) {
                    if (natural) driver.songEnded()
                }

                override fun onEnded() = driver.queueEnded()
            })
        }
        rig.mayFade = { from, to -> mayCrossfade(rig.queue, from, to, keepAlbumsGapless = true, part = null, sleep = timer.value) }
        backgroundScope.launch { driver.run() }
        rig.run(60_000)
        assertEquals(SleepTimer.BySongs(songs = 2, left = 1), timer.value)
        rig.run(60_000)
        // Only the change from song 1 to song 2 faded: song 3 came on the player song 2 played on.
        assertEquals(rig.uids[2], rig.made(1).uid)
        assertFalse(rig.made(1).released)
        assertEquals(listOf(rig.start(0), rig.start(1, natural = true), rig.start(2, natural = true)), rig.heard.starts)
        assertNull(timer.value)
        assertEquals(2, rig.engine.index)
        assertEquals(0L, rig.engine.positionMs)
        assertFalse(rig.engine.playWhenReady)
        assertEquals(1f, rig.engine.volume, 0f)
        assertEquals(1, rig.live.size)
    }

    private class MemorySongState(var stored: SongState = SongState()) : SongStateStorage {
        override suspend fun load(): SongState = stored

        override suspend fun save(state: SongState) {
            stored = state
        }
    }

    /** The service's extras on the rig's engine, as ExtrasPart wires them: speeds, the part looping, places in [mode]. */
    private fun TestScope.extrasOn(rig: FadeRig, stored: SongState = SongState(), mode: RememberPlace = RememberPlace.OFF): PlayerExtras {
        val extras = PlayerExtras(MemorySongState(stored), backgroundScope)
        val driver = ExtrasDriver(EngineExtras(rig.engine), extras, { uid -> Playing(uid, rig.songOf(uid).ref, rig.songOf(uid).durationMs) }) { mode }
        rig.engine.addListener(object : EngineListener {
            override fun onSongStarted(uid: Long, lap: Boolean, natural: Boolean) = driver.songStarted(uid, lap, natural)

            override fun onPlayingChanged(playing: Boolean) = driver.playingChanged(playing)

            override fun onSeeked(fromMs: Long, toMs: Long) = driver.seeked(toMs)
        })
        backgroundScope.launch { driver.run() }
        return extras
    }

    @Test
    fun `with a part repeating (A-B) there is no crossfade - the part loops on one player`() = runTest {
        lateinit var extras: PlayerExtras
        val rig = FadeRig(this, crossfadeSeconds = 12) { extras = extrasOn(it) }
        rig.mayFade = { from, to -> mayCrossfade(rig.queue, from, to, keepAlbumsGapless = true, part = extras.state.value.part, sleep = null) }
        runCurrent()
        // A part from 0:40 to 0:55, inside the last 12 s where the fade would start.
        extras.change { it.copy(part = AbPart(rig.songs[0].path, 40_000, 55_000)) }
        rig.run(120_000)
        val (first, waiting) = rig.two(rig.slots)
        assertEquals(listOf(rig.start(0)), rig.heard.starts)
        assertTrue(rig.heard.events.count { it == "seeked 40000" } >= 7)
        assertEquals(1f, first.volume, 0f)
        assertFalse("play true" in waiting.calls)
        // The part cleared: the song fades into the next as usual, in from the player waiting.
        extras.change { it.copy(part = null) }
        rig.run(20_000)
        assertEquals(rig.uids[1], waiting.uid)
        assertTrue(waiting.isPlaying)
        assertEquals(rig.start(1, natural = true), rig.heard.starts.last())
    }

    @Test
    fun `a song coming in at its kept place loads there and fades in over the full fade, never as a seek`() = runTest {
        val long = tones(3, lengthMs = 700_000)
        lateinit var extras: PlayerExtras
        val rig = FadeRig(this, crossfadeSeconds = 8, songs = long) {
            extras = extrasOn(it, SongState(places = mapOf(long[1].path to 240_000L)), RememberPlace.LONG_SONGS)
        }
        rig.places = { uid, durationMs -> comingInAt(rig.queue, uid, durationMs, extras.state.value, RememberPlace.LONG_SONGS) }
        val said = mutableListOf<ExtrasSaid>()
        backgroundScope.launch { extras.said.collect { said += it } }
        runCurrent()
        rig.engine.seek(0, 700_000 - 20_000)
        rig.runToLeft(8_000)
        val (first, second) = rig.two(rig.slots)
        assertEquals(rig.start(1, natural = true), rig.heard.starts.last())
        assertTrue("load ${rig.uids} 1 240000" in second.calls)
        rig.run(4_000)
        // Half way through the full 8 s fade, the song 4 s past its place.
        assertEquals(fadeInGain(0.5f), second.volume, 0.02f)
        assertEquals(1f, squares(first, second), 0.02f)
        assertTrue(second.positionMs in 243_950L..244_050L)
        assertFalse(second.calls.any { it.startsWith("seek") })
        // P2's toast still says where it picked up.
        assertEquals(listOf<ExtrasSaid>(ExtrasSaid.Resumed(long[1].path, 240_000)), said)
        rig.run(4_100)
        assertTrue(first.released)
        assertEquals(second, rig.live.first())
        assertEquals(1f, second.volume, 0f)
    }

    @Test
    fun `a song at its kept place says Resumed only when a fade brought it there, never when a queue is loaded there`() = runTest {
        val talks = tones(2, lengthMs = 700_000)
        val extras = PlayerExtras(MemorySongState(SongState(places = talks.associate { it.path to 240_000L })), backgroundScope)
        val port = FakeExtrasPort().apply {
            positionMs = 240_000
            durationMs = 700_000
        }
        val driver = ExtrasDriver(port, extras, { uid -> Playing(uid, talks[uid.toInt() - 1].ref, 700_000) }) { RememberPlace.LONG_SONGS }
        val said = mutableListOf<ExtrasSaid>()
        backgroundScope.launch { extras.said.collect { said += it } }
        runCurrent()
        // The queue restored at its place, which is also the song's: as before, no toast.
        driver.songStarted(1, lap = false, natural = false)
        runCurrent()
        assertEquals(emptyList<ExtrasSaid>(), said)
        // Crossfade brought the next song in at its place: the toast, and nothing moves.
        port.index = 1
        driver.songStarted(2, lap = false, natural = true)
        runCurrent()
        assertEquals(listOf<ExtrasSaid>(ExtrasSaid.Resumed(talks[1].path, 240_000)), said)
        assertEquals(emptyList<Pair<Int, Long>>(), port.seeks)
    }

    // Speed, Repeat one, the end of the queue

    /** Where the song going out was when the song coming in started, as the session heard it. */
    private fun FadeRig.outAtFadeStart(): () -> Long? {
        var at: Long? = null
        engine.addListener(object : EngineListener {
            override fun onSongStarted(uid: Long, lap: Boolean, natural: Boolean) {
                if (natural && at == null) at = slots.first().positionMs
            }
        })
        return { at }
    }

    @Test
    fun `at one and a half times the speed the fade fits half the song in time, starting at its middle`() = runTest {
        val rig = FadeRig(this, crossfadeSeconds = 12, songs = tones(3, lengthMs = 20_000))
        rig.speeds = { PlaybackParameters(1.5f) }
        rig.engine.setPlaybackParameters(PlaybackParameters(1.5f))
        val out = rig.outAtFadeStart()
        rig.run(7_000)
        // Half of 20 s at 1.5x is 6.7 s of time: the fade starts at 0:10 of the song, never before.
        assertTrue("began at ${out()}", out()!! in 9_950L..10_050L)
        val (first, _) = rig.two(rig.slots.take(2))
        assertFalse(first.released)
        // And it is over 6.7 s later, as the song ends.
        rig.run(6_700)
        assertTrue(first.released)
    }

    @Test
    fun `at three quarters of the speed the full 12 s fade fits, in time`() = runTest {
        val rig = FadeRig(this, crossfadeSeconds = 12, songs = tones(3, lengthMs = 20_000))
        rig.speeds = { PlaybackParameters(0.75f) }
        rig.engine.setPlaybackParameters(PlaybackParameters(0.75f))
        val out = rig.outAtFadeStart()
        rig.run(18_000)
        // 12 s of time at 0.75x is 9 s of the song: the fade starts at 0:11.
        assertTrue("began at ${out()}", out()!! in 10_950L..11_050L)
        val (first, second) = rig.two(rig.slots.take(2))
        rig.run(6_000)
        assertTrue(first.volume in 0.01f..0.99f)
        assertEquals(1f, squares(first, second), 0.02f)
        rig.run(6_100)
        assertTrue(first.released)
    }

    @Test
    fun `a second player left at another speed takes the song's own speed before its fade, and the fade length follows it`() = runTest {
        val songs = listOf(200_000L, 20_000L, 20_000L).mapIndexed { i, ms -> song("Music/Fade Test/Speed %d.flac".format(i + 1), durationMs = ms) }
        val rig = FadeRig(this, crossfadeSeconds = 12, songs = songs)
        var speed = 1f
        rig.speeds = { uid -> PlaybackParameters(if (uid == rig.uids[1]) speed else 1f) }
        rig.runToLeft(12_000 + 2_000)
        val waiting = rig.made(1)
        assertEquals(1f, waiting.playbackParameters.speed, 0f)
        // The Speed sheet: song 2 now plays at 1.5x, after its player was made.
        speed = 1.5f
        rig.run(1_000)
        assertEquals(1.5f, waiting.playbackParameters.speed, 0f)
        // Half of its 20 s at 1.5x is 6.7 s of time: the fade starts 6.7 s before the end, not 10.
        rig.runToLeft(6_666 + 10)
        assertEquals(listOf(rig.start(0)), rig.heard.starts)
        rig.run(20)
        assertEquals(rig.start(1, natural = true), rig.heard.starts.last())
        assertEquals(1.5f, rig.engine.playbackParameters.speed, 0f)
    }

    @Test
    fun `the song coming in is capped by its real length once loaded, so a short file never ends inside the fade`() = runTest {
        // The library says song 2 is 60 s; its file is 5 s.
        val rig = FadeRig(this, crossfadeSeconds = 12, files = mapOf(1 to 5_000L))
        rig.runToLeft(12_000 - 100)
        assertEquals(listOf(rig.start(0)), rig.heard.starts)
        rig.runToLeft(2_500 + 10)
        assertEquals(listOf(rig.start(0)), rig.heard.starts)
        rig.run(20)
        assertEquals(rig.start(1, natural = true), rig.heard.starts.last())
        rig.run(2_500 + 30)
        // Over in 2.5 s, half the real file; song 2 then plays on, and song 3 has not started inside the fade.
        val (first, second) = rig.two(rig.slots.take(2))
        assertTrue(first.released)
        assertEquals(1f, second.volume, 0f)
        assertEquals(listOf(rig.start(0), rig.start(1, natural = true)), rig.heard.starts)
    }

    @Test
    fun `a song coming in that ends inside the fade anyway finishes the fade at once and plays on`() = runTest {
        // Song 2 says 60 s, as decoded too, but its file stops at 4 s.
        val rig = FadeRig(this, crossfadeSeconds = 12, ends = mapOf(1 to 4_000L))
        rig.runToLeft(12_000)
        val (first, second) = rig.two(rig.slots)
        assertEquals(rig.start(1, natural = true), rig.heard.starts.last())
        rig.run(4_000 + 20)
        // Its player went on to song 3 by itself: the fade is over at once, song 3 at full volume on it.
        assertEquals(rig.start(2, natural = true), rig.heard.starts.last())
        assertTrue(first.released)
        assertEquals(rig.uids[2], second.uid)
        assertEquals(1f, second.volume, 0f)
        assertEquals(2, rig.engine.index)
    }

    @Test
    fun `the song coming in starts at its own speed, and the song going out keeps its own`() = runTest {
        val rig = FadeRig(this, crossfadeSeconds = 6)
        rig.speeds = { uid -> if (uid == rig.uids[1]) PlaybackParameters(1.5f) else null }
        rig.runToLeft(6_000 + 1_000)
        val (first, second) = rig.two(rig.slots)
        assertEquals(1.5f, second.playbackParameters.speed, 0f)
        rig.run(2_000)
        assertEquals(1f, first.playbackParameters.speed, 0f)
        assertEquals(1.5f, rig.engine.playbackParameters.speed, 0f)
        // The Speed sheet changes the song playing (the one coming in), never the one going out.
        rig.engine.setPlaybackParameters(PlaybackParameters(1.25f))
        assertEquals(1.25f, second.playbackParameters.speed, 0f)
        assertEquals(1f, first.playbackParameters.speed, 0f)
        // Its own fade starts 6 s of time before its end, at its speed.
        rig.run(5_000)
        rig.runToLeft(6_000 + 10)
        assertEquals(rig.start(1, natural = true), rig.heard.starts.last())
        rig.run(20)
        assertEquals(rig.start(2, natural = true), rig.heard.starts.last())
    }

    @Test
    fun `the service's own speeds reach the song coming in before it is heard`() = runTest {
        lateinit var extras: PlayerExtras
        val rig = FadeRig(this, crossfadeSeconds = 6) { extras = extrasOn(it) }
        rig.speeds = { uid -> extras.state.value.speedOf(rig.songOf(uid).path).let { PlaybackParameters(it.speed, it.pitch) } }
        runCurrent()
        extras.change { it.copy(speeds = mapOf(rig.songs[1].path to Speed(0.75f))) }
        rig.runToLeft(6_000 + 10)
        val (first, second) = rig.two(rig.slots)
        assertEquals(0.75f, second.playbackParameters.speed, 0f)
        rig.run(20)
        runCurrent()
        assertEquals(0.75f, rig.engine.playbackParameters.speed, 0f)
        assertEquals(1f, first.playbackParameters.speed, 0f)
        assertFalse("speed 1.0" in second.calls.drop(second.calls.indexOf("play true")))
    }

    @Test
    fun `Repeat one laps gapless on one player, and the last song of the queue ends with no fade`() = runTest {
        val rig = FadeRig(this, crossfadeSeconds = 6, songs = tones(2))
        rig.engine.setRepeat(Repeat.ONE)
        rig.run(60_000 + 1_000)
        // It laps on the first player, holding the whole short queue; the second waits for a Next by hand, never heard.
        val (first, waiting) = rig.two(rig.slots)
        assertEquals(rig.uids, first.loaded)
        assertEquals(Repeat.ONE, first.repeat)
        assertFalse("play true" in waiting.calls)
        assertEquals(listOf(rig.start(0), rig.start(0, natural = true, lap = true)), rig.heard.starts)
        rig.engine.setRepeat(Repeat.OFF)
        rig.run(60_000)
        rig.run(60_000)
        assertEquals(listOf(rig.start(0), rig.start(0, natural = true, lap = true), rig.start(1, natural = true)), rig.heard.starts)
        assertTrue("ended" in rig.heard.events)
        assertTrue(first.released)
        assertEquals(Player.STATE_ENDED, waiting.playbackState)
        assertFalse(rig.engine.isPlaying)
    }

    // Songs that fail

    @Test
    fun `a song that fails to load ahead plays its change gapless, with no retry`() = runTest {
        val rig = FadeRig(this, crossfadeSeconds = 6)
        rig.runToLeft(6_000 + 1_000)
        val ahead = rig.made(1)
        ahead.fail()
        rig.run(0)
        assertTrue(ahead.released)
        rig.run(1_500)
        assertEquals(2, rig.slots.size)
        rig.run(6_000)
        assertEquals(listOf(rig.start(0), rig.start(1, natural = true)), rig.heard.starts)
        // Song 2 played on the first player; the song after it waits on a new one.
        assertEquals(rig.uids[1], rig.slots[0].uid)
        assertEquals(1f, rig.slots[0].volume, 0f)
        assertEquals(listOf(rig.slots[0], rig.made(2)), rig.live)
        assertFalse(rig.heard.events.any { it.startsWith("error") })
    }

    @Test
    fun `a song that fails in a fade is skipped with the short fade, as the service skips it`() = runTest {
        val rig = FadeRig(this, crossfadeSeconds = 6) { rig ->
            rig.engine.addListener(object : EngineListener {
                override fun onError(error: PlaybackException, next: Int) {
                    if (next >= 0) rig.engine.seek(next, 0)
                }
            })
        }
        rig.runToLeft(6_000)
        rig.run(1_000)
        val (first, second) = rig.two(rig.live)
        second.fail()
        assertTrue("error 2" in rig.heard.events)
        assertEquals(2, rig.engine.index)
        rig.run(FakeSlot.BUFFER_MS + SHORT_FADE_MS + 50)
        assertTrue(first.released)
        assertEquals(rig.uids[2], second.uid)
        assertEquals(1f, second.volume, 0f)
        assertTrue(second.isPlaying)
        assertEquals(listOf(rig.start(0), rig.start(1, natural = true), rig.start(2)), rig.heard.starts)
    }

    // Focus, the setting, what the second player costs

    @Test
    fun `one audio focus for both players - a call pauses both and the fade waits, ducking lowers both, a loss stops`() = runTest {
        val rig = FadeRig(this, crossfadeSeconds = 6)
        assertTrue(rig.focus.handled)
        assertTrue(rig.focus.held)
        assertEquals(1, rig.focus.requests)
        assertFalse(rig.slots[0].focusHandled)
        rig.runToLeft(6_000)
        rig.run(1_500)
        val (first, second) = rig.two(rig.live)
        assertFalse(second.focusHandled)
        val out = first.volume
        rig.focus.callStarts()
        assertFalse(first.isPlaying || second.isPlaying)
        assertTrue(rig.engine.playWhenReady)
        assertEquals(Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS, rig.engine.suppression)
        rig.run(20_000)
        assertEquals(out, first.volume, 0.01f)
        rig.focus.callEnds()
        assertTrue(first.isPlaying && second.isPlaying)
        assertEquals(Player.PLAYBACK_SUPPRESSION_REASON_NONE, rig.engine.suppression)
        rig.focus.duck(true)
        assertEquals(0.04f, squares(first, second), 0.005f)
        rig.focus.duck(false)
        assertEquals(1f, squares(first, second), 0.02f)
        assertEquals("one request for both players", 1, rig.focus.requests)
        rig.run(5_000)
        rig.focus.lose()
        assertFalse(rig.engine.playWhenReady)
        assertFalse(second.playWhenReady)
        rig.engine.setPlayWhenReady(true)
        assertEquals(2, rig.focus.requests)
        assertTrue(second.isPlaying)
        // Denied: it stays paused.
        rig.focus.lose()
        rig.focus.deny = true
        rig.engine.setPlayWhenReady(true)
        assertFalse(rig.engine.playWhenReady)
        assertFalse(second.playWhenReady)
    }

    @Test
    fun `turning crossfade on or off between songs moves focus and never stops the song playing`() = runTest {
        val rig = FadeRig(this, crossfadeSeconds = 0)
        rig.run(5_000)
        val first = rig.slots.single()
        rig.engine.setCrossfade(6)
        assertFalse(first.focusHandled)
        assertTrue(rig.focus.handled)
        assertTrue(rig.focus.held)
        assertTrue(first.isPlaying)
        // On, the one player keeps its song and the two after it, the song playing on; the session still sees the queue.
        assertEquals(rig.uids.subList(0, 3), first.loaded)
        assertEquals(rig.uids, rig.engine.loaded)
        rig.runToLeft(6_000)
        rig.run(1_000)
        val second = rig.made(1)
        // Off in the middle of a fade: it ends within the short fade, and the one player has its focus back, and the whole
        // queue around its song, which plays on.
        rig.engine.setCrossfade(0)
        assertEquals(rig.uids, second.loaded)
        assertEquals(1, second.index)
        assertTrue(second.isPlaying)
        rig.run(SHORT_FADE_MS + 30)
        assertTrue(first.released)
        assertEquals(listOf(second), rig.live)
        assertTrue(second.focusHandled)
        assertFalse(rig.focus.handled)
        assertFalse(rig.focus.held)
        assertEquals(1f, second.volume, 0f)
        assertTrue(second.isPlaying)
        rig.runToLeft(3_000)
        rig.run(4_000)
        assertEquals(2, rig.slots.size)
        assertEquals(rig.start(2, natural = true), rig.heard.starts.last())
        // Neither player loaded its songs again: the first only at the start, the second once, as the next song waiting.
        assertEquals(1, first.calls.count { it.startsWith("load") })
        assertEquals(1, second.calls.count { it.startsWith("load") })
    }

    @Test
    fun `the second player lives only while the music plays - let go when it pauses before a fade, and when a paused fade waits a minute`() = runTest {
        val rig = FadeRig(this, crossfadeSeconds = 6)
        rig.runToLeft(6_000 + 1_000)
        assertEquals(2, rig.live.size)
        rig.engine.setPlayWhenReady(false)
        rig.run(0)
        assertEquals(1, rig.live.size)
        rig.engine.setPlayWhenReady(true)
        rig.run(FakeSlot.BUFFER_MS + 50)
        assertEquals(2, rig.live.size)
        rig.run(3_000)
        val (_, second) = rig.two(rig.live)
        rig.engine.setPlayWhenReady(false)
        rig.run(FROZEN_FADE_MS - 1_000)
        assertEquals(2, rig.live.size)
        rig.run(1_100)
        assertEquals(listOf(second), rig.live)
        assertEquals(1f, second.volume, 0f)
        rig.engine.setPlayWhenReady(true)
        assertTrue(second.isPlaying)
    }

    @Test
    fun `with crossfade on, offload stays off and both players share one audio session`() = runTest {
        val rig = FadeRig(this, crossfadeSeconds = 6)
        rig.engine.setOffload(true)
        assertFalse(rig.slots[0].offload)
        rig.runToLeft(6_000 + 1_000)
        val (first, second) = rig.two(rig.slots)
        assertFalse(second.offload)
        assertEquals(first.audioSessionId, second.audioSessionId)
        rig.run(8_000)
        assertEquals(first.audioSessionId, rig.engine.audioSessionId)
        // Crossfade off: as A1's offload gate does, offload comes back only at the next song's start, never mid-song.
        rig.engine.setCrossfade(0)
        assertFalse(second.offload)
        rig.runToLeft(0)
        rig.run(50)
        assertEquals(rig.start(2, natural = true), rig.heard.starts.last())
        assertTrue(second.offload)
    }

    // What each player holds (PW2)

    @Test
    fun `with crossfade on each player holds its song and the two after it, never the queue, and the session sees the queue`() = runTest {
        val rig = FadeRig(this, crossfadeSeconds = 6, songs = tones(8, lengthMs = 30_000))
        rig.run(2_000)
        val (first, waiting) = rig.two(rig.live)
        assertEquals(rig.uids.subList(0, 3), first.loaded)
        assertEquals(rig.uids.subList(1, 4), waiting.loaded)
        assertEquals(rig.uids, rig.engine.loaded)
        assertEquals(rig.uids, rig.engine.items.map { it.mediaId.toLong() })
        // A Next, songs playing to their ends (fading, then gapless where Keep albums gapless would say so), a Previous.
        rig.engine.seek(1, 0)
        rig.run(31_000)
        rig.mayFade = { _, _ -> false }
        rig.run(30_000)
        rig.engine.seek(rig.engine.index - 1, 0)
        rig.run(2_000)
        assertEquals(listOf(rig.start(0), rig.start(1), rig.start(2, natural = true), rig.start(3, natural = true), rig.start(2)), rig.heard.starts)
        assertEquals(2, rig.engine.index)
        val (playing, next) = rig.two(rig.live)
        assertEquals(rig.uids.subList(2, 5), playing.loaded)
        assertEquals(rig.uids[2], playing.uid)
        assertEquals(rig.uids.subList(3, 6), next.loaded)
        assertEquals(rig.uids, rig.engine.loaded)
        assertTrue("most songs a player held: ${rig.slots.map { it.most }}", rig.slots.all { it.most <= SONGS_HELD })
    }

    @Test
    fun `with Repeat all the last song goes on into the first, which the player holds after it`() = runTest {
        val rig = FadeRig(this, crossfadeSeconds = 6, songs = tones(5, lengthMs = 20_000))
        rig.mayFade = { _, _ -> false }
        rig.engine.setRepeat(Repeat.ALL)
        rig.run(81_000)
        val playing = rig.made(0)
        assertEquals(listOf(rig.uids[4], rig.uids[0], rig.uids[1]), playing.loaded)
        // Repeat all goes round through the queue, so the player itself never goes round its own three songs.
        assertEquals(Repeat.OFF, playing.repeat)
        rig.run(20_000)
        assertEquals(listOf(rig.start(0)) + (1..4).map { rig.start(it, natural = true) } + rig.start(0, natural = true), rig.heard.starts)
        assertEquals(0, rig.engine.index)
        assertEquals(rig.uids.subList(0, 3), playing.loaded)
    }

    /** Grok review: a queue of a few songs is held whole, Repeat as it is, so the player goes round by itself, as off. */
    @Test
    fun `a queue of two songs with Repeat all is held whole and goes round on its player by itself`() = runTest {
        val rig = FadeRig(this, crossfadeSeconds = 6, songs = tones(2, lengthMs = 20_000))
        rig.mayFade = { _, _ -> false }
        rig.engine.setRepeat(Repeat.ALL)
        rig.run(21_000)
        val playing = rig.made(0)
        assertEquals(rig.uids, playing.loaded)
        assertEquals(Repeat.ALL, playing.repeat)
        rig.run(20_000)
        assertEquals(listOf(rig.start(0), rig.start(1, natural = true), rig.start(0, natural = true)), rig.heard.starts)
        assertEquals(rig.uids, playing.loaded)
        assertEquals(0, rig.engine.index)
    }

    /**
     * CR2 for the players crossfade keeps a few songs on (Grok review): at a song's end ExoPlayer's own thread goes on to
     * the next song before the main thread hears of it, so a queue edit or Repeat one coming in then must never take that
     * song out from under it. Taken out, it would start another song from its start, or end playback (the service would
     * then pause and go to the first song).
     */
    @Test
    fun `a queue edit or Repeat one coming just as a song ends never stops or restarts the song the player went on to`() = runTest {
        val rig = FadeRig(this, crossfadeSeconds = 6, songs = tones(8))
        rig.mayFade = { _, _ -> false }
        // The race can only happen as the song ends.
        rig.runToLeft(500)
        val playing = rig.made(0)
        playing.goOnAhead()
        assertEquals(rig.uids[1], playing.ahead)
        // Play next puts song 6 right after the song playing.
        rig.engine.applyAll(listOf(Edit.Move(5, 1)))
        rig.run(0)
        assertEquals(listOf(rig.uids[0], rig.uids[5], rig.uids[1]), playing.loaded)
        // Repeat one comes on.
        rig.engine.setRepeat(Repeat.ONE)
        rig.run(0)
        assertEquals(rig.uids[1], playing.ahead)
        rig.engine.setRepeat(Repeat.OFF)
        // The song it went on to moves far down the queue, out of the songs the player holds next, and the queue is
        // reordered around it (round-2 review): it stays on the player while the queue has it.
        rig.engine.applyAll(listOf(Edit.Move(2, 6)))
        rig.run(0)
        rig.engine.applyAll(listOf(Edit.Move(1, 4), Edit.Move(3, 1)))
        rig.run(0)
        assertEquals(rig.uids[1], playing.ahead)
        assertFalse("started again", playing.restartedAhead)
        assertFalse("ended", playing.endedAhead)
        assertTrue(rig.uids[1] in playing.loaded)
        // The player says the song started: from there it holds the queue's next songs, and the songs before go.
        playing.wentOn()
        rig.run(0)
        val at = rig.engine.loaded.indexOf(rig.uids[1])
        assertEquals(at, rig.engine.index)
        assertEquals(rig.engine.loaded.drop(at).take(SONGS_HELD), playing.loaded)
    }

    @Test
    fun `Repeat all turned off just as the last song went round keeps the first song playing`() = runTest {
        val rig = FadeRig(this, crossfadeSeconds = 6, songs = tones(6, lengthMs = 20_000))
        rig.mayFade = { _, _ -> false }
        rig.engine.setRepeat(Repeat.ALL)
        rig.run(5 * 20_000 + 19_500)
        val playing = rig.made(0)
        assertEquals(5, rig.engine.index)
        playing.goOnAhead()
        assertEquals(rig.uids[0], playing.ahead)
        rig.engine.setRepeat(Repeat.OFF)
        rig.run(0)
        assertEquals(rig.uids[0], playing.ahead)
        assertFalse("ended", playing.endedAhead)
        assertFalse("started again", playing.restartedAhead)
    }

    /** Round-3 review: a song kept for the thread never plays next by itself when no race happened. */
    @Test
    fun `Repeat all turned off near the end of the last song ends the queue there, never on a song kept in case`() = runTest {
        val rig = FadeRig(this, crossfadeSeconds = 6, songs = tones(6, lengthMs = 20_000))
        rig.mayFade = { _, _ -> false }
        rig.engine.setRepeat(Repeat.ALL)
        rig.run(5 * 20_000 + 18_000)
        assertEquals(5, rig.engine.index)
        rig.engine.setRepeat(Repeat.OFF)
        rig.run(3_000)
        assertTrue("ended" in rig.heard.events)
        assertEquals(listOf(rig.start(0)) + (1..5).map { rig.start(it, natural = true) }, rig.heard.starts)
    }

    /** Round-3 review: far from the song's end its thread plays it for sure, so nothing is kept in case. */
    @Test
    fun `Play next again and again early in a long song leaves the player holding its three songs`() = runTest {
        val rig = FadeRig(this, crossfadeSeconds = 6, songs = tones(8))
        rig.run(10_000)
        val playing = rig.made(0)
        listOf(Edit.Move(5, 1), Edit.Move(6, 1), Edit.Move(7, 1)).forEach { edit ->
            rig.engine.applyAll(listOf(edit))
            rig.run(0)
        }
        assertEquals(rig.engine.loaded.take(SONGS_HELD), playing.loaded)
        assertTrue("most songs held: ${playing.most}", playing.most <= SONGS_HELD)
    }

    /**
     * Round-3 review: a song the thread may be on stays on the player through every change, never taken out and put back
     * (that starts it again): when another song the thread may be on leaves the queue, and when Crossfade goes off.
     */
    @Test
    fun `a song the thread may be on is never taken out and put back when another leaves the queue or Crossfade goes off`() = runTest {
        val rig = FadeRig(this, crossfadeSeconds = 6, songs = tones(8))
        rig.mayFade = { _, _ -> false }
        rig.runToLeft(500)
        val playing = rig.made(0)
        playing.goOnAhead()
        // The song it went on to moves far down the queue: song 3 is next now, and is kept in case too.
        rig.engine.applyAll(listOf(Edit.Move(1, 6)))
        rig.run(0)
        assertEquals(rig.uids[2], playing.loaded.getOrNull(playing.index + 1))
        // Song 3 leaves the queue.
        rig.engine.applyAll(listOf(Edit.Remove(1)))
        rig.run(0)
        assertEquals(rig.uids[1], playing.ahead)
        assertFalse("started again", playing.restartedAhead)
        assertFalse(rig.uids[2] in playing.loaded)
        rig.engine.setCrossfade(0)
        rig.run(0)
        assertEquals(rig.uids[1], playing.ahead)
        assertFalse("started again", playing.restartedAhead)
        assertEquals(listOf(0, 3, 4, 5, 6, 1, 7).map(rig.uids::get), playing.loaded)
    }

    /** Round-2 review: a queue cut down to the songs the player already holds is held whole, so Repeat all reaches it. */
    @Test
    fun `a queue cut down to the three songs the player holds goes round with Repeat all`() = runTest {
        val rig = FadeRig(this, crossfadeSeconds = 6, songs = tones(5, lengthMs = 20_000))
        rig.mayFade = { _, _ -> false }
        rig.engine.setRepeat(Repeat.ALL)
        rig.run(2 * 20_000 + 2_000)
        val playing = rig.made(0)
        assertEquals(rig.uids.subList(2, 5), playing.loaded)
        assertEquals(Repeat.OFF, playing.repeat)
        rig.engine.applyAll(listOf(Edit.Remove(0, 2)))
        rig.run(0)
        assertEquals(rig.uids.subList(2, 5), playing.loaded)
        assertEquals(Repeat.ALL, playing.repeat)
        rig.run(3 * 20_000)
        assertEquals(rig.start(2, natural = true), rig.heard.starts.last())
        assertFalse("ended" in rig.heard.events)
    }

    @Test
    fun `a queue of one song with Repeat all laps on the player holding it`() = runTest {
        val rig = FadeRig(this, crossfadeSeconds = 6, songs = tones(1, lengthMs = 20_000))
        rig.engine.setRepeat(Repeat.ALL)
        rig.run(21_000)
        assertEquals(Repeat.ALL, rig.made(0).repeat)
        assertEquals(listOf(rig.start(0), rig.start(0, natural = true)), rig.heard.starts)
        assertEquals(1, rig.slots.size)
    }

    @Test
    fun `the song a Next goes to loads a moment after Play, never in the turn of the tap`() = runTest {
        val rig = FadeRig(this, crossfadeSeconds = 6, play = false)
        rig.engine.setPlayWhenReady(true)
        assertEquals(1, rig.slots.size)
        rig.run(NEXT_READY_AFTER_MS - 200)
        assertEquals(1, rig.slots.size)
        rig.run(NEXT_READY_AFTER_MS)
        assertEquals(2, rig.slots.size)
        assertEquals(rig.uids.subList(1, 4), rig.made(1).loaded)
        // Paused, it is let go; Play again makes none in its turn, and the next song waits again a moment later.
        rig.engine.setPlayWhenReady(false)
        rig.run(0)
        assertEquals(1, rig.live.size)
        rig.engine.setPlayWhenReady(true)
        assertEquals(1, rig.live.size)
        rig.run(NEXT_READY_AFTER_MS + LOOK_EVERY_MS)
        assertEquals(2, rig.live.size)
    }

    @Test
    fun `a Next by hand plays the song waiting, and a Previous loads its song on the waiting player - no tap makes a player`() = runTest {
        val rig = FadeRig(this, crossfadeSeconds = 6, songs = tones(6))
        rig.run(2_000)
        val waiting = rig.made(1)
        rig.engine.seek(1, 0)
        assertEquals("players made by Next", 2, rig.slots.size)
        assertTrue(waiting.isPlaying)
        rig.run(1_000)
        val next = rig.made(2)
        rig.engine.seek(0, 0)
        assertEquals("players made by Previous", 3, rig.slots.size)
        assertTrue("load ${rig.uids.subList(0, 3)} 0 0" in next.calls)
        assertEquals(listOf(rig.start(0), rig.start(1), rig.start(0)), rig.heard.starts)
        assertEquals(0, rig.engine.index)
    }

    @Test
    fun `a song's new file reaches each player holding it, in place`() = runTest {
        val rig = FadeRig(this, crossfadeSeconds = 6)
        rig.run(2_000)
        val (playing, waiting) = rig.two(rig.live)
        val moved = Projected(rig.uids[1], song("Music/Fade Test/Moved/02 Tone 2.flac", durationMs = 60_000))
        rig.engine.applyAll(listOf(Edit.Replace(1, moved)))
        rig.run(0)
        assertTrue("apply ${Edit.Replace(1, moved)}" in playing.calls)
        assertTrue("apply ${Edit.Replace(0, moved)}" in waiting.calls)
        assertEquals(listOf(playing, waiting), rig.live)
        assertEquals(rig.uids, rig.engine.loaded)
    }

    /**
     * The user's case (pf1 N5, item 76): Crossfade on and a queue of the whole library. Before PW2 each Next made a new
     * ExoPlayer and handed it the whole queue on the main thread. Now the song waits on the second player, so a Next
     * makes none and hands none; a Previous hands three songs to that player. The session's list of songs stays the same
     * list, so its timeline is kept. Each press is timed in the engine's turn, on fake players (laptop JIT).
     */
    @Test
    fun `a Next by hand in a library-sized queue makes no player and hands no player the queue`() = runTest {
        val rig = FadeRig(this, crossfadeSeconds = 6, songs = tones(LIBRARY_SIZED, lengthMs = 240_000))
        rig.run(3_000)
        val shown = rig.engine.items
        val times = mutableListOf<Long>()
        var made = 0
        var handed = 0L
        repeat(PRESSES) { step ->
            val players = rig.slots.size
            val before = rig.slots.sumOf { it.handed }
            val started = System.nanoTime()
            // Next, and every fourth time Previous.
            rig.engine.seek(if (step % 4 == 3) rig.engine.index - 1 else rig.engine.index + 1, 0)
            times += System.nanoTime() - started
            made += rig.slots.size - players
            handed += rig.slots.sumOf { it.handed } - before
            rig.run(1_500)
        }
        println(
            "PW2 measure: crossfade on, $PRESSES presses in a $LIBRARY_SIZED-song queue made $made players and handed the players $handed songs in their turns; " +
                "most songs a player held ${rig.slots.maxOf { it.most }}; median %.3f ms in the engine's turn".format(times.sorted()[PRESSES / 2] / 1e6),
        )
        assertEquals("players made in a press's turn", 0, made)
        assertTrue("songs handed in the presses' turns: $handed", handed <= SONGS_HELD.toLong() * PRESSES / 4 + SONGS_HELD)
        assertTrue("most songs a player held: ${rig.slots.maxOf { it.most }}", rig.slots.all { it.most <= SONGS_HELD })
        assertTrue("the session's songs stay the same list", rig.engine.items === shown)
        assertEquals(PRESSES + 1, rig.heard.starts.size)
    }

    // Round 4, the whole engine

    @Test
    fun `headphones out during a fade pause both players where they are, whichever pauses itself first, and the fade goes on at Play`() = runTest {
        val rig = FadeRig(this, crossfadeSeconds = 6)
        rig.runToLeft(6_000)
        rig.run(2_000)
        val (out, incoming) = rig.two(rig.live)
        // Each player hears the phone on its own: the song going out first.
        out.unplugged()
        rig.run(0)
        incoming.unplugged()
        rig.run(0)
        assertFalse(out.released)
        assertFalse(rig.engine.playWhenReady)
        assertFalse(out.isPlaying || incoming.isPlaying)
        rig.run(10_000)
        assertEquals(listOf(out, incoming), rig.live)
        rig.engine.setPlayWhenReady(true)
        assertTrue(out.isPlaying && incoming.isPlaying)
        rig.run(4_100)
        assertTrue(out.released)
        assertEquals(1f, incoming.volume, 0f)
    }

    /** A player holds a few songs; one that ran past them before the main thread heard goes on, as the queue does. */
    @Test
    fun `a player that ran past the songs it holds goes on to the queue's next song, never ending the queue there`() = runTest {
        val rig = FadeRig(this, crossfadeSeconds = 6, songs = tones(6))
        rig.mayFade = { _, _ -> false }
        rig.run(10_000)
        rig.made(0).runsOut()
        rig.run(FakeSlot.BUFFER_MS + 50)
        assertFalse("ended" in rig.heard.events)
        assertEquals(rig.start(1), rig.heard.starts.last())
        assertEquals(1, rig.engine.index)
        assertTrue(rig.engine.isPlaying)
    }

    /** A part repeating whose song played on past the loop's end goes back to A at once, as without crossfade. */
    @Test
    fun `a part repeating that played past its song's end jumps back to A, with no fade`() = runTest {
        lateinit var extras: PlayerExtras
        val rig = FadeRig(this, crossfadeSeconds = 6) { extras = extrasOn(it) }
        rig.mayFade = { from, to -> mayCrossfade(rig.queue, from, to, keepAlbumsGapless = true, part = extras.state.value.part, sleep = null) }
        runCurrent()
        // The loop's end lies past the song's end, so the song plays out and the next one starts.
        extras.change { it.copy(part = AbPart(rig.songs[0].path, 50_000, 65_000)) }
        rig.run(60_000 + 100)
        assertEquals(rig.start(0), rig.heard.starts.last())
        assertEquals(0, rig.engine.index)
        assertTrue(rig.engine.positionMs in 50_000L..50_200L)
        // Back on the player it played on, at full volume: no fade in.
        assertEquals(rig.uids[0], rig.made(0).uid)
        assertEquals(1f, rig.made(0).volume, 0f)
    }

    // When the second player lets go (the lead's round 3)

    @Test
    fun `the waiting player is let go when Crossfade goes off, on stop, and when the service ends`() = runTest {
        listOf<(CrossfadeEngine) -> Unit>({ it.setCrossfade(0) }, { it.stop() }, { it.release() }).forEachIndexed { case, end ->
            val rig = FadeRig(this, crossfadeSeconds = 6)
            rig.run(2_000)
            val waiting = rig.made(1)
            end(rig.engine)
            rig.run(0)
            assertTrue("case $case", waiting.released)
        }
    }

    @Test
    fun `the phone asking for memory back lets the waiting player go, and it waits again only near a fade or once the next song starts`() = runTest {
        val rig = FadeRig(this, crossfadeSeconds = 6)
        rig.run(2_000)
        val waiting = rig.made(1)
        rig.engine.trimMemory()
        rig.run(0)
        assertTrue(waiting.released)
        rig.run(30_000)
        assertEquals("players while this song plays far from its fade", 1, rig.live.size)
        // As before PW2, the next song still loads ahead of its fade, which comes as planned.
        rig.runToLeft(6_000 + PREPARE_LEAD_MS - 1_000)
        assertEquals(2, rig.live.size)
        rig.run(4_000 + 6_000 + 50)
        assertEquals(rig.start(1, natural = true), rig.heard.starts.last())
        // From the next song on, the song a Next goes to waits again.
        rig.run(NEXT_READY_AFTER_MS + LOOK_EVERY_MS)
        val (playing, next) = rig.two(rig.live)
        assertEquals(rig.uids[1], playing.uid)
        assertEquals(rig.uids[2], next.uid)
    }

    private companion object {
        const val LIBRARY_SIZED = 7_000
        const val PRESSES = 41
    }
}
