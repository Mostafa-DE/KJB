package app.lyrify.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.FileNotFoundException
import java.io.IOException

/** The crash log: what a crash's trace keeps of its messages, the last ten kept, and the handler before the system's. */
class CrashLogTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun report(at: Long, trace: String = "java.lang.IllegalStateException: broken\n\tat app.lyrify.Made.up(Made.kt:1)") =
        CrashReport(at = at, app = "3.0.0 (10)", android = "17 (SDK 37)", device = "Maker Phone 1", mainThread = true, trace = trace)

    // Scrubbing

    @Test
    fun `a path in a message goes, the reason Android gives stays`() {
        assertEquals(
            "<path>: open failed: ENOENT (No such file or directory)",
            CrashScrub.message("/storage/emulated/0/Music/Harbour Glass/01 Paper Lantern (Live).mp3: open failed: ENOENT (No such file or directory)"),
        )
        assertEquals("cannot read <path>", CrashScrub.message("cannot read /data/user/0/app.lyrify/cache/opened/1/Paper Lantern.flac now"))
        assertEquals("bad folder <path>", CrashScrub.message("bad folder Music/Harbour Glass/01 Paper Lantern"))
    }

    @Test
    fun `addresses, quoted text, file names and every line after the first go`() {
        assertEquals("no answer from <uri>", CrashScrub.message("no answer from https://lrclib.net/api/get?track_name=Paper+Lantern&artist_name=Kit"))
        assertEquals("no access to <uri> here", CrashScrub.message("no access to content://media/external/audio/media/42 here"))
        assertEquals("Unexpected JSON token at offset 4: \"<text>\"", CrashScrub.message("Unexpected JSON token at offset 4: \"Paper Lantern\"\nJSON input: {\"title\":\"Paper Lantern\"}"))
        assertEquals("<file>", CrashScrub.message("can't decode Paper Lantern.FLAC"))
        assertEquals("Index 5 out of bounds for length 3", CrashScrub.message("Index 5 out of bounds for length 3"))
        assertNull(CrashScrub.message(null))
        assertTrue(CrashScrub.message("x".repeat(1000))!!.length <= 300)
    }

    @Test
    fun `a trace keeps every exception's class and frames, with each message scrubbed`() {
        val cause = FileNotFoundException("/storage/emulated/0/Music/Paper Lantern.mp3: open failed: EACCES (Permission denied)")
        val error = IllegalStateException("could not open \"Paper Lantern\"", cause)
        val trace = CrashScrub.trace(error)

        assertTrue(trace, trace.startsWith("java.lang.IllegalStateException: could not open \"<text>\"\n\tat "))
        assertTrue(trace, "Caused by: java.io.FileNotFoundException: <path>: open failed: EACCES (Permission denied)" in trace)
        assertTrue(trace, "CrashLogTest" in trace)
        assertFalse(trace, "Paper" in trace)
        assertFalse(trace, "storage" in trace)
    }

    @Test
    fun `a suppressed exception and a deep trace are kept short`() {
        val error = IOException("first")
        error.addSuppressed(IllegalArgumentException("Paper Lantern.ogg"))
        val trace = CrashScrub.trace(error)
        assertTrue(trace, "Suppressed: java.lang.IllegalArgumentException: <file>" in trace)

        fun dig(depth: Int): Nothing = if (depth == 0) throw IllegalStateException("deep") else dig(depth - 1)
        val deep = try {
            dig(600)
        } catch (e: IllegalStateException) {
            e
        }
        assertTrue(CrashScrub.trace(deep).lines().size < 300)
    }

    // The store

    @Test
    fun `the last ten crashes are kept, the newest first`() {
        val store = CrashStore(temp.newFolder("crashes"))
        (1L..12L).forEach { store.write(report(at = it * 1000)) }

        val kept = store.list()

        assertEquals((12L downTo 3L).map { it * 1000 }, kept.map { it.at })
        assertEquals("java.lang.IllegalStateException", kept.first().headline)
        assertEquals("3.0.0 (10)", kept.first().app)
        assertEquals(10, temp.root.resolve("crashes").listFiles()!!.size)
    }

    @Test
    fun `a crash reads back whole, and Clear removes them all`() {
        val store = CrashStore(temp.newFolder("crashes"))
        store.write(report(at = 5_000))
        val id = store.list().single().id

        val text = store.read(id)!!

        assertTrue(text, "KJB 3.0.0 (10)" in text)
        assertTrue(text, "Android 17 (SDK 37)" in text)
        assertTrue(text, "Maker Phone 1" in text)
        assertTrue(text, "at app.lyrify.Made.up(Made.kt:1)" in text)
        assertNull("only a crash file is read", store.read("../outside.txt"))
        assertTrue(store.clear())
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun `two crashes in the same millisecond are both kept`() {
        val store = CrashStore(temp.newFolder("crashes"))
        store.write(report(at = 7_000))
        store.write(report(at = 7_000))
        assertEquals(2, store.list().size)
    }

    // The handler

    @Test
    fun `the crash is written, then the handler before it gets it`() {
        val calls = mutableListOf<String>()
        val previous = Thread.UncaughtExceptionHandler { _, e -> calls += "previous ${e.message}" }
        val handler = CrashHandler(previous) { _, e -> calls += "record ${e.message}" }

        handler.uncaughtException(Thread.currentThread(), IllegalStateException("x"))

        assertEquals(listOf("record x", "previous x"), calls)
    }

    @Test
    fun `a crash that cannot be written still reaches the handler before it`() {
        var reached: Throwable? = null
        val error = IllegalStateException("x")
        val handler = CrashHandler({ _, e -> reached = e }) { _, _ -> throw IOException("disk full") }

        handler.uncaughtException(Thread.currentThread(), error)

        assertSame(error, reached)
    }
}
