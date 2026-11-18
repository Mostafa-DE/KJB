package app.lyrify.lyrics

import app.lyrify.data.LedgerEntry
import app.lyrify.data.LyricsStatus
import app.lyrify.data.SharedWrites
import app.lyrify.library.ArtistSplitter
import app.lyrify.overnight.AiQueue
import app.lyrify.settings.ArtistSplit
import app.lyrify.tags.TagFixtures
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.math.abs

/**
 * The lookup over a made-up library of 34 songs against a made-up LRCLIB ([FakeLrclibDb]), each song in a group the
 * better search was made for (several artists in one tag, lyrics in the file, a title spelled a little differently,
 * another length) or one it must not change (plain hits, misses, another artist's song). Prints what each pass got and
 * how many requests each song cost, for the lane's report; asserts each song's outcome. Made-up names and words only.
 */
class LyricsSearchBenchTest {
    @get:Rule
    val folder = TemporaryFolder()

    /** What a song ended with after one scan. */
    enum class Outcome {
        /** Synced lyrics written by themselves. */
        WRITTEN,

        /** Known lyrics left for the AI add-on to time. */
        AI,

        /** Waits in Check these. */
        CHECK,

        /** Nothing yet: not found, another length, no artist. */
        NONE,
    }

    /** One song: its file, tags, length, the LRCLIB record that is really it (if any), and what it should end with. */
    private class Song(
        val group: String,
        val file: String,
        val title: String,
        val artist: String?,
        val seconds: Int,
        val truth: Long?,
        val withAi: Outcome,
        val withoutAi: Outcome = withAi,
        val bytes: ByteArray = TagFixtures.mp3(tag = null),
    )

    private fun synced(vararg lines: String): String =
        lines.mapIndexed { index, line -> "[00:%02d.00]$line".format(10 + 15 * index) }.joinToString("\n")

    private fun record(id: Long, title: String, artist: String, seconds: Double, album: String? = null) =
        LrclibRecord(id, title, artist, album, seconds, plainLyrics = "Made-up line one\nMade-up line two", syncedLyrics = synced("Made-up one", "Made-up two", "Made-up three"))

    private val db = listOf(
        record(101, "Harbor Lights", "Tessa Morrow", 201.0),
        record(102, "Cold Iron Bell", "Junebug Parade", 245.0, "Copper Days"),
        record(103, "Window Rain", "Ilse Varga", 188.0),
        record(104, "Northbound", "The Quiet Engines", 230.0),
        record(105, "Small Hours", "Rafe Holloway", 200.5),
        record(106, "Lantern Field", "Odalys", 261.0),
        record(107, "Tidewater", "Corin Ash", 215.0),
        record(108, "Velvet Static", "Mira Solen", 222.0),
        record(201, "Glass Orchard", "Kel Varo", 210.0),
        record(202, "Night Ferry", "Pell Drummond", 233.0),
        record(203, "Copper Moon", "Tamsin Hale", 190.0),
        record(204, "Marble Steps", "Haru Pell", 205.0),
        record(205, "Saltwater Hymn", "Arlo Pike", 240.0),
        record(206, "Dust Lanes", "Lio Marsh", 215.0),
        record(301, "Pale Arcade", "Toma Riis", 210.0),
        record(302, "Rook and Rye", "Wil Sander", 190.0),
        record(401, "Don't Wake the River", "Pia Lund", 222.0),
        record(402, "Sun Down Express", "Gil Arroway", 199.0),
        record(501, "Glass Harbor - From the Film Nightjar", "Ruth Calder", 215.0),
        record(601, "Blue Static", "Ines Darrow", 241.0),
        record(602, "Lowland Song", "Teo Marsh", 224.0),
        record(603, "Iron Valley", "Wren Hollis", 260.0),
        record(701, "Paper Moons", "Okra Field", 205.0),
        record(702, "Kestrel", "Ivo Strandberg", 230.0),
    )

    private val tagSynced = listOf(10_000L to "Made-up dawn", 25_000L to "Made-up noon", 40_000L to "Made-up dusk")
    private val tagLrc = "[00:10.00]Made-up dawn\n[00:25.00]Made-up noon\n[00:40.00]Made-up dusk"

    private val library = listOf(
        // Found today, and still: the plain hits.
        Song("hits", "a01.mp3", "Harbor Lights", "Tessa Morrow", 201, 101, Outcome.WRITTEN),
        Song("hits", "a02.mp3", "Cold Iron Bell", "Junebug Parade", 245, 102, Outcome.WRITTEN),
        Song("hits", "a03.mp3", "Window Rain", "Ilse Varga", 188, 103, Outcome.WRITTEN),
        Song("hits", "a04.mp3", "Northbound", "The Quiet Engines", 230, 104, Outcome.WRITTEN),
        Song("hits", "a05.mp3", "Small Hours", "Rafe Holloway", 199, 105, Outcome.WRITTEN),
        Song("hits", "a06.mp3", "Lantern Field (Remastered 2011)", "Odalys", 260, 106, Outcome.WRITTEN),
        Song("hits", "a07.mp3", "Tidewater (feat. Bram Ellis)", "Corin Ash", 215, 107, Outcome.WRITTEN),
        Song("hits", "Mira Solen - Velvet Static.mp3", "Mira Solen - Velvet Static", null, 222, 108, Outcome.WRITTEN),
        // Several artists in one tag: LRCLIB has the song under one of them.
        Song("several artists", "b09.mp3", "Glass Orchard", "Kel Varo; Mina Ost", 210, 201, Outcome.WRITTEN),
        Song("several artists", "b10.mp3", "Night Ferry", "Pell Drummond, Sora Vey", 233, 202, Outcome.WRITTEN),
        Song("several artists", "b11.mp3", "Copper Moon", "Ansel Brooke / Tamsin Hale", 190, 203, Outcome.WRITTEN),
        Song("several artists", "b12.mp3", "Marble Steps", "Dario Lune, Oki Fen, Haru Pell", 205, 204, Outcome.WRITTEN),
        Song("several artists", "b13.mp3", "Saltwater Hymn", "Vesna Kroll feat. Arlo Pike", 240, 205, Outcome.WRITTEN),
        // "&" does not split by default: still for the user to check.
        Song("several artists", "b14.mp3", "Dust Lanes", "Lio Marsh & Cass Dunmore", 215, 206, Outcome.CHECK),
        // Lyrics in the file's own tags.
        Song("in the file", "c15.mp3", "Ember Choir", "Nadia Fenn", 180, null, Outcome.WRITTEN, bytes = LyricsTagFixtures.mp3(LyricsTagFixtures.frame("SYLT", LyricsTagFixtures.sylt(tagSynced)))),
        Song("in the file", "c16.mp3", "Quiet Signal", "Bo Rennick", 195, null, Outcome.WRITTEN, bytes = LyricsTagFixtures.mp3(LyricsTagFixtures.frame("USLT", LyricsTagFixtures.uslt(tagLrc)))),
        Song("in the file", "c17.flac", "Hollow Bay", "Ione Vasquez", 200, null, Outcome.WRITTEN, bytes = LyricsTagFixtures.flac("LYRICS" to tagLrc)),
        Song("in the file", "c18.mp3", "Pale Arcade", "Toma Riis", 210, 301, Outcome.WRITTEN, bytes = LyricsTagFixtures.mp3(LyricsTagFixtures.frame("SYLT", LyricsTagFixtures.sylt(tagSynced)))),
        Song("in the file", "c19.mp3", "Fernlight", "Clea Moss", 205, null, Outcome.AI, bytes = LyricsTagFixtures.mp3(LyricsTagFixtures.frame("USLT", LyricsTagFixtures.uslt("Made-up fern\nMade-up light")))),
        Song("in the file", "c20.flac", "Rook and Rye", "Wil Sander", 190, 302, Outcome.WRITTEN, bytes = LyricsTagFixtures.flac("UNSYNCEDLYRICS" to "Made-up rook\nMade-up rye")),
        // A title spelled a little differently.
        Song("spelling", "e21.mp3", "Dont Wake the River", "Pia Lund", 222, 401, Outcome.WRITTEN),
        Song("spelling", "e22.mp3", "Sundown Express", "Gil Arroway", 199, 402, Outcome.NONE),
        Song("spelling", "e23.mp3", "Glass Harbor", "Ruth Calder", 215, 501, Outcome.CHECK),
        // Another length: synced lyrics LRCLIB has only for a version 9, 12 and 40 s off.
        Song("another length", "g24.mp3", "Blue Static", "Ines Darrow", 250, 601, Outcome.AI, Outcome.NONE),
        Song("another length", "g25.mp3", "Lowland Song", "Teo Marsh", 212, 602, Outcome.AI, Outcome.NONE),
        Song("another length", "g26.mp3", "Iron Valley", "Wren Hollis", 300, 603, Outcome.NONE),
        // Not on LRCLIB at all.
        Song("misses", "h27.mp3", "Saffron Tide", "Ola Brenn", 200, null, Outcome.NONE),
        Song("misses", "h28.mp3", "Kite Season", "Mabel Orr", 180, null, Outcome.NONE),
        Song("misses", "h29.mp3", "Untitled 4", "Fenwick Lane", 150, null, Outcome.NONE),
        Song("misses", "Voice Memo 12.m4a", "Voice Memo 12", null, 95, null, Outcome.NONE),
        Song("misses", "i31.mp3", "Rain Garden", "Asa Quill; Bettina Rowe", 210, null, Outcome.NONE),
        Song("misses", "i32.mp3", "Moth Lamp", "Cyrus Dane, Lea Fox", 190, null, Outcome.NONE),
        // Another artist's song of that name and length: never written by itself.
        Song("other artist", "j33.mp3", "Paper Moons", "Nora Lind", 205, null, Outcome.CHECK),
        Song("other artist", "j34.mp3", "Kestrel", "Hana Bell, Ivo Strand", 230, null, Outcome.CHECK),
    )

    /** One scan of the library; prints the outcomes and the requests per song, and returns each song's outcome. */
    private fun scan(label: String, aiThere: Boolean): Map<String, Outcome> {
        val outcomes = LinkedHashMap<String, Outcome>()
        val requests = LinkedHashMap<String, Int>()
        val wrong = mutableListOf<String>()
        runTest {
            val client = FakeLrclibDb(db)
            val ledger = MemoryLedger()
            val pipeline = pipeline(client, ledger, aiThere)
            val dir = File(folder.root, label).apply { mkdirs() }
            for (song in library) {
                val file = File(dir, song.file).apply { writeBytes(song.bytes) }
                val before = client.requests
                pipeline.process(LocalTrack(file.path, song.title, song.artist, null, song.seconds * 1_000L), Lane.LOW)
                requests[song.file] = client.requests - before
                val entry = ledger[file.path]
                outcomes[song.file] = outcome(entry, file)
                val used = entry?.lrclibId
                if (outcomes[song.file] in setOf(Outcome.WRITTEN, Outcome.AI) && used != null && used != song.truth) wrong += song.file
            }
        }
        val counts = Outcome.entries.associateWith { kind -> outcomes.values.count { it == kind } }
        println("bench $label: ${library.size} songs, $counts, wrong lyrics ${wrong.size}, requests ${requests.values.sum()} (%.2f a song)".format(requests.values.sum().toDouble() / library.size))
        library.groupBy { it.group }.forEach { (group, songs) ->
            val got = songs.groupingBy { outcomes.getValue(it.file) }.eachCount()
            println("bench $label  $group: $got, requests ${songs.sumOf { requests.getValue(it.file) }} for ${songs.size} songs")
        }
        assertEquals("$label: lyrics of another song", emptyList<String>(), wrong)
        return outcomes
    }

    /** The pipeline under test. */
    private fun kotlinx.coroutines.test.TestScope.pipeline(client: LrclibClient, ledger: MemoryLedger, aiThere: Boolean) = LyricsPipeline(
        client = client,
        ledger = ledger,
        clock = { BASE + testScheduler.currentTime },
        writes = SharedWrites.ANYWHERE,
        artistNames = { splitter.names(it) },
        aiTimes = { aiThere },
    )

    /** "Artists with several names" at its defaults. */
    private val splitter = ArtistSplitter(ArtistSplit())

    private fun outcome(entry: LedgerEntry?, audio: File): Outcome = when {
        entry == null -> Outcome.NONE
        entry.status == LyricsStatus.SYNCED && File(LrcFormat.sidecarPath(audio.path)!!).isFile -> Outcome.WRITTEN
        entry.status == LyricsStatus.NEEDS_CHECK -> Outcome.CHECK
        AiQueue.wants(entry, BASE) && AiQueue.job(entry).times -> Outcome.AI
        else -> Outcome.NONE
    }

    @Test
    fun `the better search finds more of the made-up library, and never another song's lyrics`() {
        val withAi = scan("with the AI add-on", aiThere = true)
        val withoutAi = scan("without it", aiThere = false)

        assertEquals(library.associate { it.file to it.withAi }, withAi)
        assertEquals(library.associate { it.file to it.withoutAi }, withoutAi)
    }

    private companion object {
        const val BASE: Long = 1_800_000_000_000L
    }
}

/**
 * A made-up LRCLIB that answers roughly as LRCLIB does: `/api/get` by the same names (case, apostrophes and punctuation
 * aside) and a length within 2 s; `/api/search` with an artist by both names as phrases; `q=` by every word somewhere
 * in the title, artist or album. Counts every request.
 */
class FakeLrclibDb(private val records: List<LrclibRecord>) : LrclibClient {
    var requests: Int = 0
        private set

    private fun key(text: String): String =
        text.lowercase().replace(APOSTROPHES, "").replace(NOT_WORD, " ").trim()

    override suspend fun get(artist: String, title: String, album: String?, durationSeconds: Long, lane: Lane): LrclibResult {
        requests++
        val found = records.firstOrNull {
            key(it.trackName) == key(title) && key(it.artistName) == key(artist) && abs((it.duration ?: -1e9) - durationSeconds) <= 2.0
        }
        return found?.let { LrclibResult.Found(listOf(it)) } ?: LrclibResult.NotFound
    }

    override suspend fun search(title: String, artist: String?, lane: Lane): LrclibResult {
        requests++
        val found = if (artist == null) {
            val words = key(title).split(' ').filter { it.isNotEmpty() }
            records.filter { record ->
                val haystack = key("${record.trackName} ${record.artistName} ${record.albumName.orEmpty()}").split(' ').toSet()
                words.isNotEmpty() && words.all { it in haystack }
            }
        } else {
            records.filter { key(it.trackName).contains(key(title)) && key(it.artistName).contains(key(artist)) }
        }
        return LrclibResult.Found(found.take(MAX_RESULTS))
    }

    override suspend fun byId(id: Long, lane: Lane): LrclibResult {
        requests++
        return records.firstOrNull { it.id == id }?.let { LrclibResult.Found(listOf(it)) } ?: LrclibResult.NotFound
    }

    private companion object {
        const val MAX_RESULTS: Int = 20
        val APOSTROPHES = Regex("['’]")
        val NOT_WORD = Regex("""[^\p{L}\p{N}]+""")
    }
}
