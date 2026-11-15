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

    // Scrubbing: no message text, but for a few framework messages that hold none

    @Test
    fun `a message keeps no text of its own, quoted or not`() {
        assertNull(CrashScrub.message("no lyrics for 'Paper Lantern'"))
        assertNull(CrashScrub.message("Paper Lantern"))
        assertNull(CrashScrub.message("could not open Paper Lantern at /storage/emulated/0/Music/a.mp3"))
        assertNull(CrashScrub.message("no answer from https://lrclib.net/api/get?track_name=Paper+Lantern"))
        assertNull(CrashScrub.message("Unexpected JSON token at offset 4: \"Paper Lantern\""))
        assertNull(CrashScrub.message("bad folder Music\\Harbour Glass"))
        assertNull(CrashScrub.message(null))
    }

    @Test
    fun `Android's file errors, bounds and null references keep their framework text only`() {
        assertEquals(
            "open failed: ENOENT (No such file or directory)",
            CrashScrub.message("/storage/emulated/0/Music/Harbour Glass/01 Paper Lantern (Live).mp3: open failed: ENOENT (No such file or directory)"),
        )
        assertEquals("Index 5 out of bounds for length 3", CrashScrub.message("Index 5 out of bounds for length 3"))
        assertEquals(
            "Attempt to invoke virtual method 'int java.lang.String.length()' on a null object reference",
            CrashScrub.message("Attempt to invoke virtual method 'int java.lang.String.length()' on a null object reference"),
        )
        assertNull("a title in the method's place", CrashScrub.message("Attempt to invoke virtual method 'Paper Lantern by Kit' on a null object reference"))
    }

    @Test
    fun `a trace keeps every exception's class and frames, and no message text`() {
        val cause = FileNotFoundException("/storage/emulated/0/Music/Paper Lantern.mp3: open failed: EACCES (Permission denied)")
        val error = IllegalStateException("could not open Paper Lantern", cause)
        val trace = CrashScrub.trace(error)

        assertTrue(trace, trace.startsWith("java.lang.IllegalStateException\n\tat "))
        assertTrue(trace, "Caused by: java.io.FileNotFoundException: open failed: EACCES (Permission denied)" in trace)
        assertTrue(trace, "CrashLogTest" in trace)
        assertFalse(trace, "Paper" in trace)
        assertFalse(trace, "storage" in trace)
    }

    @Test
    fun `a suppressed exception and a deep trace are kept short`() {
        val error = IOException("first")
        error.addSuppressed(IllegalArgumentException("Paper Lantern.ogg"))
        val trace = CrashScrub.trace(error)
        assertTrue(trace, "\tSuppressed: java.lang.IllegalArgumentException\n" in trace)
        assertFalse(trace, "Paper" in trace)

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
    fun `crashes in the same millisecond keep their order, so the oldest goes first`() {
        val store = CrashStore(temp.newFolder("crashes"))
        (1..12).forEach { store.write(report(at = 7_000, trace = "java.lang.IllegalStateException\n\tat app.lyrify.Made.up$it(Made.kt:1)")) }

        val kept = store.list()

        assertEquals(10, kept.size)
        assertTrue(kept.map { it.id }.toString(), kept.all { Regex("""crash-\d{13}-\d{6}\.txt""").matches(it.id) })
        assertEquals((12 downTo 3).map { "Made.up$it(" }, kept.map { entry -> store.read(entry.id)!!.substringAfter("at app.lyrify.").substringBefore("Made.kt") })
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
