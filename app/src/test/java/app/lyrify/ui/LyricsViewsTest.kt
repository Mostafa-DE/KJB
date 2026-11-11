package app.lyrify.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import app.lyrify.data.LyricsStatus
import app.lyrify.data.recorded
import app.lyrify.lyrics.LocalTrack
import app.lyrify.lyrics.LrcLine
import app.lyrify.settings.LyricsAlign
import app.lyrify.settings.LyricsSettings
import app.lyrify.settings.LyricsSize
import app.lyrify.settings.LyricsWeight
import app.lyrify.settings.PlayerUiSettings
import app.lyrify.settings.Settings
import app.lyrify.settings.SettingsJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lyrics on the player, as pure rules: the side the player remembers, "Aa", the lookup's plain lyrics standing in for a
 * missing file, which editor the "⋯" opens, and a tap on a line. Made-up names and lines only.
 */
class LyricsViewsTest {
    // The flip and the side remembered

    @Test
    fun `the side shown last and the one-time hint survive a restart`() {
        val flipped = Settings(playerUi = PlayerUiSettings(showLyrics = true, flipHintShown = true))
        val read = SettingsJson.decode(SettingsJson.encode(flipped))!!
        assertEquals(flipped.playerUi, read.playerUi)
        // Opened again on the lyrics, it says nothing; on a first open, on the cover, it says "Tap the cover for lyrics".
        assertFalse(flipHintOnOpen(read.playerUi))
        assertTrue(flipHintOnOpen(Settings().playerUi))
        assertFalse(Settings().playerUi.showLyrics)
    }

    // "Aa"

    @Test
    fun `Aa sizes grow from S to XL around M, weights are the prototype's, and centre centres`() {
        val looks = LyricsSize.entries.map { lyricsLook(LyricsSettings(textSize = it)).scale }
        assertEquals(looks.sorted(), looks)
        assertEquals(looks.size, looks.distinct().size)
        assertEquals(1f, lyricsLook(LyricsSettings(textSize = LyricsSize.M)).scale)

        val bold = lyricsLook(LyricsSettings(textWeight = LyricsWeight.BOLD))
        val regular = lyricsLook(LyricsSettings(textWeight = LyricsWeight.REGULAR))
        // The line playing stands out by its size and colour; every timed line has one weight (the prototype's --lw),
        // plain lyrics a lighter one (--lw2): 650 and 560 bold, 440 and 400 regular.
        assertEquals(FontWeight(650), bold.current)
        assertEquals(FontWeight(650), bold.other)
        assertEquals(FontWeight(560), bold.plain)
        assertEquals(FontWeight(440), regular.current)
        assertEquals(FontWeight(440), regular.other)
        assertEquals(FontWeight.Normal, regular.plain)

        assertEquals(TextAlign.Start, lyricsLook(LyricsSettings(textAlign = LyricsAlign.LEFT)).align)
        assertEquals(TextAlign.Center, lyricsLook(LyricsSettings(textAlign = LyricsAlign.CENTRE)).align)
    }

    @Test
    fun `the Aa choices are remembered, each on its own`() {
        val chosen = Settings(lyrics = LyricsSettings(showWhenLocked = true, textSize = LyricsSize.XL, textWeight = LyricsWeight.REGULAR, textAlign = LyricsAlign.CENTRE))
        assertEquals(chosen.lyrics, SettingsJson.decode(SettingsJson.encode(chosen))!!.lyrics)
        // One unknown choice reads as its default; the others stay.
        val text = SettingsJson.encode(chosen).replace("\"XL\"", "\"XXL\"")
        assertEquals(chosen.lyrics.copy(textSize = LyricsSize.M), SettingsJson.decode(text)!!.lyrics)
    }

    // Plain lyrics without a file

    private val track = LocalTrack("/storage/emulated/0/Music/Harbour Lights.mp3", "Harbour Lights", "Tin Roof Choir", null, 200_000)

    @Test
    fun `a song with plain lyrics on LRCLIB shows them, fetched by its record`() {
        assertEquals(7L, plainRecord(recorded(null, track, LyricsStatus.PLAIN_ONLY, 7, 1)))
        assertNull(plainRecord(recorded(null, track, LyricsStatus.PLAIN_ONLY, null, 1)))
        assertNull(plainRecord(recorded(null, track, LyricsStatus.NOT_FOUND, 7, 1)))
        assertNull(plainRecord(recorded(null, track, LyricsStatus.SYNCED, 7, 1)))
        assertNull(plainRecord(null))
        assertEquals(listOf("Paper lanterns rise", "Over the harbour glass"), plainLines("\uFEFF[ar:Tin Roof Choir]\n Paper lanterns rise \n\n\nOver the harbour glass\n"))
        assertEquals(emptyList<String>(), plainLines(" \n\n"))
    }

    // The editor the "⋯" opens

    @Test
    fun `synced lyrics offer Edit timing, plain ones Make synced, and no lyrics neither`() {
        assertEquals(SyncAction.EDIT, syncAction(PanelLyrics.Synced(listOf(LrcLine(1_000, "Paper lanterns rise")))))
        assertEquals(SyncAction.MAKE, syncAction(PanelLyrics.Plain(listOf("Paper lanterns rise"))))
        assertNull(syncAction(PanelLyrics.Finding))
        assertNull(syncAction(PanelLyrics.None))
        assertNull(syncAction(null))
    }

    // A tap on a line

    @Test
    fun `a tap on a line goes to its start, and plays when the song was paused`() {
        val line = LrcLine(64_250, "Over the harbour glass")
        assertEquals(LineTap(64_250, play = true), lineTap(line, playing = false))
        assertEquals(LineTap(64_250, play = false), lineTap(line, playing = true))
    }

    // Following the song (no reflow: one style for every line, colour only, a smooth scroll to a fixed anchor)

    @Test
    fun `the line playing has the same size, line height and weight as every other line, whatever the Aa choices`() {
        val bases = listOf(PanelLine, FullLine, LockLine)
        LyricsSize.entries.forEach { size ->
            LyricsWeight.entries.forEach { weight ->
                val look = lyricsLook(LyricsSettings(textSize = size, textWeight = weight))
                bases.forEach { base -> assertEquals(look.style(base, playing = false), look.style(base, playing = true)) }
            }
        }
    }

    @Test
    fun `the lines follow the song until the user scrolls, then wait 3,5 s, and a tap on a line follows again at once`() {
        val follow = LyricsFollow()
        assertTrue(follow.follows(now = 0))
        assertEquals(0L, follow.waitMs(now = 0))
        val touched = follow.touched(now = 10_000)
        assertFalse(touched.follows(now = 10_000))
        assertFalse(touched.follows(now = 13_499))
        assertEquals(1_000L, touched.waitMs(now = 12_500))
        assertTrue(touched.follows(now = 13_500))
        assertTrue(touched.tapped().follows(now = 10_001))
    }

    @Test
    fun `the line playing scrolls until its middle sits at the anchor, from where it is or from its row when off screen`() {
        // A row 100 px tall in a 2000 px list, anchored at 38 percent, has its top at 710 px once in place.
        assertEquals(-10, followDelta(itemTop = 700, itemSize = 100, viewport = 2000, anchor = 0.38f))
        assertEquals(0, followDelta(itemTop = 710, itemSize = 100, viewport = 2000, anchor = 0.38f))
        assertEquals(290, followDelta(itemTop = 1000, itemSize = 100, viewport = 2000, anchor = 0.38f))
        // Off screen: the row's top lands below the top padding by the anchor, less half a row.
        assertEquals(48 - 710, followOffset(padTop = 48, rowSize = 100, viewport = 2000, anchor = 0.38f))
    }

    // Review M2: lines sharing a time, a seek or a finger during the pause, a line scrolled to from off screen

    private val tied = listOf(LrcLine(1_000, "Paper lanterns rise"), LrcLine(5_000, "Over the harbour glass"), LrcLine(5_000, "Sobre el vidrio del puerto"), LrcLine(9_000, "Tin roof choir"))

    @Test
    fun `lines that share a time are current together, the first of them leading, and none of them counts as sung before its time`() {
        assertEquals(1, currentLine(tied, 6_000))
        assertEquals(LineState.PAST, lineState(tied, at = 1, index = 0))
        assertEquals(LineState.NOW, lineState(tied, at = 1, index = 1))
        assertEquals(LineState.NOW, lineState(tied, at = 1, index = 2))
        assertEquals(LineState.NEXT, lineState(tied, at = 1, index = 3))
        assertEquals(LineState.NEXT, lineState(tied, at = -1, index = 0))
        assertEquals(3, currentLine(tied, 9_000))
    }

    @Test
    fun `a seek or a jump to another line ends the pause at once, the song moving on to the next line does not`() {
        val paused = LyricsFollow().touched(now = 10_000)
        assertFalse(paused.moved(from = 4, to = 5).follows(now = 10_500))
        assertFalse(paused.moved(from = 4, to = 4).follows(now = 10_500))
        assertTrue(paused.moved(from = 4, to = 12).follows(now = 10_500))
        assertTrue(paused.moved(from = 4, to = 2).follows(now = 10_500))
    }

    @Test
    fun `the line playing is the accent, darker on light, and the lines to come are clearly dimmer than it`() {
        listOf(Triple(LightColors, LightBrandColors, false), Triple(DarkColors, DarkBrandColors, true)).forEach { (scheme, brand, dark) ->
            val tones = lyricTones(scheme, brand, dark)
            // The orange of the song playing's title: the token itself on dark, the token darkened toward its own ink on light.
            if (dark) assertEquals(brand.highlight, tones.now) else assertNotEquals(brand.highlight, tones.now)
            assertEquals(scheme.outline, tones.past)
            listOf(scheme.background, scheme.surfaceContainerLow).forEach { under ->
                val now = contrast(tones.now, under)
                val next = contrast(tones.next.compositeOver(under), under)
                assertTrue("now $now", now >= 4.5f)
                assertTrue("next $next", next >= 3f)
                // Lit apart by its lightness too, not by its hue alone.
                assertTrue("now $now, next $next", now / next >= 1.3f)
            }
        }
    }

    @Test
    fun `the line playing is lit whole, in one colour, its word times never shown`() {
        val tones = LineTones(now = Color.White, past = Color.Gray, next = Color.DarkGray)
        assertEquals("Paper lanterns rise", shownLine("<00:01.00>Paper <00:01.52>lanterns <00:02.10>rise"))
        assertEquals("Over the harbour glass", shownLine("Over the harbour glass"))
        assertEquals("Tin roof", shownLine("<1:02.3>Tin <1:02:45>roof<1:03.00>"))
        // One colour for the whole line, whatever its words' times say.
        assertEquals(Color.White, lineColor(LineState.NOW, tones))
        assertEquals(Color.Gray, lineColor(LineState.PAST, tones))
        assertEquals(Color.DarkGray, lineColor(LineState.NEXT, tones))
    }

    @Test
    fun `a finger resting on the lines stops the follow from its touch, however long it stays, and the wait starts when it lifts`() {
        val follow = LyricsFollow()
        assertTrue(follow.follows(now = 0))
        // Down, not moving: no follow, however long.
        val down = follow.down(now = 1_000)
        assertFalse(down.follows(now = 1_000))
        assertFalse(down.follows(now = 60_000))
        // Up: the 3.5 s wait starts then.
        val up = down.up(now = 61_000)
        assertFalse(up.follows(now = 64_499))
        assertTrue(up.follows(now = 64_500))
        // A tap on a line while held follows at once once the finger is up, as a tap's own down and up do.
        assertTrue(down.up(now = 2_000).tapped().follows(now = 2_001))
    }

    @Test
    fun `a line scrolled to from off screen is corrected by its own height once it is laid out`() = kotlinx.coroutines.runBlocking {
        // 2000 px list, 48 px top padding, anchored at 38%: a 300 px line must end with its top at 610 px.
        val list = FakeLines(firstVisibleHeight = 60)
        followLine(list, at = 30, padTop = 48, viewport = 2_000, anchor = 0.38f, smooth = true)
        assertEquals(listOf("jump 30 ${followOffset(48, 60, 2_000, 0.38f)}", "by ${list.laidTop - 610}"), list.calls)
        // Already laid out: one smooth move by what is left.
        val near = FakeLines(firstVisibleHeight = 60, visible = mapOf(5 to (700 to 100)))
        followLine(near, at = 5, padTop = 48, viewport = 2_000, anchor = 0.38f, smooth = true)
        assertEquals(listOf("by -10"), near.calls)
    }

    @Test
    fun `before the first line, after a song starts again too, the lines go back to their start`() = kotlinx.coroutines.runBlocking {
        // Repeat one: the song is back before its first line while the list still shows its last ones.
        val list = FakeLines(firstVisibleHeight = 60, visible = mapOf(40 to (700 to 100)))
        followLine(list, at = -1, padTop = 48, viewport = 2_000, anchor = 0.38f, smooth = true)
        assertEquals(listOf("jump 0 0"), list.calls)
    }

    /** The list as followLine sees it: line 30 is not laid out until a jump lands it 40 px off, 300 px tall. */
    private class FakeLines(private val firstVisibleHeight: Int, visible: Map<Int, Pair<Int, Int>> = emptyMap()) : LineList {
        val calls = mutableListOf<String>()
        private val laid = visible.toMutableMap()
        val laidTop = 650

        override fun laidOut(index: Int): Pair<Int, Int>? = laid[index]

        override fun firstHeight(): Int = firstVisibleHeight

        override suspend fun jumpTo(index: Int, offset: Int, smooth: Boolean) {
            calls += "jump $index $offset"
            laid[index] = laidTop to 300
        }

        override suspend fun moveBy(px: Int, smooth: Boolean) {
            calls += "by $px"
        }
    }

    @Test
    fun `in every lyrics view the first and last lines rest clear of the faded edges, even in a short list`() {
        // The smallest flip area the prototype plans for is 200 dp tall.
        val shortest = 200f
        listOf(PanelFrame, FullFrame, LockFrame).forEach { frame ->
            assertTrue(frame.top * shortest >= LYRICS_FADE.value)
            assertTrue(frame.bottom * shortest >= LYRICS_FADE.value)
            // The anchor sits below the top fade, so the line playing is never faded.
            assertTrue(frame.anchor * shortest > LYRICS_FADE.value)
        }
    }
}
