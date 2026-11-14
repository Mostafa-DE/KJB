package app.lyrify.ui

import app.lyrify.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The first run (onboarding-research.md 4.2 and 4.3): four screens, one required (Find your music, no Not now), one
 * optional, then the tour or not; the AI's asks in context (4.4, user decisions 2 and 3 of 2026-11-13); and the copy rules
 * on what ships: labels of 3 words or fewer, no em-dashes, "your phone".
 */
class OnboardingTest {
    private val none = Access(notifications = false, allFiles = false)
    private val files = Access(notifications = false, allFiles = true)
    private val both = Access(notifications = true, allFiles = true)
    private val all = setupScreens(setupDone = false)

    // The screens

    @Test
    fun `a new phone gets four screens, a phone that set up before only Find your music`() {
        assertEquals(listOf(SetupScreen.WELCOME, SetupScreen.FILES, SetupScreen.OTHER_APPS, SetupScreen.READY), all)
        assertEquals(listOf(SetupScreen.FILES), setupScreens(setupDone = true))
    }

    @Test
    fun `Welcome has one way on`() {
        assertEquals(SetupBar(R.string.ob_get_started), setupBar(SetupScreen.WELCOME, none))
        assertEquals(SetupMove.To(SetupScreen.FILES), setupPrimary(all, SetupScreen.WELCOME, none))
        assertNull(setupSecondary(all, SetupScreen.WELCOME))
    }

    @Test
    fun `Find your music has no Not now, and continues only once All files access is on`() {
        assertEquals(SetupBar(R.string.ob_open_settings, note = true), setupBar(SetupScreen.FILES, none))
        assertEquals(SetupMove.OpenFiles, setupPrimary(all, SetupScreen.FILES, none))
        assertNull(setupSecondary(all, SetupScreen.FILES))
        assertEquals(SetupBar(R.string.setup_continue), setupBar(SetupScreen.FILES, files))
        assertEquals(SetupMove.To(SetupScreen.OTHER_APPS), setupPrimary(all, SetupScreen.FILES, files))
    }

    @Test
    fun `Other music apps can be left for later`() {
        assertEquals(SetupBar(R.string.ob_open_settings, R.string.setup_not_now), setupBar(SetupScreen.OTHER_APPS, files))
        assertEquals(SetupMove.OpenNotifications, setupPrimary(all, SetupScreen.OTHER_APPS, files))
        assertEquals(SetupMove.To(SetupScreen.READY), setupSecondary(all, SetupScreen.OTHER_APPS))
        assertEquals(SetupBar(R.string.setup_continue), setupBar(SetupScreen.OTHER_APPS, both))
        assertEquals(SetupMove.To(SetupScreen.READY), setupPrimary(all, SetupScreen.OTHER_APPS, both))
    }

    @Test
    fun `You're ready takes the tour or skips it`() {
        assertEquals(SetupBar(R.string.ob_take_tour, R.string.ob_skip_tour), setupBar(SetupScreen.READY, files))
        assertEquals(SetupMove.Finish(declinesTour = false), setupPrimary(all, SetupScreen.READY, files))
        assertEquals(SetupMove.Finish(declinesTour = true), setupSecondary(all, SetupScreen.READY))
    }

    @Test
    fun `on a phone that set up before, Find your music goes straight back to the app`() {
        val again = setupScreens(setupDone = true)
        assertEquals(SetupMove.OpenFiles, setupPrimary(again, SetupScreen.FILES, none))
        assertEquals(SetupMove.Finish(declinesTour = false), setupPrimary(again, SetupScreen.FILES, files))
        assertNull(setupBack(again, SetupScreen.FILES))
    }

    @Test
    fun `Back goes one screen back, and leaves KJB from the first`() {
        assertNull(setupBack(all, SetupScreen.WELCOME))
        assertEquals(SetupScreen.WELCOME, setupBack(all, SetupScreen.FILES))
        assertEquals(SetupScreen.FILES, setupBack(all, SetupScreen.OTHER_APPS))
        assertEquals(SetupScreen.OTHER_APPS, setupBack(all, SetupScreen.READY))
    }

    // Asked in context, once the AI is switched on

    @Test
    fun `notifications are asked for on Android 13 and later only, until allowed or denied for good`() {
        assertEquals(NotificationGrant.ON, notificationGrant(sdk = 32, granted = false, deniedForGood = false))
        assertEquals(NotificationGrant.ASK, notificationGrant(sdk = 33, granted = false, deniedForGood = false))
        assertEquals(NotificationGrant.ON, notificationGrant(sdk = 33, granted = true, deniedForGood = false))
        assertEquals(NotificationGrant.OFF, notificationGrant(sdk = 36, granted = false, deniedForGood = true))
        // Allowed later in the phone's settings: on, whatever was denied before.
        assertEquals(NotificationGrant.ON, notificationGrant(sdk = 36, granted = true, deniedForGood = true))
    }

    @Test
    fun `the second no is for good`() {
        // The usual way: the first no makes Android explain, the second ends it.
        assertFalse(deniedForGood(granted = false, rationaleBefore = false, rationaleAfter = true, denialsBefore = 0, answeredMs = 2_000))
        assertTrue(deniedForGood(granted = false, rationaleBefore = true, rationaleAfter = false, denialsBefore = 1, answeredMs = 2_000))
        // A no after the dialog was swiped away once: no explanation either time, still the second no.
        assertTrue(deniedForGood(granted = false, rationaleBefore = false, rationaleAfter = false, denialsBefore = 1, answeredMs = 2_000))
    }

    @Test
    fun `a request that comes back at once is for good, a first swipe away is not`() {
        // No dialog at all (turned off in the phone's settings): Android answered for the user.
        assertTrue(deniedForGood(granted = false, rationaleBefore = false, rationaleAfter = false, denialsBefore = 0, answeredMs = 40))
        // The first dialog swiped away: Android may ask again.
        assertFalse(deniedForGood(granted = false, rationaleBefore = false, rationaleAfter = false, denialsBefore = 0, answeredMs = 2_000))
        assertFalse(deniedForGood(granted = true, rationaleBefore = true, rationaleAfter = false, denialsBefore = 3, answeredMs = 40))
    }

    @Test
    fun `the nos are counted in settings, and older settings read none`() {
        val counted = app.lyrify.settings.Settings(nav = app.lyrify.settings.NavSettings(notificationDenials = 1))
        assertEquals(counted, app.lyrify.settings.SettingsJson.decode(app.lyrify.settings.SettingsJson.encode(counted)))
        assertEquals(0, app.lyrify.settings.SettingsJson.decode("""{"nav":{"notificationDenials":-2}}""")!!.nav.notificationDenials)
        assertEquals(0, app.lyrify.settings.SettingsJson.decode("""{"version":4,"nav":{}}""")!!.nav.notificationDenials)
        // Once for good: no more Show progress, and the AI screen's row instead.
        assertEquals(NotificationGrant.OFF, notificationGrant(sdk = 36, granted = false, deniedForGood = true))
        assertEquals(emptyList<AiAsk>(), aiAsks(switchedOn = false, NotificationGrant.OFF, unrestricted = true))
    }

    @Test
    fun `the Welcome hero goes above large text`() {
        assertTrue(heroShown(1.0f))
        assertTrue(heroShown(LARGE_TEXT))
        assertFalse(heroShown(1.5f))
        assertFalse(heroShown(2.0f))
    }

    @Test
    fun `switching the AI on asks for progress then battery use, a download only for progress`() {
        assertEquals(listOf(AiAsk.PROGRESS, AiAsk.BATTERY), aiAsks(switchedOn = true, NotificationGrant.ASK, unrestricted = false))
        assertEquals(listOf(AiAsk.BATTERY), aiAsks(switchedOn = true, NotificationGrant.ON, unrestricted = false))
        assertEquals(listOf(AiAsk.PROGRESS), aiAsks(switchedOn = true, NotificationGrant.ASK, unrestricted = true))
        assertEquals(emptyList<AiAsk>(), aiAsks(switchedOn = true, NotificationGrant.OFF, unrestricted = true))
        assertEquals(listOf(AiAsk.PROGRESS), aiAsks(switchedOn = false, NotificationGrant.ASK, unrestricted = false))
        assertEquals(emptyList<AiAsk>(), aiAsks(switchedOn = false, NotificationGrant.OFF, unrestricted = false))
    }

    @Test
    fun `the manifest declares notifications, for the AI only`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue("android.permission.POST_NOTIFICATIONS" in manifest)
    }

    // The copy as it ships

    private val file = File("src/main/res/values/strings_onboarding.xml").readText()
    private val shared = File("src/main/res/values/strings.xml").readText()

    private fun string(name: String, text: String = file): String =
        checkNotNull(Regex("""<string name="$name">(.*?)</string>""").find(text)) { "no string $name" }.groupValues[1].replace("\\'", "'")

    @Test
    fun `labels are three words or fewer`() {
        val labels = listOf(
            "ob_get_started", "ob_files_title", "ob_open_settings", "ob_apps_title", "ob_ready_title", "ob_take_tour", "ob_skip_tour",
            "tour_tabs_title", "tour_play_title", "tour_lyrics_title", "tour_search_title", "tour_tools_title",
            "tour_skip", "tour_back", "tour_next", "tour_done", "tour_open_player", "tour_show", "tour_show_line",
            "ask_progress_title", "ask_progress_off",
        )
        for (label in labels) {
            val words = string(label).split(' ').filter { it.isNotBlank() }
            assertTrue("$label: ${string(label)}", words.size <= 3)
        }
    }

    @Test
    fun `no em-dashes anywhere, and the phone is your phone`() {
        assertFalse("an em-dash", '—' in file)
        assertFalse("Android in the copy", Regex("""<string name="[^"]+">[^<]*Android""").containsMatchIn(file))
        for (name in listOf("ob_files_hint", "ob_apps_hint", "tour_tabs_body", "ask_battery_text")) {
            assertTrue(name, "your phone" in string(name).lowercase())
        }
        assertTrue("on your phone" in string("setup_files_detail", shared))
    }

    @Test
    fun `nothing in the first run mentions the AI add-on yet`() {
        assertFalse("add-on" in file.lowercase())
        assertFalse("add on" in file.lowercase())
    }

    @Test
    fun `the Welcome lines are KJB's own`() {
        assertEquals("When the night feels long and slow", string("ob_line_past"))
        assertEquals("Hum the tune and let it go", string("ob_line_now"))
        assertEquals("Morning finds us on the road", string("ob_line_next"))
    }

    @Test
    fun `Find your music says why before the promise`() {
        val body = string("setup_files_detail", shared)
        assertTrue(body, body.startsWith("KJB needs to see the music on your phone to build your library."))
        assertTrue("Open App info, tap the three dots" in string("setup_restricted_detail", shared))
    }

    @Test
    fun `with reduced motion the waiting cover runs no endless shimmer`() {
        // Phone session 9: the shimmer's infinite transition ran on with reduced motion, only its drawing was off.
        val fan = File("src/main/java/app/lyrify/ui/OnboardingScreen.kt").readText().substringAfter("private fun CoverFan(").substringBefore("\n}\n")
        assertTrue(Regex("""if \(reduced\) null else rememberInfiniteTransition""").containsMatchIn(fan))
    }

}
