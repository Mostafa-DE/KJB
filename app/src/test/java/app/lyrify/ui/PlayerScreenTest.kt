package app.lyrify.ui

import androidx.compose.ui.unit.dp
import app.lyrify.R
import app.lyrify.data.LyricsStatus
import app.lyrify.library.song
import app.lyrify.lyrics.LrcLine
import app.lyrify.player.PlayerUi
import app.lyrify.player.QueueBook
import app.lyrify.player.QueueSource
import app.lyrify.player.Repeat
import app.lyrify.player.SourceKind
import app.lyrify.player.seekTarget
import app.lyrify.settings.LibrarySettings
import app.lyrify.settings.PlaybackSettings
import app.lyrify.settings.PlayerUiSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The player screen's rules, as pure functions: the seek buttons, the flip side, the Now bar, the lyrics panel. Made-up names and lines only. */
class PlayerScreenTest {
    // The seek buttons ("Skip amounts")

    @Test
    fun `the seek buttons show the chosen amounts, 15 included, with the default for anything else`() {
        assertEquals(R.drawable.ic_replay_30, seekIcon(back = true, seconds = PlaybackSettings().skipBackSeconds))
        assertEquals(R.drawable.ic_forward_10, seekIcon(back = false, seconds = PlaybackSettings().skipForwardSeconds))
        assertEquals(
            listOf(R.drawable.ic_replay_5, R.drawable.ic_replay_10, R.drawable.ic_replay_15, R.drawable.ic_replay_30),
            PlaybackSettings.SKIP_SECONDS.map { seekIcon(back = true, seconds = it) },
        )
        assertEquals(
            listOf(R.drawable.ic_forward_5, R.drawable.ic_forward_10, R.drawable.ic_forward_15, R.drawable.ic_forward_30),
            PlaybackSettings.SKIP_SECONDS.map { seekIcon(back = false, seconds = it) },
        )
        assertEquals(R.drawable.ic_replay_30, seekIcon(back = true, seconds = 7))
        assertEquals(R.drawable.ic_forward_10, seekIcon(back = false, seconds = 7))
    }

    @Test
    fun `holding a seek button repeats the jump, once a short hold has passed`() {
        assertEquals(1, jumpsWhileHeld(0))
        assertEquals(1, jumpsWhileHeld(HOLD_DELAY_MS - 1))
        assertEquals(2, jumpsWhileHeld(HOLD_DELAY_MS))
        assertEquals(2, jumpsWhileHeld(HOLD_DELAY_MS + HOLD_EVERY_MS - 1))
        assertEquals(3, jumpsWhileHeld(HOLD_DELAY_MS + HOLD_EVERY_MS))
        assertEquals(11, jumpsWhileHeld(HOLD_DELAY_MS + 9 * HOLD_EVERY_MS))
    }

    @Test
    fun `a jump stays inside the song`() {
        assertEquals(0L, seekTarget(12_000, 200_000, -30_000))
        assertEquals(200_000L, seekTarget(195_000, 200_000, 10_000))
        assertEquals(52_000L, seekTarget(42_000, 200_000, 10_000))
        assertEquals(52_000L, seekTarget(42_000, 0, 10_000))
    }

    // The flip area

    @Test
    fun `the player opens on the side shown last, and tells about the flip once, on the cover side`() {
        assertTrue(flipHintOnOpen(PlayerUiSettings()))
        assertFalse(flipHintOnOpen(PlayerUiSettings(flipHintShown = true)))
        assertFalse(flipHintOnOpen(PlayerUiSettings(showLyrics = true)))
    }

    @Test
    fun `the links and the progress bar keep 16 dp of clear space between their touch areas`() {
        assertTrue(linksToBarClearance(chips = false) >= 16.dp)
        assertTrue(linksToBarClearance(chips = true) >= 16.dp)
    }

    // The lyrics panel

    private val synced = "[00:01.000]Paper lanterns rise\n[00:04.500]Over the harbour glass\n[00:09.250]\n[00:10.000]And the ferry hums\n"

    @Test
    fun `a synced lrc shows its timed lines, plain text shows as it is, and without a file the ledger says why`() {
        val lines = (panelLyrics(synced, LyricsStatus.SYNCED) as PanelLyrics.Synced).lines
        assertEquals(listOf(LrcLine(1_000, "Paper lanterns rise"), LrcLine(4_500, "Over the harbour glass"), LrcLine(10_000, "And the ferry hums")), lines)
        assertEquals(PanelLyrics.Plain(listOf("Paper lanterns rise", "Over the harbour glass")), panelLyrics("[ar:Kit Calloway]\nPaper lanterns rise\nOver the harbour glass\n", LyricsStatus.EXISTING))
        assertEquals(PanelLyrics.Finding, panelLyrics(null, null))
        assertEquals(PanelLyrics.Finding, panelLyrics(null, LyricsStatus.WRITING))
        assertEquals(PanelLyrics.None, panelLyrics(null, LyricsStatus.NOT_FOUND))
        assertEquals(PanelLyrics.None, panelLyrics("   \n", LyricsStatus.SYNCED))
    }

    @Test
    fun `the current line is the last one that has started`() {
        val lines = (panelLyrics(synced, LyricsStatus.SYNCED) as PanelLyrics.Synced).lines
        assertEquals(-1, currentLine(lines, 999))
        assertEquals(0, currentLine(lines, 1_000))
        assertEquals(1, currentLine(lines, 9_999))
        assertEquals(2, currentLine(lines, 60_000))
        assertEquals(-1, currentLine(emptyList(), 5_000))
    }

    @Test
    fun `times read as minutes and seconds, hours when a song is that long`() {
        assertEquals("0:00", clock(0))
        assertEquals("1:42", clock(102_400))
        assertEquals("10:05", clock(605_999))
        assertEquals("1:02:03", clock(3_723_000))
        assertEquals("0:00", clock(-5))
    }

    // The Now bar's own song

    @Test
    fun `the Now bar shows KJB's own song, as the library names it, with its progress`() {
        val lantern = song("Music/Harbour Glass/01 Paper Lantern.mp3", title = "Paper Lantern", artist = "Kit Calloway", durationMs = 200_000)
        val book = QueueBook().play(listOf(lantern.ref), 0, QueueSource(SourceKind.ALBUM, "album:x", "Harbour Glass"), now = 1).book
        val queue = book.current!!
        val ui = PlayerUi(book, queue, queue.current!!, lantern, playing = true, positionMs = 50_000, at = 1_000, speed = 1f, durationMs = 200_000, repeat = Repeat.OFF, library = null)
        assertEquals(OwnNow("Paper Lantern", "Kit Calloway", SongArt(lantern.path), playing = true, progress = 0.255f), ownNow(ui, LibrarySettings(), now = 2_000))
        assertEquals("01 Paper Lantern.mp3", ownNow(ui, LibrarySettings(showFileNames = true), now = 2_000)!!.title)
        assertEquals("01 Paper Lantern.mp3", ownNow(ui.copy(song = null), LibrarySettings(), now = 2_000)!!.title)
        assertNull(ownNow(null, LibrarySettings(), now = 2_000))
    }
}
