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
import androidx.lifecycle.Lifecycle
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
import app.lyrify.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
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

/** An output music is routed to: its AudioDeviceInfo type, [addressTail] and product name. */
data class RoutedOutput(val type: Int, val addressTail: String?, val name: String? = null)

/** How KJB knows the picked device among the outputs: its [addressTail] and the [name] Android's picker showed. */
data class DeviceMatch(val addressTail: String?, val name: String?)

/**
 * The last two bytes of a Bluetooth [address] ("5E:A1"), the part Android leaves to apps without Bluetooth access
 * ("XX:XX:XX:XX:5E:A1"); null for anything that is not an address. Kept in memory only, never stored or logged.
 */
fun addressTail(address: String?): String? = address?.takeIf { it.length == MAC_LENGTH }?.takeLast(TAIL_LENGTH)?.uppercase()

private const val MAC_LENGTH: Int = 17
private const val TAIL_LENGTH: Int = 5

/**
 * Whether music plays on the picked [device]: a Bluetooth output among [routed] that is that device. The last two
 * address bytes decide when both sides have them; else the name, exact after trimming; with neither, it is not. Never
 * the phone speaker, wired headphones or a call's link.
 */
fun playsOnDevice(routed: List<RoutedOutput>, device: DeviceMatch): Boolean = routed.any { output ->
    outputKey(OutputDevice(output.type, productName = null))?.kind == OutputKind.BLUETOOTH && isDevice(output, device)
}

private fun isDevice(output: RoutedOutput, device: DeviceMatch): Boolean {
    if (output.addressTail != null && device.addressTail != null) return output.addressTail == device.addressTail
    val name = output.name?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    return name == device.name?.trim()
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

/** How long after a connect start its playback beginning is checked against the device ([pausesOnBegin]). */
const val START_GUARD_MS: Long = 30_000

/** A connect start on its way to the player: when the Play went out ([atMs], elapsed realtime) and for which device. */
data class ConnectStartSent(val atMs: Long, val device: DeviceMatch)

/**
 * Whether playback beginning at [nowMs] pauses: it is the connect start [sent] (within [START_GUARD_MS]) and the device
 * no longer holds the music. A disconnect before playback begins reaches no "Pause when disconnected" receiver.
 */
fun pausesOnBegin(sent: ConnectStartSent?, nowMs: Long, stillOnDevice: Boolean): Boolean =
    sent != null && nowMs - sent.atMs < START_GUARD_MS && !stillOnDevice

/** What a connect start reads from the phone and does: the phone's in [ConnectService], a fake in tests. Main thread. */
interface ConnectPhone {
    /** The picked device of [associationId], or null when it is not picked or Android no longer pairs it with KJB. */
    suspend fun picked(associationId: Int): DeviceMatch?

    /** Whether the device was added this soon before its connect ([justAdded]). */
    fun addedJustNow(associationId: Int): Boolean

    /** Whether [device] becomes the music output within [OUTPUT_WAIT_MS] ([awaitOutput]). */
    suspend fun becomesOutput(device: DeviceMatch): Boolean

    /** The conditions as they are now, with [device] still the music output or not. */
    fun moment(device: DeviceMatch, hasQueue: Boolean): ConnectMoment

    /** Whether there is a last queue to resume. */
    suspend fun hasQueue(): Boolean

    /** The Play that resumes the last queue. */
    fun play(device: DeviceMatch)
}

/**
 * The connects of picked devices, one waited on at a time: a later connect of a picked device starts the wait over for
 * that device, and the disconnect of the device waited on ends it. Each wait ends in [startsOnConnect]'s answer, read
 * again as the Play goes out. The [log] gets the outcome only. Main thread.
 */
class ConnectStarts(private val scope: CoroutineScope, private val phone: ConnectPhone, private val log: (String) -> Unit) {
    private var waiting: Job? = null
    private var waitingFor: Int? = null

    fun connected(associationId: Int) {
        scope.launch {
            val device = phone.picked(associationId) ?: return@launch
            if (phone.addedJustNow(associationId)) return@launch
            val job = coroutineContext.job
            waiting?.takeIf { it !== job }?.cancel()
            waiting = job
            waitingFor = associationId
            try {
                log(if (start(device)) "start when connected: resumes" else "start when connected: stays quiet")
            } finally {
                if (waiting === job) {
                    waiting = null
                    waitingFor = null
                }
            }
        }
    }

    fun disconnected(associationId: Int) {
        if (waitingFor != associationId) return
        waiting?.cancel()
        waiting = null
        waitingFor = null
    }

    private suspend fun start(device: DeviceMatch): Boolean {
        if (!phone.becomesOutput(device)) return false
        // The queues are read last, only when everything else holds.
        if (!startsOnConnect(phone.moment(device, hasQueue = true))) return false
        val hasQueue = phone.hasQueue()
        // Read again as the Play goes out: the music may have left the device meanwhile.
        if (!startsOnConnect(phone.moment(device, hasQueue))) return false
        phone.play(device)
        return true
    }
}

/**
 * Opens Android's picker through [launch] while KJB is on screen ([state], its activity's); false when it is not, or
 * when the launcher is gone (unregistered). Android may hand the picker back after the sheet that asked has closed.
 */
fun openPicker(state: Lifecycle.State, launch: () -> Unit): Boolean {
    if (!state.isAtLeast(Lifecycle.State.STARTED)) return false
    return try {
        launch()
        true
    } catch (_: IllegalStateException) {
        false
    }
}

/** The saved devices kept to those Android still pairs with KJB ([live] association ids); false when it could not be stored. */
suspend fun reconcileConnectDevices(store: SettingsStore, live: Set<Int>): Boolean =
    store.update { it.copy(playback = it.playback.keepingConnectDevices(live)) }

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

    /** The connect start on its way to the player, for [ConnectPart]'s check as playback begins. */
    @Volatile
    internal var sent: ConnectStartSent? = null

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
        scope.launch { reconcileConnectDevices(Lyrify.settings(app), live) }
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

/**
 * Start when connected inside the playback service: tells [StartWhenConnected] whether KJB plays, and, as a connect
 * start's playback begins, checks the device still holds the music ([pausesOnBegin]). After that, ExoPlayer's own
 * "Pause when disconnected" (handleAudioBecomingNoisy) takes over; it misses a disconnect before playback begins.
 */
class ConnectPart : ServicePart {
    private var player: Player? = null
    private var audio: AudioManager? = null

    private val listener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            StartWhenConnected.kjbPlaying = playWhenReady
            if (playWhenReady) checkDevice()
        }
    }

    override fun start(service: PlaybackService, session: MediaLibraryService.MediaLibrarySession) {
        audio = service.getSystemService(AudioManager::class.java)
        player = session.player.also {
            it.addListener(listener)
            StartWhenConnected.kjbPlaying = it.playWhenReady
        }
    }

    override fun stop() {
        player?.removeListener(listener)
        player = null
        audio = null
        StartWhenConnected.kjbPlaying = false
    }

    /** The first play after a connect start pauses when the device no longer holds the music: never the phone speaker. */
    private fun checkDevice() {
        val sent = StartWhenConnected.sent ?: return
        StartWhenConnected.sent = null
        val audio = audio ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (pausesOnBegin(sent, SystemClock.elapsedRealtime(), playsOnDevice(audio.musicRoutes(), sent.device))) {
            player?.pause()
            Log.d(LOG_TAG, "start when connected: the device left, paused")
        }
    }
}

/**
 * Bound by Android while a device paired with KJB ([StartWhenConnected.add]) is connected; Android starts KJB's process
 * for it if needed. Connects and disconnects go to [ConnectStarts]; this is its phone side. Android 16 and later send
 * both callbacks for one event, so each version listens to one. On start, the saved devices are checked against
 * Android's pairings. The log gets outcomes only.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class ConnectService : CompanionDeviceService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + logFailures)
    private val starts = ConnectStarts(scope, Phone()) { Log.d(LOG_TAG, it) }

    override fun onCreate() {
        super.onCreate()
        StartWhenConnected.reconcile(this)
    }

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        when (event.event) {
            DevicePresenceEvent.EVENT_BT_CONNECTED -> starts.connected(event.associationId)
            DevicePresenceEvent.EVENT_BT_DISCONNECTED -> starts.disconnected(event.associationId)
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) starts.connected(associationInfo.id)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onDeviceDisappeared(associationInfo: AssociationInfo) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) starts.disconnected(associationInfo.id)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private inner class Phone : ConnectPhone {
        private val audio: AudioManager get() = getSystemService(AudioManager::class.java)

        override suspend fun picked(associationId: Int): DeviceMatch? {
            val picked = Lyrify.settings(this@ConnectService).settings.first().playback.connectDevices
            if (picked.none { it.associationId == associationId }) return null
            val info = getSystemService(CompanionDeviceManager::class.java).myAssociations.firstOrNull { it.id == associationId } ?: return null
            return DeviceMatch(addressTail(info.deviceMacAddress?.toString()), info.displayName?.toString())
        }

        override fun addedJustNow(associationId: Int): Boolean = justAdded(StartWhenConnected.addedAt(associationId), SystemClock.elapsedRealtime())

        /** Watched with an AudioDeviceCallback, unregistered however the wait ends. */
        override suspend fun becomesOutput(device: DeviceMatch): Boolean {
            val audio = audio
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
                awaitOutput(changes) { playsOnDevice(audio.musicRoutes(), device) }
            } finally {
                audio.unregisterAudioDeviceCallback(callback)
            }
        }

        override fun moment(device: DeviceMatch, hasQueue: Boolean): ConnectMoment {
            val audio = audio
            val kjb = StartWhenConnected.kjbPlaying
            return ConnectMoment(hasQueue, kjbPlaying = kjb, otherPlaying = !kjb && audio.isMusicActive, inCall = inCall(audio.mode), deviceIsOutput = playsOnDevice(audio.musicRoutes(), device))
        }

        override suspend fun hasQueue(): Boolean {
            val core = PlayerGraph.core(this@ConnectService)
            return withTimeoutOrNull(QUEUES_WAIT_MS) { core.ready.first { it } } != null && core.book.value.current?.items?.isNotEmpty() == true
        }

        /** As a headset's Play after KJB was closed: the playback service resumes the last queue at its place (onPlaybackResumption). */
        override fun play(device: DeviceMatch) {
            val play = Intent(Intent.ACTION_MEDIA_BUTTON)
                .setClass(this@ConnectService, PlaybackService::class.java)
                .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
            StartWhenConnected.sent = ConnectStartSent(SystemClock.elapsedRealtime(), device)
            try {
                ContextCompat.startForegroundService(this@ConnectService, play)
            } catch (e: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException: Android did not let the pairing start the player.
                StartWhenConnected.sent = null
                Log.w(LOG_TAG, "start when connected: not allowed (${e.javaClass.simpleName})")
            }
        }
    }

    private companion object {
        /** The queues are one small file; a phone that takes longer than this stays quiet. */
        const val QUEUES_WAIT_MS: Long = 5_000
    }
}

/** Where music, as the player plays it, is routed now. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal fun AudioManager.musicRoutes(): List<RoutedOutput> {
    val music = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    return getAudioDevicesForAttributes(music).map { RoutedOutput(it.type, addressTail(it.address), it.productName?.toString()) }
}
