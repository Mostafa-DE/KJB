package app.lyrify.cut

import app.lyrify.tones.FadeCurve
import app.lyrify.tones.PreviewSource
import app.lyrify.tones.ToneAudio
import app.lyrify.tones.ToneEntry
import app.lyrify.tones.ToneWindow

// The audio cutter's pure parts: the cut's two ends, its fades, the M4A quality, the size, the names and the tags.

/** Which end of a cut: the one a nudge moves, and the one the preview follows. */
enum class CutEnd { START, END }

/**
 * The part of a song a cut keeps, from [startMs] to [endMs]. Each change keeps it inside the song (`durationMs`, however
 * long) with at least [MIN_LENGTH_MS] between the ends; neither end pushes the other, it stops a second short of it. A
 * song shorter than [MIN_LENGTH_MS] is cut whole.
 */
data class CutRange(val startMs: Long, val endMs: Long) {
    val lengthMs: Long get() = endMs - startMs

    /** The same part as the tone code takes it: the encoder, the fades and the preview are the Tones ones. */
    val window: ToneWindow get() = ToneWindow(startMs, lengthMs)

    fun at(end: CutEnd): Long = if (end == CutEnd.START) startMs else endMs

    fun withStart(ms: Long, durationMs: Long): CutRange {
        val end = endMs.coerceIn(0, durationMs.coerceAtLeast(0))
        return CutRange(ms.coerceIn(0, (end - MIN_LENGTH_MS).coerceAtLeast(0)), end)
    }

    fun withEnd(ms: Long, durationMs: Long): CutRange {
        val duration = durationMs.coerceAtLeast(0)
        val start = startMs.coerceIn(0, duration)
        return CutRange(start, ms.coerceIn(minOf(start + MIN_LENGTH_MS, duration), duration))
    }

    /** [end] moved by [steps] of [STEP_MS], from wherever it is now (a dragged end need not sit on a step). */
    fun nudged(end: CutEnd, steps: Int, durationMs: Long): CutRange = when (end) {
        CutEnd.START -> withStart(startMs + steps * STEP_MS, durationMs)
        CutEnd.END -> withEnd(endMs + steps * STEP_MS, durationMs)
    }

    /** The whole cut dragged to start at [startMs]: its length is kept and it moves to fit. */
    fun moved(startMs: Long, durationMs: Long): CutRange {
        val duration = durationMs.coerceAtLeast(0)
        val length = lengthMs.coerceIn(0, duration)
        val start = startMs.coerceIn(0, duration - length)
        return CutRange(start, start + length)
    }

    companion object {
        /** A nudge: the cutter's fine step. */
        const val STEP_MS: Long = 250

        /** The shortest cut. */
        const val MIN_LENGTH_MS: Long = 1_000

        /** The whole of a song of [durationMs]; empty when the length is unknown (0). */
        fun whole(durationMs: Long): CutRange = CutRange(0, durationMs.coerceAtLeast(0))
    }
}

/** A fade of [lengthMs] (0: no fade) along [curve]. */
data class Fade(val lengthMs: Long = 0, val curve: FadeCurve = FadeCurve.SMOOTH) {
    companion object {
        /** The lengths offered: none, then half a second to ten. Longer than the cut, the two fades meet in the middle. */
        val LENGTHS_MS: List<Long> = listOf(0L, 500L, 1_000L, 2_000L, 3_000L, 5_000L, 10_000L)
    }
}

/** An AAC encoder on the phone: its codec [name], the bit rates it takes, and whether it takes 44.1 kHz stereo. */
data class AacEncoder(val name: String, val bitRates: IntRange, val stereoAt44k: Boolean)

/** The M4A a cut is saved as: AAC-LC, 44.1 kHz stereo, at one of three bit rates. */
enum class CutQuality(val bitRate: Int) {
    KBPS_128(128_000),
    KBPS_256(256_000),
    KBPS_320(320_000),
    ;

    val kbps: Int get() = bitRate / 1_000

    companion object {
        val DEFAULT: CutQuality = KBPS_256

        /**
         * The qualities the phone can make, each with the first of [encoders] (the phone's own order) that takes 44.1 kHz
         * stereo at its bit rate. Empty: the phone cannot make an M4A, and nothing is offered.
         */
        fun offered(encoders: List<AacEncoder>): Map<CutQuality, String> = entries.mapNotNull { quality ->
            encoders.firstOrNull { it.stereoAt44k && quality.bitRate in it.bitRates }?.let { quality to it.name }
        }.toMap()

        /** [wanted] when the phone makes it, else the nearest below it, else the lowest above it; null when none is [offered]. */
        fun pick(wanted: CutQuality, offered: Set<CutQuality>): CutQuality? =
            wanted.takeIf { it in offered } ?: offered.filter { it < wanted }.maxOrNull() ?: offered.minOrNull()
    }
}

/** The size shown before a cut is saved: "about", as the encoder's exact output is known only after. */
object CutSize {
    /** Samples in an AAC frame. */
    private const val AAC_FRAME: Long = 1_024

    /** The MPEG-4 file's index takes about this much per AAC frame (each frame's size). */
    private const val INDEX_BYTES_PER_FRAME: Long = 4

    /** The MPEG-4 file's own boxes, besides the index. */
    private const val CONTAINER_BYTES: Long = 4_096

    /** A cut of [lengthMs] at [quality] with the song's tags and cover ([tagBytes]) in it; 0 for an empty cut. */
    fun estimateBytes(lengthMs: Long, quality: CutQuality, tagBytes: Long): Long {
        if (lengthMs <= 0) return 0
        val audio = quality.bitRate.toLong() * lengthMs / 8_000
        val frames = (ToneAudio.frames(lengthMs) + AAC_FRAME - 1) / AAC_FRAME
        return audio + frames * INDEX_BYTES_PER_FRAME + CONTAINER_BYTES + tagBytes.coerceAtLeast(0)
    }
}

/**
 * How a cut is named. Its title is the song's with " (cut)" ([title]); its file is the song's file name with " (cut)",
 * as an `.m4a` ([base], [fileName]), so it sits beside the song in a folder. A cut of a cut is marked once. A name
 * already taken gets " (2)", " (3)" and on, never over the other file.
 */
object CutNames {
    const val MARK: String = "(cut)"
    const val EXTENSION: String = "m4a"
    const val MIME_TYPE: String = "audio/mp4"

    /** A name without its base, for a song file named only by its extension. */
    private const val FALLBACK: String = "Cut"

    /** A file name holds at most 255 bytes: the base keeps under this, so the mark, a number and `.m4a` always fit. */
    private const val MAX_BASE_BYTES: Int = 200

    fun title(songTitle: String): String {
        val title = songTitle.trim()
        return when {
            title.isEmpty() -> FALLBACK
            title.endsWith(MARK, ignoreCase = true) -> title
            else -> "$title $MARK"
        }
    }

    /** The cut's file name before its number and extension, from the song's [songFileName]. */
    fun base(songFileName: String): String {
        val stem = songFileName.substringBeforeLast('.')
        val safe = ToneEntry.safeName(stem, fallback = "")
        if (safe.isEmpty()) return FALLBACK
        val marked = if (safe.endsWith(MARK, ignoreCase = true)) safe else "${shortened(safe, MAX_BASE_BYTES - MARK.length - 1)} $MARK"
        return shortened(marked, MAX_BASE_BYTES)
    }

    /** The [n]th name for a cut of [base]: the first is plain, the next "base (2).m4a". */
    fun fileName(base: String, n: Int): String = if (n <= 1) "$base.$EXTENSION" else "$base ($n).$EXTENSION"

    /** [text] cut to at most [bytes] UTF-8 bytes, whole characters only. */
    private fun shortened(text: String, bytes: Int): String {
        var used = 0
        var end = 0
        while (end < text.length) {
            val next = text.offsetByCodePoints(end, 1)
            used += text.substring(end, next).toByteArray(Charsets.UTF_8).size
            if (used > bytes) break
            end = next
        }
        return text.substring(0, end).trim()
    }
}

/**
 * The tags a cut is saved with: the song's own, by TagLib's property names, with the cut's title, less what described
 * the song's audio and is wrong for the cut (its length, ReplayGain, iTunes' gapless and loudness values, and lyrics
 * with times, which would be out of step from a cut that starts elsewhere). When KJB could not read the song's tags,
 * the library's title, artist and album.
 */
object CutTags {
    private const val TITLE: String = "TITLE"
    private const val LYRICS: String = "LYRICS"
    private val DROPPED: Set<String> = setOf("LENGTH", "ITUNSMPB", "ITUNNORM")
    private const val REPLAY_GAIN: String = "REPLAYGAIN_"

    /** A time as an `.lrc` line or word has it: "[01:23" or "<01:23". */
    private val TIMED = Regex("""[\[<]\d{1,3}:\d{2}""")

    fun fields(original: Map<String, List<String>>?, title: String, artist: String?, album: String?): Map<String, List<String>> {
        if (original == null) {
            return buildMap {
                put(TITLE, listOf(title))
                artist?.trim()?.takeIf { it.isNotEmpty() }?.let { put("ARTIST", listOf(it)) }
                album?.trim()?.takeIf { it.isNotEmpty() }?.let { put("ALBUM", listOf(it)) }
            }
        }
        val kept = original.filter { (key, values) ->
            val name = key.uppercase()
            name != TITLE && name !in DROPPED && !name.startsWith(REPLAY_GAIN) && !(name == LYRICS && values.any(TIMED::containsMatchIn))
        }
        return kept + (TITLE to listOf(title))
    }
}

/** The songs KJB offers to cut: files of the formats every phone decodes (Android's supported media formats). */
object CutFormats {
    private val EXTENSIONS: Set<String> = setOf("mp3", "m4a", "mp4", "aac", "flac", "ogg", "oga", "opus", "wav", "3gp", "amr", "mka", "webm")

    fun reads(path: String): Boolean {
        val name = path.substringAfterLast('/')
        return '.' in name && name.substringAfterLast('.').lowercase() in EXTENSIONS
    }
}

/** A time in a song to the hundredth: "1:12.25", "1:02:03.50" past an hour. */
fun cutTime(ms: Long): String {
    val hundredths = ms.coerceAtLeast(0) / 10
    val seconds = hundredths / 100
    val tail = "${(seconds % 60).toString().padStart(2, '0')}.${(hundredths % 100).toString().padStart(2, '0')}"
    val hours = seconds / 3_600
    return if (hours > 0) "$hours:${(seconds / 60 % 60).toString().padStart(2, '0')}:$tail" else "${seconds / 60}:$tail"
}

/** The end of a cut is heard from this long before it, or from before its fade out. */
private const val END_HEARD_MS: Long = 3_000

/** The end is heard from this long before its fade out starts. */
private const val BEFORE_FADE_MS: Long = 2_000

/**
 * Where in the cut the preview starts when it follows [focus]: its start, or for its end a few seconds before it (from
 * before the fade out, so the whole fade is heard), never before the start.
 */
fun previewFromMs(range: CutRange, fadeOut: Fade, focus: CutEnd): Long = when (focus) {
    CutEnd.START -> 0
    CutEnd.END -> (range.lengthMs - maxOf(END_HEARD_MS, fadeOut.lengthMs + BEFORE_FADE_MS)).coerceAtLeast(0)
}

/**
 * What the cutter holds for a song of [songDurationMs], however long (unlike Tones, the cutter reads the song only to
 * the cut's end, a piece at a time): the cut's ends, its fades, the quality, and which end the preview follows
 * ([focus]: the one that changed last, so a nudge of the end is heard at the end).
 */
data class CutDraft(
    val songDurationMs: Long,
    val range: CutRange = CutRange.whole(songDurationMs),
    val fadeIn: Fade = Fade(),
    val fadeOut: Fade = Fade(),
    val quality: CutQuality = CutQuality.DEFAULT,
    val focus: CutEnd = CutEnd.START,
) {
    val durationMs: Long get() = songDurationMs.coerceAtLeast(0)

    fun withStart(ms: Long): CutDraft = copy(range = range.withStart(ms, durationMs), focus = CutEnd.START)

    fun withEnd(ms: Long): CutDraft = copy(range = range.withEnd(ms, durationMs), focus = CutEnd.END)

    fun nudged(end: CutEnd, steps: Int): CutDraft = copy(range = range.nudged(end, steps, durationMs), focus = end)

    fun movedTo(ms: Long): CutDraft = copy(range = range.moved(ms, durationMs), focus = CutEnd.START)

    fun withFadeIn(fade: Fade): CutDraft = copy(fadeIn = fade, focus = CutEnd.START)

    fun withFadeOut(fade: Fade): CutDraft = copy(fadeOut = fade, focus = CutEnd.END)

    /** The preview is to play from the cut's start, or its end. */
    fun heard(from: CutEnd): CutDraft = copy(focus = from)

    /** What the preview plays: the cut with its fades along their curves, from where [focus] says. */
    fun preview(songPath: String): PreviewSource.Window = PreviewSource.Window(
        songPath = songPath,
        window = range.window,
        fadeInMs = fadeIn.lengthMs,
        fadeOutMs = fadeOut.lengthMs,
        fadeInCurve = fadeIn.curve,
        fadeOutCurve = fadeOut.curve,
        fromMs = previewFromMs(range, fadeOut, focus),
    )
}
