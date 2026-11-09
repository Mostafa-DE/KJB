package app.lyrify.stats

import app.lyrify.library.SongRef
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The play log's rows as `plays.db` gives them back, whatever wrote them, and the store's rules. Made-up names only. */
class PlayLogTest {
    private val path = "/storage/emulated/0/Music/KJB test/Salt Lattice.mp3"

    @Test
    fun `a song's row reads as written`() {
        assertEquals(SongPlays(SongRef(path, 12), 3, T0, 540_000), songPlaysOf(path, 12, 3, T0, 540_000))
    }

    @Test
    fun `an old or damaged row reads leniently, and one without a song is dropped`() {
        // Missing values: no plays, never played, nothing heard, no id hint.
        assertEquals(SongPlays(SongRef(path), 0, null, 0), songPlaysOf(path, null, null, null, null))
        // Out of range: a negative count or time heard, a last play at 0 or before, a MediaStore id below 1.
        assertEquals(SongPlays(SongRef(path), 0, null, 0), songPlaysOf(path, 0, -2, -5, -1))
        assertEquals(SongPlays(SongRef(path), 2, null, 0), songPlaysOf(path, -3, 2, 0, null))
        // A last play without any play that counted is no last play.
        assertEquals(SongPlays(SongRef(path), 0, null, 9_000), songPlaysOf(path, null, 0, T0, 9_000))
        // A count past what an Int holds stays the largest.
        assertEquals(Int.MAX_VALUE, songPlaysOf(path, null, Long.MAX_VALUE, T0, 0)?.count)

        assertNull(songPlaysOf(null, 12, 3, T0, 540_000))
        assertNull(songPlaysOf("", 12, 3, T0, 540_000))
        assertNull(songPlaysOf("  ", 12, 3, T0, 540_000))
    }

    @Test
    fun `the store replaces a play of the same song and start, and totals a period's plays per song`() = runBlocking {
        val store = FakePlayLog()
        val other = "/storage/emulated/0/Music/KJB test/Ember Ledger.mp3"
        store.put(LoggedPlay(SongRef(path, 12), T0, 50_000, counted = true))
        store.put(LoggedPlay(SongRef(path, 12), T0, 180_000, counted = true))
        store.put(LoggedPlay(SongRef(path, 14), T0 + 1_000, 20_000, counted = false))
        store.put(LoggedPlay(SongRef(other), T0 + 2_000, 90_000, counted = true))

        assertEquals(
            setOf(SongPlays(SongRef(path, 14), 1, T0, 200_000), SongPlays(SongRef(other), 1, T0 + 2_000, 90_000)),
            store.totals().toSet(),
        )
        // From inclusive, until exclusive.
        assertEquals(listOf(SongPlays(SongRef(path, 14), 0, null, 20_000)), store.totals(T0 + 1_000, T0 + 2_000))
    }

    // plays.db's versions (the SQL itself runs in PlaysDbDeviceTest)

    @Test
    fun `a new file runs every step, and an upgrade only the steps after its version`() {
        val steps = PlaysSchema.STEPS
        assertEquals(steps.size, PlaysSchema.VERSION)
        assertEquals(steps.flatten(), PlaysSchema.upgrade(0, PlaysSchema.VERSION))
        (0..PlaysSchema.VERSION).forEach { version -> assertEquals("from $version", steps.drop(version).flatten(), PlaysSchema.upgrade(version, PlaysSchema.VERSION)) }
        // A newer KJB's file is kept as it is.
        assertEquals(emptyList<String>(), PlaysSchema.upgrade(PlaysSchema.VERSION + 1, PlaysSchema.VERSION))
    }

    @Test
    fun `every step only adds, and runs safely again after a downgrade`() {
        PlaysSchema.STEPS.flatten().forEach { sql ->
            val words = sql.uppercase()
            assertFalse(sql, "DROP " in words || "DELETE " in words)
            if (words.startsWith("CREATE ")) assertTrue(sql, "IF NOT EXISTS" in words)
        }
    }

    private companion object {
        const val T0: Long = 1_800_000_000_000L
    }
}
