package app.lyrify.ui

import androidx.compose.ui.text.font.FontWeight
import android.os.Build
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lyrify.R
import app.lyrify.models.ModelState
import app.lyrify.player.StartWhenConnected
import app.lyrify.player.connectNames
import app.lyrify.settings.AdvancedSettings
import app.lyrify.settings.AudioSettings
import app.lyrify.settings.LibraryTab
import app.lyrify.settings.Levelling
import app.lyrify.settings.NotificationButtons
import app.lyrify.settings.PlaybackSettings
import app.lyrify.settings.RememberPlace
import app.lyrify.settings.Settings
import app.lyrify.settings.ThemeChoice
import app.lyrify.update.Updates

/** Settings > Backup's actions; milestone B2 fills them ([rememberBackupActions] in BackupScreens.kt), lane IM1 Import from app. */
@Immutable
data class BackupActions(val backUpNow: () -> Unit, val restore: () -> Unit, val setAutomatic: (Boolean) -> Unit, val importFromApp: () -> Unit)

/** The phone's grants Settings shows, and where each is switched on. */
@Immutable
data class Grants(
    val access: Access,
    val unrestricted: Boolean,
    val onOpenNotificationAccess: () -> Unit,
    val onOpenAllFilesAccess: () -> Unit,
    val onOpenAppInfo: () -> Unit,
)

/** The sheet open over Settings. */
private enum class SettingsSheet { REMEMBER, PLAY_SHARE, SKIPS, LEVELLING, CROSSFADE, ANDROID_AUTO, NOTIFICATION_BUTTONS, BOOST, START_WHEN_CONNECTED, THEME, CRASHES }

/**
 * Tools > Settings: every option of 3.0 in its section (Playback, Library, Display, Lyrics, AI, Backup, About), engine detail
 * under a collapsed Advanced. Each row stores its value in [settings] through [onChange], also for features still to
 * come; the grants open the phone's own switches; AI lyrics keeps its own switch ([ai], [aiActions]).
 */
@Composable
fun SettingsScreen(
    settings: Settings,
    onChange: ((Settings) -> Settings) -> Unit,
    grants: Grants,
    ai: AiState,
    aiActions: AiActions,
    backup: BackupActions,
    versionName: String,
    onOpen: (Page) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var sheet by rememberSaveable { mutableStateOf<SettingsSheet?>(null) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    val playback = settings.playback
    val library = settings.library
    val context = LocalContext.current
    val connect = remember(context) { StartWhenConnected.supported(context) }
    fun setPlayback(change: (PlaybackSettings) -> PlaybackSettings) = onChange { it.copy(playback = change(it.playback)) }
    fun setAudio(change: (AudioSettings) -> AudioSettings) = onChange { it.copy(audio = change(it.audio)) }
    fun setAdvanced(change: (AdvancedSettings) -> AdvancedSettings) = onChange { it.copy(advanced = change(it.advanced)) }
    val showWhenLocked = rememberLockSwitch(settings.lyrics.showWhenLocked, onChange)
    val updates by remember(context) { Updates.checker(context).state }.collectAsStateWithLifecycle()
    val checkNow = rememberCheckNow()
    val tour = LocalTour.current

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            LyrifyTopBar(
                title = { Text(stringResource(R.string.settings_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigation = { ChromeIconButton(R.drawable.ic_arrow_back, stringResource(R.string.check_back), onBack) },
            )
        },
        // Messages dock at the bottom, above the gesture area.
        bottomBar = { ToastSlot(Modifier.navigationBarsPadding().padding(horizontal = ScreenPadding), padding = PaddingValues(bottom = 12.dp)) },
    ) { padding ->
        val reduced = LocalReducedMotion.current
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp)) {
            section(R.string.settings_playback, first = true) {
                ValueRow(R.drawable.ic_resume, R.string.set_remember, stringResource(playback.rememberPlace.label), first = true) { sheet = SettingsSheet.REMEMBER }
                SwitchRow(R.drawable.ic_headphones, R.string.set_headset, stringResource(R.string.set_headset_detail), playback.headsetButtons) { on -> setPlayback { it.copy(headsetButtons = on) } }
                if (connect) {
                    ValueRow(R.drawable.ic_bluetooth, R.string.set_autostart, connectNames(playback.connectDevices) ?: stringResource(R.string.set_autostart_off)) {
                        sheet = SettingsSheet.START_WHEN_CONNECTED
                    }
                }
                SwitchRow(R.drawable.ic_timer_off, R.string.set_clips, stringResource(R.string.set_clips_detail), playback.skipShortClips) { on -> setPlayback { it.copy(skipShortClips = on) } }
                ValueRow(R.drawable.ic_bar_chart, R.string.set_playpct, stringResource(R.string.set_playpct_value, playback.countPlayAfterPercent)) { sheet = SettingsSheet.PLAY_SHARE }
                ValueRow(R.drawable.ic_forward_10, R.string.set_skips, stringResource(R.string.set_skips_value, playback.skipBackSeconds, playback.skipForwardSeconds)) { sheet = SettingsSheet.SKIPS }
                ValueRow(R.drawable.ic_volume_up, R.string.set_levelling, stringResource(settings.audio.levelling.label)) { sheet = SettingsSheet.LEVELLING }
                ValueRow(R.drawable.ic_swap_horiz, R.string.set_crossfade, crossfadeText(settings.audio.crossfadeSeconds)) { sheet = SettingsSheet.CROSSFADE }
            }
            section(R.string.settings_library) {
                val shown = library.tabs.count { it.shown }
                ValueRow(R.drawable.ic_tab, R.string.set_tabs, pluralStringResource(R.plurals.set_tabs_value, shown, shown), first = true) { onOpen(Page.LibraryTabs) }
                val skipped = library.foldersToSkip.size
                ValueRow(R.drawable.ic_folder_off, R.string.set_skip, if (skipped > 0) pluralStringResource(R.plurals.set_skip_value, skipped, skipped) else stringResource(R.string.set_skip_none)) {
                    onOpen(Page.FoldersToSkip)
                }
                val hidden = library.hiddenSongs.size
                ValueRow(R.drawable.ic_visibility_off, R.string.set_hidden, if (hidden > 0) pluralStringResource(R.plurals.set_hidden_value, hidden, hidden) else stringResource(R.string.set_hidden_none)) {
                    onOpen(Page.HiddenSongs)
                }
                ValueRow(R.drawable.ic_person, R.string.set_artists, splitArtistsValue(library.artistSplit)) { onOpen(Page.SplitArtists) }
                SwitchRow(R.drawable.ic_description, R.string.set_filenames, stringResource(R.string.set_filenames_detail), library.showFileNames) { on ->
                    onChange { it.copy(library = it.library.copy(showFileNames = on)) }
                }
            }
            section(R.string.settings_display) {
                val display = settings.display
                ValueRow(R.drawable.ic_dark_mode, R.string.set_theme, stringResource(display.theme.label), first = true) { sheet = SettingsSheet.THEME }
                // Pure black darkens a dark theme only: with Light chosen it waits, switched off.
                val light = display.theme == ThemeChoice.LIGHT
                SwitchRow(
                    R.drawable.ic_contrast,
                    R.string.set_pure_black,
                    stringResource(if (light) R.string.set_pure_black_light else R.string.set_pure_black_detail),
                    display.pureBlack && !light,
                    enabled = !light,
                ) { on -> onChange { it.copy(display = it.display.copy(pureBlack = on)) } }
                // The bar the user makes (KJB 3.1): its look and how many icons it holds now.
                val icons = LocalDockBar.current.items.size
                ValueRow(R.drawable.ic_smartphone, R.string.set_bar, pluralStringResource(R.plurals.set_bar_value, icons, stringResource(display.dockStyle.label), icons)) { onOpen(Page.BottomBar) }
            }
            section(R.string.settings_lyrics) {
                GrantRow(R.drawable.ic_notifications, R.string.setup_notifications_title, R.string.set_notifications_detail, grants.access.notifications, R.string.setup_done, R.string.settings_turn_on, grants.onOpenNotificationAccess, first = true)
                GrantRow(R.drawable.ic_folder_open, R.string.setup_files_title, R.string.settings_files, grants.access.allFiles, R.string.setup_done, R.string.settings_turn_on, grants.onOpenAllFilesAccess)
                GrantRow(R.drawable.ic_battery_full, R.string.setup_battery_title, R.string.settings_battery, grants.unrestricted, R.string.settings_unrestricted, R.string.open_app_info, grants.onOpenAppInfo)
                val locked = settings.lyrics.showWhenLocked
                SwitchRow(R.drawable.ic_lock, R.string.set_lock, stringResource(if (locked) R.string.set_lock_on else R.string.set_lock_off), locked, onSwitch = showWhenLocked)
            }
            section(R.string.settings_ai) {
                SwitchRow(
                    R.drawable.ic_auto_awesome,
                    R.string.set_ai,
                    if (ai.supported) null else stringResource(if (ai.chip == null) R.string.tools_ai_no_chip else R.string.tools_ai_update),
                    ai.enabled,
                    enabled = ai.supported && !ai.stopping,
                    first = true,
                    onSwitch = aiActions.onSwitch,
                )
                if (ai.supported) ValueRow(R.drawable.ic_download, R.string.set_ai_download, aiDownloadText(ai.models)) { onOpen(Page.Ai) }
            }
            downloadsSettings()
            section(R.string.settings_backup) {
                ActionRow(R.drawable.ic_backup, R.string.set_backup_now, first = true, onClick = backup.backUpNow)
                ActionRow(R.drawable.ic_settings_backup_restore, R.string.set_restore, onClick = backup.restore)
                ValueRow(R.drawable.ic_download, R.string.import_row, stringResource(R.string.import_row_detail), onClick = backup.importFromApp)
                SwitchRow(R.drawable.ic_schedule, R.string.set_auto_backup, stringResource(R.string.set_auto_backup_detail), settings.backup.automatic, onSwitch = backup.setAutomatic)
            }
            section(R.string.settings_about) {
                // Show tour: Settings closes and the tour starts from its first step; setup and the grants stay as they are.
                if (tour != null) {
                    val brand = LocalBrandColors.current
                    GroupedRow(
                        R.drawable.ic_explore,
                        stringResource(R.string.tour_show),
                        tile = brand.highlightContainer,
                        onTile = brand.onHighlightContainer,
                        first = true,
                        supporting = { Text(stringResource(R.string.tour_show_line)) },
                        onClick = {
                            onBack()
                            tour.start()
                        },
                        trailing = { Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, modifier = Modifier.size(20.dp)) },
                    )
                }
                // The version beside the app's own mark, the Note arm K on black.
                GroupedRow(R.drawable.ic_brand_k, stringResource(R.string.version_label, versionName), tile = BrandBlack, onTile = Color.Unspecified, first = tour == null)
                // The update check and the crash reports (AboutSheets.kt): nothing leaves the phone but the hourly check's one request.
                ValueRow(R.drawable.ic_refresh, R.string.set_updates_now, updateLine(updates), onClick = checkNow)
                ValueRow(R.drawable.ic_error, R.string.set_crashes, crashLine(sheet)) { sheet = SettingsSheet.CRASHES }
                ActionRow(R.drawable.ic_lyrics, stringResource(R.string.settings_source))
                ActionRow(R.drawable.ic_directions_car, stringResource(R.string.set_auto)) { sheet = SettingsSheet.ANDROID_AUTO }
                ActionRow(R.drawable.ic_description, R.string.set_licences) { onOpen(Page.Licences) }
            }
            item(key = "advanced", contentType = "advanced") {
                GroupedRows(rowMotion(reduced).padding(top = 16.dp)) {
                    GroupedRow(
                        R.drawable.ic_tune,
                        stringResource(R.string.settings_advanced),
                        tile = MaterialTheme.colorScheme.surfaceContainerHighest,
                        onTile = MaterialTheme.colorScheme.onSurfaceVariant,
                        first = true,
                        modifier = Modifier.toggleable(advanced, role = Role.DropdownList) { advanced = it },
                        trailing = { Icon(painterResource(if (advanced) R.drawable.ic_expand_less else R.drawable.ic_expand_more), contentDescription = null, modifier = Modifier.size(20.dp)) },
                    )
                    if (advanced) {
                        val engine = settings.advanced
                        SwitchRow(R.drawable.ic_graphic_eq, R.string.set_gapless, stringResource(R.string.set_gapless_detail), engine.gaplessPlayback) { on -> setAdvanced { it.copy(gaplessPlayback = on) } }
                        SwitchRow(R.drawable.ic_battery_full, R.string.set_offload, stringResource(R.string.set_offload_detail), engine.audioOffload) { on -> setAdvanced { it.copy(audioOffload = on) } }
                        ValueRow(R.drawable.ic_notifications, R.string.set_nbtn, stringResource(engine.notificationButtons.label)) { sheet = SettingsSheet.NOTIFICATION_BUTTONS }
                        ValueRow(R.drawable.ic_volume_up, R.string.set_boost, stringResource(R.string.set_boost_value, engine.levellingBoostDb)) { sheet = SettingsSheet.BOOST }
                        SwitchRow(R.drawable.ic_album, R.string.set_albums_gapless, stringResource(R.string.set_albums_gapless_detail), engine.keepAlbumsGapless) { on ->
                            setAdvanced { it.copy(keepAlbumsGapless = on) }
                        }
                    }
                }
            }
        }
    }

    val close = { sheet = null }
    when (sheet) {
        SettingsSheet.REMEMBER -> ChoiceSheet(R.string.set_remember, R.string.set_remember_sheet, RememberPlace.entries, playback.rememberPlace, { it.label }, { it.detail }, close) { choice ->
            setPlayback { it.copy(rememberPlace = choice) }
        }
        SettingsSheet.PLAY_SHARE -> OptionsSheet(R.string.set_playpct, R.string.set_playpct_sheet, close) {
            Options(PlaybackSettings.PLAY_SHARES, playback.countPlayAfterPercent, { stringResource(R.string.set_playpct_option, it) }) { share -> setPlayback { it.copy(countPlayAfterPercent = share) } }
        }
        SettingsSheet.SKIPS -> OptionsSheet(R.string.set_skips, R.string.set_skips_sheet, close) {
            SectionTitle(stringResource(R.string.set_skips_back))
            Options(PlaybackSettings.SKIP_SECONDS, playback.skipBackSeconds, { stringResource(R.string.set_seconds, it) }) { seconds -> setPlayback { it.copy(skipBackSeconds = seconds) } }
            SectionTitle(stringResource(R.string.set_skips_forward))
            Options(PlaybackSettings.SKIP_SECONDS, playback.skipForwardSeconds, { stringResource(R.string.set_seconds, it) }) { seconds -> setPlayback { it.copy(skipForwardSeconds = seconds) } }
        }
        SettingsSheet.LEVELLING -> ChoiceSheet(R.string.set_levelling, R.string.set_levelling_sheet, Levelling.entries, settings.audio.levelling, { it.label }, { it.detail }, close) { choice ->
            setAudio { it.copy(levelling = choice) }
        }
        SettingsSheet.CROSSFADE -> OptionsSheet(R.string.set_crossfade, R.string.set_crossfade_sheet, close) {
            Options(AudioSettings.CROSSFADE_SECONDS.toList(), settings.audio.crossfadeSeconds, { crossfadeText(it) }) { seconds -> setAudio { it.copy(crossfadeSeconds = seconds) } }
        }
        SettingsSheet.NOTIFICATION_BUTTONS -> ChoiceSheet(
            R.string.set_nbtn,
            R.string.set_nbtn_sheet,
            NotificationButtons.entries,
            settings.advanced.notificationButtons,
            { it.label },
            { it.detail },
            close,
        ) { choice -> setAdvanced { it.copy(notificationButtons = choice) } }
        SettingsSheet.BOOST -> OptionsSheet(R.string.set_boost, R.string.set_boost_sheet, close) {
            Options(AdvancedSettings.LEVELLING_BOOST_DB.toList(), settings.advanced.levellingBoostDb, { stringResource(R.string.set_boost_value, it) }) { db -> setAdvanced { it.copy(levellingBoostDb = db) } }
        }
        SettingsSheet.THEME -> ChoiceSheet(R.string.set_theme, R.string.set_theme_sheet, ThemeChoice.entries, settings.display.theme, { it.label }, { it.detail }, close) { choice ->
            onChange { it.copy(display = it.display.copy(theme = choice)) }
        }
        SettingsSheet.ANDROID_AUTO -> HelpSheet(stringResource(R.string.set_auto), stringResource(R.string.set_auto_sheet), onDismiss = close)
        SettingsSheet.START_WHEN_CONNECTED -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) StartWhenConnectedSheet(playback.connectDevices, close)
        SettingsSheet.CRASHES -> CrashReportsSheet(close)
        null -> Unit
    }
}

/**
 * Settings > Library tabs: the tab Library opens on (KJB 3.1: the first in the order, or the one chosen by holding it in
 * Library, with a way back to the first), then every tab once, in its order, each with a switch and arrows to move it.
 * The last tab shown can't be switched off.
 */
@Composable
fun LibraryTabsScreen(settings: Settings, onChange: ((Settings) -> Settings) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val tabs = settings.library.tabs
    val shown = shownLibraryTabs(settings.library, rememberLibraryUi()?.library)
    val opensOn = libraryOpensOn(shown, settings.library.opensOn)
    val chosen = settings.library.opensOn in shown
    fun move(from: Int, to: Int) = onChange { current ->
        val list = current.library.tabs.toMutableList().apply { add(to, removeAt(from)) }
        current.copy(library = current.library.copy(tabs = list))
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            LyrifyTopBar(
                title = { Text(stringResource(R.string.set_tabs), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigation = { ChromeIconButton(R.drawable.ic_arrow_back, stringResource(R.string.check_back), onBack) },
            )
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp)) {
            item(contentType = "sentence") {
                Text(
                    stringResource(R.string.set_tabs_sheet),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = ScreenPadding + 4.dp, end = ScreenPadding, top = 4.dp, bottom = 12.dp),
                )
            }
            item(contentType = "opens") {
                val (tile, onTile) = neutralTile()
                GroupedRows(Modifier.padding(bottom = 16.dp)) {
                    GroupedRow(
                        R.drawable.ic_flag,
                        stringResource(R.string.set_tabs_opens, stringResource(opensOn.label)),
                        tile = tile,
                        onTile = onTile,
                        first = true,
                        supporting = { Text(stringResource(if (chosen) R.string.set_tabs_opens_chosen else R.string.set_tabs_opens_first)) },
                        trailing = if (chosen) {
                            {
                                LyrifyButton(stringResource(R.string.set_tabs_opens_reset), onClick = { onChange { it.copy(library = it.library.copy(opensOn = null)) } }, kind = ButtonKind.GHOST, compact = true)
                            }
                        } else {
                            null
                        },
                    )
                }
            }
            item(contentType = "group") {
                GroupedRows {
                    tabs.forEachIndexed { index, setting ->
                        val name = stringResource(setting.tab.label)
                        val last = setting.shown && tabs.count { it.shown } == 1
                        GroupedRow(
                            R.drawable.ic_tab,
                            name,
                            tile = MaterialTheme.colorScheme.surfaceContainerHighest,
                            onTile = MaterialTheme.colorScheme.onSurfaceVariant,
                            first = index == 0,
                            supporting = if (setting.tab == opensOn) ({ Text(stringResource(R.string.set_tabs_opens_here)) }) else null,
                            modifier = Modifier.toggleable(setting.shown, enabled = !last, role = Role.Switch) { on ->
                                onChange { current ->
                                    current.copy(library = current.library.copy(tabs = current.library.tabs.map { if (it.tab == setting.tab) it.copy(shown = on) else it }))
                                }
                            },
                            trailing = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    ChromeIconButton(R.drawable.ic_arrow_upward, stringResource(R.string.set_tab_up, name), { move(index, index - 1) }, enabled = index > 0, glass = false, size = 36.dp)
                                    ChromeIconButton(R.drawable.ic_arrow_downward, stringResource(R.string.set_tab_down, name), { move(index, index + 1) }, enabled = index < tabs.lastIndex, glass = false, size = 36.dp)
                                    LyrifySwitch(setting.shown, Modifier.padding(start = 4.dp), enabled = !last)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

// Rows

/** A section's title and its rows on one card. */
private fun androidx.compose.foundation.lazy.LazyListScope.section(@StringRes title: Int, first: Boolean = false, rows: @Composable () -> Unit) {
    item(key = title, contentType = "section") {
        Column(rowMotion(LocalReducedMotion.current)) {
            SectionTitle(stringResource(title), Modifier.padding(start = ScreenPadding + 4.dp, end = ScreenPadding, top = if (first) 4.dp else 18.dp, bottom = 8.dp))
            GroupedRows { rows() }
        }
    }
}

@Composable
private fun neutralTile(): Pair<Color, Color> = MaterialTheme.colorScheme.let { it.surfaceContainerHighest to it.onSurfaceVariant }

/** A row that opens a sheet or a page, with its value under its title. */
@Composable
private fun ValueRow(@DrawableRes icon: Int, @StringRes title: Int, value: String, first: Boolean = false, onClick: () -> Unit) {
    val (tile, onTile) = neutralTile()
    GroupedRow(
        icon,
        stringResource(title),
        tile = tile,
        onTile = onTile,
        first = first,
        supporting = { Text(value, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        onClick = onClick,
        trailing = { Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, modifier = Modifier.size(20.dp)) },
    )
}

/** A row with a switch: one TalkBack stop with its name, its line and its state. */
@Composable
internal fun SwitchRow(
    @DrawableRes icon: Int,
    @StringRes title: Int,
    detail: String?,
    checked: Boolean,
    enabled: Boolean = true,
    first: Boolean = false,
    onSwitch: (Boolean) -> Unit,
) {
    val (tile, onTile) = neutralTile()
    GroupedRow(
        icon,
        stringResource(title),
        tile = tile,
        onTile = onTile,
        first = first,
        modifier = Modifier.toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onSwitch),
        supporting = detail?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        trailing = { LyrifySwitch(checked, enabled = enabled) },
    )
}

/** A row that does one thing when tapped, or with no [onClick] only says something (the version). */
@Composable
private fun ActionRow(@DrawableRes icon: Int, text: String, first: Boolean = false, onClick: (() -> Unit)? = null) {
    val (tile, onTile) = neutralTile()
    GroupedRow(icon, text, tile = tile, onTile = onTile, first = first, onClick = onClick)
}

@Composable
private fun ActionRow(@DrawableRes icon: Int, @StringRes text: Int, first: Boolean = false, onClick: () -> Unit) = ActionRow(icon, stringResource(text), first, onClick)

/** A grant: its icon, title and setting, then [onLabel] with a check while it is on, else a button that opens it. */
@Composable
private fun GrantRow(
    @DrawableRes icon: Int,
    @StringRes title: Int,
    @StringRes setting: Int,
    on: Boolean,
    @StringRes onLabel: Int,
    @StringRes action: Int,
    onAction: () -> Unit,
    first: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val highlight = LocalBrandColors.current.highlight
    GroupedRow(
        icon,
        stringResource(title),
        tile = if (on) colors.surfaceContainerHighest else colors.errorContainer,
        onTile = if (on) colors.onSurfaceVariant else colors.onErrorContainer,
        first = first,
        supporting = { Text(stringResource(setting)) },
        trailing = {
            if (on) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = highlight, modifier = Modifier.size(18.dp))
                    Text(stringResource(onLabel), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = colors.onSurface)
                }
            } else {
                LyrifyButton(stringResource(action), onClick = onAction, kind = ButtonKind.GHOST, compact = true)
            }
        },
    )
}

// Sheets

/** A sheet with a few choices, each with a line under it; a choice closes the sheet. */
@Composable
private fun <T> ChoiceSheet(
    @StringRes title: Int,
    @StringRes sentence: Int,
    choices: List<T>,
    selected: T,
    label: (T) -> Int,
    detail: (T) -> Int,
    onDismiss: () -> Unit,
    onChoose: (T) -> Unit,
) {
    LyrifySheet(onDismiss = onDismiss, spacing = 8.dp) {
        SheetHead(title, sentence)
        Column(Modifier.selectableGroup()) {
            choices.forEach { choice ->
                ChoiceRow(stringResource(label(choice)), stringResource(detail(choice)), choice == selected) {
                    onChoose(choice)
                    onDismiss()
                }
            }
        }
    }
}

/** A sheet with option chips; it stays open to try them and closes with Done. */
@Composable
private fun OptionsSheet(@StringRes title: Int, @StringRes sentence: Int, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    LyrifySheet(
        onDismiss = onDismiss,
        footer = { LyrifyButton(stringResource(R.string.settings_done), onClick = onDismiss, kind = ButtonKind.SECONDARY, modifier = Modifier.fillMaxWidth()) },
    ) {
        SheetHead(title, sentence)
        content()
    }
}

@Composable
private fun SheetHead(@StringRes title: Int, @StringRes sentence: Int) {
    Text(stringResource(title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
    Text(stringResource(sentence), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Options(values: List<Int>, selected: Int, label: @Composable (Int) -> String, onPick: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth().selectableGroup()) {
        values.forEach { value -> LyrifyChip(selected = value == selected, onClick = { onPick(value) }, role = Role.RadioButton) { Text(label(value)) } }
    }
}

/** One choice: its label and line; the chosen one on the brand's container with a check. */
@Composable
internal fun ChoiceRow(label: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val brand = LocalBrandColors.current
    val content = if (selected) brand.onHighlightContainer else colors.onSurface
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) brand.highlightContainer else Color.Transparent)
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 60.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = content)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = if (selected) content else colors.onSurfaceVariant)
        }
        if (selected) Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
    }
}

// Values as words

@get:StringRes
private val ThemeChoice.label: Int
    get() = when (this) {
        ThemeChoice.SYSTEM -> R.string.set_theme_system
        ThemeChoice.LIGHT -> R.string.set_theme_light
        ThemeChoice.DARK -> R.string.set_theme_dark
    }

@get:StringRes
private val ThemeChoice.detail: Int
    get() = when (this) {
        ThemeChoice.SYSTEM -> R.string.set_theme_system_detail
        ThemeChoice.LIGHT -> R.string.set_theme_light_detail
        ThemeChoice.DARK -> R.string.set_theme_dark_detail
    }

private val RememberPlace.label: Int
    get() = when (this) {
        RememberPlace.LONG_SONGS -> R.string.set_remember_long
        RememberPlace.ALL_SONGS -> R.string.set_remember_all
        RememberPlace.OFF -> R.string.set_remember_off
    }

private val RememberPlace.detail: Int
    get() = when (this) {
        RememberPlace.LONG_SONGS -> R.string.set_remember_long_detail
        RememberPlace.ALL_SONGS -> R.string.set_remember_all_detail
        RememberPlace.OFF -> R.string.set_remember_off_detail
    }

private val Levelling.label: Int
    get() = when (this) {
        Levelling.OFF -> R.string.set_levelling_off
        Levelling.AUTO -> R.string.set_levelling_auto
        Levelling.SONG -> R.string.set_levelling_song
        Levelling.ALBUM -> R.string.set_levelling_album
    }

private val Levelling.detail: Int
    get() = when (this) {
        Levelling.OFF -> R.string.set_levelling_off_detail
        Levelling.AUTO -> R.string.set_levelling_auto_detail
        Levelling.SONG -> R.string.set_levelling_song_detail
        Levelling.ALBUM -> R.string.set_levelling_album_detail
    }

private val NotificationButtons.label: Int
    get() = when (this) {
        NotificationButtons.FAVOURITE -> R.string.set_nbtn_favourite
        NotificationButtons.SHUFFLE_REPEAT -> R.string.set_nbtn_shuffle
    }

private val NotificationButtons.detail: Int
    get() = when (this) {
        NotificationButtons.FAVOURITE -> R.string.set_nbtn_favourite_detail
        NotificationButtons.SHUFFLE_REPEAT -> R.string.set_nbtn_shuffle_detail
    }

/** A Library tab's name, for Settings and for the Library itself. */
@get:StringRes
val LibraryTab.label: Int
    get() = when (this) {
        LibraryTab.SONGS -> R.string.library_tab_songs
        LibraryTab.ALBUMS -> R.string.library_tab_albums
        LibraryTab.ARTISTS -> R.string.library_tab_artists
        LibraryTab.FOLDERS -> R.string.library_tab_folders
        LibraryTab.PLAYLISTS -> R.string.library_tab_playlists
        LibraryTab.GENRES -> R.string.library_tab_genres
        LibraryTab.BOOKS -> R.string.library_tab_books
    }

@Composable
private fun crossfadeText(seconds: Int): String = if (seconds == 0) stringResource(R.string.set_crossfade_off) else stringResource(R.string.set_seconds, seconds)

/** The AI download's size and state; null states read as not downloaded yet. */
@Composable
private fun aiDownloadText(models: ModelState?): String {
    val context = LocalContext.current
    return when (models) {
        is ModelState.Ready -> stringResource(R.string.ai_models_ready, Formatter.formatShortFileSize(context, models.totalBytes))
        is ModelState.Downloading -> stringResource(R.string.ai_downloading, Formatter.formatShortFileSize(context, models.doneBytes), Formatter.formatShortFileSize(context, models.totalBytes))
        else -> stringResource(R.string.set_ai_not_downloaded)
    }
}
