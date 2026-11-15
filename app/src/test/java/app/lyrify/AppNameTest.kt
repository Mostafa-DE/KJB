package app.lyrify

import app.lyrify.data.SharedWrites
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The app's visible name is KJB from 3.0 (formerly Lyrify): every string the user sees, the User-Agent, the log tag and
 * the debug build's test folder, and its GitHub repository, Mostafa-DE/KJB. The package, class and data file names keep
 * "lyrify", so 3.0 installs over 2.0.x and keeps its data.
 */
class AppNameTest {
    private fun file(path: String): File = File(path).also { assertTrue("run from the app module: ${it.absolutePath}", it.isFile) }

    /** The text of the string resource [name] in the strings file at [path]. */
    private fun string(path: String, name: String): String? =
        Regex("""<string name="$name"[^>]*>([^<]*)</string>""").find(file(path).readText())?.groupValues?.get(1)

    @Test
    fun `the app is called KJB, and its debug build KJB dev`() {
        assertEquals("KJB", string("src/main/res/values/strings.xml", "app_name"))
        assertEquals("KJB dev", string("src/debug/res/values/strings.xml", "app_name"))
    }

    @Test
    fun `no string resource says Lyrify`() {
        val files = listOf("src/main/res", "src/debug/res").flatMap { root ->
            File(root).walkTopDown().filter { it.isFile && it.parentFile.name.startsWith("values") && it.extension == "xml" }.toList()
        }
        assertTrue(files.size > 1)
        files.forEach { xml ->
            val text = xml.readText().replace(XML_COMMENT, "")
            assertTrue(xml.path, "Lyrify" !in text.replace("Theme.Lyrify", ""))
        }
    }

    @Test
    fun `no text in the code says Lyrify`() {
        val found = listOf("src/main/java", "src/debug/java").flatMap { root ->
            File(root).walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { source ->
                KOTLIN_TOKEN.findAll(source.readText()).map { it.value }
                    .filter { it.startsWith('"') && "Lyrify" in it }
                    .map { "${source.name}: $it" }
            }.toList()
        }
        assertEquals(emptyList<String>(), found)
    }

    @Test
    fun `requests carry KJB, its version and the repository, and the log its tag`() {
        assertEquals("KJB/${BuildConfig.VERSION_NAME} ( $REPOSITORY )", USER_AGENT)
        assertEquals("KJB", LOG_TAG)
        assertEquals("Music/KJB test/", SharedWrites.TEST_FOLDER)
    }

    @Test
    fun `the update check and both add-ons' downloads use the repository Mostafa-DE KJB`() {
        assertEquals("Mostafa-DE/KJB", BuildConfig.UPDATE_REPO)
        assertEquals(REPOSITORY, BuildConfig.REPOSITORY)
        assertTrue(BuildConfig.AI_ADDON_URL, BuildConfig.AI_ADDON_URL.startsWith("$REPOSITORY/releases/download/"))
        assertTrue(BuildConfig.DL_ADDON_URL, BuildConfig.DL_ADDON_URL.startsWith("$REPOSITORY/releases/download/"))
    }

    private companion object {
        const val REPOSITORY: String = "https://github.com/Mostafa-DE/KJB"
        val XML_COMMENT = Regex("""<!--[\s\S]*?-->""")

        /** Comments, string literals and char literals of Kotlin source, whichever starts first. */
        val KOTLIN_TOKEN = Regex("""//[^\n]*|/\*[\s\S]*?\*/|""${'"'}[\s\S]*?""${'"'}|"(?:[^"\\\n]|\\.)*"|'(?:[^'\\\n]|\\.)'""")
    }
}
