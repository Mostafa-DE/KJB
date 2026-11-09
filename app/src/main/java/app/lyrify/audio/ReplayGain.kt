@file:OptIn(UnstableApi::class)

package app.lyrify.audio

import androidx.annotation.OptIn
import androidx.media3.common.Metadata
import androidx.media3.common.audio.AudioProcessor.StreamMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.metadata.id3.InternalFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import app.lyrify.settings.AdvancedSettings
import app.lyrify.settings.Levelling
import kotlin.math.min

/** A song's ReplayGain tags: gains in dB against the -18 LUFS reference, peaks as linear sample values; null where missing. */
data class ReplayGainTags(
    val trackGainDb: Float? = null,
    val trackPeak: Float? = null,
    val albumGainDb: Float? = null,
    val albumPeak: Float? = null,
) {
    companion object {
        val NONE: ReplayGainTags = ReplayGainTags()

        /**
         * The tags in a stream's `Format.metadata`, as Media3's extractors deliver them: ID3 TXXX frames (MP3), Vorbis
         * comments (FLAC, Ogg Vorbis, and Opus with its R128 gains), and MP4 freeform atoms (M4A, as ID3 internal frames).
         * [NONE] without any. Where a value is tagged twice, the first in that order wins.
         *
         * Ported from Gramophone's ReplayGainUtil.parse (GPL-3.0, Copyright (C) 2025 nift4,
         * github.com/FoedusProgramme/Gramophone at e6fa468f, app/src/main/java/org/akanework/gramophone/logic/utils/
         * ReplayGainUtil.kt): its keys, units, reference-loudness shift and R128 offset. Left out: the legacy ID3 frames
         * (RVA2, RVAD, RGAD), iTunes SoundCheck and the LAME header, which the spec does not ask for.
         */
        fun from(metadata: Metadata?): ReplayGainTags {
            if (metadata == null) return NONE
            val freeform = metadata.getMatchingEntries(InternalFrame::class.java) {
                (it.domain == ITUNES_DOMAIN || it.domain == REPLAYGAIN_DOMAIN) && it.description.startsWith(REPLAYGAIN_PREFIX, ignoreCase = true)
            }.map { it.description to it.text }
            val vorbis = metadata.getMatchingEntries(VorbisComment::class.java) {
                it.key.startsWith(REPLAYGAIN_PREFIX, ignoreCase = true) || it.key.startsWith(R128_PREFIX, ignoreCase = true) ||
                    it.key.equals(JRIVER_GAIN, ignoreCase = true) || it.key.equals(JRIVER_PEAK, ignoreCase = true)
            }.map { it.key to it.value }
            val txxx = metadata.getMatchingEntries(TextInformationFrame::class.java) {
                (it.id == "TXXX" || it.id == "TXX") && it.description?.startsWith(REPLAYGAIN_PREFIX, ignoreCase = true) == true
            }.mapNotNull { frame -> frame.values.firstOrNull()?.let { (frame.description ?: "") to it } }
            val values = listOf(freeform, vorbis, txxx).flatMap(::tagValues)
            return ReplayGainTags(
                trackGainDb = values.firstOrNull { it.kind == TagKind.TRACK_GAIN }?.value,
                trackPeak = values.firstOrNull { it.kind == TagKind.TRACK_PEAK }?.value,
                albumGainDb = values.firstOrNull { it.kind == TagKind.ALBUM_GAIN }?.value,
                albumPeak = values.firstOrNull { it.kind == TagKind.ALBUM_PEAK }?.value,
            )
        }

        /** The values in one format's [tags] (key, text), the ReplayGain gains shifted by that format's reference loudness. */
        private fun tagValues(tags: List<Pair<String, String>>): List<TagValue> {
            val shift = tags.firstNotNullOfOrNull { (key, text) -> referenceShift(key, text) } ?: 0f
            return tags.mapNotNull { (key, text) -> tagValue(key, text, shift) }
        }

        /** REPLAYGAIN_REFERENCE_LOUDNESS in LUFS: how far the tagger's reference is from -18 LUFS. Values in dB SPL are not used. */
        private fun referenceShift(key: String, text: String): Float? {
            if (!key.equals("REPLAYGAIN_REFERENCE_LOUDNESS", ignoreCase = true)) return null
            val value = text.trim()
            if (!value.endsWith(" LUFS", ignoreCase = true)) return null
            return number(value.dropLast(" LUFS".length))?.let { REFERENCE_LUFS - it }
        }

        private fun tagValue(key: String, text: String, shift: Float): TagValue? {
            val name = key.uppercase()
            return when (name) {
                "REPLAYGAIN_TRACK_GAIN", "REPLAYGAIN_ALBUM_GAIN", JRIVER_GAIN -> {
                    var value = text.trim()
                    if (value.endsWith(" dB", ignoreCase = true) || value.endsWith(" LU", ignoreCase = true)) value = value.dropLast(3)
                    number(value)?.let { gain ->
                        if (name == "REPLAYGAIN_ALBUM_GAIN") TagValue(TagKind.ALBUM_GAIN, gain + shift)
                        else TagValue(TagKind.TRACK_GAIN, if (name == JRIVER_GAIN) gain else gain + shift)
                    }
                }
                "REPLAYGAIN_TRACK_PEAK", "REPLAYGAIN_ALBUM_PEAK", JRIVER_PEAK -> number(text.trim())?.let {
                    TagValue(if (name == "REPLAYGAIN_ALBUM_PEAK") TagKind.ALBUM_PEAK else TagKind.TRACK_PEAK, it)
                }
                // Opus: Q7.8 fixed point against -23 LUFS; +5 dB brings it to ReplayGain's -18 LUFS.
                "R128_TRACK_GAIN", "R128_ALBUM_GAIN" -> number(text.trim())?.let {
                    TagValue(if (name == "R128_ALBUM_GAIN") TagKind.ALBUM_GAIN else TagKind.TRACK_GAIN, it / 256f + R128_OFFSET_DB)
                }
                else -> null
            }
        }

        /** A finite number, with a point or a comma for the decimals; null for anything else. */
        private fun number(text: String): Float? = text.replace(',', '.').toFloatOrNull()?.takeIf { it.isFinite() }

        private const val ITUNES_DOMAIN: String = "com.apple.iTunes"
        private const val REPLAYGAIN_DOMAIN: String = "org.hydrogenaudio.replaygain"
        private const val REPLAYGAIN_PREFIX: String = "REPLAYGAIN_"
        private const val R128_PREFIX: String = "R128_"

        /** JRiver Media Center's own names. */
        private const val JRIVER_GAIN: String = "REPLAY GAIN"
        private const val JRIVER_PEAK: String = "PEAK LEVEL"
        private const val REFERENCE_LUFS: Float = -18f
        private const val R128_OFFSET_DB: Float = 5f
    }
}

private enum class TagKind { TRACK_GAIN, TRACK_PEAK, ALBUM_GAIN, ALBUM_PEAK }

private class TagValue(val kind: TagKind, val value: Float)

/**
 * What levelling needs to know about the song a stream plays: its [tags], and whether the queue plays its album in
 * track order now (`albumInOrder` in player/Queues.kt), which makes Auto use album gain.
 */
data class SongLevels(val tags: ReplayGainTags, val albumInOrder: Boolean) {
    companion object {
        val NONE: SongLevels = SongLevels(ReplayGainTags.NONE, albumInOrder = false)
    }
}

/**
 * The linear gain Volume levelling gives a song: 1 when it is Off or the song has no gain tag ("untagged songs play
 * unchanged"). Song and Album fall back to the other gain when theirs is missing; Auto is Album for an album played in
 * track order, else Song. The boost (held to -6 to +6 dB) is added to the tag's gain, and the whole is limited so that
 * peak × gain stays at or below 1 (Gramophone's "reduce gain" rule); a missing peak counts as full scale, so it never
 * boosts.
 */
fun levellingGain(tags: ReplayGainTags, levelling: Levelling, albumInOrder: Boolean, boostDb: Int): Float {
    val album = when (levelling) {
        Levelling.OFF -> return 1f
        Levelling.SONG -> false
        Levelling.ALBUM -> true
        Levelling.AUTO -> albumInOrder
    }
    val gainDb = (if (album) tags.albumGainDb ?: tags.trackGainDb else tags.trackGainDb ?: tags.albumGainDb) ?: return 1f
    val peak = (if (album) tags.albumPeak ?: tags.trackPeak else tags.trackPeak ?: tags.albumPeak)?.takeIf { it > 0f } ?: 1f
    return min(dbToGain(gainDb + boostDb.coerceIn(AdvancedSettings.LEVELLING_BOOST_DB)), 1f / peak)
}

/** The first stage of the chain: the song's levelling gain, decided per stream and gliding when the settings change. */
internal class ReplayGainProcessor(
    settings: () -> AudioPipelineSettings,
    private val songLevels: (StreamMetadata) -> SongLevels,
) : FloatStage(settings) {
    private var song = SongLevels.NONE
    private val gain = Glide()
    private var target = 1f
    private var fresh = true

    override fun start(stream: StreamMetadata) {
        song = songLevels(stream)
        gain.setRate(sampleRate)
        fresh = true
    }

    override fun begin(settings: AudioPipelineSettings) {
        target = levellingGain(song.tags, settings.levelling, song.albumInOrder, settings.levellingBoostDb)
        // A new song starts at its own gain; only a change of setting mid-song glides.
        if (fresh) gain.value = target
        fresh = false
    }

    override fun process(frame: FloatArray) {
        val g = gain.next(target)
        if (g != 1f) for (c in frame.indices) frame[c] *= g
    }
}
