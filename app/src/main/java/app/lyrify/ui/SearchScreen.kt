package app.lyrify.ui

import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import app.lyrify.settings.DockTab

// Search's pages. Milestone L2 replaces these bodies; the shell calls them as they are declared here.

/**
 * Search, the second tab, at its root: the field at the top, focused when the tab opens and when Search is tapped
 * again. Until L2: 2.0's text filter over the songs KJB knows.
 */
@Composable
fun SearchPage() {
    val nav = LocalNavigator.current
    var query by rememberSaveable(stateSaver = SongQuery.Saver) { mutableStateOf(SongQuery()) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val reselects = nav.state.reselectsOf(DockTab.SEARCH)
    LaunchedEffect(reselects) {
        // The field attaches its requester in the frame the tab shows.
        withFrameNanos { }
        try {
            focus.requestFocus()
            keyboard?.show()
        } catch (e: IllegalStateException) {
            // Not attached yet: the user taps the field.
        }
    }
    PageFrame(topBar = { under, backdrop -> SongSearchBar(query, { query = it }, focus, onSettings = null, under = under, backdrop = backdrop) }) { padding ->
        LedgerSongs(query, { query = it }, reselect = reselects, Modifier.padding(padding).consumeWindowInsets(padding))
    }
}

/** "View all" of one group of results (L2). */
@Composable
fun SearchAllPage(page: Page.SearchAll) {
    val nav = LocalNavigator.current
    PageStub("L2") { nav.back() }
}
