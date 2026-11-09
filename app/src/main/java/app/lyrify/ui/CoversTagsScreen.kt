package app.lyrify.ui

import androidx.compose.runtime.Composable

// Covers & tags. Milestone T2 replaces these bodies; the shell and Tools call them as they are declared here.

/** Tools > Covers & tags (T2). */
@Composable
fun CoversTagsPage() {
    val nav = LocalNavigator.current
    PageStub("T2") { nav.back() }
}

/** Covers & tags' status line on its Tools row (T2); none until then. */
@Composable
fun coversTagsLine(): ToolLine? = null
