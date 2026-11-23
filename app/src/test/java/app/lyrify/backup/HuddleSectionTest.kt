package app.lyrify.backup

import app.lyrify.backup.BackupFixtures.contents
import app.lyrify.backup.BackupFixtures.entries
import app.lyrify.backup.BackupFixtures.snapshot
import app.lyrify.backup.BackupFixtures.zip
import app.lyrify.backup.BackupFixtures.zipOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Huddle's part of the backup zip (plan Q3.3; user 2026-11-23, always in it): sealed with the user's passphrase on the
 * phone, or plain without one, it goes in as it is, beside the sections, and comes back only as what it says; a KJB
 * without Huddle never reads it.
 */
class HuddleSectionTest {
    private val sealed = """{"format":2,"kdf":"pbkdf2-sha256","rounds":1000,"salt":"c2FsdA","box":"Ym94"}"""
    private val plain = """{"format":2,"lines":"bGluZXM"}"""

    @Test
    fun `the sealed Huddle part goes into the zip as it is and comes back, the sections as before`() {
        val bytes = zip(snapshot.copy(huddle = sealed))
        assertEquals(sealed, entries(bytes).single { it.first == BackupBundle.HUDDLE }.second)
        // Version 3 may be plain (no passphrase set): a KJB that reads only version 2, which would ask for a passphrase, leaves it.
        assertEquals(JsonObject(mapOf("version" to JsonPrimitive(3))), manifest(bytes)["huddle"])
        assertEquals(sealed, contents(bytes).huddle)
        assertEquals(contents().sections, contents(bytes).sections)
    }

    @Test
    fun `the plain Huddle part goes into the zip as it is and comes back, under the same version`() {
        val bytes = zip(snapshot.copy(huddle = plain))
        assertEquals(plain, entries(bytes).single { it.first == BackupBundle.HUDDLE }.second)
        assertEquals(JsonObject(mapOf("version" to JsonPrimitive(3))), manifest(bytes)["huddle"])
        assertEquals(plain, contents(bytes).huddle)
    }

    @Test
    fun `a snapshot without Huddle's part has none in the zip, and a backup from before has none`() {
        val bytes = zip()
        assertFalse(entries(bytes).any { it.first == BackupBundle.HUDDLE })
        assertFalse(manifest(bytes).containsKey("huddle"))
        assertNull(contents(bytes).huddle)
    }

    @Test
    fun `a Huddle part of a newer form, one that is no JSON object, or one the manifest leaves out is skipped, and the rest reads`() {
        val bytes = zip(snapshot.copy(huddle = sealed))
        val newer = rewrite(bytes, change = { it + ("huddle" to JsonObject(mapOf("version" to JsonPrimitive(4)))) })
        assertNull(contents(newer).huddle)
        assertEquals(contents().sections, contents(newer).sections)
        assertNull(contents(rewrite(bytes, huddle = "[1,2]")).huddle)
        assertNull(contents(rewrite(bytes, change = { it - "huddle" })).huddle)
    }

    @Test
    fun `a Huddle part from before the history (version 1) or from before plain ones (version 2, KJB v4 test builds) still reads`() {
        listOf(1, 2).forEach { version ->
            val older = rewrite(zip(snapshot.copy(huddle = sealed)), change = { it + ("huddle" to JsonObject(mapOf("version" to JsonPrimitive(version)))) })
            assertEquals(sealed, contents(older).huddle)
        }
    }

    private fun manifest(bytes: ByteArray): JsonObject = Json.parseToJsonElement(entries(bytes).single { it.first == BackupBundle.MANIFEST }.second).jsonObject

    /** [bytes] with its manifest changed by [change] and its Huddle part replaced by [huddle]. */
    private fun rewrite(bytes: ByteArray, change: (JsonObject) -> Map<String, JsonElement> = { it }, huddle: String? = null): ByteArray = zipOf(
        entries(bytes).map { (name, text) ->
            name to when (name) {
                BackupBundle.MANIFEST -> JsonObject(change(manifest(bytes))).toString()
                BackupBundle.HUDDLE -> huddle ?: text
                else -> text
            }.encodeToByteArray()
        },
    )
}
