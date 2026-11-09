package app.lyrify.ui

import androidx.compose.runtime.Composable

// Lyrics on the player. Milestone P3 replaces this body; the shell calls it as it is declared here.

/** Full lyrics, over the player (P3). */
@Composable
fun FullLyricsView() {
    val nav = LocalNavigator.current
    PageStub("P3") { nav.close(FullScreen.FullLyrics) }
}
