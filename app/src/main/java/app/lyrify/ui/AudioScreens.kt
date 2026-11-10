package app.lyrify.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lyrify.R
import app.lyrify.audio.BuiltInPreset
import app.lyrify.audio.EQ_BANDS_HZ
import app.lyrify.audio.EqProfile
import app.lyrify.audio.EqProfiles
import app.lyrify.audio.EqSound
import app.lyrify.audio.LiveEq
import app.lyrify.audio.OutputKey
import app.lyrify.audio.PresetChoice
import app.lyrify.audio.outputLabel
import app.lyrify.audio.readOutput
import app.lyrify.player.PlayerGraph
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

// The equalizer (A1): from the top, its switch with the output playing ("Now: Galaxy Buds"), the 10 bands, the presets
// and the user's own, Bass boost and Pre-amp, "More" with Balance and Mono, and the phone's own sound effects. It shows
// and changes the profile of the output playing (LiveEq), which the audio chain plays at its next buffer.

/** The equalizer, from the player's "⋯". */
@Composable
fun EqualizerView() {
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val resources = LocalResources.current
    val toasts = LocalToasts.current
    val scope = rememberCoroutineScope()
    val live = remember(context) { LiveEq.of(context.applicationContext) }
    val profiles by live.profiles.collectAsStateWithLifecycle()
    val output by live.output.collectAsStateWithLifecycle()
    // The output playing now, also when the player is not running to follow it.
    LaunchedEffect(live) { live.switchTo(readOutput(context.getSystemService(AudioManager::class.java))) }
    EqualizerScreen(
        state = profiles?.let { eqScreenState(it, output) },
        onAction = { action -> live.change { all, at -> all.after(at, action) } },
        onNameTaken = { scope.launch { toasts.show(Toast(resources.getString(R.string.eq_name_taken))) } },
        onPhoneEffects = {
            // The phone's own sound effects panel for KJB's player, the link that was in the player's "⋯".
            val session = effectsSession(PlayerGraph.audioSessionId)
            if (session == null) {
                scope.launch { toasts.show(Toast(resources.getString(R.string.eq_effects_wait))) }
            } else {
                val panel = Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL)
                    .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, session)
                    .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
                    .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
                try {
                    context.startActivity(panel)
                } catch (_: ActivityNotFoundException) {
                    scope.launch { toasts.show(Toast(resources.getString(R.string.player_no_effects))) }
                }
            }
        },
        onBack = { nav.close(FullScreen.Equalizer) },
    )
}

// What the screen shows and what its controls do: pure, tested on the JVM.

/** What the equalizer screen shows: the output playing, its profile, the presets to pick from, and the one in use. */
@Immutable
data class EqScreenState(
    val output: OutputKey,
    val profile: EqProfile,
    /** The 11 built-in presets in their order, then the user's own in the order saved. */
    val presets: List<PresetChoice>,
    /** The preset the bands come from, as listed in [presets]; null once a band was moved by hand. */
    val selected: PresetChoice?,
) {
    /** The user's own presets, by name. */
    val own: List<String> get() = presets.mapNotNull { (it as? PresetChoice.Own)?.name }

    /** Whether [name] is already one of the user's presets, other than [except]. */
    fun nameTaken(name: String, except: String? = null): Boolean =
        own.any { it.equals(name.trim(), ignoreCase = true) && !it.equals(except, ignoreCase = true) }
}

fun eqScreenState(profiles: EqProfiles, output: OutputKey): EqScreenState {
    val profile = profiles.profile(output)
    val presets = BuiltInPreset.entries.map(PresetChoice::BuiltIn) + profiles.presets.map { PresetChoice.Own(it.name) }
    val selected = when (val preset = profile.preset) {
        null, is PresetChoice.BuiltIn -> preset
        is PresetChoice.Own -> presets.firstOrNull { it is PresetChoice.Own && it.name.equals(preset.name, ignoreCase = true) }
    }
    return EqScreenState(output, profile, presets, selected)
}

/** What a control on the equalizer screen does. */
sealed interface EqAction {
    data class Switch(val on: Boolean) : EqAction

    data class Band(val index: Int, val db: Float) : EqAction

    data class UsePreset(val choice: PresetChoice) : EqAction

    /** "Save preset": the bands under [name], which the output then uses; the same name again replaces it. */
    data class SavePreset(val name: String) : EqAction

    data class RenamePreset(val from: String, val to: String) : EqAction

    data class DeletePreset(val name: String) : EqAction

    data class BassBoost(val db: Float) : EqAction

    data class Preamp(val db: Float) : EqAction

    /** -1 is left only, 1 right only. */
    data class Balance(val value: Float) : EqAction

    data class Mono(val on: Boolean) : EqAction
}

/**
 * The profiles after [action] on [output]'s profile: gains in half-dB steps and balance in 5% steps, each held to its
 * range. A band moved by hand leaves the profile with no preset; the other controls keep it. Presets are the user's for
 * every output: saving, renaming and deleting one never changes how an output sounds.
 */
fun EqProfiles.after(output: OutputKey, action: EqAction): EqProfiles {
    val profile = profile(output)
    val sound = profile.sound
    fun with(changed: EqSound, preset: PresetChoice? = profile.preset) = withProfile(output, EqProfile(changed, preset))
    return when (action) {
        is EqAction.Switch -> with(sound.copy(enabled = action.on))
        is EqAction.Band -> {
            if (action.index !in sound.bandsDb.indices) return this
            val bands = sound.bandsDb.toMutableList().also { it[action.index] = halfDb(action.db, -EqSound.BAND_DB, EqSound.BAND_DB) }
            with(sound.copy(bandsDb = bands), preset = null)
        }
        is EqAction.UsePreset -> usePreset(output, action.choice)
        is EqAction.SavePreset -> {
            val name = action.name.trim()
            if (name.isEmpty()) return this
            val saved = savePreset(name, sound.bandsDb)
            saved.withProfile(output, saved.profile(output).copy(preset = PresetChoice.Own(name)))
        }
        is EqAction.RenamePreset -> renamePreset(action.from, action.to)
        is EqAction.DeletePreset -> deletePreset(action.name)
        is EqAction.BassBoost -> with(sound.copy(bassBoostDb = halfDb(action.db, 0f, EqSound.BASS_BOOST_DB)))
        is EqAction.Preamp -> with(sound.copy(preampDb = halfDb(action.db, -EqSound.PREAMP_DB, EqSound.PREAMP_DB)))
        is EqAction.Balance -> with(sound.copy(balance = (action.value.coerceIn(-1f, 1f) * BALANCE_STEPS).roundToInt() / BALANCE_STEPS))
        is EqAction.Mono -> with(sound.copy(mono = action.on))
    }
}

private fun halfDb(db: Float, min: Float, max: Float): Float = ((db * 2).roundToInt() / 2f).coerceIn(min, max)

/** Balance moves in steps of 5%. */
private const val BALANCE_STEPS: Float = 20f

/** A gain as the screen writes it: "+3", "+3.5", "0", "−12" (a true minus, as KJB writes them). */
fun dbText(db: Float): String {
    val halves = (db * 2).roundToInt()
    if (halves == 0) return "0"
    val whole = abs(halves) / 2
    return (if (halves > 0) "+" else MINUS) + whole + (if (abs(halves) % 2 == 1) ".5" else "")
}

private const val MINUS: String = "−"

/**
 * The audio session the phone's sound effects panel opens for: the player's own; null before the player has one, as
 * session 0 would be every app's output.
 */
fun effectsSession(audioSessionId: Int): Int? = audioSessionId.takeIf { it > 0 }

// The screen

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EqualizerScreen(
    state: EqScreenState?,
    onAction: (EqAction) -> Unit,
    onNameTaken: () -> Unit,
    onPhoneEffects: () -> Unit,
    onBack: () -> Unit,
) {
    var more by rememberSaveable { mutableStateOf(false) }
    var saving by rememberSaveable { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            LyrifyTopBar(
                title = { Text(stringResource(R.string.eq_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigation = { ChromeIconButton(R.drawable.ic_arrow_back, stringResource(R.string.check_back), onBack) },
            )
        },
        bottomBar = { ToastSlot(Modifier.navigationBarsPadding().padding(horizontal = ScreenPadding), padding = PaddingValues(bottom = 12.dp)) },
    ) { padding ->
        // The profiles are read in a moment; until then the page stays empty.
        if (state == null) return@Scaffold
        val sound = state.profile.sound
        val on = sound.enabled
        val resources = LocalResources.current
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp)) {
            item(key = "switch") {
                Column(Modifier.padding(top = 8.dp)) {
                    GroupedRows {
                        SwitchRow(
                            R.drawable.ic_equalizer,
                            stringResource(R.string.eq_title),
                            stringResource(R.string.eq_now, outputLabel(state.output) { resources.getString(it) }),
                            on,
                            first = true,
                        ) { onAction(EqAction.Switch(it)) }
                    }
                    Text(
                        stringResource(R.string.eq_per_output),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = ScreenPadding + 4.dp, end = ScreenPadding, top = 12.dp),
                    )
                }
            }
            item(key = "bands") {
                Section(R.string.eq_bands) {
                    LyrifyCard(Modifier.fillMaxWidth().padding(horizontal = ScreenPadding)) {
                        Bands(sound.bandsDb, enabled = on) { index, db -> onAction(EqAction.Band(index, db)) }
                    }
                }
            }
            item(key = "presets") {
                Section(R.string.eq_presets) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = ScreenPadding).selectableGroup().alpha(if (on) 1f else OFF_ALPHA),
                    ) {
                        state.presets.filterIsInstance<PresetChoice.BuiltIn>().forEach { choice ->
                            LyrifyChip(
                                selected = choice == state.selected,
                                onClick = { if (on) onAction(EqAction.UsePreset(choice)) },
                                role = Role.RadioButton,
                                modifier = if (on) Modifier else Modifier.semantics { disabled() },
                            ) { Text(stringResource(choice.preset.label)) }
                        }
                    }
                }
            }
            item(key = "own") {
                Section(R.string.eq_your_presets) {
                    OwnPresets(
                        state,
                        enabled = on,
                        onUse = { onAction(EqAction.UsePreset(PresetChoice.Own(it))) },
                        onSave = { saving = true },
                        onRename = { renaming = it },
                        onDelete = { deleting = it },
                    )
                }
            }
            item(key = "levels") {
                LyrifyCard(Modifier.fillMaxWidth().padding(start = ScreenPadding, end = ScreenPadding, top = 24.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        LevelRow(stringResource(R.string.eq_bass_boost), dbLabel(sound.bassBoostDb), sound.bassBoostDb, 0f..EqSound.BASS_BOOST_DB, HALF_DB, on) {
                            onAction(EqAction.BassBoost(it))
                        }
                        LevelRow(stringResource(R.string.eq_preamp), dbLabel(sound.preampDb), sound.preampDb, -EqSound.PREAMP_DB..EqSound.PREAMP_DB, HALF_DB, on) {
                            onAction(EqAction.Preamp(it))
                        }
                    }
                }
            }
            item(key = "more") {
                Column(Modifier.padding(top = 24.dp)) {
                    GroupedRows {
                        GroupedRow(
                            R.drawable.ic_tune,
                            stringResource(R.string.eq_more),
                            tile = MaterialTheme.colorScheme.surfaceContainerHighest,
                            onTile = MaterialTheme.colorScheme.onSurfaceVariant,
                            first = true,
                            modifier = Modifier.toggleable(more, role = Role.DropdownList) { more = it },
                            trailing = { Icon(painterResource(if (more) R.drawable.ic_expand_less else R.drawable.ic_expand_more), contentDescription = null) },
                        )
                        if (more) {
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                LevelRow(stringResource(R.string.eq_balance), balanceLabel(sound.balance), sound.balance, -1f..1f, 1f / BALANCE_STEPS, on) {
                                    onAction(EqAction.Balance(it))
                                }
                            }
                            SwitchRow(R.drawable.ic_headphones, stringResource(R.string.eq_mono), stringResource(R.string.eq_mono_detail), sound.mono, enabled = on) {
                                onAction(EqAction.Mono(it))
                            }
                        }
                    }
                }
            }
            item(key = "phone") {
                GroupedRows(Modifier.padding(top = 24.dp)) {
                    GroupedRow(
                        R.drawable.ic_settings,
                        stringResource(R.string.eq_phone_effects),
                        tile = MaterialTheme.colorScheme.surfaceContainerHighest,
                        onTile = MaterialTheme.colorScheme.onSurfaceVariant,
                        first = true,
                        supporting = { Text(stringResource(R.string.eq_phone_effects_detail), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        onClick = onPhoneEffects,
                    )
                }
            }
        }
        if (saving) {
            NameDialog(stringResource(R.string.eq_save_preset), "", R.drawable.ic_save, onDismiss = { saving = false }, confirm = R.string.eq_save) { name ->
                saving = false
                onAction(EqAction.SavePreset(name))
            }
        }
        renaming?.let { old ->
            NameDialog(stringResource(R.string.eq_rename_preset), old, R.drawable.ic_edit, onDismiss = { renaming = null }, confirm = R.string.eq_rename) { name ->
                renaming = null
                if (state.nameTaken(name, except = old)) onNameTaken() else onAction(EqAction.RenamePreset(old, name))
            }
        }
        deleting?.let { name ->
            ConfirmDialog(
                title = stringResource(R.string.eq_delete_preset, name),
                confirm = stringResource(R.string.eq_delete),
                dismiss = stringResource(R.string.remove_cancel),
                text = stringResource(R.string.eq_delete_preset_text),
                onConfirm = {
                    deleting = null
                    onAction(EqAction.DeletePreset(name))
                },
                onDismiss = { deleting = null },
            )
        }
    }
}

/** A section's title over its content, as Settings shows them. */
@Composable
private fun Section(@StringRes title: Int, content: @Composable () -> Unit) {
    Column {
        SectionTitle(stringResource(title), Modifier.padding(start = ScreenPadding + 4.dp, end = ScreenPadding, top = 24.dp, bottom = 8.dp))
        content()
    }
}

/** A row with a switch: one TalkBack stop with its name, its line and its state. */
@Composable
private fun SwitchRow(@DrawableRes icon: Int, title: String, detail: String, checked: Boolean, enabled: Boolean = true, first: Boolean = false, onSwitch: (Boolean) -> Unit) {
    GroupedRow(
        icon,
        title,
        tile = MaterialTheme.colorScheme.surfaceContainerHighest,
        onTile = MaterialTheme.colorScheme.onSurfaceVariant,
        first = first,
        modifier = Modifier.toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onSwitch),
        supporting = { Text(detail, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailing = { LyrifySwitch(checked, enabled = enabled) },
    )
}

/** The user's presets, each used by a tap, renamed or deleted from its "⋯"; then "Save preset" for the bands as they are. */
@Composable
private fun OwnPresets(state: EqScreenState, enabled: Boolean, onUse: (String) -> Unit, onSave: () -> Unit, onRename: (String) -> Unit, onDelete: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val highlight = LocalBrandColors.current.highlight
    var menuFor by remember { mutableStateOf<String?>(null) }
    GroupedRows(Modifier.selectableGroup()) {
        state.own.forEachIndexed { index, name ->
            val selected = state.selected == PresetChoice.Own(name)
            GroupedRow(
                R.drawable.ic_tune,
                name,
                tile = colors.surfaceContainerHighest,
                onTile = colors.onSurfaceVariant,
                first = index == 0,
                modifier = Modifier.selectable(selected, enabled = enabled, role = Role.RadioButton) { onUse(name) }.alpha(if (enabled) 1f else OFF_ALPHA),
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (selected) Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = highlight, modifier = Modifier.size(20.dp))
                        Box {
                            ChromeIconButton(R.drawable.ic_more_horiz, stringResource(R.string.eq_preset_more, name), { menuFor = name }, glass = false)
                            LyrifyMenu(expanded = menuFor == name, onDismiss = { menuFor = null }) {
                                MenuRow(stringResource(R.string.eq_rename), R.drawable.ic_edit, {
                                    menuFor = null
                                    onRename(name)
                                })
                                MenuRow(stringResource(R.string.eq_delete), R.drawable.ic_delete, {
                                    menuFor = null
                                    onDelete(name)
                                }, destructive = true)
                            }
                        }
                    }
                },
            )
        }
        GroupedRow(
            R.drawable.ic_add,
            stringResource(R.string.eq_save_preset),
            tile = colors.surfaceContainerHighest,
            onTile = colors.onSurfaceVariant,
            first = state.own.isEmpty(),
            modifier = Modifier.alpha(if (enabled) 1f else OFF_ALPHA).then(if (enabled) Modifier else Modifier.semantics { disabled() }),
            onClick = onSave.takeIf { enabled },
        )
    }
}

/** The 10 bands side by side, each its gain over its slider and its frequency under it. */
@Composable
private fun Bands(bandsDb: List<Float>, enabled: Boolean, onBand: (Int, Float) -> Unit) {
    val resources = LocalResources.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 12.dp).alpha(if (enabled) 1f else OFF_ALPHA)) {
        EQ_BANDS_HZ.forEachIndexed { index, hz ->
            val db = bandsDb[index]
            val name = if (hz < 1_000) resources.getString(R.string.eq_band_hz, hz) else resources.getString(R.string.eq_band_khz, hz / 1_000)
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                Text(dbText(db), style = MaterialTheme.typography.labelSmall.tabular(), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                LevelSlider(
                    value = db,
                    range = -EqSound.BAND_DB..EqSound.BAND_DB,
                    step = HALF_DB,
                    label = name,
                    valueText = dbLabel(db),
                    enabled = enabled,
                    vertical = true,
                    onChange = { onBand(index, it) },
                    // Room for the knob at either end, clear of the gain above and the frequency below; a drag that starts
                    // between two sliders, or on a label, scrolls the page.
                    modifier = Modifier.padding(vertical = 10.dp).height(BAND_HEIGHT).width(BAND_TOUCH),
                )
                Text(
                    if (hz < 1_000) hz.toString() else "${hz / 1_000}k",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
    }
}

/** A level's name and value over its slider: Bass boost, Pre-amp, Balance. */
@Composable
private fun LevelRow(title: String, valueText: String, value: Float, range: ClosedFloatingPointRange<Float>, step: Float, enabled: Boolean, onChange: (Float) -> Unit) {
    Column(Modifier.alpha(if (enabled) 1f else OFF_ALPHA)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(valueText, style = MaterialTheme.typography.bodyMedium.tabular(), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LevelSlider(value, range, step, title, valueText, enabled, vertical = false, onChange = onChange, modifier = Modifier.fillMaxWidth().height(TOUCH))
    }
}

/**
 * A level on a 6 dp track in a touch area of at least 48 dp, filled from 0 (the middle of a range either side of it). A
 * horizontal one is set by a tap or a drag where the finger is; a vertical one (a band) moves by as much as the finger
 * does, so a touch meant to scroll never sets a band. Each change is handed on at once ([onChange], the screen rounds it
 * to [step]s), so the music follows the finger. TalkBack reads [label] and [valueText] and can set it.
 */
@Composable
private fun LevelSlider(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    label: String,
    valueText: String,
    enabled: Boolean,
    vertical: Boolean,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val highlight = LocalBrandColors.current.highlight
    val latest by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    var dragging by remember { mutableStateOf(false) }
    val span = range.endInclusive - range.start
    fun share(of: Float) = ((of - range.start) / span).coerceIn(0f, 1f)
    val at = share(value)
    val zero = share(0f)
    val gestures = when {
        !enabled -> Modifier
        vertical -> Modifier.pointerInput(range) {
            var moved = 0f
            detectVerticalDragGestures(
                onDragStart = {
                    moved = latest
                    dragging = true
                },
                onDragEnd = { dragging = false },
                onDragCancel = { dragging = false },
            ) { _, dy ->
                moved = (moved - dy / size.height * span).coerceIn(range)
                change(moved)
            }
        }
        else -> Modifier
            .pointerInput(range) { detectTapGestures { offset -> change(range.start + span * (offset.x / size.width).coerceIn(0f, 1f)) } }
            .pointerInput(range) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        dragging = true
                        change(range.start + span * (offset.x / size.width).coerceIn(0f, 1f))
                    },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                ) { pointer, _ -> change(range.start + span * (pointer.position.x / size.width).coerceIn(0f, 1f)) }
            }
    }
    BoxWithConstraints(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .semantics {
                contentDescription = label
                stateDescription = valueText
                progressBarRangeInfo = ProgressBarRangeInfo(value, range, steps = (span / step).roundToInt() - 1)
                if (enabled) {
                    setProgress { target ->
                        change(target.coerceIn(range))
                        true
                    }
                } else {
                    disabled()
                }
            }
            .then(gestures),
    ) {
        val knob = if (dragging) 20.dp else 16.dp
        val low = minOf(at, zero)
        val high = maxOf(at, zero)
        if (vertical) {
            Box(Modifier.fillMaxHeight().width(TRACK).clip(CircleShape).background(highlight.copy(alpha = TRACK_ALPHA)))
            Box(Modifier.align(Alignment.TopCenter).offset(y = maxHeight * (1f - high)).height(maxHeight * (high - low)).width(TRACK).clip(CircleShape).background(highlight))
            Box(Modifier.align(Alignment.TopCenter).offset(y = maxHeight * (1f - at) - knob / 2).size(knob).clip(CircleShape).background(highlight))
        } else {
            Box(Modifier.fillMaxWidth().height(TRACK).clip(CircleShape).background(highlight.copy(alpha = TRACK_ALPHA)))
            Box(Modifier.align(Alignment.CenterStart).offset(x = maxWidth * low).width(maxWidth * (high - low)).height(TRACK).clip(CircleShape).background(highlight))
            Box(Modifier.align(Alignment.CenterStart).offset(x = maxWidth * at - knob / 2).size(knob).clip(CircleShape).background(highlight))
        }
    }
}

@Composable
private fun dbLabel(db: Float): String = stringResource(R.string.eq_db, dbText(db))

@Composable
private fun balanceLabel(balance: Float): String {
    val percent = (abs(balance) * 100).roundToInt()
    return when {
        percent == 0 -> stringResource(R.string.eq_balance_centre)
        balance < 0 -> stringResource(R.string.eq_balance_left, percent)
        else -> stringResource(R.string.eq_balance_right, percent)
    }
}

/** A built-in preset's name. */
@get:StringRes
private val BuiltInPreset.label: Int
    get() = when (this) {
        BuiltInPreset.FLAT -> R.string.eq_preset_flat
        BuiltInPreset.BASS -> R.string.eq_preset_bass
        BuiltInPreset.BASS_AND_TREBLE -> R.string.eq_preset_bass_treble
        BuiltInPreset.VOCAL -> R.string.eq_preset_vocal
        BuiltInPreset.ROCK -> R.string.eq_preset_rock
        BuiltInPreset.POP -> R.string.eq_preset_pop
        BuiltInPreset.CLASSICAL -> R.string.eq_preset_classical
        BuiltInPreset.JAZZ -> R.string.eq_preset_jazz
        BuiltInPreset.ELECTRONIC -> R.string.eq_preset_electronic
        BuiltInPreset.ACOUSTIC -> R.string.eq_preset_acoustic
        BuiltInPreset.SMALL_SPEAKERS -> R.string.eq_preset_small_speakers
    }

private const val HALF_DB: Float = 0.5f

/** The controls of an equalizer switched off: shown faded, not changed. */
private const val OFF_ALPHA: Float = 0.38f
private const val TRACK_ALPHA: Float = 0.16f
private val TRACK = 6.dp
private val BAND_HEIGHT = 176.dp

/** A band slider's touch width, narrower than its column so the gaps between sliders scroll. */
private val BAND_TOUCH = 28.dp
