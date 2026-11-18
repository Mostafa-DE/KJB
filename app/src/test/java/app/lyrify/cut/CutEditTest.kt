package app.lyrify.cut

import app.lyrify.tones.FadeCurve
import app.lyrify.tones.PreviewSource
import app.lyrify.tones.ToneWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The cutter's pure parts. Made-up song names only. */
class CutEditTest {
    private val song = 201_000L

    // The two ends

    @Test
    fun `a cut starts as the whole song, however long it is`() {
        assertEquals(CutRange(0, song), CutRange.whole(song))
        assertEquals(CutRange(0, 0), CutRange.whole(0))
        // Past the 20 minutes Tones can use: a three-hour mix is cut whole, and its end reaches the song's.
        val threeHours = 3 * 3_600_000L
        val long = CutDraft(songDurationMs = threeHours)
        assertEquals(CutRange(0, threeHours), long.range)
        assertEquals(CutRange(threeHours - 61_000, threeHours), long.withStart(threeHours - 61_000).withEnd(threeHours + 5_000).range)
        assertEquals(threeHours, long.nudged(CutEnd.END, 1).range.endMs)
    }

    @Test
    fun `each nudge moves an end by a quarter second, from wherever it is`() {
        val range = CutRange(12_340, 84_500)
        assertEquals(CutRange(12_590, 84_500), range.nudged(CutEnd.START, 1, song))
        assertEquals(CutRange(12_090, 84_500), range.nudged(CutEnd.START, -1, song))
        assertEquals(CutRange(12_340, 84_750), range.nudged(CutEnd.END, 1, song))
        assertEquals(CutRange(12_340, 84_250), range.nudged(CutEnd.END, -1, song))
        assertEquals(CutRange(13_340, 84_500), range.nudged(CutEnd.START, 4, song))
    }

    @Test
    fun `the ends stay inside the song`() {
        val whole = CutRange.whole(song)
        assertEquals(whole, whole.nudged(CutEnd.START, -1, song))
        assertEquals(whole, whole.nudged(CutEnd.END, 1, song))
        assertEquals(CutRange(0, song), CutRange(5_000, song).withStart(-3_000, song))
        assertEquals(CutRange(5_000, song), CutRange(5_000, 60_000).withEnd(song + 9_000, song))
        // An end kept from before the song got shorter is pulled in.
        assertEquals(CutRange(5_000, song), CutRange(5_000, song + 9_000).withStart(5_000, song))
    }

    @Test
    fun `the start never comes within a second of the end, nor the end within a second of the start`() {
        val range = CutRange(10_000, 12_000)
        assertEquals(CutRange(11_000, 12_000), range.withStart(11_900, song))
        assertEquals(CutRange(11_000, 12_000), range.nudged(CutEnd.START, 40, song))
        assertEquals(CutRange(10_000, 11_000), range.withEnd(10_200, song))
        assertEquals(CutRange(10_000, 11_000), range.nudged(CutEnd.END, -40, song))
        // Neither end pushes the other.
        assertEquals(12_000L, range.nudged(CutEnd.START, 40, song).endMs)
        assertEquals(10_000L, range.nudged(CutEnd.END, -40, song).startMs)
    }

    @Test
    fun `a song shorter than a second is cut whole, and its ends do not move`() {
        val short = CutRange.whole(800)
        assertEquals(CutRange(0, 800), short)
        assertEquals(short, short.nudged(CutEnd.START, 1, 800))
        assertEquals(short, short.nudged(CutEnd.END, -1, 800))
        assertEquals(short, short.withStart(400, 800))
        assertEquals(short, short.withEnd(300, 800))
    }

    @Test
    fun `moving the whole cut keeps its length and keeps it inside the song`() {
        val range = CutRange(10_000, 40_000)
        assertEquals(CutRange(50_000, 80_000), range.moved(50_000, song))
        assertEquals(CutRange(0, 30_000), range.moved(-5_000, song))
        assertEquals(CutRange(song - 30_000, song), range.moved(song, song))
        assertEquals(CutRange(0, song), CutRange(0, song).moved(9_000, song))
    }

    // The draft the screen holds

    @Test
    fun `a new draft is the whole song, no fades, 256 kbps, heard from its start`() {
        val draft = CutDraft(songDurationMs = song)
        assertEquals(CutRange(0, song), draft.range)
        assertEquals(Fade(), draft.fadeIn)
        assertEquals(0L, draft.fadeIn.lengthMs)
        assertEquals(0L, draft.fadeOut.lengthMs)
        assertEquals(CutQuality.KBPS_256, draft.quality)
        assertEquals(CutEnd.START, draft.focus)
    }

    @Test
    fun `the preview follows the end that changed last`() {
        val draft = CutDraft(songDurationMs = song)
        assertEquals(CutEnd.END, draft.withEnd(90_000).focus)
        assertEquals(CutEnd.START, draft.withEnd(90_000).withStart(5_000).focus)
        assertEquals(CutEnd.END, draft.nudged(CutEnd.END, -1).focus)
        assertEquals(CutEnd.START, draft.withEnd(90_000).nudged(CutEnd.START, 1).focus)
        assertEquals(CutEnd.START, draft.withEnd(90_000).movedTo(1_000).focus)
        assertEquals(CutEnd.END, draft.withFadeOut(Fade(2_000)).focus)
        assertEquals(CutEnd.START, draft.withEnd(90_000).withFadeIn(Fade(2_000)).focus)
        assertEquals(CutEnd.END, draft.heard(CutEnd.END).focus)
        assertEquals(CutRange(250, song - 250), draft.nudged(CutEnd.START, 1).nudged(CutEnd.END, -1).range)
    }

    @Test
    fun `the preview plays the cut with its fades and curves, the end from shortly before it`() {
        val draft = CutDraft(songDurationMs = song)
            .withStart(10_000)
            .withEnd(70_000)
            .withFadeIn(Fade(1_000, FadeCurve.EVEN))
            .withFadeOut(Fade(5_000, FadeCurve.LINEAR))
        assertEquals(
            PreviewSource.Window("/m/Paper Lanterns.mp3", ToneWindow(10_000, 60_000), 1_000, 5_000, FadeCurve.EVEN, FadeCurve.LINEAR, fromMs = 53_000),
            draft.preview("/m/Paper Lanterns.mp3"),
        )
        assertEquals(0L, draft.heard(CutEnd.START).preview("/m/Paper Lanterns.mp3").fromMs)
    }

    @Test
    fun `the end is heard from three seconds before it, or from before its fade out, never before the start`() {
        val minute = CutRange(10_000, 70_000)
        assertEquals(0L, previewFromMs(minute, Fade(), CutEnd.START))
        assertEquals(57_000L, previewFromMs(minute, Fade(), CutEnd.END))
        assertEquals(57_000L, previewFromMs(minute, Fade(500), CutEnd.END))
        assertEquals(53_000L, previewFromMs(minute, Fade(5_000), CutEnd.END))
        assertEquals(0L, previewFromMs(CutRange(10_000, 12_000), Fade(), CutEnd.END))
    }

    // Fades

    @Test
    fun `fade lengths run from none to ten seconds, smooth unless the user picks another curve`() {
        assertEquals(listOf(0L, 500L, 1_000L, 2_000L, 3_000L, 5_000L, 10_000L), Fade.LENGTHS_MS)
        assertEquals(FadeCurve.SMOOTH, Fade(2_000).curve)
    }

    // Quality

    private val aosp = AacEncoder("c2.android.aac.encoder", 8_000..960_000, stereoAt44k = true)

    @Test
    fun `every quality the phone's AAC encoder takes is offered, with the encoder that makes it`() {
        assertEquals(CutQuality.entries.associateWith { "c2.android.aac.encoder" }, CutQuality.offered(listOf(aosp)))
        val capped = AacEncoder("c2.vendor.aac.encoder", 8_000..288_000, stereoAt44k = true)
        assertEquals(
            mapOf(CutQuality.KBPS_128 to "c2.vendor.aac.encoder", CutQuality.KBPS_256 to "c2.vendor.aac.encoder", CutQuality.KBPS_320 to "c2.android.aac.encoder"),
            CutQuality.offered(listOf(capped, aosp)),
        )
        assertEquals(listOf(128, 256, 320), CutQuality.entries.map { it.kbps })
    }

    @Test
    fun `a phone without an AAC encoder for 44_1 kHz stereo offers none`() {
        assertEquals(emptyMap<CutQuality, String>(), CutQuality.offered(emptyList()))
        assertEquals(emptyMap<CutQuality, String>(), CutQuality.offered(listOf(aosp.copy(stereoAt44k = false))))
        assertEquals(emptyMap<CutQuality, String>(), CutQuality.offered(listOf(aosp.copy(bitRates = 8_000..96_000))))
    }

    @Test
    fun `the quality saved is the one picked, else the nearest the phone makes`() {
        val all = CutQuality.entries.toSet()
        assertEquals(CutQuality.KBPS_320, CutQuality.pick(CutQuality.KBPS_320, all))
        assertEquals(CutQuality.KBPS_256, CutQuality.pick(CutQuality.KBPS_320, setOf(CutQuality.KBPS_128, CutQuality.KBPS_256)))
        assertEquals(CutQuality.KBPS_256, CutQuality.pick(CutQuality.KBPS_128, setOf(CutQuality.KBPS_256, CutQuality.KBPS_320)))
        assertNull(CutQuality.pick(CutQuality.KBPS_256, emptySet()))
    }

    // Size

    @Test
    fun `the size estimate is the bit rate over the length, plus the container and the tags`() {
        val minute = CutSize.estimateBytes(72_000, CutQuality.KBPS_320, tagBytes = 0)
        // 1:12 at 320 kbps is 2.88 MB of audio.
        assertTrue("$minute", minute in 2_880_000L..2_910_000L)
        val withCover = CutSize.estimateBytes(72_000, CutQuality.KBPS_320, tagBytes = 350_000)
        assertEquals(350_000L, withCover - minute)
        val low = CutSize.estimateBytes(72_000, CutQuality.KBPS_128, tagBytes = 0)
        assertTrue("$low", low in 1_152_000L..1_170_000L)
        val twice = CutSize.estimateBytes(144_000, CutQuality.KBPS_320, tagBytes = 0)
        assertTrue("$twice", twice > minute && twice < 2 * minute)
        assertEquals(0L, CutSize.estimateBytes(0, CutQuality.KBPS_320, tagBytes = 350_000))
    }

    // Names

    @Test
    fun `a cut's title is the song's with (cut), and a cut of a cut is marked once`() {
        assertEquals("Paper Lanterns (cut)", CutNames.title("Paper Lanterns"))
        assertEquals("Paper Lanterns (cut)", CutNames.title("  Paper Lanterns "))
        assertEquals("Paper Lanterns (cut)", CutNames.title("Paper Lanterns (cut)"))
        assertEquals("Paper Lanterns (Cut)", CutNames.title("Paper Lanterns (Cut)"))
        assertEquals("Cut", CutNames.title("  "))
        assertFalse(CutNames.title("Paper Lanterns").contains('\u2014'))
    }

    @Test
    fun `a cut's file is named after the song's file, with (cut), as an m4a`() {
        assertEquals("Paper Lanterns (cut)", CutNames.base("Paper Lanterns.mp3"))
        assertEquals("01 - Tin Roof (cut)", CutNames.base("01 - Tin Roof.flac"))
        assertEquals("Paper Lanterns (cut)", CutNames.base("Paper Lanterns (cut).m4a"))
        assertEquals("Paper Lanterns (cut)", CutNames.base("Paper Lanterns"))
        assertEquals("Hidden Track (cut)", CutNames.base("..Hidden Track.ogg"))
        assertEquals("Cut", CutNames.base(".mp3"))
        assertEquals("Paper Lanterns (cut).m4a", CutNames.fileName("Paper Lanterns (cut)", 1))
        assertEquals("Paper Lanterns (cut) (2).m4a", CutNames.fileName("Paper Lanterns (cut)", 2))
        assertEquals("Paper Lanterns (cut) (12).m4a", CutNames.fileName("Paper Lanterns (cut)", 12))
    }

    @Test
    fun `a cut on its way is a hidden part file, and only KJB's part names read as one`() {
        assertEquals(".Paper Lanterns (cut).m4a.part", CutNames.partName("Paper Lanterns (cut)", 1))
        assertEquals(".Paper Lanterns (cut) (3).m4a.part", CutNames.partName("Paper Lanterns (cut)", 3))
        for (name in listOf(
            CutNames.partName("Paper Lanterns (cut)", 1),
            CutNames.partName("Paper Lanterns (cut)", 2),
            CutNames.partName("Paper Lanterns (cut)", CutNames.MAX_NUMBER),
            CutNames.partName(CutNames.base("Paper Lanterns (Cut).mp3"), 1),
            CutNames.partName(CutNames.base(".mp3"), 7),
            CutNames.partName(CutNames.base("Café Nocturne.flac"), 1),
        )) {
            assertTrue(name, CutNames.isPart(name))
        }
        for (name in listOf(
            // The cut itself, a hidden cut, another app's part, a download's part.
            "Paper Lanterns (cut).m4a",
            ".Paper Lanterns (cut).m4a",
            "Paper Lanterns (cut).m4a.part",
            ".Paper Lanterns.mp3.part",
            ".Paper Lanterns (cut).mp3.part",
            ".Paper Lanterns.m4a.part",
            // Numbers KJB never gives, other endings, a doubled dot.
            ".Paper Lanterns (cut) (1).m4a.part",
            ".Paper Lanterns (cut) (101).m4a.part",
            ".Paper Lanterns (cut) (02).m4a.part",
            ".Paper Lanterns (cut).m4a.part.bak",
            "..Paper Lanterns (cut).m4a.part",
            ".Paper Lanterns (cut)(2).m4a.part",
        )) {
            assertFalse(name, CutNames.isPart(name))
        }
    }

    @Test
    fun `a long file name is shortened, and the mark and a number still fit`() {
        val base = CutNames.base("x".repeat(400) + ".mp3")
        assertTrue(base, base.endsWith(" (cut)"))
        assertTrue("${base.length}", base.length <= 110)
        assertTrue(CutNames.fileName(base, 99).toByteArray().size < 255)
    }

    // Tags

    @Test
    fun `the cut keeps the song's tags with its own title, and drops what described the old audio`() {
        val original = mapOf(
            "TITLE" to listOf("Paper Lanterns"),
            "ARTIST" to listOf("Kit Calloway"),
            "ALBUM" to listOf("Harbour Glass"),
            "TRACKNUMBER" to listOf("3/11"),
            "GENRE" to listOf("Folk"),
            "LENGTH" to listOf("201000"),
            "REPLAYGAIN_TRACK_GAIN" to listOf("-6.2 dB"),
            "replaygain_album_peak" to listOf("0.98"),
            "ITUNSMPB" to listOf(" 00000000 00000840"),
            "ITUNNORM" to listOf(" 000003E8"),
        )
        assertEquals(
            mapOf(
                "TITLE" to listOf("Paper Lanterns (cut)"),
                "ARTIST" to listOf("Kit Calloway"),
                "ALBUM" to listOf("Harbour Glass"),
                "TRACKNUMBER" to listOf("3/11"),
                "GENRE" to listOf("Folk"),
            ),
            CutTags.fields(original, "Paper Lanterns (cut)", "Someone Else", "Other Album"),
        )
    }

    @Test
    fun `lyrics with times are dropped, since the cut starts elsewhere, and plain lyrics stay`() {
        val synced = mapOf("TITLE" to listOf("Paper Lanterns"), "LYRICS" to listOf("[00:12.40]la la\n[00:15.00]lo"))
        assertEquals(mapOf("TITLE" to listOf("Paper Lanterns (cut)")), CutTags.fields(synced, "Paper Lanterns (cut)", null, null))
        val plain = mapOf("lyrics" to listOf("la la\nlo"))
        assertEquals(mapOf("lyrics" to listOf("la la\nlo"), "TITLE" to listOf("Paper Lanterns (cut)")), CutTags.fields(plain, "Paper Lanterns (cut)", null, null))
    }

    @Test
    fun `a title tag in any case is replaced, never doubled`() {
        val fields = CutTags.fields(mapOf("Title" to listOf("Paper Lanterns", "Alt")), "Paper Lanterns (cut)", null, null)
        assertEquals(mapOf("TITLE" to listOf("Paper Lanterns (cut)")), fields)
    }

    @Test
    fun `a song whose tags KJB cannot read gives the cut the library's title, artist and album`() {
        assertEquals(
            mapOf("TITLE" to listOf("Paper Lanterns (cut)"), "ARTIST" to listOf("Kit Calloway"), "ALBUM" to listOf("Harbour Glass")),
            CutTags.fields(null, "Paper Lanterns (cut)", "Kit Calloway", "Harbour Glass"),
        )
        assertEquals(mapOf("TITLE" to listOf("Paper Lanterns (cut)")), CutTags.fields(null, "Paper Lanterns (cut)", " ", null))
    }

    // Formats and times

    @Test
    fun `Cut is offered for the formats every phone decodes`() {
        for (name in listOf("a.mp3", "a.M4A", "a.flac", "a.ogg", "a.opus", "a.wav", "a.aac", "a.3gp", "a.amr", "a.mka", "a.webm", "a.mp4", "a.oga")) {
            assertTrue(name, CutFormats.reads("/storage/emulated/0/Music/$name"))
        }
        for (name in listOf("a.wma", "a.ape", "a.mid", "a.dsf", "a", "mp3")) {
            assertFalse(name, CutFormats.reads("/storage/emulated/0/Music/$name"))
        }
    }

    @Test
    fun `times show hundredths, and hours for a long song`() {
        assertEquals("0:00.00", cutTime(0))
        assertEquals("0:00.99", cutTime(999))
        assertEquals("0:12.34", cutTime(12_345))
        assertEquals("1:12.25", cutTime(72_250))
        assertEquals("1:02:03.50", cutTime(3_723_500))
        assertEquals("0:00.00", cutTime(-40))
    }
}
