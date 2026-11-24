package app.lyrify.ui

import app.lyrify.books.BookState
import app.lyrify.books.Chapter
import app.lyrify.books.bookNow
import app.lyrify.books.bookSource
import app.lyrify.books.toChapter
import app.lyrify.library.Library
import app.lyrify.library.SongRef
import app.lyrify.library.song
import app.lyrify.player.PlayerUi
import app.lyrify.player.Queue
import app.lyrify.player.QueueBook
import app.lyrify.player.QueueItem
import app.lyrify.player.QueueSource
import app.lyrify.player.Repeat
import app.lyrify.player.SourceKind
import app.lyrify.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The player cover's swipe (the prototype's coverSwipe): it locks to an axis after 12 dp, follows the finger at 0.6 with
 * a tilt of one degree per 40 dp, skips past 70 dp, and otherwise springs back; a drag down is the player's own, from
 * anywhere on it (PlayerDragTest). The song that slides in is the one Next or Previous plays. Made-up paths only.
 */
class CoverSwipeTest {
    private fun queue(size: Int, index: Int) = Queue(
        id = "q",
        source = QueueSource(SourceKind.ALBUM, "album:x", "Paper Harbour"),
        items = (0 until size).map { QueueItem(uid = it.toLong(), song = SongRef("/Music/KJB test/made-up-$it.mp3")) },
        index = index,
    )

    /** The player on [queue], one of [queues] (by default the only one). */
    private fun ui(queue: Queue, repeat: Repeat = Repeat.OFF, queues: QueueBook = QueueBook(listOf(queue), queue.id)) =
        PlayerUi(queues, queue, queue.current!!, song = null, playing = false, positionMs = 0, at = 0, speed = 1f, durationMs = 0, repeat = repeat, library = null)

    @Test
    fun `the gesture locks to an axis only past 12 dp, sideways or down, never up`() {
        assertNull(swipeAxis(dx = 11f, dy = 0f))
        assertNull(swipeAxis(dx = 0f, dy = -40f))
        assertEquals(SwipeAxis.SIDEWAYS, swipeAxis(dx = -13f, dy = 4f))
        assertEquals(SwipeAxis.SIDEWAYS, swipeAxis(dx = 20f, dy = -19f))
        assertEquals(SwipeAxis.DOWN, swipeAxis(dx = 5f, dy = 13f))
        // As far sideways as down: not sideways (the prototype needs more sideways than down), and down needs more down.
        assertNull(swipeAxis(dx = 20f, dy = 20f))
    }

    @Test
    fun `the cover follows the finger at 0_6 and tilts a degree per 40 dp`() {
        assertEquals(-60f, coverShift(-100f), 0.001f)
        assertEquals(2.5f, coverTilt(100f), 0.001f)
        assertEquals(-1.25f, coverTilt(-50f), 0.001f)
    }

    @Test
    fun `past 70 dp a swipe left plays the next song and a swipe right the previous one, else it springs back`() {
        assertEquals(SwipeOutcome.Skip(SkipWay.NEXT), swipeOutcome(SwipeAxis.SIDEWAYS, dx = -71f))
        assertEquals(SwipeOutcome.Skip(SkipWay.PREVIOUS), swipeOutcome(SwipeAxis.SIDEWAYS, dx = 71f))
        assertEquals(SwipeOutcome.Return, swipeOutcome(SwipeAxis.SIDEWAYS, dx = -70f))
        assertEquals(SwipeOutcome.Return, swipeOutcome(SwipeAxis.SIDEWAYS, dx = 69f))
        assertEquals(SwipeOutcome.Return, swipeOutcome(null, dx = 0f))
    }

    @Test
    fun `down never skips nor closes from the cover itself, the player's own drag takes it (DR1)`() {
        assertEquals(SwipeOutcome.Return, swipeOutcome(SwipeAxis.DOWN, dx = 0f))
        assertEquals(SwipeOutcome.Return, swipeOutcome(SwipeAxis.DOWN, dx = -80f))
    }

    @Test
    fun `the next song slides in, and from the last song the first one, as Next goes there whatever Repeat says`() {
        assertEquals(3L, incoming(ui(queue(5, 2)), SkipWay.NEXT, positionMs = 60_000)?.uid)
        assertEquals(0L, incoming(ui(queue(5, 4)), SkipWay.NEXT, positionMs = 0)?.uid)
        assertEquals(0L, incoming(ui(queue(5, 4), Repeat.ONE), SkipWay.NEXT, positionMs = 0)?.uid)
        assertEquals(0L, incoming(ui(queue(5, 4), Repeat.ALL), SkipWay.NEXT, positionMs = 0)?.uid)
        // A queue of one song has no other song to slide in.
        assertNull(incoming(ui(queue(1, 0), Repeat.ALL), SkipWay.NEXT, positionMs = 0))
    }

    @Test
    fun `the previous song slides in within 3 s of the start, else Previous starts this song over and none does`() {
        assertEquals(1L, incoming(ui(queue(5, 2)), SkipWay.PREVIOUS, positionMs = 3_000)?.uid)
        assertNull(incoming(ui(queue(5, 2)), SkipWay.PREVIOUS, positionMs = 3_001))
        assertNull(incoming(ui(queue(5, 0)), SkipWay.PREVIOUS, positionMs = 0))
        assertEquals(4L, incoming(ui(queue(5, 0), Repeat.ALL), SkipWay.PREVIOUS, positionMs = 0)?.uid)
    }

    @Test
    fun `a skip with a song sliding in ends with that song centred, one without springs back`() {
        // The incoming cover waits just past the screen's edge: half the free width, half the cover, plus the edge.
        val travel = coverTravel(area = 340f, side = 300f, edge = 24f)
        assertEquals(344f, travel, 0.001f)
        assertEquals(-travel, settleShift(SwipeOutcome.Skip(SkipWay.NEXT), slides = true, travel = travel), 0.001f)
        assertEquals(travel, settleShift(SwipeOutcome.Skip(SkipWay.PREVIOUS), slides = true, travel = travel), 0.001f)
        assertEquals(0f, settleShift(SwipeOutcome.Skip(SkipWay.PREVIOUS), slides = false, travel = travel), 0.001f)
        assertEquals(0f, settleShift(SwipeOutcome.Return, slides = true, travel = travel), 0.001f)
    }

    @Test
    fun `while dragging, the neighbour on the side the cover leaves is the one shown`() {
        assertEquals(SkipWay.NEXT, comingFrom(shift = -1f))
        assertEquals(SkipWay.PREVIOUS, comingFrom(shift = 1f))
        assertNull(comingFrom(shift = 0f))
    }

    // A skip from elsewhere during the settle (review finding C)

    @Test
    fun `a finished swipe skips only when the song playing is still the one it started on`() {
        // Nothing moved meanwhile: the swipe skips.
        assertEquals(Settled.SKIP, settledSwipe(startedOn = 4, playingNow = 4, coming = 5))
        // A headset or the notification already played the song sliding in: no second skip, the cover just lands.
        assertEquals(Settled.LANDED, settledSwipe(startedOn = 4, playingNow = 5, coming = 5))
        // It moved somewhere else: the cover springs back and nothing skips.
        assertEquals(Settled.RETURN, settledSwipe(startedOn = 4, playingNow = 9, coming = 5))
        assertEquals(Settled.RETURN, settledSwipe(startedOn = 4, playingNow = 9, coming = null))
    }

    // In a book (BK1): the swipe is the player's chapter Previous and Next, as its buttons are

    @Test
    fun `in a book the cover brings in the part the chapter buttons go to, never round to the first`() {
        val parts = (1..3).map { song("Audiobooks/Folder Book/$it.mp3", album = "Folder Book", durationMs = 600_000) }
        val book = Library.of(parts, Settings()).books.single()
        val queue = Queue("b", bookSource(book), parts.mapIndexed { i, part -> QueueItem(uid = i.toLong(), song = part.ref) }, index = 1)
        // Where the chapter was last looked at does not matter: the swipe reads the place at its end.
        val now = bookNow(book, 1, 0, BookState())
        assertEquals(2L, incoming(ui(queue), SkipWay.NEXT, positionMs = 60_000, book = now)?.uid)
        assertEquals(0L, incoming(ui(queue), SkipWay.PREVIOUS, positionMs = 3_000, book = now)?.uid)
        // Past 3 s Previous starts the part over, as the button does: nothing slides in.
        assertNull(incoming(ui(queue), SkipWay.PREVIOUS, positionMs = 3_001, book = now))
        // The last part's Next goes nowhere, and the first part's Previous neither, whatever Repeat says.
        assertNull(incoming(ui(queue.copy(index = 2), Repeat.ALL), SkipWay.NEXT, positionMs = 0, book = bookNow(book, 2, 0, BookState())))
        assertNull(incoming(ui(queue.copy(index = 0), Repeat.ALL), SkipWay.PREVIOUS, positionMs = 0, book = bookNow(book, 0, 0, BookState())))
    }

    @Test
    fun `in a one-file book no other cover comes, its chapters are inside the file`() {
        val orchard = song("Audiobooks/Quiet Orchard.m4b", durationMs = 900_000)
        val book = Library.of(listOf(orchard), Settings()).books.single()
        val chapters = BookState().withChapters(book, "stamp", listOf(Chapter(0, 300_000, "One"), Chapter(300_000, 600_000, "Two"), Chapter(600_000, 900_000, "Three")))
        val queue = Queue("o", bookSource(book), listOf(QueueItem(uid = 9, song = orchard.ref)))
        val now = bookNow(book, 0, 400_000, chapters)
        assertNull(incoming(ui(queue), SkipWay.NEXT, positionMs = 400_000, book = now))
        assertNull(incoming(ui(queue, Repeat.ALL), SkipWay.PREVIOUS, positionMs = 301_000, book = now))
        // Nothing slides in, yet the swipe still runs its chapter step (BookCoverDeviceTest swipes it on a phone).
        assertEquals(Settled.SKIP, settledSwipe(startedOn = 9, playingNow = 9, coming = null))
    }

    @Test
    fun `in a book the cover brings in a part taken out of the queue, the very one the chapter step puts back (BC1)`() {
        val parts = (1..3).map { song("Audiobooks/Folder Book/$it.mp3", album = "Folder Book", durationMs = 600_000) }
        val book = Library.of(parts, Settings()).books.single()
        val playing = QueueBook().play(parts.map { it.ref }, 1, bookSource(book), now = 1).book
        // Both parts around the one playing taken out of the book's queue.
        val alone = listOf(0, 2).fold(playing) { queues, gone -> queues.remove(queues.currentId!!, queues.current!!.items.first { it.song.path == parts[gone].path }.uid)!!.book }
        val player = ui(alone.current!!, queues = alone)
        val now = bookNow(book, 1, 0, BookState())
        val next = incoming(player, SkipWay.NEXT, positionMs = 60_000, book = now)
        assertEquals(parts[2].path, next?.song?.path)
        // The item Next chapter plays, so the cover that slid in lands on it.
        assertEquals(alone.toChapter(now, 2, now = 10).book.current?.current?.uid, next?.uid)
        val previous = incoming(player, SkipWay.PREVIOUS, positionMs = 2_000, book = now)
        assertEquals(parts[0].path, previous?.song?.path)
        assertEquals(alone.toChapter(now, 0, now = 10).book.current?.current?.uid, previous?.uid)
        // Past 3 s Previous starts the part over: nothing slides in, nothing is put back.
        assertNull(incoming(player, SkipWay.PREVIOUS, positionMs = 3_001, book = now))
    }

    @Test
    fun `in a book the swipe goes by chapter, as the player's chapter buttons do`() {
        // Unit tests run in the module's folder.
        val player = File("src/main/java/app/lyrify/ui/PlayerScreen.kt").readText()
        assertTrue(player.contains("onNext = { if (bookNow != null) chapterStep(connection, ui, bookNow, forward = true) else connection.next() }"))
        assertTrue(player.contains("onPrevious = { if (bookNow != null) chapterStep(connection, ui, bookNow, forward = false) else connection.previous() }"))
    }
}
