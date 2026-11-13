package app.lyrify.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.lyrify.LOG_TAG
import app.lyrify.R
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
            Log.d(LOG_TAG, "no screen for ${intent.action}: ${e.javaClass.simpleName}")
        }
    }
}

// Asked in context: what the AI needs once it is switched on

/**
 * Whether KJB may post notifications (only the AI's progress and its download's need them): [ON] (allowed, or Android 11
 * and 12, which do not ask), [ASK] (Android 13 and later, not allowed yet), [OFF] (denied for good: Android asks no
 * more, only the phone's settings can allow them).
 */
enum class NotificationGrant { ON, ASK, OFF }

fun notificationGrant(sdk: Int, granted: Boolean, deniedForGood: Boolean): NotificationGrant = when {
    sdk < Build.VERSION_CODES.TIRAMISU || granted -> NotificationGrant.ON
    deniedForGood -> NotificationGrant.OFF
    else -> NotificationGrant.ASK
}

/** A request answered no, and Android will not ask again: it would have explained before ([rationaleBefore]) and won't now. */
fun deniedForGood(granted: Boolean, rationaleBefore: Boolean, rationaleAfter: Boolean): Boolean = !granted && rationaleBefore && !rationaleAfter

/** The sheets that follow the AI switched on, or its download started: Show progress, then Keep running. */
enum class AiAsk { PROGRESS, BATTERY }

/**
 * What to ask, in order: Show progress while notifications can still be asked for; Keep running only when the AI was
 * [switchedOn] (not for a download alone) and battery use is not [unrestricted].
 */
fun aiAsks(switchedOn: Boolean, notifications: NotificationGrant, unrestricted: Boolean): List<AiAsk> =
    listOfNotNull(AiAsk.PROGRESS.takeIf { notifications == NotificationGrant.ASK }, AiAsk.BATTERY.takeIf { switchedOn && !unrestricted })

/** True when KJB may post notifications: allowed on Android 13 and later, always on 11 and 12. */
fun notificationsAllowed(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

/** KJB's own notification settings, else App info. */
fun notificationSettingsIntents(context: Context): List<Intent> =
    listOf(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) + appInfoIntents(context)

/**
 * The sheet [ask] calls for, each in turn in one window (null: none): Show progress, whose Allow leads to Android's own
 * question ([onAllow]), and Keep running, which opens App info, where battery use is set. Not now, or the sheet dragged
 * away, goes on to the next ask ([onNotNow]).
 */
@Composable
fun AiAskSheets(ask: AiAsk?, onAllow: () -> Unit, onOpenAppInfo: () -> Unit, onNotNow: () -> Unit) {
    SheetFlow(ask, onDismiss = onNotNow) { at ->
        when (at) {
            AiAsk.PROGRESS -> AskSheet(R.drawable.ic_notifications, R.string.ask_progress_title, R.string.ask_progress_text, R.string.setup_allow, onAllow, onNotNow)
            AiAsk.BATTERY -> AskSheet(R.drawable.ic_battery_full, R.string.setup_battery_title, R.string.ask_battery_text, R.string.open_app_info, onOpenAppInfo, onNotNow)
        }
    }
}

/** One ask: its tile, title and reason, then its one action and Not now. */
@Composable
private fun AskSheet(@DrawableRes icon: Int, @StringRes title: Int, @StringRes text: Int, @StringRes action: Int, onAction: () -> Unit, onNotNow: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    LyrifySheet(
        onDismiss = onNotNow,
        spacing = 12.dp,
        footer = {
            LyrifyButton(stringResource(action), onClick = onAction, modifier = Modifier.fillMaxWidth())
            LyrifyButton(stringResource(R.string.setup_not_now), onClick = onNotNow, modifier = Modifier.fillMaxWidth(), kind = ButtonKind.GHOST, contentColor = colors.onSurface)
        },
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(colors.surfaceContainerHighest)) {
            Icon(painterResource(icon), contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(22.dp))
        }
        Text(stringResource(title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        Text(stringResource(text), style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
    }
}
