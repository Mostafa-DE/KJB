package app.lyrify.player

import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import app.lyrify.R
import app.lyrify.settings.NotificationButtons
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Headset presses (one, two, three, the timing window, other keys) and the notification's extra buttons. */
@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalCoroutinesApi::class)
class MediaButtonsTest {
    private val hook = KeyEvent.KEYCODE_HEADSETHOOK
    private val playPause = KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE

    @Test
    fun `one press plays or pauses, two skip, three or more go back`() {
        assertEquals(Press.PLAY_PAUSE, pressOf(1))
        assertEquals(Press.NEXT, pressOf(2))
        assertEquals(Press.PREVIOUS, pressOf(3))
        assertEquals(Press.PREVIOUS, pressOf(5))
    }

    private fun TestScope.counter(acted: MutableList<Press>) = PressCounter(backgroundScope) { acted += it }

    /** A press: the key down, then up 80 ms later, as a headset sends it. */
    private fun TestScope.press(counter: PressCounter, key: Int = hook, enabled: Boolean = true): Boolean {
        val down = counter.onKey(key, down = true, repeat = 0, enabled = enabled)
        advanceTimeBy(80)
        val up = counter.onKey(key, down = false, repeat = 0, enabled = enabled)
        return down && up
    }

    @Test
    fun `presses within the window make one row, acted on once it ends`() = runTest {
        val acted = mutableListOf<Press>()
        val counter = counter(acted)
        assertTrue(press(counter))
        advanceTimeBy(PRESS_WINDOW_MS - 100)
        runCurrent()
        assertEquals(emptyList<Press>(), acted)
        advanceTimeBy(200)
        runCurrent()
        assertEquals(listOf(Press.PLAY_PAUSE), acted)

        acted.clear()
        press(counter)
        advanceTimeBy(150)
        press(counter, playPause)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf(Press.NEXT), acted)

        acted.clear()
        repeat(3) {
            press(counter)
            advanceTimeBy(150)
        }
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf(Press.PREVIOUS), acted)
    }

    @Test
    fun `presses further apart than the window are rows of their own`() = runTest {
        val acted = mutableListOf<Press>()
        val counter = counter(acted)
        press(counter)
        advanceTimeBy(PRESS_WINDOW_MS + 50)
        press(counter)
        advanceTimeBy(PRESS_WINDOW_MS + 50)
        runCurrent()
        assertEquals(listOf(Press.PLAY_PAUSE, Press.PLAY_PAUSE), acted)
    }

    @Test
    fun `a held key is taken but counts once`() = runTest {
        val acted = mutableListOf<Press>()
        val counter = counter(acted)
        assertTrue(counter.onKey(hook, down = true, repeat = 0))
        assertTrue(counter.onKey(hook, down = true, repeat = 1))
        assertTrue(counter.onKey(hook, down = true, repeat = 2))
        assertTrue(counter.onKey(hook, down = false, repeat = 0))
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf(Press.PLAY_PAUSE), acted)
    }

    @Test
    fun `Next and Previous from a car or a Bluetooth headset go on as they are, after a row waiting`() = runTest {
        val acted = mutableListOf<Press>()
        val counter = counter(acted)
        press(counter)
        assertFalse(counter.onKey(KeyEvent.KEYCODE_MEDIA_NEXT, down = true, repeat = 0))
        // The press before is acted on at once, before the Next goes on.
        assertEquals(listOf(Press.PLAY_PAUSE), acted)
        assertFalse(counter.onKey(KeyEvent.KEYCODE_MEDIA_NEXT, down = false, repeat = 0))
        assertFalse(counter.onKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS, down = true, repeat = 0))
        assertFalse(counter.onKey(KeyEvent.KEYCODE_MEDIA_PLAY, down = true, repeat = 0))
        assertFalse(counter.onKey(KeyEvent.KEYCODE_MEDIA_PAUSE, down = true, repeat = 0))
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf(Press.PLAY_PAUSE), acted)
    }

    @Test
    fun `with Headset buttons off, presses are taken and do nothing, other keys still go on`() = runTest {
        val acted = mutableListOf<Press>()
        val counter = counter(acted)
        assertTrue(press(counter, enabled = false))
        assertTrue(press(counter, playPause, enabled = false))
        assertFalse(counter.onKey(KeyEvent.KEYCODE_MEDIA_NEXT, down = true, repeat = 0, enabled = false))
        // Play and Pause keys (a car, a Bluetooth headset with two buttons) are no headset presses: they still go on.
        assertFalse(counter.onKey(KeyEvent.KEYCODE_MEDIA_PLAY, down = true, repeat = 0, enabled = false))
        assertFalse(counter.onKey(KeyEvent.KEYCODE_MEDIA_PAUSE, down = true, repeat = 0, enabled = false))
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(emptyList<Press>(), acted)
    }

    // The notification's extra buttons

    @Test
    fun `Favourite shows a heart for the song playing, filled when it is a favourite`() {
        assertEquals(
            listOf(NotificationButton(COMMAND_FAVOURITE, CommandButton.ICON_HEART_UNFILLED, R.string.player_favourite_off)),
            notificationButtons(NotificationButtons.FAVOURITE, favourite = false, shuffled = true, repeat = Repeat.ALL),
        )
        assertEquals(
            listOf(NotificationButton(COMMAND_FAVOURITE, CommandButton.ICON_HEART_FILLED, R.string.player_favourite_on)),
            notificationButtons(NotificationButtons.FAVOURITE, favourite = true, shuffled = false, repeat = Repeat.OFF),
        )
    }

    @Test
    fun `Shuffle and Repeat show both, each as it is now`() {
        assertEquals(
            listOf(
                NotificationButton(COMMAND_SHUFFLE, CommandButton.ICON_SHUFFLE_OFF, R.string.player_shuffle_off),
                NotificationButton(COMMAND_REPEAT, CommandButton.ICON_REPEAT_OFF, R.string.player_repeat_off),
            ),
            notificationButtons(NotificationButtons.SHUFFLE_REPEAT, favourite = true, shuffled = false, repeat = Repeat.OFF),
        )
        assertEquals(
            listOf(
                NotificationButton(COMMAND_SHUFFLE, CommandButton.ICON_SHUFFLE_ON, R.string.player_shuffle_on),
                NotificationButton(COMMAND_REPEAT, CommandButton.ICON_REPEAT_ALL, R.string.player_repeat_all),
            ),
            notificationButtons(NotificationButtons.SHUFFLE_REPEAT, favourite = false, shuffled = true, repeat = Repeat.ALL),
        )
        assertEquals(CommandButton.ICON_REPEAT_ONE, notificationButtons(NotificationButtons.SHUFFLE_REPEAT, false, false, Repeat.ONE)[1].icon)
        assertEquals(R.string.player_repeat_one, notificationButtons(NotificationButtons.SHUFFLE_REPEAT, false, false, Repeat.ONE)[1].label)
    }
}
