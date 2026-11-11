package app.lyrify.library.search

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Recent searches: the newest first, each once, at most eight, kept in `search-history.json` and read leniently. */
@OptIn(ExperimentalCoroutinesApi::class)
class RecentSearchesTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val file: File get() = File(temp.root, "search-history.json")

    @Test
    fun `a search goes first, as typed, once whatever its case or accents`() {
        assertEquals(listOf("Férry", "paper"), withSearch(listOf("paper", "ferry"), "  Férry "))
        assertEquals(listOf("ferry"), withSearch(emptyList(), "ferry"))
        assertEquals(listOf("ferry", "paper"), withSearch(listOf("ferry", "paper"), "ferry"))
    }

    @Test
    fun `at most eight are kept, the oldest leaving first`() {
        val recent = (1..10).fold(emptyList<String>()) { searches, n -> withSearch(searches, "tide $n") }

        assertEquals((10 downTo 3).map { "tide $it" }, recent)
        assertEquals(MAX_RECENT_SEARCHES, recent.size)
    }

    @Test
    fun `blank words are not kept`() {
        assertEquals(listOf("ferry"), withSearch(listOf("ferry"), "   "))
    }

    @Test
    fun `its x removes one search`() {
        assertEquals(listOf("ferry", "bell"), withoutSearch(listOf("ferry", "lantern", "bell"), "lantern"))
        assertEquals(listOf("ferry"), withoutSearch(listOf("ferry"), "lantern"))
    }

    @Test
    fun `searches are stored and read back`() {
        val store = RecentSearchesStore(file)

        assertTrue(store.save(listOf("Férry", "paper lantern")))
        assertEquals(listOf("Férry", "paper lantern"), RecentSearchesStore(file).load())
        assertEquals(listOf(file.name), temp.root.list()?.toList())
    }

    @Test
    fun `stored searches are read leniently`() {
        assertEquals(
            listOf("ferry", "bell"),
            RecentSearchesJson.decode("""{"version":7,"later":true,"searches":["ferry",3,null," ",{"x":1},"bell","FERRY"]}"""),
        )
        assertEquals(emptyList<String>(), RecentSearchesJson.decode("""{"version":1}"""))
        assertEquals((1..8).map { "tide $it" }, RecentSearchesJson.decode(RecentSearchesJson.encode((1..9).map { "tide $it" })))
        assertNull(RecentSearchesJson.decode("[\"ferry\"]"))
        assertNull(RecentSearchesJson.decode("ferry lantern"))
    }

    @Test
    fun `a missing file is no searches, an unreadable one starts over and is logged by its size only`() {
        assertEquals(emptyList<String>(), RecentSearchesStore(file).load())

        file.writeText("ferry lantern")
        val logs = mutableListOf<String>()
        assertEquals(emptyList<String>(), RecentSearchesStore(file) { logs += it }.load())
        assertEquals(1, logs.size)
        assertFalse(logs.single(), logs.single().contains("ferry"))
    }

    @Test
    fun `a restore's searches count once stored, all in one write, or not at all`() = runTest {
        RecentSearchesStore(file).save(listOf("bell"))
        val recent = RecentSearches(RecentSearchesStore(file), backgroundScope, StandardTestDispatcher(testScheduler))

        assertTrue(recent.addAll(listOf("ferry", "lantern")))
        assertEquals(listOf("ferry", "lantern", "bell"), recent.state.value)
        assertEquals(listOf("ferry", "lantern", "bell"), RecentSearchesStore(file).load())

        // Its folder is a file: nothing can be stored.
        val broken = File(temp.root, "not-a-folder").apply { writeText("") }
        val failing = RecentSearches(RecentSearchesStore(File(broken, "search-history.json")), backgroundScope, StandardTestDispatcher(testScheduler))
        assertFalse(failing.addAll(listOf("ferry")))
        assertEquals(emptyList<String>(), failing.state.value)
    }

    @Test
    fun `changes apply in order once the stored searches are read, and are stored`() = runTest {
        RecentSearchesStore(file).save(listOf("bell"))
        val recent = RecentSearches(RecentSearchesStore(file), backgroundScope, StandardTestDispatcher(testScheduler))
        assertNull(recent.state.value)

        recent.add("ferry")
        recent.add("lantern")
        recent.remove("bell")
        runCurrent()

        assertEquals(listOf("lantern", "ferry"), recent.state.value)
        assertEquals(listOf("lantern", "ferry"), RecentSearchesStore(file).load())
    }
}
