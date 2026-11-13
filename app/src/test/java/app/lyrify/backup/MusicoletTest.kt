package app.lyrify.backup

import app.lyrify.library.song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/**
 * Musicolet's own backup, read from a synthetic one built here in its format (a zip of Blowfish-encrypted members, the
 * md5 manifest and its hash, JSON queues, playlists and favourites; the songs table through a fake reader, which the
 * device test replaces with a real SQLite file). Nothing in it is trusted until every checksum agrees. Made-up names only.
 */
class MusicoletTest {
    private val lanternRow = MusicoletSong(LANTERN, "Paper Lantern", "Kit Calloway", "Harbour Glass", 201_000, playCount = 3, lastPlayedAt = LAST, flags = 3073)
    private val bellRow = MusicoletSong(BELL, "Tide Bell", "<unknown>", "<unknown>", 185_000, playCount = 0, lastPlayedAt = 0, flags = 3073)
    private val waterRow = MusicoletSong(WATER, "Low Water", "Mira Solen", "Night Ferry", 240_000, playCount = 0, lastPlayedAt = 0, flags = 3073)
    private val goneRow = MusicoletSong(GONE, "Glass Steps", "Odo Brannick", null, 175_000, playCount = 5, lastPlayedAt = LAST, flags = 3073L or Musicolet.GONE)
    private val db = FakeDb(listOf(lanternRow, bellRow, waterRow, goneRow))

    private val lantern = ImportSong("Music/Harbour Glass/01 Paper Lantern.mp3", null, "Paper Lantern", "Kit Calloway", "Harbour Glass", 201_000, relativePath = "Music/Harbour Glass/01 Paper Lantern.mp3", mediaStoreId = 11)
    private val bell = ImportSong("Music/Harbour Glass/02 Tide Bell.mp3", null, "Tide Bell", durationMs = 185_000, relativePath = "Music/Harbour Glass/02 Tide Bell.mp3", mediaStoreId = 12)
    private val water = ImportSong("Music/Night Ferry/01 Low Water.mp3", null, "Low Water", "Mira Solen", "Night Ferry", 240_000, relativePath = "Music/Night Ferry/01 Low Water.mp3", mediaStoreId = 999)
    private val gone = ImportSong("Music/Gone/03 Glass Steps.mp3", null, "Glass Steps", "Odo Brannick")

    // Reading

    @Test
    fun `a backup gives its playlists, favourites, play counts and queues, each song by its id, folder, name and tags`() {
        val read = Musicolet.read(backup(standard()), db) as MusicoletRead.Read

        assertEquals(
            ImportBundle(
                playlists = listOf(ImportPlaylist("Evening", listOf(lantern, water))),
                favourites = listOf(bell),
                plays = listOf(ImportPlays(lantern, 3, LAST)),
                queues = listOf(ImportQueue(listOf(lantern, bell, water, gone), index = 1, positionMs = 42_000, name = "Queue one")),
            ),
            read.bundle,
        )
    }

    @Test
    fun `a queue keeps its name (review panel-im1, finding 7)`() {
        assertEquals("Queue one", (Musicolet.read(backup(standard()), db) as MusicoletRead.Read).bundle.queues.single().name)
    }

    @Test
    fun `only a plain songs table is read, never a view, a virtual table or computed columns (finding 3)`() {
        assertTrue(SongsTable.plain("table", "CREATE TABLE TABLE_SONGS ( COL_PATH TEXT primary key, COL_TITLE TEXT COLLATE NOCASE )"))
        assertFalse(SongsTable.plain("view", "CREATE VIEW TABLE_SONGS AS SELECT hex(randomblob(700000)) AS COL_PATH"))
        assertFalse(SongsTable.plain("table", "CREATE VIRTUAL TABLE TABLE_SONGS USING fts4(COL_PATH)"))
        assertFalse(SongsTable.plain("table", "CREATE TABLE TABLE_SONGS ( COL_PATH TEXT, COL_TITLE TEXT GENERATED ALWAYS AS (hex(randomblob(9))) )"))
        assertFalse(SongsTable.plain("table", "CREATE TABLE TABLE_SONGS ( COL_PATH TEXT, COL_TITLE TEXT AS(upper(COL_PATH)) )"))
        assertFalse(SongsTable.plain(null, null))
    }

    @Test
    fun `a read of the songs table stops at a field too long, at too much text in all, and at its time (finding 3)`() {
        var now = 0L
        val budget = TextBudget(maxField = 10, maxTotal = 25, deadline = 100) { now }

        assertTrue(budget.take("0123456789"))
        assertTrue(budget.take(null))
        assertFalse(TextBudget(maxField = 10, maxTotal = 25, deadline = 100) { now }.take("01234567890"))
        assertTrue(budget.take("0123456789"))
        assertFalse(budget.take("0123456789"))
        val timed = TextBudget(maxField = 10, maxTotal = 25, deadline = 100) { now }
        now = 101
        assertFalse(timed.take("a"))
    }

    @Test
    fun `a zip is read as Musicolet's through the import, and is not recognised without the reader`() {
        val bytes = backup(standard())

        val read = ImportFormats.read(bytes, "2026-11-13 10-00-00.zip", null, db) as ImportRead.Read
        assertEquals(ImportFormat.MUSICOLET, read.format)
        assertEquals("2026-11-13 10-00-00.zip", read.name)
        assertEquals(ImportFailure.NOT_RECOGNISED, (ImportFormats.read(bytes, "b.zip", null) as ImportRead.Failed).why)
    }

    @Test
    fun `a song Musicolet marks as gone from its phone is only listed, never counted or found`() {
        val bundle = (Musicolet.read(backup(standard()), db) as MusicoletRead.Read).bundle

        assertTrue(bundle.plays.none { it.song.location == gone.location })
        assertEquals(gone, bundle.queues.single().songs.last())
        assertNull(SongMatcher(listOf(song("Music/Gone/03 Glass Steps.mp3", title = "Glass Steps", artist = "Odo Brannick", durationMs = 175_000))).find(gone))
    }

    @Test
    fun `a song the table does not list keeps its playlist's own tags, and a reference in any encoding is read`() {
        val members = standard() + ("Mix.mpl" to json("""{"S_P":["musicolet://media-store?p_v=primary&p_rp=Music/Rips&p_dn=Salt Road.mp3&p_id=14&p_mt=1"],"S_T":["Salt Road"],"S_AR":["Mira Solen"],"S_AL":["Night Ferry"],"S_D":[199000]}"""))

        val mix = (Musicolet.read(backup(members), db) as MusicoletRead.Read).bundle.playlists.first { it.name == "Mix" }

        assertEquals(listOf(ImportSong("Music/Rips/Salt Road.mp3", null, "Salt Road", "Mira Solen", "Night Ferry", 199_000, relativePath = "Music/Rips/Salt Road.mp3", mediaStoreId = 14)), mix.songs)
    }

    // Nothing is trusted until every checksum agrees

    @Test
    fun `another key, a member changed, the hash changed, a member missing or one not listed, and nothing is used`() {
        val members = standard()
        listOf(
            backup(members, key = "NOT_THE_KEY"),
            backup(members, md5Of = { name, plain -> if (name == Musicolet.QUEUES) md5(plain + 1) else md5(plain) }),
            backup(members, hash = "0".repeat(32)),
            backup(members, leaveOut = setOf("Evening.mpl")),
            backup(members, unlisted = mapOf("extra" to json("[]"))),
        ).forEach { bytes -> assertEquals(ImportFailure.MUSICOLET, (Musicolet.read(bytes, db) as MusicoletRead.Failed).why) }
    }

    @Test
    fun `a songs table that cannot be read, or JSON that is not Musicolet's, makes the backup unreadable`() {
        assertEquals(ImportFailure.MUSICOLET, (Musicolet.read(backup(standard()), FakeDb(null)) as MusicoletRead.Failed).why)
        assertEquals(ImportFailure.MUSICOLET, (Musicolet.read(backup(standard() + (Musicolet.QUEUES to json("""{"S0_PQ":"not a list"}"""))), db) as MusicoletRead.Failed).why)
        assertEquals(ImportFailure.MUSICOLET, (Musicolet.read(backup(standard() + (Musicolet.FAVOURITES to "not json".encodeToByteArray())), db) as MusicoletRead.Failed).why)
    }

    @Test
    fun `a zip that is no Musicolet backup is not recognised, and one over the caps is too big`() {
        val other = zip(mapOf("manifest.json" to "{}".encodeToByteArray(), "playlists.json" to "{}".encodeToByteArray()))
        val crowded = zip((1..30).associate { "PCs_Y_$it" to ByteArray(0) })

        assertEquals(ImportFailure.NOT_RECOGNISED, (Musicolet.read(other, db) as MusicoletRead.Failed).why)
        assertEquals(ImportFailure.TOO_BIG, (Musicolet.read(crowded, db, ZipLimits(entries = 20)) as MusicoletRead.Failed).why)
        assertEquals(ImportFailure.TOO_BIG, (Musicolet.read(backup(standard()), db, ZipLimits(entryBytes = 64)) as MusicoletRead.Failed).why)
    }

    @Test
    fun `random bytes and every cut of a backup never throw`() {
        val random = Random(540)
        repeat(300) { Musicolet.read(byteArrayOf(0x50, 0x4B, 3, 4) + random.nextBytes(random.nextInt(0, 3_000)), db) }
        val bytes = backup(standard())
        (0..bytes.size step 7).forEach { cut -> Musicolet.read(bytes.copyOf(cut), db) }
    }

    // Matching: the MediaStore id first, with the same file name

    @Test
    fun `the id finds the song on the same phone even in another folder, only with the same file name`() {
        val road = song("Music/Rips/02 Salt Road.mp3", title = "Salt Road", artist = "Mira Solen", durationMs = 199_000, id = 14)
        val impostor = song("Music/Other/Some Other.mp3", title = "Salt Road", artist = "Mira Solen", durationMs = 199_000, id = 15)
        val matcher = SongMatcher(listOf(road, impostor))

        assertEquals(road, matcher.find(ImportSong("Download/02 Salt Road.mp3", null, "Salt Road", "Mira Solen", durationMs = 199_000, relativePath = "Download/02 Salt Road.mp3", mediaStoreId = 14)))
        // Id 15 names another file: the id is no clue here, and the tags find both, so none.
        assertNull(matcher.find(ImportSong("Download/02 Salt Road.mp3", null, "Salt Road", "Mira Solen", durationMs = 199_000, relativePath = "Download/02 Salt Road.mp3", mediaStoreId = 15)))
    }

    @Test
    fun `on a new phone the folder and file name below the root find the song, then its tags`() {
        val moved = song("Music/Night Ferry/01 Low Water.mp3", title = "Low Water", artist = "Mira Solen", album = "Night Ferry", durationMs = 240_000, id = 77)

        assertEquals(moved, SongMatcher(listOf(moved)).find(water))
        assertEquals(moved, SongMatcher(listOf(moved)).find(water.copy(relativePath = "Other/Place.mp3", location = "Other/Place.mp3")))
    }

    @Test
    fun `the preview reads 1 queue and its songs, the queue left unticked at first unless it is all there is`() {
        val phone = listOf(song("Music/Harbour Glass/01 Paper Lantern.mp3", title = "Paper Lantern", durationMs = 201_000, id = 11))
        val plan = ImportPlan((Musicolet.read(backup(standard()), db) as MusicoletRead.Read).bundle, phone)

        assertEquals(mapOf(ImportKind.PLAYLISTS to 1, ImportKind.FAVOURITES to 1, ImportKind.PLAYS to 1, ImportKind.QUEUES to 1), plan.counts)
        assertEquals(4, plan.queueSongs)
        assertEquals(setOf(ImportKind.PLAYLISTS, ImportKind.FAVOURITES, ImportKind.PLAYS), plan.defaultChosen())
        val queuesOnly = ImportPlan(ImportBundle(queues = listOf(ImportQueue(listOf(lantern)))), phone)
        assertEquals(setOf(ImportKind.QUEUES), queuesOnly.defaultChosen())
    }

    // The synthetic backup

    /** The members a small Musicolet backup holds, decrypted: the songs table, a queue, a playlist, favourites, settings. */
    private fun standard(): Map<String, ByteArray> = linkedMapOf(
        Musicolet.SONGS to DB_BYTES,
        "DB_BDN" to "SQLite format 3\u0000devices".encodeToByteArray(),
        Musicolet.QUEUES to json("""{"S0_CPQ":0,"S0_PQ":[{"S0_PQ_T":"Queue one","S0_PQ_CPS":1,"S0_PQ_LKP":42000,"S0_PQ_OQS":{"S_P":["$LANTERN","$BELL","$WATER","$GONE"]}}]}"""),
        "Evening.mpl" to json("""{"S_P":["$LANTERN","$WATER"],"S_T":["Paper Lantern"]}"""),
        Musicolet.FAVOURITES to json("""{"S_P":["$BELL"]}"""),
        "PP" to json("""[{"key":"k_b_x","value":"true","type":"Boolean"}]"""),
        "SSP" to json("""[{"key":"SQH","value":"[\"a made-up search\"]","type":"String"}]"""),
        "PCs_W_2026.45" to ByteArray(0),
    )

    /**
     * [members] as Musicolet writes them: each encrypted with [key], the manifest listing each one's md5 ([md5Of]),
     * `hash` the manifest's md5 ([hash] in its place), [leaveOut] listed but not in the zip, [unlisted] in it but not listed.
     */
    private fun backup(
        members: Map<String, ByteArray>,
        key: String = Musicolet.KEY,
        md5Of: (String, ByteArray) -> String = { _, plain -> md5(plain) },
        hash: String? = null,
        leaveOut: Set<String> = emptySet(),
        unlisted: Map<String, ByteArray> = emptyMap(),
    ): ByteArray {
        val sums = members.entries.joinToString(",") { (name, plain) -> "\"$name\":\"${md5Of(name, plain)}\"" }
        val manifest = """{"v":540,"time":$LAST,"md5":{$sums},"equalizer":{"numberOfBands":5},"db_file":"DB_SONGS_LOG","DB_BDN":"DB_BDN","pcs":["PCs_W_2026.45"]}""".encodeToByteArray()
        val files = linkedMapOf(Musicolet.MANIFEST to encrypt(manifest, key), Musicolet.HASH to (hash ?: md5(manifest)).encodeToByteArray())
        (members - leaveOut + unlisted).forEach { (name, plain) -> files[name] = encrypt(plain, key) }
        return zip(files)
    }

    private fun zip(files: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip -> files.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() } }
    }.toByteArray()

    private fun encrypt(plain: ByteArray, key: String): ByteArray =
        Cipher.getInstance(Musicolet.CIPHER).apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.encodeToByteArray(), "Blowfish")) }.doFinal(plain)

    private fun md5(bytes: ByteArray): String = MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun json(text: String): ByteArray = text.encodeToByteArray()

    /** The songs table: [rows] for the synthetic file's bytes, null for any other or when [rows] is null. */
    private class FakeDb(private val rows: List<MusicoletSong>?) : MusicoletDb {
        override fun songs(bytes: ByteArray): List<MusicoletSong>? = rows?.takeIf { bytes.contentEquals(DB_BYTES) }
    }

    private companion object {
        const val LAST: Long = 1_790_000_000_000L
        const val LANTERN: String = "musicolet://media-store?p_v=primary&p_rp=Music%2FHarbour%20Glass&p_dn=01%20Paper%20Lantern.mp3&p_id=11&p_mt=1"
        const val BELL: String = "musicolet://media-store?p_v=primary&p_rp=Music%2FHarbour%20Glass%2F&p_dn=02%20Tide%20Bell.mp3&p_id=12&p_mt=1"
        const val WATER: String = "musicolet://media-store?p_v=primary&p_rp=Music%2FNight%20Ferry&p_dn=01%20Low%20Water.mp3&p_id=999&p_mt=1"
        const val GONE: String = "musicolet://media-store?p_v=primary&p_rp=Music%2FGone&p_dn=03%20Glass%20Steps.mp3&p_id=55&p_mt=1"

        /** The synthetic songs table: a stand-in the fake reader knows (a real SQLite file in ImportDeviceTest). */
        val DB_BYTES: ByteArray = "SQLite format 3\u0000synthetic songs".encodeToByteArray()
    }
}
