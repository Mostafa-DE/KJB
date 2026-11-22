package app.lyrify.player

import app.lyrify.library.Library
import app.lyrify.library.LibraryState
import app.lyrify.library.SongRef
import app.lyrify.library.song
import app.lyrify.settings.PlaybackSettings
import app.lyrify.settings.RememberPlace
import app.lyrify.settings.Settings
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
    private val lanternRef = SongRef(lantern, 11)
    private val threadRef = SongRef(thread, 12)

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
        val set = opened.withSpeed(lanternRef) { it.copy(speed = 1.25f) }
        assertEquals(Speed(1.25f), set.usual)
        assertEquals(Speed(1.25f), set.speedOf(thread))
        // This song only: what was set moves to the song, and the usual speed goes back to what it was.
        val own = set.thisSongOnly(lanternRef, on = true, usualBefore = opened.usual)
        assertEquals(Speed(1.25f), own.speedOf(lantern))
        assertEquals(Speed(), own.speedOf(thread))
        assertEquals(Speed(), own.usual)
        // Changes now go to the song's own speed only.
        val slower = own.withSpeed(lanternRef) { it.copy(speed = 0.75f, keepPitch = false) }
        assertEquals(Speed(0.75f, keepPitch = false), slower.speedOf(lantern))
        assertEquals(Speed(), slower.usual)
        // Off: the song plays at the usual speed again.
        val back = slower.thisSongOnly(lanternRef, on = false, usualBefore = Speed(1.5f))
        assertEquals(Speed(), back.speedOf(lantern))
        assertTrue(back.speeds.isEmpty())
        assertEquals(Speed(), back.speedOf(null))
        // Speeds set are normalised.
        assertEquals(Speed(2f), SongState().withSpeed(null) { it.copy(speed = 9f) }.usual)
    }

    // Bookmarks

    @Test
    fun `bookmarks keep the second, a trimmed note and their own ids, in time order`() {
        val (one, first) = SongState().withBookmark(lanternRef, 61_900, "  Second verse  ")!!
        assertEquals(Bookmark(1, 61_000, "Second verse"), first)
        val (two, second) = one.withBookmark(lanternRef, 12_400, "")!!
        assertEquals(Bookmark(2, 12_000, ""), second)
        assertEquals(listOf(second, first), two.bookmarksOf(lantern))
        assertEquals(emptyList<Bookmark>(), two.bookmarksOf(thread))
        assertEquals(3, two.nextBookmarkId)
        val (long, capped) = two.withBookmark(threadRef, 5_000, "x".repeat(500))!!
        assertEquals(SongState.MAX_NOTE, capped.note.length)
        assertEquals(listOf(first), long.withoutBookmark(lantern, 2).bookmarksOf(lantern))
        // The last bookmark of a song leaves no empty entry; removing one that is not there changes nothing.
        assertFalse(long.withoutBookmark(thread, capped.id).bookmarks.containsKey(thread))
        assertEquals(long, long.withoutBookmark(thread, 99))
    }

    @Test
    fun `a bookmark keeps when it was made, and its note changes later, trimmed and capped, its spot, id and time kept`() {
        val (one, mark) = SongState().withBookmark(lanternRef, 61_000, "Bridge", addedAt = 1_700_000_000_000)!!
        assertEquals(Bookmark(1, 61_000, "Bridge", addedAt = 1_700_000_000_000), mark)
        val noted = one.withNote(lantern, mark.id, "  Second verse  ")
        assertEquals(listOf(mark.copy(note = "Second verse")), noted.bookmarksOf(lantern))
        assertEquals(SongState.MAX_NOTE, one.withNote(lantern, mark.id, "x".repeat(500)).bookmarksOf(lantern).single().note.length)
        // A note of spaces is no note; a bookmark or a song that is not there changes nothing.
        assertEquals("", noted.withNote(lantern, mark.id, "   ").bookmarksOf(lantern).single().note)
        assertEquals(noted, noted.withNote(lantern, 99, "Gone"))
        assertEquals(noted, noted.withNote(thread, mark.id, "Gone"))
        // Ids still never repeat, and the song keeps its id hint.
        assertEquals(2, noted.nextBookmarkId)
        assertEquals(mapOf(lantern to 11L), noted.ids)
    }

    @Test
    fun `a song's last bookmark removed and put back, as the lists' Undo does, keeps its spot, note and time, and the song's id hint`() {
        val (state, mark) = SongState().withBookmark(lanternRef, 61_000, "Bridge", addedAt = 1_700_000_000_000)!!
        val removed = state.withoutBookmark(lantern, mark.id)
        assertEquals(emptyMap<String, Long>(), removed.ids)
        val back = removed.withBookmark(lanternRef, mark.positionMs, mark.note, mark.addedAt)!!.first
        assertEquals(listOf(Triple(61_000L, "Bridge", 1_700_000_000_000L)), back.bookmarksOf(lantern).map { Triple(it.positionMs, it.note, it.addedAt) })
        assertEquals(state.ids, back.ids)
    }

    @Test
    fun `a song's bookmarks, or every part's of its book in the book's order, each with its song`() {
        val parts = listOf("1", "2", "3").map { song("Audiobooks/Folder Book/$it.mp3", album = "Folder Book", durationMs = 600_000, id = it.toLong()) }
        val book = Library.of(parts, Settings()).bookOf(parts[0].path)!!
        val state = SongState()
            .withBookmark(parts[2].ref, 5_000, "Late")!!.first
            .withBookmark(parts[0].ref, 90_000, "")!!.first
            .withBookmark(parts[0].ref, 30_000, "Early")!!.first
            .withBookmark(lanternRef, 12_000, "Chorus")!!.first
        assertEquals(
            listOf(SongMark(parts[0].ref, Bookmark(3, 30_000, "Early")), SongMark(parts[0].ref, Bookmark(2, 90_000, "")), SongMark(parts[2].ref, Bookmark(1, 5_000, "Late"))),
            state.marksOf(parts[1].path, book),
        )
        assertEquals(listOf(SongMark(lanternRef, Bookmark(4, 12_000, "Chorus"))), state.marksOf(lantern, null))
        assertEquals(emptyList<SongMark>(), state.marksOf(thread, null))
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
        val one = SongState().withPlace(lanternRef, 61_000).withPlace(threadRef, 5_500)
        assertEquals(listOf(lantern to 61_000L, thread to 5_500L), one.places.toList())
        // A place kept again is the newest.
        assertEquals(listOf(thread, lantern), one.withPlace(lanternRef, 70_000).places.keys.toList())
        assertEquals(mapOf(thread to 5_500L), one.withPlace(lanternRef, null).places)
        val full = (1..SongState.MAX_PLACES + 5).fold(SongState()) { state, i -> state.withPlace(SongRef("/m/$i.mp3"), i * 10_000L) }
        assertEquals(SongState.MAX_PLACES, full.places.size)
        assertFalse(full.places.containsKey("/m/1.mp3"))
        assertTrue(full.places.containsKey("/m/${SongState.MAX_PLACES + 5}.mp3"))
    }

    // Following a moved song, caps, songs gone

    @Test
    fun `song state notes each song's id hint, and lets it go once the song keeps nothing`() {
        val state = SongState().withPlace(lanternRef, 61_000).thisSongOnly(threadRef, on = true, usualBefore = Speed())
        assertEquals(mapOf(lantern to 11L, thread to 12L), state.ids)
        val (marked, mark) = state.withBookmark(lanternRef, 5_000, "")!!
        assertEquals(mapOf(lantern to 11L, thread to 12L), marked.ids)
        val left = marked.withPlace(lanternRef, null).withoutBookmark(lantern, mark.id).thisSongOnly(threadRef, on = false, usualBefore = Speed())
        assertEquals(emptyMap<String, Long>(), left.ids)
    }

    @Test
    fun `a moved song takes its speed, place, bookmarks and part to its new path, and a song gone leaves`() {
        val moved = "/storage/emulated/0/Music/Archive/01 Paper Lantern.flac"
        val (kept, mark) = SongState(part = AbPart(lantern, 30_000, 40_000))
            .withPlace(lanternRef, 61_000)
            .withPlace(threadRef, 5_500)
            .withSpeed(null) { it.copy(speed = 1.5f) }
            .thisSongOnly(lanternRef, on = true, usualBefore = Speed())
            .withBookmark(lanternRef, 12_000, "Bridge")!!
        // Found by its id hint at its new path, as queues and Favourites find it; the other song is not on the phone.
        val found = kept.refresh { ref -> if (ref.mediaStoreId == 11L) SongRef(moved, 11) else null }
        assertEquals(mapOf(moved to 61_000L), found.places)
        assertEquals(mapOf(moved to Speed(1.5f)), found.speeds)
        assertEquals(listOf(mark), found.bookmarksOf(moved))
        assertEquals(AbPart(moved, 30_000, 40_000), found.part)
        assertEquals(mapOf(moved to 11L), found.ids)
        assertEquals(Speed(), found.usual)
        // Found where it was: nothing changes.
        assertEquals(kept, kept.refresh { it })
        // A new id at the same path is the new hint.
        assertEquals(mapOf(lantern to 40L, thread to 12L), kept.refresh { if (it.path == lantern) SongRef(lantern, 40) else it }.ids)
    }

    @Test
    fun `a song moved onto one with state of its own keeps that state and gains the moved bookmarks`() {
        val (first, a) = SongState().withPlace(lanternRef, 61_000).withBookmark(lanternRef, 12_000, "")!!
        val (both, b) = first.withPlace(threadRef, 5_500).withBookmark(threadRef, 30_000, "")!!
        val merged = both.refresh { ref -> if (ref.path == lantern) threadRef else ref }
        assertEquals(mapOf(thread to 5_500L), merged.places)
        assertEquals(listOf(a, b), merged.bookmarksOf(thread))
        assertEquals(mapOf(thread to 12L), merged.ids)
    }

    @Test
    fun `a library read moves and drops song state, but never one not read, unreadable or empty`() {
        val state = SongState().withPlace(lanternRef, 61_000)
        assertEquals(state, state.followLibrary(LibraryState.Loading))
        assertEquals(state, state.followLibrary(LibraryState.Unreadable))
        assertEquals(state, state.followLibrary(LibraryState.Ready(Library.of(emptyList(), Settings()))))
        val here = Library.of(listOf(song("Music/Harbour Glass/01 Paper Lantern.flac", id = 11)), Settings())
        assertEquals(state, state.followLibrary(LibraryState.Ready(here)))
        val moved = Library.of(listOf(song("Music/Archive/01 Paper Lantern.flac", id = 11)), Settings())
        assertEquals(mapOf("/storage/emulated/0/Music/Archive/01 Paper Lantern.flac" to 61_000L), state.followLibrary(LibraryState.Ready(moved)).places)
        val gone = Library.of(listOf(song("Music/Other/Night Ferry.mp3", id = 99)), Settings())
        assertEquals(emptyMap<String, Long>(), state.followLibrary(LibraryState.Ready(gone)).places)
    }

    @Test
    fun `a song keeps at most 100 bookmarks, and all songs together at most 1000`() {
        val full = (0 until SongState.MAX_BOOKMARKS_PER_SONG).fold(SongState()) { state, i -> state.withBookmark(lanternRef, i * 1_000L, "")!!.first }
        assertEquals(SongState.MAX_BOOKMARKS_PER_SONG, full.bookmarksOf(lantern).size)
        assertNull(full.withBookmark(lanternRef, 500_000, ""))
        val many = (0 until SongState.MAX_BOOKMARKS).fold(SongState()) { state, i -> state.withBookmark(SongRef("/m/${i / 50}.mp3"), i * 1_000L, "")!!.first }
        assertNull(many.withBookmark(threadRef, 0, ""))
        assertTrue(many.withoutBookmark("/m/0.mp3", 1).withBookmark(threadRef, 0, "") != null)
    }

    @Test
    fun `at most 300 songs keep a speed of their own, the oldest go first`() {
        val many = (1..SongState.MAX_SPEEDS + 5).fold(SongState()) { state, i -> state.thisSongOnly(SongRef("/m/$i.mp3", i.toLong()), on = true, usualBefore = Speed()) }
        assertEquals(SongState.MAX_SPEEDS, many.speeds.size)
        assertFalse(many.speeds.containsKey("/m/1.mp3"))
        assertFalse(many.ids.containsKey("/m/1.mp3"))
        assertTrue(many.speeds.containsKey("/m/${SongState.MAX_SPEEDS + 5}.mp3"))
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
        ids = mapOf(lantern to 11L, thread to 12L),
    )

    @Test
    fun `song state is stored and read back whole`() {
        assertEquals(full, SongStateJson.decode(SongStateJson.encode(full)))
        assertEquals(SongState(), SongStateJson.decode(SongStateJson.encode(SongState())))
        // Places keep their order: the oldest first.
        assertEquals(listOf(thread, lantern), SongStateJson.decode(SongStateJson.encode(full))!!.places.keys.toList())
    }

    @Test
    fun `when a bookmark was made is kept in song-state json, and one kept before, or unreadable, reads as not known`() {
        val made = SongState(bookmarks = mapOf(lantern to listOf(Bookmark(1, 12_000, "", addedAt = 1_700_000_000_000), Bookmark(2, 61_000, "Bridge"))), nextBookmarkId = 3)
        assertEquals(made, SongStateJson.decode(SongStateJson.encode(made)))
        // Not known: nothing written for it.
        assertEquals(1, Regex("addedAt").findAll(SongStateJson.encode(made)).count())
        val old = """{"songs":[{"path":"$lantern","bookmarks":[{"id":1,"positionMs":12000,"note":"Chorus"},{"id":2,"positionMs":30000,"addedAt":"soon"},{"id":3,"positionMs":40000,"addedAt":-4}]}]}"""
        assertEquals(listOf(Bookmark(1, 12_000, "Chorus"), Bookmark(2, 30_000, ""), Bookmark(3, 40_000, "")), SongStateJson.decode(old)!!.bookmarksOf(lantern))
    }

    @Test
    fun `a file from before the id hints reads as it was, with no hints, and caps hold on reading`() {
        val old = """{"schemaVersion":1,"usual":{"speed":1.0},"songs":[{"path":"$lantern","place":61000,"speed":{"speed":1.25}}]}"""
        val read = SongStateJson.decode(old)!!
        assertEquals(mapOf(lantern to 61_000L), read.places)
        assertEquals(Speed(1.25f), read.speeds[lantern])
        assertEquals(emptyMap<String, Long>(), read.ids)
        val marks = (1..150).joinToString(",") { """{"id":$it,"positionMs":${it * 1_000}}""" }
        val crowded = SongStateJson.decode("""{"songs":[{"path":"$lantern","mediaStoreId":11,"bookmarks":[$marks]}]}""")!!
        assertEquals(SongState.MAX_BOOKMARKS_PER_SONG, crowded.bookmarksOf(lantern).size)
        assertEquals(mapOf(lantern to 11L), crowded.ids)
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
