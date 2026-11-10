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
import kotlinx.coroutines.launch
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
    // Toasts: the newest replaces the one on screen

    @Test
    fun `a newer message replaces the one on screen at once, never waiting behind it`() = runTest {
        val toasts = ToastState()
        val first = async { toasts.show(Toast("Added to a made-up list", action = "Undo")) }
        runCurrent()
        assertEquals("Added to a made-up list", toasts.current?.toast?.text)
        val second = async { toasts.show(Toast("Shuffle on")) }
        runCurrent()
        assertEquals("Shuffle on", toasts.current?.toast?.text)
        // The replaced message's caller is done: its Undo went away untapped.
        assertFalse(first.isActive)
        assertFalse(first.await())
        toasts.current?.dismiss()
        runCurrent()
        assertNull(toasts.current)
        assertFalse(second.isActive)
    }

    @Test
    fun `a replaced message ends once, and its late timer or tap does not touch the newer one`() = runTest {
        val toasts = ToastState()
        var ended = 0
        var acted: Boolean? = null
        launch {
            acted = toasts.show(Toast("Removed from a made-up list", action = "Undo"))
            ended++
        }
        runCurrent()
        val replaced = toasts.current!!
        val newer = async { toasts.show(Toast("Lyrics saved")) }
        runCurrent()
        assertEquals(1, ended)
        assertEquals(false, acted)
        // The replaced pill's own timer, or a tap on its Undo while it fades, arrives late: nothing changes.
        replaced.dismiss(acted = true)
        replaced.dismiss()
        runCurrent()
        assertEquals(1, ended)
        assertEquals(false, acted)
        assertEquals("Lyrics saved", toasts.current?.toast?.text)
        assertTrue(newer.isActive)
        toasts.current?.dismiss()
        assertFalse(newer.await())
    }

    @Test
    fun `three quick messages leave only the last on screen, each earlier caller ended once`() = runTest {
        val toasts = ToastState()
        val calls = (1..3).map { n -> async { toasts.show(Toast("Message $n")) } }
        runCurrent()
        assertEquals("Message 3", toasts.current?.toast?.text)
        assertFalse(calls[0].isActive)
        assertFalse(calls[1].isActive)
        assertTrue(calls[2].isActive)
        toasts.current?.dismiss()
        runCurrent()
        assertNull(toasts.current)
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
    fun `the dock has three destinations, Library, Search and Tools`() {
        assertEquals(listOf(DockTab.LIBRARY, DockTab.SEARCH, DockTab.TOOLS), DOCK_TABS)
        assertEquals(listOf(R.string.nav_library, R.string.nav_search, R.string.nav_tools), DOCK_TABS.map { it.label })
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
