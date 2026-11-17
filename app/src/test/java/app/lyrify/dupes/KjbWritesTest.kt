package app.lyrify.dupes

import app.lyrify.tags.AudioFormat
import app.lyrify.tags.EditKind
import app.lyrify.tags.HistoryEntry
import app.lyrify.tags.Payload
import app.lyrify.tags.SongEdit
import app.lyrify.tags.UndoMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A song listed at one size and bigger now: changed only by KJB's own tag writes when History's records of it lead, write
 * after write, from a file of the listed size to the bytes on disk. Made-up names and hashes only.
 */
class KjbWritesTest {
    private val listed = AudioFile(
        id = 41, path = "/storage/emulated/0/Music/a/Paper Lantern.mp3", relativePath = "Music/a/", displayName = "Paper Lantern.mp3",
        title = "Paper Lantern", artist = "Kit Calloway", durationMs = 200_000, size = 1_000,
    )

    /** One KJB write of the song: from [before] ([lengthBefore] bytes) to [after] ([lengthAfter] bytes). */
    private fun edit(before: String, after: String, lengthBefore: Long?, lengthAfter: Long?, id: Long = 41, path: String = listed.path) =
        SongEdit(id, path, Payload(AudioFormat.MP3, "audio"), before, after, emptyList(), lengthBefore = lengthBefore, lengthAfter = lengthAfter)

    private fun now(size: Long) = listed.copy(size = size)

    @Test
    fun `one KJB write since the listing, and the file is what it wrote`() {
        val edits = listOf(edit("s0", "s1", 1_000, 1_200))
        assertTrue(changedOnlyByKjb(listed, now(1_200), edits) { "s1" })
    }

    @Test
    fun `KJB writes one after another since the listing`() {
        val edits = listOf(edit("s0", "s1", 1_000, 1_200), edit("s1", "s2", 1_200, 1_350))
        assertTrue(changedOnlyByKjb(listed, now(1_350), edits) { "s2" })
        // Listed between the two writes: the second started from the listed size.
        assertTrue(changedOnlyByKjb(listed.copy(size = 1_200), now(1_350), edits) { "s2" })
    }

    @Test
    fun `a file another app changed after KJB's write is changed`() {
        val edits = listOf(edit("s0", "s1", 1_000, 1_200))
        assertFalse(changedOnlyByKjb(listed, now(1_200), edits) { "other" })
        assertFalse(changedOnlyByKjb(listed, now(1_300), edits) { "s1" })
    }

    @Test
    fun `a file another app changed before KJB's write is changed`() {
        // Another app made it 1,100 bytes; KJB then wrote its tags.
        assertFalse(changedOnlyByKjb(listed, now(1_200), listOf(edit("x", "s1", 1_100, 1_200))) { "s1" })
        // Another app wrote between KJB's two writes.
        val edits = listOf(edit("s0", "s1", 1_000, 1_200), edit("x", "s2", 1_150, 1_350))
        assertFalse(changedOnlyByKjb(listed, now(1_350), edits) { "s2" })
    }

    @Test
    fun `no record, a record without sizes, or a file that can't be read proves nothing`() {
        assertFalse(changedOnlyByKjb(listed, now(1_200), emptyList()) { "s1" })
        assertFalse(changedOnlyByKjb(listed, now(1_200), listOf(edit("s0", "s1", null, null))) { "s1" })
        assertFalse(changedOnlyByKjb(listed, now(1_200), listOf(edit("s0", "s1", 1_000, 1_200))) { null })
    }

    @Test
    fun `the file is read only when the latest write's size matches MediaStore's`() {
        var reads = 0
        assertFalse(changedOnlyByKjb(listed, now(1_300), listOf(edit("s0", "s1", 1_000, 1_200))) { reads++; "s1" })
        assertEquals(0, reads)
    }

    @Test
    fun `History's records of the song are its own, oldest first`() {
        val first = edit("s0", "s1", 1_000, 1_200)
        val second = edit("s1", "s2", 1_200, 1_350)
        val history = UndoMemory(
            listOf(
                HistoryEntry(7, EditKind.SONG, "b", 7, listOf(second, edit("t0", "t1", 1, 2, id = 42, path = "/other.mp3"))),
                HistoryEntry(3, EditKind.ALBUM, "a", 3, listOf(first)),
                HistoryEntry(5, EditKind.ALBUM, "c", 5, listOf(edit("u0", "u1", 1, 2, path = "/moved.mp3"))),
            ),
        )
        assertEquals(listOf(first, second), kjbEdits(history, listed))
    }
}
