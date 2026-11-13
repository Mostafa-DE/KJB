package app.lyrify.ui

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lyrify.R
import app.lyrify.data.LedgerState
import app.lyrify.library.Library
import app.lyrify.library.LibraryState
import app.lyrify.library.PlaylistsState
import app.lyrify.library.Song
import app.lyrify.library.SongRef
import app.lyrify.player.QueueSource
import app.lyrify.player.SourceKind
import app.lyrify.settings.DockTab
import app.lyrify.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Song rows on the phone (user feedback 2026-11-13): a tap on a row's round check picks that song, and a drag on a
 * row's handle in a custom order moves it at once. The list is a page's own list on made-up songs; moves go to a list in
 * the test, never to playlists.json, and nothing plays (no row is tapped outside a selection). Made-up names only.
 */
@RunWith(AndroidJUnit4::class)
class SongRowsDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val lantern = made("01 Paper Lantern", "Paper Lantern")
    private val bell = made("02 Tide Bell", "Tide Bell")
    private val water = made("03 Low Water", "Low Water")
    private val kite = made("04 Glass Kite", "Glass Kite")

    @Test
    fun aTapOnARowsRoundCheckPicksAndUnpicksThatSong() {
        compose.setContent { Shell { Rows(listOf(lantern, bell, water), moves = null) } }

        row("Paper Lantern").performTouchInput { longClick() }
        compose.waitForIdle()
        row("Paper Lantern").assertIsSelected()
        row("Tide Bell").assertIsNotSelected()

        // Each row's check: a 48 dp target, in list order.
        val checks = compose.onAllNodesWithTag(CHECK_TAG, useUnmergedTree = true)
        checks[1].assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
        checks[1].performClick()
        compose.waitForIdle()
        row("Tide Bell").assertIsSelected()
        row("Paper Lantern").assertIsSelected()

        compose.onAllNodesWithTag(CHECK_TAG, useUnmergedTree = true)[0].performClick()
        compose.waitForIdle()
        row("Paper Lantern").assertIsNotSelected()
        row("Tide Bell").assertIsSelected()
    }

    @Test
    fun aDragOnAHandleMovesTheSongAtOnceAndTheListKeepsTheMove() {
        val moves = mutableListOf<Pair<Int, Int>>()
        compose.setContent { Shell { Rows(listOf(lantern, bell, water, kite), moves) } }
        val row = with(compose.density) { 64.dp.toPx() }

        // No long press: down on Paper Lantern's handle, two and a half rows down in small steps, and up.
        compose.onAllNodesWithTag(HANDLE_TAG, useUnmergedTree = true)[0].performTouchInput {
            down(center)
            repeat(25) { moveBy(Offset(0f, row / 10)) }
            up()
        }
        compose.waitForIdle()

        assertEquals(listOf(0 to 2), moves)
        compose.waitUntil("the list in its new order", TIMEOUT_MS) { top("Paper Lantern") > top("Low Water") }
        assertTrue(top("Tide Bell") < top("Low Water"))
        assertTrue(top("Paper Lantern") < top("Glass Kite"))
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun talkBacksMoveDownMovesTheSongOnePlace() {
        val moves = mutableListOf<Pair<Int, Int>>()
        compose.setContent { Shell { Rows(listOf(lantern, bell, water), moves) } }

        row("Tide Bell").performCustomAccessibilityActionWithLabel(context.getString(R.string.lib_move_down))
        compose.waitForIdle()

        assertEquals(listOf(1 to 2), moves)
        compose.waitUntil("Tide Bell last", TIMEOUT_MS) { top("Tide Bell") > top("Low Water") }
    }

    /** A song's row, as TalkBack reads it (its "⋯" says the title too). */
    private fun row(title: String) = compose.onNodeWithContentDescription("$title, Kit Calloway")

    private fun top(title: String): Float = row(title).getUnclippedBoundsInRoot().top.value

    /**
     * [songs] as a page lists them: in a custom order with handles when [moves] is given, each move kept there and made
     * on the list as playlists.json would; else a plain list.
     */
    @Composable
    private fun Rows(songs: List<Song>, moves: MutableList<Pair<Int, Int>>?) {
        var shown by remember { mutableStateOf(songs) }
        val ui = LibraryUi(LibraryState.Ready(Library.of(songs, Settings())), Settings(), PlaylistsState(), queues = emptyList(), ledger = LedgerState())
        val rows = rememberLazyListState()
        val reorder = rememberSongReorder(rows) { from, to ->
            moves?.add(from to to)
            shown = shown.toMutableList().apply { add(to, removeAt(from)) }
            null
        }
        SongListFrame(ui, shown, QueueSource(SourceKind.PLAYLIST, "road-trip", "Road trip"), state = rows, topBar = { _, _ -> }) { list ->
            songRows(list, repeats = true, reorder = reorder.takeIf { moves != null })
        }
    }

    /** The shell's locals a page needs: a navigator, lyrics and messages. */
    @Composable
    private fun Shell(content: @Composable () -> Unit) {
        val nav = remember { Navigator(NavState.start(DockTab.LIBRARY)) }
        val toasts = remember { ToastState() }
        CompositionLocalProvider(LocalNavigator provides nav, LocalLyrics provides shellLyrics(), LocalToasts provides toasts, content = content)
    }

    /** A made-up song in the test folder, which nothing on the phone has. */
    private fun made(file: String, title: String): Song = Song(
        ref = SongRef("/storage/emulated/0/Music/Lyrify test/Road Trip/$file.mp3"),
        title = title,
        artist = "Kit Calloway",
        album = "Road Trip",
        albumArtist = null,
        durationMs = 200_000,
        track = null,
        disc = null,
        year = null,
        genre = null,
        folder = "Music/Lyrify test/Road Trip/",
        addedAt = null,
        modifiedAt = null,
        sizeBytes = 4_000_000,
        mimeType = "audio/mpeg",
        albumId = null,
    )

    private companion object {
        const val TIMEOUT_MS: Long = 5_000
    }
}
