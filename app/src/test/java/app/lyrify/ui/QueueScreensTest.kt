package app.lyrify.ui

import app.lyrify.library.SongRef
import app.lyrify.library.song
import app.lyrify.player.QueueBook
import app.lyrify.player.QueueSource
import app.lyrify.player.QueueTarget
import app.lyrify.player.Repeat
import app.lyrify.player.SourceKind
import app.lyrify.player.Transport
import app.lyrify.player.playerUi
import app.lyrify.settings.LibrarySettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The queues' screens after the user's first day with 3.0.0 (QF3: "0 saved" confused, and the queue playing did not
 * show): no Saved part anywhere, the queue playing named plainly, a queue's list opening with its song in view, and no
 * count that reads 0. The copy is read from the resources as they ship. Made-up names only.
 */
class QueueScreensTest {
    private val player = File("src/main/res/values/strings_player.xml").readText()
    private val library = File("src/main/res/values/strings_library.xml").readText()

    /** The text of the string or plurals [name] in [resources]; null when there is none. */
    private fun named(resources: String, name: String): String? =
        Regex("""<(string|plurals) name="$name"[^>]*>(.*?)</\1>""", RegexOption.DOT_MATCHES_ALL).find(resources)?.groupValues?.get(2)

    @Test
    fun `the Playing now card names a file another app opened by its own tags, as the player does`() {
        val path = "/data/user/0/app.lyrify/cache/opened/1000/0/Paper Harbour (demo).mp3"
        val tagged = song("Music/x.mp3", title = "Paper Harbour", artist = "Mira Solen").copy(ref = SongRef(path))
        val book = QueueBook().play(listOf(SongRef(path)), 0, QueueSource(SourceKind.OPENED, "opened", ""), now = 1).book
        val ui = playerUi(book, Repeat.OFF, place = null, Transport(), library = null, now = 0, opened = mapOf(path to tagged))!!
        val item = book.current?.current

        assertEquals("Paper Harbour", playingTitle(item, ui, library = null, LibrarySettings()))
        // Its tags not read, or its queue not the one the player has: its file's name.
        assertEquals("Paper Harbour (demo).mp3", playingTitle(item, ui.copy(opened = null), library = null, LibrarySettings()))
        assertEquals("Paper Harbour (demo).mp3", playingTitle(item, live = null, library = null, LibrarySettings()))
        assertEquals("", playingTitle(item = null, live = null, library = null, LibrarySettings()))
    }

    @Test
    fun `the Queue sheet's list opens with the song here in view, the song before it above`() {
        // Among the first songs, the list opens at its top, with the head above its songs.
        assertEquals(0, firstShown(index = 0, head = 1))
        assertEquals(0, firstShown(index = 1, head = 2))
        // Further in, the song before it is the first row shown, and the song here the second.
        assertEquals(1 + 11, firstShown(index = 12, head = 1))
        assertEquals(2 + 46, firstShown(index = 47, head = 2))
    }

    @Test
    fun `a queue's page opens at its song again, the song before it under the pinned buttons, or at its top without one`() {
        val songs = (1..30).map { SongRef("/storage/emulated/0/Music/Kite Weather/Song $it.mp3") }
        val source = QueueSource(SourceKind.SONGS, "tab:songs", "Songs")
        val book = QueueBook().play(songs, 11, source, now = 1).book
        val saved = book.current!!
        // Rows: the head (0), Resume and Play from start (1, pinned), then the songs from row 2.
        // A saved queue: the song it stopped at, 12th, with the 11th first under the buttons.
        assertEquals(2 + 10, queuePageOpening(saved, live = null))
        // The queue playing: the song playing now, wherever the stored place is.
        val ui = playerUi(book, Repeat.OFF, place = null, Transport(), library = null, now = 0)!!
        val on = ui.copy(queue = saved.copy(index = 20))
        assertEquals(2 + 19, queuePageOpening(saved, live = on))
        // Another queue playing says nothing of this one.
        assertEquals(2 + 10, queuePageOpening(saved, live = on.copy(queue = saved.copy(id = "other", index = 20))))
        // Among the first two songs: the top, the head showing above the buttons and the song.
        assertEquals(0, queuePageOpening(saved.copy(index = 0), live = null))
        assertEquals(0, queuePageOpening(saved.copy(index = 1), live = null))
        assertEquals(2 + 28, queuePageOpening(saved.copy(index = 29), live = null))
        // No song here (an empty queue, or a place past its end): the top.
        assertEquals(0, queuePageOpening(saved.copy(items = emptyList(), index = 0), live = null))
        assertEquals(0, queuePageOpening(saved.copy(index = 30), live = null))
    }

    @Test
    fun `a queue's page opened further in moves so the song before the song here shows right under the pinned buttons`() {
        // Opened with the song before at the list's top, under the 60 px buttons: back by all of them.
        assertEquals(60, queuePageLift(buttons = 60, before = 0, hereTop = 72, hereEnd = 144, end = 1_500))
        // Already partly clear of them: back by what they still cover; clear by 12 px: up by those 12.
        assertEquals(40, queuePageLift(buttons = 60, before = 20, hereTop = 92, hereEnd = 164, end = 1_500))
        assertEquals(-12, queuePageLift(buttons = 60, before = 72, hereTop = 144, hereEnd = 216, end = 1_500))
        // Near the queue's end the list stops short: never so far back that the song here goes past the list's end.
        assertEquals(0, queuePageLift(buttons = 60, before = 30, hereTop = 1_428, hereEnd = 1_500, end = 1_500))
        assertEquals(10, queuePageLift(buttons = 60, before = 0, hereTop = 1_418, hereEnd = 1_490, end = 1_500))
        // A short window (a phone on its side, a large font): the song here whole above the list's end, the song before
        // as far as it fits; too short even for the buttons and the song here: the song here right under the buttons.
        assertEquals(36, queuePageLift(buttons = 60, before = 0, hereTop = 72, hereEnd = 144, end = 180))
        assertEquals(-12, queuePageLift(buttons = 60, before = 0, hereTop = 72, hereEnd = 144, end = 100))
        assertEquals(-60, queuePageLift(buttons = 60, before = 0, hereTop = 120, hereEnd = 240, end = 150))
    }

    @Test
    fun `a queue's page folds its head into the top bar once it has gone, and lines its pinned buttons while songs run under them`() {
        // At its top (a queue at one of its first songs opens there), and while the head scrolls away: nothing folded.
        assertEquals(QueueHeadFold(folded = false, edge = false), queueHeadFold(firstIndex = 0, firstOffset = 0))
        assertEquals(QueueHeadFold(folded = false, edge = false), queueHeadFold(firstIndex = 0, firstOffset = 400))
        // The head gone, the pinned buttons at their own place: the top bar names the queue.
        assertEquals(QueueHeadFold(folded = true, edge = false), queueHeadFold(firstIndex = 1, firstOffset = 0))
        // Songs under the pinned buttons: their line shows.
        assertEquals(QueueHeadFold(folded = true, edge = true), queueHeadFold(firstIndex = 1, firstOffset = 1))
        assertEquals(QueueHeadFold(folded = true, edge = true), queueHeadFold(firstIndex = 9, firstOffset = 0))
    }

    @Test
    fun `the Queue sheet's other queues say what they are, so they never read as lyrics`() {
        assertEquals("Other queues", named(player, "queue_others"))
        val hint = checkNotNull(named(player, "queue_others_hint"))
        assertTrue(hint, "waiting to play" in hint)
        assertTrue(hint, hint.length <= 60)
        assertNull(named(player, "queue_recent"))
    }

    @Test
    fun `the Queues row counts every queue, and says nothing when there are none`() {
        assertNull(queuesCount(emptyList()))
        val playing = QueueTarget("q1", "Album · Harbour Glass · 21:30", 12, playing = true)
        val recent = QueueTarget("q2", "Songs · 20:05", 300, playing = false)
        assertEquals(1, queuesCount(listOf(playing)))
        assertEquals(2, queuesCount(listOf(playing, recent)))
        assertNotNull(named(library, "lib_queues_count"))
    }

    @Test
    fun `the queues have no Saved part, so no Saved title, count, tag or empty line, and no Keep`() {
        listOf("queues_saved", "queues_saved_empty", "queue_saved_tag", "queue_keep", "queue_keep_named", "queue_kept").forEach { assertNull(it, named(player, it)) }
        listOf("lib_saved", "lib_queue_saved").forEach { assertNull(it, named(library, it)) }
        // An empty part is left out rather than saying it is empty.
        assertNull(named(player, "queue_no_recent"))
    }

    @Test
    fun `the queue playing is named plainly, and so is having no queue at all`() {
        assertEquals("Playing now", named(player, "queues_playing"))
        assertEquals("%1\$d of %2\$d", named(player, "queue_position"))
        val none = checkNotNull(named(player, "queues_none"))
        assertTrue(none, none.split(' ').size <= 3)
        assertNotNull(named(player, "queues_none_text"))
        assertFalse("—" in player || "—" in library)
    }
}
