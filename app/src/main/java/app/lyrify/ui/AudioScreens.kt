package app.lyrify.ui

import androidx.compose.runtime.Composable

// The equalizer. Milestone A1 replaces this body; the shell calls it as it is declared here.

/** The equalizer, from the player's "⋯" (A1). */
@Composable
fun EqualizerView() {
    val nav = LocalNavigator.current
    PageStub("A1") { nav.close(FullScreen.Equalizer) }
}
