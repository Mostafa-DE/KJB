package app.lyrify.ui

import androidx.compose.ui.unit.dp
import app.lyrify.R
import app.lyrify.data.LedgerEntry
import app.lyrify.data.LyricsStatus
import app.lyrify.library.SongRef
import app.lyrify.library.song
import app.lyrify.lyrics.LrcLine
import app.lyrify.player.PlayerUi
import app.lyrify.player.QueueBook
import app.lyrify.player.QueueSource
import app.lyrify.player.Repeat
import app.lyrify.player.SourceKind
import app.lyrify.player.Transport
import app.lyrify.player.playerUi
import app.lyrify.player.seekTarget
import app.lyrify.settings.LibrarySettings
import app.lyrify.settings.PlaybackSettings
import app.lyrify.settings.PlayerUiSettings
import kotlinx.coroutines.test.runTest
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
    fun `a seek button tapped jumps once on the lift, and held jumps from the short hold on, on every beat`() {
        // A tap: one jump, at the lift (no longer at the press, which may be the start of a drag down, review 2026-11-17).
        assertEquals(1, jumpsWhileHeld(0))
        assertEquals(1, jumpsWhileHeld(HOLD_DELAY_MS - 1))
        // Held: the first jump once the short hold has passed, then one every beat.
        assertEquals(1, jumpsWhileHeld(HOLD_DELAY_MS))
        assertEquals(1, jumpsWhileHeld(HOLD_DELAY_MS + HOLD_EVERY_MS - 1))
        assertEquals(2, jumpsWhileHeld(HOLD_DELAY_MS + HOLD_EVERY_MS))
        assertEquals(10, jumpsWhileHeld(HOLD_DELAY_MS + 9 * HOLD_EVERY_MS))
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
        assertTrue(flipHintOnOpen(PlayerUiSettings(), book = false))
        assertFalse(flipHintOnOpen(PlayerUiSettings(flipHintShown = true), book = false))
        assertFalse(flipHintOnOpen(PlayerUiSettings(showLyrics = true), book = false))
    }

    @Test
    fun `a book shows its cover whatever side songs show, and the flip is told about on a song`() {
        assertTrue(showsLyrics(PlayerUiSettings(showLyrics = true), book = false, touring = false))
        assertFalse(showsLyrics(PlayerUiSettings(), book = false, touring = false))
        assertFalse(showsLyrics(PlayerUiSettings(showLyrics = true), book = true, touring = false))
        // The tour's lyrics step still turns it, onto the book's calm state.
        assertTrue(showsLyrics(PlayerUiSettings(), book = true, touring = true))
        assertFalse(flipHintOnOpen(PlayerUiSettings(), book = true))
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
    fun `a book's part has no lyrics, its lrc is never read and LRCLIB never asked, whatever the ledger says`() = runTest {
        val path = "/storage/emulated/0/Audiobooks/Lantern Keeper/Lantern Keeper.m4b"
        // Looked up as a song before it became a book: LRCLIB has plain lyrics for it.
        val entry = LedgerEntry(path = path, title = "Lantern Keeper", durationMs = 200_000, status = LyricsStatus.PLAIN_ONLY, lrclibId = 42, updatedAt = 1)
        var reads = 0
        var asks = 0
        val lrc: suspend (String) -> String? = { reads++; null }
        val plain: suspend (Long) -> String? = { asks++; "Paper lanterns rise" }
        assertEquals(PanelLyrics.Book, songLyrics(path, entry, book = true, lrc, plain))
        assertEquals(PanelLyrics.Book, songLyrics(path, null, book = true, lrc, plain))
        assertEquals(0 to 0, reads to asks)
        assertNull(syncAction(PanelLyrics.Book))
        // The same file as a song: its lrc read, and without one the plain lyrics LRCLIB has.
        assertEquals(PanelLyrics.Plain(listOf("Paper lanterns rise")), songLyrics(path, entry, book = false, lrc, plain))
        assertEquals(1 to 1, reads to asks)
        assertEquals(PanelLyrics.Finding, songLyrics(path, null, book = false, lrc, plain))
    }

    @Test
    fun `a flip on a book's part, as the tour's cover chip makes, never changes the side kept for songs`() {
        val onLyrics = PlayerUiSettings(showLyrics = true, flipHintShown = true)
        assertEquals(onLyrics, flipped(onLyrics, lyrics = false, book = true))
        assertEquals(PlayerUiSettings(), flipped(PlayerUiSettings(), lyrics = true, book = true))
        // A song's flip is kept.
        assertEquals(onLyrics.copy(showLyrics = false), flipped(onLyrics, lyrics = false, book = false))
        assertEquals(PlayerUiSettings(showLyrics = true), flipped(PlayerUiSettings(), lyrics = true, book = false))
    }

    @Test
    fun `the player's menu offers Make a tone for a song, never for a book's part, whose lrc the editor would read`() {
        assertTrue(makesTone(book = false))
        assertFalse(makesTone(book = true))
        // Not known yet while the library is read: nothing that reads lyrics, as for Share lyrics.
        assertFalse(makesTone(book = null))
    }

    // The second row and the "⋯" (3.1, user 2026-11-18)

    @Test
    fun `the second row is Shuffle, Queue, the heart, Library and Repeat, and a book keeps the heart in the middle`() {
        val music = secondaryButtons(book = false, chapters = false)
        assertEquals(listOf(PlayerButton.SHUFFLE, PlayerButton.QUEUE, PlayerButton.FAVOURITE, PlayerButton.LIBRARY, PlayerButton.REPEAT), music)
        // A book never shuffles or repeats: Chapters in Shuffle's place when it has some, room for the rest.
        assertEquals(listOf(PlayerButton.CHAPTERS, PlayerButton.QUEUE, PlayerButton.FAVOURITE, PlayerButton.LIBRARY, PlayerButton.ROOM), secondaryButtons(book = true, chapters = true))
        assertEquals(listOf(PlayerButton.ROOM, PlayerButton.QUEUE, PlayerButton.FAVOURITE, PlayerButton.LIBRARY, PlayerButton.ROOM), secondaryButtons(book = true, chapters = false))
        assertEquals(music, secondaryButtons(book = false, chapters = true))
        // Five places whatever plays, the heart in the middle one; the sleep timer is in the "⋯" now.
        for (book in listOf(false, true)) for (chapters in listOf(false, true)) assertEquals(PlayerButton.FAVOURITE, secondaryButtons(book, chapters)[2])
    }

    @Test
    fun `the player's menu has the sleep timer after Speed, and Edit tags after Song info for a song of the library only`() {
        val all = playerMenuRows(song = true, album = true, artist = true, tone = true, cut = true, bookmarks = true)
        assertEquals(PlayerMenuRow.entries, all)
        assertEquals(PlayerMenuRow.SLEEP, all[all.indexOf(PlayerMenuRow.SPEED) + 1])
        assertEquals(PlayerMenuRow.EDIT_TAGS, all[all.indexOf(PlayerMenuRow.SONG_INFO) + 1])
        assertEquals(PlayerMenuRow.MOVE_TO_TRASH, all.last())
        // A song of the library with no album or artist page, no lyrics, a book's part (no tone): Edit tags still, as on its sheet.
        assertEquals(
            listOf(
                PlayerMenuRow.ADD_TO, PlayerMenuRow.SPEED, PlayerMenuRow.SLEEP, PlayerMenuRow.REPEAT_PART, PlayerMenuRow.BOOKMARK, PlayerMenuRow.PLAY_ON,
                PlayerMenuRow.EQUALIZER, PlayerMenuRow.SHARE, PlayerMenuRow.SONG_INFO, PlayerMenuRow.EDIT_TAGS, PlayerMenuRow.MOVE_TO_TRASH,
            ),
            playerMenuRows(song = true, album = false, artist = false, tone = false, cut = false),
        )
        // A file another app opened, not in the library: nothing KJB could write to, share or trash.
        assertEquals(
            listOf(PlayerMenuRow.ADD_TO, PlayerMenuRow.SPEED, PlayerMenuRow.SLEEP, PlayerMenuRow.REPEAT_PART, PlayerMenuRow.BOOKMARK, PlayerMenuRow.PLAY_ON, PlayerMenuRow.EQUALIZER, PlayerMenuRow.TONE, PlayerMenuRow.SONG_INFO),
            playerMenuRows(song = false, album = false, artist = false, tone = true, cut = false),
        )
    }

    @Test
    fun `the player's menu has Bookmarks right after Bookmark, only while what plays has bookmarks`() {
        val marked = playerMenuRows(song = true, album = false, artist = false, tone = false, cut = false, bookmarks = true)
        assertEquals(PlayerMenuRow.BOOKMARKS, marked[marked.indexOf(PlayerMenuRow.BOOKMARK) + 1])
        // A file another app opened keeps its bookmarks too.
        assertTrue(PlayerMenuRow.BOOKMARKS in playerMenuRows(song = false, album = false, artist = false, tone = false, cut = false, bookmarks = true))
        assertFalse(PlayerMenuRow.BOOKMARKS in playerMenuRows(song = true, album = true, artist = true, tone = true, cut = true, bookmarks = false))
    }

    @Test
    fun `the player's menu has Cut right after Make a tone, for a song of the library KJB can cut and no book's part`() {
        val rows = playerMenuRows(song = true, album = false, artist = false, tone = true, cut = true)
        assertEquals(PlayerMenuRow.CUT, rows[rows.indexOf(PlayerMenuRow.TONE) + 1])
        assertEquals(PlayerMenuRow.SHARE, rows[rows.indexOf(PlayerMenuRow.CUT) + 1])
        assertFalse(PlayerMenuRow.CUT in playerMenuRows(song = true, album = false, artist = false, tone = true, cut = false))
        // One Share in its place, Song or Lyrics asked from it for a song with lyrics (ShareMenu): no Share lyrics row.
        val all = playerMenuRows(song = true, album = true, artist = true, tone = true, cut = true)
        assertEquals(listOf(PlayerMenuRow.TONE, PlayerMenuRow.CUT, PlayerMenuRow.SHARE, PlayerMenuRow.SONG_INFO), all.subList(all.indexOf(PlayerMenuRow.TONE), all.indexOf(PlayerMenuRow.SONG_INFO) + 1))
        // The song sheet's rule: a format the phone decodes, not a book.
        val harbour = song("Music/Kite Weather/Paper Harbour.mp3")
        assertTrue(cutsSong(harbour, book = false))
        assertTrue(cutsSong(song("Music/Kite Weather/Paper Harbour (live).FLAC"), book = false))
        assertFalse(cutsSong(harbour, book = true))
        // Not known yet as a song or a book while the library is read: no Cut, as no Make a tone.
        assertFalse(cutsSong(harbour, book = null))
        // A file another app opened is no song of the library, and the cutter takes only those.
        assertFalse(cutsSong(song = null, book = false))
        assertFalse(cutsSong(song("Music/Kite Weather/Paper Harbour.wma"), book = false))
    }

    @Test
    fun `a file not known yet as a song or a book has nothing read until the library tells`() = runTest {
        val path = "/storage/emulated/0/Music/Lantern Keeper/01 Lantern Keeper.mp3"
        val entry = LedgerEntry(path = path, title = "Lantern Keeper", durationMs = 200_000, status = LyricsStatus.PLAIN_ONLY, lrclibId = 42, updatedAt = 1)
        var reads = 0
        var asks = 0
        val lrc: suspend (String) -> String? = { reads++; null }
        val plain: suspend (Long) -> String? = { asks++; "Paper lanterns rise" }
        assertNull(songLyrics(path, entry, book = null, lrc, plain))
        assertNull(songLyrics(path, null, book = null, lrc, plain))
        assertEquals(0 to 0, reads to asks)
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
        // Its path too, so the song's row in the lists shows it playing.
        assertEquals(OwnNow("Paper Lantern", "Kit Calloway", SongArt(lantern.path), playing = true, progress = 0.255f, path = lantern.path), ownNow(ui, LibrarySettings(), now = 2_000))
        assertEquals("01 Paper Lantern.mp3", ownNow(ui, LibrarySettings(showFileNames = true), now = 2_000)!!.title)
        assertEquals("01 Paper Lantern.mp3", ownNow(ui.copy(song = null), LibrarySettings(), now = 2_000)!!.title)
        assertNull(ownNow(null, LibrarySettings(), now = 2_000))
    }

    /**
     * A file another app opened that the library lacks (phone session 10: the player showed the copy's file name and
     * "Opened file"): the player, the Now bar and the lyrics name it by its own tags, which open no page; by its file's
     * name only when none were read.
     */
    @Test
    fun `a file another app opened is named by its own tags on the player and the Now bar`() {
        val path = "/data/user/0/app.lyrify/cache/opened/1000/0/Paper Harbour (demo).mp3"
        val tagged = song("Music/x.mp3", title = "Paper Harbour", artist = "Mira Solen", album = "Glass Harbour Sessions").copy(ref = SongRef(path))
        val book = QueueBook().play(listOf(SongRef(path)), 0, QueueSource(SourceKind.OPENED, "opened", ""), now = 1).book
        // The connection hands over the copy's tags while the library lacks the song.
        val ui = playerUi(book, Repeat.OFF, place = null, Transport(), library = null, now = 0, opened = mapOf(path to tagged))!!
        // In no library: no heart, no artist or album page.
        assertNull(ui.song)
        assertEquals(tagged, ui.opened)
        assertEquals(tagged, ui.named)
        assertEquals("Paper Harbour", shownTitle(ui, LibrarySettings()))
        assertEquals(
            OwnNow("Paper Harbour", "Mira Solen", SongArt(path), playing = false, progress = 0f, path = path, album = "Glass Harbour Sessions"),
            ownNow(ui, LibrarySettings(), now = 0),
        )
        // Show file names: its file's name, as any song's.
        assertEquals("Paper Harbour (demo).mp3", shownTitle(ui, LibrarySettings(showFileNames = true)))
        // No tags read for it: its file's name.
        assertEquals("Paper Harbour (demo).mp3", shownTitle(ui.copy(opened = null), LibrarySettings()))
        assertEquals("Paper Harbour (demo).mp3", playerUi(book, Repeat.OFF, place = null, Transport(), library = null, now = 0)!!.let { shownTitle(it, LibrarySettings()) })
    }

    // The line under the title

    @Test
    fun `the line under the title shows the artist and the album as tagged, the same name twice included`() {
        assertEquals(MetaLine(MetaName("Mira Solen"), MetaName("Night Ferry")), metaLine("Mira Solen", "Night Ferry"))
        assertEquals(MetaLine(MetaName("Tin Roof"), MetaName("Tin Roof")), metaLine("Tin Roof", "Tin Roof"))
        assertTrue(metaLine("Mira Solen", "Night Ferry").artist.tappable)
        assertTrue(metaLine("Mira Solen", "Night Ferry").album.tappable)
    }

    @Test
    fun `a missing tag shows its placeholder, which is not tappable`() {
        val noArtist = metaLine(null, "Night Ferry")
        assertEquals(MetaName(null), noArtist.artist)
        assertFalse(noArtist.artist.tappable)
        assertTrue(noArtist.album.tappable)
        val neither = metaLine("  ", " ")
        assertEquals(MetaLine(MetaName(null), MetaName(null)), neither)
        assertFalse(neither.artist.tappable)
        assertFalse(neither.album.tappable)
    }
}
