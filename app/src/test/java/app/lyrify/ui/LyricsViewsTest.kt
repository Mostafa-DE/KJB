package app.lyrify.ui

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
}
