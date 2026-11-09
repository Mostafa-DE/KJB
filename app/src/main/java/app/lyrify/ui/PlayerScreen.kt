package app.lyrify.ui

import androidx.compose.runtime.Composable

// The player. Milestone P1 replaces these bodies; the shell calls them as they are declared here.

/** The player, over everything (P1). */
@Composable
fun PlayerView() {
    val nav = LocalNavigator.current
    PageStub("P1") { nav.close(FullScreen.Player) }
}

/** KJB's own player for the Now bar: what is loaded and its Play/Pause (P1). Nothing is loaded until then. */
@Composable
fun rememberOwnNow(): OwnNowSource = NothingLoaded
