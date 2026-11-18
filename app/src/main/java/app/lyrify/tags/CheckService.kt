package app.lyrify.tags

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import app.lyrify.LOG_TAG
import app.lyrify.R
import app.lyrify.logFailures
import app.lyrify.ui.DESTINATION_COVERS_TAGS
import app.lyrify.ui.EXTRA_DESTINATION
import app.lyrify.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import java.text.NumberFormat

/**
 * Keeps KJB running while a Covers & tags check looks albums up, so the check goes on when the user leaves the page or
 * KJB: a foreground service of type dataSync, as KJB's downloads use. A check with albums to look up starts it
 * ([CoversTags.check], [CoversTags.opened]), always from KJB in front; it runs no lookup itself, shows how far the check
 * is (its tap opens Covers & tags), and stops once the check ends or is cancelled. It never starts again by itself
 * (START_NOT_STICKY): with KJB closed by Android, the check goes on the next time Covers & tags opens. From Android 15
 * dataSync may run 6 hours in 24; at the limit ([onTimeout]) the service stops, and the check goes on while KJB stays
 * open.
 */
class CheckService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + logFailures)
    private var following: Job? = null

    /** The latest start: the service stops only when no start came in after the check it followed ended ([follow]). */
    private var lastStart = 0

    override fun onBind(intent: Intent): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStart = startId
        val tags = TagsGraph.coversTags(this)
        if (!foreground(CheckNotices.working(this, tags.state.value))) {
            // Android did not let it run in the foreground now: the check goes on while KJB is open.
            stopSelf()
            return START_NOT_STICKY
        }
        if (following?.isActive != true) following = scope.launch { follow(tags) }
        return START_NOT_STICKY
    }

    /** Android 15+: the 6 hours of dataSync in 24 are used up. The service stops; the check goes on while KJB is open. */
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(LOG_TAG, "tags: the daily limit for long work is used up; the check goes on while KJB is open")
        following?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /**
     * The notification follows the check while it runs; then the service goes, unless a check started since asked for it
     * again (a start after the one it followed): then it follows that one.
     */
    private suspend fun follow(tags: CoversTags) {
        while (true) {
            tags.state.map(CheckNotices::line).distinctUntilChanged().takeWhile { it != null }.collect {
                CheckNotices.update(this, CheckNotices.working(this, tags.state.value))
            }
            if (stopSelfResult(lastStart)) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                return
            }
        }
    }

    /** Runs in the foreground with [notification]; false when Android does not allow it now. */
    private fun foreground(notification: Notification): Boolean = try {
        startForeground(CheckNotices.ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        true
    } catch (e: IllegalStateException) {
        // ForegroundServiceStartNotAllowedException (Android 12+) is one.
        Log.w(LOG_TAG, "tags: the check's service is not allowed in the foreground now (${e.javaClass.simpleName})")
        false
    } catch (e: SecurityException) {
        Log.w(LOG_TAG, "tags: the check's service is not allowed in the foreground now (${e.javaClass.simpleName})")
        false
    }

    companion object {
        /** Starts the service from KJB in front; quiet when Android refuses (the check goes on while KJB is open). */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, CheckService::class.java))
            } catch (e: IllegalStateException) {
                Log.w(LOG_TAG, "tags: the check's service not started (${e.javaClass.simpleName})")
            }
        }
    }
}

/** The check's notification: "Covers & tags", "Checking 12/84" (or waiting for MusicBrainz) over a bar; a tap opens the page. */
internal object CheckNotices {
    const val ID: Int = 5
    private const val CHANNEL = "covers_tags"

    /** What the notification says of [state]'s check; null once no check runs. */
    data class Line(val done: Int, val total: Int, val waiting: Boolean)

    fun line(state: CoversTagsState): Line? = state.checking?.let { Line(it.done, it.total, state.waitingBusy) }

    fun working(context: Context, state: CoversTagsState): Notification {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.tags_channel), NotificationManager.IMPORTANCE_LOW))
        val line = line(state)
        val count = NumberFormat.getIntegerInstance()
        val text = when {
            line == null || line.total == 0 -> context.getString(R.string.tags_checking_start)
            line.waiting -> context.getString(R.string.tags_waiting)
            else -> context.getString(R.string.tags_checking, count.format(line.done), count.format(line.total))
        }
        val open = PendingIntent.getActivity(
            context,
            ID,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(EXTRA_DESTINATION, DESTINATION_COVERS_TAGS),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_kjb)
            .setContentTitle(context.getString(R.string.tools_covers))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setProgress(line?.total ?: 0, line?.done ?: 0, line == null || line.total == 0)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    @SuppressLint("MissingPermission")
    fun update(context: Context, notification: Notification) {
        // Without POST_NOTIFICATIONS Android keeps it to the Task Manager; the service runs all the same.
        runCatching { context.getSystemService(NotificationManager::class.java).notify(ID, notification) }
    }
}
