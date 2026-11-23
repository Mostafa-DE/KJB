package app.lyrify.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lyrify.R
import app.lyrify.data.LedgerEntry
import app.lyrify.data.LedgerState
import app.lyrify.data.LyricsStatus
import app.lyrify.settings.DockTab
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tools on the phone: "Retry" for failed lookups is there with no session loaded at all (M1 review, 9ea3753); before, only
 * Companion Now had it, and that shows only while a session is loaded. Made-up names only.
 */
@RunWith(AndroidJUnit4::class)
class ToolsScreenDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun retryFailedIsOnToolsWithNoSessionLoaded() {
        val failed = listOf("Paper Lantern", "Low Water", "Salt Road").fold(LedgerState()) { ledger, title ->
            ledger.put(LedgerEntry(path = "/storage/emulated/0/Music/Harbour Glass/$title.mp3", title = title, durationMs = 200_000, status = LyricsStatus.ERROR, attempts = 1, updatedAt = 1_000))
        }
        var retried = 0
        compose.setContent {
            // ToolsScreen takes no track or session: the shell draws it whatever plays, here with nothing at all. Its header's
            // Huddle opens with the shell's navigator (HH1).
            CompositionLocalProvider(
                LocalLyrics provides shellLyrics(failed, onRetryFailed = { retried++ }),
                LocalNavigator provides remember { Navigator(NavState.start(DockTab.TOOLS)) },
            ) {
                ToolsScreen(rows = emptyList(), onOpen = {})
            }
        }

        compose.onNodeWithText(context.resources.getQuantityString(R.plurals.retry_title, 3, 3)).assertExists()
        compose.onNodeWithText(context.getString(R.string.retry_failed)).assertIsEnabled().performClick()

        compose.runOnIdle { assertEquals(1, retried) }
    }
}

/** Lyrics as the shell gives every page: [ledger], the listener [connected] and nothing being retried. */
internal fun shellLyrics(ledger: LedgerState = LedgerState(), connected: Boolean = true, onRetryFailed: () -> Unit = {}): LyricsUi = LyricsUi(
    ledger = ledger,
    timing = PlainTiming.NO_AI,
    removal = RemovalState(leftOnDisk = { emptySet() }, remove = {}),
    connected = connected,
    retrying = false,
    onRetryFailed = onRetryFailed,
    notWatching = false,
    onOpenNotificationAccess = {},
)
