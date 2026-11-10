package app.lyrify.player

import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import android.companion.ObservingDevicePresenceRequest
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaLibraryService
import app.lyrify.LOG_TAG
import app.lyrify.Lyrify
import app.lyrify.R
import app.lyrify.audio.OutputDevice
import app.lyrify.audio.OutputKind
import app.lyrify.audio.outputKey
import app.lyrify.logFailures
import app.lyrify.settings.ConnectDevice
import app.lyrify.settings.PlaybackSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

// Start when connected (lane P2b): a Bluetooth device picked in Settings connects, and KJB resumes the last queue at its
// place, through the playback service as a headset's Play after KJB was closed does. Android 13 and later: the Companion
// Device Manager tells KJB of the connect and lets it start the player from the background. The facts and their
// sources: handoff/start-when-connected-research.md.

/** What KJB knows a few seconds after a picked device connects. */
data class ConnectMoment(
    /** There is a last queue to resume. */
    val hasQueue: Boolean,
    /** KJB's own player plays, or means to. */
    val kjbPlaying: Boolean,
    /** Another app's music plays. */
    val otherPlaying: Boolean,
    /** A call rings or is on. */
    val inCall: Boolean,
    /** The device became the music output within [OUTPUT_WAIT_MS] of the connect. */
    val deviceIsOutput: Boolean,
)

/** Whether KJB resumes the last queue: only into the device, with nothing playing and no call. Otherwise it stays quiet. */
fun startsOnConnect(moment: ConnectMoment): Boolean =
    moment.hasQueue && !moment.kjbPlaying && !moment.otherPlaying && !moment.inCall && moment.deviceIsOutput

/** Whether the phone's audio [mode] (AudioManager.getMode) means a call: any mode but the normal one, ringing too. */
fun inCall(mode: Int): Boolean = mode != AudioManager.MODE_NORMAL

/** Android reports a device that is connected already when KJB starts listening for it: a connect this soon after Add is that report. */
const val ADD_QUIET_MS: Long = 30_000

/** Whether a connect at [nowMs] comes within [ADD_QUIET_MS] of the device's Add at [addedAtMs] (elapsed realtime; null when not added in this run). */
fun justAdded(addedAtMs: Long?, nowMs: Long): Boolean = addedAtMs != null && nowMs - addedAtMs < ADD_QUIET_MS

/** An output music is routed to: its AudioDeviceInfo type and [addressTail]. */
data class RoutedOutput(val type: Int, val addressTail: String?)

/**
 * The last two bytes of a Bluetooth [address] ("5E:A1"), the part Android leaves to apps without Bluetooth access
 * ("XX:XX:XX:XX:5E:A1"); null for anything that is not an address. Kept in memory only, never stored or logged.
 */
fun addressTail(address: String?): String? = address?.takeIf { it.length == MAC_LENGTH }?.takeLast(TAIL_LENGTH)?.uppercase()

private const val MAC_LENGTH: Int = 17
private const val TAIL_LENGTH: Int = 5

/**
 * Whether music plays on the picked device, whose address ends in [deviceTail]: a Bluetooth output among [routed] with
 * that tail, or any Bluetooth output when the phone hides the tail. The phone speaker, wired headphones and a call's
 * link never count.
 */
fun playsOnDevice(routed: List<RoutedOutput>, deviceTail: String?): Boolean = routed.any { output ->
    outputKey(OutputDevice(output.type, productName = null))?.kind == OutputKind.BLUETOOTH &&
        (output.addressTail == null || deviceTail == null || output.addressTail == deviceTail)
}

/** How long KJB waits for the device to become the music output after the connect. */
const val OUTPUT_WAIT_MS: Long = 10_000

/** How often it looks again between device events: a route can switch without one. */
const val OUTPUT_RECHECK_MS: Long = 500

/** Waits up to [waitMs] for [isOutput]: asked at once, after each event on [changes] and every [recheckMs]. True when it held in time. */
suspend fun awaitOutput(
    changes: ReceiveChannel<Unit>,
    waitMs: Long = OUTPUT_WAIT_MS,
    recheckMs: Long = OUTPUT_RECHECK_MS,
    isOutput: () -> Boolean,
): Boolean = withTimeoutOrNull(waitMs) {
    while (!isOutput()) withTimeoutOrNull(recheckMs) { changes.receive() }
    true
} ?: false

/** [device] picked: added at the end, or in its place with its new name when its association is listed already. */
fun PlaybackSettings.withConnectDevice(device: ConnectDevice): PlaybackSettings {
    val listed = connectDevices.any { it.associationId == device.associationId }
    return copy(connectDevices = if (listed) connectDevices.map { if (it.associationId == device.associationId) device else it } else connectDevices + device)
}

/** The device of [associationId] removed, the others kept. */
fun PlaybackSettings.withoutConnectDevice(associationId: Int): PlaybackSettings = copy(connectDevices = connectDevices.filterNot { it.associationId == associationId })

/** Only the devices Android still pairs with KJB ([live] association ids); one removed in Android's settings, or restored from a backup, goes. */
fun PlaybackSettings.keepingConnectDevices(live: Set<Int>): PlaybackSettings = copy(connectDevices = connectDevices.filter { it.associationId in live })

/** The row's line: the devices' names, or null for Off. */
fun connectNames(devices: List<ConnectDevice>): String? = devices.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.name }

/**
 * Whether the phone can do it: Android 13 or later, with companion devices. Android 11 has no presence events, and 12
 * and 12L have them by Bluetooth address only, without the association ids KJB keeps.
 */
fun connectSupported(sdk: Int, companionSetup: Boolean): Boolean = sdk >= Build.VERSION_CODES.TIRAMISU && companionSetup

/**
 * Start when connected in the app: Add (Android's picker), Remove, the list kept in the settings, and what the connect
 * handler ([ConnectService]) asks of this process: whether KJB plays, and which devices were just added.
 */
object StartWhenConnected {
    /** Whether KJB's player plays or means to; set by [ConnectPart] while the playback service runs. */
    @Volatile
    internal var kjbPlaying: Boolean = false

    /** When each device was added in this run, elapsed realtime ([justAdded]). */
    private val addedAtMs = ConcurrentHashMap<Int, Long>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + logFailures)

    /** Whether the row shows on this phone ([connectSupported]). */
    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.TIRAMISU)
    fun supported(context: Context): Boolean =
        connectSupported(Build.VERSION.SDK_INT, context.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP))

    /** Add headphones: Android's picker of Bluetooth devices, opened through [launch]; the device picked is [added]. */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun add(context: Context, launch: (IntentSender) -> Unit) {
        val app = context.applicationContext
        // No filter: every Bluetooth device the phone can find nearby.
        val request = AssociationRequest.Builder().addDeviceFilter(BluetoothDeviceFilter.Builder().build()).build()
        manager(app).associate(
            request,
            ContextCompat.getMainExecutor(app),
            object : CompanionDeviceManager.Callback() {
                override fun onAssociationPending(intentSender: IntentSender) = launch(intentSender)

                override fun onAssociationCreated(associationInfo: AssociationInfo) = added(app, associationInfo)

                // The user backed out, or no device was found.
                override fun onFailure(error: CharSequence?) {
                    Log.d(LOG_TAG, "start when connected: no device added")
                }
            },
        )
    }

    /** The device picked ([info], from the picker's callback or its result): listened for, and kept in the settings. Twice is once. */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun added(context: Context, info: AssociationInfo) {
        val app = context.applicationContext
        addedAtMs[info.id] = SystemClock.elapsedRealtime()
        observe(manager(app), info)
        val name = info.displayName?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: app.getString(R.string.connect_device)
        scope.launch { Lyrify.settings(app).update { it.copy(playback = it.playback.withConnectDevice(ConnectDevice(info.id, name))) } }
        Log.d(LOG_TAG, "start when connected: a device was added")
    }

    /** Remove: Android ends the pairing with KJB, its connect events with it, and the device leaves the settings. */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun remove(context: Context, associationId: Int) {
        val app = context.applicationContext
        try {
            manager(app).disassociate(associationId)
        } catch (e: RuntimeException) {
            // Gone already (Android's own settings): it leaves the list all the same.
            Log.w(LOG_TAG, "start when connected: no pairing to end (${e.javaClass.simpleName})")
        }
        addedAtMs.remove(associationId)
        scope.launch { Lyrify.settings(app).update { it.copy(playback = it.playback.withoutConnectDevice(associationId)) } }
    }

    /** The settings keep only the devices Android still pairs with KJB. */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun reconcile(context: Context) {
        val app = context.applicationContext
        val live = manager(app).myAssociations.map { it.id }.toSet()
        scope.launch { Lyrify.settings(app).update { it.copy(playback = it.playback.keepingConnectDevices(live)) } }
    }

    internal fun addedAt(associationId: Int): Long? = addedAtMs[associationId]

    /** Asks Android for the device's connects: by association from Android 16, by address before (passed on, never kept). */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun observe(manager: CompanionDeviceManager, info: AssociationInfo) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                manager.startObservingDevicePresence(ObservingDevicePresenceRequest.Builder().setAssociationId(info.id).build())
            } else {
                @Suppress("DEPRECATION")
                manager.startObservingDevicePresence(info.deviceMacAddress?.toString() ?: return)
            }
        } catch (e: Exception) {
            // DeviceNotAssociatedException (removed meanwhile) or a SecurityException.
            Log.w(LOG_TAG, "start when connected: not listening (${e.javaClass.simpleName})")
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun manager(context: Context): CompanionDeviceManager = context.getSystemService(CompanionDeviceManager::class.java)
}

/** Tells [StartWhenConnected] whether KJB plays: the session's player, while the playback service runs. */
class ConnectPart : ServicePart {
    private var player: Player? = null

    private val listener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            StartWhenConnected.kjbPlaying = playWhenReady
        }
    }

    override fun start(service: PlaybackService, session: MediaLibraryService.MediaLibrarySession) {
        player = session.player.also {
            it.addListener(listener)
            StartWhenConnected.kjbPlaying = it.playWhenReady
        }
    }

    override fun stop() {
        player?.removeListener(listener)
        player = null
        StartWhenConnected.kjbPlaying = false
    }
}

/**
 * Bound by Android while a device paired with KJB ([StartWhenConnected.add]) is connected; Android starts KJB's process
 * for it if needed. A picked device's connect waits up to 10 s for the device to take the music, then resumes the last
 * queue when [startsOnConnect] says so. Android 16 and later send both callbacks for one connect, so each version
 * listens to one. One connect at a time. The log gets the outcome only.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class ConnectService : CompanionDeviceService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + logFailures)
    private var waiting: Job? = null

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        if (event.event == DevicePresenceEvent.EVENT_BT_CONNECTED) connected(event.associationId)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) connected(associationInfo.id)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun connected(associationId: Int) {
        if (waiting?.isActive == true) return
        waiting = scope.launch { onConnected(associationId) }
    }

    private suspend fun onConnected(associationId: Int) {
        val picked = Lyrify.settings(this).settings.first().playback.connectDevices
        if (picked.none { it.associationId == associationId }) return
        if (justAdded(StartWhenConnected.addedAt(associationId), SystemClock.elapsedRealtime())) return
        val info = getSystemService(CompanionDeviceManager::class.java).myAssociations.firstOrNull { it.id == associationId } ?: return
        val audio = getSystemService(AudioManager::class.java)
        val output = deviceOutput(audio, addressTail(info.deviceMacAddress?.toString()))
        val kjb = StartWhenConnected.kjbPlaying
        val moment = ConnectMoment(hasQueue = true, kjbPlaying = kjb, otherPlaying = !kjb && audio.isMusicActive, inCall = inCall(audio.mode), deviceIsOutput = output)
        // The queues are read last, only when everything else holds.
        val start = startsOnConnect(moment) && startsOnConnect(moment.copy(hasQueue = hasQueue()))
        if (start) resume() else Log.d(LOG_TAG, "start when connected: stays quiet")
    }

    /** Whether the device, whose address ends in [tail], becomes the music output within 10 s: watched with an AudioDeviceCallback. */
    private suspend fun deviceOutput(audio: AudioManager, tail: String?): Boolean {
        val changes = Channel<Unit>(Channel.CONFLATED)
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                changes.trySend(Unit)
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                changes.trySend(Unit)
            }
        }
        audio.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        return try {
            awaitOutput(changes) {
                playsOnDevice(audio.getAudioDevicesForAttributes(MUSIC).map { RoutedOutput(it.type, addressTail(it.address)) }, tail)
            }
        } finally {
            audio.unregisterAudioDeviceCallback(callback)
        }
    }

    private suspend fun hasQueue(): Boolean {
        val core = PlayerGraph.core(this)
        return withTimeoutOrNull(QUEUES_WAIT_MS) { core.ready.first { it } } != null && core.book.value.current?.items?.isNotEmpty() == true
    }

    /** Play, as a headset's Play after KJB was closed: the playback service resumes the last queue at its place (onPlaybackResumption). */
    private fun resume() {
        val play = Intent(Intent.ACTION_MEDIA_BUTTON)
            .setClass(this, PlaybackService::class.java)
            .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
        try {
            ContextCompat.startForegroundService(this, play)
            Log.d(LOG_TAG, "start when connected: resumes")
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException: Android did not let the pairing start the player.
            Log.w(LOG_TAG, "start when connected: not allowed (${e.javaClass.simpleName})")
        }
    }

    private companion object {
        /** Music, as the player plays it, for where it is routed. */
        val MUSIC: AudioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()

        /** The queues are one small file; a phone that takes longer than this stays quiet. */
        const val QUEUES_WAIT_MS: Long = 5_000
    }
}
