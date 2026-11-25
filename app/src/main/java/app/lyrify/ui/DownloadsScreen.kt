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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lyrify.Lyrify
import app.lyrify.R
import app.lyrify.addon.AddonExemptions
import app.lyrify.addon.AddonInstalls
import app.lyrify.addon.AddonState
import app.lyrify.addon.InstallFailure
import app.lyrify.addon.InstallState
import app.lyrify.addon.KjbAddons
import app.lyrify.addon.packageChanges
import app.lyrify.dl.api.AudioQuality
import app.lyrify.dl.api.DlEnd
import app.lyrify.dl.api.DlFailure
import app.lyrify.dl.api.DlInfo
import app.lyrify.dl.api.DlKind
import app.lyrify.dl.api.DlProgress
import app.lyrify.dl.api.DlSearch
import app.lyrify.dl.api.DlSearchResult
import app.lyrify.dl.api.DlSearchResults
import app.lyrify.dl.api.DlUpdate
import app.lyrify.dl.api.VideoChoice
import app.lyrify.dl.api.playlistId
import app.lyrify.downloads.DlAddon
import app.lyrify.downloads.DlLookup
import app.lyrify.downloads.DlLookupEvents
import app.lyrify.downloads.DlSearchEvents
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
import app.lyrify.settings.DockTab
import app.lyrify.settings.SettingsSection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
 * Tools > Downloads: the add-on to get (or update) until it is ready, then Run when locked offered once; then a link field
 * with Paste (a copied link is offered when Check clipboard is on), Search YouTube (its videos in the search sheet; an
 * add-on too old for it offers its update instead), and the downloads: what downloads now with Cancel, what did not save
 * with Retry, and what was saved (VidGrab's Finished list), newest first. After a download stalled
 * with the phone locked, one plain line says so. Without [onBack] it is the bar's Downloads (KJB 3.1): the bar's header,
 * no Back, and its icon tapped again goes to the top.
 */
@Composable
fun DownloadsPage(onBack: (() -> Unit)?) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val toasts = LocalToasts.current
    val scope = rememberCoroutineScope()
    val ui by Downloads.state.collectAsStateWithLifecycle()
    val dl by remember(context) { DlAddon.states(context) }.collectAsStateWithLifecycle(initialValue = null)
    val addon = dl?.state
    val installer = remember(context) { AddonInstalls.installer(context, KjbAddons.DOWNLOADS) }
    val install by installer.state.collectAsStateWithLifecycle()
    var text by rememberSaveable { mutableStateOf("") }
    var looking by rememberSaveable { mutableStateOf<String?>(null) }
    var searching by rememberSaveable { mutableStateOf<String?>(null) }
    var opened by remember { mutableStateOf<DownloadItem?>(null) }
    var filesAccess by remember { mutableStateOf(true) }
    // The add-on left out of the phone's battery optimisation (AD9), and what the user answered about it.
    val exemptions = remember(context) { AddonExemptions.store(context) }
    val exemptionChoices by exemptions.choices.collectAsStateWithLifecycle()
    var exempt by remember { mutableStateOf(AddonExemptions.exempt(context, KjbAddons.DOWNLOADS)) }
    val requestExemption = rememberExemptionRequest(KjbAddons.DOWNLOADS) { exempt = AddonExemptions.exempt(context, KjbAddons.DOWNLOADS) }
    // Allow tapped on the offer: not offered again on this visit, whatever the phone answered.
    var allowTapped by rememberSaveable { mutableStateOf(false) }
    val locked = lockedPhone(KjbAddons.DOWNLOADS, addon == AddonState.Ready, exempt, exemptionChoices)
    LaunchedEffect(Unit) { Downloads.load(context) }
    LifecycleResumeEffect(Unit) {
        filesAccess = Environment.isExternalStorageManager()
        exempt = AddonExemptions.exempt(context, KjbAddons.DOWNLOADS)
        installer.resume()
        Downloads.resume(context)
        if (Downloads.state.value.settings.checkClipboard && text.isEmpty()) clipboardLink(context)?.let { text = it }
        onPauseOrDispose { }
    }
    val reduced = LocalReducedMotion.current
    val list = rememberLazyListState()
    if (onBack == null) ReselectToTop(LocalNavigator.current.state.reselectsOf(DockTab.DOWNLOADS)) { list.animateScrollToItem(0) }
    PageFrame(
        topBar = { under, backdrop ->
            if (onBack == null) {
                MainTopBar(DockTab.DOWNLOADS, under, backdrop)
            } else {
                LyrifyTopBar(
                    title = { Text(stringResource(R.string.dl_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    under = under,
                    backdrop = backdrop,
                    navigation = { ChromeIconButton(R.drawable.ic_arrow_back, stringResource(R.string.check_back), onBack) },
                )
            }
        },
    ) { padding ->
        LazyColumn(
            state = list,
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
            // Only after a download stalled with the phone locked: one plain line, until it is answered.
            if (locked.hint) {
                item(key = "locked", contentType = "locked") {
                    Box(rowMotion(reduced)) {
                        LockedHint(
                            stringResource(R.string.dl_locked_hint),
                            onAllow = {
                                requestExemption()
                                exemptions.update { it.hintSeen(KjbAddons.DOWNLOADS) }
                            },
                            onNotNow = { exemptions.update { it.hintSeen(KjbAddons.DOWNLOADS) } },
                            modifier = Modifier.padding(horizontal = ScreenPadding),
                        )
                    }
                }
            }
            // Android's few hours a day of background work for the add-on used up: its downloads go on only while KJB is open.
            if (ui.keepOpen) {
                item(key = "keep-open", contentType = "keep-open") {
                    Box(rowMotion(reduced)) { Banner(R.drawable.ic_hourglass_top_fill, stringResource(R.string.dl_keep_open), action = null) {} }
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
            // One box: a link is looked up, words are searched for on YouTube when the add-on can.
            if (ready) {
                val searches = dl?.searches == true
                item(key = "link", contentType = "link") {
                    Box(rowMotion(reduced)) {
                        LinkOrSearchField(
                            text,
                            { text = it },
                            searches,
                            stringResource(if (searches) R.string.dl_link_or_search else R.string.dl_link),
                            stringResource(if (searches) R.string.dl_link_or_search_hint else R.string.dl_link_hint),
                            Modifier.padding(horizontal = ScreenPadding),
                            onPaste = { clipboardLink(context)?.let { text = it } },
                            onLookUp = { looking = it },
                            onSearch = { searching = it },
                        )
                    }
                }
            }
            // The newer add-on search needs (getting it shows on the add-on card above).
            if (dl?.searches != true && ready && install == InstallState.Idle) {
                item(key = "search-update", contentType = "search-update") {
                    Box(rowMotion(reduced)) { Banner(R.drawable.ic_system_update, stringResource(R.string.dl_search_update), stringResource(R.string.dl_addon_update), installer::start) }
                }
            }
            downloadsList(ui, onOpen = { opened = it }, onCancel = { key, rest -> Downloads.cancel(context, key, rest) })
        }
    }
    // Where the add-on is set up, once it is ready: Not now (or the sheet put away) is kept, and the row in Settings keeps
    // the way to it.
    if (locked.offer && !allowTapped) {
        AskSheet(
            R.drawable.ic_lock,
            R.string.addon_locked_title,
            R.string.dl_locked_text,
            R.string.setup_allow,
            onAction = {
                allowTapped = true
                requestExemption()
            },
            onNotNow = { exemptions.update { it.turnedDown(KjbAddons.DOWNLOADS) } },
        )
    }
    looking?.let { url ->
        SaveSheet(url, onDismiss = { looking = null }) {
            text = ""
            scope.launch { toasts.say(resources, R.string.dl_started) }
        }
    }
    searching?.let { query ->
        YouTubeSearchSheet(query, onDismiss = { searching = null }) {
            text = ""
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

/**
 * The page's one box, with Paste: a link in it is looked up ([onLookUp], the save sheet), and words are searched for on
 * YouTube ([onSearch], the search sheet) when the add-on [searches]; with one too old to search, only a link goes. Its
 * button and the keyboard's key say which. [label] above it, also what TalkBack names it by once it is filled, and
 * [placeholder] in it while it is empty.
 */
@Composable
internal fun LinkOrSearchField(
    text: String,
    onText: (String) -> Unit,
    searches: Boolean,
    label: String,
    placeholder: String?,
    modifier: Modifier = Modifier,
    onPaste: () -> Unit,
    onLookUp: (String) -> Unit,
    onSearch: (String) -> Unit,
) {
    val link = ShareLinks.parse(text).firstOrNull()
    val words = link == null && searches
    val usable = link != null || (words && text.isNotBlank())
    val submit = {
        if (link != null) onLookUp(link) else if (usable) onSearch(text)
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = modifier) {
        LyrifyField(
            value = text,
            onValueChange = onText,
            modifier = Modifier.fillMaxWidth(),
            label = label,
            placeholder = placeholder,
            trailing = { ChromeIconButton(R.drawable.ic_content_paste, stringResource(R.string.dl_paste), onPaste) },
            keyboardOptions = KeyboardOptions(keyboardType = if (searches) KeyboardType.Text else KeyboardType.Uri, imeAction = if (words) ImeAction.Search else ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { submit() }, onSearch = { submit() }),
        )
        LyrifyButton(
            stringResource(if (words) R.string.dl_search_go else R.string.dl_look_up),
            onClick = submit,
            enabled = usable,
            icon = if (words) R.drawable.ic_search else R.drawable.ic_download,
            modifier = Modifier.fillMaxWidth(),
        )
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

/**
 * The add-on card's sentence when getting the add-on failed: a KJB built with no add-on to download says so, as the AI
 * page does (asking for the connection would send the user looking for a fault that is not there), else the connection.
 */
@StringRes
internal fun addonFailedText(reason: InstallFailure): Int = when (reason) {
    InstallFailure.NOT_PUBLISHED -> R.string.dl_addon_not_published
    InstallFailure.NETWORK, InstallFailure.DOWNLOAD, InstallFailure.NOT_KJBS, InstallFailure.INSTALL -> R.string.dl_addon_failed
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
        is InstallState.Failed -> Banner(R.drawable.ic_error, stringResource(addonFailedText(install.reason)), stringResource(R.string.dl_try_again), onGet)
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

/**
 * The downloads: Now (under way, waiting, not saved) and Done, newest first; or a line saying how to start. In Now, a
 * playlist's songs come under its own row: how many were saved and could not be, a bar, and its Cancel ([onCancel] with
 * rest) while songs are still to come.
 */
private fun LazyListScope.downloadsList(ui: DownloadsUi, onOpen: (DownloadItem) -> Unit, onCancel: (key: String, rest: Boolean) -> Unit) {
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
            val colors = MaterialTheme.colorScheme
            GroupedRows {
                items.forEachIndexed { index, item ->
                    val playlist = item.playlist?.takeIf { title == R.string.dl_now && it != items.getOrNull(index - 1)?.playlist }
                    if (playlist != null) {
                        val count = playlist.count(ui.items)
                        GroupedRow(
                            R.drawable.ic_queue_music,
                            playlist.title,
                            tile = colors.surfaceContainerHighest,
                            onTile = colors.onSurfaceVariant,
                            first = index == 0,
                            supporting = {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(DownloadNotices.playlistText(LocalContext.current, count), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    if (count.active > 0) Bar((count.saved + count.failed).toFloat() / count.total)
                                }
                            },
                            trailing = playlist.rest(ui.items).firstOrNull()?.let { next ->
                                { ChromeIconButton(R.drawable.ic_close, stringResource(R.string.dl_cancel_rest), onClick = { onCancel(next, true) }) }
                            },
                        )
                    }
                    DownloadRow(item, ui.progress[item.key], first = index == 0 && playlist == null, onOpen = { onOpen(item) }, onCancel = { onCancel(item.key, false) })
                }
            }
        }
    }
}

/** One download: its kind, title, where it is and its length (a bar while it downloads); Cancel while it is under way. */
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(rowLine(context, item, progress), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    SongLength(item.durationSec * 1_000L, Modifier.padding(start = 8.dp))
                }
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
internal fun failureSentence(reason: DownloadFailure): Int = when (reason) {
    DownloadFailure.OFFLINE -> R.string.dl_failed_offline_detail
    DownloadFailure.NOT_FOUND -> R.string.dl_failed_not_found_detail
    DownloadFailure.SITE_CHANGED -> R.string.dl_failed_site_detail
    DownloadFailure.SITE_CHANGED_NEWEST -> R.string.dl_failed_site_newest_detail
    DownloadFailure.BLOCKED -> R.string.dl_failed_blocked_detail
    DownloadFailure.AGE_RESTRICTED -> R.string.dl_failed_age_detail
    DownloadFailure.NO_ROOM -> R.string.dl_failed_room_detail
    DownloadFailure.HAND_OFF -> R.string.dl_failed_hand_off_detail
    DownloadFailure.STALLED -> R.string.dl_failed_stalled_detail
    DownloadFailure.FROZEN -> R.string.dl_failed_frozen_detail
    DownloadFailure.ADDON_GONE -> R.string.dl_failed_addon_detail
    DownloadFailure.NO_ADDON -> R.string.dl_failed_no_addon_detail
    DownloadFailure.NOT_ALLOWED -> R.string.dl_failed_not_allowed_detail
    DownloadFailure.LOCAL_ADDRESS -> R.string.dl_failed_local_detail
    DownloadFailure.UNKNOWN -> R.string.dl_failed_unknown_detail
}

/**
 * Why a search found nothing to show, in dl1's words where they are true of a search too: no connection, or a site that
 * refused it. Anything else asks to try again or update the downloader, since a search makes no update of its own.
 */
@StringRes
internal fun searchFailureText(failure: DlFailure): Int = when (failure) {
    DlFailure.OFFLINE -> R.string.dl_failed_offline_detail
    DlFailure.BLOCKED -> R.string.dl_failed_blocked_detail
    else -> R.string.dl_failed_unknown_detail
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
 * language, where songs and videos go, the copy warning, the clipboard, and yt-dlp's update with its version. Its rows,
 * and their questions to the add-on, are there only while the section is open.
 */
internal fun LazyListScope.downloadsSettings(sections: SettingsSections) {
    item(key = SettingsSection.DOWNLOADS.name, contentType = "section") {
        // yt-dlp's update beside the section, not in its rows: closing the section leaves it running, its message to come,
        // and opening it again starts no second one meanwhile.
        val scope = rememberCoroutineScope()
        var updating by remember { mutableStateOf(false) }
        SettingsSectionCard(SettingsSection.DOWNLOADS, sections) { DownloadsSettings(scope, updating) { updating = it } }
    }
}

private enum class DownloadsSheet { QUALITY, AUDIO_FOLDER, VIDEO_FOLDER }

@Composable
private fun DownloadsSettings(scope: CoroutineScope, updating: Boolean, onUpdating: (Boolean) -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val toasts = LocalToasts.current
    val ui by Downloads.state.collectAsStateWithLifecycle()
    var sheet by remember { mutableStateOf<DownloadsSheet?>(null) }
    val version by produceState<String?>(null, updating) { value = withContext(Dispatchers.IO) { DlAddon.withPort(context) { it.status()?.ytdlpVersion } } }
    // Locked phone, while the add-on is ready: left out of the phone's battery optimisation or not (AD9).
    val addon by remember(context) { DlAddon.states(context) }.collectAsStateWithLifecycle(initialValue = null)
    var exempt by remember { mutableStateOf(AddonExemptions.exempt(context, KjbAddons.DOWNLOADS)) }
    val requestExemption = rememberExemptionRequest(KjbAddons.DOWNLOADS) { exempt = AddonExemptions.exempt(context, KjbAddons.DOWNLOADS) }
    LifecycleResumeEffect(Unit) {
        exempt = AddonExemptions.exempt(context, KjbAddons.DOWNLOADS)
        onPauseOrDispose { }
    }
    LaunchedEffect(Unit) { Downloads.load(context) }
    val settings = ui.settings
    fun change(block: (DownloadSettings) -> DownloadSettings) = Downloads.settings(context, block)
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
                onUpdating(true)
                scope.launch {
                    val outcome = withContext(Dispatchers.IO) { DlAddon.withPort(context) { it.update() } }
                    onUpdating(false)
                    toasts.say(resources, updateMessage(outcome))
                }
            }
        }
        lockedRow(addon?.state == AddonState.Ready, exempt)?.let { row ->
            LockedPhoneRow(row, onAllow = requestExemption, onOpenAppInfo = { context.startFirst(listOf(AddonInstalls.addonInfo(KjbAddons.DOWNLOADS))) })
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

/**
 * One choice of a sheet: its label, its line, and a check when it is the one set, else its [icon] (what a tap does); a
 * search's video has its thumbnail first ([leading]) and its length at the end of its line ([lengthMs]).
 */
@Composable
private fun ChoiceLine(
    label: String,
    detail: String,
    selected: Boolean,
    @DrawableRes icon: Int? = null,
    leading: (@Composable () -> Unit)? = null,
    lengthMs: Long = 0,
    onClick: () -> Unit,
) {
    LyrifyCard(Modifier.fillMaxWidth(), color = if (selected) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainerLow, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            leading?.invoke()
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    SongLength(lengthMs)
                }
            }
            if (selected) {
                Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = LocalBrandColors.current.highlight, modifier = Modifier.size(20.dp))
            } else if (icon != null) {
                Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
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
 * One link being looked up in the Downloads add-on while the sheet shows ([DlLookup]): the quick info at once (YouTube's
 * oEmbed), then the full info, or why there is none. The add-on stays bound until [close], which asks nothing of it.
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

    private val lookup = DlLookup { DlAddon.connect(context) }

    fun start() {
        scope.launch(Dispatchers.IO) {
            val state = lookup.start(url, events)
            withContext(Dispatchers.Main) { addon = state }
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

    /** The sheet went: the add-on is let go of, with no call into it (one the phone froze could hold the call). */
    fun close() = lookup.close()
}

/**
 * The save sheet for [url] (the share target's "Save to KJB", Look up on the page, or a video the search sheet found):
 * what the link is (quick info at once, the full info when it comes), then Audio at the set quality (Quality picks
 * another) or Video at a height. A tap starts at once, before the full info if need be (the add-on looks the link up
 * itself). With full info, a song KJB's Duplicates rules find on the phone asks first (Warn about copies). A playlist's
 * link (a list, an album, a Mix) lists its songs, all ticked, and saves each one ticked as a download of its own, in the
 * format and quality picked as for one song; a song's link that names a playlist too asks first which is meant.
 * [onStarted] hears a download that started.
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
    // A song's link that names a playlist too: whether the whole playlist is meant, null until the user says.
    var whole by remember { mutableStateOf<Boolean?>(null) }
    // The playlist's songs the user left out, by their place in it.
    var unticked by remember { mutableStateOf(emptySet<Int>()) }
    val named = lookup.info?.playlist
    // What the sheet saves song by song: a playlist's own link, or the whole playlist a song's link names.
    val playlist = named?.takeIf { !it.withSong || whole == true }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        val next = pending
        pending = null
        next?.invoke()
    }

    /** Starts the download (asking for notifications first on Android 13+, once), then closes the sheet. */
    fun begin(kind: DlKind, height: Int?, quality: AudioQuality?) {
        val info = lookup.info
        val go = {
            if (playlist == null) {
                Downloads.add(context, listOf(DlSearchResult(url, info?.title.orEmpty(), info?.artist.orEmpty(), info?.durationSec ?: 0, info?.thumbnail)), kind, height, quality)
            } else {
                Downloads.add(context, playlist.songs.filterIndexed { index, _ -> index !in unticked }, kind, height, quality, playlist.title)
            }
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

    /** Audio: with full info and Warn about copies on, a song already on the phone asks first (a playlist's songs never do). */
    fun audio(quality: AudioQuality?) {
        val info = lookup.info
        if (playlist != null || !ui.settings.warnDuplicates || info == null || info.partial || info.durationSec <= 0) return begin(DlKind.AUDIO, null, quality)
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
        SaveStep.MAIN -> LyrifySheet(
            onDismiss = onDismiss,
            // A playlist's buttons stay in view under its songs, once they are in: Save, as audio at the quality set, or Video
            // at a height.
            footer = playlist?.takeIf { it.songs.isNotEmpty() }?.let { list ->
                {
                    val ticked = list.songs.size - unticked.size
                    val ready = lookup.addon == AddonState.Ready && ticked > 0
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        LyrifyButton(
                            pluralStringResource(if (ticked == list.songs.size) R.plurals.dl_save_all else R.plurals.dl_save_songs, ticked, ticked),
                            onClick = { audio(null) },
                            icon = R.drawable.ic_music_note,
                            enabled = ready,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                            LyrifyButton(stringResource(R.string.dl_video), onClick = { step = SaveStep.VIDEO }, icon = R.drawable.ic_movie, kind = ButtonKind.SECONDARY, enabled = ready, modifier = Modifier.weight(1f))
                            LyrifyButton(
                                stringResource(R.string.dl_quality) + " · " + stringResource(qualityLabel(ui.settings.audioQuality)),
                                onClick = { step = SaveStep.AUDIO },
                                kind = ButtonKind.GHOST,
                                enabled = ready,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            },
        ) {
            // A song's playlist has the playlist's own name, its first song's cover and its songs' length together.
            LinkHead(if (playlist?.withSong == true) DlInfo(url, playlist.title, "", playlist.songs.sumOf { it.durationSec }, playlist.songs.firstOrNull()?.thumbnail) else lookup.info, url)
            val failed = lookup.failed
            when {
                lookup.addon != null && lookup.addon != AddonState.Ready -> {
                    Text(stringResource(if (lookup.addon is AddonState.Outdated) R.string.dl_playlist_update_first else R.string.dl_get_first), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LyrifyButton(stringResource(R.string.dl_open_kjb), onClick = {
                        openDownloads(context)
                        onDismiss()
                    }, modifier = Modifier.fillMaxWidth())
                }
                // Nothing to save: no one but its owner can list it.
                failed == DlFailure.PRIVATE_PLAYLIST -> Text(stringResource(R.string.dl_playlist_private), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // A song's playlist has its songs as soon as the add-on says which is meant; a playlist's own link with the
                // full info.
                playlist != null -> when {
                    failed != null && playlist.songs.isEmpty() -> Text(stringResource(searchFailureText(failed)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    lookup.info?.partial != false && playlist.songs.isEmpty() -> Text(stringResource(R.string.dl_looking_up), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> {
                        val songs = playlist.songs.size
                        Text(
                            when {
                                playlist.mix -> pluralStringResource(R.plurals.dl_mix_songs, songs, songs)
                                playlist.more > 0 -> pluralStringResource(R.plurals.dl_playlist_first, songs + playlist.more, songs, songs + playlist.more)
                                else -> pluralStringResource(R.plurals.dl_playlist_songs, songs, songs)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (songs > 0) {
                            CheckRow(unticked.isEmpty(), { all -> unticked = if (all) emptySet() else playlist.songs.indices.toSet() }) {
                                Text(stringResource(R.string.dl_all_songs), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            }
                        }
                        playlist.songs.forEachIndexed { index, song ->
                            CheckRow(index !in unticked, { on -> unticked = if (on) unticked - index else unticked + index }) {
                                Text(song.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(song.channel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                    SongLength(song.durationSec * 1_000L)
                                }
                            }
                        }
                    }
                }
                // A song's link that names a playlist too: which one is meant.
                named != null && whole == null -> {
                    Text(stringResource(if (named.mix) R.string.dl_ask_mix else R.string.dl_ask_playlist), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LyrifyButton(stringResource(R.string.dl_just_song), onClick = { whole = false }, icon = R.drawable.ic_music_note, modifier = Modifier.fillMaxWidth())
                    LyrifyButton(
                        pluralStringResource(if (named.mix) R.plurals.dl_whole_mix else R.plurals.dl_whole_playlist, named.songs.size, named.songs.size),
                        onClick = { whole = true },
                        icon = R.drawable.ic_queue_music,
                        kind = ButtonKind.SECONDARY,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                else -> {
                    if (failed != null && lookup.info?.partial != false) {
                        Text(stringResource(R.string.dl_lookup_failed), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else if (!lookup.settled) {
                        Text(stringResource(R.string.dl_looking_up), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    // A link that names a playlist waits until the add-on says whether it is one (the ask, or the songs), so it
                    // is never saved as one song unasked; Just this song, or a lookup that ended, lets it go.
                    val ready = lookup.addon == AddonState.Ready && (named != null || lookup.settled || playlistId(url) == null)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                        LyrifyButton(stringResource(R.string.dl_audio), onClick = { audio(null) }, icon = R.drawable.ic_music_note, enabled = ready, modifier = Modifier.weight(1f))
                        LyrifyButton(stringResource(R.string.dl_video), onClick = { step = SaveStep.VIDEO }, icon = R.drawable.ic_movie, kind = ButtonKind.SECONDARY, enabled = ready, modifier = Modifier.weight(1f))
                    }
                    LyrifyButton(
                        stringResource(R.string.dl_quality) + " · " + stringResource(qualityLabel(ui.settings.audioQuality)),
                        onClick = { step = SaveStep.AUDIO },
                        kind = ButtonKind.GHOST,
                        enabled = ready,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        SaveStep.AUDIO -> QualitySheet(R.string.dl_quality, R.string.dl_audio_quality_sheet, ui.settings.audioQuality, onDismiss = { step = SaveStep.MAIN }) { audio(it) }
        // A playlist's songs were never opened: a song's own sizes are not theirs.
        SaveStep.VIDEO -> VideoSheet(lookup.info?.choices.orEmpty().map { if (playlist == null) it else it.copy(bytes = null) }, onDismiss = { step = SaveStep.MAIN }) {
            begin(DlKind.VIDEO, it.height, null)
        }
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

/** KJB's Downloads page, from the share target or from inside KJB. SINGLE_TOP: with KJB open, MainActivity gets it in onNewIntent. */
private fun openDownloads(context: Context) {
    context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(EXTRA_DESTINATION, DESTINATION_DOWNLOADS))
}

// The search sheet: the page's Search YouTube, and Huddle's Search on YouTube

/**
 * YouTube searches in the Downloads add-on for the search sheet ([DlLookup.search]), one at a time: a new one lets go of
 * the one before, as [close] does, and only the newest is heard. What it found, or why it failed, or why the add-on
 * can't search (Outdated: too old for it).
 */
@Stable
private class YouTubeSearch(private val context: Context, private val scope: CoroutineScope) {
    var results: List<DlSearchResult>? by mutableStateOf(null)
        private set
    var failed: DlFailure? by mutableStateOf(null)
        private set

    /** Null while it binds; then Ready, or why the add-on can't search. */
    var addon: AddonState? by mutableStateOf(null)
        private set

    /** The newest search, until [close]. Read and set on the main thread. */
    private var lookup: DlLookup? = null

    /** Searches for [words], on one line and cut to what a search takes. */
    fun search(words: String) {
        lookup?.close()
        val next = DlLookup { DlAddon.connect(context) }
        lookup = next
        results = null
        failed = null
        addon = null
        val events = object : DlSearchEvents {
            override fun onResults(results: DlSearchResults) {
                scope.launch(Dispatchers.Main) { if (lookup === next) this@YouTubeSearch.results = results.results }
            }

            override fun onFailed(end: DlEnd.Failed) {
                scope.launch(Dispatchers.Main) { if (lookup === next) failed = end.failure }
            }
        }
        val search = DlSearch.of(words)
        scope.launch(Dispatchers.IO) {
            val state = next.search(search, events)
            withContext(Dispatchers.Main) { if (lookup === next) addon = state }
        }
    }

    /** Nothing found yet, nor any reason there will be none: the sheet says it searches. */
    val waiting: Boolean get() = results == null && failed == null && (addon == null || addon == AddonState.Ready)

    /** The sheet went: the add-on is let go of, with no call into it (one the phone froze could hold the call). */
    fun close() {
        lookup?.close()
        lookup = null
    }
}

/**
 * The search sheet: YouTube's videos for [query] ("<artist> <title>" from Huddle's Search on YouTube, or the page's
 * words), searched at once, and the words to change and search again. A video's title, channel and length; a tap opens
 * the save sheet for its link (the usual download, the copy warning too). Without the add-on, or with one too old to
 * search, it says so and opens Downloads, where KJB gets or updates it. [onStarted] hears a download that started, after
 * which the sheet goes ([onDismiss]).
 */
@Composable
fun YouTubeSearchSheet(query: String, onDismiss: () -> Unit, onStarted: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val search = remember { YouTubeSearch(context.applicationContext, scope) }
    var words by rememberSaveable { mutableStateOf(query) }
    var picked by remember { mutableStateOf<DlSearchResult?>(null) }
    DisposableEffect(search) {
        search.search(words)
        onDispose { search.close() }
    }
    picked?.let {
        SaveSheet(it.url, onDismiss = { picked = null }) {
            onStarted()
            onDismiss()
        }
        return
    }
    LyrifySheet(onDismiss = onDismiss, spacing = 8.dp) {
        Text(stringResource(R.string.dl_search), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        when (val addon = search.addon) {
            null, AddonState.Ready -> {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                    // Labelled, not a placeholder: it opens with the words in it, and the label is what TalkBack names it by.
                    LyrifyField(
                        value = words,
                        onValueChange = { words = it.take(DlSearch.MAX_QUERY) },
                        modifier = Modifier.fillMaxWidth(),
                        label = stringResource(R.string.dl_search_hint),
                        leading = { Icon(painterResource(R.drawable.ic_search), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp)) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { if (words.isNotBlank()) search.search(words) }),
                    )
                    LyrifyButton(stringResource(R.string.dl_search_go), onClick = { search.search(words) }, enabled = words.isNotBlank(), icon = R.drawable.ic_search, modifier = Modifier.fillMaxWidth())
                }
                SearchFound(search) { picked = it }
            }
            AddonState.Unreachable -> {
                Text(stringResource(R.string.dl_addon_unreachable), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LyrifyButton(stringResource(R.string.dl_try_again), onClick = { search.search(words) }, modifier = Modifier.fillMaxWidth())
            }
            AddonState.Missing, AddonState.Untrusted, is AddonState.Outdated -> {
                val text = if (addon is AddonState.Outdated) R.string.dl_search_update_first else R.string.dl_search_get_first
                Text(stringResource(text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LyrifyButton(stringResource(R.string.dl_open_downloads), onClick = {
                    openDownloads(context)
                    onDismiss()
                }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/** What the search found, each video a choice to download; or that it searches, found nothing, or why it failed. */
@Composable
private fun SearchFound(search: YouTubeSearch, onPick: (DlSearchResult) -> Unit) {
    val found = search.results
    val failed = search.failed
    val line = when {
        search.waiting -> stringResource(R.string.dl_searching)
        failed != null -> stringResource(searchFailureText(failed))
        found.isNullOrEmpty() -> stringResource(R.string.dl_search_none)
        else -> null
    }
    line?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    found.orEmpty().forEach { result ->
        ChoiceLine(
            result.title,
            result.channel,
            selected = false,
            icon = R.drawable.ic_download,
            leading = { Thumbnail(result.thumbnail, 56.dp, R.drawable.ic_movie) },
            lengthMs = result.durationSec * 1_000L,
        ) { onPick(result) }
    }
}

/** The link's cover, title, artist and length, as far as they are known yet. */
@Composable
private fun LinkHead(info: DlInfo?, url: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Thumbnail(info?.thumbnail, 72.dp, R.drawable.ic_download)
        Column(Modifier.weight(1f)) {
            Text(info?.title?.ifBlank { null } ?: url, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
            info?.artist?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
            info?.let { SongLength(it.durationSec * 1_000L) }
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

/** A link's cover in a rounded square of [size] ([thumbnail], off the main thread), [placeholder] until it comes or without one. */
@Composable
private fun Thumbnail(url: String?, size: Dp, @DrawableRes placeholder: Int) {
    val image by produceState<ImageBitmap?>(null, url) { value = url?.let { withContext(Dispatchers.IO) { thumbnail(it) } } }
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(size).clip(RoundedCornerShape(size * 14 / 72)).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        val shown = image
        if (shown != null) {
            Image(shown, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(painterResource(placeholder), contentDescription = null, modifier = Modifier.size(size * 28 / 72))
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

private const val THUMBNAIL_SIDE = 512
