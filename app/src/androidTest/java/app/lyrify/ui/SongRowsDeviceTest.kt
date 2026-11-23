package app.lyrify.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.swipeLeft
import app.lyrify.library.arranged
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
 * Song rows on the phone (user feedback 2026-11-13): a tap on a row's round check picks that song (the row staying one
 * TalkBack stop), and an upright drag on a row's handle in a custom order moves it at once, while a sideways one on the
 * same handle goes to the tab pages (the real pager). The list is a page's own list on made-up songs; moves go to a list
 * in the test, never to playlists.json, and nothing plays (no row is tapped outside a selection). Made-up names only.
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

        // Each row's check: a 48 dp target, in list order, and no TalkBack stop of its own (the row says it is selected).
        val checks = compose.onAllNodesWithTag(CHECK_TAG, useUnmergedTree = true)
        checks[1].assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
        checks[1].assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
        checks[1].assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription))
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
        val moves = mutableListOf<List<SongRef>>()
        compose.setContent { Shell { Rows(listOf(lantern, bell, water, kite), moves) } }
        val row = with(compose.density) { 64.dp.toPx() }

        // No long press: down on Paper Lantern's handle (at the row's end), two and a half rows down in small steps, and up.
        compose.onAllNodesWithTag(HANDLE_TAG, useUnmergedTree = true)[0].performTouchInput {
            down(center)
            repeat(25) { moveBy(Offset(0f, row / 10)) }
            up()
        }
        compose.waitForIdle()

        assertEquals(listOf(listOf(bell, water, lantern, kite).map { it.ref }), moves)
        compose.waitUntil("the list in its new order", TIMEOUT_MS) { top("Paper Lantern") > top("Low Water") }
        assertTrue(top("Tide Bell") < top("Low Water"))
        assertTrue(top("Paper Lantern") < top("Glass Kite"))
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun talkBacksMoveDownMovesTheSongOnePlace() {
        val moves = mutableListOf<List<SongRef>>()
        compose.setContent { Shell { Rows(listOf(lantern, bell, water), moves) } }

        row("Tide Bell").performCustomAccessibilityActionWithLabel(context.getString(R.string.lib_move_down))
        compose.waitForIdle()

        assertEquals(listOf(listOf(lantern, water, bell).map { it.ref }), moves)
        compose.waitUntil("Tide Bell last", TIMEOUT_MS) { top("Tide Bell") > top("Low Water") }
    }

    /** A song's row, as TalkBack reads it with its length in words (its "⋯" says the title too). */
    private fun row(title: String) = compose.onNodeWithContentDescription("$title, Kit Calloway, 3 minutes 20 seconds")

    @Test
    fun onTheTabPagesASidewaysSwipeOnAHandleTurnsThePageAndAnUprightDragMovesTheSong() {
        val moves = mutableListOf<List<SongRef>>()
        var chosen by mutableIntStateOf(0)
        compose.setContent {
            Shell {
                val pager = rememberTabPager(listOf("Songs", "Albums"), chosen) { chosen = it }
                TabPages(pager, Modifier.fillMaxSize()) { tab ->
                    if (tab == "Songs") Rows(listOf(lantern, bell, water), moves) else Box(Modifier.fillMaxSize().testTag("albums"))
                }
            }
        }
        val row = with(compose.density) { 64.dp.toPx() }

        compose.onAllNodesWithTag(HANDLE_TAG, useUnmergedTree = true)[1].performTouchInput {
            down(center)
            repeat(10) { moveBy(Offset(0f, row / 10)) }
            up()
        }
        compose.waitForIdle()
        assertEquals(listOf(listOf(lantern, water, bell).map { it.ref }), moves)

        compose.onAllNodesWithTag(HANDLE_TAG, useUnmergedTree = true)[0].performTouchInput { swipeLeft(startX = centerX, endX = left - width) }
        compose.waitForIdle()
        assertEquals(1, chosen)
        assertEquals(1, moves.size)
    }

    private fun top(title: String): Float = row(title).getUnclippedBoundsInRoot().top.value

    /**
     * [songs] as a page lists them: in a custom order with handles when [moves] is given, each move kept there and made
     * on the list as playlists.json would; else a plain list.
     */
    @Composable
    private fun Rows(songs: List<Song>, moves: MutableList<List<SongRef>>?) {
        var stored by remember { mutableStateOf(songs.map { it.ref }) }
        val ui = LibraryUi(LibraryState.Ready(Library.of(songs, Settings())), Settings(), PlaylistsState(), queues = emptyList(), ledger = LedgerState())
        val rows = rememberLazyListState()
        val ordered = remember { OrderedEntries() }
        val (entries, keys) = remember(stored, ordered.version) { ordered.of(stored) }
        val shown = entries.map { ref -> songs.first { it.ref == ref } }
        val reorder = rememberSongReorder(rows, ordered, { shown.indices.toList() }) { order ->
            moves?.add(order)
            stored = arranged(stored, order, SongRef::path)
            null
        }
        SongListFrame(ui, shown, QueueSource(SourceKind.PLAYLIST, "road-trip", "Road trip"), state = rows, topBar = { _, _ -> }) { list ->
            songRows(list, keys = keys.map { "entry $it" }, reorder = reorder.takeIf { moves != null })
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
