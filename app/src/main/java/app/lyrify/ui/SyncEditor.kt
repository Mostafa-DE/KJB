package app.lyrify.ui

import androidx.compose.runtime.Composable

// The synced lyrics editor. Milestone P3 replaces this body; the shell calls it as it is declared here.

/** Make synced or Edit timing, over everything (P3). */
@Composable
fun SyncEditorView(view: FullScreen.SyncEditor) {
    val nav = LocalNavigator.current
    PageStub("P3") { nav.close(view) }
}
