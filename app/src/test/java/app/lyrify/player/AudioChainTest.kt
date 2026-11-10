@file:OptIn(UnstableApi::class)

package app.lyrify.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.Timeline
import androidx.media3.common.audio.AudioProcessor.StreamMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import app.lyrify.audio.EqSound
import app.lyrify.audio.ReplayGainTags
import app.lyrify.audio.Signals
import app.lyrify.settings.AdvancedSettings
import app.lyrify.settings.AudioSettings
import app.lyrify.settings.Levelling
import app.lyrify.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import app.lyrify.audio.SongLevels as StreamLevels

/** A1's audio chain inside P1's engine: one chain per player, live settings, and each stream's levels. */
class AudioChainTest {
    /** The queue playing: item 7 continues its album in track order, item 8 does not. */
    private val queue = object : SongLevels {
        override fun albumInOrder(mediaId: String): Boolean = mediaId == "7"
    }

    @Test
    fun `levelling or the equalizer on is processing, so audio offload stays off`() {
        val off = Settings(audio = AudioSettings(levelling = Levelling.OFF))
        val auto = Settings()

        assertFalse(AudioChain.processing(off, EqSound()))
        assertTrue(AudioChain.processing(auto, EqSound()))
        assertTrue(AudioChain.processing(off, EqSound(enabled = true)))
        // Set but switched off: the equalizer changes nothing.
        assertFalse(AudioChain.processing(off, EqSound(preampDb = 6f, mono = true)))
        assertTrue(offloadAllowed(AdvancedSettings(audioOffload = true), AudioChain.processing(off, EqSound())))
        assertFalse(offloadAllowed(AdvancedSettings(audioOffload = true), AudioChain.processing(auto, EqSound())))
        assertFalse(offloadAllowed(AdvancedSettings(audioOffload = true), AudioChain.processing(off, EqSound(enabled = true))))
    }

    @Test
    fun `each player gets a chain of its own, levelling first and the limiter last`() {
        val first = AudioChain.processors({ Settings() }, queue, StreamTags()) { EqSound() }
        val second = AudioChain.processors({ Settings() }, queue, StreamTags()) { EqSound() }

        assertEquals(4, first.size)
        assertTrue(first.none { stage -> second.any { it === stage } })
    }

    @Test
    fun `a stream's levels are the tags its renderer read, with album gain when its queue plays the album in order`() {
        val streams = StreamTags()
        streams.record(PERIOD_7, TAGGED)
        streams.record(PERIOD_8, TAGGED)

        assertEquals(StreamLevels(ReplayGainTags(trackGainDb = -6f, albumGainDb = -3f), albumInOrder = true), streams.levelsOf(stream(PERIOD_7, "7"), queue))
        assertEquals(StreamLevels(ReplayGainTags(trackGainDb = -6f, albumGainDb = -3f), albumInOrder = false), streams.levelsOf(stream(PERIOD_8, "8"), queue))
        assertEquals(StreamLevels.NONE, streams.levelsOf(stream("never read", "9"), queue))
        assertEquals(StreamLevels.NONE, streams.levelsOf(StreamMetadata.DEFAULT, queue))
    }

    @Test
    fun `untagged streams play unchanged`() {
        val streams = StreamTags()
        streams.record(PERIOD_8, null)

        assertEquals(StreamLevels.NONE, streams.levelsOf(stream(PERIOD_8, "8"), queue))
    }

    @Test
    fun `the chain levels a tagged song by its gain, and follows the settings while it plays`() {
        var settings = Settings(audio = AudioSettings(levelling = Levelling.SONG))
        val streams = StreamTags().apply { record(PERIOD_8, TAGGED) }
        val chain = AudioChain.processors({ settings }, queue, streams) { EqSound() }
        val sine = Signals.sine(1_000.0, 0.5f, seconds = 1.0)
        val half = sine.size / 2 / 2

        val out = Signals.run(chain, sine, encoding = C.ENCODING_PCM_16BIT, stream = stream(PERIOD_8, "8")) { frame ->
            if (frame >= half) settings = Settings(audio = AudioSettings(levelling = Levelling.OFF))
        }

        val firstHalf = half * 2
        assertEquals(-6.0, Signals.gainDb(sine.copyOfRange(0, firstHalf), out.copyOfRange(0, firstHalf)), 0.2)
        assertEquals(0.0, Signals.gainDb(sine.copyOfRange(firstHalf, sine.size), out.copyOfRange(firstHalf, out.size)), 0.2)
    }

    @Test
    fun `the chain plays the equalizer of the output playing, and follows it while it plays`() {
        val settings = Settings(audio = AudioSettings(levelling = Levelling.OFF))
        var eq = EqSound()
        val chain = AudioChain.processors({ settings }, queue, StreamTags()) { eq }
        val sine = Signals.sine(1_000.0, 0.5f, seconds = 1.0)
        val half = sine.size / 2 / 2

        val out = Signals.run(chain, sine, encoding = C.ENCODING_PCM_16BIT, stream = stream(PERIOD_8, "8")) { frame ->
            if (frame >= half) eq = EqSound(enabled = true, preampDb = -6f)
        }

        val firstHalf = half * 2
        assertEquals(0.0, Signals.gainDb(sine.copyOfRange(0, firstHalf), out.copyOfRange(0, firstHalf)), 0.2)
        assertEquals(-6.0, Signals.gainDb(sine.copyOfRange(firstHalf, sine.size), out.copyOfRange(firstHalf, out.size)), 0.2)
    }

    // Review C: audio offload

    @Test
    fun `offload waits until the equalizer's profiles are read, and stays off while anything processes`() {
        val offloadOn = Settings(audio = AudioSettings(levelling = Levelling.OFF), advanced = AdvancedSettings(audioOffload = true))
        assertFalse(offloadWanted(offloadOn, eq = null))
        assertTrue(offloadWanted(offloadOn, EqSound()))
        assertFalse(offloadWanted(offloadOn, EqSound(enabled = true)))
        assertFalse(offloadWanted(offloadOn.copy(audio = AudioSettings(levelling = Levelling.AUTO)), EqSound()))
        assertFalse(offloadWanted(Settings(audio = AudioSettings(levelling = Levelling.OFF)), EqSound()))
    }

    @Test
    fun `offload goes off at once, and back on only at the next song start if nothing processes then`() {
        val applied = mutableListOf<Boolean>()
        val gate = OffloadGate(applied::add)

        gate.wanted(true)
        assertEquals(emptyList<Boolean>(), applied)
        gate.songStarted()
        assertEquals(listOf(true), applied)
        // The equalizer comes on mid-song: off at once.
        gate.wanted(false)
        assertEquals(listOf(true, false), applied)
        // Off again mid-song, still gliding to unity: offload waits for the next song.
        gate.wanted(true)
        assertEquals(listOf(true, false), applied)
        // On and off again before that song: nothing at its start.
        gate.wanted(false)
        gate.songStarted()
        assertEquals(listOf(true, false), applied)
        gate.wanted(true)
        gate.songStarted()
        gate.songStarted()
        assertEquals(listOf(true, false, true), applied)
    }

    @Test
    fun `a profile switch mid-song glides through the whole chain, the wave never stepping`() {
        val settings = Settings(audio = AudioSettings(levelling = Levelling.OFF))
        val speaker = EqSound(enabled = true, bandsDb = List(10) { if (it < 3) 6f else 0f }, preampDb = -3f)
        val buds = EqSound(enabled = true, bandsDb = List(10) { if (it > 6) 4f else -2f }, bassBoostDb = 4f, preampDb = -9f, balance = 0.4f)
        var eq = speaker
        val chain = AudioChain.processors({ settings }, queue, StreamTags()) { eq }
        val sine = Signals.sine(500.0, 0.4f, seconds = 1.0)
        val change = sine.size / 2 / 2

        val out = Signals.channel(Signals.run(chain, sine, stream = stream(PERIOD_8, "8"), chunk = 256) { frame -> if (frame >= change) eq = buds }, 0)

        // The largest step between two samples, before, during and after the switch: gliding, it never jumps past either.
        fun steepest(range: IntRange) = range.maxOf { abs(out[it] - out[it - 1]) }
        val before = steepest(change - 4_800 until change)
        val after = steepest(out.size - 4_800 until out.size)
        val during = steepest(change until change + 4_800)
        assertTrue("$during while switching, $before before, $after after", during <= maxOf(before, after) * 1.05f)
    }

    /** The stream of period [period], the one song of a timeline whose item is [mediaId], as a sink flushes it. */
    private fun stream(period: Any, mediaId: String): StreamMetadata =
        StreamMetadata.DEFAULT.buildUpon().setTimeline(OneSong(period, mediaId)).setPeriodUid(period).build()

    /** A timeline of one window holding one period named [uid], for media item [mediaId]. */
    private class OneSong(private val uid: Any, private val mediaId: String) : Timeline() {
        override fun getWindowCount(): Int = 1

        override fun getWindow(windowIndex: Int, window: Window, defaultPositionProjectionUs: Long): Window =
            window.set(uid, MediaItem.Builder().setMediaId(mediaId).build(), null, C.TIME_UNSET, C.TIME_UNSET, C.TIME_UNSET, true, false, null, 0, C.TIME_UNSET, 0, 0, 0)

        override fun getPeriodCount(): Int = 1

        override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period = period.set(uid, uid, 0, C.TIME_UNSET, 0)

        override fun getIndexOfPeriod(uid: Any): Int = if (uid == this.uid) 0 else C.INDEX_UNSET

        override fun getUidOfPeriod(periodIndex: Int): Any = uid
    }

    private companion object {
        const val PERIOD_7: String = "period 7"
        const val PERIOD_8: String = "period 8"
        val TAGGED = Metadata(
            TextInformationFrame("TXXX", "REPLAYGAIN_TRACK_GAIN", listOf("-6.00 dB")),
            TextInformationFrame("TXXX", "REPLAYGAIN_ALBUM_GAIN", listOf("-3.00 dB")),
        )
    }
}
