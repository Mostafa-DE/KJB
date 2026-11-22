package app.lyrify.backup

import app.lyrify.backup.BackupFixtures.T0
import app.lyrify.backup.BackupFixtures.VERSION
import app.lyrify.backup.BackupFixtures.contents
import app.lyrify.backup.BackupFixtures.plays311
import app.lyrify.backup.BackupFixtures.read
import app.lyrify.backup.BackupFixtures.zipOf
import app.lyrify.library.SongRef
import app.lyrify.player.SourceKind
import app.lyrify.stats.Interaction
import app.lyrify.stats.InteractionKind
import app.lyrify.stats.LoggedPlay
import app.lyrify.stats.PlayEnd
import app.lyrify.stats.PlayEnded
import app.lyrify.stats.PlayStart
import app.lyrify.stats.StartedBy
import app.lyrify.stats.Target
import app.lyrify.stats.TargetKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipInputStream

/**
 * A long listening history in a backup (IL1): the plays section split in parts, so a backup stays complete past the
 * size any KJB reads in one entry (32 MB, about 100,000 plays), the first part 3.1.1 reads holding the newest plays.
 * Made-up history only.
 */
class LongHistoryBackupTest {
    /** [count] made-up plays a minute apart over [songs] made-up songs, with all a play keeps; every seventh a skip. */
    private fun history(count: Int, songs: Int = 3_000): List<LoggedPlay> {
        val paths = List(songs) { "/storage/emulated/0/Music/KJB test/Made-up Artist ${it % 97}/Made-up Album ${it % 211}/$it Made-up Song $it.mp3" }
        val keys = List(songs) { "made-up song $it|made-up artist ${it % 97}|made-up album ${it % 211}|${100 + it % 200}" }
        return List(count) { i ->
            val song = (i * 7_919) % songs
            val skip = i % 7 == 0
            LoggedPlay(
                SongRef(paths[song]), T0 + i * 60_000L, if (skip) 400 else 180_000, counted = !skip,
                start = PlayStart(StartedBy.entries[i % StartedBy.entries.size], SourceKind.entries[i % SourceKind.entries.size], i % 3 == 0, keys[song]),
                zoneOffsetMinutes = 180,
                end = PlayEnded(if (skip) PlayEnd.NEXT else PlayEnd.FINISHED, if (skip) 400 else 180_000),
            )
        }
    }

    /** [count] made-up interactions a minute apart: searches with their words, albums opened, lyrics. */
    private fun interactions(count: Int): List<Interaction> = List(count) { i ->
        when (i % 3) {
            0 -> Interaction(T0 + i * 60_000L + 30_000, InteractionKind.SEARCH, 180, Target(TargetKind.ARTIST, "Made-up Artist ${i % 97}"), "made-up words $i")
            1 -> Interaction(T0 + i * 60_000L + 30_000, InteractionKind.OPEN, 180, Target(TargetKind.ALBUM, """["Made-up Artist ${i % 97}","Made-up Album ${i % 211}"]"""))
            else -> Interaction(T0 + i * 60_000L + 30_000, InteractionKind.LYRICS, -300, Target(TargetKind.SONG, "/storage/emulated/0/Music/KJB test/$i.mp3"))
        }
    }

    private fun backup(plays: List<LoggedPlay>, interactions: List<Interaction>, partBytes: Int = BackupBundle.PART_BYTES): ByteArray =
        ByteArrayOutputStream().also { BackupBundle.write(BackupSnapshot(plays = plays, interactions = interactions), emptyList(), VERSION, T0, it, partBytes) }.toByteArray()

    /** Each entry of [bytes], by name, as written: its bytes, a gzipped part's unpacked. */
    private fun unpacked(bytes: ByteArray): Map<String, ByteArray> = ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        generateSequence { zip.nextEntry }.associate { entry ->
            val data = zip.readBytes()
            entry.name to if (entry.name.endsWith(".gz")) GZIPInputStream(ByteArrayInputStream(data)).readBytes() else data
        }
    }

    /** How many parts the manifest gives the plays section; null when it says none (one part). */
    private fun parts(bytes: ByteArray): Int? {
        val manifest = Json.parseToJsonElement(checkNotNull(unpacked(bytes)[BackupBundle.MANIFEST]).decodeToString()).jsonObject
        val plays = manifest["sections"]!!.jsonObject["plays"]!!.jsonObject
        assertEquals(1, plays["version"]!!.jsonPrimitive.int)
        return plays["parts"]?.jsonPrimitive?.int
    }

    /**
     * 3.1.1's limits on a backup (ZipLimits at v3.1.1, word for word), counted as its reading counts them: each entry
     * and every entry together unpacked as the zip stores them, and how many entries.
     */
    private fun assertWithin311(bytes: ByteArray) {
        val sizes = ZipInputStream(ByteArrayInputStream(bytes)).use { zip -> generateSequence { zip.nextEntry }.map { zip.readBytes().size.toLong() }.toList() }
        assertTrue("${sizes.size} entries", sizes.size <= 64)
        assertTrue("an entry of ${sizes.max()} bytes", sizes.all { it <= 32L * 1024 * 1024 })
        assertTrue("${sizes.sum()} bytes in all", sizes.sum() <= 96L * 1024 * 1024)
    }

    private fun noId(play: LoggedPlay): LoggedPlay = play.copy(song = SongRef(play.song.path))

    /** [plays] as a plays section in one part reads them: the plays, then the skips, each the oldest first. */
    private fun asOnePart(plays: List<LoggedPlay>): List<LoggedPlay> = (plays.filterNot { it.isShort } + plays.filter { it.isShort }).map(::noId)

    // The rules, with small parts

    @Test
    fun `a history that fits stays in one part, as before`() {
        val bytes = backup(history(50), interactions(20))

        assertNull(parts(bytes))
        assertEquals(listOf(BackupBundle.MANIFEST, BackupBundle.SONGS, Section.PLAYS.file), unpacked(bytes).keys.toList())
    }

    @Test
    fun `a longer history is split in parts, the newest plays first, and every play and interaction comes back`() {
        val plays = history(400)
        val interactions = interactions(150)
        val bytes = backup(plays, interactions, partBytes = SMALL)

        // One part when the manifest says none.
        val parts = parts(bytes) ?: 1
        assertTrue("$parts parts", parts > 3)
        val entries = unpacked(bytes)
        assertEquals(listOf(Section.PLAYS.file) + (2..parts).map(Section.PLAYS::partFile), entries.keys.drop(2))
        entries.values.drop(2).forEach { part -> assertTrue("a part of ${part.size} bytes", part.size <= SMALL) }
        // The first part, all 3.1.1 reads: the newest plays, those that fit.
        val first = checkNotNull(plays311(checkNotNull(entries[Section.PLAYS.file]).decodeToString()))
        assertTrue(first.isNotEmpty())
        assertEquals(plays.filterNot { it.isShort }.takeLast(first.size).map(::noId), first)

        val read = contents(bytes)
        // Read back in the order one part has: nothing lost, doubled or moved.
        assertEquals(asOnePart(plays), read.plays)
        assertEquals(interactions, read.interactions)
        assertEquals(contents(backup(plays, interactions)).plays, read.plays)
    }

    @Test
    fun `a part missing makes it no backup, a damaged one corrupt, and one past its limits too big`() {
        val bytes = backup(history(400), interactions(150), partBytes = SMALL)
        val stored = ZipInputStream(ByteArrayInputStream(bytes)).use { zip -> generateSequence { zip.nextEntry }.map { it.name to zip.readBytes() }.toList() }
        val second = Section.PLAYS.partFile(2)

        assertEquals(BackupRead.Unreadable(BackupRead.Why.NOT_A_BACKUP), read(zipOf(stored.filterNot { it.first == second })))
        assertEquals(BackupRead.Unreadable(BackupRead.Why.CORRUPT), read(zipOf(stored.map { if (it.first == second) it.first to ByteArray(64) { 7 } else it })))
        // A part unpacks within what one entry may hold, small as it is in the zip: a zip bomb costs little.
        val spaces = ByteArray(1 shl 20) { ' '.code.toByte() }
        val bomb = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { gz -> repeat(33) { gz.write(spaces) } } }.toByteArray()
        assertEquals(BackupRead.Unreadable(BackupRead.Why.TOO_BIG), read(zipOf(stored.map { if (it.first == second) it.first to bomb else it })))
        // And all the parts together within theirs.
        assertEquals(BackupRead.Unreadable(BackupRead.Why.TOO_BIG), read(bytes, ZipLimits(partsBytes = SMALL * 2L)))
    }

    // The real sizes

    @Test
    fun `a history past what one entry holds backs up whole, 3_1_1 reads its newest plays, and it all comes back here`() {
        val plays = history(150_000)
        val interactions = interactions(30_000)
        val bytes = backup(plays, interactions)

        val entries = unpacked(bytes)
        // As one entry, as before, it would be past what any KJB reads: the whole backup unreadable.
        assertTrue(entries.filterKeys { it.startsWith("plays") }.values.sumOf { it.size.toLong() } > ZipLimits().entryBytes)
        // 3.1.1 reads the backup, and the newest plays.
        assertWithin311(bytes)
        val first = checkNotNull(plays311(checkNotNull(entries[Section.PLAYS.file]).decodeToString()))
        assertTrue("${first.size} plays in the first part", first.size > 40_000)
        assertEquals(plays.filterNot { it.isShort }.takeLast(first.size).map(::noId), first)

        val read = contents(bytes)
        // Compared whole, not printed: a failure's message would be the history twice.
        assertTrue("plays as one part has them", asOnePart(plays) == read.plays)
        assertTrue("interactions as written", interactions == read.interactions)
    }

    private companion object {
        const val SMALL: Int = 4_096
    }
}
