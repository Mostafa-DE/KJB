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
import app.lyrify.settings.Settings
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * m3u on the phone (B1 review): Export all and Import are in the Playlists tab's "⋯" and Export in a playlist's (8f8fe6b),
 * and Import hands the picked files to Import from app's sheet (FU5). The system's pickers are stood in for, so nothing
 * is opened, read or written outside the app. Made-up names only.
 */
@RunWith(AndroidJUnit4::class)
class M3uScreensDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun thePlaylistsTabsMenuHasExportAllAndImportAndImportFromAppsSheetShowsWhatWasPicked() {
        val pickers = FakePickers(picked = listOf(ROAD_TRIP))
        compose.setContent {
            Shell(pickers) {
                PlaylistsPage()
                // Import from app's sheet, as the shell's backup actions place it.
                rememberImportFromApp()
            }
        }
        val more = context.getString(R.string.menu_more)
        compose.waitUntil("the Playlists tab's top bar", TIMEOUT_MS) { compose.onAllNodesWithContentDescription(more).fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithContentDescription(more).performClick()
        compose.onNodeWithText(context.getString(R.string.m3u_export_all)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.m3u_import)).performClick()

        compose.waitUntil("Import from app's sheet with the picked file", TIMEOUT_MS) {
            compose.onAllNodesWithText(context.getString(R.string.import_unreadable)).fetchSemanticsNodes().isNotEmpty()
        }
        val (contract, input) = pickers.launched.single()
        assertTrue(contract is ActivityResultContracts.OpenMultipleDocuments)
        assertArrayEquals(M3u.MIME_TYPES, input as Array<*>)
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

        /** A picked file no app on the phone has, so Import from app's sheet says it cannot read it. */
        val ROAD_TRIP: Uri = Uri.parse("content://app.lyrify.test.none/document/Road%20Trip.m3u8")
    }
}
