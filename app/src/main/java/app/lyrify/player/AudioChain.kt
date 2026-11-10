@file:OptIn(UnstableApi::class)

package app.lyrify.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Metadata
import androidx.media3.common.Timeline
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.StreamMetadata
import androidx.media3.common.util.UnstableApi
import app.lyrify.audio.AudioPipelineSettings
import app.lyrify.audio.EqSound
import app.lyrify.audio.ReplayGainTags
import app.lyrify.audio.audioChain
import app.lyrify.settings.AdvancedSettings
import app.lyrify.settings.Settings
import app.lyrify.audio.SongLevels as StreamLevels

// The audio chain in the player (A1's app.lyrify.audio.AudioPipeline). GaplessEngine builds its audio sink with
// [AudioChain.processors]; a crossfade engine (A2) asks again for its second player, as processors are never shared
// between sinks. Media3 runs its speed stage (Sonic) after these, behind the silence-skipping stage, which takes 16-bit
// PCM only: the chain hands 16-bit on, and the sink's float output stays OFF (it would bypass the chain).

/**
 * What the chain needs about the stream playing, by its media id (the queue item's uid): whether its queue plays one
 * album in track order ([albumInOrder]: album gain in Auto levelling, no crossfade inside an album).
 */
interface SongLevels {
    fun albumInOrder(mediaId: String): Boolean
}

object AudioChain {
    /**
     * A new chain for one player: Volume levelling and its boost from [settings], read again at every buffer so a change
     * is heard at once, and each song's levels from the tags its renderer read ([streams]) and its queue ([levels]). The
     * equalizer stays off here until A1's equalizer screen hands the output's profile in.
     */
    fun processors(settings: () -> Settings, levels: SongLevels, streams: StreamTags): List<AudioProcessor> =
        audioChain(PipelineSnapshot(settings)::get) { stream -> streams.levelsOf(stream, levels) }

    /** Whether any stage changes the sound now (levelling, the equalizer, balance): offload must then stay off. */
    fun processing(settings: Settings): Boolean = AudioPipelineSettings.of(settings, EQUALIZER_OFF).processing
}

/** The equalizer as the chain plays it until A1's equalizer screen hands in the output's profile: off. */
private val EQUALIZER_OFF = EqSound()

/** Whether audio offload may run: Advanced "Audio offload" is on, and nothing in the chain processes the sound. */
fun offloadAllowed(advanced: AdvancedSettings, processing: Boolean): Boolean = advanced.audioOffload && !processing

/**
 * The ReplayGain tags of each stream the audio renderer took on, by its period uid. The renderer reads a song ahead of
 * the chain playing it, so the chain looks a song's tags up here when that song reaches it. Only the last [KEPT] streams
 * are kept. Written and read on the playback thread, once per stream.
 */
class StreamTags {
    private val tags = object : LinkedHashMap<Any, ReplayGainTags>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Any, ReplayGainTags>?): Boolean = size > KEPT
    }

    /** The renderer took on the stream of period [periodUid], whose track carries [metadata]. */
    @Synchronized
    fun record(periodUid: Any, metadata: Metadata?) {
        tags[periodUid] = ReplayGainTags.from(metadata)
    }

    /** What levelling knows about [stream]: its tags, and whether its queue plays its album in order. Untagged: none. */
    @Synchronized
    fun levelsOf(stream: StreamMetadata, levels: SongLevels): StreamLevels {
        val uid = stream.periodUid ?: return StreamLevels.NONE
        val song = tags[uid]?.takeIf { it != ReplayGainTags.NONE } ?: return StreamLevels.NONE
        return StreamLevels(song, mediaIdOf(stream.timeline, uid)?.let(levels::albumInOrder) ?: false)
    }

    private fun mediaIdOf(timeline: Timeline?, uid: Any): String? {
        val index = timeline?.getIndexOfPeriod(uid) ?: return null
        if (index == C.INDEX_UNSET) return null
        val window = timeline.getPeriod(index, Timeline.Period()).windowIndex
        return timeline.getWindow(window, Timeline.Window()).mediaItem.mediaId
    }

    private companion object {
        /** The stream playing, the next one read ahead, and a few for seeks back and forth. */
        const val KEPT: Int = 8
    }
}

/**
 * The settings as one chain reads them: made again only when [settings] hands a new value, so a buffer allocates
 * nothing. One per chain; its stages all run on the playback thread.
 */
private class PipelineSnapshot(private val settings: () -> Settings) {
    private var from: Settings? = null
    private var made = AudioPipelineSettings()

    fun get(): AudioPipelineSettings {
        val now = settings()
        if (now !== from) {
            made = AudioPipelineSettings.of(now, EQUALIZER_OFF)
            from = now
        }
        return made
    }
}
