package app.lyrify.audio

import android.media.AudioDeviceInfo
import app.lyrify.R
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Which output plays, which profile key it has, and the profile switching as devices come and go. Made-up device names. */
@OptIn(ExperimentalCoroutinesApi::class) // advanceTimeBy, runCurrent
class OutputRoutesTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val speaker = OutputDevice(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "SM-X000")
    private val earpiece = OutputDevice(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, "SM-X000")
    private val wired = OutputDevice(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, "SM-X000")
    private val usb = OutputDevice(AudioDeviceInfo.TYPE_USB_HEADSET, "Cable Adapter")
    private val buds = OutputDevice(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "Pebble Buds")
    private val budsCall = OutputDevice(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "Pebble Buds")
    private val speakerBox = OutputDevice(AudioDeviceInfo.TYPE_BLE_SPEAKER, " Harbour Box ")

    private fun bluetooth(name: String?) = OutputKey(OutputKind.BLUETOOTH, name)

    @Test
    fun `each kind of output gets its profile key, Bluetooth by its name`() {
        assertEquals(OutputKey.SPEAKER, outputKey(speaker))
        assertEquals(OutputKey.SPEAKER, outputKey(OutputDevice(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE, null)))
        assertEquals(OutputKey(OutputKind.WIRED), outputKey(wired))
        assertEquals(OutputKey(OutputKind.WIRED), outputKey(OutputDevice(AudioDeviceInfo.TYPE_WIRED_HEADSET, "Headset")))
        assertEquals(OutputKey(OutputKind.USB), outputKey(usb))
        assertEquals(OutputKey(OutputKind.USB), outputKey(OutputDevice(AudioDeviceInfo.TYPE_USB_DEVICE, "Desk DAC")))
        assertEquals(bluetooth("Pebble Buds"), outputKey(buds))
        assertEquals(bluetooth("Harbour Box"), outputKey(speakerBox))
        assertEquals(bluetooth("Ear Helper"), outputKey(OutputDevice(AudioDeviceInfo.TYPE_HEARING_AID, "Ear Helper")))
        assertEquals(bluetooth("Pebble Buds"), outputKey(OutputDevice(AudioDeviceInfo.TYPE_BLE_HEADSET, "Pebble Buds")))
        assertEquals(OutputKey(OutputKind.CAR), outputKey(OutputDevice(AudioDeviceInfo.TYPE_BUS, "Dash")))
        assertEquals(OutputKey(OutputKind.OTHER), outputKey(OutputDevice(AudioDeviceInfo.TYPE_HDMI, "Big Screen")))
    }

    @Test
    fun `a Bluetooth output whose name is hidden shares the one Bluetooth profile`() {
        assertEquals(bluetooth(null), outputKey(OutputDevice(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, null)))
        assertEquals(bluetooth(null), outputKey(OutputDevice(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "   ")))
    }

    @Test
    fun `two devices with the same name share a profile, the key never holds an address`() {
        assertEquals(outputKey(buds), outputKey(OutputDevice(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "Pebble Buds")))
    }

    @Test
    fun `outputs that never play music have no profile`() {
        assertNull(outputKey(earpiece))
        assertNull(outputKey(budsCall))
        assertNull(outputKey(OutputDevice(AudioDeviceInfo.TYPE_TELEPHONY, null)))
        assertNull(outputKey(OutputDevice(AudioDeviceInfo.TYPE_REMOTE_SUBMIX, null)))
    }

    @Test
    fun `music plays on Bluetooth, then wired, then USB, then the speaker`() {
        assertEquals(bluetooth("Pebble Buds"), currentOutput(listOf(earpiece, speaker, wired, usb, buds, budsCall)))
        assertEquals(OutputKey(OutputKind.WIRED), currentOutput(listOf(speaker, usb, wired)))
        assertEquals(OutputKey(OutputKind.USB), currentOutput(listOf(speaker, usb)))
        assertEquals(OutputKey.SPEAKER, currentOutput(listOf(earpiece, speaker)))
        assertEquals(OutputKey.SPEAKER, currentOutput(listOf(earpiece, budsCall)))
        assertEquals(OutputKey.SPEAKER, currentOutput(emptyList()))
    }

    @Test
    fun `the stored key reads back as the same output`() {
        val keys = listOf(
            OutputKey.SPEAKER, OutputKey(OutputKind.WIRED), OutputKey(OutputKind.USB), OutputKey(OutputKind.CAR),
            OutputKey(OutputKind.OTHER), bluetooth(null), bluetooth("Pebble Buds"), bluetooth("Box: Mini"),
        )
        for (key in keys) assertEquals(key, OutputKey.parse(key.id))
        assertEquals("speaker", OutputKey.SPEAKER.id)
        assertEquals("bluetooth:Pebble Buds", bluetooth("Pebble Buds").id)
        assertNull(OutputKey.parse("hologram"))
        assertNull(OutputKey.parse("speaker:Kitchen"))
        assertNull(OutputKey.parse("bluetooth:   "))
    }

    @Test
    fun `the profile follows the output as devices come and go`() {
        var profiles = EqProfiles().withProfile(OutputKey.SPEAKER, EqProfile(EqSound(enabled = true, BuiltInPreset.SMALL_SPEAKERS.bandsDb), PresetChoice.BuiltIn(BuiltInPreset.SMALL_SPEAKERS)))
        var now: OutputKey? = null
        val announced = mutableListOf<OutputKey>()
        fun event(vararg devices: OutputDevice) {
            currentOutput(devices.toList()).takeIf { it != now }?.let {
                now = it
                announced += it
            }
        }

        event(earpiece, speaker)
        assertEquals(OutputKey.SPEAKER, now)
        assertEquals(BuiltInPreset.SMALL_SPEAKERS.bandsDb, profiles.profile(OutputKey.SPEAKER).sound.bandsDb)

        // The buds connect: a new output, Flat with the equalizer off.
        event(earpiece, speaker, buds, budsCall)
        assertEquals(bluetooth("Pebble Buds"), now)
        assertEquals(EqProfile(), profiles.profile(bluetooth("Pebble Buds")))
        profiles = profiles.usePreset(bluetooth("Pebble Buds"), PresetChoice.BuiltIn(BuiltInPreset.BASS))

        // Another device appears while the buds play on: no switch, no toast.
        event(earpiece, speaker, buds, budsCall, usb)
        // The buds leave: back to the speaker's own profile; then they return to theirs.
        event(earpiece, speaker)
        assertEquals(OutputKey.SPEAKER, now)
        assertEquals(EqSound(enabled = true, BuiltInPreset.SMALL_SPEAKERS.bandsDb), profiles.profile(OutputKey.SPEAKER).sound)
        event(earpiece, speaker, buds)
        assertEquals(bluetooth("Pebble Buds"), now)
        assertEquals(BuiltInPreset.BASS.bandsDb, profiles.profile(bluetooth("Pebble Buds")).sound.bandsDb)

        assertEquals(listOf(OutputKey.SPEAKER, bluetooth("Pebble Buds"), OutputKey.SPEAKER, bluetooth("Pebble Buds")), announced)
    }

    @Test
    fun `a Bluetooth name the phone hides comes back as the phone's model, and counts as hidden`() {
        // AudioDeviceInfo.getProductName falls back to Build.MODEL when the port has no name.
        assertEquals(OutputDevice(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, null), outputDevice(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "SM-X000", phoneModel = "SM-X000"))
        assertEquals(bluetooth(null), outputKey(outputDevice(AudioDeviceInfo.TYPE_BLE_HEADSET, "SM-X000", phoneModel = "SM-X000")))
        assertEquals(buds, outputDevice(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "Pebble Buds", phoneModel = "SM-X000"))
        assertEquals(speaker, outputDevice(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "SM-X000", phoneModel = "SM-X000"))
    }

    @Test
    fun `each output is named for the toast and the equalizer, Bluetooth by its own name`() {
        val text = mapOf(
            R.string.eq_output_speaker to "Phone speaker",
            R.string.eq_output_wired to "Wired headphones",
            R.string.eq_output_usb to "USB audio",
            R.string.eq_output_bluetooth to "Bluetooth audio",
            R.string.eq_output_car to "Car",
            R.string.eq_output_other to "Other output",
        )::getValue
        assertEquals("Phone speaker", outputLabel(OutputKey.SPEAKER, text))
        assertEquals("Wired headphones", outputLabel(OutputKey(OutputKind.WIRED), text))
        assertEquals("USB audio", outputLabel(OutputKey(OutputKind.USB), text))
        assertEquals("Car", outputLabel(OutputKey(OutputKind.CAR), text))
        assertEquals("Other output", outputLabel(OutputKey(OutputKind.OTHER), text))
        assertEquals("Pebble Buds", outputLabel(bluetooth("Pebble Buds"), text))
        assertEquals("Bluetooth audio", outputLabel(bluetooth(null), text))
    }

    // Device events, as the output watch sees them

    private val on = EqProfile(EqSound(enabled = true, BuiltInPreset.SMALL_SPEAKERS.bandsDb), PresetChoice.BuiltIn(BuiltInPreset.SMALL_SPEAKERS))
    private val budsBass = EqProfile(EqSound(enabled = true, BuiltInPreset.BASS.bandsDb), PresetChoice.BuiltIn(BuiltInPreset.BASS))
    private val boxRock = EqProfile(EqSound(enabled = true, BuiltInPreset.ROCK.bandsDb, preampDb = -2f), PresetChoice.BuiltIn(BuiltInPreset.ROCK))

    /**
     * The watch as the service runs it: [devices] connected and [routed] (what the phone says music plays on, Android 13
     * and later), read at each device event ([events]) or route change ([routes]); its toasts land in [toasts].
     */
    private class Watch(val live: LiveEq, val events: MutableSharedFlow<Unit>, val routes: MutableSharedFlow<Unit>, val toasts: MutableList<OutputKey>) {
        var devices: List<OutputDevice> = emptyList()
        var routed: List<OutputDevice> = emptyList()
    }

    private fun TestScope.watch(stored: EqProfiles, vararg devices: OutputDevice, routed: List<OutputDevice> = emptyList(), store: EqProfilesStore? = null): Watch {
        val profiles = (store ?: EqProfilesStore(File(folder.root, "eq-profiles.json"), clock = { 0 })).apply { save(stored) }
        val watch = Watch(LiveEq(profiles, backgroundScope), MutableSharedFlow(), MutableSharedFlow(), mutableListOf())
        watch.devices = devices.toList()
        watch.routed = routed
        backgroundScope.launch { followOutputs(watch.events, watch.routes, { musicOutput(watch.routed) { watch.devices } }, watch.live, watch.toasts::add) }
        runCurrent()
        return watch
    }

    private suspend fun TestScope.event(watch: Watch, vararg devices: OutputDevice) {
        watch.devices = devices.toList()
        watch.events.emit(Unit)
        advanceTimeBy(OUTPUT_SETTLE_MS + 1)
        runCurrent()
    }

    @Test
    fun `the output playing at the start is followed at once, with no toast`() = runTest {
        val watch = watch(EqProfiles().withProfile(bluetooth("Pebble Buds"), budsBass), earpiece, speaker, buds)
        assertEquals(bluetooth("Pebble Buds"), watch.live.output.value)
        assertEquals(budsBass.sound, watch.live.sound.value)
        assertEquals(emptyList<OutputKey>(), watch.toasts)
    }

    @Test
    fun `a burst of device events switches once they settle, a new output starting Flat and off, with a toast`() = runTest {
        val watch = watch(EqProfiles().withProfile(OutputKey.SPEAKER, on), earpiece, speaker)
        assertEquals(on.sound, watch.live.sound.value)

        // The buds connect: their music link, then their call link, a moment apart.
        watch.devices = listOf(earpiece, speaker, buds)
        watch.events.emit(Unit)
        advanceTimeBy(200)
        watch.devices = listOf(earpiece, speaker, buds, budsCall)
        watch.events.emit(Unit)
        advanceTimeBy(OUTPUT_SETTLE_MS - 1)
        runCurrent()
        assertEquals(OutputKey.SPEAKER, watch.live.output.value)
        advanceTimeBy(2)
        runCurrent()

        assertEquals(bluetooth("Pebble Buds"), watch.live.output.value)
        assertEquals(EqSound(), watch.live.sound.value)
        assertEquals(listOf(bluetooth("Pebble Buds")), watch.toasts)
    }

    @Test
    fun `two Bluetooth devices are told apart by name, each playing its own profile`() = runTest {
        val box = OutputDevice(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "Harbour Box")
        val stored = EqProfiles().withProfile(bluetooth("Pebble Buds"), budsBass).withProfile(bluetooth("Harbour Box"), boxRock)
        val watch = watch(stored, speaker, buds)
        assertEquals(budsBass.sound, watch.live.sound.value)

        event(watch, speaker, box)
        assertEquals(bluetooth("Harbour Box"), watch.live.output.value)
        assertEquals(boxRock.sound, watch.live.sound.value)
        event(watch, speaker, buds)
        assertEquals(budsBass.sound, watch.live.sound.value)
        assertEquals(listOf(bluetooth("Harbour Box"), bluetooth("Pebble Buds")), watch.toasts)
    }

    @Test
    fun `an event that leaves the output as it was changes nothing and shows nothing`() = runTest {
        val watch = watch(EqProfiles().withProfile(OutputKey.SPEAKER, on), earpiece, speaker)
        event(watch, earpiece, speaker, usb.copy(type = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE))
        assertEquals(OutputKey.SPEAKER, watch.live.output.value)
        assertEquals(emptyList<OutputKey>(), watch.toasts)
    }

    @Test
    fun `a switch between outputs with the equalizer off on both shows no toast`() = runTest {
        val watch = watch(EqProfiles(), earpiece, speaker)
        event(watch, speaker, wired)
        assertEquals(OutputKey(OutputKind.WIRED), watch.live.output.value)
        assertEquals(emptyList<OutputKey>(), watch.toasts)
        // Back to an output whose equalizer is on: the toast says so.
        watch.live.change { all, _ -> all.withProfile(OutputKey.SPEAKER, on) }
        event(watch, speaker)
        assertEquals(listOf(OutputKey.SPEAKER), watch.toasts)
    }

    // Review A: a song already playing moved to another output that stays connected

    @Test
    fun `a route change while a song plays switches the profile at once, mid-song`() = runTest {
        val stored = EqProfiles().withProfile(bluetooth("Pebble Buds"), budsBass).withProfile(OutputKey.SPEAKER, on)
        val watch = watch(stored, speaker, buds, routed = listOf(buds))
        assertEquals(budsBass.sound, watch.live.sound.value)

        // The phone's output switcher moves the song to the speaker; the buds stay connected, so no device event comes.
        watch.routed = listOf(speaker)
        watch.routes.emit(Unit)
        runCurrent()

        assertEquals(OutputKey.SPEAKER, watch.live.output.value)
        assertEquals(on.sound, watch.live.sound.value)
        assertEquals(listOf(OutputKey.SPEAKER), watch.toasts)
    }

    @Test
    fun `another app's playback change never cuts a device event's settling short`() = runTest {
        val stored = EqProfiles().withProfile(bluetooth("Pebble Buds"), budsBass)
        val watch = watch(stored, speaker, routed = listOf(speaker))

        // The buds connect while KJB is paused; the phone moves music to them a moment later.
        watch.devices = listOf(speaker, buds)
        watch.events.emit(Unit)
        advanceTimeBy(200)
        watch.routes.emit(Unit)
        runCurrent()
        assertEquals(OutputKey.SPEAKER, watch.live.output.value)
        watch.routed = listOf(buds)
        advanceTimeBy(OUTPUT_SETTLE_MS)
        runCurrent()

        assertEquals(bluetooth("Pebble Buds"), watch.live.output.value)
        assertEquals(budsBass.sound, watch.live.sound.value)
    }

    // Review B: the output the phone routes music to, not the one ranked first

    @Test
    fun `on Android 13 and later the output the phone routes music to wins, whatever else is connected`() {
        assertEquals(OutputKey.SPEAKER, musicOutput(listOf(speaker)) { listOf(speaker, buds) })
        assertEquals(OutputKey(OutputKind.WIRED), musicOutput(listOf(wired, buds)) { listOf(speaker, wired, buds) })
        // The first one that plays music, in the phone's order.
        assertEquals(bluetooth("Pebble Buds"), musicOutput(listOf(budsCall, earpiece, buds, speaker)) { listOf(speaker, buds) })
    }

    @Test
    fun `below Android 13, or when the phone names no music output, the output is chosen by kind among those connected`() {
        assertEquals(bluetooth("Pebble Buds"), musicOutput(emptyList()) { listOf(speaker, wired, buds) })
        assertEquals(bluetooth("Pebble Buds"), musicOutput(listOf(earpiece)) { listOf(speaker, buds) })
        assertEquals(OutputKey.SPEAKER, musicOutput(emptyList()) { emptyList() })
    }

    @Test
    fun `with buds connected but music on the speaker, the watch plays the speaker's profile`() = runTest {
        val stored = EqProfiles().withProfile(bluetooth("Pebble Buds"), budsBass).withProfile(OutputKey.SPEAKER, on)
        val watch = watch(stored, speaker, buds, routed = listOf(speaker))
        assertEquals(OutputKey.SPEAKER, watch.live.output.value)
        assertEquals(on.sound, watch.live.sound.value)
    }

    // Review G: no Bluetooth address, anywhere

    /** A Bluetooth address, also as the phone hides it from apps ("XX:XX:XX:XX:A1:B2"). */
    private val address = Regex("""\b([0-9A-Fa-fXx]{2}[:-]){5}[0-9A-Fa-fXx]{2}\b""")

    @Test
    fun `a Bluetooth name that is an address counts as hidden, so an address never becomes a key`() {
        assertEquals(bluetooth(null), outputKey(outputDevice(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "00:11:22:33:44:55", phoneModel = "SM-X000")))
        assertEquals(bluetooth(null), outputKey(outputDevice(AudioDeviceInfo.TYPE_BLE_HEADSET, " XX:XX:XX:XX:A1:B2 ", phoneModel = "SM-X000")))
        assertEquals(bluetooth(null), outputKey(outputDevice(AudioDeviceInfo.TYPE_BLE_SPEAKER, "0a-1b-2c-3d-4e-5f", phoneModel = "SM-X000")))
        assertEquals(bluetooth("Pebble Buds 2"), outputKey(outputDevice(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "Pebble Buds 2", phoneModel = "SM-X000")))
    }

    @Test
    fun `no address reaches the profiles file or the log, whatever the devices are called`() = runTest {
        val logs = mutableListOf<String>()
        val file = File(folder.root, "eq-profiles.json")
        val store = EqProfilesStore(file, clock = { 0 }) { logs += it }
        val named = { name: String -> outputDevice(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, name, phoneModel = "SM-X000") }
        val watch = watch(EqProfiles(), speaker, store = store)
        for (name in listOf("00:11:22:33:44:55", "XX:XX:XX:XX:A1:B2", "Pebble Buds")) {
            event(watch, speaker, named(name))
            watch.live.change { all, output -> all.withProfile(output, budsBass) }
            advanceTimeBy(LiveEq.SAVE_DELAY_MS + 1)
            runCurrent()
        }
        val written = file.readText()
        assertTrue(written, written.contains("bluetooth:Pebble Buds"))
        assertNull(written, address.find(written))

        // A file that cannot be read is moved aside and logged, by its size only.
        file.writeText("""{"profiles":{"bluetooth:00:11:22:33:44:55":""")
        store.load()
        assertTrue(logs.isNotEmpty())
        assertNull(logs.firstNotNullOfOrNull { address.find(it) })
    }

    @Test
    fun `each play keeps where it was heard as it starts, the car while one is connected, and only a playlist's id`() {
        // Insights' Where you listen (IN3): the kind of output music plays on as the song starts, read from the phone's
        // routing (Android 13 on) or the devices connected; Android Auto's sound goes through its own link, so a car
        // connected (Android Auto, a car's media centre: auto/CarBrowse.kt's access) is the car. Never a device's name.
        val service = File("src/main/java/app/lyrify/player/PlaybackService.kt").readText()
        assertTrue(service.contains("output = if (browse.inCar) OutputKind.CAR else readOutput(getSystemService(AudioManager::class.java)).kind,"))
        assertTrue(service.contains("playlistId = queue.source.key.takeIf { queue.source.kind == SourceKind.PLAYLIST },"))
        val car = File("src/main/java/app/lyrify/auto/CarBrowse.kt").readText()
        assertTrue(car.contains("val inCar: Boolean get() = Access.CAR in connected.values"))
        // A car's own Bluetooth cannot be told from earbuds without asking for nearby devices: it stays Bluetooth.
        assertEquals(OutputKind.BLUETOOTH, outputKey(OutputDevice(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "Made-up Car"))?.kind)
        assertEquals(OutputKind.CAR, outputKey(OutputDevice(AudioDeviceInfo.TYPE_BUS, null))?.kind)
    }

    @Test
    fun `the equalizer's code never reads a device's address`() {
        val sources = File("src/main/java/app/lyrify/audio").listFiles().orEmpty().toList() + File("src/main/java/app/lyrify/ui/AudioScreens.kt")
        assertTrue(sources.size > 3)
        for (source in sources) {
            assertNull(source.name, Regex("""\.address\b|getAddress\(""").find(source.readText()))
        }
    }
}
