package app.lyrify.player

import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.lifecycle.Lifecycle
import app.lyrify.settings.ConnectDevice
import app.lyrify.settings.PlaybackSettings
import app.lyrify.settings.Settings
import app.lyrify.settings.SettingsStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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

    private val pickedBuds = DeviceMatch(addressTail = "5E:A1", name = "Example Buds")

    @Test
    fun `with both addresses known, the last two bytes decide`() {
        assertTrue(playsOnDevice(listOf(RoutedOutput(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "5E:A1", "Example Buds")), pickedBuds))
        assertTrue(playsOnDevice(listOf(RoutedOutput(AudioDeviceInfo.TYPE_BLE_HEADSET, "5E:A1", null)), pickedBuds))
        // Another Bluetooth device (the car) has the music, even one with the same name.
        assertFalse(playsOnDevice(listOf(RoutedOutput(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "77:02", "Example Buds")), pickedBuds))
    }

    @Test
    fun `with an address hidden, the device's name decides, exactly after trimming`() {
        assertTrue(playsOnDevice(listOf(RoutedOutput(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, null, " Example Buds ")), pickedBuds))
        // The pairing has no address: the name decides too.
        assertTrue(playsOnDevice(listOf(RoutedOutput(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "5E:A1", "Example Buds")), DeviceMatch(null, "Example Buds")))
        assertFalse(playsOnDevice(listOf(RoutedOutput(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, null, "Example Buds Pro")), pickedBuds))
        assertFalse(playsOnDevice(listOf(RoutedOutput(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, null, "example buds")), pickedBuds))
    }

    @Test
    fun `a different headset connected with its address hidden does not start`() {
        val other = listOf(RoutedOutput(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, null, "Other Headset Demo"))
        assertFalse(playsOnDevice(other, pickedBuds))
        assertFalse(playsOnDevice(other, DeviceMatch(null, "Example Buds")))
        // Neither an address nor a name to go by: no start.
        assertFalse(playsOnDevice(listOf(RoutedOutput(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, null, null)), pickedBuds))
        assertFalse(playsOnDevice(listOf(RoutedOutput(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, null, "  ")), DeviceMatch(null, "  ")))
        assertFalse(playsOnDevice(listOf(RoutedOutput(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "5E:A1", null)), DeviceMatch(null, null)))
    }

    @Test
    fun `the phone speaker, wired headphones and a call's link are never the picked device`() {
        listOf(
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE,
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        ).forEach { type ->
            assertFalse("type $type", playsOnDevice(listOf(RoutedOutput(type, "5E:A1", "Example Buds")), pickedBuds))
            assertFalse("type $type", playsOnDevice(listOf(RoutedOutput(type, null, "Example Buds")), pickedBuds))
        }
        assertFalse(playsOnDevice(emptyList(), pickedBuds))
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

    // A connect, from the event to the Play (review items A, C, D)

    private val car = DeviceMatch(addressTail = "77:02", name = "Car Kit Demo")

    /** The phone as a connect start sees it: which device holds the music, and what was played. */
    private class FakePhone : ConnectPhone {
        val pickedDevices = mutableMapOf<Int, DeviceMatch>()
        var output: DeviceMatch? = null
        val changes = Channel<Unit>(Channel.CONFLATED)
        var listening = 0
        var queueReadMs = 0L
        var whileQueueRead: () -> Unit = {}
        val played = mutableListOf<DeviceMatch>()

        fun route(to: DeviceMatch?) {
            output = to
            changes.trySend(Unit)
        }

        override suspend fun picked(associationId: Int): DeviceMatch? = pickedDevices[associationId]

        override fun addedJustNow(associationId: Int): Boolean = false

        override suspend fun becomesOutput(device: DeviceMatch): Boolean {
            listening++
            try {
                return awaitOutput(changes) { output == device }
            } finally {
                listening--
            }
        }

        override fun moment(device: DeviceMatch, hasQueue: Boolean): ConnectMoment =
            ConnectMoment(hasQueue, kjbPlaying = false, otherPlaying = false, inCall = false, deviceIsOutput = output == device)

        override suspend fun hasQueue(): Boolean {
            delay(queueReadMs)
            whileQueueRead()
            return true
        }

        override fun play(device: DeviceMatch) {
            played += device
        }
    }

    @Test
    fun `a picked device that takes the music is played to`() = runTest {
        val phone = FakePhone().apply { pickedDevices[4] = pickedBuds }
        val starts = ConnectStarts(backgroundScope, phone) {}
        starts.connected(4)
        advanceTimeBy(2_000)
        phone.route(pickedBuds)
        runCurrent()

        assertEquals(listOf(pickedBuds), phone.played)
        assertEquals(0, phone.listening)
    }

    @Test
    fun `the route is read again as the Play goes out, so music that left the device starts nothing`() = runTest {
        val phone = FakePhone().apply {
            pickedDevices[4] = pickedBuds
            queueReadMs = 2_000
            // The device took the music, then gave it back to the speaker while the queues were read.
            whileQueueRead = { output = null }
        }
        val starts = ConnectStarts(backgroundScope, phone) {}
        starts.connected(4)
        phone.route(pickedBuds)
        advanceTimeBy(5_000)

        assertEquals(emptyList<DeviceMatch>(), phone.played)
    }

    @Test
    fun `a later connect of a picked device starts the wait over for that device`() = runTest {
        val phone = FakePhone().apply {
            pickedDevices[4] = pickedBuds
            pickedDevices[9] = car
        }
        val starts = ConnectStarts(backgroundScope, phone) {}
        starts.connected(9)
        advanceTimeBy(3_000)
        starts.connected(4)
        advanceTimeBy(1_000)
        phone.route(pickedBuds)
        runCurrent()

        assertEquals(listOf(pickedBuds), phone.played)
        assertEquals(0, phone.listening)
    }

    @Test
    fun `a connect of a device that is not picked leaves the wait as it is`() = runTest {
        val phone = FakePhone().apply { pickedDevices[4] = pickedBuds }
        val starts = ConnectStarts(backgroundScope, phone) {}
        starts.connected(4)
        advanceTimeBy(1_000)
        starts.connected(30)
        advanceTimeBy(1_000)
        phone.route(pickedBuds)
        runCurrent()

        assertEquals(listOf(pickedBuds), phone.played)
    }

    @Test
    fun `a disconnect during the wait ends it, and the output is no longer watched`() = runTest {
        val phone = FakePhone().apply { pickedDevices[4] = pickedBuds }
        val starts = ConnectStarts(backgroundScope, phone) {}
        starts.connected(4)
        advanceTimeBy(2_000)
        assertEquals(1, phone.listening)

        starts.disconnected(4)
        runCurrent()
        assertEquals(0, phone.listening)
        phone.route(pickedBuds)
        advanceTimeBy(OUTPUT_WAIT_MS)

        assertEquals(emptyList<DeviceMatch>(), phone.played)
    }

    @Test
    fun `another device's disconnect leaves the wait as it is`() = runTest {
        val phone = FakePhone().apply { pickedDevices[4] = pickedBuds }
        val starts = ConnectStarts(backgroundScope, phone) {}
        starts.connected(4)
        advanceTimeBy(2_000)
        starts.disconnected(9)
        phone.route(pickedBuds)
        runCurrent()

        assertEquals(listOf(pickedBuds), phone.played)
    }

    @Test
    fun `a playback that begins after the device left pauses, never the phone speaker`() {
        val sent = ConnectStartSent(atMs = 1_000, device = pickedBuds)
        assertTrue(pausesOnBegin(sent, nowMs = 3_000, stillOnDevice = false))
        assertFalse(pausesOnBegin(sent, nowMs = 3_000, stillOnDevice = true))
        // A play long after the connect start is the user's own.
        assertFalse(pausesOnBegin(sent, nowMs = 1_000 + START_GUARD_MS, stillOnDevice = false))
        assertFalse(pausesOnBegin(null, nowMs = 3_000, stillOnDevice = false))
    }

    // Add's picker (review item E)

    @Test
    fun `Android's picker opens only while KJB is on screen, and a launcher gone is no crash`() {
        var launched = 0
        assertFalse(openPicker(Lifecycle.State.CREATED) { launched++ })
        assertFalse(openPicker(Lifecycle.State.DESTROYED) { launched++ })
        assertEquals(0, launched)
        assertTrue(openPicker(Lifecycle.State.STARTED) { launched++ })
        assertTrue(openPicker(Lifecycle.State.RESUMED) { launched++ })
        assertEquals(2, launched)
        assertFalse(openPicker(Lifecycle.State.RESUMED) { throw IllegalStateException("unregistered") })
    }

    // The saved list against Android's pairings (review item F)

    private class MemorySettings(initial: Settings) : SettingsStore {
        val state = MutableStateFlow(initial)
        override val settings: Flow<Settings> = state

        override suspend fun update(change: (Settings) -> Settings): Boolean {
            state.value = change(state.value)
            return true
        }
    }

    @Test
    fun `devices Android no longer pairs with KJB leave the saved list`() = runTest {
        val store = MemorySettings(Settings(playback = PlaybackSettings(headsetButtons = false, connectDevices = listOf(buds1, car1))))

        assertTrue(reconcileConnectDevices(store, live = setOf(9, 30)))
        assertEquals(PlaybackSettings(headsetButtons = false, connectDevices = listOf(car1)), store.state.value.playback)
        reconcileConnectDevices(store, live = emptySet())
        assertEquals(emptyList<ConnectDevice>(), store.state.value.playback.connectDevices)
    }

    // The picked devices

    private val buds1 = ConnectDevice(4, "Example Buds")
    private val car1 = ConnectDevice(9, "Car Kit Demo")

    @Test
    fun `adding a device keeps one entry per association, in its place`() {
        val one = PlaybackSettings().withConnectDevice(buds1)
        assertEquals(listOf(buds1), one.connectDevices)

        val two = one.withConnectDevice(car1)
        assertEquals(listOf(buds1, car1), two.connectDevices)

        // Added again (the picker's result and its callback both report it): still one entry, with the newest name.
        assertEquals(listOf(buds1.copy(name = "Example Buds Pro"), car1), two.withConnectDevice(buds1.copy(name = "Example Buds Pro")).connectDevices)
        // The other settings stay as they were.
        assertEquals(PlaybackSettings(headsetButtons = false, connectDevices = listOf(buds1)), PlaybackSettings(headsetButtons = false).withConnectDevice(buds1))
    }

    @Test
    fun `removing a device leaves the others`() {
        val both = PlaybackSettings(connectDevices = listOf(buds1, car1))
        assertEquals(listOf(car1), both.withoutConnectDevice(4).connectDevices)
        assertEquals(emptyList<ConnectDevice>(), both.withoutConnectDevice(4).withoutConnectDevice(9).connectDevices)
        assertEquals(listOf(buds1, car1), both.withoutConnectDevice(12).connectDevices)
    }

    @Test
    fun `devices Android no longer pairs with KJB leave the list`() {
        val both = PlaybackSettings(connectDevices = listOf(buds1, car1))
        assertEquals(listOf(car1), both.keepingConnectDevices(setOf(9, 30)).connectDevices)
        assertEquals(both, both.keepingConnectDevices(setOf(4, 9)))
        assertEquals(emptyList<ConnectDevice>(), both.keepingConnectDevices(emptySet()).connectDevices)
    }

    @Test
    fun `the row reads the names of the devices, or Off with none`() {
        assertNull(connectNames(emptyList()))
        assertEquals("Example Buds", connectNames(listOf(buds1)))
        assertEquals("Example Buds, Car Kit Demo", connectNames(listOf(buds1, car1)))
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
