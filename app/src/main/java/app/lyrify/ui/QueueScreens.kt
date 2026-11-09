package app.lyrify.ui

import androidx.compose.runtime.Composable

// Queue pages. Milestone P1 replaces this body; the shell calls it as it is declared here.

/** Queues (current, Recent and Saved), or one queue's page (P1). */
@Composable
fun QueuePage(page: Page.QueuePages) {
    val nav = LocalNavigator.current
    PageStub("P1") { nav.back() }
}
