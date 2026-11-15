package app.lyrify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every device test method of every module returns nothing, so JUnit runs it on the phone: `fun x() = runBlocking { ... }`
 * returns its last expression's type, and one that ends in a Boolean (a `delete()`) made JUnit reject its whole class as
 * "should be void" (DlDeviceTest, phone session 10). Such a body is `runBlocking<Unit>`. The gates compile device tests
 * but never run them; a JVM test of that shape fails its own run.
 */
class TestShapeTest {
    @Test
    fun `no device test method takes runBlocking's value as its own`() {
        val root = File("..").canonicalFile
        val sources = root.listFiles().orEmpty().map { module -> File(module, "src/androidTest") }.filter(File::isDirectory)
        assertTrue("run from the app module: $root", sources.any { it.path.endsWith("app/src/androidTest") })
        val found = sources.flatMap { dir ->
            dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { source ->
                VALUE_BODY.findAll(source.readText()).map { "${source.name}: ${it.groupValues[1]}" }
            }.toList()
        }
        assertEquals(emptyList<String>(), found)
    }

    private companion object {
        /** A test method whose body is `= runBlocking {`, without `<Unit>`. */
        val VALUE_BODY = Regex("""@Test(?:\([^)]*\))?\s+fun\s+(`[^`]+`|\w+)\(\)\s*=\s*runBlocking\s*\{""")
    }
}
