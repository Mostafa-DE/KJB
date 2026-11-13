package app.lyrify.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.then
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lyrify.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The first run on the phone, the test clock held (the waiting cover shimmers for ever): its button stays in reach at font
 * scale 2.0 on a short screen while the words scroll, Find your music has no Not now, each screen's title takes the focus,
 * and the walk to the tour's choice. Not run in the lane: the orchestrator runs it in a phone session.
 */
@RunWith(AndroidJUnit4::class)
class OnboardingScreenDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun words(id: Int, vararg args: Any): String = context.getString(id, *args)

    private val none = Access(notifications = false, allFiles = false)
    private val files = Access(notifications = false, allFiles = true)

    private fun show(access: Access, setupDone: Boolean = false, phone: DeviceConfigurationOverride? = null, onFinish: (Boolean) -> Unit = {}) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val screen: @Composable () -> Unit = {
                LyrifyTheme {
                    CompositionLocalProvider(LocalToasts provides remember { ToastState() }) {
                        OnboardingScreen(access, setupDone, onOpenAllFilesAccess = {}, onOpenNotificationAccess = {}, onOpenAppInfo = {}, onFinish = onFinish)
                    }
                }
            }
            if (phone == null) screen() else DeviceConfigurationOverride(phone, screen)
        }
        settle()
    }

    private fun settle() {
        compose.mainClock.advanceTimeBy(SETTLE_MS)
        compose.waitForIdle()
    }

    private fun tap(id: Int) {
        compose.onNodeWithText(words(id)).performClick()
        settle()
    }

    @Test
    fun atFontScale2OnAShortScreenTheButtonStaysInReachAndTheWordsScroll() {
        show(none, phone = DeviceConfigurationOverride.FontScale(2f) then DeviceConfigurationOverride.ForcedSize(DpSize(320.dp, 480.dp)))
        val root = compose.onRoot().getUnclippedBoundsInRoot()
        val button = compose.onNodeWithText(words(R.string.ob_get_started)).assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue("Get started ${button.bottom} on screen ${root.bottom}", button.bottom <= root.bottom)
        compose.onNodeWithText(words(R.string.setup_privacy)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(words(R.string.ob_get_started)).assertIsDisplayed()
        // Find your music at the same size: its note and button stay under the scrolling words.
        tap(R.string.ob_get_started)
        compose.onNodeWithText(words(R.string.ob_open_settings)).assertIsDisplayed()
        compose.onNodeWithText(words(R.string.ob_files_note)).assertIsDisplayed()
    }

    @Test
    fun findYourMusicHasNoNotNowAndItsTitleTakesTheFocus() {
        show(none)
        tap(R.string.ob_get_started)
        compose.onNodeWithText(words(R.string.ob_files_title)).assertIsFocused()
        compose.onNodeWithText(words(R.string.step_count, 2, 4)).assertIsDisplayed()
        compose.onNodeWithText(words(R.string.setup_not_now)).assertDoesNotExist()
        compose.onNodeWithText(words(R.string.ob_files_note)).assertIsDisplayed()
    }

    @Test
    fun withAccessOnTheWalkEndsAtTheToursChoice() {
        var finished: Boolean? = null
        show(files) { finished = it }
        tap(R.string.ob_get_started)
        compose.onNodeWithText(words(R.string.setup_done)).assertIsDisplayed()
        tap(R.string.setup_continue)
        compose.onNodeWithText(words(R.string.ob_apps_title)).assertIsFocused()
        tap(R.string.setup_not_now)
        compose.onNodeWithText(words(R.string.ob_ready_title)).assertIsFocused()
        tap(R.string.ob_skip_tour)
        compose.runOnIdle { assertEquals(true, finished) }
    }

    @Test
    fun aPhoneThatSetUpBeforeGetsFindYourMusicAlone() {
        var finished: Boolean? = null
        show(files, setupDone = true) { finished = it }
        compose.onNodeWithText(words(R.string.ob_files_title)).assertIsDisplayed()
        compose.onNodeWithText(words(R.string.step_count, 1, 1)).assertDoesNotExist()
        tap(R.string.setup_continue)
        compose.runOnIdle { assertEquals(false, finished) }
    }

    private companion object {
        /** Enough for a screen to slide in and its title to take the focus. */
        const val SETTLE_MS = 800L
    }
}
