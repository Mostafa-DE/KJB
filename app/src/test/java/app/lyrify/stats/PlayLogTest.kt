package app.lyrify.stats

import app.lyrify.library.SongRef
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The play log's rows as `plays.db` gives them back, whatever wrote them, and the store's rules. Made-up names only. */
class PlayLogTest {
    @get:Rule
    val folder = TemporaryFolder()

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

    @Test
    fun `a play written again never loses its count or the time it heard`() = runBlocking {
        val store = FakePlayLog()
        store.put(LoggedPlay(SongRef(path, 12), T0, 120_000, counted = true))
        // The same song and start told again, from scratch: a player that started one play twice.
        store.put(LoggedPlay(SongRef(path), T0, 5_000, counted = false))

        assertEquals(listOf(LoggedPlay(SongRef(path, 12), T0, 120_000, counted = true)), store.plays)
    }

    @Test
    fun `a play still going is kept with where it stood, and a closed one is not`() = runBlocking {
        val store = FakePlayLog()
        val open = OpenState(200_000, 60_000, listOf(HeardSpan(0, 60_000)))
        store.put(LoggedPlay(SongRef(path), T0, 60_000, counted = false), open)
        assertEquals(listOf(StoredPlay(LoggedPlay(SongRef(path), T0, 60_000, counted = false), open)), store.openPlays())

        store.put(LoggedPlay(SongRef(path), T0, 70_000, counted = false))
        assertEquals(emptyList<StoredPlay>(), store.openPlays())
        assertEquals(listOf(LoggedPlay(SongRef(path), T0, 70_000, counted = false)), store.plays)
    }

    @Test
    fun `the parts heard are kept as text and read back leniently`() {
        val spans = listOf(HeardSpan(0, 5_000), HeardSpan(8_000, 12_000))
        assertEquals("0-5000,8000-12000", spansText(spans))
        assertEquals(spans, spansOf("0-5000,8000-12000"))
        // Damaged parts are dropped: not numbers, backwards, empty, negative.
        assertEquals(spans, spansOf("0-5000,junk,9-3,4-4,-4-7,,8000-12000, "))
        assertEquals(emptyList<HeardSpan>(), spansOf(null))
        assertEquals(emptyList<HeardSpan>(), spansOf(""))
    }

    @Test
    fun `a kept play reads leniently, and one without a song or a start is dropped`() {
        val open = OpenState(200_000, 60_000, listOf(HeardSpan(0, 60_000)))
        assertEquals(StoredPlay(LoggedPlay(SongRef(path, 12), T0, 60_000, counted = true), open), storedPlayOf(path, T0, 12, 60_000, 1, 200_000, 60_000, "0-60000"))
        assertEquals(
            StoredPlay(LoggedPlay(SongRef(path), T0, 0, counted = false), OpenState(0, 0, emptyList())),
            storedPlayOf(path, T0, -1, -5, null, null, -3, "junk", clockMs = null),
        )
        // Its real minutes when kept (none from before they were: 3.1.1's), none below 0.
        assertEquals(45_000L, storedPlayOf(path, T0, 12, 60_000, 1, 200_000, 60_000, "0-60000", clockMs = 45_000)?.play?.clockMs)
        assertEquals(0L, storedPlayOf(path, T0, 12, 60_000, 1, 200_000, 60_000, "0-60000", clockMs = -4)?.play?.clockMs)
        assertNull(storedPlayOf(" ", T0, 12, 60_000, 1, 200_000, 60_000, "0-60000"))
        assertNull(storedPlayOf(path, null, 12, 60_000, 1, 200_000, 60_000, "0-60000"))
        assertNull(storedPlayOf(path, 0, 12, 60_000, 1, 200_000, 60_000, "0-60000"))
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
            // A column added is left out where the table has it ([PlaysSchema.upgrade] with what it has).
            if (words.startsWith("ALTER ")) assertTrue(sql, words.startsWith("ALTER TABLE PLAYS ADD COLUMN "))
            // The only change to rows there are: an import's plays marked, which a new import does too.
            if (words.startsWith("UPDATE ")) assertEquals("UPDATE PLAYS SET IMPORTED = 1 WHERE HEARD_MS = 0 AND COUNTED != 0", words)
        }
    }

    @Test
    fun `an upgrade leaves out the columns a table has already, and adds those it lacks`() {
        val added = PlaysSchema.upgrade(2, PlaysSchema.VERSION).filter { it.startsWith("ALTER ") }
        assertEquals(9, added.size)
        // A file a newer KJB left, then 3.1.0 kept: every column there, so no column is added twice.
        val all = setOf("path", "started_at", "started_by", "source", "shuffled", "zone_offset_min", "song_key", "ended_how", "ended_at_ms", "imported", "clock_ms")
        assertEquals(PlaysSchema.upgrade(2, PlaysSchema.VERSION) - added.toSet(), PlaysSchema.upgrade(2, PlaysSchema.VERSION) { if (it == "plays") all else emptySet() })
        // One column there: only the others are added.
        assertEquals(PlaysSchema.upgrade(2, PlaysSchema.VERSION).filterNot { it.contains(" song_key ") }, PlaysSchema.upgrade(2, PlaysSchema.VERSION) { if (it == "plays") setOf("song_key") else emptySet() })
        // A new file: every step, every statement.
        assertEquals(PlaysSchema.upgrade(0, PlaysSchema.VERSION), PlaysSchema.upgrade(0, PlaysSchema.VERSION) { emptySet() })
    }

    @Test
    fun `step 4 adds the real minutes, an empty column on the plays from before, and nothing else`() {
        assertEquals(listOf("ALTER TABLE plays ADD COLUMN clock_ms INTEGER"), PlaysSchema.STEPS.getOrNull(3))
        // 3.1.1's file (version 3) gets only that; a file that has it already (a newer KJB's, kept by 3.1.1) nothing.
        assertEquals(listOf("ALTER TABLE plays ADD COLUMN clock_ms INTEGER"), PlaysSchema.upgrade(3, 4))
        assertEquals(emptyList<String>(), PlaysSchema.upgrade(3, 4) { if (it == "plays") setOf("clock_ms") else emptySet() })
    }

    // An unreadable plays.db (the open that finds it runs in PlaysDbDeviceTest)

    @Test
    fun `an unreadable file is moved aside with its journal files, never deleted, and nothing else moves`() {
        val dir = folder.root
        val file = File(dir, PlaysSchema.NAME).apply { writeBytes(JUNK) }
        File(dir, "plays.db-wal").writeBytes(byteArrayOf(1, 2))
        File(dir, "plays.db-shm").writeBytes(byteArrayOf(3))
        File(dir, "other.db").writeBytes(byteArrayOf(4))

        assertEquals(File(dir, "plays-unreadable-$NOW.db"), keepAside(file, NOW))
        assertEquals(listOf("other.db", "plays-unreadable-$NOW.db", "plays-unreadable-$NOW.db-shm", "plays-unreadable-$NOW.db-wal"), dir.list()?.sorted())
        assertArrayEquals(JUNK, File(dir, "plays-unreadable-$NOW.db").readBytes())
        assertArrayEquals(byteArrayOf(1, 2), File(dir, "plays-unreadable-$NOW.db-wal").readBytes())
    }

    @Test
    fun `a file kept aside before is never overwritten, even at the same millisecond`() {
        val dir = folder.root
        File(dir, "plays-unreadable-$NOW.db").writeBytes(byteArrayOf(9))
        File(dir, "plays-unreadable-${NOW + 1}.db-wal").writeBytes(byteArrayOf(8))
        val file = File(dir, PlaysSchema.NAME).apply { writeBytes(JUNK) }

        assertEquals(File(dir, "plays-unreadable-${NOW + 2}.db"), keepAside(file, NOW))
        assertArrayEquals(byteArrayOf(9), File(dir, "plays-unreadable-$NOW.db").readBytes())
        assertArrayEquals(byteArrayOf(8), File(dir, "plays-unreadable-${NOW + 1}.db-wal").readBytes())
        assertArrayEquals(JUNK, File(dir, "plays-unreadable-${NOW + 2}.db").readBytes())
        assertFalse(file.exists())
    }

    @Test
    fun `with no file there, nothing is kept aside`() {
        assertNull(keepAside(File(folder.root, PlaysSchema.NAME), NOW))
        assertEquals(emptyList<String>(), folder.root.list()?.toList())
    }

    private companion object {
        const val T0: Long = 1_800_000_000_000L
        const val NOW: Long = 1_762_800_000_000L
        val JUNK = ByteArray(8_192) { (it * 31 + 7).toByte() }
    }
}
