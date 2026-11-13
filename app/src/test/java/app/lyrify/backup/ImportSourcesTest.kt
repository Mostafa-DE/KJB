package app.lyrify.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where an import's files come from: a picked folder's playlist files, and the playlists other apps saved on the phone (fail-safe). Made-up names only. */
class ImportSourcesTest {
    // A picked folder

    @Test
    fun `a folder's playlist files are found two folders deep, each folder's files in name order before its folders'`() {
        val tree = mapOf(
            "root" to listOf(dir("music"), file("b.m3u8"), file("cover.jpg"), file("A.pls"), dir("empty")),
            "music" to listOf(file("01 Song.mp3"), dir("playlists"), file("x.xspf")),
            "playlists" to listOf(file("Evening.m3u"), dir("old")),
            "old" to listOf(file("Too deep.m3u")),
            "empty" to emptyList(),
        )

        assertEquals(listOf("A.pls", "b.m3u8", "x.xspf", "Evening.m3u"), ImportFolder.walk("root") { tree[it] }.map { it.name })
    }

    @Test
    fun `a folder that cannot be listed is skipped, and the listing stops at its cap`() {
        val wide = List(ImportFolder.MAX_LISTED + 50) { file("$it.m3u") }

        assertEquals(listOf("a.m3u"), ImportFolder.walk("root") { if (it == "root") listOf(dir("locked"), file("a.m3u")) else null }.map { it.name })
        assertTrue(ImportFolder.walk("root") { if (it == "root") wide else null }.size <= ImportFormats.MAX_FILES)
    }

    // Playlists other apps saved on the phone

    @Test
    fun `each playlist other apps saved comes with its songs in order, their paths and tags`() {
        val rows = FakeRows(
            listOf(1L to "Road Trip", 2L to null),
            mapOf(
                1L to listOf(PhoneMember(LANTERN, "Paper Lantern", "Kit Calloway", "Harbour Glass", 201_000, 4_100_000), PhoneMember(null, "Low Water", "Mira Solen", null, 240_000, null)),
                2L to listOf(PhoneMember(LANTERN, null, null, null, null, null)),
            ),
        )

        assertEquals(
            ImportBundle(
                listOf(
                    ImportPlaylist(
                        "Road Trip",
                        listOf(
                            ImportSong(LANTERN, LANTERN, "Paper Lantern", "Kit Calloway", "Harbour Glass", 201_000, 4_100_000),
                            ImportSong("", null, "Low Water", "Mira Solen", null, 240_000, null),
                        ),
                    ),
                    ImportPlaylist(null, listOf(ImportSong(LANTERN, LANTERN))),
                ),
            ),
            PhonePlaylists.bundle(rows),
        )
        assertEquals(2, PhonePlaylists.count(rows))
    }

    @Test
    fun `a read that throws leaves out that playlist, or all of them, and the log gets the exception's class only`() {
        val logged = mutableListOf<String>()
        val one = FakeRows(listOf(1L to "Good", 2L to "Bad"), mapOf(1L to listOf(PhoneMember(LANTERN, null, null, null, null, null))), failing = setOf(2L))
        val all = object : PhonePlaylistRows {
            override fun playlists(): List<Pair<Long, String?>> = throw SecurityException("no access to /storage/emulated/0/Music")

            override fun members(id: Long): List<PhoneMember> = emptyList()
        }

        assertEquals(listOf("Good"), PhonePlaylists.bundle(one, logged::add).playlists.map { it.name })
        assertEquals(ImportBundle(), PhonePlaylists.bundle(all, logged::add))
        assertEquals(0, PhonePlaylists.count(all, logged::add))
        assertTrue(logged.isNotEmpty())
        logged.forEach { assertTrue(it, "/storage" !in it && "Bad" !in it) }
    }

    @Test
    fun `phone playlists keep to the import caps`() {
        val many = FakeRows(List(3) { it.toLong() to "P$it" }, List(3) { it.toLong() to List(40_000) { n -> PhoneMember("/storage/emulated/0/Music/$n.mp3", null, null, null, null, null) } }.toMap())

        assertTrue(PhonePlaylists.bundle(many).size <= ImportFormats.MAX_TOTAL_ENTRIES)
        assertTrue(PhonePlaylists.bundle(many).playlists.all { it.songs.size <= ImportFormats.MAX_ENTRIES })
    }

    private class FakeRows(val lists: List<Pair<Long, String?>>, val songs: Map<Long, List<PhoneMember>>, val failing: Set<Long> = emptySet()) : PhonePlaylistRows {
        override fun playlists(): List<Pair<Long, String?>> = lists

        override fun members(id: Long): List<PhoneMember> = if (id in failing) throw IllegalStateException("cursor closed") else songs[id].orEmpty()
    }

    private fun dir(name: String) = FolderChild(name, name, folder = true)

    private fun file(name: String) = FolderChild(name, name, folder = false, sizeBytes = 100)

    private companion object {
        const val LANTERN: String = "/storage/emulated/0/Music/Harbour Glass/01 Paper Lantern.mp3"
    }
}
