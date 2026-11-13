package app.lyrify.addon

import app.lyrify.ai.AiModels
import app.lyrify.ai.api.ImportReply
import app.lyrify.models.ModelStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/** KJB 2.0's models handed over to the add-on: never a file deleted before the add-on confirmed every one. */
class ModelHandoverTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val models by lazy { folder.newFolder("models") }
    private val whisper by lazy { File(models, AiModels.WHISPER) }

    /** KJB 2.0's folder: the two single models and AI Hub's Whisper with its manifest, made-up bytes. */
    private fun keptByKjb(whisperComplete: Boolean = true) {
        File(models, AiModels.MDX).writeBytes(ByteArray(300) { 1 })
        File(models, AiModels.WAV2VEC2).writeBytes(ByteArray(200) { 2 })
        whisper.mkdirs()
        AiModels.WHISPER_FILES.forEachIndexed { i, name -> File(whisper, name).writeBytes(ByteArray(100 + i) { (3 + i).toByte() }) }
        if (whisperComplete) File(whisper, ModelStore.MANIFEST).writeText("""{"archiveSha256":"ab","files":{}}""")
        File(models, AiModels.MDX + ModelStore.PART).writeBytes(ByteArray(5)) // a part never moved into place
    }

    /** What the add-on was sent, with what the folder held at that moment. */
    private data class Sent(val name: String, val sha256: String, val kjbStillHasAll: Boolean)

    private val sent = mutableListOf<Sent>()
    private var done = 0
    private var answer: (String) -> ImportReply = { ImportReply(ImportReply.Outcome.IMPORTED) }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    private fun handover() = Handover(
        folder = models,
        send = { file, sha ->
            sent += Sent(file.name, sha, File(models, AiModels.MDX).exists() && File(whisper, AiModels.ENCODER).exists())
            answer(file.name)
        },
        sha256 = ::sha256,
        markDone = { done++ },
    )

    @Test
    fun `every file goes over with its SHA-256, the manifest last, and KJB's copies go only after the add-on took them all`() = runTest {
        keptByKjb()
        val wav2vec2 = sha256(File(models, AiModels.WAV2VEC2))

        assertEquals(HandoverEnd.Done, handover().run())

        val names = sent.map { it.name }
        assertEquals(listOf(AiModels.MDX, AiModels.WAV2VEC2), names.take(2))
        assertEquals("${AiModels.WHISPER}/${ModelStore.MANIFEST}", names.last())
        assertEquals((AiModels.WHISPER_FILES.map { "${AiModels.WHISPER}/$it" }).toSet(), names.drop(2).dropLast(1).toSet())
        assertEquals(wav2vec2, sent[1].sha256)
        assertTrue("nothing deleted while files went over", sent.all { it.kjbStillHasAll })
        assertEquals(1, done)
        assertFalse(models.exists())
    }

    @Test
    fun `the add-on asking to try again midway deletes nothing, and the next run finishes`() = runTest {
        keptByKjb()
        answer = { name -> if (name.endsWith(AiModels.DECODER)) ImportReply(ImportReply.Outcome.RETRY, "busy") else ImportReply(ImportReply.Outcome.IMPORTED) }

        assertEquals(HandoverEnd.Retry("busy"), handover().run())
        assertTrue(File(whisper, AiModels.DECODER).exists() && File(models, AiModels.MDX).exists())
        assertEquals(0, done)

        answer = { ImportReply(ImportReply.Outcome.IMPORTED) }
        assertEquals(HandoverEnd.Done, handover().run())
        assertFalse(models.exists())
    }

    @Test
    fun `KJB killed midway deletes nothing, and the next run sends everything again`() = runTest {
        keptByKjb()
        answer = { name -> if (name.endsWith(AiModels.ENCODER)) throw CancellationException("the worker was stopped") else ImportReply(ImportReply.Outcome.IMPORTED) }

        assertTrue(runCatching { handover().run() }.exceptionOrNull() is CancellationException)
        assertTrue(File(whisper, AiModels.ENCODER).exists())
        assertEquals(0, done)

        sent.clear()
        answer = { ImportReply(ImportReply.Outcome.IMPORTED) }
        assertEquals(HandoverEnd.Done, handover().run())
        assertEquals(AiModels.WHISPER_FILES.size + 3, sent.size)
    }

    @Test
    fun `a file that does not match its SHA-256 is never deleted, the add-on downloads instead`() = runTest {
        keptByKjb()
        answer = { name -> if (name == AiModels.WAV2VEC2) ImportReply(ImportReply.Outcome.MISMATCH, "sha256") else ImportReply(ImportReply.Outcome.IMPORTED) }

        assertEquals(HandoverEnd.GaveUp(AiModels.WAV2VEC2, "sha256"), handover().run())

        assertTrue(File(models, AiModels.WAV2VEC2).exists() && File(models, AiModels.MDX).exists())
        assertEquals(0, done)
        assertEquals(2, sent.size) // it stops at the first file the add-on will not take
    }

    @Test
    fun `the add-on dying during the handover deletes nothing and is tried again`() = runTest {
        keptByKjb()
        answer = { name -> if (name == AiModels.MDX) ImportReply(ImportReply.Outcome.IMPORTED) else throw AddonDiedException() }

        assertTrue(handover().run() is HandoverEnd.Retry)
        assertTrue(models.exists() && File(models, AiModels.WAV2VEC2).exists())
    }

    @Test
    fun `a Whisper folder KJB never finished unpacking is not sent, and goes with the rest once the others are taken`() = runTest {
        keptByKjb(whisperComplete = false)

        assertEquals(HandoverEnd.Done, handover().run())

        assertEquals(listOf(AiModels.MDX, AiModels.WAV2VEC2), sent.map { it.name })
        assertFalse(models.exists())
    }

    @Test
    fun `nothing kept, nothing sent`() = runTest {
        assertEquals(HandoverEnd.Done, Handover(File(folder.root, "none"), { _, _ -> error("sent") }, ::sha256, markDone = { done++ }).run())
        assertEquals(1, done)
    }

    @Test
    fun `the progress counts the bytes the add-on took`() = runTest {
        keptByKjb()
        val seen = mutableListOf<Pair<Long, Long>>()

        Handover(models, { _, _ -> ImportReply(ImportReply.Outcome.IMPORTED) }, ::sha256, progress = { d, t -> seen += d to t }, markDone = {}).run()

        val total = 300L + 200 + AiModels.WHISPER_FILES.indices.sumOf { 100L + it } + File(folder.root, "m").apply { writeText("""{"archiveSha256":"ab","files":{}}""") }.length()
        assertEquals(0L to total, seen.first())
        assertEquals(total to total, seen.last())
    }
}
