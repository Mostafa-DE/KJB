package app.lyrify.audio

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which output plays, which profile key it has, and the profile switching as devices come and go. Made-up device names. */
class OutputRoutesTest {
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
            switchedOutput(now, devices.toList())?.let {
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
}
