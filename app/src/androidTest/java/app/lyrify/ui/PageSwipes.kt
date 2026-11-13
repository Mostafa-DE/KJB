package app.lyrify.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * The system's gesture edges (back) on the phone running the test, left and right in pixels, as the pages read them:
 * a finger landing there is the system's, and the pages ignore it on purpose (none with 3-button navigation).
 */
class GestureEdges {
    var left = 0
    var right = 0
}

/** Reads the window's gesture edges into [edges], where the test composes its pages. */
@Composable
fun ReadGestureEdges(edges: GestureEdges) {
    val gestures = WindowInsets.systemGestures
    val density = LocalDensity.current
    edges.left = gestures.getLeft(density, LayoutDirection.Ltr)
    edges.right = gestures.getRight(density, LayoutDirection.Ltr)
}

/**
 * A swipe across the pages at [y] (by default low on the node), from one side toward the other, starting and ending a
 * little inside the system's gesture [edges], so the pages take it on any phone, with either navigation.
 */
fun TouchInjectionScope.swipeAcross(edges: GestureEdges, toNext: Boolean, y: Float = height * 0.6f, durationMillis: Long = SWIPE_MS) {
    val clear = CLEAR_OF_EDGE.toPx()
    val near = left + edges.left + clear
    val far = right - edges.right - clear
    swipe(Offset(if (toNext) far else near, y), Offset(if (toNext) near else far, y), durationMillis)
}

/** How far inside a gesture edge a swipe across the pages starts. */
private val CLEAR_OF_EDGE = 16.dp

/** A plain swipe across the pages: Compose's own swipeLeft and swipeRight take this long. */
private const val SWIPE_MS = 200L
