package app.lyrify.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.lyrify.books.BookMarks
import app.lyrify.books.BookState
import app.lyrify.books.Chapter
import app.lyrify.books.bookNow
import app.lyrify.books.bookSource
import app.lyrify.books.booksOf
import app.lyrify.library.Song
import app.lyrify.library.SongRef
import app.lyrify.player.PlayerUi
import app.lyrify.player.QueueBook
import app.lyrify.player.Repeat
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A swipe on a book's cover goes by chapter (BK1): in a book of one file no other cover slides in, the cover springs
 * back, and the swipe still runs the chapter step it stands for, Next to the left and Previous to the right. Made-up
 * names only.
 */
@RunWith(AndroidJUnit4::class)
class BookCoverDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private var nexts = 0
    private var previouses = 0

    private val orchard = Song(
        ref = SongRef("/storage/emulated/0/Audiobooks/Quiet Orchard.m4b"),
        title = "Quiet Orchard",
        artist = "Mira Solen",
        album = null,
        albumArtist = null,
        durationMs = 900_000,
        track = null,
        disc = null,
        year = null,
        genre = null,
        folder = "Audiobooks/",
        addedAt = null,
        modifiedAt = null,
        sizeBytes = 4_000_000,
        mimeType = "audio/mp4",
    )

    private fun cover() {
        val book = booksOf(listOf(orchard), BookMarks()).single()
        val chapters = BookState().withChapters(book, "stamp", listOf(Chapter(0, 300_000, "One"), Chapter(300_000, 600_000, "Two"), Chapter(600_000, 900_000, "Three")))
        val queues = QueueBook().play(listOf(orchard.ref), 0, bookSource(book), now = 1).book.place(0, 400_000)
        val queue = queues.current!!
        val ui = PlayerUi(queues, queue, queue.current!!, song = orchard, playing = false, positionMs = 400_000, at = 0, speed = 1f, durationMs = orchard.durationMs, repeat = Repeat.OFF, library = null)
        val now = bookNow(book, 0, 400_000, chapters)
        compose.setContent {
            LyrifyTheme {
                CompositionLocalProvider(LocalToasts provides remember { ToastState() }) {
                    Box(Modifier.size(COVER).testTag(COVER_TAG)) {
                        CoverFace(ui, side = COVER, area = AREA, enabled = true, onFlip = null, onNext = { nexts++ }, onPrevious = { previouses++ }, book = now)
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun aSwipeOnABookOfOneFileStepsAChapterThoughNoCoverSlidesIn() {
        cover()
        compose.onNodeWithTag(COVER_TAG).performTouchInput { swipe(Offset(width * 0.9f, centerY), Offset(width * 0.1f, centerY + 8f), durationMillis = 300) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, nexts)
            assertEquals(0, previouses)
        }
        compose.onNodeWithTag(COVER_TAG).performTouchInput { swipe(Offset(width * 0.1f, centerY), Offset(width * 0.9f, centerY + 8f), durationMillis = 300) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, nexts)
            assertEquals(1, previouses)
        }
    }

    private companion object {
        const val COVER_TAG = "cover"
        val COVER = 160.dp
        val AREA = 312.dp
    }
}
