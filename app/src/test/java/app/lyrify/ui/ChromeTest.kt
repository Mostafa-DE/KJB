package app.lyrify.ui

import android.os.Build
import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import app.lyrify.R
import app.lyrify.settings.DockTab
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChromeTest {
    // The toast queue

    @Test
    fun `messages show one at a time, in the order they were asked for`() = runTest {
        val toasts = ToastState()
        val first = async { toasts.show(Toast("Synced lyrics added")) }
        val second = async { toasts.show(Toast("Lyrics removed")) }
        runCurrent()
        assertEquals("Synced lyrics added", toasts.current?.toast?.text)
        toasts.current?.dismiss()
        runCurrent()
        assertEquals("Lyrics removed", toasts.current?.toast?.text)
        assertFalse(first.isActive)
        toasts.current?.dismiss()
        runCurrent()
        assertNull(toasts.current)
        assertFalse(second.isActive)
    }

    @Test
    fun `the caller hears whether the message's action was tapped`() = runTest {
        val toasts = ToastState()
        val acted = async { toasts.show(Toast("Song moved to trash", action = "Undo")) }
        runCurrent()
        toasts.current?.dismiss(acted = true)
        assertTrue(acted.await())
        val timedOut = async { toasts.show("Tone deleted") }
        runCurrent()
        toasts.current?.dismiss()
        assertFalse(timedOut.await())
    }

    @Test
    fun `a message whose caller went away leaves the screen`() = runTest {
        val toasts = ToastState()
        val shown = async { toasts.show("Tone deleted") }
        runCurrent()
        shown.cancel()
        runCurrent()
        assertNull(toasts.current)
    }

    @Test
    fun `the newest slot on screen shows the message, and the one under it takes over when it leaves`() {
        val toasts = ToastState()
        val shell = Any()
        val sheet = Any()
        toasts.register(shell)
        assertTrue(toasts.showsIn(shell))
        toasts.register(sheet)
        assertFalse(toasts.showsIn(shell))
        assertTrue(toasts.showsIn(sheet))
        toasts.unregister(sheet)
        assertTrue(toasts.showsIn(shell))
        toasts.unregister(shell)
        assertFalse(toasts.showsIn(shell))
    }

    @Test
    fun `without the app's ToastState a debug build fails loud`() {
        assertThrows(IllegalStateException::class.java) { missingToasts(debug = true) }
    }

    @Test
    fun `without it a release build drops a message at once instead of waiting for ever`() = runTest {
        val toasts = missingToasts(debug = false)
        assertEquals(false, withTimeoutOrNull(1_000) { toasts.show("Tone deleted") })
        assertNull(toasts.current)
    }

    @Test
    fun `a message stays 4 seconds, 6 with an action or two lines`() {
        assertEquals(4_000L, toastMillis(Toast("Tone deleted")))
        assertEquals(6_000L, toastMillis(Toast("Tone deleted", action = "Undo")))
        assertEquals(6_000L, toastMillis(Toast("Couldn't open your Clock app. Open it from your home screen.")))
    }

    @Test
    fun `messages gain an icon and a tone where one fits, failures in the error tone`() {
        assertEquals(Toast("a", R.drawable.ic_lyrics, Tone.SYNCED), messageToast(R.string.check_done_synced, "a"))
        assertEquals(Toast("a", R.drawable.ic_delete, Tone.NONE), messageToast(R.string.removal_done, "a"))
        assertEquals(Toast("a", R.drawable.ic_delete, Tone.NONE), messageToast(R.plurals.dupes_moved, "a"))
        assertEquals(Toast("a", R.drawable.ic_restore_from_trash, Tone.SYNCED), messageToast(R.string.dupes_restored, "a"))
        assertEquals(Toast("a", R.drawable.ic_sync_problem, Tone.ERROR), messageToast(R.string.check_not_saved, "a"))
        listOf(R.string.removal_failed, R.string.dupes_trash_failed, R.string.tones_not_saved, R.string.tones_clock_not_opened).forEach {
            assertEquals(Toast("a", R.drawable.ic_error, Tone.ERROR), messageToast(it, "a"))
        }
        assertEquals(Toast("a"), messageToast(R.string.check_changed, "a"))
    }

    // The dock

    @Test
    fun `the dock has four destinations, Library, Playlists, Search and Tools`() {
        assertEquals(listOf(DockTab.LIBRARY, DockTab.PLAYLISTS, DockTab.SEARCH, DockTab.TOOLS), DOCK_TABS)
        assertEquals(listOf(R.string.nav_library, R.string.nav_playlists, R.string.nav_search, R.string.nav_tools), DOCK_TABS.map { it.label })
        // Unit tests run in the module's folder.
        val strings = java.io.File("src/main/res/values/strings.xml").readText()
        assertTrue("""<string name="nav_playlists">Playlists</string>""" in strings)
    }

    @Test
    fun `Tools gets a dot when Check these, Duplicates or the AI need the user`() {
        assertFalse(toolsDot(checks = 0, duplicates = 0, aiAttention = false))
        assertTrue(toolsDot(checks = 3, duplicates = 0, aiAttention = false))
        assertTrue(toolsDot(checks = 0, duplicates = 4, aiAttention = false))
        assertTrue(toolsDot(checks = 0, duplicates = 0, aiAttention = true))
    }

    @Test
    fun `a dock item's name folds in its dot`() {
        assertEquals("Tools, Something needs you", dockItemName("Tools", "Something needs you"))
        assertEquals("Library", dockItemName("Library", null))
    }

    @Test
    fun `a new tab slides in from the side of the tab tapped`() {
        assertEquals(1, tabSlide(DockTab.LIBRARY, DockTab.SEARCH))
        assertEquals(-1, tabSlide(DockTab.TOOLS, DockTab.LIBRARY))
        assertEquals(0, tabSlide(DockTab.SEARCH, DockTab.SEARCH))
        // Playlists sits between Library and Search.
        assertEquals(1, tabSlide(DockTab.LIBRARY, DockTab.PLAYLISTS))
        assertEquals(-1, tabSlide(DockTab.SEARCH, DockTab.PLAYLISTS))
        assertEquals(1, tabSlide(DockTab.PLAYLISTS, DockTab.TOOLS))
    }

    // Dialogs

    @Test
    fun `a dialog's buttons read Cancel first, then the confirm, stacked or side by side`() {
        assertEquals(listOf(DialogButton.DISMISS, DialogButton.CONFIRM), DIALOG_BUTTONS)
    }

    // Foundations

    @Test
    fun `contrast is the WCAG ratio`() {
        assertEquals(21f, contrast(Color.Black, Color.White), 0.01f)
        assertEquals(21f, contrast(Color.White, Color.Black), 0.01f)
        assertEquals(1f, contrast(Color.White, Color.White), 0.001f)
    }

    @Test
    fun `glass keeps text at 4_5 to 1 and the coral at 3 to 1 over the brightest or the darkest content, light and dark`() {
        listOf(Triple(LightColors, LightBrandColors, false), Triple(DarkColors, DarkBrandColors, true)).forEach { (scheme, brand, dark) ->
            val chrome = chromeColors(scheme, brand, dark)
            // The worst content for the glass: white under dark glass, black under light glass.
            val worst = if (dark) Color.White else Color.Black
            listOf(chrome.glass, chrome.glassSolid).forEach { glass ->
                val tint = glass.compositeOver(worst)
                val name = "dark $dark, glass ${glass.alpha}"
                assertTrue("$name: muted text ${contrast(scheme.onSurfaceVariant, tint)}", contrast(scheme.onSurfaceVariant, tint) >= 4.5f)
                assertTrue("$name: text ${contrast(scheme.onSurface, tint)}", contrast(scheme.onSurface, tint) >= 4.5f)
                assertTrue("$name: coral ${contrast(brand.highlight, tint)}", contrast(brand.highlight, tint) >= 3f)
            }
            assertTrue(chrome.glass.alpha >= 0.72f)
        }
    }

    @Test
    fun `glass is no more opaque than it needs to be`() {
        assertEquals(0.72f, glassAlpha(Color.White, text = Color.Black, mark = Color.Black, behind = Color.Black), 0.001f)
        assertEquals(1f, glassAlpha(Color.Gray, text = Color.Gray, mark = Color.Gray, behind = Color.White), 0.001f)
    }

    @Test
    fun `glass blurs on Android 12 and later, not in Battery Saver or with raised contrast`() {
        assertTrue(glassBlurs(Build.VERSION_CODES.S, powerSave = false, contrast = 0f))
        assertFalse(glassBlurs(Build.VERSION_CODES.R, powerSave = false, contrast = 0f))
        assertFalse(glassBlurs(Build.VERSION_CODES.UPSIDE_DOWN_CAKE, powerSave = true, contrast = 0f))
        assertFalse(glassBlurs(Build.VERSION_CODES.UPSIDE_DOWN_CAKE, powerSave = false, contrast = 0.5f))
    }

    @Test
    fun `with reduced motion every motion snaps and nothing squashes`() {
        assertTrue(motion(Motion.indicator, reduced = true) is SnapSpec)
        assertTrue(motion(tween<Float>(180), reduced = true) is SnapSpec)
        assertSame(Motion.indicator, motion(Motion.indicator, reduced = false))
        assertEquals(1f, pressScale(0.92f, reduced = true))
        assertEquals(0.92f, pressScale(0.92f, reduced = false))
    }
}
