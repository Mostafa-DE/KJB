package app.lyrify.ui

import androidx.compose.runtime.Composable

// Collection pages. Milestone L1 replaces this body; the shell calls it as it is declared here.

/** An album, artist, folder, genre or playlist page, Built for you, Unknown album, or a Favourites section (L1). */
@Composable
fun CollectionPage(page: Page.CollectionPages) {
    val nav = LocalNavigator.current
    PageStub("L1") { nav.back() }
}
