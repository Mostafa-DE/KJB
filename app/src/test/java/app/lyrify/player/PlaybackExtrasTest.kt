package app.lyrify.player

import app.lyrify.library.SongRef
import app.lyrify.settings.PlaybackSettings
import app.lyrify.settings.RememberPlace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * Speed and pitch with "This song only", Repeat a part, bookmarks and their ticks, Remember my place, a bookmark's jump,
 * the session's skip amounts, and `song-state.json`. Made-up names only.
 */
class PlaybackExtrasTest {
    private val lantern = "/storage/emulated/0/Music/Harbour Glass/01 Paper Lantern.flac"
    private val thread = "/storage/emulated/0/Music/Rips/Loose Thread.mp3"

    // Speed and pitch

    @Test
    fun `the pitch is kept, follows the speed, or moves from it in semitones`() {
        assertEquals(1f, Speed(1.5f).pitch, 0f)
        assertEquals(1.5f, Speed(1.5f, keepPitch = false).pitch, 0.0001f)
        assertEquals(3f, Speed(1.5f, keepPitch = false, semitones = 12).pitch, 0.0001f)
        assertEquals(0.5f, Speed(1f, keepPitch = false, semitones = -12).pitch, 0.0001f)
        assertEquals(2f.pow(2f / 12f), Speed(1f, keepPitch = false, semitones = 2).pitch, 0.0001f)
        // Kept pitch ignores the semitones left from before.
        assertEquals(1f, Speed(1.25f, keepPitch = true, semitones = 4).pitch, 0f)
    }

    @Test
    fun `the speed chip shows for any speed but 1x, and for a moved pitch`() {
        assertFalse(Speed().shows)
        assertTrue(Speed(1.25f).shows)
        assertFalse(Speed(1f, keepPitch = false).shows)
        assertTrue(Speed(1f, keepPitch = false, semitones = -3).shows)
        assertFalse(Speed(1f, keepPitch = true, semitones = -3).shows)
    }

    @Test
    fun `speeds keep to steps of 0_05 from 0_5 to 2, and the pitch to 6 semitones either way`() {
        assertEquals(Speed(1.25f), Speed(1.27f).normalised())
        assertEquals(Speed(0.5f), Speed(0.1f).normalised())
        assertEquals(Speed(2f), Speed(3.5f).normalised())
        assertEquals(Speed(1f, keepPitch = false, semitones = 6), Speed(1f, keepPitch = false, semitones = 9).normalised())
        assertEquals(Speed(1f, keepPitch = false, semitones = -6), Speed(1f, keepPitch = false, semitones = -40).normalised())
        assertEquals(listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f), SPEEDS)
        SPEEDS.forEach { assertEquals(Speed(it), Speed(it).normalised()) }
    }

    @Test
    fun `a speed set applies to every song, until a song keeps its own with This song only`() {
        val opened = SongState()
        val set = opened.withSpeed(lantern) { it.copy(speed = 1.25f) }
        assertEquals(Speed(1.25f), set.usual)
        assertEquals(Speed(1.25f), set.speedOf(thread))
        // This song only: what was set moves to the song, and the usual speed goes back to what it was.
        val own = set.thisSongOnly(lantern, on = true, usualBefore = opened.usual)
        assertEquals(Speed(1.25f), own.speedOf(lantern))
        assertEquals(Speed(), own.speedOf(thread))
        assertEquals(Speed(), own.usual)
        // Changes now go to the song's own speed only.
        val slower = own.withSpeed(lantern) { it.copy(speed = 0.75f, keepPitch = false) }
        assertEquals(Speed(0.75f, keepPitch = false), slower.speedOf(lantern))
        assertEquals(Speed(), slower.usual)
        // Off: the song plays at the usual speed again.
        val back = slower.thisSongOnly(lantern, on = false, usualBefore = Speed(1.5f))
        assertEquals(Speed(), back.speedOf(lantern))
        assertTrue(back.speeds.isEmpty())
        assertEquals(Speed(), back.speedOf(null))
        // Speeds set are normalised.
        assertEquals(Speed(2f), SongState().withSpeed(null) { it.copy(speed = 9f) }.usual)
    }

    // Bookmarks

    @Test
    fun `bookmarks keep the second, a trimmed note and their own ids, in time order`() {
        val (one, first) = SongState().withBookmark(lantern, 61_900, "  Second verse  ")
        assertEquals(Bookmark(1, 61_000, "Second verse"), first)
        val (two, second) = one.withBookmark(lantern, 12_400, "")
        assertEquals(Bookmark(2, 12_000, ""), second)
        assertEquals(listOf(second, first), two.bookmarksOf(lantern))
        assertEquals(emptyList<Bookmark>(), two.bookmarksOf(thread))
        assertEquals(3, two.nextBookmarkId)
        val (long, capped) = two.withBookmark(thread, 5_000, "x".repeat(500))
        assertEquals(SongState.MAX_NOTE, capped.note.length)
        assertEquals(listOf(first), long.withoutBookmark(lantern, 2).bookmarksOf(lantern))
        // The last bookmark of a song leaves no empty entry; removing one that is not there changes nothing.
        assertFalse(long.withoutBookmark(thread, capped.id).bookmarks.containsKey(thread))
        assertEquals(long, long.withoutBookmark(thread, 99))
    }

    @Test
    fun `bookmarks tick the progress bar at their share of the song, once each`() {
        val marks = listOf(Bookmark(1, 50_000, ""), Bookmark(2, 100_000, "Bridge"), Bookmark(3, 100_000, "Again"), Bookmark(4, 300_000, ""))
        assertEquals(listOf(0.25f, 0.5f, 1f), bookmarkTicks(marks, 200_000))
        assertEquals(emptyList<Float>(), bookmarkTicks(marks, 0))
        assertEquals(emptyList<Float>(), bookmarkTicks(emptyList(), 200_000))
    }

    // Repeat a part (A-B)

    @Test
    fun `a part starts at the second playing and lasts 10 s, or the last 10 s before the final second`() {
        assertEquals(AbPart(lantern, 30_000, 40_000), abPart(lantern, 30_400, 200_000))
        assertEquals(AbPart(lantern, 189_000, 199_000), abPart(lantern, 195_000, 200_000))
        assertEquals(AbPart(lantern, 0, 4_000), abPart(lantern, 3_000, 5_000))
        assertNull(abPart(lantern, 0, 2_500))
        assertNull(abPart(lantern, 0, 0))
    }

    @Test
    fun `A and B stay 2 s apart, inside the song and before its final second, to the second`() {
        val part = AbPart(lantern, 30_000, 40_000)
        assertEquals(AbPart(lantern, 12_000, 40_000), part.withStart(12_345))
        assertEquals(AbPart(lantern, 0, 40_000), part.withStart(-5_000))
        assertEquals(AbPart(lantern, 38_000, 40_000), part.withStart(39_500))
        assertEquals(AbPart(lantern, 30_000, 75_000), part.withEnd(75_200, 200_000))
        assertEquals(AbPart(lantern, 30_000, 32_000), part.withEnd(10_000, 200_000))
        assertEquals(AbPart(lantern, 30_000, 199_000), part.withEnd(250_000, 200_000))
    }

    @Test
    fun `the part loops back to A once B is reached, and a change moves the player into it`() {
        val part = AbPart(lantern, 30_000, 40_000)
        assertNull(loopTo(part, 39_990))
        assertEquals(30_000L, loopTo(part, 40_000))
        assertEquals(30_000L, loopTo(part, 120_000))
        assertNull(loopTo(part, 10_000))
        assertNull(intoPart(part, 35_000))
        assertNull(intoPart(part, 30_000))
        assertEquals(30_000L, intoPart(part, 20_000))
        assertEquals(30_000L, intoPart(part, 41_000))
    }

    // Remember my place

    @Test
    fun `Long songs are 10 minutes or longer, All songs and Off are what they say`() {
        assertTrue(remembers(RememberPlace.LONG_SONGS, 600_000))
        assertFalse(remembers(RememberPlace.LONG_SONGS, 599_999))
        assertTrue(remembers(RememberPlace.ALL_SONGS, 60_000))
        assertFalse(remembers(RememberPlace.OFF, 3_600_000))
    }

    @Test
    fun `a place is kept only past 5 s and more than 10 s from the end`() {
        val long = 1_800_000L
        assertNull(placeToKeep(RememberPlace.LONG_SONGS, long, 5_000))
        assertEquals(5_001L, placeToKeep(RememberPlace.LONG_SONGS, long, 5_001))
        assertEquals(long - 10_001, placeToKeep(RememberPlace.LONG_SONGS, long, long - 10_001))
        assertNull(placeToKeep(RememberPlace.LONG_SONGS, long, long - 10_000))
        assertNull(placeToKeep(RememberPlace.LONG_SONGS, 240_000, 60_000))
        assertEquals(60_000L, placeToKeep(RememberPlace.ALL_SONGS, 240_000, 60_000))
        assertNull(placeToKeep(RememberPlace.OFF, long, 60_000))
        assertNull(placeToKeep(RememberPlace.ALL_SONGS, 0, 60_000))
    }

    @Test
    fun `a song resumes at its place only when it starts from the beginning`() {
        val long = 1_800_000L
        assertEquals(1_390_000L, resumeAt(RememberPlace.LONG_SONGS, long, kept = 1_390_000, startMs = 0))
        assertEquals(1_390_000L, resumeAt(RememberPlace.LONG_SONGS, long, kept = 1_390_000, startMs = 900))
        // The queue put it at its own place already.
        assertNull(resumeAt(RememberPlace.LONG_SONGS, long, kept = 1_390_000, startMs = 300_000))
        assertNull(resumeAt(RememberPlace.LONG_SONGS, long, kept = null, startMs = 0))
        assertNull(resumeAt(RememberPlace.OFF, long, kept = 1_390_000, startMs = 0))
        assertNull(resumeAt(RememberPlace.LONG_SONGS, 240_000, kept = 60_000, startMs = 0))
        assertEquals(60_000L, resumeAt(RememberPlace.ALL_SONGS, 240_000, kept = 60_000, startMs = 0))
        // A place that no longer fits the song (it got shorter) is not used.
        assertNull(resumeAt(RememberPlace.ALL_SONGS, 240_000, kept = 235_000, startMs = 0))
    }

    @Test
    fun `places are kept per song, the newest last, and the oldest go past the cap`() {
        val one = SongState().withPlace(lantern, 61_000).withPlace(thread, 5_500)
        assertEquals(listOf(lantern to 61_000L, thread to 5_500L), one.places.toList())
        // A place kept again is the newest.
        assertEquals(listOf(thread, lantern), one.withPlace(lantern, 70_000).places.keys.toList())
        assertEquals(mapOf(thread to 5_500L), one.withPlace(lantern, null).places)
        val full = (1..SongState.MAX_PLACES + 5).fold(SongState()) { state, i -> state.withPlace("/m/$i.mp3", i * 10_000L) }
        assertEquals(SongState.MAX_PLACES, full.places.size)
        assertFalse(full.places.containsKey("/m/1.mp3"))
        assertTrue(full.places.containsKey("/m/${SongState.MAX_PLACES + 5}.mp3"))
    }

    // A bookmark's jump

    private val album = QueueSource(SourceKind.ALBUM, "album:harbour", "Harbour Glass")
    private val picked = QueueSource(SourceKind.PICKED, "picked:9", "")
    private val refs = (1..4).map { SongRef("/storage/emulated/0/Music/Harbour Glass/0$it Paper Lantern $it.flac") }

    @Test
    fun `a bookmark of a song in the queue playing jumps there, at the bookmark`() {
        val book = QueueBook().play(refs, 0, album, now = 1).book
        val jumped = book.toSpot(refs[2], 61_000, picked, now = 2).book
        assertEquals(book.currentId, jumped.currentId)
        assertEquals(2, jumped.current!!.index)
        assertEquals(61_000L, jumped.current!!.positionMs)
        assertEquals(book.jumps + 1, jumped.jumps)
        // The song playing: a jump inside it.
        val same = jumped.toSpot(refs[2], 12_000, picked, now = 3).book
        assertEquals(2, same.current!!.index)
        assertEquals(12_000L, same.current!!.positionMs)
        assertEquals(jumped.jumps + 1, same.jumps)
    }

    @Test
    fun `a bookmark of a song not in the queue plays it next, there, without a queue it makes one`() {
        val book = QueueBook().play(refs.take(3), 0, album, now = 1).book
        val loose = SongRef("/storage/emulated/0/Music/Rips/Loose Thread.mp3")
        val jumped = book.toSpot(loose, 30_000, picked, now = 2).book
        val queue = jumped.current!!
        assertEquals(book.currentId, queue.id)
        assertEquals(listOf(refs[0], loose, refs[1], refs[2]), queue.items.map { it.song })
        assertEquals(1, queue.index)
        assertEquals(30_000L, queue.positionMs)
        val made = QueueBook().toSpot(loose, 30_000, picked, now = 2).book
        assertEquals(listOf(loose), made.current!!.items.map { it.song })
        assertEquals(30_000L, made.current!!.positionMs)
    }

    @Test
    fun `Remove from queue finds the song at its place in that queue`() {
        val book = QueueBook().play(refs, 0, album, now = 1).book
        val id = book.currentId!!
        assertEquals(book.current!!.items[3].uid, book.uidAt(id, 3))
        assertNull(book.uidAt(id, 4))
        assertNull(book.uidAt("q-none", 0))
    }

    // Skip amounts

    @Test
    fun `the session's seek back and forward follow Skip amounts`() {
        assertEquals(SkipAmounts(30_000, 10_000), skipAmounts(PlaybackSettings()))
        PlaybackSettings.SKIP_SECONDS.forEach { back ->
            PlaybackSettings.SKIP_SECONDS.forEach { forward ->
                assertEquals(SkipAmounts(back * 1_000L, forward * 1_000L), skipAmounts(PlaybackSettings(skipBackSeconds = back, skipForwardSeconds = forward)))
            }
        }
    }

    // song-state.json

    private val full = SongState(
        usual = Speed(1.25f),
        speeds = mapOf(lantern to Speed(0.75f, keepPitch = false, semitones = -2)),
        places = mapOf(thread to 1_390_000L, lantern to 61_000L),
        bookmarks = mapOf(lantern to listOf(Bookmark(1, 12_000, ""), Bookmark(4, 61_000, "Second verse"))),
        part = AbPart(lantern, 30_000, 40_000),
        nextBookmarkId = 5,
    )

    @Test
    fun `song state is stored and read back whole`() {
        assertEquals(full, SongStateJson.decode(SongStateJson.encode(full)))
        assertEquals(SongState(), SongStateJson.decode(SongStateJson.encode(SongState())))
        // Places keep their order: the oldest first.
        assertEquals(listOf(thread, lantern), SongStateJson.decode(SongStateJson.encode(full))!!.places.keys.toList())
    }

    @Test
    fun `song state reads leniently, entry by entry, and counts what it leaves out`() {
        var dropped = 0
        val text = """
            {"schemaVersion":9,"newKey":true,"usual":{"speed":"fast"},"nextBookmarkId":2,
             "part":{"path":"$lantern","startMs":40000,"endMs":30000},
             "songs":[
               {"path":"$lantern","speed":{"speed":7.5,"keepPitch":false,"semitones":2},"place":-5,
                "bookmarks":[{"id":3,"positionMs":61000,"note":"Bridge"},{"id":"x","positionMs":1},{"id":6,"positionMs":-1}]},
               {"speed":{"speed":1.5}},
               "nothing",
               {"path":"$thread","place":1390000,"speed":{"speed":1.5,"keepPitch":"yes"}}
             ]}
        """.trimIndent()
        val state = SongStateJson.decode(text) { dropped += it }
        assertNotNull(state)
        // An unreadable usual speed reads as 1x; a speed out of range is brought into it.
        assertEquals(Speed(), state!!.usual)
        assertEquals(Speed(2f, keepPitch = false, semitones = 2), state.speeds[lantern])
        assertEquals(Speed(1.5f), state.speeds[thread])
        assertEquals(mapOf(thread to 1_390_000L), state.places)
        assertEquals(listOf(Bookmark(3, 61_000, "Bridge")), state.bookmarksOf(lantern))
        // A part whose B is not after A is no part.
        assertNull(state.part)
        // Ids never repeat: the next one is past every id read.
        assertEquals(4L, state.nextBookmarkId)
        // Two songs, a place, two bookmarks and the part; a field it cannot read (the usual speed) reads as its default.
        assertEquals(6, dropped)
        assertNull(SongStateJson.decode("[1,2]"))
        assertNull(SongStateJson.decode("not json"))
        assertEquals(SongState(), SongStateJson.decode("{}"))
    }
}
