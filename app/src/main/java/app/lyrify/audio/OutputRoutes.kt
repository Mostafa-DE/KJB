package app.lyrify.audio

import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
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
import kotlinx.coroutines.coroutineScope
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
 * What KJB reads of a device of [type] named [productName]: never its address. A Bluetooth output's name is hidden (it
 * shares the one Bluetooth profile) when it comes back as the phone's own model ([phoneModel]; AudioDeviceInfo
 * .getProductName falls back to it when the device's port has no name) or as an address, which is never stored.
 */
fun outputDevice(type: Int, productName: CharSequence?, phoneModel: String): OutputDevice {
    val name = productName?.toString()
    val hidden = type in BLUETOOTH_TYPES && name != null && (name.trim() == phoneModel.trim() || BLUETOOTH_ADDRESS.matches(name.trim()))
    return OutputDevice(type, name.takeUnless { hidden })
}

/** A Bluetooth address, also as the phone hides it from apps ("XX:XX:XX:XX:A1:B2"). */
private val BLUETOOTH_ADDRESS = Regex("""([0-9A-Fa-fXx]{2}[:-]){5}[0-9A-Fa-fXx]{2}""")

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
 * The output music most likely plays on among every output connected ([devices]), when the phone does not say: the
 * first by kind, Bluetooth, wired, USB, a car, anything else, then the speaker. With none, the speaker.
 */
fun currentOutput(devices: List<OutputDevice>): OutputKey {
    val outputs = devices.mapNotNull(::outputKey)
    return OUTPUT_PRIORITY.firstNotNullOfOrNull { kind -> outputs.firstOrNull { it.kind == kind } } ?: OutputKey.SPEAKER
}

private val OUTPUT_PRIORITY: List<OutputKind> =
    listOf(OutputKind.BLUETOOTH, OutputKind.WIRED, OutputKind.USB, OutputKind.CAR, OutputKind.OTHER, OutputKind.SPEAKER)

/**
 * The output music plays on: on Android 13 and later the phone says ([routed], getAudioDevicesForAttributes), and the
 * first of those that plays music is the answer, in the phone's order. Below 13, or when it names none, the output is
 * chosen by kind among every output connected ([connected], read only then).
 */
fun musicOutput(routed: List<OutputDevice>, connected: () -> List<OutputDevice>): OutputKey =
    routed.firstNotNullOfOrNull(::outputKey) ?: currentOutput(connected())

/** [musicOutput] as the phone reports it now. */
fun readOutput(audio: AudioManager): OutputKey {
    val music = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    val routed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) audio.getAudioDevicesForAttributes(music) else emptyList()
    return musicOutput(routed.map { it.toOutputDevice() }) { audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.toOutputDevice() } }
}

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

/** How long the devices must stay as they are before music counts as moved: a Bluetooth device comes in two or three events. */
const val OUTPUT_SETTLE_MS: Long = 700

/**
 * Follows the output music plays on ([read]): at once at the start; at once after each of [routes] (the song playing
 * moved, or started); and after each of [events] (a device came or went) once the devices have stayed as they are for
 * [OUTPUT_SETTLE_MS]. Each switch plays that output's profile, which the chain glides to mid-song. [announce] hears each
 * switch after the first, when the equalizer is on for the output left or the one now playing; between two outputs that
 * both play unchanged it says nothing.
 */
suspend fun followOutputs(events: Flow<Unit>, routes: Flow<Unit>, read: () -> OutputKey, live: LiveEq, announce: (OutputKey) -> Unit): Unit = coroutineScope {
    fun follow() {
        val left = live.output.value
        val now = read()
        if (!live.switchTo(now)) return
        val profiles = live.profiles.value ?: return
        if (profiles.profile(left).sound.enabled || profiles.profile(now).sound.enabled) announce(now)
    }
    live.switchTo(read())
    // A route change never cuts a device event's settling short: other apps' playback changes come often.
    launch {
        events.collectLatest {
            delay(OUTPUT_SETTLE_MS)
            follow()
        }
    }
    routes.collect { follow() }
}

/**
 * The output watch, while the player runs: the phone's device events (AudioDeviceCallback), and every change of what
 * plays where (AudioPlaybackCallback: a song moved in the phone's output switcher, between outputs that stay connected,
 * brings no device event) and each start of playing go to [followOutputs], whose switches show "Equalizer: Galaxy Buds"
 * as the phone's own toast, as the output mostly changes while KJB is not on screen. When the player stops, profiles a
 * failed write left unsaved are written ([LiveEq.flush]). Logs nothing.
 */
class OutputRoutesPart : ServicePart {
    private var onStop: () -> Unit = {}

    override fun start(service: PlaybackService, session: MediaLibraryService.MediaLibrarySession) {
        val context = service.applicationContext
        val audio = context.getSystemService(AudioManager::class.java)
        val live = LiveEq.of(context)
        val events = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        val routes = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        val devices = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                events.tryEmit(Unit)
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                events.tryEmit(Unit)
            }
        }
        val moved = object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: List<AudioPlaybackConfiguration>?) {
                routes.tryEmit(Unit)
            }
        }
        val playing = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) routes.tryEmit(Unit)
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + logFailures)
        scope.launch {
            followOutputs(events, routes, { readOutput(audio) }, live) { output ->
                Toast.makeText(context, context.getString(R.string.eq_switched, outputLabel(output) { context.getString(it) }), Toast.LENGTH_SHORT).show()
            }
        }
        val main = Handler(Looper.getMainLooper())
        audio.registerAudioDeviceCallback(devices, main)
        audio.registerAudioPlaybackCallback(moved, main)
        session.player.addListener(playing)
        onStop = {
            audio.unregisterAudioDeviceCallback(devices)
            audio.unregisterAudioPlaybackCallback(moved)
            session.player.removeListener(playing)
            scope.cancel()
            live.flush()
        }
    }

    override fun stop() = onStop()
}
