package app.lyrify.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalAccessibilityManager
import app.lyrify.R
import app.lyrify.downloads.ShareLinks

/**
 * "Save to KJB" in other apps' share sheets: a small sheet over the sharing app ([SaveSheet]), with the shared text's first
 * link. It shows the link's quick info at once and its full info as it comes; Audio or Video starts the download in
 * KJB's foreground service and the sheet closes, back to the app the user was in. A text with no link says so and
 * closes. Its own task, never in recents, so it never brings KJB's main screen up over the sharing app.
 */
class SaveToKjbActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val url = links(intent).firstOrNull()
        if (url == null) {
            Toast.makeText(this, R.string.dl_no_link, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        setContent {
            LyrifyTheme {
                val a11y = LocalAccessibilityManager.current
                CompositionLocalProvider(LocalToasts provides remember { ToastState(a11y) }) {
                    SaveSheet(url, onDismiss = ::finish) { Toast.makeText(this, R.string.dl_started, Toast.LENGTH_SHORT).show() }
                }
            }
        }
    }

    /** The links in what was shared ([ShareLinks.fromShare]: a share only, its first few kilobytes). */
    private fun links(intent: Intent?): List<String> =
        ShareLinks.fromShare(intent?.action, intent?.getCharSequenceExtra(Intent.EXTRA_TEXT), intent?.getCharSequenceExtra(Intent.EXTRA_SUBJECT))
}
