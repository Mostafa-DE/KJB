package app.lyrify.ui

import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lyrify.Lyrify
import app.lyrify.R
import app.lyrify.backup.M3u
import app.lyrify.data.LedgerState
import app.lyrify.library.Library
import app.lyrify.library.LibraryState
import app.lyrify.library.Playlist
import app.lyrify.library.PlaylistsState
import app.lyrify.settings.DockTab
import app.lyrify.settings.LibrarySettings
import app.lyrify.settings.LibraryTab
import app.lyrify.settings.Settings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * m3u on the phone (B1 review): Export all and Import are in the Playlists tab's "⋯" and Export in a playlist's (8f8fe6b),
 * and a picked file's result is still in the import sheet after the activity is made again (80f40b3). The system's
 * pickers are stood in for, so nothing is opened, read or written outside the app. Made-up names only.
 */
@RunWith(AndroidJUnit4::class)
class M3uScreensDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun thePlaylistsTabsMenuHasExportAllAndImportAndTheTabShowsWhatWasPicked() {
        // The Library page opens on the tab its settings name; the debug app's own settings are put back after.
        val settings = Lyrify.settings(context)
        val before = runBlocking { settings.settings.first() }
        assertTrue(runBlocking { settings.update { it.copy(library = it.library.copy(tabs = LibrarySettings.DEFAULT_TABS), nav = it.nav.copy(libraryTab = LibraryTab.PLAYLISTS)) } })
        try {
            val pickers = FakePickers(picked = listOf(ROAD_TRIP))
            compose.setContent { Shell(pickers) { LibraryPage() } }
            val more = context.getString(R.string.menu_more)
            compose.waitUntil("the Playlists tab's top bar", TIMEOUT_MS) { compose.onAllNodesWithContentDescription(more).fetchSemanticsNodes().isNotEmpty() }

            compose.onNodeWithContentDescription(more).performClick()
            compose.onNodeWithText(context.getString(R.string.m3u_export_all)).assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.m3u_import)).performClick()

            compose.waitUntil("the import sheet on the Playlists tab", TIMEOUT_MS) {
                compose.onAllNodesWithText(context.getString(R.string.m3u_untitled)).fetchSemanticsNodes().isNotEmpty()
            }
            val (contract, input) = pickers.launched.single()
            assertTrue(contract is ActivityResultContracts.OpenMultipleDocuments)
            assertArrayEquals(M3u.MIME_TYPES, input as Array<*>)
        } finally {
            runBlocking { settings.update { before } }
        }
    }

    @Test
    fun aPlaylistsMenuHasExport() {
        val playlist = Playlist("road-trip", "Road Trip", songs = emptyList(), createdAt = 1_000)
        val library = Library.of(emptyList(), Settings())
        val ui = LibraryUi(LibraryState.Ready(library), Settings(), PlaylistsState(listOf(playlist)), queues = emptyList(), ledger = LedgerState())
        val pickers = FakePickers()
        compose.setContent { Shell(pickers) { PlaylistPage(ui, library, playlist.id) } }

        compose.onNodeWithContentDescription(context.getString(R.string.lib_more_playlist)).performClick()
        compose.onNodeWithText(context.getString(R.string.m3u_export)).performClick()

        compose.runOnIdle {
            // A debug build exports one playlist into a folder it checks, a release build through the save dialog.
            val contract = pickers.launched.single().first
            if (M3u.exportsByFolder(Lyrify.writes)) {
                assertTrue(contract is ActivityResultContracts.OpenDocumentTree)
            } else {
                assertTrue(contract is ActivityResultContracts.CreateDocument)
            }
        }
    }

    @Test
    fun aPickedFilesResultIsInTheSheetAgainAfterTheActivityIsMadeAgain() {
        val pickers = FakePickers(picked = listOf(ROAD_TRIP))
        val restoration = StateRestorationTester(compose)
        var m3u: M3uActions? = null
        restoration.setContent {
            Shell(pickers) {
                val actions = rememberM3uActions()
                m3u = actions
                M3uImportSheet(actions)
            }
        }
        val untitled = context.getString(R.string.m3u_untitled)

        compose.runOnIdle { m3u!!.import() }
        compose.waitUntil("the picked file's result in the sheet", TIMEOUT_MS) { compose.onAllNodesWithText(untitled).fetchSemanticsNodes().isNotEmpty() }
        restoration.emulateSavedInstanceStateRestore()

        compose.waitUntil("the picked file's result in the sheet again", TIMEOUT_MS) { compose.onAllNodesWithText(untitled).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(context.getString(R.string.m3u_not_m3u)).assertExists()
        assertEquals(1, pickers.launched.size)
    }

    /** The shell's locals a page needs: a navigator, lyrics, messages, and [pickers] for the system's dialogs. */
    @Composable
    private fun Shell(pickers: FakePickers, content: @Composable () -> Unit) {
        val nav = remember { Navigator(NavState.start(DockTab.LIBRARY)) }
        val toasts = remember { ToastState() }
        CompositionLocalProvider(
            LocalNavigator provides nav,
            LocalLyrics provides shellLyrics(),
            LocalToasts provides toasts,
            LocalActivityResultRegistryOwner provides pickers,
            content = content,
        )
    }

    /** The system's pickers stood in for: each launch is kept, with its input, and the open dialog answers [picked]. */
    private class FakePickers(private val picked: List<Uri> = emptyList()) : ActivityResultRegistry(), ActivityResultRegistryOwner {
        val launched = mutableListOf<Pair<ActivityResultContract<*, *>, Any?>>()

        override val activityResultRegistry: ActivityResultRegistry get() = this

        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            launched += contract to input
            if (contract is ActivityResultContracts.OpenMultipleDocuments && picked.isNotEmpty()) {
                Handler(Looper.getMainLooper()).post { dispatchResult(requestCode, picked) }
            }
        }
    }

    private companion object {
        const val TIMEOUT_MS: Long = 10_000

        /** A picked file no app on the phone has, so it reads as not an m3u: a result all the same, under the stand-in name. */
        val ROAD_TRIP: Uri = Uri.parse("content://app.lyrify.test.none/document/Road%20Trip.m3u8")
    }
}
