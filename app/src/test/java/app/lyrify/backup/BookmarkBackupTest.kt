package app.lyrify.backup

import app.lyrify.backup.BackupFixtures.T0
import app.lyrify.backup.BackupFixtures.contents
import app.lyrify.backup.BackupFixtures.entries
import app.lyrify.backup.BackupFixtures.lantern
import app.lyrify.backup.BackupFixtures.water
import app.lyrify.backup.BackupFixtures.zip
import app.lyrify.backup.SectionJson.long
import app.lyrify.backup.SectionJson.text
import app.lyrify.player.Bookmark
import app.lyrify.player.SongState
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Bookmarks in backups, both ways (BM1): a new backup carries each bookmark's note and when it was made in the bookmarks
 * section 3.1.1 already reads, at the same version, so 3.1.1 restores it as before; and a 3.1.1 backup's bookmarks come
 * back here, made at a time not known. Made-up names only.
 */
class BookmarkBackupTest {
    /** Two bookmarks made since KJB keeps the time, and one from before whose note was changed later. */
    private val made = SongState(
        bookmarks = mapOf(
            lantern.path to listOf(Bookmark(1, 30_000, "Chorus", addedAt = T0 + 5_000), Bookmark(2, 95_000, "", addedAt = T0 + 9_000)),
            water.path to listOf(Bookmark(3, 12_000, "Changed later")),
        ),
        nextBookmarkId = 4,
        ids = mapOf(lantern.path to 11L, water.path to 13L),
    )

    private fun backup(): ByteArray = zip(BackupFixtures.snapshot.copy(songState = made))

    private fun section(bytes: ByteArray, section: Section): String = entries(bytes).first { it.first == section.file }.second

    // A new backup here

    @Test
    fun `each bookmark comes back with its note and when it was made`() {
        val stores = MemoryStores()
        runBlocking { RestorePlan(contents(backup()), BackupFixtures.library).apply(setOf(Section.BOOKMARKS), stores, newId = { "new" }) }

        assertEquals(listOf(Triple(30_000L, "Chorus", T0 + 5_000), Triple(95_000L, "", T0 + 9_000)), stores.songState.bookmarksOf(lantern.path).map { Triple(it.positionMs, it.note, it.addedAt) })
        assertEquals(listOf(Triple(12_000L, "Changed later", 0L)), stores.songState.bookmarksOf(water.path).map { Triple(it.positionMs, it.note, it.addedAt) })
    }

    @Test
    fun `a bookmark whose note changed since the backup comes back once, with the note the phone has now`() {
        // The chorus's note changed after the backup; another note at the backup's spot of a time not known is another bookmark.
        val phone = made.withNote(lantern.path, 1, "Chorus, the second time")
            .let { it.copy(bookmarks = it.bookmarks + (water.path to listOf(Bookmark(3, 12_000, "Other", addedAt = T0 + 1)))) }
        val stores = MemoryStores(songState = phone)
        runBlocking { RestorePlan(contents(backup()), BackupFixtures.library).apply(setOf(Section.BOOKMARKS), stores, newId = { "new" }) }

        assertEquals(listOf(30_000L to "Chorus, the second time", 95_000L to ""), stores.songState.bookmarksOf(lantern.path).map { it.positionMs to it.note })
        assertEquals(listOf("Other", "Changed later"), stores.songState.bookmarksOf(water.path).map { it.note })
    }

    @Test
    fun `the bookmarks section keeps its version, so 3_1_1 reads it`() {
        val manifest = Json.parseToJsonElement(entries(backup()).first { it.first == BackupBundle.MANIFEST }.second).jsonObject
        assertEquals(1, manifest["sections"]!!.jsonObject["bookmarks"]!!.jsonObject["version"]!!.jsonPrimitive.int)
        assertEquals(1, Section.BOOKMARKS.version)
    }

    // A new backup in 3.1.1

    @Test
    fun `3_1_1 reads a new backup's bookmarks as it always did, every spot and note`() {
        assertEquals(
            listOf(lantern.path to listOf(30_000L to "Chorus", 95_000L to ""), water.path to listOf(12_000L to "Changed later")),
            marks311(section(backup(), Section.BOOKMARKS)),
        )
    }

    // A 3.1.1 backup here

    @Test
    fun `a 3_1_1 backup's bookmarks come back with their notes, made at a time not known, and a time that cannot be read is not known`() {
        val old = """{"songs":[{"path":"${lantern.path}","bookmarks":[{"positionMs":30000,"note":"Chorus"},""" +
            """{"positionMs":40000,"note":"Soon","addedAt":"soon"},{"positionMs":50000,"addedAt":-4}]}]}"""
        assertEquals(
            listOf(BackupBookmark(30_000, "Chorus"), BackupBookmark(40_000, "Soon"), BackupBookmark(50_000, "")),
            SectionJson.marks(old)!!.marks.single().bookmarks,
        )
    }

    private companion object {
        /** 3.1.1's reading of the bookmarks section (backup/BackupSections.kt), each song's spots and notes, word for word. */
        fun marks311(text: String): List<Pair<String, List<Pair<Long, String>>>>? {
            val root = SectionJson.jsonObject(text) ?: return null
            return (root["songs"] as? JsonArray ?: return null).mapNotNull { element ->
                val song = element as? JsonObject ?: return@mapNotNull null
                val bookmarks = (song["bookmarks"] as? JsonArray).orEmpty().mapNotNull { stored ->
                    val mark = stored as? JsonObject ?: return@mapNotNull null
                    (mark.long("positionMs")?.takeIf { it >= 0 } ?: return@mapNotNull null) to mark.text("note").orEmpty()
                }
                (song.text("path")?.takeIf(String::isNotEmpty) ?: return@mapNotNull null) to bookmarks
            }
        }
    }
}
