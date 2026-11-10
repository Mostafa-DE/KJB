package app.lyrify.ui

import androidx.compose.runtime.Composable

// Edit tags, Edit album and the edit History. Milestone T3 replaces these bodies; the shell calls them as they are
// declared here.

/** Edit tags for one song or many, over everything (T3). */
@Composable
fun EditTagsView(view: FullScreen.EditTags) {
    val nav = LocalNavigator.current
    PageStub("T3") { nav.close(view) }
}

/** Edit album, over everything (T3). */
@Composable
fun EditAlbumView(view: FullScreen.EditAlbum) {
    val nav = LocalNavigator.current
    PageStub("T3") { nav.close(view) }
}

/** Tools > Covers & tags > History: every edit, each with Undo (the list is Covers & tags', T2). */
@Composable
fun TagHistoryPage() {
    val nav = LocalNavigator.current
    TagHistoryList { nav.back() }
}
