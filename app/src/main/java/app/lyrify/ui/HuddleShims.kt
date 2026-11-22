package app.lyrify.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.lyrify.R

// STAND-IN, to DELETE when lane dl2 (v4/dl2-*) is merged: dl2's YouTubeSearchSheet (ui/DownloadsScreen.kt), YouTube's
// search for Huddle's "Search on YouTube" (ui/HuddleSheets.kt). Until then it only says what would be searched for. Left
// in after the merge, the build fails on the two declarations of one function, which is how it is found.

@Composable
fun YouTubeSearchSheet(query: String, onDismiss: () -> Unit, onStarted: () -> Unit) {
    HelpSheet(stringResource(R.string.huddle_youtube), query, onDismiss = onDismiss)
}
