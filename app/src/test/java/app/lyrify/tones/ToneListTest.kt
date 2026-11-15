package app.lyrify.tones

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ToneListTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @After
    fun tearDown() = scope.cancel()

    // The state

    @Test
    fun `tones are listed newest first`() {
        val state = TonesState().put(tone(1, createdAt = 10)).put(tone(2, createdAt = 30)).put(tone(3, createdAt = 20))
        assertEquals(listOf(2L, 3L, 1L), state.newestFirst().map { it.id })
    }

    @Test
    fun `putting a tone with the same id replaces it, removing drops it`() {
        val state = TonesState().put(tone(1)).put(tone(1, name = "Renamed"))
        assertEquals("Renamed", state.tones.getValue(1).name)
        assertTrue(state.remove(1).tones.isEmpty())
    }

    @Test
    fun `retaining keeps only the tones still on the phone`() {
        val state = TonesState().put(tone(1)).put(tone(2)).put(tone(3))
        assertEquals(setOf(1L, 3L), state.retain(setOf(1L, 3L, 99L)).tones.keys)
    }

    // The stored form

    @Test
    fun `a list reads back as it was written`() {
        val state = TonesState().put(tone(1, kind = ToneKind.ALARM)).put(tone(2, kind = ToneKind.NOTIFICATION))
        assertEquals(state, TonesJson.decode(TonesJson.encode(state)))
    }

    @Test
    fun `a list from this version loads unchanged`() {
        val stored = """
            {"schemaVersion":1,"tones":{"41":{"id":41,"name":"Paper Lanterns (Lyrify)","kind":"RINGTONE",
            "songPath":"/storage/emulated/0/Music/Paper Lanterns.mp3","startMs":72000,"lengthMs":30000,
            "fadeInMs":1500,"fadeOutMs":1500,"createdAt":1000,"fileName":"Paper Lanterns (Lyrify).m4a","size":48000}}}
        """.trimIndent()
        assertEquals(TonesState().put(tone(41)), TonesJson.decode(stored))
    }

    @Test
    fun `fields a newer version adds are ignored, fields added since take their defaults`() {
        val stored = """
            {"schemaVersion":3,"later":true,"tones":{"41":{"id":41,"name":"Paper Lanterns (Lyrify)","kind":"RINGTONE",
            "songPath":"/storage/emulated/0/Music/Paper Lanterns.mp3","startMs":72000,"lengthMs":30000,"sparkle":7}}}
        """.trimIndent()
        val read = TonesJson.decode(stored).tones.getValue(41)
        assertEquals(tone(41).copy(fadeInMs = 0, fadeOutMs = 0, createdAt = 0, fileName = "", size = 0), read)
    }

    @Test
    fun `an entry this version cannot read is kept as it was and written back, the others load`() {
        val stored = """
            {"schemaVersion":1,"tones":{
            "41":{"id":41,"name":"Paper Lanterns (Lyrify)","kind":"RINGTONE","songPath":"/storage/emulated/0/Music/Paper Lanterns.mp3",
            "startMs":72000,"lengthMs":30000,"fadeInMs":1500,"fadeOutMs":1500,"createdAt":1000},
            "42":{"id":42,"name":"Glass Harbor (Lyrify)","kind":"DOORBELL","songPath":"/x.mp3","startMs":0,"lengthMs":15000},
            "43":"not a tone"}}
        """.trimIndent()
        val state = TonesJson.decode(stored)
        assertEquals(setOf(41L), state.tones.keys)
        assertEquals(setOf("42", "43"), state.unknown.keys)

        val written = Json.parseToJsonElement(TonesJson.encode(state)).jsonObject.getValue("tones").jsonObject
        assertEquals(Json.parseToJsonElement(stored).jsonObject.getValue("tones").jsonObject["42"], written["42"])
        assertEquals(setOf("41", "42", "43"), written.keys)
    }

    @Test
    fun `an empty object is an empty list, text that is no list is an error`() {
        assertEquals(TonesState(), TonesJson.decode("{}"))
        listOf("[]", "\"x\"", """{"tones":[]}""").forEach { text ->
            try {
                TonesJson.decode(text)
                fail("expected SerializationException for $text")
            } catch (_: SerializationException) {
            }
        }
    }

    @Test
    fun `the stored form names its schema version`() {
        val root = Json.parseToJsonElement(TonesJson.encode(TonesState())) as JsonObject
        assertEquals("1", root.getValue("schemaVersion").toString())
    }

    // The DataStore file

    private val logs = mutableListOf<String>()

    /** The app's `files` folder, where a copy of a list that is not one at all goes. */
    private val backupDir: File by lazy { folder.newFolder("files") }

    private fun store(file: File, now: Long = NOW) = ToneListStore(tonesStore(file, backupDir, scope, clock = { now }) { synchronized(logs) { logs += it } }) { logs += it }

    @Test
    fun `the store keeps tones in its file`() = runBlocking {
        val file = folder.root.resolve("tones.json")
        val store = store(file)
        assertTrue(store.put(tone(41)))
        assertEquals(setOf(41L), store.state.first().tones.keys)
        assertTrue(file.readText().contains("\"41\""))
        assertTrue(store.remove(41))
        assertTrue(store.state.first().tones.isEmpty())
    }

    @Test
    fun `a file that is not a tones list is kept aside before the list starts over`() = runBlocking {
        val broken = """{"schemaVersion":1,"tones":{"41":{"id":41,"name":"Paper Lantern (KJB)","ki"""
        val file = folder.root.resolve("tones.json").apply { writeText(broken) }

        assertEquals(TonesState(), store(file).state.first())

        assertEquals(listOf("tones-corrupt-$NOW.json"), backupDir.list()?.toList())
        assertEquals(broken, File(backupDir, "tones-corrupt-$NOW.json").readText())
        // The log names the copy and its size, never what the list holds.
        assertTrue(logs.toString(), logs.any { "tones-corrupt-$NOW.json" in it && "${broken.length} bytes" in it })
        logs.forEach { assertTrue(it, !it.contains("Paper")) }
    }

    @Test
    fun `only the newest copy of an unreadable list is kept`() = runBlocking {
        val first = folder.root.resolve("first.json").apply { writeText("not json") }
        store(first, now = NOW).state.first()
        val second = folder.root.resolve("second.json").apply { writeText("not json either") }

        store(second, now = NOW + 1).state.first()

        assertEquals(listOf("tones-corrupt-${NOW + 1}.json"), backupDir.list()?.toList())
        assertEquals("not json either", File(backupDir, "tones-corrupt-${NOW + 1}.json").readText())
    }

    private companion object {
        const val NOW = 1_762_800_000_000L
    }
}
