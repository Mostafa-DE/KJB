package app.lyrify.ui

import app.lyrify.player.QueueTarget
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
    fun `a queue's list opens with the song here in view, the song before it above`() {
        // Among the first songs, the list opens at its top, with the head above its songs.
        assertEquals(0, firstShown(index = 0, head = 1))
        assertEquals(0, firstShown(index = 1, head = 2))
        // Further in, the song before it is the first row shown, and the song here the second.
        assertEquals(1 + 11, firstShown(index = 12, head = 1))
        assertEquals(2 + 46, firstShown(index = 47, head = 2))
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
