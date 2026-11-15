package app.lyrify.ui

import android.content.Context
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lyrify.BuildConfig
import app.lyrify.R
import app.lyrify.addon.AddonInstalls
import app.lyrify.addon.InstallFailure
import app.lyrify.addon.InstallState
import app.lyrify.addon.KjbAddons
import app.lyrify.crash.CrashEntry
import app.lyrify.crash.CrashLog
import app.lyrify.update.AddonUpdate
import app.lyrify.update.UpdateState
import app.lyrify.update.Updates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Settings > About's update check and crash reports (lane F1): the update sheet, which shows over any screen (MainActivity
// hosts it), the lines of the About rows, and the crash reports sheet. update/Updates.kt and crash/CrashLog.kt do the work.

/** The update sheet whenever the update check has something to show ([UpdateState.Checked.ask]); placed once, over the app. */
@Composable
fun UpdateHost() {
    val context = LocalContext.current
    val checker = remember(context) { Updates.checker(context) }
    val state by checker.state.collectAsStateWithLifecycle()
    val shown = (state as? UpdateState.Checked)?.takeIf { it.ask } ?: return
    UpdateSheet(shown, onDismiss = checker::dismiss)
}

/**
 * "KJB <ver> is out": the version on the phone, the release notes and Install, which downloads, checks and installs the
 * update (its progress, the phone's install switch, Android's dialog, or why it failed); and each add-on's update (the AI,
 * Downloads) when this KJB brings a newer one. Not now, or putting the sheet away, keeps it for the next Check now.
 */
@Composable
private fun UpdateSheet(checked: UpdateState.Checked, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val release = checked.release
    val apk = release?.apk
    val installer = apk?.let { remember(context, it) { Updates.installer(context, it) } }
    val idle = remember { MutableStateFlow<InstallState>(InstallState.Idle) }
    val install by (installer?.state ?: idle).collectAsStateWithLifecycle()
    // Back from the phone's "Install unknown apps" switch: the installs go on.
    LifecycleResumeEffect(installer, checked.addons) {
        installer?.resume()
        checked.addons.forEach { AddonInstalls.installer(context, it.addon).resume() }
        onPauseOrDispose { }
    }
    val openInstallSettings = { context.startFirst(listOf(AddonInstalls.installSettings(context))) }
    LyrifySheet(
        onDismiss = onDismiss,
        footer = {
            if (installer != null) InstallButtons(install, installer::start, installer::cancel, openInstallSettings, Modifier.fillMaxWidth())
            if (installer == null || install == InstallState.Idle || install is InstallState.Failed) {
                LyrifyButton(stringResource(R.string.update_not_now), onClick = onDismiss, kind = ButtonKind.GHOST, modifier = Modifier.fillMaxWidth())
            }
        },
    ) {
        if (release != null) {
            Text(stringResource(R.string.update_title, release.version), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Text(
                if (apk != null) stringResource(R.string.update_detail, BuildConfig.VERSION_NAME) else stringResource(R.string.update_no_file),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            InstallLine(install)
            if (release.notes.isNotBlank()) {
                SectionTitle(stringResource(R.string.update_notes))
                Text(release.notes, style = MaterialTheme.typography.bodyMedium)
            }
        }
        checked.addons.forEachIndexed { i, update -> AddonUpdateSection(update, asHeading = release == null && i == 0, openInstallSettings) }
    }
}

/** One add-on's update in the update sheet: its title (the sheet's heading when it comes first), what happens, and Update. */
@Composable
private fun AddonUpdateSection(update: AddonUpdate, asHeading: Boolean, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val installer = remember(context, update.addon) { AddonInstalls.installer(context, update.addon) }
    val install by installer.state.collectAsStateWithLifecycle()
    val title = addonUpdateTitle(update)
    if (asHeading) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
    } else {
        SectionTitle(title)
    }
    val detail = if (update.addon == KjbAddons.DOWNLOADS) R.string.update_dl_detail else R.string.update_addon_detail
    Text(stringResource(detail), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    InstallLine(install)
    InstallButtons(install, installer::start, installer::cancel, onOpenSettings, compact = true, start = R.string.update_addon_action)
}

/** "AI add-on 3.0.1" or "Downloads add-on 3.0.1". */
@Composable
private fun addonUpdateTitle(update: AddonUpdate): String =
    stringResource(if (update.addon == KjbAddons.DOWNLOADS) R.string.update_dl_title else R.string.update_addon_title, update.version)

/** What an install is doing, under its title; nothing while it waits for a tap. */
@Composable
private fun InstallLine(install: InstallState) {
    val context = LocalContext.current
    val line = when (install) {
        InstallState.Idle -> return
        is InstallState.Downloading -> stringResource(
            R.string.update_downloading,
            Formatter.formatShortFileSize(context, install.doneBytes),
            Formatter.formatShortFileSize(context, install.totalBytes),
        )
        InstallState.NeedsPermission -> stringResource(R.string.update_allow_detail)
        InstallState.Installing -> stringResource(R.string.update_confirm)
        is InstallState.Failed -> stringResource(
            when (install.reason) {
                InstallFailure.NOT_PUBLISHED -> R.string.update_failed_not_published
                InstallFailure.NETWORK -> R.string.update_failed_network
                InstallFailure.DOWNLOAD -> R.string.update_failed_download
                InstallFailure.NOT_KJBS -> R.string.update_failed_not_kjbs
                InstallFailure.INSTALL -> R.string.update_failed_install
            },
        )
    }
    val failed = install is InstallState.Failed
    Text(line, style = MaterialTheme.typography.bodyMedium, color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
}

/** An install's button: [start] it (Try again after a failure), Cancel it while it runs, or open the phone's install switch. */
@Composable
private fun InstallButtons(
    install: InstallState,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    @StringRes start: Int = R.string.update_install,
) {
    when (install) {
        InstallState.Idle -> LyrifyButton(stringResource(start), onClick = onStart, modifier = modifier, compact = compact)
        is InstallState.Failed -> LyrifyButton(stringResource(R.string.update_try_again), onClick = onStart, modifier = modifier, compact = compact)
        InstallState.NeedsPermission -> {
            LyrifyButton(stringResource(R.string.update_open_settings), onClick = onOpenSettings, modifier = modifier, compact = compact)
            LyrifyButton(stringResource(R.string.update_cancel), onClick = onCancel, kind = ButtonKind.GHOST, modifier = modifier, compact = compact)
        }
        is InstallState.Downloading, InstallState.Installing ->
            LyrifyButton(stringResource(R.string.update_cancel), onClick = onCancel, kind = ButtonKind.SECONDARY, modifier = modifier, compact = compact)
    }
}

/** Settings > About > Check now's line: where the update check stands. */
@Composable
fun updateLine(state: UpdateState): String = when (state) {
    UpdateState.Idle -> stringResource(R.string.update_line_idle)
    UpdateState.Checking -> stringResource(R.string.update_line_checking)
    UpdateState.Failed -> stringResource(R.string.update_line_failed)
    is UpdateState.Checked -> when {
        state.release != null -> stringResource(R.string.update_title, state.release.version)
        state.addons.isNotEmpty() -> addonUpdateTitle(state.addons.first())
        else -> stringResource(R.string.update_line_latest, BuildConfig.VERSION_NAME)
    }
}

/** Check now: asks GitHub at once; an offer shows in the update sheet, else a toast says KJB is up to date, or no answer came. */
@Composable
fun rememberCheckNow(): () -> Unit {
    val context = LocalContext.current
    val resources = LocalResources.current
    val toasts = LocalToasts.current
    val scope = rememberCoroutineScope()
    return remember(context, toasts) {
        {
            scope.launch {
                when (val checked = Updates.checker(context).checkNow()) {
                    UpdateState.Failed -> toasts.say(resources, R.string.update_check_failed)
                    is UpdateState.Checked -> if (!checked.ask) toasts.say(resources, R.string.update_up_to_date)
                    else -> Unit
                }
            }
        }
    }
}

/** Settings > About > Crash reports' line: how many are kept, read again whenever [refresh] changes. */
@Composable
fun crashLine(refresh: Any?): String {
    val context = LocalContext.current
    val count by produceState(0, refresh) { value = withContext(Dispatchers.IO) { CrashLog.store(context).list().size } }
    return if (count == 0) stringResource(R.string.crashes_none) else pluralStringResource(R.plurals.crashes_kept, count, count)
}

/** The crash reports sheet's two steps: the list, and one crash. */
private sealed interface CrashStep {
    data object List : CrashStep

    data class One(val entry: CrashEntry) : CrashStep
}

/**
 * Settings > About > Crash reports: the crashes kept on the phone (when, the KJB version, the exception), one crash's
 * whole report with Share (only then does it leave the phone, where the user sends it), and Clear.
 */
@Composable
fun CrashReportsSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { CrashLog.store(context) }
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf<CrashStep>(CrashStep.List) }
    var reads by remember { mutableIntStateOf(0) }
    var clearing by remember { mutableStateOf(false) }
    val entries by produceState<List<CrashEntry>?>(null, reads) { value = withContext(Dispatchers.IO) { store.list() } }
    SheetFlow(step, onDismiss) { at ->
        when (at) {
            CrashStep.List -> CrashList(entries, onOpen = { step = CrashStep.One(it) }, onClear = { clearing = true }, onDismiss = onDismiss)
            is CrashStep.One -> CrashOne(at.entry, read = { withContext(Dispatchers.IO) { store.read(it) } }, onBack = { step = CrashStep.List }, onDismiss = onDismiss)
        }
    }
    if (clearing) {
        ConfirmDialog(
            title = stringResource(R.string.crashes_clear_title),
            confirm = stringResource(R.string.crashes_clear),
            dismiss = stringResource(R.string.update_cancel),
            onConfirm = {
                clearing = false
                scope.launch {
                    withContext(Dispatchers.IO) { store.clear() }
                    reads++
                }
            },
            onDismiss = { clearing = false },
        )
    }
}

@Composable
private fun CrashList(entries: List<CrashEntry>?, onOpen: (CrashEntry) -> Unit, onClear: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    LyrifySheet(
        onDismiss = onDismiss,
        footer = if (entries.isNullOrEmpty()) {
            null
        } else {
            { LyrifyButton(stringResource(R.string.crashes_clear), onClick = onClear, kind = ButtonKind.DESTRUCTIVE, modifier = Modifier.fillMaxWidth()) }
        },
    ) {
        Text(stringResource(R.string.set_crashes), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.crashes_sentence), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        when {
            entries == null -> Unit
            entries.isEmpty() -> Text(stringResource(R.string.crashes_empty), style = MaterialTheme.typography.bodyLarge)
            else -> GroupedRows {
                entries.forEachIndexed { index, entry ->
                    GroupedRow(
                        R.drawable.ic_error,
                        entry.headline.substringAfterLast('.').ifEmpty { stringResource(R.string.set_crashes) },
                        tile = MaterialTheme.colorScheme.surfaceContainerHighest,
                        onTile = MaterialTheme.colorScheme.onSurfaceVariant,
                        first = index == 0,
                        supporting = { Text(stringResource(R.string.crashes_row, crashTime(context, entry.at), entry.app)) },
                        onClick = { onOpen(entry) },
                        trailing = { Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, modifier = Modifier.size(20.dp)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CrashOne(entry: CrashEntry, read: suspend (String) -> String?, onBack: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val text by produceState<String?>(null, entry) { value = read(entry.id) }
    LyrifySheet(
        onDismiss = onDismiss,
        footer = {
            LyrifyButton(stringResource(R.string.crashes_share), onClick = { text?.let { CrashLog.share(context, it) } }, enabled = text != null, modifier = Modifier.fillMaxWidth())
            LyrifyButton(stringResource(R.string.crashes_back), onClick = onBack, kind = ButtonKind.GHOST, modifier = Modifier.fillMaxWidth())
        },
    ) {
        Text(entry.headline.substringAfterLast('.'), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.crashes_row, crashTime(context, entry.at), entry.app), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer {
            Text(text.orEmpty(), style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
        }
    }
}

/** When a crash happened, in the phone's own date and time format. */
private fun crashTime(context: Context, at: Long): String =
    DateUtils.formatDateTime(context, at, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH)
