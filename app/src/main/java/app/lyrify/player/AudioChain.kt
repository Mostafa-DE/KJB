package app.lyrify.player

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import app.lyrify.library.SongRef
import app.lyrify.settings.AdvancedSettings
import app.lyrify.settings.Settings

// The audio chain's hook (milestone A1 fills it). GaplessEngine builds its audio sink with [AudioChain.processors]; a
// crossfade engine (A2) asks again for its second player, as processors are never shared between sinks. Media3 runs
// its speed stage (Sonic) after these, behind the silence-skipping stage, which takes 16-bit PCM only: the chain hands
// 16-bit on, and the sink's float output stays OFF (it would bypass the chain).

/**
 * What the chain needs about the stream playing, by its media id (the queue item's uid): the song, to read its
 * ReplayGain tags once from the track's metadata, and whether its queue plays one album in track order ([albumInOrder]:
 * album gain in Auto levelling, no crossfade inside an album).
 */
interface SongLevels {
    fun song(mediaId: String): SongRef?

    fun albumInOrder(mediaId: String): Boolean
}

@UnstableApi
object AudioChain {
    /**
     * A new list of processors for one player, from [settings] and [levels]. Empty until A1, which returns
     * `audioChain(settings, songLevels)` from app.lyrify.audio.AudioPipeline here.
     */
    @Suppress("UNUSED_PARAMETER") // Until A1: no processing.
    fun processors(settings: Settings, levels: SongLevels): List<AudioProcessor> = emptyList()

    /** Whether any stage changes the sound now (levelling, the equalizer, balance): offload must then stay off. Never until A1. */
    @Suppress("UNUSED_PARAMETER") // Until A1: no processing.
    fun processing(settings: Settings): Boolean = false
}

/** Whether audio offload may run: Advanced "Audio offload" is on, and nothing in the chain processes the sound. */
fun offloadAllowed(advanced: AdvancedSettings, processing: Boolean): Boolean = advanced.audioOffload && !processing
