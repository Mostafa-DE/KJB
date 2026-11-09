package app.lyrify.ui

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import app.lyrify.LOG_TAG
import app.lyrify.media.NowPlayingListener

/**
 * KJB's two grants. All files access is required (the library, `.lrc` files, tags): the setup screen shows until it is
 * on. Notification access is optional, only for music played in other apps (companion mode).
 */
data class Access(val notifications: Boolean, val allFiles: Boolean) {
    val complete: Boolean get() = allFiles

    companion object {
        fun of(context: Context): Access = Access(hasNotificationAccess(context), Environment.isExternalStorageManager())
    }
}

/**
 * Whether the first-run screen shows, given whether it [was] showing (null when KJB just opened). On opening: unless All
 * files access is on and setup was finished before ([setupDone]) or the phone already has both grants (an update from
 * 2.0). Once showing, until the user taps Continue (which needs All files access). Afterwards, again whenever All files
 * access is off.
 */
fun setupShows(access: Access, setupDone: Boolean, was: Boolean?): Boolean = when (was) {
    null -> !access.allFiles || !(setupDone || access.notifications)
    true -> true
    false -> !access.allFiles
}

/**
 * True when KJB is exempt from battery optimisation (battery use "Unrestricted"): the phone then lets it start the
 * AI's foreground service from the background at night.
 */
fun isUnrestricted(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

/** True when the user has switched KJB on in Notification access (Device and app notifications). */
fun hasNotificationAccess(context: Context): Boolean =
    context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

/** KJB's own switch in notification access, else the list of all listeners. */
fun notificationAccessIntents(context: Context): List<Intent> = listOf(
    Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
        Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
        ComponentName(context, NowPlayingListener::class.java).flattenToString(),
    ),
    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
)

/** KJB's own All files access switch, else the list of apps with that access. */
fun allFilesAccessIntents(context: Context): List<Intent> = listOf(
    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, packageUri(context)),
    Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
)

/** App info: the ⋮ menu there has "Allow restricted settings"; Battery has "Never sleeping apps" on Samsung. */
fun appInfoIntents(context: Context): List<Intent> = listOf(
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(context)),
)

private fun packageUri(context: Context): Uri = Uri.fromParts("package", context.packageName, null)

/** Opens the first of [intents] that this device can handle: Settings screens differ between builds. */
fun Context.startFirst(intents: List<Intent>) {
    for (intent in intents) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (e: ActivityNotFoundException) {
            Log.d(LOG_TAG, "no screen for ${intent.action}: ${e.message}")
        }
    }
}
