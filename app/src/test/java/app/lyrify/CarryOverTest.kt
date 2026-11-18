package app.lyrify

import app.lyrify.addon.Handover
import app.lyrify.addon.HandoverEnd
import app.lyrify.ai.api.ImportReply
import app.lyrify.data.LedgerJson
import app.lyrify.data.LedgerState
import app.lyrify.data.LyricsStatus
import app.lyrify.data.ledgerStore
import app.lyrify.dupes.DuplicateGroup
import app.lyrify.dupes.DuplicatesJson
import app.lyrify.dupes.DuplicatesMemory
import app.lyrify.dupes.DuplicatesStore
import app.lyrify.overnight.AiLyrics
import app.lyrify.overnight.AiRunLog
import app.lyrify.overnight.AiWorker
import app.lyrify.overnight.CrashGuard
import app.lyrify.overnight.GuardVerdict
import app.lyrify.overnight.ModelLoadFailures
import app.lyrify.tones.MediaRow
import app.lyrify.tones.ToneKind
import app.lyrify.tones.ToneListStore
import app.lyrify.tones.TonesJson
import app.lyrify.tones.TonesState
import app.lyrify.tones.isSavedTone
import app.lyrify.tones.tonesStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.time.ZoneOffset

/**
 * Lyrify 2.0.2 (versionCode 9) updated to KJB 3.0 in place: what 2.0.2 kept in its app-private storage reads in 3.0 with
 * nothing lost. The files in `carry-over-2.0.2/` were written by 2.0.2's own code (6a1d929: LedgerJson, TonesJson,
 * DuplicatesJson, CrashGuard, AiRunLog, ModelLoadFailures, and ModelDownloader for the models folder, from a loopback
 * server) with made-up songs, so they are 2.0.2's format byte for byte; make them again only from 2.0.2. 2.0.2 kept no
 * settings (its Settings screen showed the phone's grants only), so 3.0's settings start from their defaults
 * (SettingsStoreTest) and a phone with both grants skips setup (AccessTest).
 */
class CarryOverTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val logs = mutableListOf<String>()

    /** The app's `files` folder, as 2.0.2 left it. */
    private val files: File by lazy { folder.newFolder("files") }

    @After
    fun stop() = scope.cancel()

    private fun fixture(name: String): String = fixtureBytes(name).decodeToString()

    private fun fixtureBytes(name: String): ByteArray = checkNotNull(javaClass.getResource("/carry-over-2.0.2/$name")) { name }.readBytes()

    private fun placed(path: String, text: String): File = File(files, path).apply {
        parentFile?.mkdirs()
        writeText(text)
    }

    private fun log(line: String) {
        synchronized(logs) { logs += line }
    }

    private fun entries(ledger: String): JsonObject = Json.parseToJsonElement(ledger).jsonObject.getValue("entries").jsonObject

    // The update itself

    @Test
    fun `3_0 installs over 2_0_2 as an update, with a higher version code`() {
        assertTrue(BuildConfig.VERSION_CODE > VERSION_202)
        assertEquals("app.lyrify", BuildConfig.APPLICATION_ID.removeSuffix(".debug"))
    }

    // The ledger (files/datastore/ledger.json)

    @Test
    fun `the ledger 2_0_2 wrote loads whole through the store, every field as written, and none of it the user's own lyrics`() = runBlocking {
        val text = fixture("ledger.json")
        val file = placed("datastore/ledger.json", text)

        val state = ledgerStore(file, files, scope, clock = { NOW }, log = ::log).data.first()

        val stored = entries(text)
        assertEquals(stored.keys, state.entries.keys)
        assertTrue(state.unknownEntries.isEmpty())
        assertEquals("every status 2.0.2 has", LyricsStatus.entries.toSet() - LyricsStatus.EXISTING_PLAIN, state.entries.values.map { it.status }.toSet())
        // Each entry as 2.0.2 wrote it, plus 3.0's two fields at their defaults (not the user's own lyrics, no whole write)
        // and 3.1's two (no plain lyrics in the file's tags, nothing to time anew).
        val now = entries(LedgerJson.encode(state))
        val added = mapOf("mine" to JsonPrimitive(false), "whole" to JsonNull, "plainInTags" to JsonPrimitive(false), "retime" to JsonPrimitive(false))
        for ((path, entry) in stored) {
            assertEquals(path, JsonObject(entry.jsonObject + added), now.getValue(path))
        }
        assertTrue(state.entries.values.none { it.mine })
        assertEquals(emptyList<String>(), logs)
    }

    @Test
    fun `a 2_0_2 ledger of 1,300 songs loads with none left out, and a 3_0 write keeps every entry`() = runBlocking {
        val root = Json.parseToJsonElement(fixture("ledger.json")).jsonObject
        val kinds = entries(fixture("ledger.json")).values.map { it.jsonObject }
        val songs = (0 until 1_300).associate { i ->
            val path = "/storage/emulated/0/Music/Shelf ${i / 100}/%04d Song.mp3".format(i)
            path to JsonObject(kinds[i % kinds.size] + ("path" to JsonPrimitive(path)))
        }
        val file = placed("datastore/ledger.json", JsonObject(root + ("entries" to JsonObject(songs))).toString())
        val store = ledgerStore(file, files, scope, clock = { NOW }, log = ::log)

        val state = store.data.first()
        assertEquals(songs.keys, state.entries.keys)
        assertTrue(state.unknownEntries.isEmpty())

        val first = state.entries.values.first()
        store.updateData { it.put(first.copy(mine = true)) }
        assertEquals(state.entries + (first.path to first.copy(mine = true)), LedgerJson.decode(file.readText()).entries)
    }

    // Your tones (files/datastore/tones.json)

    @Test
    fun `the tones 2_0_2 saved load whole through the store, and are known by their files`() = runBlocking {
        val text = fixture("tones.json")
        val file = placed("datastore/tones.json", text)

        val state = ToneListStore(tonesStore(file, files, scope, clock = { NOW }, log = ::log), ::log).state.first()

        assertEquals(setOf(5001L, 5002L, 5003L, 4001L), state.tones.keys)
        assertTrue(state.unknown.isEmpty())
        assertEquals(ToneKind.entries.toSet(), state.tones.values.map { it.kind }.toSet())
        val stored = Json.parseToJsonElement(text).jsonObject.getValue("tones")
        assertEquals(stored, Json.parseToJsonElement(TonesJson.encode(state)).jsonObject.getValue("tones"))
        // A tone is the app's own by the file it was saved as, "(Lyrify)" in its name or not (ToneFilesTest has the rest).
        val ringtone = state.tones.getValue(5001)
        assertTrue(isSavedTone(ringtone, MediaRow(ringtone.name, ringtone.fileName, ringtone.size, "Ringtones/")))
        // Saved before 2.0 kept the file's name and size: listed, never changed.
        assertFalse(isSavedTone(state.tones.getValue(4001), MediaRow("Salt Letters (Lyrify)", "Salt Letters (Lyrify).m4a", 1, "Ringtones/")))
        assertEquals(emptyList<String>(), logs)
    }

    // Duplicates (files/duplicates.json, version 1)

    @Test
    fun `the duplicates 2_0_2 kept keep the user's choices on the same songs and the phone's trash, only the scan is found again`() {
        val text = fixture("duplicates.json")
        val store = DuplicatesStore(placed("duplicates.json", text), clock = { NOW }, log = ::log)

        val loaded = store.load()

        // DuplicatesStoreTest covers the version 1 mapping on its own (T2); this is 2.0.2's file as written.
        assertNull(loaded.results)
        assertEquals(setOf("13,14", "21,22,23"), loaded.notDuplicates)
        assertEquals(setOf("11,12", "15,16"), loaded.reviewed)
        // The groups 2.0.2 showed have these keys in 3.0: a scan that finds them again finds the choices on them.
        val stored = Json.parseToJsonElement(text).jsonObject
        val groups = Json.decodeFromJsonElement(ListSerializer(DuplicateGroup.serializer()), stored.getValue("results").jsonObject.getValue("groups"))
        assertEquals(setOf("11,12", "13,14", "15,16"), groups.map { it.key }.toSet())
        // Songs in the phone's trash, restorable from Duplicates, and one whose trash dialog was open: every field kept.
        val now = Json.parseToJsonElement(DuplicatesJson.encode(loaded)).jsonObject
        assertEquals(stored.getValue("trashed"), now.getValue("trashed"))
        assertEquals(stored.getValue("pendingTrash"), now.getValue("pendingTrash"))

        assertTrue(store.save(loaded))
        assertEquals(loaded, store.load())
        assertEquals(emptyList<String>(), logs)
    }

    // The AI: its switch and preferences (shared_prefs/ai_lyrics.xml), crash guard, run log and models

    @Test
    fun `the AI switch keeps 2_0_2's preferences and schedule, and 2_0_2's model load failures do not hold 3_0`() {
        // ai_lyrics.xml and its keys, and the periodic work "ai-lyrics" of this worker class, as 2.0.2 named them.
        assertEquals("ai_lyrics", AiLyrics.PREFERENCES)
        assertEquals("enabled", AiLyrics.ENABLED)
        assertEquals("model_load_failures", AiLyrics.LOAD_FAILURES)
        assertEquals("ai-lyrics", AiLyrics.WORK)
        assertEquals("app.lyrify.overnight.AiWorker", AiWorker::class.java.name)

        val stored = fixture("ai_lyrics-model_load_failures.json")
        fun failures(version: Int) = ModelLoadFailures(version, pinned = { SHA }, read = { stored }, write = {})
        assertTrue("2.0.2 had paused the AI on a model that did not load", failures(VERSION_202).paused())
        assertFalse(failures(BuildConfig.VERSION_CODE).paused())
    }

    @Test
    fun `the crash guard 2_0_2 kept is read, its songs kept, and its deaths and stopped stages start over in 3_0`() {
        val file = placed("ai-crash-guard.json", fixture("ai-crash-guard.json"))
        assertEquals("as 2.0.2 reads it: timing lyrics stopped", GuardVerdict.NO_ALIGN, CrashGuard(file, { NOW }, VERSION_202).verdict())

        val guard = CrashGuard(file, { NOW }, BuildConfig.VERSION_CODE, ::log)

        assertEquals(4, guard.paths().size)
        assertEquals(GuardVerdict.NONE, guard.verdict())
        // The run the update stopped is no death.
        assertTrue(guard.collectDeaths().isEmpty())
        assertEquals(emptyList<String>(), logs)
    }

    @Test
    fun `the AI run log 2_0_2 kept goes on, its lines first`() {
        val old = fixture("ai-run.log").lines().filter(String::isNotEmpty)
        val log = AiRunLog(placed("ai-run.log", fixture("ai-run.log")), { NOW }, ZoneOffset.UTC)

        log.append("AI: the add-on is bound")

        assertEquals(old + "11-10 18:40:00 AI: the add-on is bound", log.tail(10))
    }

    @Test
    fun `the models 2_0_2 downloaded are found where it left them and go to the add-on, each with the SHA-256 its manifest has`() = runTest {
        val models = File(files, "models")
        MODEL_FILES.forEach { name -> File(models, name).apply { parentFile?.mkdirs() }.writeBytes(fixtureBytes("models/$name")) }
        val sent = linkedMapOf<String, String>()
        var done = 0
        val handover = Handover(
            models,
            send = { file, sha ->
                sent[file.name] = sha
                ImportReply(ImportReply.Outcome.IMPORTED)
            },
            sha256 = ::sha256,
            markDone = { done++ },
        )

        // The single models first, then Whisper's files, its manifest last (which puts the add-on's folder in place).
        assertEquals(MODEL_FILES, handover.files().map { it.name })
        assertEquals(HandoverEnd.Done, handover.run())

        assertEquals(MODEL_FILES, sent.keys.toList())
        MODEL_FILES.forEach { name -> assertEquals(name, sha256(fixtureBytes("models/$name")), sent.getValue(name)) }
        // The add-on takes Whisper's folder only when what KJB sent is what 2.0.2's manifest recorded (ModelImportTest).
        val manifest = Json.parseToJsonElement(fixture("models/whisper-npu/manifest.json")).jsonObject.getValue("files").jsonObject
        assertEquals(5, manifest.size)
        manifest.forEach { (name, entry) -> assertEquals(name, entry.jsonObject.getValue("sha256").jsonPrimitive.content, sent.getValue("whisper-npu/$name")) }
        // Only once the add-on took them all do KJB's copies go. The AI page meanwhile asks to get the add-on (AddonGlanceTest).
        assertEquals(1, done)
        assertFalse(models.exists())
    }

    // What 3.0 cannot read

    @Test
    fun `a 2_0_2 store 3_0 cannot read is kept aside as it was, never written over`() = runBlocking {
        val ledger = fixture("ledger.json").take(5_000)
        val tones = fixture("tones.json").take(400)
        val duplicates = fixture("duplicates.json").take(900)

        assertEquals(LedgerState(), ledgerStore(placed("datastore/ledger.json", ledger), files, scope, clock = { NOW }, log = ::log).data.first())
        assertEquals(TonesState(), ToneListStore(tonesStore(placed("datastore/tones.json", tones), files, scope, clock = { NOW }, log = ::log), ::log).state.first())
        assertEquals(DuplicatesMemory(), DuplicatesStore(placed("duplicates.json", duplicates), clock = { NOW }, log = ::log).load())

        val kept = mapOf("ledger" to ledger, "tones" to tones, "duplicates" to duplicates).filter { (store, text) ->
            File(files, "$store-corrupt-$NOW.json").takeIf { it.isFile }?.readText() == text
        }
        assertEquals(setOf("ledger", "tones", "duplicates"), kept.keys)
        // The logs name copies and sizes, never a song.
        assertFalse(logs.toString(), logs.any { "Paper Comet" in it || "/storage/" in it })
    }

    private fun sha256(file: File): String = sha256(file.readBytes())

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val VERSION_202 = 9
        const val NOW = 1_762_800_000_000L
        const val SHA = "3f7a000000000000000000000000000000000000000000000000000000009c1e"

        /** The models folder as 2.0.2 left it, by 2.0.2's names, in the order the handover sends them. */
        val MODEL_FILES = listOf(
            "UVR-MDX-NET-Voc_FT.onnx",
            "wav2vec2-base-960h_q4.onnx",
            "whisper-npu/encoder.onnx",
            "whisper-npu/encoder.bin",
            "whisper-npu/decoder.onnx",
            "whisper-npu/decoder.bin",
            "whisper-npu/metadata.json",
            "whisper-npu/manifest.json",
        )
    }
}
