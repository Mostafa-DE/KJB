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
import kotlin.math.max
import kotlin.math.min

// Volume levelling (v3-extras-spec.md, "Volume levelling"): the ReplayGain and R128 tags a song carries, the gain
// levelling takes from them, and the first stage of the sound chain, which applies it.
//
// Written from the public standards:
// - ReplayGain 2.0 specification, Hydrogenaudio Knowledgebase (revision of 7 August 2023),
//   https://wiki.hydrogenaudio.org/index.php?title=ReplayGain_2.0_specification : the keys REPLAYGAIN_TRACK_GAIN,
//   _TRACK_PEAK, _ALBUM_GAIN and _ALBUM_PEAK, their values ("[-]a.bb dB" and "c.dddddd") and the variations a robust
//   player reads, any other formatting error counting as no tag; the de-facto REPLAYGAIN_REFERENCE_LOUDNESS
//   ("[-]a.bb LUFS") and the gain RG = Lr - L against the -18 LUFS reference; the player requirements: track or album
//   gain, the other one when only it is there, unity gain without either, a pre-amp, and the scale factor
//   min(10^((RG + pre-amp) / 20), 1 / peak), with a peak of 1.0 when none is tagged.
// - ID3v2.3.0, https://id3.org/id3v2.3.0 , and ID3v2.4.0, https://id3.org/id3v2.4.0-structure and
//   https://id3.org/id3v2.4.0-frames : the TXXX frame (an encoding, a description, a value), and v2.4's several values
//   in one text frame, of which the first is the value.
// - Vorbis comment, https://xiph.org/vorbis/doc/v-comment.html : FIELD=value, field names compared without case.
// - MP4 freeform atoms, AtomicParsley's "MPEG-4 files", https://atomicparsley.sourceforge.net/mpeg-4files.html : a
//   "----" atom holding "mean" (the reverse-DNS domain, com.apple.iTunes), "name" and "data".
// - RFC 7845, Ogg Encapsulation for the Opus Audio Codec, https://www.rfc-editor.org/rfc/rfc7845 , sections 5.1 and
//   5.2.1: R128_TRACK_GAIN and R128_ALBUM_GAIN, base-10 Q7.8 integers in dB against EBU R128's -23 LUFS, applied in
//   addition to the ID header's output gain, and preferred to REPLAYGAIN tags.
// What Media3 1.11.1 hands on (https://github.com/androidx/media): TXXX frames as TextInformationFrame("TXXX",
// description, values) from Id3Decoder; FLAC, Ogg Vorbis and Opus comments as VorbisComment from
// VorbisUtil.parseVorbisComments; freeform atoms as InternalFrame(mean, name, data) from the MP4 MetadataUtil. The Opus
// output gain stays in the codec data, and Android's Opus decoder applies it (frameworks/av C2SoftOpusDec,
// OPUS_SET_GAIN), so this chain adds the R128 gain alone.

/**
 * The ReplayGain tags of one song: gains in dB against the -18 LUFS reference, peaks as a linear amplitude where 1 is
 * full scale. Null where the song has no such tag, or only one the standard's format does not allow.
 */
data class ReplayGainTags(
    val trackGainDb: Float? = null,
    val trackPeak: Float? = null,
    val albumGainDb: Float? = null,
    val albumPeak: Float? = null,
) {
    companion object {
        val NONE: ReplayGainTags = ReplayGainTags()

        /**
         * The tags in a track's [metadata] (`Format.metadata`): ID3 TXXX frames, Vorbis comments (FLAC, Ogg Vorbis,
         * Opus) and MP4 freeform atoms of the com.apple.iTunes domain, keys in any case. Of several tags with one key,
         * the first readable one counts. R128 gains count in Vorbis comments only, where RFC 7845 defines them, and
         * win over REPLAYGAIN gains. ReplayGain gains tagged against another reference loudness come back to -18 LUFS.
         */
        fun from(metadata: Metadata?): ReplayGainTags {
            if (metadata == null) return NONE
            val fields = (0 until metadata.length()).mapNotNull { fieldOf(metadata.get(it)) }
            if (fields.isEmpty()) return NONE
            fun first(key: String, vorbisOnly: Boolean = false, read: (String) -> Float?): Float? =
                fields.firstNotNullOfOrNull { if ((it.vorbis || !vorbisOnly) && it.key.equals(key, ignoreCase = true)) read(it.value) else null }
            // RG = Lr - L: a gain computed against reference Lr moves by as much as Lr does.
            val shift = first(REFERENCE_LOUDNESS, read = ::lufs)?.let { REFERENCE_LUFS - it } ?: 0f
            return ReplayGainTags(
                trackGainDb = first(R128_TRACK_GAIN, vorbisOnly = true, read = ::r128Db) ?: first(TRACK_GAIN, read = ::decibels)?.plus(shift),
                trackPeak = first(TRACK_PEAK, read = ::peak),
                albumGainDb = first(R128_ALBUM_GAIN, vorbisOnly = true, read = ::r128Db) ?: first(ALBUM_GAIN, read = ::decibels)?.plus(shift),
                albumPeak = first(ALBUM_PEAK, read = ::peak),
            )
        }

        private fun fieldOf(entry: Metadata.Entry): TagField? = when (entry) {
            is TextInformationFrame -> {
                val description = entry.description
                val value = entry.values.firstOrNull()
                if (entry.id == TXXX && description != null && value != null) TagField(description, value, vorbis = false) else null
            }
            is VorbisComment -> TagField(entry.key, entry.value, vorbis = true)
            is InternalFrame -> if (entry.domain == ITUNES_DOMAIN) TagField(entry.description, entry.text, vorbis = false) else null
            else -> null
        }

        /** A gain, "[-]a.bb dB": with a leading + or -, any number of digits, leading zeros or spaces, dB in any case or none. */
        private fun decibels(text: String): Float? = number(GAIN.matchEntire(text))

        /** A reference loudness, "[-]a.bb LUFS". Only LUFS: ReplayGain 1's "89 dB" is a sound pressure level. */
        private fun lufs(text: String): Float? = number(LOUDNESS.matchEntire(text))

        /** A peak, "c.dddddd": a positive decimal, no unit. */
        private fun peak(text: String): Float? = number(PEAK.matchEntire(text))?.takeIf { it > 0f }

        private fun number(match: MatchResult?): Float? {
            val (sign, digits) = match?.destructured ?: return null
            return (sign + digits).toFloat().takeIf { it.isFinite() }
        }

        /** An R128 gain: a base-10 integer, an optional sign, at most 6 characters, -32768 to 32767; Q7.8 dB against -23 LUFS. */
        private fun r128Db(text: String): Float? {
            if (text.length > R128_MAX_CHARS || !R128.matches(text)) return null
            val q78 = text.toInt().takeIf { it in R128_RANGE } ?: return null
            return q78 / Q78_ONE_DB + (REFERENCE_LUFS - R128_REFERENCE_LUFS)
        }

        private const val TXXX: String = "TXXX"
        private const val ITUNES_DOMAIN: String = "com.apple.iTunes"
        private const val TRACK_GAIN: String = "REPLAYGAIN_TRACK_GAIN"
        private const val TRACK_PEAK: String = "REPLAYGAIN_TRACK_PEAK"
        private const val ALBUM_GAIN: String = "REPLAYGAIN_ALBUM_GAIN"
        private const val ALBUM_PEAK: String = "REPLAYGAIN_ALBUM_PEAK"
        private const val REFERENCE_LOUDNESS: String = "REPLAYGAIN_REFERENCE_LOUDNESS"
        private const val R128_TRACK_GAIN: String = "R128_TRACK_GAIN"
        private const val R128_ALBUM_GAIN: String = "R128_ALBUM_GAIN"

        /** ReplayGain 2.0's reference, and EBU R128's: an R128 gain plus 5 dB is a ReplayGain gain. */
        private const val REFERENCE_LUFS: Float = -18f
        private const val R128_REFERENCE_LUFS: Float = -23f

        /** Q7.8 fixed point: 8 fractional bits, so 256 is 1 dB. */
        private const val Q78_ONE_DB: Float = 256f
        private const val R128_MAX_CHARS: Int = 6
        private val R128_RANGE: IntRange = -32_768..32_767
        private val R128 = Regex("""[+-]?\d+""")

        private const val NUMBER: String = """(\d+(?:\.\d*)?|\.\d+)"""
        private val GAIN = Regex("""\s*([+-]?)\s*$NUMBER\s*(?:dB)?\s*""", RegexOption.IGNORE_CASE)
        private val LOUDNESS = Regex("""\s*([+-]?)\s*$NUMBER\s*LUFS\s*""", RegexOption.IGNORE_CASE)
        private val PEAK = Regex("""\s*(\+?)\s*$NUMBER\s*""")
    }
}

/** One key and value from a song's metadata; [vorbis] for a Vorbis comment, the only place R128 gains count. */
private class TagField(val key: String, val value: String, val vorbis: Boolean)

/** What levelling knows about the stream playing: its [tags], and whether its queue plays its album in track order. */
data class SongLevels(val tags: ReplayGainTags, val albumInOrder: Boolean) {
    companion object {
        val NONE: SongLevels = SongLevels(ReplayGainTags.NONE, albumInOrder = false)
    }
}

/**
 * The linear gain Volume levelling gives a song with [tags]. Off plays it as it is. Album, or Auto while its queue plays
 * the album in track order ([albumInOrder]), takes the album gain; Song, or Auto otherwise, the song gain; either takes
 * the other when only that one is tagged, and a song with neither plays unchanged. The Advanced "Levelling boost"
 * ([boostDb], held to -6 to +6 dB) is the standard's pre-amp, added to the tag's gain. Then nothing clips: peak times
 * gain stays at or below 1. The peak is the one that goes with the gain used; else the other one (the album's is at
 * least the song's, and the song's is this song's own), else 1, so a song without a peak tag is never boosted.
 */
fun levellingGain(tags: ReplayGainTags, levelling: Levelling, albumInOrder: Boolean, boostDb: Int): Float {
    val album = when (levelling) {
        Levelling.OFF -> return 1f
        Levelling.AUTO -> albumInOrder
        Levelling.SONG -> false
        Levelling.ALBUM -> true
    }
    val albumKind = if (album) tags.albumGainDb != null else tags.trackGainDb == null
    val gainDb = (if (albumKind) tags.albumGainDb else tags.trackGainDb) ?: return 1f
    val trackPeak = tags.trackPeak?.takeIf { it > 0f }
    val albumPeak = tags.albumPeak?.takeIf { it > 0f }
    val peak = (if (albumKind) albumPeak ?: trackPeak else trackPeak ?: albumPeak) ?: 1f
    return min(dbToGain(gainDb + boostDb.coerceIn(AdvancedSettings.LEVELLING_BOOST_DB)), 1f / peak)
}

/**
 * The chain's first stage: plays each stream at the gain [levellingGain] gives it. It asks for the stream's levels once,
 * when the stream starts (a new song or a seek), and plays its first frame at that gain; a change of setting mid-song
 * glides there instead of clicking. At a gain of exactly 1 the samples pass untouched. It tells the limiter ([chain])
 * the highest gain it may play in the buffer, so the limiter watches for overs while levelling boosts.
 */
internal class ReplayGainProcessor(
    settings: () -> AudioPipelineSettings,
    private val songLevels: (StreamMetadata) -> SongLevels,
    private val chain: ChainState = ChainState(),
) : FloatStage(settings) {
    private val gain = Glide()
    private var levels = SongLevels.NONE
    private var target = 1f
    private var fresh = true

    override fun start(stream: StreamMetadata) {
        levels = songLevels(stream)
        gain.setRate(sampleRate)
        fresh = true
    }

    override fun begin(settings: AudioPipelineSettings) {
        target = levellingGain(levels.tags, settings.levelling, levels.albumInOrder, settings.levellingBoostDb)
        if (fresh) {
            gain.value = target
            fresh = false
        }
        chain.levellingGain = max(gain.value, target)
    }

    override fun process(frame: FloatArray) {
        val g = gain.next(target)
        if (g != 1f) for (c in frame.indices) frame[c] *= g
    }
}
