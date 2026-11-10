package app.lyrify.audio

import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.media3.common.Player
import androidx.media3.session.MediaLibraryService
import app.lyrify.R
import app.lyrify.logFailures
import app.lyrify.player.PlaybackService
import app.lyrify.player.ServicePart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

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

fun AudioDeviceInfo.toOutputDevice(): OutputDevice = outputDevice(type, productName, Build.MODEL)

/**
 * What KJB reads of a device of [type] named [productName]. AudioDeviceInfo.getProductName falls back to the phone's own
 * model ([phoneModel]) when the device's port has no name, so a Bluetooth output named like the phone has its name hidden.
 */
fun outputDevice(type: Int, productName: CharSequence?, phoneModel: String): OutputDevice {
    val name = productName?.toString()
    return OutputDevice(type, name.takeUnless { type in BLUETOOTH_TYPES && it?.trim() == phoneModel.trim() })
}

/** The Bluetooth outputs that play music; each is told apart by its name. */
private val BLUETOOTH_TYPES: Set<Int> = setOf(
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
    AudioDeviceInfo.TYPE_BLE_HEADSET,
    AudioDeviceInfo.TYPE_BLE_SPEAKER,
    AudioDeviceInfo.TYPE_BLE_BROADCAST,
    AudioDeviceInfo.TYPE_HEARING_AID,
)

/** The output [device] is, or null for one that never plays music (the earpiece, a call's Bluetooth link). */
fun outputKey(device: OutputDevice): OutputKey? = when (device.type) {
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> OutputKey.SPEAKER
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_AUX_LINE ->
        OutputKey(OutputKind.WIRED)
    AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY -> OutputKey(OutputKind.USB)
    in BLUETOOTH_TYPES -> OutputKey(OutputKind.BLUETOOTH, device.productName?.trim()?.takeIf { it.isNotEmpty() })
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


/** The output's name, for "Now: …" and the toast: a Bluetooth device's own, else its kind's ([text] reads a string). */
fun outputLabel(output: OutputKey, text: (Int) -> String): String = output.name ?: text(
    when (output.kind) {
        OutputKind.SPEAKER -> R.string.eq_output_speaker
        OutputKind.WIRED -> R.string.eq_output_wired
        OutputKind.USB -> R.string.eq_output_usb
        OutputKind.BLUETOOTH -> R.string.eq_output_bluetooth
        OutputKind.CAR -> R.string.eq_output_car
        OutputKind.OTHER -> R.string.eq_output_other
    },
)

/**
 * The devices music plays on now: on Android 13 and later the phone says which (getAudioDevicesForAttributes, usually
 * one); before that, or when it says none, every output connected, for [currentOutput]'s order.
 */
fun musicDevices(audio: AudioManager): List<OutputDevice> {
    val music = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    val routed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) audio.getAudioDevicesForAttributes(music) else emptyList()
    return routed.ifEmpty { audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList() }.map { it.toOutputDevice() }
}

/** How long the devices must stay as they are before music counts as moved: a Bluetooth device comes in two or three events. */
const val OUTPUT_SETTLE_MS: Long = 700

/**
 * Follows the output music plays on: at once from [read] (the devices now), then after each of [events], once the
 * devices have stayed as they are for [OUTPUT_SETTLE_MS], the equalizer plays that output's profile. [announce] hears
 * each switch after the first, when the equalizer is on for the output left or the one now playing; between two outputs
 * that both play unchanged it says nothing.
 */
suspend fun followOutputs(events: Flow<Unit>, read: () -> List<OutputDevice>, live: LiveEq, announce: (OutputKey) -> Unit) {
    live.switchTo(currentOutput(read()))
    events.collectLatest {
        delay(OUTPUT_SETTLE_MS)
        val left = live.output.value
        val now = switchedOutput(left, read()) ?: return@collectLatest
        live.switchTo(now)
        val profiles = live.profiles.value ?: return@collectLatest
        if (profiles.profile(left).sound.enabled || profiles.profile(now).sound.enabled) announce(now)
    }
}

/**
 * The output watch, while the player runs: the phone's device events (AudioDeviceCallback) and each start of playing (a
 * route picked in the phone's output switcher brings no device event) go to [followOutputs], whose switches show
 * "Equalizer: Galaxy Buds" as the phone's own toast, as the output mostly changes while KJB is not on screen. Logs nothing.
 */
class OutputRoutesPart : ServicePart {
    private var onStop: () -> Unit = {}

    override fun start(service: PlaybackService, session: MediaLibraryService.MediaLibrarySession) {
        val context = service.applicationContext
        val audio = context.getSystemService(AudioManager::class.java)
        val events = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        val devices = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                events.tryEmit(Unit)
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                events.tryEmit(Unit)
            }
        }
        val playing = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) events.tryEmit(Unit)
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + logFailures)
        scope.launch {
            followOutputs(events, { musicDevices(audio) }, LiveEq.of(context)) { output ->
                Toast.makeText(context, context.getString(R.string.eq_switched, outputLabel(output) { context.getString(it) }), Toast.LENGTH_SHORT).show()
            }
        }
        audio.registerAudioDeviceCallback(devices, Handler(Looper.getMainLooper()))
        session.player.addListener(playing)
        onStop = {
            audio.unregisterAudioDeviceCallback(devices)
            session.player.removeListener(playing)
            scope.cancel()
        }
    }

    override fun stop() = onStop()
}
