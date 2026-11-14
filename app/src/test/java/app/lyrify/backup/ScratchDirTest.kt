package app.lyrify.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The Musicolet reader's scratch folder (phone run 2026-11-15, session 9: a file that is no database left a file
 * behind there): empty after every read, whatever the read left or however it ended. Made-up names only.
 */
class ScratchDirTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `a read leaves nothing behind, whatever its files are called`() {
        val root = temp.newFolder("musicolet-import")

        ScratchDir(root).use { dir ->
            dir.resolve("songs.db").writeText("x")
            dir.resolve("songs.db-corrupt").writeText("x")
            dir.resolve("etilqs_1a2b3c").writeText("x")
            dir.resolve("nested").mkdirs()
            dir.resolve("nested/more.db-mj01").writeText("x")
        }

        assertEquals(emptyList<String>(), root.list().orEmpty().toList())
    }

    @Test
    fun `a read that throws leaves nothing behind either`() {
        val root = temp.newFolder("musicolet-import")

        val thrown = runCatching { ScratchDir(root).use<Unit> { dir -> dir.resolve("songs.db-unknown").writeText("x"); throw IllegalStateException("not a database") } }

        assertTrue(thrown.isFailure)
        assertEquals(emptyList<String>(), root.list().orEmpty().toList())
    }

    @Test
    fun `what an earlier read left when the app died is cleared before the next one, which gets a folder of its own`() {
        val root = temp.newFolder("musicolet-import")
        root.resolve("read-1").mkdirs()
        root.resolve("read-1/songs.db").writeText("x")
        root.resolve("songs-123.db-corrupt").writeText("x")

        val seen = ScratchDir(root).use { dir -> dir.list().orEmpty().toList() to (dir != root) }

        assertEquals(emptyList<String>() to true, seen)
        assertEquals(emptyList<String>(), root.list().orEmpty().toList())
    }
}
