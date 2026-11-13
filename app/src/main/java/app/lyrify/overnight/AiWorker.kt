package app.lyrify.overnight

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.lyrify.BuildConfig
import app.lyrify.LOG_TAG
import app.lyrify.Lyrify
import app.lyrify.R
import app.lyrify.addon.Addon
import app.lyrify.addon.AddonDiedException
import app.lyrify.addon.AddonPort
import app.lyrify.addon.AddonSession
import app.lyrify.addon.Connection
import app.lyrify.addon.ModelHandover
import app.lyrify.addon.RemoteSongEngine
import app.lyrify.data.LedgerRepository
import app.lyrify.logFailures
import app.lyrify.models.ModelState
import app.lyrify.models.ModelStore
import app.lyrify.models.NpuChips
import app.lyrify.models.inForeground
import app.lyrify.ui.DESTINATION_AI
import app.lyrify.ui.EXTRA_DESTINATION
import app.lyrify.ui.MainActivity
import app.lyrify.verbose
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The overnight AI run ([AiRunner]): a long-running WorkManager worker, looked at every hour while the battery is not
 * low and storage is not low, that runs only while the phone is plugged in (charging or not, see [AiLyrics.schedule]),
 * in a foreground service of type mediaProcessing, the type Android 15+ prescribes for long work on media files. The AI
 * itself runs in the AI add-on (app.lyrify.addon), bound from here for the whole run: the add-on runs at this
 * service's priority, needs no foreground service of its own, and the mediaProcessing time limit stays KJB's. That
 * type may run 6 hours in 24 (the time resets when the user opens the app); at the limit WorkManager's service gets
 * `onTimeout` and stops this worker ([WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT]), and the AI then holds off for a
 * day or until the app is opened. Unplugged ([AiRunner] watches the cable) or cancelled, the engine finishes the step it
 * is on and stops; nothing is recorded for that song. It goes into the foreground before it looks at any song's files.
 * The engine's NPU sessions stay loaded for the whole run and are released at its end. What happens goes to the AI's
 * run log ([AiLyrics.runLog]) as well as logcat. Android 15+ only, on the supported chips ([NpuChips]) only, with the
 * add-on installed: without it (or with one too old to speak to) nothing runs, and the AI page offers to get it.
 */
class AiWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) night() else Result.success()

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private suspend fun night(): Result {
        val context = applicationContext
        // A phone whose chip the AI does not run on: nothing to do.
        if (NpuChips.supported() == null) return Result.success()
        // No add-on, or one KJB cannot use: the AI page says so; the next period looks again.
        val session = (Addon.client(context).connect() as? Connection.Open)?.session ?: return Result.success()
        return try {
            session.use { night(it) }
        } catch (_: AddonDiedException) {
            // Died between songs, or before the first one: the next attempt binds it again.
            AiLyrics.runLog(context).append("AI: the AI add-on stopped; trying again later")
            Result.retry()
        }
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private suspend fun night(session: AddonSession): Result {
        val context = applicationContext
        val addon = session.port
        val status = addon.status() ?: return Result.retry()
        val now = System.currentTimeMillis()
        if (status.models !is ModelState.Ready) {
            // Not all in place (an update can pin another file): the download goes on by itself, over Wi-Fi, once the
            // models KJB 2.0 kept are handed over.
            if (AiLyrics.enabled(context).value && !ModelHandover.pending(context)) addon.startDownload(restart = false)
            return Result.success()
        }
        val loadFailures = AiLyrics.loadFailures(context, status.pins)
        if (loadFailures.paused()) {
            // A model did not load again after it was downloaded again: nothing runs until the next version or new models.
            Log.i(LOG_TAG, "AI: paused, a model did not load again after it was downloaded again")
            AiLyrics.held(AiHold.MODEL_FAILED)
            return Result.success()
        }
        // No access to the music, or not plugged in: the next period looks again.
        if (!Environment.isExternalStorageManager() || !AiLyrics.pluggedIn(context).first()) return Result.success()
        if (AiLyrics.limitHolds(context, now)) {
            Log.i(LOG_TAG, "AI: the daily limit for long work was reached; waiting")
            return Result.success()
        }
        val ledger = LedgerRepository(context)
        val state = ledger.ledger.first()
        if (!state.readable) {
            // Storage trouble, not "no song waiting": the night is not lost, WorkManager's backoff tries again.
            Log.w(LOG_TAG, "AI: the song list cannot be read; trying again later")
            return Result.retry()
        }
        // The ledger alone (no file is looked at): with no song waiting, no foreground service.
        if (state.entries.values.none { AiQueue.wants(it, now) }) return Result.success()
        val runLog = AiLyrics.runLog(context)
        // Into the foreground first; the run then builds its queue, looking at each song's files.
        if (!foreground(null)) {
            runLog.append("AI: the phone did not let the run start in the background (foreground service refused)")
            AiLyrics.held(AiHold.NOT_ALLOWED)
            return Result.retry()
        }
        AiLyrics.held(null)
        val power = context.getSystemService(PowerManager::class.java)
        runLog.append(
            "AI: run started: ${AiLyrics.battery(context)}, thermal status ${power.currentThermalStatus}, " +
                "${NpuChips.device()?.socModel} NPU, ${status.runtime}, $AI_THREADS CPU threads, KJB ${BuildConfig.VERSION_NAME}",
        )
        // The run log names the songs; logcat (which bug reports carry) gets its lines only under VERBOSE.
        val log: (String) -> Unit = {
            if (verbose()) Log.i(LOG_TAG, it)
            runLog.append(it)
        }
        val pipeline = Lyrify.pipeline(context)
        val runner = AiRunner(
            ledger = ledger,
            plainLyrics = pipeline::plainLyrics,
            record = pipeline::recordAi,
            // The engine's notes (stages, times, counts) come from the add-on.
            openEngine = { RemoteSongEngine(session, notes = { runLog.append("AI:   $it") }) },
            ready = { addon.status()?.models is ModelState.Ready },
            thermalStatus = { power.currentThermalStatus },
            pluggedIn = AiLyrics.pluggedIn(context),
            clock = System::currentTimeMillis,
            guard = AiLyrics.crashGuard(context, log),
            loadFailures = loadFailures,
            engineContext = Dispatchers.Default,
            aiPaths = pipeline.aiPaths,
            // A song the tag writer or its start-up recovery holds is left for the next run.
            canTake = { path -> path !in Lyrify.locks && audioWithoutLyrics(path) },
            inUse = AiLyrics.inUse,
            badModel = { model -> discard(addon, model) },
            onProgress = { progress ->
                AiLyrics.show(progress)
                foreground(progress)
            },
            runLog = log,
        )
        val started = SystemClock.elapsedRealtime()
        return try {
            val end = runner.run()
            log("AI: run ended: $end after ${(SystemClock.elapsedRealtime() - started) / 60_000} min")
            if (end == AiRunEnd.CRASHED) AiLyrics.held(AiHold.CRASHED)
            if (end == AiRunEnd.MODEL_FAILED) AiLyrics.held(AiHold.MODEL_FAILED)
            if (end == AiRunEnd.NO_NPU) AiLyrics.held(AiHold.NO_NPU)
            // Each end waits for the next period (unplugged, the next hourly look finds the cable again); an add-on that
            // died is bound again sooner, after WorkManager's backoff.
            if (end == AiRunEnd.ADDON_DIED) Result.retry() else Result.success()
        } catch (e: CancellationException) {
            runLog.append("AI: run stopped by the system after ${(SystemClock.elapsedRealtime() - started) / 60_000} min (WorkManager stop reason $stopReason)")
            throw e
        } finally {
            AiLyrics.show(null)
            if (stopReason == WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT) {
                log("AI: the daily limit for long work is used up (onTimeout); stopped after the current step")
                AiLyrics.limitReached(context, System.currentTimeMillis())
            }
        }
    }

    /** A model that did not load: the add-on deletes it, and downloads it again while the AI is on. */
    private fun discard(addon: AddonPort, model: String) {
        addon.discardModel(model)
        if (AiLyrics.enabled(applicationContext).value) addon.startDownload(restart = true)
    }

    /** Runs (or keeps running) in the foreground showing [progress] (null: getting ready); false when the phone does not allow it now. */
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private suspend fun foreground(progress: AiProgress?): Boolean =
        inForeground({ setForeground(AiLyrics.foregroundInfo(applicationContext, progress)) }) { e ->
            // Started from the background without an exemption (see the card's battery tip), or the day's time for long
            // work used up. A cancelled run is rethrown, not taken for this.
            Log.w(LOG_TAG, "AI: not allowed to start in the background: ${e.javaClass.simpleName}")
        }
}

/**
 * Switching the AI on and off, and what the AI card shows about it. Process-wide. The user's choice is stored and is
 * what the switch shows; WorkManager's schedule follows it ([reconcile]).
 */
object AiLyrics {
    private const val WORK = "ai-lyrics"
    private const val CHANNEL = "ai_lyrics"
    private const val NOTIFICATION_ID = 2
    private const val PREFERENCES = "ai_lyrics"
    private const val LIMIT_REACHED_AT = "limit_reached_at"
    private const val ENABLED = "enabled"
    private const val LOAD_FAILURES = "model_load_failures"
    private const val RUN_EVERY_HOURS = 1L
    private const val RETRY_MINUTES = 15L
    private const val DAY_MS = 24 * 60 * 60_000L
    private const val RUN_LOG = "ai-run.log"
    private const val CRASH_GUARD = "ai-crash-guard.json"

    /** Where 2.0's CPU engine kept its per-song checkpoints (app-private cache); deleted, as songs now take under a minute. */
    private const val OLD_RESUME_CACHE = "ai-resume"
    private const val PERCENT = 100

    /** How long "Delete models" waits for a run to stop: the step it is on takes seconds; creating the MDX session, about 30 s. */
    private const val STOP_TIMEOUT_MS = 5 * 60_000L

    /** Held by a run while it may use the models ([AiRunner]); a delete waits for it ([afterStopping]). */
    internal val inUse = Mutex()

    /** Calls to the AI add-on that outlive the screen that asked (the switch, the start-up look). */
    private val calls = CoroutineScope(SupervisorJob() + Dispatchers.IO + logFailures)

    private var stored: MutableStateFlow<Boolean>? = null

    private var runLog: AiRunLog? = null

    private val _progress = MutableStateFlow<AiProgress?>(null)

    /** The run in progress, while there is one in this process. */
    val progress: StateFlow<AiProgress?> = _progress.asStateFlow()

    private val _hold = MutableStateFlow<AiHold?>(null)

    /** Why the last start did not happen, if it did not. */
    val hold: StateFlow<AiHold?> = _hold.asStateFlow()

    /** The user's choice, as stored: the switch shows it at once, without waiting for WorkManager. */
    @Synchronized
    private fun choice(context: Context): MutableStateFlow<Boolean> =
        stored ?: MutableStateFlow(preferences(context).getBoolean(ENABLED, false)).also { stored = it }

    private fun choose(context: Context, on: Boolean) {
        preferences(context).edit { putBoolean(ENABLED, on) }
        choice(context).value = on
    }

    /**
     * On: stored as the user's choice, has the AI add-on download the models if needed (over Wi-Fi; models in place are
     * not fetched again; not while KJB 2.0's are being handed over) and schedules the run, looked at every hour while the
     * battery is not low and storage is not low; it runs when the phone is plugged in. Without the add-on, the choice is
     * kept and the AI page offers to get it.
     */
    fun turnOn(context: Context) {
        choose(context, true)
        val app = context.applicationContext
        calls.launch { if (!ModelHandover.pending(app)) Addon.withPort(app) { it.startDownload(restart = true) } }
        schedule(context)
    }

    /**
     * The periodic run. UPDATE gives a schedule that is there the current constraints from its next run on (a run in
     * progress goes on, the period is kept), and replaces one that was cancelled, even while that run is still finishing
     * its step ([inUse] keeps the two apart).
     *
     * Not `setRequiresCharging`: in WorkManager 2.12 (BatteryChargingTracker, and JobScheduler for the start) that is
     * Android's charging state, `BatteryManager.ACTION_CHARGING` / `ACTION_DISCHARGING`, not the cable. On the user's
     * phone it came 18 and 19 minutes after plugging in, never in a 13-minute charge, and went off at 63% with the cable
     * still in; a battery Samsung's "Protect battery" holds at 80-85% stops charging the same way. The worker checks the
     * cable itself ([pluggedIn], any `EXTRA_PLUGGED` source) and [AiRunner] stops when it comes off.
     */
    private fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<AiWorker>(RUN_EVERY_HOURS, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .setRequiresStorageNotLow(true)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, RETRY_MINUTES, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /** Download (or Resume, or Try again) on the AI page: the add-on downloads the models anew from what it has. */
    fun download(context: Context) {
        val app = context.applicationContext
        calls.launch { Addon.withPort(app) { it.startDownload(restart = true) } }
    }

    /** Off, stored as the user's choice: a run in progress finishes the step it is on and stops. The models stay. */
    fun turnOff(context: Context) {
        choose(context, false)
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
    }

    /** Whether the AI is on: the user's stored choice. */
    fun enabled(context: Context): StateFlow<Boolean> = choice(context).asStateFlow()

    /**
     * When the app starts: on, with nothing scheduled (the schedule was lost) or a schedule an earlier version made
     * (waiting for charging, see [schedule]), the run is scheduled again; models not all in place in the AI add-on (a
     * new version can pin another file) are downloaded unless a download is under way, once KJB 2.0's are handed over
     * ([ModelHandover]). A schedule from before the choice was stored (an earlier build) counts as on.
     */
    suspend fun reconcile(context: Context) {
        withContext(Dispatchers.IO) { removeOldFiles(context) }
        if (NpuChips.supported() == null) return
        ModelHandover.startIfNeeded(context)
        val pending = WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(WORK).first().filterNot { it.state.isFinished }
        if (pending.isNotEmpty() && !preferences(context).contains(ENABLED)) choose(context, true)
        if (!choice(context).value) return
        if (loadFailures(context).paused()) _hold.value = AiHold.MODEL_FAILED
        if (pending.isEmpty() || pending.any { it.constraints.requiresCharging() }) {
            Log.i(LOG_TAG, "AI: on; scheduled again")
            schedule(context)
        }
        if (!ModelHandover.pending(context)) {
            Addon.withPort(context) { addon -> if (addon.status()?.models?.let { it !is ModelState.Ready } == true) addon.startDownload(restart = false) }
        }
    }

    /**
     * What earlier versions left in app-private storage and this one no longer uses: the models they downloaded before the
     * ones KJB 2.0 kept ([ModelStore.removeRetired]; those go to the add-on, [ModelHandover]) and 2.0's per-song
     * checkpoints in the cache. Nothing else.
     */
    private fun removeOldFiles(context: Context) {
        val gone = ModelStore.removeRetired(ModelHandover.legacyFolder(context))
        if (!gone) Log.w(LOG_TAG, "AI: a model an earlier version used could not be deleted")
        val checkpoints = File(context.cacheDir, OLD_RESUME_CACHE)
        if (checkpoints.exists() && !checkpoints.deleteRecursively()) Log.w(LOG_TAG, "AI: the old checkpoints could not all be deleted")
    }

    /** The crash guard's records (app-private files, see [CrashGuard]). */
    internal fun crashGuard(context: Context, log: (String) -> Unit): CrashGuard =
        CrashGuard(File(context.applicationContext.filesDir, CRASH_GUARD), System::currentTimeMillis, BuildConfig.VERSION_CODE, log)

    /**
     * Off, then [block] once the AI has actually stopped: WorkManager no longer runs it and its engine has let go of
     * the models (a cancelled run finishes the step it is on first). Null when that takes longer than
     * [STOP_TIMEOUT_MS]: [block] did not run.
     */
    suspend fun <T> afterStopping(context: Context, block: suspend () -> T): T? {
        turnOff(context)
        val running = WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(WORK)
            .map { infos -> infos.any { it.state == WorkInfo.State.RUNNING } }
        return whenStopped(running, inUse, STOP_TIMEOUT_MS, block)
    }

    /**
     * Whether the phone is plugged in (any `EXTRA_PLUGGED` source: AC, USB, wireless, dock), charging or not, from the
     * battery broadcast.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun pluggedIn(context: Context): Flow<Boolean> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                trySend(plugged(intent))
            }
        }
        // A system broadcast: delivered although the receiver is not exported. The sticky intent is the state now.
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), Context.RECEIVER_NOT_EXPORTED)?.let { trySend(plugged(it)) }
        awaitClose { context.unregisterReceiver(receiver) }
    }.conflate().distinctUntilChanged()

    private fun plugged(intent: Intent): Boolean = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0

    /** The battery now, for the run log: "plugged in (usb), not charging, 81%". */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun battery(context: Context): String {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED), Context.RECEIVER_NOT_EXPORTED) ?: return "battery unknown"
        val plugged = when (val source = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
            0 -> "not plugged in"
            BatteryManager.BATTERY_PLUGGED_AC -> "plugged in (ac)"
            BatteryManager.BATTERY_PLUGGED_USB -> "plugged in (usb)"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "plugged in (wireless)"
            BatteryManager.BATTERY_PLUGGED_DOCK -> "plugged in (dock)"
            else -> "plugged in ($source)"
        }
        val status = when (val code = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
            BatteryManager.BATTERY_STATUS_FULL -> "full"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not charging"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
            else -> "status $code"
        }
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * PERCENT / intent.getIntExtra(BatteryManager.EXTRA_SCALE, PERCENT).coerceAtLeast(1)
        return "$plugged, $status, $level%"
    }

    /** The AI's run log (app-private files, see [AiRunLog]); the AI card shows its last lines on a long press. */
    @Synchronized
    fun runLog(context: Context): AiRunLog =
        runLog ?: AiRunLog(File(context.applicationContext.filesDir, RUN_LOG), System::currentTimeMillis).also { runLog = it }

    internal fun show(progress: AiProgress?) {
        _progress.value = progress
    }

    internal fun held(hold: AiHold?) {
        _hold.value = hold
    }

    /**
     * The mediaProcessing time was used up at [now]: no new run for a day, unless the app is opened (which resets it).
     * Written at once (commit): the process may be killed right after the timeout.
     */
    internal fun limitReached(context: Context, now: Long) {
        runLog(context).append("AI: holding off for a day or until KJB is opened (daily limit for long media work)")
        preferences(context).edit(commit = true) { putLong(LIMIT_REACHED_AT, now) }
        _hold.value = AiHold.DAILY_LIMIT
    }

    internal fun limitHolds(context: Context, now: Long): Boolean {
        val at = preferences(context).getLong(LIMIT_REACHED_AT, 0)
        return at > 0 && now - at < DAY_MS
    }

    /**
     * The models' load failures ([ModelLoadFailures]), in the AI's preferences, written at once (commit); [pins]: each
     * model's pinned SHA-256, as the add-on tells them (AddonStatus.pins).
     */
    internal fun loadFailures(context: Context, pins: Map<String, String> = emptyMap()): ModelLoadFailures {
        val preferences = preferences(context)
        return ModelLoadFailures(
            version = BuildConfig.VERSION_CODE,
            pinned = { model -> pins[model] },
            read = { preferences.getString(LOAD_FAILURES, null) },
            write = { value -> preferences.edit(commit = true) { if (value == null) remove(LOAD_FAILURES) else putString(LOAD_FAILURES, value) } },
        )
    }

    /** The user deleted the models: a model that did not load is tried again once they are downloaded anew. */
    fun modelsDeleted(context: Context) {
        loadFailures(context).clear()
        _hold.compareAndSet(AiHold.MODEL_FAILED, null)
    }

    /** The app was opened: Android gives a foreground app its mediaProcessing time back. */
    fun appOpened(context: Context) {
        preferences(context).edit { remove(LIMIT_REACHED_AT) }
        _hold.compareAndSet(AiHold.DAILY_LIMIT, null)
    }

    private fun preferences(context: Context) = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    internal fun foregroundInfo(context: Context, progress: AiProgress?): ForegroundInfo {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.ai_channel), NotificationManager.IMPORTANCE_LOW))
        val resources = context.resources
        val text = when {
            progress == null -> resources.getString(R.string.ai_notification_starting)
            progress.hot -> resources.getString(R.string.ai_notification_hot, progress.done, progress.total)
            progress.current != null -> resources.getString(R.string.ai_notification_song, progress.current, progress.done, progress.total)
            else -> resources.getString(R.string.ai_notification_starting)
        }
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(EXTRA_DESTINATION, DESTINATION_AI),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_kjb)
            .setContentTitle(context.getString(R.string.ai_notification_title))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
    }
}
