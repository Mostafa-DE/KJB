package app.lyrify

import app.lyrify.ai.EditionEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Two editions of one app (plan section 13, Z1): KJB (the `lite` flavour) without AI lyrics, and KJB + AI (`full`) with
 * ONNX Runtime and QNN. The AI sits behind `ai.AiEngine` in `main`. These tests run once per edition; the build itself
 * checks every lite APK as it is assembled (`checkLite<BuildType>NoAi` in app/build.gradle.kts).
 */
class EditionsTest {
    @Test
    fun `the AI engine is in KJB + AI only`() {
        val full = BuildConfig.FLAVOR == "full"
        assertEquals(full, EditionEngine.included)
        assertEquals(full, EditionEngine.versions.startsWith("ONNX Runtime "))
    }

    @Test
    fun `what KJB is built from never names ONNX Runtime or QNN, only KJB + AI's source sets do`() {
        val shared = listOf("main", "debug", "lite").map { File("src/$it") }
        assertTrue("run from the app module: ${File("").absolutePath}", shared[0].isDirectory)
        val found = shared.flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension in SOURCES }.toList() }
            .filter { ENGINE.containsMatchIn(it.readText()) }
            .map { it.path }
        assertEquals(emptyList<String>(), found)
        // The Whisper tokenizer files are the engine's too.
        assertFalse(File("src/main/assets/whisper").exists())
        assertTrue(File("src/full/assets/whisper/vocab.json").isFile)
    }

    private companion object {
        val SOURCES = setOf("kt", "xml")

        /** ONNX Runtime's and Qualcomm's packages, as code or a manifest names them. */
        val ENGINE = Regex("""ai\.onnxruntime|com\.qualcomm""")
    }
}
