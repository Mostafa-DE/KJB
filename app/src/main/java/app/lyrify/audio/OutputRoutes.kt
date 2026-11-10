package app.lyrify.audio

import android.media.AudioDeviceInfo

/** The kinds of output an equalizer profile belongs to. Only Bluetooth outputs are told apart, by their name. */
enum class OutputKind { SPEAKER, WIRED, USB, BLUETOOTH, CAR, OTHER }

/**
 * The output a profile belongs to: its kind, plus the device name for Bluetooth ("Galaxy Buds"), never its address.
 * A Bluetooth output whose name the phone hides is plain Bluetooth: they then share one profile.
 */
data class OutputKey(val kind: OutputKind, val name: String? = null) {
    init {
        require(name == null || (kind == OutputKind.BLUETOOTH && name.isNotBlank() && name == name.trim())) { "only Bluetooth outputs are named" }
    }

    /** The stored form, `eq-profiles.json`'s key: "speaker", "bluetooth", "bluetooth:Galaxy Buds". */
    val id: String get() = kind.name.lowercase() + (name?.let { "$NAME_MARK$it" } ?: "")

    companion object {
        val SPEAKER: OutputKey = OutputKey(OutputKind.SPEAKER)

        private const val NAME_MARK: Char = ':'

        /** The output stored as [id]; null for one this version does not know. */
        fun parse(id: String): OutputKey? {
            val kind = OutputKind.entries.firstOrNull { it.name.lowercase() == id.substringBefore(NAME_MARK) } ?: return null
            if (NAME_MARK !in id) return OutputKey(kind)
            val name = id.substringAfter(NAME_MARK)
            return if (kind == OutputKind.BLUETOOTH && name.isNotBlank() && name == name.trim()) OutputKey(kind, name) else null
        }
    }
}

/** What KJB reads from an AudioDeviceInfo: its type and product name, never its address. A seam, so routing tests on the JVM. */
data class OutputDevice(val type: Int, val productName: String?)

fun AudioDeviceInfo.toOutputDevice(): OutputDevice = OutputDevice(type, productName?.toString())

/** The output [device] is, or null for one that never plays music (the earpiece, a call's Bluetooth link). */
fun outputKey(device: OutputDevice): OutputKey? = when (device.type) {
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> OutputKey.SPEAKER
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_AUX_LINE ->
        OutputKey(OutputKind.WIRED)
    AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY -> OutputKey(OutputKind.USB)
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_BLE_BROADCAST,
    AudioDeviceInfo.TYPE_HEARING_AID,
    -> OutputKey(OutputKind.BLUETOOTH, device.productName?.trim()?.takeIf { it.isNotEmpty() })
    // Android Automotive's own outputs; a phone in a car over Bluetooth is a Bluetooth output named after the car.
    AudioDeviceInfo.TYPE_BUS -> OutputKey(OutputKind.CAR)
    // Calls and the phone's internals: the earpiece, a call's Bluetooth link, telephony, screen capture, the FM radio.
    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_TELEPHONY, AudioDeviceInfo.TYPE_REMOTE_SUBMIX,
    AudioDeviceInfo.TYPE_FM, AudioDeviceInfo.TYPE_UNKNOWN,
    -> null
    else -> OutputKey(OutputKind.OTHER)
}

/**
 * The output music plays on, among [devices]: on Android 13 and later the phone says which (getAudioDevicesForAttributes),
 * before that every connected output is listed (getDevices) and the first by priority wins: Bluetooth, wired, USB, a car,
 * anything else, then the speaker. With none, the speaker.
 */
fun currentOutput(devices: List<OutputDevice>): OutputKey {
    val outputs = devices.mapNotNull(::outputKey)
    return OUTPUT_PRIORITY.firstNotNullOfOrNull { kind -> outputs.firstOrNull { it.kind == kind } } ?: OutputKey.SPEAKER
}

private val OUTPUT_PRIORITY: List<OutputKind> =
    listOf(OutputKind.BLUETOOTH, OutputKind.WIRED, OutputKind.USB, OutputKind.CAR, OutputKind.OTHER, OutputKind.SPEAKER)

/** After a device event: the output music plays on now when it is not [now] (switch profile, show the toast), else null. */
fun switchedOutput(now: OutputKey?, devices: List<OutputDevice>): OutputKey? = currentOutput(devices).takeIf { it != now }
