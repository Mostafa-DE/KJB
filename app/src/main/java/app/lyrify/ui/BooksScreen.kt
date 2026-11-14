package app.lyrify.ui

import android.content.Context
import android.content.res.Resources
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import app.lyrify.R
import app.lyrify.books.BookState
import app.lyrify.books.BooksGraph
import app.lyrify.library.Folder
import app.lyrify.library.Library
import kotlinx.coroutines.launch

// Audiobook mode on screen (features 2026-11-15, item 13): "Mark as book" and "Not a book" in the song and folder menus,
// the Books tab with its Continue row and cards, the chapter sheet, and the player's book parts.

/** A book row of the song or folder menu acted on: [change] to `books.json`, then its toast, whose Undo puts the marks back. */
internal suspend fun ToastState.markBooks(context: Context, resources: Resources, marked: Boolean, change: (BookState) -> BookState) {
    val store = BooksGraph.store(context)
    val before = store.state.value.marks
    store.change(change)
    val undo = show(Toast(resources.getString(if (marked) R.string.book_marked else R.string.book_unmarked), R.drawable.ic_auto_stories, action = resources.getString(R.string.player_undo)))
    if (undo) store.change { it.copy(marks = before) }
}

/** A folder page's "⋯": "Mark as book", or "Not a book" while one of its own files is in a book. */
@Composable
internal fun BookFolderMenu(folder: Folder, library: Library) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val toasts = LocalToasts.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    val holdsBook = remember(folder, library) { folder.songs.any { library.bookOf(it.path) != null } }
    Box {
        ChromeIconButton(R.drawable.ic_more_horiz, stringResource(R.string.book_folder_menu), { open = true })
        LyrifyMenu(expanded = open, onDismiss = { open = false }) {
            MenuRow(stringResource(if (holdsBook) R.string.book_not else R.string.book_mark), R.drawable.ic_auto_stories, onClick = {
                open = false
                val own = folder.songs.map { it.path }
                scope.launch { toasts.markBooks(context, resources, marked = !holdsBook) { it.toggledFolder(folder.path, own, holdsBook) } }
            })
        }
    }
}
