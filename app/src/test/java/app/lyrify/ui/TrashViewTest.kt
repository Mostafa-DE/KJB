package app.lyrify.ui

import app.lyrify.R
import app.lyrify.dupes.AudioFile
import app.lyrify.dupes.Deleted
import app.lyrify.dupes.DuplicatesMemory
import app.lyrify.dupes.DuplicatesState
import app.lyrify.dupes.TrashedSong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Tools > Trash: which songs it lists, the room they take, its line in Tools and what each delete answer says. Made-up names only. */
class TrashViewTest {
    private fun file(id: Long, size: Long) = AudioFile(
        id = id, path = "/storage/emulated/0/Music/Harbour Glass/0$id Paper Lantern.mp3", relativePath = "Music/Harbour Glass/",
        displayName = "0$id Paper Lantern.mp3", title = "Paper Lantern", artist = "Kit Calloway", durationMs = 201_000, size = size,
    )

    private val own = TrashedSong(file(1, 3_000_000), trashedAt = 10, expiresAt = 1_000)
    private val older = TrashedSong(file(2, 4_000_000), trashedAt = 5, expiresAt = 900)
    private val other = TrashedSong(file(3, 5_000_000), trashedAt = 0, expiresAt = 950)

    private fun state(loaded: Boolean = true, trashed: List<TrashedSong> = listOf(older, own)) =
        DuplicatesState(loaded = loaded, memory = DuplicatesMemory(trashed = trashed))

    @Test
    fun `the trash lists KJB's songs and other apps' together, each once, the latest moved first, once both are read`() {
        assertNull(trashSongs(state(loaded = false), emptyList()))
        assertNull(trashSongs(state(), null))
        // KJB's own song as MediaStore lists it too is listed once, as KJB keeps it.
        assertEquals(listOf(own, other, older), trashSongs(state(), listOf(other, own.copy(trashedAt = 0))))
        assertEquals(listOf(other), trashSongs(state(trashed = emptyList()), listOf(other)))
        assertEquals(emptyList<TrashedSong>(), trashSongs(state(trashed = emptyList()), emptyList()))
    }

    @Test
    fun `Trash's line in Tools says nothing until the trash is read, then Empty, or its songs and the room they take`() {
        assertNull(trashLine(null))
        assertEquals(ToolLine.Text(R.string.tools_trash_none), trashLine(emptyList()))
        assertEquals(ToolLine.CountBytes(R.plurals.tools_trash, 3, 12_000_000), trashLine(listOf(own, older, other)))
        assertEquals(12_000_000L, trashBytes(listOf(own, older, other)))
        assertEquals(0L, trashBytes(emptyList()))
    }

    @Test
    fun `every answer of the phone's delete dialog says what happened, with the room it freed`() {
        assertEquals(TrashSaid.Text(R.string.trash_delete_cancelled), deleteSaid(approved = false, Deleted.Unchanged))
        assertEquals(TrashSaid.Freed(R.plurals.trash_deleted, 2, listOf(2), 8_000_000), deleteSaid(approved = true, Deleted.Gone(2, of = 2, bytes = 8_000_000)))
        assertEquals(TrashSaid.Freed(R.plurals.trash_deleted_some, 3, listOf(1, 3), 4_000_000), deleteSaid(approved = true, Deleted.Gone(1, of = 3, bytes = 4_000_000)))
        assertEquals(TrashSaid.Count(R.plurals.trash_deleted_none, 2), deleteSaid(approved = true, Deleted.Gone(0, of = 2, bytes = 0)))
        assertEquals(TrashSaid.Text(R.string.dupes_trash_unconfirmed), deleteSaid(approved = true, Deleted.NotConfirmed))
        // Nothing waited for this answer: there is nothing to say.
        assertNull(deleteSaid(approved = true, Deleted.Unchanged))
    }

    @Test
    fun `the delete messages take the tone that fits them, and the words point at Tools, Trash`() {
        listOf(R.plurals.trash_deleted, R.plurals.trash_deleted_some).forEach { assertEquals(Toast("a", R.drawable.ic_delete, Tone.NONE), messageToast(it, "a")) }
        listOf(R.plurals.trash_deleted_none, R.plurals.trash_delete_refused).forEach { assertEquals(Toast("a", R.drawable.ic_error, Tone.ERROR), messageToast(it, "a")) }
        assertEquals(Toast("a"), messageToast(R.string.trash_delete_cancelled, "a"))
        // Unit tests run in the module's folder.
        val trash = File("src/main/res/values/strings_trash.xml").readText()
        assertTrue("""<string name="trash_delete_text">This frees %1${'$'}s. You can\'t undo this.</string>""" in trash)
        assertTrue("doesn\\'t go back into its playlists or queues" in trash)
        val library = File("src/main/res/values/strings_library.xml").readText()
        assertTrue("its playlists and queues. You can restore them in Tools, Trash for about 30 days." in library)
        assertFalse("Tools, Duplicates" in library)
        assertFalse('\u2014' in trash)
    }
}
