package app.lyrify.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lyrify.R
import app.lyrify.settings.DockTab

// Library's pages. Milestone L1 replaces these bodies; the shell calls them as they are declared here.

/**
 * Library, the first tab, at its root. Until L1: the songs KJB knows (the ledger) with their lyrics status, sorted
 * from the top bar; tapping Library again goes to the top.
 */
@Composable
fun LibraryPage() {
    val nav = LocalNavigator.current
    var query by rememberSaveable(stateSaver = SongQuery.Saver) { mutableStateOf(SongQuery()) }
    var sortMenu by remember { mutableStateOf(false) }
    PageFrame(
        topBar = { under, backdrop ->
            LyrifyTopBar(
                title = { Text(stringResource(R.string.nav_library), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                under = under,
                backdrop = backdrop,
                actions = {
                    Box {
                        ChromeIconButton(R.drawable.ic_sort, stringResource(R.string.sort_cd), { sortMenu = true })
                        SortMenu(sortMenu, query.sort, onDismiss = { sortMenu = false }) {
                            sortMenu = false
                            query = query.copy(sort = it)
                        }
                    }
                },
            )
        },
    ) { padding ->
        LedgerSongs(query, { query = it }, reselect = nav.state.reselectsOf(DockTab.LIBRARY), Modifier.padding(padding).consumeWindowInsets(padding))
    }
}

/** Library > Songs with a lyrics filter, from the AI's results. Until L1: the ledger's songs with that status. */
@Composable
fun SongsPage(page: Page.Songs) {
    val nav = LocalNavigator.current
    var query by rememberSaveable(stateSaver = SongQuery.Saver) { mutableStateOf(SongQuery(group = page.group, status = page.status)) }
    PageFrame(
        topBar = { under, backdrop ->
            LyrifyTopBar(
                title = { Text(stringResource(R.string.songs_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                under = under,
                backdrop = backdrop,
                navigation = { ChromeIconButton(R.drawable.ic_arrow_back, stringResource(R.string.check_back), { nav.back() }) },
            )
        },
    ) { padding ->
        LedgerSongs(query, { query = it }, reselect = 0, Modifier.padding(padding).consumeWindowInsets(padding))
    }
}

/** Settings > Folders to skip: the library's folders, each with a switch (L1). */
@Composable
fun FoldersToSkipPage() {
    val nav = LocalNavigator.current
    PageStub("L1") { nav.back() }
}

/** 2.0's song list over the ledger, with its status bar and chips, for Library and Search until L1 and L2. */
@Composable
internal fun LedgerSongs(query: SongQuery, onQuery: (SongQuery) -> Unit, reselect: Int, modifier: Modifier) {
    val nav = LocalNavigator.current
    val lyrics = LocalLyrics.current
    Box(modifier.fillMaxSize().imePadding()) {
        SongsScreen(
            ledger = lyrics.ledger,
            query = query,
            onQuery = onQuery,
            timing = lyrics.timing,
            removal = lyrics.removal,
            retrying = lyrics.retrying,
            connected = lyrics.connected,
            reselect = reselect,
            onRetryFailed = lyrics.onRetryFailed,
            onPickLyrics = { nav.open(Page.Check(it)) },
            banner = { LyricsBanners(lyrics, Modifier.padding(start = ScreenPadding, end = ScreenPadding, bottom = 8.dp), listener = false) },
        )
    }
}
