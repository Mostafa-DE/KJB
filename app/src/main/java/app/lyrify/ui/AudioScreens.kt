package app.lyrify.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Resources
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lyrify.R
import app.lyrify.audio.BuiltInPreset
import app.lyrify.audio.EQ_BANDS_HZ
import app.lyrify.audio.EqProfile
import app.lyrify.audio.EqProfiles
import app.lyrify.audio.EqSound
import app.lyrify.audio.LiveEq
import app.lyrify.audio.OutputKey
import app.lyrify.audio.OutputKind
import app.lyrify.audio.PresetChoice
import app.lyrify.audio.outputLabel
import app.lyrify.audio.readOutput
import app.lyrify.player.PlayerGraph
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

// The equalizer (A1), as the prototype's equalizer page (D1): its switch in the top bar, then the output playing ("Now:
// Galaxy Buds", which opens the Outputs sheet), the presets as one row of chips, the 10 bands over their response curve,
// Bass boost and Pre-amp, "More" with Balance and Mono, and the phone's own sound effects. It shows and changes the
// profile of the output playing (LiveEq), which the audio chain plays at its next buffer.

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
    /** What the Outputs sheet lists ([eqOutputRows]). */
    val outputs: List<EqOutputRow>,
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
    return EqScreenState(output, profile, presets, selected, eqOutputRows(profiles, output))
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

// The look (D1, the prototype's equalizer page): the chips, the Outputs sheet, the bands panel and its curve. Pure too.

/** A chip of the presets row: "Custom" while the bands were moved by hand, a preset, or "Save preset". */
sealed interface EqChip {
    data object Custom : EqChip

    data class Preset(val choice: PresetChoice) : EqChip

    data object Save : EqChip
}

/** The presets row, in its order: Custom (only while no preset is in use), the 11 built-in presets, the user's own, Save preset. */
fun eqChips(state: EqScreenState): List<EqChip> =
    listOfNotNull<EqChip>(EqChip.Custom.takeIf { state.selected == null }) + state.presets.map(EqChip::Preset) + EqChip.Save

/** Where the selected chip is in [eqChips]: the preset in use, else Custom. */
fun selectedChip(state: EqScreenState): Int = eqChips(state).indexOf(state.selected?.let(EqChip::Preset) ?: EqChip.Custom)

/**
 * How far to scroll a row so the item from [start] to [end] shows whole, [margin] in from the visible part's edges
 * ([viewStart] to [viewEnd]): as little as it takes, its start first when it is too wide. 0 when it already shows.
 */
fun intoView(start: Int, end: Int, viewStart: Int, viewEnd: Int, margin: Int): Int = when {
    start - margin < viewStart -> start - margin - viewStart
    end + margin > viewEnd -> end + margin - viewEnd
    else -> 0
}

/** A row of the Outputs sheet: an output, its profile (null: never used yet), and whether music plays on it now. */
@Immutable
data class EqOutputRow(val output: OutputKey, val profile: EqProfile?, val playing: Boolean)

/**
 * The Outputs sheet: the phone's speaker, every output with a profile and the one playing, by kind then by name. The one
 * playing shows the profile the screen shows, a new one (off) until it is changed.
 */
fun eqOutputRows(profiles: EqProfiles, playing: OutputKey): List<EqOutputRow> =
    (profiles.profiles.keys + OutputKey.SPEAKER + playing)
        .sortedWith(compareBy<OutputKey> { it.kind.ordinal }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name.orEmpty() })
        .map { output -> EqOutputRow(output, profiles.profiles[output] ?: EqProfile().takeIf { output == playing }, output == playing) }

/** "More"'s line while it is closed: the balance ([balance] writes it) and [mono], those not as new; null when both are. */
fun moreLine(sound: EqSound, balance: (Float) -> String, mono: String): String? =
    listOfNotNull(sound.balance.takeIf { it != 0f }?.let(balance), mono.takeIf { sound.mono }).joinToString(" · ").ifEmpty { null }

/** Bass boost as the screen writes it: its share of the most it can add, in percent. */
fun bassPercent(db: Float): Int = (db / EqSound.BASS_BOOST_DB * 100).roundToInt()

/** Balance under a finger: the step either side of centre lands on centre (the prototype's detent). */
fun balanceHeld(value: Float): Float = if (abs(value) < BALANCE_DETENT) 0f else value

private const val BALANCE_DETENT: Float = 1.5f / BALANCE_STEPS

/**
 * The bands panel's plot (.eqplot), [width] wide, in px where [unit] is one dp: 252 dp tall, +12 dB 44 dp under its top
 * (room for a knob's gain above it), then 1 dB every 8 dp down to −12 dB. Each band is the middle of its tenth.
 */
@Immutable
data class EqPlot(val width: Float, val unit: Float = 1f) {
    val height: Float get() = PLOT_DP * unit

    /** Where +12 dB is. */
    val top: Float get() = PLOT_TOP_DP * unit

    /** From +12 dB down to −12 dB. */
    val track: Float get() = TRACK_DP * unit

    fun x(band: Int, bands: Int = EQ_BANDS_HZ.size): Float = (band + 0.5f) * width / bands

    fun y(db: Float): Float = top + (EqSound.BAND_DB - db) / (2 * EqSound.BAND_DB) * track

    /** The gain at the height [y], held to ±12 dB. */
    fun db(y: Float): Float = (EqSound.BAND_DB - (y - top) / track * 2 * EqSound.BAND_DB).coerceIn(-EqSound.BAND_DB, EqSound.BAND_DB)

    /** The middles of the scale's labels: "dB", then +12, 0 and −12 level with their gains. */
    val scale: List<Float> get() = listOf(UNIT_LABEL_DP * unit, y(EqSound.BAND_DB), y(0f), y(-EqSound.BAND_DB))
}

/** A cubic from the point before it, pulled toward [c1] then [c2], to [to]. */
@Immutable
data class CurveStep(val c1: Offset, val c2: Offset, val to: Offset)

/** The response curve: from [start] (the plot's left edge) straight to the first of the [knots], [steps] through the rest, straight on to [end]. */
@Immutable
data class EqCurve(val start: Offset, val knots: List<Offset>, val steps: List<CurveStep>, val end: Offset)

/**
 * The curve through the knobs' centres of [bandsDb] in [plot], level from the plot's edges to the first and the last:
 * monotone cubics (Fritsch and Carlson, the prototype's eqPath), so it never bulges past a knob.
 */
fun eqCurve(bandsDb: List<Float>, plot: EqPlot): EqCurve {
    val n = bandsDb.size
    val h = plot.width / n
    val knots = bandsDb.mapIndexed { i, db -> Offset(plot.x(i, n), plot.y(db)) }
    val slopes = List(n - 1) { (knots[it + 1].y - knots[it].y) / h }
    val tangents = FloatArray(n) { i ->
        when {
            n == 1 -> 0f
            i == 0 -> slopes[0]
            i == n - 1 -> slopes[n - 2]
            slopes[i - 1] * slopes[i] <= 0f -> 0f
            else -> (slopes[i - 1] + slopes[i]) / 2
        }
    }
    slopes.forEachIndexed { i, d ->
        if (d == 0f) {
            tangents[i] = 0f
            tangents[i + 1] = 0f
        } else {
            val a = tangents[i] / d
            val b = tangents[i + 1] / d
            val s = a * a + b * b
            if (s > 9f) {
                val t = 3f / sqrt(s)
                tangents[i] = t * a * d
                tangents[i + 1] = t * b * d
            }
        }
    }
    val steps = List(n - 1) { i ->
        CurveStep(
            Offset(knots[i].x + h / 3, knots[i].y + tangents[i] * h / 3),
            Offset(knots[i + 1].x - h / 3, knots[i + 1].y - tangents[i + 1] * h / 3),
            knots[i + 1],
        )
    }
    return EqCurve(Offset(0f, knots.first().y), knots, steps, Offset(plot.width, knots.last().y))
}

/** How the knobs and the curve reach new gains: a 240 ms ease-out glide (a preset picked); at once under a finger or with reduced motion. */
fun glideSpec(dragging: Boolean, reduced: Boolean): AnimationSpec<Float> = if (dragging || reduced) snap() else tween(GLIDE_MS, easing = Motion.Glide)

private const val GLIDE_MS = 240
private const val PLOT_DP = 252f
private const val PLOT_TOP_DP = 44f
private const val TRACK_DP = 192f
private const val UNIT_LABEL_DP = 16f

// The screen

@Composable
private fun EqualizerScreen(
    state: EqScreenState?,
    onAction: (EqAction) -> Unit,
    onNameTaken: () -> Unit,
    onPhoneEffects: () -> Unit,
    onBack: () -> Unit,
) {
    var outputs by rememberSaveable { mutableStateOf(false) }
    var saving by rememberSaveable { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    val reduced = LocalReducedMotion.current
    PageFrame(
        topBar = { under, backdrop ->
            LyrifyTopBar(
                title = { Text(stringResource(R.string.eq_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                under = under,
                backdrop = backdrop,
                navigation = { ChromeIconButton(R.drawable.ic_arrow_back, stringResource(R.string.check_back), onBack) },
                actions = {
                    // The switch shows once the profiles are read, as the page does.
                    AnimatedVisibility(state != null, enter = fadeIn(motion(tween(Motion.QUICK_MS), reduced)), exit = fadeOut(motion(tween(Motion.QUICK_MS), reduced))) {
                        EqSwitch(state?.profile?.sound?.enabled == true) { onAction(EqAction.Switch(it)) }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            if (state != null) {
                EqualizerPage(
                    state,
                    top = padding.calculateTopPadding(),
                    onAction = onAction,
                    onOutputs = { outputs = true },
                    onSave = { saving = true },
                    onRename = { renaming = it },
                    onDelete = { deleting = it },
                    onPhoneEffects = onPhoneEffects,
                )
            }
            ToastSlot(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = ScreenPadding), padding = PaddingValues(bottom = 12.dp))
        }
    }
    // The profiles are read in a moment; until then the page stays empty.
    if (state == null) return
    if (outputs) OutputsSheet(state.outputs, onDismiss = { outputs = false })
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

/** The page under the bar, blocks 12 dp apart: the output playing, then the body the switch turns on, then the phone's own effects. */
@Composable
private fun EqualizerPage(
    state: EqScreenState,
    top: Dp,
    onAction: (EqAction) -> Unit,
    onOutputs: () -> Unit,
    onSave: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: (String) -> Unit,
    onPhoneEffects: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val resources = LocalResources.current
    val sound = state.profile.sound
    val on = sound.enabled
    // Off, the whole body fades to 45% as one, and none of it takes a touch (the prototype's .eqbody.off, inert).
    val body by animateFloatAsState(if (on) 1f else OFF_ALPHA, motion(tween(Motion.SETTLE_MS), LocalReducedMotion.current), label = "eq body")
    Column(
        verticalArrangement = Arrangement.spacedBy(BLOCK_GAP),
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = top).navigationBarsPadding().padding(bottom = 24.dp),
    ) {
        GroupedRows {
            GroupedRow(
                state.output.icon,
                stringResource(R.string.eq_now, outputLabel(state.output) { resources.getString(it) }),
                tile = colors.surfaceContainerHighest,
                onTile = colors.onSurfaceVariant,
                first = true,
                onClick = onOutputs,
                trailing = { Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, modifier = Modifier.size(20.dp)) },
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(BLOCK_GAP), modifier = Modifier.graphicsLayer { alpha = body }) {
            PresetChips(state, enabled = on, onPick = { onAction(EqAction.UsePreset(it)) }, onSave = onSave, onRename = onRename, onDelete = onDelete)
            BandsPanel(sound.bandsDb, enabled = on) { index, db -> onAction(EqAction.Band(index, db)) }
            GroupedRows {
                LevelRow(
                    R.drawable.ic_speaker,
                    stringResource(R.string.eq_bass_boost),
                    stringResource(R.string.eq_percent, bassPercent(sound.bassBoostDb)),
                    sound.bassBoostDb,
                    0f..EqSound.BASS_BOOST_DB,
                    HALF_DB,
                    ends = listOf(stringResource(R.string.eq_percent, 0), stringResource(R.string.eq_percent, 100)),
                    enabled = on,
                    first = true,
                ) { onAction(EqAction.BassBoost(it)) }
                LevelRow(
                    R.drawable.ic_volume_up,
                    stringResource(R.string.eq_preamp),
                    dbLabel(sound.preampDb),
                    sound.preampDb,
                    -EqSound.PREAMP_DB..EqSound.PREAMP_DB,
                    HALF_DB,
                    ends = listOf(dbText(-EqSound.PREAMP_DB), dbText(0f), dbText(EqSound.PREAMP_DB)),
                    enabled = on,
                ) { onAction(EqAction.Preamp(it)) }
            }
            MoreGroup(sound, enabled = on, onAction = onAction)
        }
        GroupedRows {
            GroupedRow(
                R.drawable.ic_graphic_eq,
                stringResource(R.string.eq_phone_effects),
                tile = colors.surfaceContainerHighest,
                onTile = colors.onSurfaceVariant,
                first = true,
                onClick = onPhoneEffects,
                trailing = { Icon(painterResource(R.drawable.ic_open_in_new), contentDescription = null, modifier = Modifier.size(20.dp)) },
            )
        }
    }
}

/** The bar's switch (.eqsw): the 52 by 32 dp switch in a 48 dp tap, its knob widening while pressed. */
@Composable
private fun EqSwitch(on: Boolean, onSwitch: (Boolean) -> Unit) {
    val name = stringResource(R.string.eq_title)
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .toggleable(on, interactionSource = source, indication = null, role = Role.Switch, onValueChange = onSwitch)
            .semantics { contentDescription = name }
            .height(TOUCH)
            .padding(horizontal = 8.dp),
    ) {
        LyrifySwitch(on, pressed = pressed)
    }
}

// The presets row

/**
 * The presets as one row of chips that scrolls sideways from edge to edge: Custom while the bands were moved by hand,
 * the built-in ones, the user's own (the chosen one with its "⋯": rename, delete) and Save preset. The chosen chip
 * scrolls into view when the page opens, the output changes or another is chosen.
 */
@Composable
private fun PresetChips(
    state: EqScreenState,
    enabled: Boolean,
    onPick: (PresetChoice) -> Unit,
    onSave: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    val reduced = LocalReducedMotion.current
    val chips = eqChips(state)
    val selected = selectedChip(state)
    val list = rememberLazyListState()
    val margin = with(LocalDensity.current) { ScreenPadding.roundToPx() }
    val shown = remember { mutableStateOf(false) }
    LaunchedEffect(state.output, chips.getOrNull(selected)) {
        if (selected < 0) return@LaunchedEffect
        snapshotFlow { list.layoutInfo.totalItemsCount }.first { it > selected }
        // At once when the page opens; a glide after that.
        val glide = shown.value && !reduced
        shown.value = true
        val visible = list.layoutInfo.visibleItemsInfo
        // Out of sight, it is brought to the row's start; one that was past the end then backs off to end at the end.
        val past = visible.none { it.index == selected } && selected > (visible.lastOrNull()?.index ?: -1)
        if (visible.none { it.index == selected }) {
            if (glide) list.animateScrollToItem(selected) else list.scrollToItem(selected)
        }
        val info = list.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == selected } ?: return@LaunchedEffect
        val end = item.offset + item.size + margin - info.viewportEndOffset
        val by = (if (past) end.coerceAtMost(0) else intoView(item.offset, item.offset + item.size, info.viewportStartOffset, info.viewportEndOffset, margin)).toFloat()
        if (by != 0f) {
            if (glide) list.animateScrollBy(by, tween(Motion.SETTLE_MS, easing = Motion.Standard)) else list.scrollBy(by)
        }
    }
    LazyRow(
        state = list,
        contentPadding = PaddingValues(horizontal = ScreenPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        // The chips' 48 dp taps reach 4 dp into the gaps above and below the row (the prototype's .eqchips margin).
        modifier = Modifier.fillMaxWidth().overhang(top = 4.dp, bottom = 4.dp).height(TOUCH).selectableGroup(),
    ) {
        items(
            chips,
            key = ::chipKey,
            contentType = { chip ->
                when (chip) {
                    EqChip.Save -> "save"
                    is EqChip.Preset -> if (chip.choice is PresetChoice.Own) "own" else "preset"
                    EqChip.Custom -> "preset"
                }
            },
        ) { chip ->
            val item = rowMotion(reduced)
            when (chip) {
                EqChip.Custom -> PresetChip(stringResource(R.string.eq_custom), selected = true, enabled, item) {}
                EqChip.Save -> LyrifyChip(
                    selected = false,
                    onClick = { if (enabled) onSave() },
                    role = Role.Button,
                    modifier = item.inert(!enabled),
                    icon = R.drawable.ic_add,
                    iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                ) { Text(stringResource(R.string.eq_save_preset), maxLines = 1) }
                is EqChip.Preset -> when (val choice = chip.choice) {
                    is PresetChoice.BuiltIn -> PresetChip(stringResource(choice.preset.label), choice == state.selected, enabled, item) { onPick(choice) }
                    is PresetChoice.Own -> OwnChip(
                        choice.name,
                        selected = choice == state.selected,
                        enabled = enabled,
                        modifier = item,
                        onPick = { onPick(choice) },
                        onRename = { onRename(choice.name) },
                        onDelete = { onDelete(choice.name) },
                    )
                }
            }
        }
    }
}

private fun chipKey(chip: EqChip): String = when (chip) {
    EqChip.Custom -> "custom"
    EqChip.Save -> "save"
    is EqChip.Preset -> when (val choice = chip.choice) {
        is PresetChoice.BuiltIn -> "built-in:${choice.preset.name}"
        is PresetChoice.Own -> "own:${choice.name}"
    }
}

/** A preset's chip: one choice of the row. */
@Composable
private fun PresetChip(name: String, selected: Boolean, enabled: Boolean, modifier: Modifier, onPick: () -> Unit) {
    LyrifyChip(selected, onClick = { if (enabled) onPick() }, role = Role.RadioButton, modifier = modifier.inert(!enabled)) { Text(name, maxLines = 1) }
}

/** One of the user's presets: chosen, it makes room at its end for its "⋯" (rename, delete), which fades in there. */
@Composable
private fun OwnChip(name: String, selected: Boolean, enabled: Boolean, modifier: Modifier, onPick: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    val brand = LocalBrandColors.current
    val reduced = LocalReducedMotion.current
    val room by animateDpAsState(if (selected) DOTS_ROOM else 0.dp, motion(tween(Motion.QUICK_MS), reduced), label = "dots room")
    val moreFor = stringResource(R.string.eq_preset_more, name)
    var menu by remember { mutableStateOf(false) }
    Box(contentAlignment = Alignment.CenterEnd, modifier = modifier) {
        LyrifyChip(selected, onClick = { if (enabled) onPick() }, role = Role.RadioButton, modifier = Modifier.inert(!enabled)) {
            Text(name, maxLines = 1, modifier = Modifier.padding(end = room))
        }
        AnimatedVisibility(
            selected,
            enter = fadeIn(motion(tween(Motion.QUICK_MS), reduced)) + scaleIn(motion(tween(Motion.QUICK_MS), reduced), initialScale = DOTS_SCALE),
            exit = fadeOut(motion(tween(Motion.QUICK_MS), reduced)) + scaleOut(motion(tween(Motion.QUICK_MS), reduced), targetScale = DOTS_SCALE),
        ) {
            val source = remember { MutableInteractionSource() }
            // A 32 dp circle 4 dp in from the chip's end, in a tap the chip's full height.
            Box(
                contentAlignment = Alignment.CenterStart,
                modifier = Modifier
                    .size(DOTS_TOUCH, TOUCH)
                    .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button) { menu = true }
                    .semantics { contentDescription = moreFor },
            ) {
                CompositionLocalProvider(LocalContentColor provides brand.onHighlightContainer) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(start = 2.dp).size(32.dp).indication(source, PressIndication(CircleShape))) {
                        Icon(painterResource(R.drawable.ic_more_horiz), contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                }
                LyrifyMenu(expanded = menu, onDismiss = { menu = false }) {
                    MenuRow(stringResource(R.string.eq_rename), R.drawable.ic_edit, {
                        menu = false
                        onRename()
                    })
                    MenuRow(stringResource(R.string.eq_delete), R.drawable.ic_delete, {
                        menu = false
                        onDelete()
                    }, destructive = true)
                }
            }
        }
    }
}

// The bands panel

/**
 * The 10 bands over their response curve, in a 24 dp panel with the glass edge: the dB scale on the left, the plot, the
 * frequencies under it. Each knob moves as far as the finger does and shows its gain in a bubble over it meanwhile;
 * picking a preset glides every knob and the curve to it ([glideSpec]). A drag that starts between two bands scrolls
 * the page. The plot is a graph, low notes on the left in every language.
 */
@Composable
private fun BandsPanel(bandsDb: List<Float>, enabled: Boolean, onBand: (Int, Float) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val chrome = LocalChromeColors.current
    val highlight = LocalBrandColors.current.highlight
    val reduced = LocalReducedMotion.current
    val unit = LocalDensity.current.density
    var dragging by remember { mutableStateOf<Int?>(null) }
    val glide = glideSpec(dragging != null, reduced)
    val shown = bandsDb.map { animateFloatAsState(it, glide, label = "band") }
    val grown = bandsDb.indices.map { animateFloatAsState(if (dragging == it) KNOB_GROWN else 1f, motion(tween(Motion.QUICK_MS), reduced), label = "knob") }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(
            Modifier
                .padding(horizontal = ScreenPadding)
                .fillMaxWidth()
                .clip(PanelShape)
                .background(colors.surfaceContainerLow)
                .border(1.dp, chrome.glassEdge, PanelShape)
                .padding(start = 4.dp, end = 8.dp, top = 2.dp, bottom = 12.dp),
        ) {
            Row(Modifier.fillMaxWidth().height(PLOT_DP.dp)) {
                PlotScale(Modifier.width(SCALE_WIDTH).fillMaxHeight())
                BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                    val plot = EqPlot(constraints.maxWidth.toFloat(), unit)
                    val zero = colors.outlineVariant
                    val track = colors.onSurface.copy(alpha = TRACK_ALPHA)
                    Canvas(Modifier.fillMaxSize()) {
                        val gains = shown.map { it.value }
                        drawCurve(eqCurve(gains, plot), plot, zero, highlight)
                        val width = BAND_TRACK.toPx()
                        gains.indices.forEach { i -> drawRoundRect(track, Offset(plot.x(i) - width / 2, plot.top), Size(width, plot.track), CornerRadius(width / 2)) }
                        gains.forEachIndexed { i, db -> drawKnob(Offset(plot.x(i), plot.y(db)), KNOB.toPx() / 2 * grown[i].value, highlight, chrome.shadow) }
                    }
                    Row(Modifier.fillMaxSize()) {
                        bandsDb.forEachIndexed { i, db ->
                            Box(contentAlignment = Alignment.TopCenter, modifier = Modifier.weight(1f).fillMaxHeight().zIndex(if (dragging == i) 1f else 0f)) {
                                Band(i, db, { shown[i].value }, plot, enabled, dragging = dragging == i, onDragging = { on -> if (on) dragging = i else if (dragging == i) dragging = null }, onBand = onBand)
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().clearAndSetSemantics {}) {
                Text(
                    stringResource(R.string.eq_scale_hz),
                    style = ScaleText,
                    color = colors.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    modifier = Modifier.width(SCALE_WIDTH).padding(top = SCALE_GAP, end = SCALE_GAP),
                )
                EQ_BANDS_HZ.forEach { hz ->
                    Text(
                        if (hz < 1_000) hz.toString() else "${hz / 1_000}k",
                        style = ScaleText,
                        color = colors.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        modifier = Modifier.weight(1f).padding(top = SCALE_GAP),
                    )
                }
            }
        }
    }
}

/** The scale left of the plot: "dB", +12, 0 and −12, each level with its gain ([EqPlot.scale]), 6 dp from the plot. */
@Composable
private fun PlotScale(modifier: Modifier) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    val labels = listOf(stringResource(R.string.eq_scale_db), dbText(EqSound.BAND_DB), dbText(0f), dbText(-EqSound.BAND_DB))
    Layout(content = { labels.forEach { Text(it, style = ScaleText, color = color, maxLines = 1) } }, modifier = modifier.clearAndSetSemantics {}) { measurables, constraints ->
        val scale = EqPlot(width = 0f, unit = density).scale
        val gap = SCALE_GAP.roundToPx()
        val placeables = measurables.map { it.measure(Constraints()) }
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeables.forEachIndexed { i, label -> label.place(constraints.maxWidth - gap - label.width, (scale[i] - label.height / 2f).roundToInt()) }
        }
    }
}

/**
 * A band's touch: 22 dp wide, from 28 dp under the plot's top to its bottom, one TalkBack slider with the band's name and
 * gain. Under a finger its gain shows in a bubble 42 dp over the knob's centre, fading in and out.
 */
@Composable
private fun Band(index: Int, db: Float, shown: () -> Float, plot: EqPlot, enabled: Boolean, dragging: Boolean, onDragging: (Boolean) -> Unit, onBand: (Int, Float) -> Unit) {
    val resources = LocalResources.current
    val brand = LocalBrandColors.current
    val latest by rememberUpdatedState(db)
    val change by rememberUpdatedState(onBand)
    val drag by rememberUpdatedState(onDragging)
    val bubble by animateFloatAsState(if (dragging) 1f else 0f, motion(tween(Motion.QUICK_MS), LocalReducedMotion.current), label = "gain bubble")
    val hz = EQ_BANDS_HZ[index]
    val name = if (hz < 1_000) resources.getString(R.string.eq_band_hz, hz) else resources.getString(R.string.eq_band_khz, hz / 1_000)
    val valueText = dbLabel(db)
    val range = -EqSound.BAND_DB..EqSound.BAND_DB
    val gestures = if (!enabled) {
        Modifier
    } else {
        Modifier.pointerInput(plot) {
            // The knob moves as far as the finger does, from where it was, so a touch meant to scroll never makes it jump.
            var y = 0f
            detectVerticalDragGestures(
                onDragStart = {
                    y = plot.y(latest)
                    drag(true)
                },
                onDragEnd = { drag(false) },
                onDragCancel = { drag(false) },
            ) { _, dy ->
                y = (y + dy).coerceIn(plot.y(EqSound.BAND_DB), plot.y(-EqSound.BAND_DB))
                change(index, plot.db(y))
            }
        }
    }
    Box(
        contentAlignment = Alignment.TopCenter,
        modifier = Modifier
            .padding(top = BAND_TOP)
            .width(BAND_WIDTH)
            .fillMaxHeight()
            .semantics {
                contentDescription = name
                stateDescription = valueText
                progressBarRangeInfo = ProgressBarRangeInfo(db, range, steps = ((range.endInclusive - range.start) / HALF_DB).roundToInt() - 1)
                if (enabled) {
                    setProgress { target ->
                        change(index, target.coerceIn(range))
                        true
                    }
                } else {
                    disabled()
                }
            }
            .then(gestures),
    ) {
        if (bubble > 0f) {
            Text(
                dbText(db),
                style = BubbleText,
                color = brand.onHighlight,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier
                    .offset { IntOffset(0, (plot.y(shown()) - (BAND_TOP + BUBBLE_RISE).toPx()).roundToInt()) }
                    .wrapContentWidth(unbounded = true)
                    .graphicsLayer { alpha = bubble }
                    .background(brand.highlight, BubbleShape)
                    .widthIn(min = BUBBLE_MIN_WIDTH)
                    .padding(horizontal = 6.dp)
                    .clearAndSetSemantics {},
            )
        }
    }
}

/** The zero line, the area between it and the curve (coral, strongest at ±12 dB) and the curve's 2.5 dp line. */
private fun DrawScope.drawCurve(curve: EqCurve, plot: EqPlot, zero: Color, line: Color) {
    val zeroY = plot.y(0f)
    drawLine(zero, Offset(0f, zeroY), Offset(size.width, zeroY), 1.dp.toPx())
    val area = Path().apply {
        trace(curve)
        lineTo(size.width, zeroY)
        lineTo(0f, zeroY)
        close()
    }
    val fill = Brush.verticalGradient(
        0f to line.copy(alpha = FILL_EDGE),
        0.5f to line.copy(alpha = FILL_MIDDLE),
        1f to line.copy(alpha = FILL_EDGE),
        startY = plot.top,
        endY = plot.top + plot.track,
    )
    drawPath(area, fill)
    drawPath(Path().apply { trace(curve) }, line, style = Stroke(CURVE_WIDTH.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private fun Path.trace(curve: EqCurve) {
    moveTo(curve.start.x, curve.start.y)
    curve.knots.firstOrNull()?.let { lineTo(it.x, it.y) }
    curve.steps.forEach { lineStep -> cubicTo(lineStep.c1.x, lineStep.c1.y, lineStep.c2.x, lineStep.c2.y, lineStep.to.x, lineStep.to.y) }
    lineTo(curve.end.x, curve.end.y)
}

/** A knob: a [color] disc with a soft [shadow] a dp under it, as the prototype's 0 1px 4px. */
private fun DrawScope.drawKnob(center: Offset, radius: Float, color: Color, shadow: Color) {
    val blur = KNOB_BLUR.toPx()
    val under = center + Offset(0f, 1.dp.toPx())
    val soft = Brush.radialGradient(0f to shadow, radius / (radius + blur) to shadow, 1f to Color.Transparent, center = under, radius = radius + blur)
    drawCircle(soft, radius + blur, under)
    drawCircle(color, radius, center)
}

// Bass boost, Pre-amp, More

/** "More": Balance and Mono under it when open; closed, its line says how they are when not as new. */
@Composable
private fun MoreGroup(sound: EqSound, enabled: Boolean, onAction: (EqAction) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val resources = LocalResources.current
    val reduced = LocalReducedMotion.current
    var more by rememberSaveable { mutableStateOf(false) }
    val turn by animateFloatAsState(if (more) 180f else 0f, motion(tween(Motion.SETTLE_MS, easing = Motion.Standard), reduced), label = "more chevron")
    val line = moreLine(sound, { balanceText(resources, it) }, stringResource(R.string.eq_mono))
    GroupedRows {
        GroupedRow(
            R.drawable.ic_tune,
            stringResource(R.string.eq_more),
            tile = colors.surfaceContainerHighest,
            onTile = colors.onSurfaceVariant,
            first = true,
            modifier = Modifier.toggleable(more, enabled = enabled, role = Role.DropdownList) { more = it },
            supporting = {
                AnimatedVisibility(!more && line != null, enter = opening(reduced), exit = closing(reduced)) {
                    Text(line.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            },
            trailing = { Icon(painterResource(R.drawable.ic_expand_more), contentDescription = null, modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = turn }) },
        )
        AnimatedVisibility(more, enter = opening(reduced), exit = closing(reduced)) {
            Column {
                LevelRow(
                    R.drawable.ic_balance,
                    stringResource(R.string.eq_balance),
                    balanceText(resources, sound.balance),
                    sound.balance,
                    -1f..1f,
                    1f / BALANCE_STEPS,
                    ends = listOf(stringResource(R.string.eq_mark_left), stringResource(R.string.eq_mark_right)),
                    enabled = enabled,
                    detent = true,
                ) { onAction(EqAction.Balance(it)) }
                GroupedRow(
                    R.drawable.ic_hearing,
                    stringResource(R.string.eq_mono),
                    tile = colors.surfaceContainerHighest,
                    onTile = colors.onSurfaceVariant,
                    modifier = Modifier.toggleable(sound.mono, enabled = enabled, role = Role.Switch) { onAction(EqAction.Mono(it)) },
                    trailing = { LyrifySwitch(sound.mono) },
                )
            }
        }
    }
}

private fun opening(reduced: Boolean): EnterTransition =
    if (reduced) EnterTransition.None else expandVertically(tween(Motion.SETTLE_MS, easing = Motion.Standard)) + fadeIn(tween(Motion.SETTLE_MS))

private fun closing(reduced: Boolean): ExitTransition =
    if (reduced) ExitTransition.None else shrinkVertically(tween(Motion.SETTLE_MS, easing = Motion.Standard)) + fadeOut(tween(Motion.QUICK_MS))

/**
 * A level as a grouped row (.gr.eqsl): its tile, its title with the value at the end, the slider pulled up under them
 * and the marks of its ends under that. TalkBack hears the slider alone, with the title and the value.
 */
@Composable
private fun LevelRow(
    @DrawableRes icon: Int,
    title: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    ends: List<String>,
    enabled: Boolean,
    first: Boolean = false,
    detent: Boolean = false,
    onChange: (Float) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .hairline(first, colors.outlineVariant.copy(alpha = HAIRLINE_ALPHA))
            .padding(start = 14.dp, end = 16.dp, top = 14.dp, bottom = 12.dp),
    ) {
        RowTile(icon)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.heightIn(min = 36.dp).clearAndSetSemantics {}) {
                Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text(valueText, style = LevelValue, color = colors.onSurface, maxLines = 1)
            }
            // Left and right are the ears' (Balance), so the levels run left to right in every language.
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                LevelSlider(value, range, step, title, valueText, enabled, detent, onChange, Modifier.overhang(top = 6.dp).fillMaxWidth().height(TOUCH))
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.overhang(top = 6.dp).fillMaxWidth().clearAndSetSemantics {}) {
                    ends.forEach { Text(it, style = EndMark, color = colors.onSurfaceVariant, maxLines = 1) }
                }
            }
        }
    }
}

/** A grouped row's tile: the icon on a 36 dp squircle. */
@Composable
private fun RowTile(@DrawableRes icon: Int) {
    val colors = MaterialTheme.colorScheme
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(36.dp).clip(TileShape).background(colors.surfaceContainerHighest)) {
        Icon(painterResource(icon), contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
}

/**
 * A level on a 6 dp track in a touch area of at least 48 dp, filled from its start to the 18 dp knob, which grows under
 * the finger; [detent]: a tick marks the middle and the step either side of it holds there ([balanceHeld]). A tap or a
 * drag sets it where the finger is; each change is handed on at once ([onChange], the screen rounds it to [step]s), so
 * the music follows the finger. TalkBack reads [label] and [valueText] and can set it.
 */
@Composable
private fun LevelSlider(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    label: String,
    valueText: String,
    enabled: Boolean,
    detent: Boolean,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val highlight = LocalBrandColors.current.highlight
    val shadow = LocalChromeColors.current.shadow
    val change by rememberUpdatedState(onChange)
    var dragging by remember { mutableStateOf(false) }
    val grow by animateFloatAsState(if (dragging) KNOB_GROWN else 1f, motion(tween(Motion.QUICK_MS), LocalReducedMotion.current), label = "knob")
    val span = range.endInclusive - range.start
    val share = ((value - range.start) / span).coerceIn(0f, 1f)
    fun PointerInputScope.at(x: Float): Float {
        val inset = KNOB.toPx() / 2
        val level = range.start + span * ((x - inset) / (size.width - 2 * inset)).coerceIn(0f, 1f)
        return if (detent) balanceHeld(level) else level
    }
    val gestures = if (!enabled) {
        Modifier
    } else {
        Modifier
            .pointerInput(range) { detectTapGestures { offset -> change(at(offset.x)) } }
            .pointerInput(range) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        dragging = true
                        change(at(offset.x))
                    },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                ) { pointer, _ -> change(at(pointer.position.x)) }
            }
    }
    Box(
        modifier
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
            .then(gestures)
            .drawBehind {
                val knob = KNOB.toPx()
                val x = knob / 2 + (size.width - knob) * share
                val middle = size.height / 2
                val track = LEVEL_TRACK.toPx()
                val corner = CornerRadius(track / 2)
                if (detent) {
                    val tick = Size(1.dp.toPx() * 2, DETENT_TICK.toPx())
                    drawRoundRect(colors.outline, Offset(size.width / 2 - tick.width / 2, middle - tick.height / 2), tick, CornerRadius(tick.width / 2))
                }
                drawRoundRect(colors.onSurface.copy(alpha = TRACK_ALPHA), Offset(0f, middle - track / 2), Size(size.width, track), corner)
                drawRoundRect(highlight, Offset(0f, middle - track / 2), Size(x, track), corner)
                drawKnob(Offset(x, middle), knob / 2 * grow, highlight, shadow)
            },
    )
}

/** The hairline over a grouped row, from after its tile to its end, unless it is the [first]. */
private fun Modifier.hairline(first: Boolean, color: Color): Modifier = if (first) {
    this
} else {
    drawBehind {
        val start = HAIRLINE_START.toPx()
        drawRect(color, Offset(if (layoutDirection == LayoutDirection.Rtl) 0f else start, 0f), Size(size.width - start, 1.dp.toPx()))
    }
}

/** Takes [top] less room above and [bottom] less below than it draws: the prototype's negative margins. */
private fun Modifier.overhang(top: Dp = 0.dp, bottom: Dp = 0.dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val above = top.roundToPx()
    layout(placeable.width, (placeable.height - above - bottom.roundToPx()).coerceAtLeast(0)) { placeable.place(0, -above) }
}

/** A control of the body while the equalizer is off: TalkBack hears it as off too. */
private fun Modifier.inert(off: Boolean): Modifier = if (off) semantics { disabled() } else this

// The Outputs sheet

/** Every output's own settings, the one music plays on chosen, as the phone decides it. */
@Composable
private fun OutputsSheet(rows: List<EqOutputRow>, onDismiss: () -> Unit) {
    val resources = LocalResources.current
    LyrifySheet(onDismiss = onDismiss, padding = 12.dp) {
        Text(stringResource(R.string.eq_outputs), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 8.dp).semantics { heading() })
        Text(
            stringResource(R.string.eq_outputs_text),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        Column {
            rows.forEach { row -> OutputRow(row, outputLabel(row.output) { resources.getString(it) }, outputLine(row.profile)) }
        }
    }
}

/** An output with how it plays (its preset, Off, Not used yet); the one playing on the brand's container with a check. */
@Composable
private fun OutputRow(row: EqOutputRow, name: String, line: String) {
    val colors = MaterialTheme.colorScheme
    val brand = LocalBrandColors.current
    val reduced = LocalReducedMotion.current
    val fade = motion(tween<Color>(Motion.QUICK_MS), reduced)
    val fill by animateColorAsState(if (row.playing) brand.highlightContainer else brand.highlightContainer.copy(alpha = 0f), fade, label = "output fill")
    val text by animateColorAsState(if (row.playing) brand.onHighlightContainer else colors.onSurface, fade, label = "output text")
    val icon by animateColorAsState(if (row.playing) brand.onHighlightContainer else colors.onSurfaceVariant, fade, label = "output icon")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .background(fill, OutputShape)
            .semantics(mergeDescendants = true) { selected = row.playing }
            .padding(horizontal = 8.dp),
    ) {
        Icon(painterResource(row.output.icon), contentDescription = null, tint = icon, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, color = text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(line, style = OutputLineText, color = text.copy(alpha = OUTPUT_LINE_ALPHA), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        AnimatedVisibility(
            row.playing,
            enter = fadeIn(motion(tween(Motion.QUICK_MS), reduced)) + scaleIn(motion(tween(Motion.QUICK_MS), reduced), initialScale = DOTS_SCALE),
            exit = fadeOut(motion(tween(Motion.QUICK_MS), reduced)) + scaleOut(motion(tween(Motion.QUICK_MS), reduced), targetScale = DOTS_SCALE),
        ) {
            Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = brand.onHighlightContainer, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun outputLine(profile: EqProfile?): String = when {
    profile == null -> stringResource(R.string.eq_output_unused)
    !profile.sound.enabled -> stringResource(R.string.eq_off)
    else -> presetName(profile.preset)
}

@Composable
private fun presetName(preset: PresetChoice?): String = when (preset) {
    null -> stringResource(R.string.eq_custom)
    is PresetChoice.BuiltIn -> stringResource(preset.preset.label)
    is PresetChoice.Own -> preset.name
}

/** An output's icon: the phone, headphones by their plug, a car, any other speaker. */
@get:DrawableRes
private val OutputKey.icon: Int
    get() = when (kind) {
        OutputKind.SPEAKER -> R.drawable.ic_smartphone
        OutputKind.WIRED -> R.drawable.ic_headset_mic
        OutputKind.USB -> R.drawable.ic_usb
        OutputKind.BLUETOOTH -> R.drawable.ic_headphones
        OutputKind.CAR -> R.drawable.ic_directions_car
        OutputKind.OTHER -> R.drawable.ic_speaker
    }

@Composable
private fun dbLabel(db: Float): String = stringResource(R.string.eq_db, dbText(db))

private fun balanceText(resources: Resources, balance: Float): String {
    val percent = (abs(balance) * 100).roundToInt()
    return when {
        percent == 0 -> resources.getString(R.string.eq_balance_centre)
        balance < 0 -> resources.getString(R.string.eq_balance_left, percent)
        else -> resources.getString(R.string.eq_balance_right, percent)
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

/** The scale's and the frequencies' labels (.eqscale, .eqhz, .eqfreqs). */
private val ScaleText = body(11.5f, 14).tabular()

/** A knob's gain while it moves (.eqval). */
private val BubbleText = body(12f, 22, 700).tabular()

/** A level's value at the end of its title (.eqslh b). */
private val LevelValue = body(16f, 22, 600).tabular()

/** The marks of a level's ends (.rlab). */
private val EndMark = body(12f, 16).tabular()

/** An output's second line in the Outputs sheet (.eqosub). */
private val OutputLineText = body(13f, 18)

private const val HALF_DB: Float = 0.5f

/** The body of an equalizer switched off: shown faded, taking no touch. */
private const val OFF_ALPHA: Float = 0.45f

/** A knob under the finger. */
private const val KNOB_GROWN: Float = 1.25f
private const val TRACK_ALPHA: Float = 0.12f
private const val FILL_EDGE: Float = 0.3f
private const val FILL_MIDDLE: Float = 0.03f
private const val HAIRLINE_ALPHA: Float = 0.7f
private const val OUTPUT_LINE_ALPHA: Float = 0.8f

/** Where a "⋯" or a check grows from as it fades in. */
private const val DOTS_SCALE: Float = 0.6f

private val BLOCK_GAP = 12.dp
private val PanelShape = RoundedCornerShape(24.dp)
private val TileShape = RoundedCornerShape(11.dp)
private val BubbleShape = RoundedCornerShape(11.dp)
private val OutputShape = RoundedCornerShape(14.dp)
private val SCALE_WIDTH = 28.dp
private val SCALE_GAP = 6.dp

/** A band's touch starts this far under the plot's top: room for its bubble over +12 dB. */
private val BAND_TOP = 28.dp
private val BAND_WIDTH = 22.dp
private val BAND_TRACK = 4.dp
private val KNOB = 18.dp
private val KNOB_BLUR = 3.dp
private val CURVE_WIDTH = 2.5.dp
private val BUBBLE_RISE = 42.dp
private val BUBBLE_MIN_WIDTH = 30.dp
private val LEVEL_TRACK = 6.dp
private val DETENT_TICK = 16.dp
private val HAIRLINE_START = 64.dp

/** A chosen own preset's end: 42 dp in all, its "⋯" in it. */
private val DOTS_ROOM = 28.dp
private val DOTS_TOUCH = 38.dp
