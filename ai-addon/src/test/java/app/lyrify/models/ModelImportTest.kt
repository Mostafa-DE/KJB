package app.lyrify.models

import app.lyrify.ai.AiModels
import app.lyrify.ai.api.ImportReply
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/** The add-on's side of the handover: each file KJB sends is checked before it is in place, and the folder only by its manifest. */
class ModelImportTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val mdx = ByteArray(300) { 1 }
    private val whisperFiles = AiModels.WHISPER_FILES.mapIndexed { i, name -> name to ByteArray(100 + i) { (3 + i).toByte() } }.toMap()
    private val archiveSha = "ab".repeat(32)

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private val store by lazy {
        val specs = listOf(
            ModelSpec(AiModels.WHISPER, "https://github.com/x", archiveSha, 999, ModelSpec.Unpack("w", AiModels.WHISPER_FILES, whisperFiles.values.sumOf { it.size.toLong() })),
            ModelSpec(AiModels.MDX, "https://github.com/x", sha(mdx), mdx.size.toLong()),
        )
        ModelStore(folder.newFolder("models"), specs, folder.newFolder("cache"))
    }
    private val whisperSpec by lazy { store.specs.first() }
    private val mdxSpec by lazy { store.specs.last() }
    private val import by lazy { ModelImport(store, freeBytes = { Long.MAX_VALUE }) }

    private fun send(name: String, bytes: ByteArray, sha: String = sha(bytes)) = import.import(name, ByteArrayInputStream(bytes), bytes.size.toLong(), sha)

    /** KJB 2.0's manifest for the Whisper folder: the pinned archive and each file's size and SHA-256. */
    private fun manifest(archive: String = archiveSha): ByteArray = Json.encodeToString(
        ModelStore.Manifest.serializer(),
        ModelStore.Manifest(archive, whisperFiles.mapValues { (_, bytes) -> ModelStore.Entry(bytes.size.toLong(), sha(bytes)) }),
    ).toByteArray()

    private val imported = ImportReply(ImportReply.Outcome.IMPORTED)

    /** A stream that fails the test if it is read: the add-on already holds the file. */
    private val unread = object : InputStream() {
        override fun read(): Int = error("read a file the add-on already holds")
    }

    @Test
    fun `a single model checked against its pin is in place, and asked again it is not read again`() {
        assertEquals(imported, send(AiModels.MDX, mdx))
        assertTrue(store.has(mdxSpec))

        assertEquals(imported, import.import(AiModels.MDX, unread, mdx.size.toLong(), sha(mdx)))
    }

    @Test
    fun `a single model that is not the pinned one is refused before a byte is written`() {
        val other = ByteArray(300) { 9 }

        assertEquals(ImportReply.Outcome.MISMATCH, send(AiModels.MDX, other).outcome)
        assertFalse(store.has(mdxSpec))
        assertFalse(store.part(mdxSpec).exists())
    }

    @Test
    fun `bytes that do not match the SHA-256 they came with are deleted, not put in place`() {
        val damaged = mdx.copyOf().also { it[10] = 7 }

        assertEquals(ImportReply.Outcome.MISMATCH, send(AiModels.MDX, damaged, sha = sha(mdx)).outcome)
        assertFalse(store.has(mdxSpec))
        assertFalse(store.part(mdxSpec).exists())
    }

    @Test
    fun `Whisper's files then its manifest put the folder in place, and the journal goes`() {
        whisperFiles.forEach { (name, bytes) -> assertEquals(imported, send("${AiModels.WHISPER}/$name", bytes)) }
        assertFalse("not in place before its manifest", store.has(whisperSpec))

        assertEquals(imported, send("${AiModels.WHISPER}/${ModelStore.MANIFEST}", manifest()))

        assertTrue(store.has(whisperSpec))
        assertEquals(setOf(ModelStore.MANIFEST) + AiModels.WHISPER_FILES, store.file(whisperSpec).list()!!.toSet())
        // In place now: what KJB sends again is taken as is.
        assertEquals(imported, import.import("${AiModels.WHISPER}/${AiModels.ENCODER}", unread, 1, "00"))
    }

    @Test
    fun `a Whisper folder as KJB 2_0_2 left it goes in whole, with the manifest 2_0_2 wrote`() {
        // What 2.0.2's own downloader wrote for a made-up archive (app: CarryOverTest, carry-over-2.0.2/models), byte for byte.
        val files = AiModels.WHISPER_FILES.mapIndexed { i, name -> name to ByteArray(100 + i) { ((it * 31 + 3 + i) % 251).toByte() } }.toMap()
        val manifest = (
            """{"archiveSha256":"2fa16a9d993b33da3ea47ab847a52c9702dc6d4d433de0a985e57defb6b206fb","files":{""" +
                """"encoder.onnx":{"bytes":100,"sha256":"7e335cb0ca4ba0bf749efe0e46e142bb6c8f710eea7fbf806ae9150bf98a19b7"},""" +
                """"encoder.bin":{"bytes":101,"sha256":"8f0bb883c871e48183c0e92e11dedf298b23b4ed6c90e02de2652ae6acc45da9"},""" +
                """"decoder.onnx":{"bytes":102,"sha256":"61334c2c1d9bcad4fd83c55a47df1dc6b229505397eda2ed8a542a933e923332"},""" +
                """"decoder.bin":{"bytes":103,"sha256":"505b488002db5b71682d3485f94dda3f46fd8fda0537bd1f71c86f732778a8ae"},""" +
                """"metadata.json":{"bytes":104,"sha256":"245a07efa256cc6c2803cf4500682bea15b484d323d9e1146af5301a9a556b56"}}}"""
            ).toByteArray()
        val spec = ModelSpec(
            AiModels.WHISPER, "https://github.com/x", "2fa16a9d993b33da3ea47ab847a52c9702dc6d4d433de0a985e57defb6b206fb", 999,
            ModelSpec.Unpack("whisper-pack", AiModels.WHISPER_FILES, files.values.sumOf { it.size.toLong() }),
        )
        val store = ModelStore(folder.newFolder("models-202"), listOf(spec), folder.newFolder("cache-202"))
        val import = ModelImport(store, freeBytes = { Long.MAX_VALUE })
        fun send(name: String, bytes: ByteArray) = import.import("${AiModels.WHISPER}/$name", ByteArrayInputStream(bytes), bytes.size.toLong(), sha(bytes))

        files.forEach { (name, bytes) -> assertEquals(imported, send(name, bytes)) }
        assertEquals(imported, send(ModelStore.MANIFEST, manifest))

        assertTrue(store.has(spec))
    }

    @Test
    fun `a Whisper file sent again after a kill is not read again`() {
        val (name, bytes) = whisperFiles.entries.first()
        assertEquals(imported, send("${AiModels.WHISPER}/$name", bytes))

        assertEquals(imported, import.import("${AiModels.WHISPER}/$name", unread, bytes.size.toLong(), sha(bytes)))
    }

    @Test
    fun `a manifest before all its files asks KJB to send them, and the folder stays out of place`() {
        val (name, bytes) = whisperFiles.entries.first()
        send("${AiModels.WHISPER}/$name", bytes)

        assertEquals(ImportReply.Outcome.RETRY, send("${AiModels.WHISPER}/${ModelStore.MANIFEST}", manifest()).outcome)
        assertFalse(store.has(whisperSpec))
    }

    @Test
    fun `a manifest of another archive than the pinned one is refused`() {
        whisperFiles.forEach { (name, bytes) -> send("${AiModels.WHISPER}/$name", bytes) }

        assertEquals(ImportReply.Outcome.MISMATCH, send("${AiModels.WHISPER}/${ModelStore.MANIFEST}", manifest(archive = "cd".repeat(32))).outcome)
        assertFalse(store.has(whisperSpec))
    }

    @Test
    fun `names that are not model files, or that leave the models folder, are refused`() {
        for (name in listOf("notes.txt", "../${AiModels.MDX}", "${AiModels.WHISPER}/../${AiModels.MDX}", "${AiModels.WHISPER}/other.bin", "/${AiModels.MDX}")) {
            assertEquals(name, ImportReply.Outcome.REFUSED, send(name, mdx).outcome)
        }
        assertFalse(File(store.folder.parentFile, AiModels.MDX).exists())
    }

    @Test
    fun `a copy cut short leaves nothing in place, and sent again it goes in`() {
        val cut = object : InputStream() {
            private var left = 100
            override fun read(): Int = if (left-- > 0) 1 else throw IOException("KJB went away")
        }

        assertEquals(ImportReply.Outcome.RETRY, import.import(AiModels.MDX, cut, mdx.size.toLong(), sha(mdx)).outcome)
        assertFalse(store.has(mdxSpec))

        assertEquals(imported, send(AiModels.MDX, mdx))
    }

    @Test
    fun `without room for a file it asks KJB to try again later`() {
        val full = ModelImport(store, freeBytes = { 10 })

        assertEquals(ImportReply.Outcome.RETRY, full.import(AiModels.MDX, ByteArrayInputStream(mdx), mdx.size.toLong(), sha(mdx)).outcome)
    }
}
