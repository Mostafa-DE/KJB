package app.lyrify.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lyrify.R
import app.lyrify.books.Book
import app.lyrify.library.Library
import app.lyrify.library.Song
import app.lyrify.library.SongRef
import app.lyrify.player.Bookmark
import app.lyrify.player.PlayerExtras
import app.lyrify.player.SongMark
import app.lyrify.player.SongState

// Tools > Bookmarks (BM1): every bookmark and its note, by book, episode or song, to find a spot again. The lists and
// the note dialog are the player's own (PlayerSheets.kt).

// What the page shows

/**
 * A group of Tools > Bookmarks: the bookmarks of one [book] (every part's, in the book's order), or of one song or
 * episode ([song]; null for a file the library does not list), keyed by [key].
 */
data class BookmarkGroup(val key: String, val book: Book?, val song: Song?, val marks: List<SongMark>)

/**
 * Every bookmark of [state] once, by book or song as [library] has them, the group with the newest bookmark first: by
 * when each was made, then by id, so those made before KJB kept the time ([Bookmark.addedAt] 0) follow, the latest
 * made first.
 */
fun bookmarkGroups(state: SongState, library: Library): List<BookmarkGroup> {
    val newest = compareBy(Bookmark::addedAt, Bookmark::id)
    return state.bookmarks.keys
        .map { path -> library.bookOf(path) to path }
        .distinctBy { (book, path) -> book?.id ?: path }
        .map { (book, path) -> BookmarkGroup(book?.id ?: path, book, if (book == null) library.resolver.find(SongRef(path, state.ids[path])) else null, state.marksOf(path, book)) }
        .sortedWith(compareByDescending(newest) { group -> group.marks.map(SongMark::mark).maxWith(newest) })
}

// The page

/**
 * Tools > Bookmarks: its own top bar with a back arrow over a card per book, episode or song ([bookmarkGroups]), each
 * with its bookmarks: a tap plays from there and opens the player (a Tools page has no Now bar), the pencil changes the
 * note, × removes it with Undo. Says how to add one while there are none.
 */
@Composable
fun BookmarksPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val nav = LocalNavigator.current
    val extras = remember(context) { PlayerExtras.of(context) }
    val state by extras.state.collectAsStateWithLifecycle()
    val ready by extras.ready.collectAsStateWithLifecycle()
    val ui = rememberLibraryUi()
    val library = ui?.library
    // Null until the bookmarks and the library are read.
    val groups = remember(state, ready, library) { library?.takeIf { ready }?.let { bookmarkGroups(state, it) } }
    PageFrame(
        topBar = { under, backdrop ->
            LyrifyTopBar(
                title = { Text(stringResource(R.string.bookmarks_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                under = under,
                backdrop = backdrop,
                navigation = { ChromeIconButton(R.drawable.ic_arrow_back, stringResource(R.string.check_back), onBack) },
            )
        },
    ) { padding ->
        FadeThrough(groups, Modifier.fillMaxSize(), kind = { it?.isEmpty() }) { shown ->
            when {
                shown == null || ui == null -> Unit
                shown.isEmpty() -> CenteredState(padding) {
                    EmptyState(EmptyArt.Icon(R.drawable.ic_bookmark), stringResource(R.string.tools_bookmarks_none), text = stringResource(R.string.bookmarks_empty_text))
                }
                else -> {
                    val reduced = LocalReducedMotion.current
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 16.dp)) {
                        // A card per group: its cover, title and author or artist (a heading for TalkBack), then its list.
                        items(shown, key = { "group ${it.key}" }, contentType = { "group" }) { group ->
                            val cover = group.book?.parts?.first()?.ref ?: group.marks.first().song
                            val title = group.book?.title ?: group.song?.let(ui::title) ?: cover.path.substringAfterLast('/')
                            val line = group.book?.author ?: group.song?.artist
                            LyrifyCard(rowMotion(reduced).fillMaxWidth().padding(horizontal = ScreenPadding, vertical = 4.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                                    modifier = Modifier.semantics(mergeDescendants = true) { heading() }.padding(start = 14.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
                                ) {
                                    SongCover(SongArt(cover.path, cover.mediaStoreId), size = 48.dp)
                                    Column(Modifier.weight(1f)) {
                                        Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        line?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                    }
                                }
                                Column(Modifier.padding(start = 4.dp, end = 4.dp, bottom = 8.dp)) {
                                    BookmarkList(group.marks, group.book) { at ->
                                        jumpTo(context, at.song, at.mark.positionMs)
                                        nav.show(FullScreen.Player)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
