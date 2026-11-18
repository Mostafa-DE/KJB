package app.lyrify.ui

import android.os.Build
import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.AccessibilityManager
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import app.lyrify.R
import app.lyrify.settings.DockTab
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
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
        // Its Undo went away untapped; its caller hears so at the message's own time (the tests below).
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
        // The replaced pill's own timer, or a tap on its Undo while it fades, arrives late: nothing changes.
        replaced.dismiss(acted = true)
        replaced.dismiss()
        advanceUntilIdle()
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
        // Without an action there is nothing to keep: the replaced callers are done at once.
        assertFalse(calls[0].isActive)
        assertFalse(calls[1].isActive)
        assertTrue(calls[2].isActive)
        toasts.current?.dismiss()
        runCurrent()
        assertNull(toasts.current)
    }

    // Toasts: a compact pill just above the Now bar or the dock (phone test 2026-11-11)

    @Test
    fun `a message is a one-line pill 40 dp high, centred, floating 10 dp above the bar and never over it`() {
        assertEquals(40f, TOAST_HEIGHT.value, 0f)
        assertEquals(10f, TOAST_GAP.value, 0f)
        // Up from its slot (the top of the Now bar, or of the dock when nothing plays): centred, its bottom edge the gap above.
        assertEquals(IntOffset(300, -(120 + 30)), toastPlace(ToastFloat.UP, slotWidth = 1000, size = IntSize(400, 120), gap = 30))
        // Down from its slot (under the player's top bar): centred, its top edge the gap below.
        assertEquals(IntOffset(300, 30), toastPlace(ToastFloat.DOWN, slotWidth = 1000, size = IntSize(400, 120), gap = 30))
        // As wide as its slot at most, never off its start.
        assertEquals(0, toastPlace(ToastFloat.UP, slotWidth = 1000, size = IntSize(1000, 120), gap = 30).x)
    }

    // Toasts: what an Undo needs is never let go sooner than its message's own time

    @Test
    fun `a replaced Undo message lets go of what its Undo needs no sooner than its own time`() = runTest {
        val toasts = ToastState()
        var letGo = false
        var undone = false
        // As the sync editor's Save: Undo tapped, it is undone; else its backup is deleted.
        launch {
            val acted = toasts.show(Toast("Synced lyrics saved", action = "Undo"))
            undone = acted
            letGo = !acted
        }
        runCurrent()
        val newer = async { toasts.show(Toast("Shuffle on")) }
        runCurrent()
        // Its Undo went away at once, as Snackbar's does; the backup stays until its 6 s are up.
        assertEquals("Shuffle on", toasts.current?.toast?.text)
        advanceTimeBy(5_999)
        assertFalse(letGo)
        advanceTimeBy(2)
        assertTrue(letGo)
        assertFalse(undone)
        toasts.current?.dismiss()
        assertFalse(newer.await())
    }

    @Test
    fun `an Undo tapped still undoes at once, and one whose time ran out or was swiped away still lets go at once`() = runTest {
        val toasts = ToastState()
        val tapped = async { toasts.show(Toast("Synced lyrics saved", action = "Undo")) }
        runCurrent()
        toasts.current?.dismiss(acted = true)
        runCurrent()
        assertFalse(tapped.isActive)
        assertTrue(tapped.await())
        val gone = async { toasts.show(Toast("Synced lyrics saved", action = "Undo")) }
        runCurrent()
        toasts.current?.dismiss()
        runCurrent()
        assertFalse(gone.isActive)
        assertFalse(gone.await())
    }

    @Test
    fun `with a longer accessibility timeout a replaced Undo message keeps what its Undo needs that long`() = runTest {
        val toasts = ToastState(phone { own, controls -> if (controls) 30_000L else own })
        var letGo = false
        launch { letGo = !toasts.show(Toast("Synced lyrics saved", action = "Undo")) }
        runCurrent()
        // The pill on screen waits the same time.
        assertEquals(30_000L, toasts.current?.millis)
        val newer = async { toasts.show(Toast("Shuffle on")) }
        runCurrent()
        assertEquals(3_000L, toasts.current?.millis)
        advanceTimeBy(29_999)
        assertFalse(letGo)
        advanceTimeBy(2)
        assertTrue(letGo)
        toasts.current?.dismiss()
        assertFalse(newer.await())
    }

    @Test
    fun `a message replaced after its own time was up ends for its caller at once`() = runTest {
        val toasts = ToastState()
        val held = async { toasts.show(Toast("Synced lyrics saved", action = "Undo")) }
        runCurrent()
        // Held under a finger past its 6 s, then replaced.
        advanceTimeBy(7_000)
        val newer = async { toasts.show("Shuffle on") }
        runCurrent()
        assertFalse(held.isActive)
        assertFalse(held.await())
        toasts.current?.dismiss()
        assertFalse(newer.await())
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
    fun `a message stays 3 seconds, 6 with an action or two lines`() {
        assertEquals(3_000L, toastMillis(Toast("Tone deleted"), a11y = null))
        assertEquals(6_000L, toastMillis(Toast("Tone deleted", action = "Undo"), a11y = null))
        assertEquals(6_000L, toastMillis(Toast("Couldn't open your Clock app. Open it from your home screen."), a11y = null))
    }

    @Test
    fun `the phone's accessibility timeout stretches a message, an action asked as controls, and never shortens it`() {
        val asked = mutableListOf<Boolean>()
        // As Android's: one time for controls, another for text alone.
        val slow = phone { own, controls ->
            asked += controls
            if (controls) 30_000L else maxOf(own, 10_000L)
        }
        assertEquals(30_000L, toastMillis(Toast("Song moved to trash", action = "Undo"), slow))
        assertEquals(10_000L, toastMillis(Toast("Tone deleted"), slow))
        assertEquals(listOf(true, false), asked)
        // A phone that answers less than the message's own time never cuts it short.
        val hasty = phone { _, _ -> 1_000L }
        assertEquals(6_000L, toastMillis(Toast("Song moved to trash", action = "Undo"), hasty))
        assertEquals(3_000L, toastMillis(Toast("Tone deleted"), hasty))
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
    fun `every item the bar can hold has a name of its own, Queues, Favourites, Downloads and Books among them`() {
        assertEquals(
            listOf(R.string.nav_queues, R.string.nav_favourites, R.string.nav_downloads, R.string.nav_books),
            listOf(DockTab.QUEUES, DockTab.FAVOURITES, DockTab.DOWNLOADS, DockTab.BOOKS).map { it.label },
        )
        assertEquals(DockTab.entries.size, DockTab.entries.map { it.label }.distinct().size)
        val strings = java.io.File("src/main/res/values/strings_bar.xml").readText()
        listOf("Queues", "Favourites", "Downloads", "Books").forEach { assertTrue(it, ">$it</string>" in strings) }
    }

    // The main pages' header (user decision 2026-11-11, changed the same day): the KJB logo, then the page's title

    @Test
    fun `each dock destination's header is the KJB logo then its own name, the logo silent, the heading only the title`() {
        assertEquals(DOCK_TABS.map { it.label }, DOCK_TABS.map { headerPage(it) })
        val chrome = java.io.File("src/main/java/app/lyrify/ui/Chrome.kt").readText()
        val logo = chrome.substringAfter("private fun HeaderLogo(").substringBefore("\n}\n")
        // The launcher's black tile with the Note arm K, default cover A, decorative.
        assertTrue(logo, "R.drawable.kjb_default_cover" in logo && "contentDescription = null" in logo)
        val strings = java.io.File("src/main/res/values/strings.xml").readText()
        // No brand words in the header any more, and the visible app name stays KJB.
        assertFalse("header_brand" in strings)
        assertTrue("""<string name="app_name">KJB</string>""" in strings)
        assertTrue(HEADER_LOGO.value in 28f..32f)
    }

    @Test
    fun `only the bar's pages carry the logo, every inner page keeps Back and its own title`() {
        val ui = java.io.File("src/main/java/app/lyrify/ui").listFiles { f -> f.extension == "kt" }!!.associate { it.name to it.readText() }
        val uses = ui.mapValues { (_, text) -> Regex("MainTopBar\\(").findAll(text).count() }.filterValues { it > 0 } - "Chrome.kt"
        // KJB 3.1: Queues, Favourites, Downloads and Books are roots too when the user puts them on the bar.
        assertEquals(
            mapOf("LibraryScreen.kt" to 2, "SearchScreen.kt" to 1, "ToolsScreen.kt" to 1, "QueueScreens.kt" to 1, "CollectionScreens.kt" to 1, "DownloadsScreen.kt" to 1, "BooksScreen.kt" to 1),
            uses,
        )
        // Each from its root: Library, Playlists, Search and Tools, then the new ones.
        listOf(
            "LibraryScreen.kt" to "DockTab.LIBRARY", "LibraryScreen.kt" to "DockTab.PLAYLISTS", "SearchScreen.kt" to "DockTab.SEARCH", "ToolsScreen.kt" to "DockTab.TOOLS",
            "QueueScreens.kt" to "DockTab.QUEUES", "DownloadsScreen.kt" to "DockTab.DOWNLOADS", "BooksScreen.kt" to "DockTab.BOOKS",
        ).forEach { (file, tab) -> assertTrue("$file $tab", "MainTopBar($tab" in ui.getValue(file)) }
        // The inner pages' bars keep their Back.
        assertTrue(Regex("LyrifyTopBar\\(").findAll(ui.getValue("CollectionScreens.kt") + ui.getValue("SettingsScreen.kt") + ui.getValue("CoversTagsScreen.kt")).count() >= 4)
        assertTrue("navigation = { ChromeIconButton(R.drawable.ic_arrow_back" in ui.getValue("CoversTagsScreen.kt"))
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

    @Test
    fun `on the user's own bar a new tab slides in from its side there, and one off the bar from the end`() {
        val bar = listOf(DockTab.FAVOURITES, DockTab.LIBRARY, DockTab.TOOLS)
        assertEquals(-1, tabSlide(DockTab.LIBRARY, DockTab.FAVOURITES, bar))
        assertEquals(1, tabSlide(DockTab.FAVOURITES, DockTab.TOOLS, bar))
        // Search, off this bar (Library's top bar opens it): from the end, and back from it from the start.
        assertEquals(1, tabSlide(DockTab.TOOLS, DockTab.SEARCH, bar))
        assertEquals(-1, tabSlide(DockTab.SEARCH, DockTab.TOOLS, bar))
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

    /** A phone whose accessibility timeout is [answer], given a message's own time and whether it has controls; it always has text. */
    private fun phone(answer: (own: Long, controls: Boolean) -> Long) = object : AccessibilityManager {
        override fun calculateRecommendedTimeoutMillis(originalTimeoutMillis: Long, containsIcons: Boolean, containsText: Boolean, containsControls: Boolean): Long {
            assertTrue(containsText)
            return answer(originalTimeoutMillis, containsControls)
        }
    }
}
