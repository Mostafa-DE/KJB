package app.lyrify.ui

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lyrify.Lyrify
import app.lyrify.logFailures
import app.lyrify.R
import app.lyrify.addon.AddonDiedException
import app.lyrify.addon.AddonInstalls
import app.lyrify.addon.AddonState
import app.lyrify.addon.InstallState
import app.lyrify.addon.KjbAddons
import app.lyrify.addon.packageChanges
import app.lyrify.dl.api.AudioQuality
import app.lyrify.dl.api.DlEnd
import app.lyrify.dl.api.DlFailure
import app.lyrify.dl.api.DlInfo
import app.lyrify.dl.api.DlKind
import app.lyrify.dl.api.DlProgress
import app.lyrify.dl.api.DlUpdate
import app.lyrify.dl.api.VideoChoice
import app.lyrify.downloads.DlAddon
import app.lyrify.downloads.DlConnection
import app.lyrify.downloads.DlLookupEvents
import app.lyrify.downloads.DlSession
import app.lyrify.downloads.DownloadFailure
import app.lyrify.downloads.DownloadFolders
import app.lyrify.downloads.DownloadItem
import app.lyrify.downloads.DownloadNotices
import app.lyrify.downloads.DownloadSettings
import app.lyrify.downloads.DownloadState
import app.lyrify.downloads.Downloads
import app.lyrify.downloads.DownloadsUi
import app.lyrify.downloads.DuplicateCheck
import app.lyrify.downloads.ShareLinks
import app.lyrify.downloads.Thumbnails
import app.lyrify.dupes.AudioFile
import app.lyrify.dupes.LibraryRead
import app.lyrify.dupes.MediaStoreLibrary
import app.lyrify.library.SongRef
import app.lyrify.player.QueueSource
import app.lyrify.player.SourceKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// The Tools row

/**
 * Downloads' line on Tools: Get Downloads while the add-on is missing or not KJB's, else how many download now (the
 * badge), how many did not save, how many were saved, or nothing yet. [addon] is what Android says without a bind.
 */
fun downloadsEntry(addon: AddonState?, ui: DownloadsUi): EntryRow {
    val active = ui.items.count { it.active }
    val failed = ui.items.count { it.state is DownloadState.Failed }
    val saved = ui.items.count { it.state is DownloadState.Done }
    val line = when {
        addon == AddonState.Missing || addon == AddonState.Untrusted -> ToolLine.Text(R.string.dl_get)
        active > 0 -> ToolLine.Count(R.plurals.dl_active, active)
        failed > 0 -> ToolLine.Count(R.plurals.dl_failed_count, failed)
        saved > 0 -> ToolLine.Count(R.plurals.dl_saved_count, saved)
        else -> ToolLine.Text(R.string.dl_nothing_yet)
    }
    return EntryRow(line, badge = active.takeIf { it > 0 })
}

/** The Tools row's state: the add-on as Android sees it (no bind), looked at again when it is installed or removed. */
@Composable
fun downloadsRow(): EntryRow? {
    val context = LocalContext.current
    val ui by Downloads.state.collectAsStateWithLifecycle()
    val addon by remember(context) {
        packageChanges(context.applicationContext, KjbAddons.DOWNLOADS.packageName).onStart { emit(Unit) }.map { withContext(Dispatchers.IO) { DlAddon.peek(context) ?: AddonState.Ready } }
    }.collectAsStateWithLifecycle(initialValue = null)
    LaunchedEffect(Unit) { Downloads.load(context) }
    return if (ui.loaded && addon != null) downloadsEntry(addon, ui) else null
}

// The page

/**
 * Tools > Downloads: the add-on to get (or update) until it is ready; then a link field with Paste (a copied link is
 * offered when Check clipboard is on), and the downloads: what downloads now with Cancel, what did not save with Retry,
 * and what was saved (VidGrab's Finished list), newest first.
 */
@Composable
fun DownloadsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val toasts = LocalToasts.current
    val scope = rememberCoroutineScope()
    val ui by Downloads.state.collectAsStateWithLifecycle()
    val addon by remember(context) { DlAddon.states(context) }.collectAsStateWithLifecycle(initialValue = null)
    val installer = remember(context) { AddonInstalls.installer(context, KjbAddons.DOWNLOADS) }
    val install by installer.state.collectAsStateWithLifecycle()
    var link by rememberSaveable { mutableStateOf("") }
    var looking by rememberSaveable { mutableStateOf<String?>(null) }
    var opened by remember { mutableStateOf<DownloadItem?>(null) }
    var filesAccess by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { Downloads.load(context) }
    LifecycleResumeEffect(Unit) {
        filesAccess = Environment.isExternalStorageManager()
        installer.resume()
        Downloads.resume(context)
        if (Downloads.state.value.settings.checkClipboard && link.isEmpty()) clipboardLink(context)?.let { link = it }
        onPauseOrDispose { }
    }
    val reduced = LocalReducedMotion.current
    PageFrame(
        topBar = { under, backdrop ->
            LyrifyTopBar(
                title = { Text(stringResource(R.string.dl_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                under = under,
                backdrop = backdrop,
                navigation = { ChromeIconButton(R.drawable.ic_arrow_back, stringResource(R.string.check_back), onBack) },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val ready = addon == AddonState.Ready
            if (!ready || install != InstallState.Idle) {
                item(key = "addon", contentType = "addon") {
                    Box(rowMotion(reduced)) { AddonCard(addon, install, installer::start, installer::cancel) }
                }
            }
            if (!filesAccess) {
                item(key = "access", contentType = "access") {
                    Box(rowMotion(reduced)) {
                        Banner(R.drawable.ic_folder_open, stringResource(R.string.dl_files_access), stringResource(R.string.dl_files_access_action)) {
                            context.startFirst(listOf(Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.fromParts("package", context.packageName, null))))
                        }
                    }
                }
            }
            if (ready) {
                item(key = "link", contentType = "link") {
                    Box(rowMotion(reduced)) { LinkField(link, { link = it }, onPaste = { clipboardLink(context)?.let { link = it } }) { ShareLinks.parse(link).firstOrNull()?.let { looking = it } } }
                }
            }
            downloadsList(ui, onOpen = { opened = it }, onCancel = { Downloads.cancel(context, it.key) })
        }
    }
    looking?.let { url ->
        SaveSheet(url, onDismiss = { looking = null }) {
            link = ""
            scope.launch { toasts.say(resources, R.string.dl_started) }
        }
    }
    opened?.let { item ->
        // The sheet follows the row: closed once it is gone, its state as it is now.
        val now = ui.items.firstOrNull { it.key == item.key }
        if (now == null) opened = null else DownloadSheet(now) { opened = null }
    }
}

/** A link copied to the clipboard, if any (Android 12+ tells the user KJB read it). */
private fun clipboardLink(context: Context): String? {
    val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip ?: return null
    return (0 until clip.itemCount).asSequence().mapNotNull { clip.getItemAt(it).coerceToText(context)?.toString() }.flatMap { ShareLinks.parse(it) }.firstOrNull()
}

/** The link field with Paste, and Look up, which opens the save sheet. */
@Composable
private fun LinkField(link: String, onLink: (String) -> Unit, onPaste: () -> Unit, onLookUp: () -> Unit) {
    val usable = ShareLinks.parse(link).isNotEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(horizontal = ScreenPadding)) {
        LyrifyField(
            value = link,
            onValueChange = onLink,
            modifier = Modifier.fillMaxWidth(),
            label = stringResource(R.string.dl_link),
            placeholder = stringResource(R.string.dl_link_hint),
            trailing = { ChromeIconButton(R.drawable.ic_content_paste, stringResource(R.string.dl_paste), onPaste) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { if (usable) onLookUp() }),
        )
        LyrifyButton(stringResource(R.string.dl_look_up), onClick = onLookUp, enabled = usable, icon = R.drawable.ic_download, modifier = Modifier.fillMaxWidth())
    }
}

/** A card with an icon, one sentence and one button. */
@Composable
private fun Banner(@DrawableRes icon: Int, text: String, action: String?, onAction: () -> Unit) {
    LyrifyCard(Modifier.fillMaxWidth().padding(horizontal = ScreenPadding)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(16.dp)) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(24.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (action != null) LyrifyButton(action, onClick = onAction, kind = ButtonKind.SECONDARY, compact = true)
        }
    }
}

/** The add-on while it is not ready: getting it (the AI add-on's install flow and checks), updating it, or why it can't run. */
@Composable
private fun AddonCard(addon: AddonState?, install: InstallState, onGet: () -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    when (install) {
        is InstallState.Downloading -> Banner(
            R.drawable.ic_download,
            stringResource(R.string.dl_addon_downloading, Formatter.formatShortFileSize(context, install.doneBytes), Formatter.formatShortFileSize(context, install.totalBytes)),
            stringResource(R.string.dl_cancel),
            onCancel,
        )
        InstallState.NeedsPermission -> Banner(R.drawable.ic_lock, stringResource(R.string.dl_addon_allow), stringResource(R.string.dl_addon_allow_action)) {
            context.startFirst(listOf(AddonInstalls.installSettings(context)))
        }
        InstallState.Installing -> Banner(R.drawable.ic_system_update, stringResource(R.string.dl_addon_confirm), stringResource(R.string.dl_cancel), onCancel)
        is InstallState.Failed -> Banner(R.drawable.ic_error, stringResource(R.string.dl_addon_failed), stringResource(R.string.dl_try_again), onGet)
        InstallState.Idle -> when (addon) {
            null, AddonState.Ready -> Unit
            AddonState.Missing -> Banner(R.drawable.ic_download, stringResource(R.string.dl_addon_needed), stringResource(R.string.dl_get), onGet)
            is AddonState.Outdated -> Banner(R.drawable.ic_system_update, stringResource(R.string.dl_addon_outdated), stringResource(R.string.dl_addon_update), onGet)
            AddonState.Untrusted -> Banner(R.drawable.ic_block, stringResource(R.string.dl_addon_untrusted), stringResource(R.string.dl_app_info)) {
                context.startFirst(listOf(AddonInstalls.addonInfo(KjbAddons.DOWNLOADS)))
            }
            AddonState.Unreachable -> Banner(R.drawable.ic_schedule, stringResource(R.string.dl_addon_unreachable), null) {}
        }
    }
}

/** The downloads: Now (under way, waiting, not saved) and Done, newest first; or a line saying how to start. */
private fun LazyListScope.downloadsList(ui: DownloadsUi, onOpen: (DownloadItem) -> Unit, onCancel: (DownloadItem) -> Unit) {
    if (!ui.loaded) return
    val (done, now) = ui.items.partition { it.state is DownloadState.Done }
    if (ui.items.isEmpty()) {
        item(key = "empty", contentType = "empty") {
            Text(
                stringResource(R.string.dl_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = ScreenPadding + 4.dp, vertical = 8.dp),
            )
        }
        return
    }
    listOf(R.string.dl_now to now, R.string.dl_done to done).filter { it.second.isNotEmpty() }.forEach { (title, items) ->
        item(key = "title $title", contentType = "title") {
            SectionTitle(stringResource(title), Modifier.padding(start = ScreenPadding + 4.dp, end = ScreenPadding, top = 8.dp))
        }
        item(key = "group $title", contentType = "group") {
            GroupedRows {
                items.forEachIndexed { index, item -> DownloadRow(item, ui.progress[item.key], first = index == 0, onOpen = { onOpen(item) }, onCancel = { onCancel(item) }) }
            }
        }
    }
}

/** One download: its kind, title and where it is (a bar while it downloads); Cancel while it is under way. */
@Composable
private fun DownloadRow(item: DownloadItem, progress: DlProgress?, first: Boolean, onOpen: () -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val failed = item.state is DownloadState.Failed
    GroupedRow(
        if (item.kind == DlKind.AUDIO) R.drawable.ic_music_note else R.drawable.ic_movie,
        item.title.ifBlank { item.url },
        tile = if (failed) colors.errorContainer else colors.surfaceContainerHighest,
        onTile = if (failed) colors.onErrorContainer else colors.onSurfaceVariant,
        first = first,
        onClick = onOpen,
        supporting = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(rowLine(context, item, progress), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (item.state == DownloadState.Running) Bar(progress?.percent?.div(100f))
            }
        },
        trailing = if (item.active) {
            { ChromeIconButton(R.drawable.ic_close, stringResource(R.string.dl_cancel), onCancel) }
        } else {
            null
        },
    )
}

/** What a row says under its title. */
private fun rowLine(context: Context, item: DownloadItem, progress: DlProgress?): String = when (val state = item.state) {
    DownloadState.Queued -> context.getString(R.string.dl_waiting)
    DownloadState.Running -> DownloadNotices.stageText(context, progress)
    is DownloadState.Failed -> context.getString(DownloadNotices.failureLabel(state.reason))
    is DownloadState.Done -> context.getString(if (state.copyOf != null) R.string.dl_maybe_copy else R.string.dl_saved) + " · " +
        DateUtils.getRelativeTimeSpanString(state.savedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
}

/** A thin bar, [fraction] full, or a faint full one while the size is not known yet. */
@Composable
private fun Bar(fraction: Float?) {
    val brand = LocalBrandColors.current
    Box(
        Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .semantics { if (fraction != null) progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f) },
    ) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction ?: 1f).background(if (fraction == null) brand.highlight.copy(alpha = 0.3f) else brand.highlight))
    }
}

/** A download's sheet: what happened, in a sentence, and what can be done (Play or Open, Retry, Cancel, Remove from list). */
@Composable
private fun DownloadSheet(item: DownloadItem, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val state = item.state
    LyrifySheet(
        onDismiss = onDismiss,
        footer = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (state) {
                    is DownloadState.Done -> if (File(state.path).isFile) {
                        LyrifyButton(stringResource(if (item.kind == DlKind.AUDIO) R.string.dl_play else R.string.dl_open), onClick = {
                            openSaved(context, item, state.path)
                            onDismiss()
                        }, modifier = Modifier.fillMaxWidth())
                    }
                    is DownloadState.Failed -> LyrifyButton(stringResource(R.string.dl_retry), onClick = {
                        Downloads.retry(context, item.key)
                        onDismiss()
                    }, icon = R.drawable.ic_refresh, modifier = Modifier.fillMaxWidth())
                    else -> LyrifyButton(stringResource(R.string.dl_cancel), onClick = {
                        Downloads.cancel(context, item.key)
                        onDismiss()
                    }, kind = ButtonKind.SECONDARY, modifier = Modifier.fillMaxWidth())
                }
                if (!item.active) {
                    LyrifyButton(stringResource(R.string.dl_remove), onClick = {
                        Downloads.remove(context, item.key)
                        onDismiss()
                    }, kind = ButtonKind.SECONDARY, modifier = Modifier.fillMaxWidth())
                }
            }
        },
    ) {
        Text(item.title.ifBlank { item.url }, style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
        if (item.artist.isNotBlank()) Text(item.artist, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val sentence = when (state) {
            is DownloadState.Failed -> stringResource(failureSentence(state.reason))
            is DownloadState.Done -> state.copyOf?.let { stringResource(R.string.dl_copy_of, File(it).name) }
            else -> null
        }
        sentence?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@StringRes
private fun failureSentence(reason: DownloadFailure): Int = when (reason) {
    DownloadFailure.OFFLINE -> R.string.dl_failed_offline_detail
    DownloadFailure.NOT_FOUND -> R.string.dl_failed_not_found_detail
    DownloadFailure.SITE_CHANGED -> R.string.dl_failed_site_detail
    DownloadFailure.NO_ROOM -> R.string.dl_failed_room_detail
    DownloadFailure.HAND_OFF -> R.string.dl_failed_hand_off_detail
    DownloadFailure.STALLED -> R.string.dl_failed_stalled_detail
    DownloadFailure.ADDON_GONE -> R.string.dl_failed_addon_detail
    DownloadFailure.NO_ADDON -> R.string.dl_failed_no_addon_detail
    DownloadFailure.NOT_ALLOWED -> R.string.dl_failed_not_allowed_detail
    DownloadFailure.LOCAL_ADDRESS -> R.string.dl_failed_local_detail
    DownloadFailure.UNKNOWN -> R.string.dl_failed_unknown_detail
}

/** A saved song plays in KJB's own player; a video opens in the phone's video app. */
private fun openSaved(context: Context, item: DownloadItem, path: String) {
    if (item.kind == DlKind.AUDIO) {
        Lyrify.playback(context).play(listOf(SongRef(path)), 0, QueueSource(SourceKind.PICKED, "downloads", item.title))
        return
    }
    val uri = context.contentResolver.query(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), arrayOf(MediaStore.MediaColumns._ID), "_data = ?", arrayOf(path), null)?.use { cursor ->
        if (cursor.moveToFirst()) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL).buildUpon().appendPath(cursor.getLong(0).toString()).build() else null
    } ?: return
    context.startFirst(listOf(Intent(Intent.ACTION_VIEW).setDataAndType(uri, DownloadFolders.mime(File(path).extension)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)))
}

// Settings > Downloads

/**
 * Settings' Downloads section (VidGrab's settings that still mean something in KJB): the audio quality, the original
 * language, where songs and videos go, the copy warning, the clipboard, and yt-dlp's update with its version.
 */
fun LazyListScope.downloadsSettings() {
    item(key = "downloads", contentType = "section") { DownloadsSettings(rowMotion(LocalReducedMotion.current)) }
}

private enum class DownloadsSheet { QUALITY, AUDIO_FOLDER, VIDEO_FOLDER }

@Composable
private fun DownloadsSettings(modifier: Modifier) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val toasts = LocalToasts.current
    val scope = rememberCoroutineScope()
    val ui by Downloads.state.collectAsStateWithLifecycle()
    var sheet by remember { mutableStateOf<DownloadsSheet?>(null) }
    var updating by remember { mutableStateOf(false) }
    val version by produceState<String?>(null, updating) { value = withContext(Dispatchers.IO) { DlAddon.withPort(context) { it.status()?.ytdlpVersion } } }
    LaunchedEffect(Unit) { Downloads.load(context) }
    val settings = ui.settings
    fun change(block: (DownloadSettings) -> DownloadSettings) = Downloads.settings(context, block)
    Column(modifier) {
        SectionTitle(stringResource(R.string.settings_downloads), Modifier.padding(start = ScreenPadding + 4.dp, end = ScreenPadding, top = 18.dp, bottom = 8.dp))
        GroupedRows {
            SettingValueRow(R.drawable.ic_music_note, R.string.set_dl_quality, stringResource(qualityLabel(settings.audioQuality)), first = true) { sheet = DownloadsSheet.QUALITY }
            SettingSwitchRow(R.drawable.ic_lyrics, R.string.set_dl_language, R.string.set_dl_language_detail, settings.originalLanguage) { on -> change { it.copy(originalLanguage = on) } }
            SettingValueRow(R.drawable.ic_folder_open, R.string.set_dl_audio_folder, settings.audioFolder) { sheet = DownloadsSheet.AUDIO_FOLDER }
            SettingValueRow(R.drawable.ic_movie, R.string.set_dl_video_folder, settings.videoFolder) { sheet = DownloadsSheet.VIDEO_FOLDER }
            SettingSwitchRow(R.drawable.ic_content_copy, R.string.set_dl_copies, R.string.set_dl_copies_detail, settings.warnDuplicates) { on -> change { it.copy(warnDuplicates = on) } }
            SettingSwitchRow(R.drawable.ic_content_paste, R.string.set_dl_clipboard, R.string.set_dl_clipboard_detail, settings.checkClipboard) { on -> change { it.copy(checkClipboard = on) } }
            val update = version?.let { stringResource(R.string.set_dl_update_value, it) } ?: stringResource(R.string.set_dl_update_none)
            SettingValueRow(R.drawable.ic_system_update, R.string.set_dl_update, update) {
                if (!updating) {
                    updating = true
                    scope.launch {
                        val outcome = withContext(Dispatchers.IO) { DlAddon.withPort(context) { it.update() } }
                        updating = false
                        toasts.say(resources, updateMessage(outcome))
                    }
                }
            }
        }
    }
    val close = { sheet = null }
    when (sheet) {
        DownloadsSheet.QUALITY -> QualitySheet(R.string.set_dl_quality, R.string.set_dl_quality_sheet, settings.audioQuality, close) { quality -> change { it.copy(audioQuality = quality) } }
        DownloadsSheet.AUDIO_FOLDER -> FolderSheet(DlKind.AUDIO, settings.audioFolder, close) { folder -> change { it.copy(audioFolder = folder) } }
        DownloadsSheet.VIDEO_FOLDER -> FolderSheet(DlKind.VIDEO, settings.videoFolder, close) { folder -> change { it.copy(videoFolder = folder) } }
        null -> Unit
    }
}

/** A Settings row that opens a sheet, with its value under its title. */
@Composable
private fun SettingValueRow(@DrawableRes icon: Int, @StringRes title: Int, value: String, first: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    GroupedRow(
        icon,
        stringResource(title),
        tile = colors.surfaceContainerHighest,
        onTile = colors.onSurfaceVariant,
        first = first,
        supporting = { Text(value, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        onClick = onClick,
        trailing = { Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, modifier = Modifier.size(20.dp)) },
    )
}

/** A Settings row with a switch: one TalkBack stop with its name, its line and its state. */
@Composable
private fun SettingSwitchRow(@DrawableRes icon: Int, @StringRes title: Int, @StringRes detail: Int, checked: Boolean, onSwitch: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    GroupedRow(
        icon,
        stringResource(title),
        tile = colors.surfaceContainerHighest,
        onTile = colors.onSurfaceVariant,
        modifier = Modifier.toggleable(checked, role = Role.Switch, onValueChange = onSwitch),
        supporting = { Text(stringResource(detail), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailing = { LyrifySwitch(checked) },
    )
}

@StringRes
private fun updateMessage(update: DlUpdate?): Int = when (update?.outcome) {
    DlUpdate.Outcome.UPDATED -> R.string.dl_updated
    DlUpdate.Outcome.CURRENT -> R.string.dl_up_to_date
    DlUpdate.Outcome.BUSY -> R.string.dl_update_busy
    DlUpdate.Outcome.FAILED, null -> R.string.dl_update_failed
}

@StringRes
private fun qualityLabel(quality: AudioQuality): Int = when (quality) {
    AudioQuality.MP3_320 -> R.string.dl_q_mp3_320
    AudioQuality.MP3_V0 -> R.string.dl_q_mp3_v0
    AudioQuality.MP3_192 -> R.string.dl_q_mp3_192
    AudioQuality.ORIGINAL -> R.string.dl_q_original
}

@StringRes
private fun qualityDetail(quality: AudioQuality): Int = when (quality) {
    AudioQuality.MP3_320 -> R.string.dl_q_mp3_320_detail
    AudioQuality.MP3_V0 -> R.string.dl_q_mp3_v0_detail
    AudioQuality.MP3_192 -> R.string.dl_q_mp3_192_detail
    AudioQuality.ORIGINAL -> R.string.dl_q_original_detail
}

/** The four audio choices, each with its line; a choice closes the sheet. */
@Composable
private fun QualitySheet(@StringRes title: Int, @StringRes sentence: Int, selected: AudioQuality?, onDismiss: () -> Unit, onChoose: (AudioQuality) -> Unit) {
    LyrifySheet(onDismiss = onDismiss, spacing = 8.dp) {
        Text(stringResource(title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        Text(stringResource(sentence), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        AudioQuality.entries.forEach { quality ->
            ChoiceLine(stringResource(qualityLabel(quality)), stringResource(qualityDetail(quality)), quality == selected) {
                onChoose(quality)
                onDismiss()
            }
        }
    }
}

/** One choice of a sheet: its label, its line, and a check when it is the one set. */
@Composable
private fun ChoiceLine(label: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    LyrifyCard(Modifier.fillMaxWidth(), color = if (selected) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainerLow, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (selected) Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = LocalBrandColors.current.highlight, modifier = Modifier.size(20.dp))
        }
    }
}

/** A folder for [kind]: typed, checked ([DownloadFolders.valid]), saved. */
@Composable
private fun FolderSheet(kind: DlKind, current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var folder by rememberSaveable { mutableStateOf(current) }
    val valid = DownloadFolders.valid(folder.trim().trim('/'), kind)
    LyrifySheet(
        onDismiss = onDismiss,
        footer = {
            LyrifyButton(stringResource(R.string.set_dl_folder_save), onClick = {
                onSave(folder.trim().trim('/'))
                onDismiss()
            }, enabled = valid, modifier = Modifier.fillMaxWidth())
        },
    ) {
        Text(stringResource(if (kind == DlKind.AUDIO) R.string.set_dl_audio_folder else R.string.set_dl_video_folder), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        Text(
            stringResource(if (kind == DlKind.AUDIO) R.string.set_dl_audio_folder_sheet else R.string.set_dl_video_folder_sheet),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LyrifyField(folder, { folder = it }, Modifier.fillMaxWidth(), label = stringResource(R.string.set_dl_folder), isError = !valid)
        if (!valid) Text(stringResource(R.string.set_dl_folder_invalid), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

// The save sheet: the share target's, and the page's Look up

/**
 * One link being looked up in the Downloads add-on while the sheet shows: the quick info at once (YouTube's oEmbed),
 * then the full info, or why there is none. The add-on stays bound until [close].
 */
@Stable
private class LinkLookup(private val context: Context, private val scope: CoroutineScope, private val url: String) {
    var info: DlInfo? by mutableStateOf(null)
        private set
    var failed: DlFailure? by mutableStateOf(null)
        private set

    /** Null while it binds; then Ready, or why the add-on can't be used. */
    var addon: AddonState? by mutableStateOf(null)
        private set

    private var session: DlSession? = null
    private var lookup: Long? = null

    fun start() {
        scope.launch(Dispatchers.IO) {
            when (val connection = DlAddon.connect(context)) {
                is DlConnection.Closed -> withContext(Dispatchers.Main) { addon = connection.state }
                is DlConnection.Open -> {
                    session = connection.session
                    withContext(Dispatchers.Main) { addon = AddonState.Ready }
                    lookup = try {
                        // Looked up in the add-on's own foreground service, which KJB starts once it took the link.
                        connection.session.port.info(url, events).also { connection.session.foreground() }
                    } catch (_: AddonDiedException) {
                        withContext(Dispatchers.Main) { failed = DlFailure.ENGINE }
                        null
                    }
                }
            }
        }
    }

    private val events = object : DlLookupEvents {
        override fun onInfo(info: DlInfo) {
            scope.launch(Dispatchers.Main) { if (this@LinkLookup.info?.partial != false) this@LinkLookup.info = info }
        }

        override fun onFailed(end: DlEnd.Failed) {
            scope.launch(Dispatchers.Main) { failed = end.failure }
        }
    }

    /** Full info is in, or no more is coming. */
    val settled: Boolean get() = info?.partial == false || failed != null || (addon != null && addon != AddonState.Ready)

    /** Stops the lookup and lets the add-on go, off the main thread: the sheet's own scope is gone by then. */
    fun close() {
        val open = session ?: return
        val asked = lookup
        session = null
        closing.launch {
            try {
                asked?.let { open.port.cancelInfo(it) }
            } catch (_: AddonDiedException) {
                // Gone, and its lookup with it.
            }
            open.close()
        }
    }
}

/**
 * The save sheet for [url] (the share target's "Save to KJB", or Look up on the page): what the link is (quick info at
 * once, the full info when it comes), then Audio at the set quality (Quality picks another) or Video at a height. A tap
 * starts at once, before the full info if need be (the add-on looks the link up itself). With full info, a song KJB's
 * Duplicates rules find on the phone asks first (Warn about copies). [onStarted] hears a download that started.
 */
@Composable
fun SaveSheet(url: String, onDismiss: () -> Unit, onStarted: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ui by Downloads.state.collectAsStateWithLifecycle()
    val lookup = remember(url) { LinkLookup(context.applicationContext, scope, url) }
    DisposableEffect(lookup) {
        lookup.start()
        onDispose { lookup.close() }
    }
    LaunchedEffect(Unit) { Downloads.load(context) }
    var step by remember { mutableStateOf(SaveStep.MAIN) }
    var copyOf by remember { mutableStateOf<AudioFile?>(null) }
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        val next = pending
        pending = null
        next?.invoke()
    }

    /** Starts the download (asking for notifications first on Android 13+, once), then closes the sheet. */
    fun begin(kind: DlKind, height: Int?, quality: AudioQuality?) {
        val info = lookup.info
        val go = {
            Downloads.add(context, url, kind, height, info?.title.orEmpty(), info?.artist.orEmpty(), info?.thumbnail, info?.durationSec ?: 0, quality)
            onStarted()
            onDismiss()
        }
        val asked = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (asked) {
            pending = go
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            go()
        }
    }

    /** Audio: with full info and Warn about copies on, a song already on the phone asks first. */
    fun audio(quality: AudioQuality?) {
        val info = lookup.info
        if (!ui.settings.warnDuplicates || info == null || info.partial || info.durationSec <= 0) return begin(DlKind.AUDIO, null, quality)
        scope.launch {
            val library = withContext(Dispatchers.IO) { (MediaStoreLibrary(context.contentResolver, Lyrify.writes).read() as? LibraryRead.Files)?.files.orEmpty() }
            val copy = DuplicateCheck.copyOf(DuplicateCheck.Candidate(info.title, info.artist, info.durationSec * 1000L), library)
            if (copy == null) {
                begin(DlKind.AUDIO, null, quality)
            } else {
                copyOf = copy
                pending = { begin(DlKind.AUDIO, null, quality) }
            }
        }
    }

    when (step) {
        SaveStep.MAIN -> LyrifySheet(onDismiss = onDismiss) {
            LinkHead(lookup.info, url)
            when {
                lookup.addon != null && lookup.addon != AddonState.Ready -> {
                    Text(stringResource(R.string.dl_get_first), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LyrifyButton(stringResource(R.string.dl_open_kjb), onClick = {
                        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(EXTRA_DESTINATION, DESTINATION_DOWNLOADS))
                        onDismiss()
                    }, modifier = Modifier.fillMaxWidth())
                }
                else -> {
                    if (lookup.failed != null && lookup.info?.partial != false) {
                        Text(stringResource(R.string.dl_lookup_failed), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else if (!lookup.settled) {
                        Text(stringResource(R.string.dl_looking_up), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                        LyrifyButton(stringResource(R.string.dl_audio), onClick = { audio(null) }, icon = R.drawable.ic_music_note, enabled = lookup.addon == AddonState.Ready, modifier = Modifier.weight(1f))
                        LyrifyButton(stringResource(R.string.dl_video), onClick = { step = SaveStep.VIDEO }, icon = R.drawable.ic_movie, kind = ButtonKind.SECONDARY, enabled = lookup.addon == AddonState.Ready, modifier = Modifier.weight(1f))
                    }
                    LyrifyButton(
                        stringResource(R.string.dl_quality) + " · " + stringResource(qualityLabel(ui.settings.audioQuality)),
                        onClick = { step = SaveStep.AUDIO },
                        kind = ButtonKind.GHOST,
                        enabled = lookup.addon == AddonState.Ready,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        SaveStep.AUDIO -> QualitySheet(R.string.dl_quality, R.string.dl_audio_quality_sheet, ui.settings.audioQuality, onDismiss = { step = SaveStep.MAIN }) { audio(it) }
        SaveStep.VIDEO -> VideoSheet(lookup.info?.choices.orEmpty(), onDismiss = { step = SaveStep.MAIN }) { begin(DlKind.VIDEO, it.height, null) }
    }
    copyOf?.let { copy ->
        ConfirmDialog(
            title = stringResource(R.string.dl_copy_title),
            confirm = stringResource(R.string.dl_save_anyway),
            dismiss = stringResource(R.string.dl_cancel),
            onConfirm = {
                copyOf = null
                // Taken first: the download it starts may set its own (the notifications ask).
                val next = pending
                pending = null
                next?.invoke()
            },
            onDismiss = {
                copyOf = null
                pending = null
            },
            text = stringResource(R.string.dl_copy_of, File(copy.path).name),
            icon = R.drawable.ic_content_copy,
            destructive = false,
        )
    }
}

private enum class SaveStep { MAIN, AUDIO, VIDEO }

/** The link's cover, title, artist and length, as far as they are known yet. */
@Composable
private fun LinkHead(info: DlInfo?, url: String) {
    val thumbnail by produceState<ImageBitmap?>(null, info?.thumbnail) { value = info?.thumbnail?.let { withContext(Dispatchers.IO) { thumbnail(it) } } }
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(72.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
            val image = thumbnail
            if (image != null) {
                Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(painterResource(R.drawable.ic_download), contentDescription = null, modifier = Modifier.size(28.dp))
            }
        }
        Column(Modifier.weight(1f)) {
            Text(info?.title?.ifBlank { null } ?: url, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
            info?.artist?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
            info?.durationSec?.takeIf { it > 0 }?.let { Text(DateUtils.formatElapsedTime(it.toLong()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
    Spacer(Modifier.height(4.dp))
}

/** The video's qualities with their sizes (none yet from the quick info); a choice starts the download. */
@Composable
private fun VideoSheet(choices: List<VideoChoice>, onDismiss: () -> Unit, onPick: (VideoChoice) -> Unit) {
    val context = LocalContext.current
    val shown = choices.ifEmpty { listOf(VideoChoice(null, "")) }
    LyrifySheet(onDismiss = onDismiss, spacing = 8.dp) {
        Text(stringResource(R.string.dl_video_quality), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.dl_video_quality_sheet), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        shown.forEach { choice ->
            val size = choice.bytes?.let { Formatter.formatShortFileSize(context, it) }
            val (label, detail) = when {
                choice.height == null && choice.label.isNotEmpty() && size != null -> stringResource(R.string.dl_best) to stringResource(R.string.dl_best_detail, choice.label, size)
                choice.height == null -> stringResource(R.string.dl_best) to choice.label
                else -> choice.label to size.orEmpty()
            }
            ChoiceLine(label, detail, selected = false) { onPick(choice) }
        }
    }
}

/** The link's cover ([Thumbnails.fetch]: https, a host on the internet, 2 MB at most), scaled down as it is decoded. */
private fun thumbnail(url: String): ImageBitmap? {
    val bytes = Thumbnails.fetch(url) ?: return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val sample = generateSequence(1) { it * 2 }.first { maxOf(bounds.outWidth, bounds.outHeight) / it <= THUMBNAIL_SIDE }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}

/** Where the sheets let go of the add-on once they have closed. */
private val closing = CoroutineScope(SupervisorJob() + Dispatchers.IO + logFailures)

private const val THUMBNAIL_SIDE = 512
