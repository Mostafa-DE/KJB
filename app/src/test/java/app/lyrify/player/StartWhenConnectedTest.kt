package app.lyrify.player

import android.media.AudioDeviceInfo
import android.media.AudioManager
import app.lyrify.settings.ConnectDevice
import app.lyrify.settings.PlaybackSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Start when connected: the start rule (each condition on its own), the output it waits for, the picked devices in the
 * settings, and where the row shows. Made-up names and addresses only.
 */
@kotlin.OptIn(ExperimentalCoroutinesApi::class)
class StartWhenConnectedTest {
    /** Every condition met. */
    private val ready = ConnectMoment(hasQueue = true, kjbPlaying = false, otherPlaying = false, inCall = false, deviceIsOutput = true)

    // The start rule

    @Test
    fun `with every condition met, KJB resumes the last queue`() {
        assertTrue(startsOnConnect(ready))
    }

    @Test
    fun `KJB playing already is left as it is`() {
        assertFalse(startsOnConnect(ready.copy(kjbPlaying = true)))
    }

    @Test
    fun `another app playing music keeps KJB quiet`() {
        assertFalse(startsOnConnect(ready.copy(otherPlaying = true)))
    }

    @Test
    fun `a call keeps KJB quiet`() {
        assertFalse(startsOnConnect(ready.copy(inCall = true)))
    }

    @Test
    fun `a device that did not become the output keeps KJB quiet, so the phone speaker never plays`() {
        assertFalse(startsOnConnect(ready.copy(deviceIsOutput = false)))
    }

    @Test
    fun `with no queue to resume KJB stays quiet`() {
        assertFalse(startsOnConnect(ready.copy(hasQueue = false)))
    }

    @Test
    fun `every audio mode but the normal one is a call`() {
        assertFalse(inCall(AudioManager.MODE_NORMAL))
        listOf(
            AudioManager.MODE_RINGTONE,
            AudioManager.MODE_IN_CALL,
            AudioManager.MODE_IN_COMMUNICATION,
            AudioManager.MODE_CALL_SCREENING,
            AudioManager.MODE_CALL_REDIRECT,
            AudioManager.MODE_COMMUNICATION_REDIRECT,
        ).forEach { mode -> assertTrue("mode $mode", inCall(mode)) }
    }

    @Test
    fun `a connect within 30 s of adding the device starts nothing`() {
        val added = 1_000_000L
        assertTrue(justAdded(added, added))
        assertTrue(justAdded(added, added + ADD_QUIET_MS - 1))
        assertFalse(justAdded(added, added + ADD_QUIET_MS))
        assertFalse(justAdded(null, added))
    }

    // The output

    private val buds = "XX:XX:XX:XX:5E:A1"

    @Test
    fun `the address keeps only its last two bytes, whole or as the phone hides it`() {
        assertEquals("5E:A1", addressTail("0c:1d:2e:3f:5e:a1"))
        assertEquals("5E:A1", addressTail(buds))
        assertNull(addressTail(""))
        assertNull(addressTail(null))
        assertNull(addressTail("bus0_media_out"))
    }

    @Test
    fun `music plays on the picked device when a Bluetooth output with its address has it`() {
        val tail = "5E:A1"
        assertTrue(playsOnDevice(listOf(RoutedOutput(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "5E:A1")), tail))
        assertTrue(playsOnDevice(listOf(RoutedOutput(AudioDeviceInfo.TYPE_BLE_HEADSET, "5E:A1")), tail))
        // Another Bluetooth device (the car) has the music.
        assertFalse(playsOnDevice(listOf(RoutedOutput(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "77:02")), tail))
        // A Bluetooth output whose address the phone hides entirely: any Bluetooth output counts.
        assertTrue(playsOnDevice(listOf(RoutedOutput(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, null)), tail))
    }

    @Test
    fun `the phone speaker, wired headphones and a call's link are never the picked device`() {
        val tail = "5E:A1"
        listOf(
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE,
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        ).forEach { type ->
            assertFalse("type $type", playsOnDevice(listOf(RoutedOutput(type, "5E:A1")), tail))
            assertFalse("type $type", playsOnDevice(listOf(RoutedOutput(type, null)), tail))
        }
        assertFalse(playsOnDevice(emptyList(), tail))
    }

    @Test
    fun `the device becoming the output within 10 s counts`() = runTest {
        val changes = Channel<Unit>(Channel.CONFLATED)
        var output = false
        val result = async { awaitOutput(changes) { output } }
        advanceTimeBy(9_000)
        output = true
        changes.trySend(Unit)
        runCurrent()

        assertTrue(result.await())
        assertEquals(9_000, currentTime)
    }

    @Test
    fun `a route change with no device event is seen at the next look`() = runTest {
        var output = false
        val result = async { awaitOutput(Channel()) { output } }
        advanceTimeBy(3_000)
        output = true
        advanceTimeBy(OUTPUT_RECHECK_MS)
        runCurrent()

        assertTrue(result.await())
    }

    @Test
    fun `the device becoming the output after 10 s is too late`() = runTest {
        val changes = Channel<Unit>(Channel.CONFLATED)
        var output = false
        val result = async { awaitOutput(changes) { output } }
        advanceTimeBy(OUTPUT_WAIT_MS + 1)
        output = true
        changes.trySend(Unit)
        runCurrent()

        assertFalse(result.await())
    }

    @Test
    fun `an output that is already the device counts at once`() = runTest {
        assertTrue(awaitOutput(Channel()) { true })
        assertEquals(0, currentTime)
    }

    // The picked devices

    private val buds1 = ConnectDevice(4, "Example Buds")
    private val car = ConnectDevice(9, "Car Kit Demo")

    @Test
    fun `adding a device keeps one entry per association, in its place`() {
        val one = PlaybackSettings().withConnectDevice(buds1)
        assertEquals(listOf(buds1), one.connectDevices)

        val two = one.withConnectDevice(car)
        assertEquals(listOf(buds1, car), two.connectDevices)

        // Added again (the picker's result and its callback both report it): still one entry, with the newest name.
        assertEquals(listOf(buds1.copy(name = "Example Buds Pro"), car), two.withConnectDevice(buds1.copy(name = "Example Buds Pro")).connectDevices)
        // The other settings stay as they were.
        assertEquals(PlaybackSettings(headsetButtons = false, connectDevices = listOf(buds1)), PlaybackSettings(headsetButtons = false).withConnectDevice(buds1))
    }

    @Test
    fun `removing a device leaves the others`() {
        val both = PlaybackSettings(connectDevices = listOf(buds1, car))
        assertEquals(listOf(car), both.withoutConnectDevice(4).connectDevices)
        assertEquals(emptyList<ConnectDevice>(), both.withoutConnectDevice(4).withoutConnectDevice(9).connectDevices)
        assertEquals(listOf(buds1, car), both.withoutConnectDevice(12).connectDevices)
    }

    @Test
    fun `devices Android no longer pairs with KJB leave the list`() {
        val both = PlaybackSettings(connectDevices = listOf(buds1, car))
        assertEquals(listOf(car), both.keepingConnectDevices(setOf(9, 30)).connectDevices)
        assertEquals(both, both.keepingConnectDevices(setOf(4, 9)))
        assertEquals(emptyList<ConnectDevice>(), both.keepingConnectDevices(emptySet()).connectDevices)
    }

    @Test
    fun `the row reads the names of the devices, or Off with none`() {
        assertNull(connectNames(emptyList()))
        assertEquals("Example Buds", connectNames(listOf(buds1)))
        assertEquals("Example Buds, Car Kit Demo", connectNames(listOf(buds1, car)))
    }

    // The copy

    private val strings = File("src/main/res/values/strings_connect.xml")

    private fun strings(): Map<String, String> {
        assertTrue("run from the app module: ${strings.absolutePath}", strings.isFile)
        return Regex("""<string name="([^"]+)">([^<]*)</string>""").findAll(strings.readText()).associate { it.groupValues[1] to it.groupValues[2].replace("\\'", "'") }
    }

    @Test
    fun `the sheet says what the spec says, in labels of three words or fewer, with no em-dash`() {
        val copy = strings()
        assertEquals("Start when connected", copy["set_autostart"])
        assertEquals("Off", copy["set_autostart_off"])
        assertEquals("Plays when these connect", copy["connect_sheet"])
        assertEquals("Add headphones", copy["connect_add"])
        assertEquals("Android asks you to pick the device once.", copy["connect_add_note"])
        listOf("set_autostart", "set_autostart_off", "connect_add", "connect_remove", "connect_device").forEach { label ->
            assertTrue(label, copy.getValue(label).split(' ').size <= 3)
        }
        assertFalse(strings.readText().contains('\u2014'))
    }

    @Test
    fun `Settings has the row under Playback and opens the sheet`() {
        val screen = File("src/main/java/app/lyrify/ui/SettingsScreen.kt").readText()
        listOf("R.string.set_autostart", "StartWhenConnected.supported(", "StartWhenConnectedSheet(").forEach { assertTrue(it, screen.contains(it)) }
        assertTrue(screen.indexOf("R.string.set_autostart") in screen.indexOf("R.string.settings_playback")..screen.indexOf("R.string.settings_library"))
    }

    // Where the row shows

    @Test
    fun `the row shows from Android 13, on phones with companion devices`() {
        // Android 11 has no presence events; 12 and 12L have them by Bluetooth address only.
        assertFalse(connectSupported(sdk = 30, companionSetup = true))
        assertFalse(connectSupported(sdk = 31, companionSetup = true))
        assertFalse(connectSupported(sdk = 32, companionSetup = true))
        assertTrue(connectSupported(sdk = 33, companionSetup = true))
        assertTrue(connectSupported(sdk = 37, companionSetup = true))
        assertFalse(connectSupported(sdk = 37, companionSetup = false))
    }
}
