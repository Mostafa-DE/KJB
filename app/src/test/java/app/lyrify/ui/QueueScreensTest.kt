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
    fun `a queue's page folds its head into the top bar once it has gone, and lines its pinned buttons while songs run under them`() {
        // At its top (the page opens there, 3.1), and while the head scrolls away: nothing folded.
        assertEquals(QueueHeadFold(folded = false, edge = false), queueHeadFold(firstIndex = 0, firstOffset = 0))
        assertEquals(QueueHeadFold(folded = false, edge = false), queueHeadFold(firstIndex = 0, firstOffset = 400))
        // The head gone, the pinned buttons at their own place: the top bar names the queue.
        assertEquals(QueueHeadFold(folded = true, edge = false), queueHeadFold(firstIndex = 1, firstOffset = 0))
        // Songs under the pinned buttons: their line shows.
        assertEquals(QueueHeadFold(folded = true, edge = true), queueHeadFold(firstIndex = 1, firstOffset = 1))
        assertEquals(QueueHeadFold(folded = true, edge = true), queueHeadFold(firstIndex = 9, firstOffset = 0))
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
