package app.lyrify.dl

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import app.lyrify.addon.shared.AddonKeepAlive
import app.lyrify.addon.shared.CallerCheck
import app.lyrify.addon.shared.ExemptionActivity
import app.lyrify.addon.shared.ForegroundPort
import app.lyrify.addon.shared.JobForeground
import app.lyrify.dl.api.DlEnd
import app.lyrify.dl.api.DlFile
import app.lyrify.dl.api.DlInfo
import app.lyrify.dl.api.DlNames
import app.lyrify.dl.api.DlProgress
import app.lyrify.dl.api.DlStatus
import app.lyrify.dl.api.DlWire
import app.lyrify.dl.api.IDlAddon
import app.lyrify.dl.api.IDlInfoCallback
import app.lyrify.dl.api.IDlRunCallback
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.io.File

/** Logcat's tag for the add-on. Its lines carry ids, counts, stages and exception classes, never a link, a title or a path. */
const val LOG_TAG: String = "KJB-DL"

/** How the add-on introduces itself to YouTube's oEmbed. */
val USER_AGENT: String = "KJB-Downloads/${BuildConfig.VERSION_NAME} ( ${BuildConfig.REPOSITORY} )"

/** When yt-dlp last looked for an update, in the add-on's own preferences. */
private class PrefsMemory(context: Context) : UpdateMemory {
    private val prefs = context.getSharedPreferences("ytdlp", Context.MODE_PRIVATE)

    override var lastCheck: Long?
        get() = prefs.getLong(KEY, 0).takeIf { it > 0 }
        set(value) {
            prefs.edit().apply { if (value == null) remove(KEY) else putLong(KEY, value) }.apply()
        }

    private companion object {
        const val KEY = "lastCheck"
    }
}

/** KJB of this build type, signed with its pinned certificate: the one caller of the add-on's service and activity. */
fun Context.kjbCallers(): CallerCheck = CallerCheck(
    packageName,
    BuildConfig.KJB_PACKAGE,
    BuildConfig.KJB_CERT_SHA256,
    packagesFor = packageManager::getPackagesForUid,
    hasCert = { uid, sha -> packageManager.hasSigningCertificate(uid, sha, PackageManager.CERT_INPUT_SHA256) },
)

/** The add-on's own request to be left out of the phone's battery optimisation ([ExemptionActivity]), started by KJB only. */
class DlExemptionActivity : ExemptionActivity() {
    override val callers: CallerCheck by lazy { kjbCallers() }
}

/**
 * The add-on's one service, bound by KJB only ([CallerCheck] on every call, behind the signature permission): link lookups
 * and downloads ([DlRuns]) and yt-dlp's updates. KJB binds it while it works, from its own screen or foreground service,
 * and starts it for the foreground once it took a link: it runs in the foreground with its own notice from the link taken
 * to the file handed back ([JobForeground], dataSync), as a phone's app freezer leaves alone an app at work in the
 * foreground, not a service another app binds. KJB going away (its process dies, or it unbinds) stops what it started; a
 * download's parts stay for its next run.
 */
class DlService : Service() {
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> Log.e(LOG_TAG, "work failed: ${e.javaClass.simpleName}") },
    )

    private val callers by lazy { kjbCallers() }

    private val memory by lazy { PrefsMemory(this) }

    private val runs: DlRuns by lazy {
        val log: (String) -> Unit = { Log.d(LOG_TAG, it) }
        DlRuns(
            scope = scope,
            ytdlp = LibraryYtdlp(applicationContext, cacheDir, log = log),
            folder = File(cacheDir, "dl"),
            memory = memory,
            clock = System::currentTimeMillis,
            monotonic = SystemClock::elapsedRealtime,
            quickInfo = { Info.quick(it, USER_AGENT) },
            log = log,
            ended = { foreground.jobEnded() },
            // The main thread: no run's work is done there.
            beats = Dispatchers.Main,
        ).also { it.sweep() }
    }

    private val foreground: JobForeground<PendingIntent> by lazy {
        JobForeground(
            { ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC },
            object : ForegroundPort<PendingIntent> {
                override fun startForeground(type: Int, tap: PendingIntent?) = this@DlService.startForeground(NOTICE_ID, notice(tap), type)

                override fun stopForeground() = this@DlService.stopForeground(STOP_FOREGROUND_REMOVE)

                override fun stopSelf(startId: Int?) {
                    if (startId == null) this@DlService.stopSelf() else this@DlService.stopSelfResult(startId)
                }
            },
            busy = { runs.busy },
            fromKjb = { tap -> AddonKeepAlive.fromKjb(tap, callers) },
            endJob = { runs.timeUp() },
            log = { Log.i(LOG_TAG, it) },
        )
    }

    /**
     * Served only while the bind permission is this add-on's own, at signature level ([CallerCheck.guardsBind]); the
     * caller is not known here (onBind runs outside its transaction), so each call checks it.
     */
    override fun onBind(intent: Intent): IBinder? {
        val permission = try {
            packageManager.getPermissionInfo(DlNames.permission(packageName), 0)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
        if (!callers.guardsBind(permission?.packageName, permission?.protection == PermissionInfo.PROTECTION_SIGNATURE)) {
            Log.w(LOG_TAG, "bind refused: the bind permission is not this add-on's own")
            return null
        }
        return binder
    }

    /**
     * KJB's start once the add-on took a link (Context.startForegroundService, [JobForeground]): into the foreground at
     * once, as Android requires within seconds. Never started again by Android on its own.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        foreground.start(startId, intent?.let(AddonKeepAlive::tap))
        return START_NOT_STICKY
    }

    /** Android's time for the foreground service ran out (Android 15+): out of it within seconds, or Android ends the add-on. */
    override fun onTimeout(startId: Int, fgsType: Int) = foreground.timeUp()

    /**
     * The notice while it works, in a quiet channel: the same words for every link, nothing of it (KJB's own notification
     * names the download). A tap opens KJB's Downloads page through [tap], KJB's own. Without the notification permission,
     * which the add-on never asks for, it shows in the phone's list of apps at work only, and the service runs all the same.
     */
    private fun notice(tap: PendingIntent?): Notification {
        val channel = NotificationChannel(NOTICE_CHANNEL, getString(R.string.work_channel), NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        return Notification.Builder(this, NOTICE_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_kjb)
            .setContentTitle(getString(R.string.work_title))
            .setContentIntent(tap)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    /** KJB let go of the add-on: its runs stop, their parts kept for when it comes back, and it leaves the foreground. */
    override fun onUnbind(intent: Intent): Boolean {
        foreground.unbound()
        runs.cancelAll()
        return false
    }

    override fun onDestroy() {
        runs.cancelAll()
        scope.cancel()
        super.onDestroy()
    }

    private val binder = object : IDlAddon.Stub() {
        override fun apiVersion(): Int = checked { IDlAddon.VERSION }

        override fun status(): String = checked { DlWire.encode(DlStatus(runs.version(), memory.lastCheck)) }

        override fun info(url: String, callback: IDlInfoCallback): Long = checked {
            val events = InfoCallback(callback)
            events.lookup = runs.info(url, events)
            events.lookup
        }

        override fun cancelInfo(lookup: Long) = checked { runs.cancelInfo(lookup) }

        override fun start(request: String, callback: IDlRunCallback): Long = checked {
            val events = RunCallback(callback)
            events.run = runs.start(DlWire.request(request), events)
            events.run
        }

        override fun deliver(run: Long, media: ParcelFileDescriptor, cover: ParcelFileDescriptor?) = checked(media, cover) {
            // The add-on's own copies of KJB's descriptors, from the call: closed once streamed into, or at once.
            runs.deliver(run, ParcelFileDescriptor.AutoCloseOutputStream(media), cover?.let { ParcelFileDescriptor.AutoCloseOutputStream(it) })
        }

        override fun cancel(run: Long, keepParts: Boolean) = checked { runs.cancel(run, keepParts) }

        override fun discard(key: String) = checked { runs.discard(key) }

        // Blocks this binder thread for the update (seconds); KJB calls it off its main thread.
        override fun update(): String = checked { DlWire.encode(runBlocking { runs.update() }) }
    }

    /**
     * [block] for KJB only: the calling uid is checked first ([CallerCheck]), on the binder thread that carries it. A
     * refused call closes the descriptors it brought ([owned]), so none is left open.
     */
    private inline fun <T> checked(vararg owned: ParcelFileDescriptor?, block: () -> T): T {
        try {
            callers.enforce(Binder.getCallingUid())
        } catch (e: SecurityException) {
            owned.forEach { runCatching { it?.close() } }
            throw e
        }
        return block()
    }

    /**
     * One run's events to its caller, by binder. That caller's process dying stops that run, its parts kept (KJB starts it
     * again from them): its death link is made before the run starts, and let go when the run ends.
     */
    private inner class RunCallback(private val callback: IDlRunCallback) : RunEvents {
        @Volatile
        var run: Long = 0

        private val death = IBinder.DeathRecipient { runs.cancel(run, keepParts = true) }
        private val linked = try {
            callback.asBinder().linkToDeath(death, 0)
            true
        } catch (_: RemoteException) {
            // Already gone: the run's first report fails and stops it.
            false
        }

        override fun onProgress(progress: DlProgress) = send { callback.onProgress(run, DlWire.encode(progress)) }

        override fun onFile(file: DlFile) = send { callback.onFile(run, DlWire.encode(file)) }

        override fun onBeat() = send { callback.onBeat(run) }

        override fun onEnd(end: DlEnd) {
            send { callback.onEnd(run, DlWire.encode(end)) }
            if (linked) runCatching { callback.asBinder().unlinkToDeath(death, 0) }
        }

        private inline fun send(call: () -> Unit) {
            try {
                call()
            } catch (e: RemoteException) {
                Log.w(LOG_TAG, "KJB is gone (${e.javaClass.simpleName}); stopping run $run")
                runs.cancel(run, keepParts = true)
            }
        }
    }

    /** One lookup's events to its caller; the caller gone stops the lookup. */
    private inner class InfoCallback(private val callback: IDlInfoCallback) : InfoEvents {
        @Volatile
        var lookup: Long = 0

        private val death = IBinder.DeathRecipient { runs.cancelInfo(lookup) }
        private val linked = try {
            callback.asBinder().linkToDeath(death, 0)
            true
        } catch (_: RemoteException) {
            false
        }

        override fun onInfo(info: DlInfo) {
            send { callback.onInfo(lookup, DlWire.encode(info)) }
            if (!info.partial) unlink()
        }

        override fun onFailed(end: DlEnd.Failed) {
            send { callback.onFailed(lookup, DlWire.encode(end)) }
            unlink()
        }

        private fun unlink() {
            if (linked) runCatching { callback.asBinder().unlinkToDeath(death, 0) }
        }

        private inline fun send(call: () -> Unit) {
            try {
                call()
            } catch (_: RemoteException) {
                runs.cancelInfo(lookup)
            }
        }
    }

    private companion object {
        /** The notice while it works: its id, and its channel, "Downloads work". */
        const val NOTICE_ID = 1
        const val NOTICE_CHANNEL = "dl_work"
    }
}
