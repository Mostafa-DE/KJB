package app.lyrify.ui

import androidx.compose.runtime.Composable

// Your listening. Milestone S1 replaces this body; the shell calls it as it is declared here.

/** Your listening, or the "View all" of one of its top lists (S1). */
@Composable
fun ListeningPage(page: Page.ListeningPages) {
    val nav = LocalNavigator.current
    PageStub("S1") { nav.back() }
}
