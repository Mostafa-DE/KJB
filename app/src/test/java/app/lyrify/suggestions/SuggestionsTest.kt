package app.lyrify.suggestions

import app.lyrify.library.Library
import app.lyrify.library.Song
import app.lyrify.library.SongRef
import app.lyrify.library.VOLUME
import app.lyrify.library.song
import app.lyrify.player.QueueBook
import app.lyrify.player.QueueSource
import app.lyrify.player.Repeat
import app.lyrify.player.SourceKind
import app.lyrify.player.Suggested
import app.lyrify.settings.HeldSong
import app.lyrify.settings.Settings
import app.lyrify.settings.SettingsStore
import app.lyrify.settings.SuggestionSettings
import app.lyrify.stats.FakePlayLog
import app.lyrify.stats.LoggedPlay
import app.lyrify.stats.PlayEnd
import app.lyrify.stats.PlayEnded
import app.lyrify.stats.PlayLogStore
import app.lyrify.stats.PlayStart
import app.lyrify.stats.StartedBy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.random.Random

/** The suggestions service: held songs, the daily For you mix, the cache that reads only new plays, and the queue's feed. Made-up names only. */
class SuggestionsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val amman = ZoneId.of("Asia/Amman")
    private var now = ZonedDateTime.parse("2026-11-22T12:00+03:00").toInstant().toEpochMilli()
    private val day = DAY_MS.toLong()

    private val songs: List<Song> = (0 until 60).map { n ->
        song("Music/Artist ${n % 12}/Song $n.mp3", title = "Song $n", artist = "Artist ${n % 12}", album = "Album ${n % 12}", genre = "Genre ${n % 3}", id = n + 1L, addedAt = 1L)
    }
    private val library = Library.of(songs, Settings())

    private fun play(n: Int, at: Long, by: StartedBy = StartedBy.PICKED, end: PlayEnded? = PlayEnded(PlayEnd.FINISHED, songs[n].durationMs)) =
        LoggedPlay(songs[n].ref, at, end?.atMs ?: 60_000, true, PlayStart(by, SourceKind.SONGS, false, null), 180, end)

    /** Songs 0 to 29 each picked twice in the last month: plenty to suggest. */
    private val log = FakePlayLog((0 until 30).flatMap { n -> listOf(play(n, now - (n % 20 + 2) * day), play(n, now - (n % 20 + 3) * day + 1_000)) })

    /** [log], counting how it is read: whole, or from a start on. */
    private class CountingLog(private val log: PlayLogStore) : PlayLogStore by log {
        var whole = 0
        val froms = ArrayList<Long>()

        override suspend fun all(): List<LoggedPlay>? = log.all().also { whole++ }

        override suspend fun since(from: Long): List<LoggedPlay>? = log.since(from).also { froms += from }
    }

    private class MemorySettings(initial: Settings = Settings()) : SettingsStore {
        val now = MutableStateFlow(initial)
        override val settings: Flow<Settings> = now

        override suspend fun update(change: (Settings) -> Settings): Boolean {
            now.value = change(now.value)
            return true
        }
    }

    private val settings = MemorySettings()

    private fun suggestions(file: File? = File(folder.root, "for-you.json"), plays: PlayLogStore = log) =
        Suggestions(plays, { library }, { emptyMap() }, settings, file, clock = { now }, zone = { amman }, compute = Dispatchers.Unconfined, random = Random(7))

    // Held songs

    @Test
    fun `Not now holds a song out for 30 days, Never until allowed, and Allow lets it back from either`() {
        val a = songs[1].ref
        val b = songs[2].ref
        val held = SuggestionSettings().notNow(a, now).never(b, now)
        assertEquals(listOf(HeldSong(a, now + 30 * day)), held.notNow)
        assertEquals(listOf(HeldSong(b, now)), held.never)
        assertEquals(setOf(a.path, b.path), held.heldPaths(now, library))
        assertEquals(setOf(b.path), held.heldPaths(now + 31 * day, library))
        assertEquals(SuggestionSettings(), held.allow(a).allow(b))
        // Never wins over Not now: a song said never to suggest leaves Not now.
        assertEquals(listOf(HeldSong(a, now)), held.never(a, now).never.filter { it.song == a })
        assertTrue(held.never(a, now).notNow.isEmpty())
        // A Not now that ran out leaves the list the next time it changes.
        assertEquals(listOf(HeldSong(b, now + 31 * day + 30 * day)), held.notNow(b, now + 31 * day).notNow)
    }

    @Test
    fun `a held song moved to another folder is still held, found by its id`() {
        val moved = SongRef(VOLUME + "Old/Song 3.mp3", songs[3].ref.mediaStoreId)
        assertEquals(setOf(songs[3].path), SuggestionSettings().never(moved, now).heldPaths(now, library))
    }

    // For you

    @Test
    fun `For you is the same all day, new plays or not, and new the next morning`() = runBlocking {
        val first = suggestions().forYou()
        assertEquals(FOR_YOU_SONGS, first.size)
        log.plays += play(40, now + 60_000)
        now += 3_600_000
        assertEquals(first.map { it.song }, suggestions().forYou().map { it.song })
        now += day
        assertNotEquals(first.map { it.song }, suggestions().forYou().map { it.song })
    }

    @Test
    fun `a song said never to suggest leaves today's For you at once, and Not now too`() = runBlocking {
        val service = suggestions()
        val first = service.forYou()
        assertTrue(service.never(first[3].song.ref))
        assertTrue(service.notNow(first[5].song.ref))
        val after = service.forYou()
        assertFalse(after.any { it.song == first[3].song || it.song == first[5].song })
        assertEquals(first.size - 2, after.size)
        assertTrue(service.allow(first[3].song.ref))
        assertTrue(service.forYou().any { it.song == first[3].song })
    }

    @Test
    fun `For you leaves out the songs played in the last day and the held ones`() = runBlocking {
        log.plays += play(5, now - 3_600_000)
        settings.now.value = Settings(suggestions = SuggestionSettings().never(songs[6].ref, now))
        val picks = suggestions().forYou()
        assertTrue(picks.isNotEmpty())
        assertFalse(picks.any { it.song == songs[5] || it.song == songs[6] })
    }

    // The cache

    @Test
    fun `new plays reach the taste without reading the whole log again, and a play written again is read as it is now`() = runBlocking {
        val counting = CountingLog(log)
        val service = suggestions(file = null, plays = counting)
        assertEquals(null, service.taste()!!.songs[songs[50].path])
        log.plays += play(50, now - 60_000, end = null)
        now += 120_000
        assertEquals(1, service.taste()!!.songs.getValue(songs[50].path).counted)
        assertEquals(1, counting.whole)
        // Only from a while before the latest play it had (two days ago), not the month before it.
        assertTrue(counting.froms.isNotEmpty() && counting.froms.all { it > now - 3 * day })
        // The same play, written again as it closed: skipped after 3 s.
        val again = log.plays.single { it.song == songs[50].ref }
        log.plays.remove(again)
        log.plays += again.copy(counted = false, heardMs = 3_000, end = PlayEnded(PlayEnd.NEXT, 3_000))
        now += 120_000
        val taste = service.taste()!!.songs.getValue(songs[50].path)
        assertEquals(1, taste.earlySkips)
        assertEquals(0, taste.counted)
        assertEquals(1, counting.whole)
    }

    @Test
    fun `Suggestions health works out over the plays its taste holds, also right after new plays`() = runBlocking {
        val service = suggestions(file = null)
        service.taste()
        // New plays within the minute the taste is kept: one of a song the taste already has, then another song.
        log.plays += play(3, now - 60_000)
        log.plays += play(4, now - 30_000)
        now += 10_000
        val checked = service.health(again = true)!!
        assertTrue(checked.health.steps > 0)
        assertEquals(now, checked.at)
    }

    @Test
    fun `Autoplay and More like this never suggest a held song`() = runBlocking {
        settings.now.value = Settings(suggestions = SuggestionSettings().never(songs[13].ref, now).notNow(songs[25].ref, now))
        val service = suggestions(file = null)
        val queue = listOf(songs[1], songs[13 - 12])
        val autoplay = service.autoplay(queue, queue.map { it.path }.toSet())
        assertTrue(autoplay.isNotEmpty() && autoplay.none { it.song == songs[13] || it.song == songs[25] })
        val more = service.moreLike(Seed.OfSong(songs[1]), Basis.entries.toSet())
        assertTrue(more.picks.isNotEmpty() && more.picks.none { it.song == songs[13] || it.song == songs[25] })
    }

    // The queue's feed

    private val source = QueueSource(SourceKind.ALBUM, "trip", "Road trip")

    private fun queueOf(vararg n: Int, start: Int = 0) = QueueBook().play(n.map { songs[it].ref }, start, source, now).book

    @Test
    fun `Autoplay adds its songs after the queue's last song once that one plays, with Repeat off and Autoplay on only`() = runBlocking {
        val service = suggestions(file = null)
        val book = queueOf(1, 2, 3, start = 2)
        val feed = service.feed(book.current!!, Repeat.OFF, autoplay = true)
        val last = book.current!!.items.last().uid
        assertEquals(1, feed.size)
        assertEquals(Suggested.AUTOPLAY, feed[0].by)
        assertEquals(AUTOPLAY_SONGS, feed[0].songs.size)
        assertTrue(feed[0].songs.all { it.first == last })
        val fed = feed.fold(book) { b, f -> b.suggest(f.queueId, f.songs, f.by) }
        assertEquals(3 + AUTOPLAY_SONGS, fed.current!!.items.size)
        // Not before the last song, not with Repeat on, not with Autoplay off, and not twice.
        assertTrue(service.feed(queueOf(1, 2, 3, start = 1).current!!, Repeat.OFF, autoplay = true).isEmpty())
        assertTrue(service.feed(book.current!!, Repeat.ALL, autoplay = true).isEmpty())
        assertTrue(service.feed(book.current!!, Repeat.OFF, autoplay = false).isEmpty())
        assertTrue(service.feed(fed.current!!, Repeat.OFF, autoplay = true).isEmpty())
    }

    @Test
    fun `Autoplay adds one batch of 20 when the queue's own songs end, nothing while it plays, and a fresh 20 only when its last song plays`() = runBlocking {
        // A library with room for two batches; songs 0 to 59 are the ones the log has plays of.
        val big = Library.of(
            (0 until 150).map { n -> song("Music/Artist ${n % 12}/Song $n.mp3", title = "Song $n", artist = "Artist ${n % 12}", album = "Album ${n % 12}", genre = "Genre ${n % 3}", id = n + 1L, addedAt = 1L) },
            Settings(),
        )
        val service = Suggestions(log, { big }, { emptyMap() }, settings, null, clock = { now }, zone = { amman }, compute = Dispatchers.Unconfined, random = Random(7))
        var book = queueOf(1, 2, 3, start = 2)
        val first = service.feed(book.current!!, Repeat.OFF, autoplay = true).single()
        assertEquals(Suggested.AUTOPLAY, first.by)
        assertEquals(20, AUTOPLAY_SONGS)
        assertEquals(AUTOPLAY_SONGS, first.songs.size)
        book = book.suggest(first.queueId, first.songs, first.by)
        // While the batch plays, nothing more: Up next holds at most what is left of it.
        (3 until 3 + AUTOPLAY_SONGS - 1).forEach { at ->
            book = book.jump(book.current!!.id, at, now).book
            assertEquals("at $at", emptyList<Feed>(), service.feed(book.current!!, Repeat.OFF, autoplay = true))
        }
        // Its last song playing: the next batch of 20, none of them queued before (the queue's own or the first batch).
        book = book.jump(book.current!!.id, 3 + AUTOPLAY_SONGS - 1, now).book
        val next = service.feed(book.current!!, Repeat.OFF, autoplay = true).single()
        assertEquals(AUTOPLAY_SONGS, next.songs.size)
        assertTrue(next.songs.all { it.first == book.current!!.items.last().uid })
        assertTrue(next.songs.none { (_, song) -> book.current!!.items.any { it.song.path == song.path } })
        assertEquals(AUTOPLAY_SONGS, book.suggest(next.queueId, next.songs, next.by).current!!.items.drop(3 + AUTOPLAY_SONGS).size)
    }

    @Test
    fun `Smart shuffle mixes about 1 in 3 into the queue's own songs however often it runs, and never into Autoplay's batch`() = runBlocking {
        val service = suggestions(file = null)
        var book = queueOf(*IntArray(12) { it }).cycleShuffle(Random(1)).cycleShuffle(Random(1))
        // As the playback service runs it: after every change, at every song, through the queue's own songs into Autoplay;
        // after each, at most 1 per (oneIn - 1) of the queue's own songs, never two suggestions together, none at or past
        // Autoplay's songs, and Up next never more than one batch of them.
        repeat(40) {
            repeat(2) { service.feed(book.current!!, Repeat.OFF, autoplay = true).forEach { book = book.suggest(it.queueId, it.songs, it.by) } }
            val order = book.current!!.items.joinToString { "${it.suggested}" }
            assertTrue(order, book.current!!.items.count { it.suggested == Suggested.SMART_SHUFFLE } <= 12 / (service.mix().oneIn - 1))
            assertTrue(order, book.current!!.items.zipWithNext().none { (before, after) -> before.suggested != null && after.suggested != null && Suggested.SMART_SHUFFLE in listOf(before.suggested, after.suggested) })
            assertTrue(order, book.current!!.items.dropWhile { it.suggested != Suggested.AUTOPLAY }.none { it.suggested == Suggested.SMART_SHUFFLE })
            assertTrue(order, book.current!!.items.drop(book.current!!.index + 1).count { it.suggested == Suggested.AUTOPLAY } <= AUTOPLAY_SONGS)
            if (book.current!!.index < book.current!!.items.lastIndex) book = book.jump(book.current!!.id, book.current!!.index + 1, now).book
        }
        // It did mix some in, and Autoplay took over at the end.
        assertTrue(book.current!!.items.any { it.suggested == Suggested.SMART_SHUFFLE })
        assertTrue(book.current!!.items.any { it.suggested == Suggested.AUTOPLAY })
    }

    @Test
    fun `a book's queue never gets Autoplay`() = runBlocking {
        val book = QueueBook().play(listOf(songs[1].ref), 0, QueueSource(SourceKind.BOOK, "book", "A book"), now).book
        assertTrue(suggestions(file = null).feed(book.current!!, Repeat.OFF, autoplay = true).isEmpty())
    }

    @Test
    fun `Smart shuffle puts suggestions among the songs to come, each after a song of the queue, none of the queue's own`() = runBlocking {
        val service = suggestions(file = null)
        val book = queueOf(*IntArray(12) { it }).cycleShuffle(Random(1)).cycleShuffle(Random(1))
        val queue = book.current!!
        val feed = service.feed(queue, Repeat.OFF, autoplay = true)
        val smart = feed.single { it.by == Suggested.SMART_SHUFFLE }
        val upcoming = queue.items.drop(queue.index + 1).map { it.uid }.toSet()
        assertTrue(smart.songs.isNotEmpty() && smart.songs.all { it.first in upcoming })
        assertTrue(smart.songs.none { (_, song) -> queue.items.any { it.song.path == song.path } })
        val fed = book.suggest(smart.queueId, smart.songs, smart.by)
        assertEquals(smart.songs.size, fed.current!!.items.count { it.suggested == Suggested.SMART_SHUFFLE })
    }

    @Test
    fun `the app's Suggestions read the interaction log, the similar artists found and friends' plays`() {
        listOf("interactions = StatsGraph.interactions(app)", "similar = SimilarGraph.cache(app)", "friends = HuddleGraph.friends(app)").forEach {
            assertTrue(it, File("src/main/java/app/lyrify/suggestions/Suggestions.kt").readText().substringAfter("object SuggestionsGraph").contains(it))
        }
    }
}
