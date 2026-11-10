package app.lyrify.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.media.MediaRouter2
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lyrify.R
import app.lyrify.data.LedgerEntry
import app.lyrify.data.LyricsStatus
import app.lyrify.library.SongRef
import app.lyrify.player.AbPart
import app.lyrify.player.Bookmark
import app.lyrify.player.ExtrasSaid
import app.lyrify.player.PlayerConnection
import app.lyrify.player.PlayerExtras
import app.lyrify.player.PlayerGraph
import app.lyrify.player.PlayerUi
import app.lyrify.player.QueueSource
import app.lyrify.player.SEMITONES
import app.lyrify.player.SLEEP_CUSTOM_MINUTES
import app.lyrify.player.SLEEP_CUSTOM_SONGS
import app.lyrify.player.SLEEP_MINUTES
import app.lyrify.player.SLEEP_SONGS
import app.lyrify.player.SPEEDS
import app.lyrify.player.SPEED_STEPS_PER_X
import app.lyrify.player.SleepTimer
import app.lyrify.player.SongState
import app.lyrify.player.SourceKind
import app.lyrify.player.Speed
import app.lyrify.player.abPart
import app.lyrify.player.sleepAfter
import app.lyrify.player.sleepFor
import app.lyrify.player.sleepLeftMs
import app.lyrify.player.toSpot
import app.lyrify.tones.ToneFiles
import app.lyrify.tones.TonePlayer
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// The player's extras (milestone P2), their sheets and actions: the sleep timer's sheets, the Speed sheet, Repeat a
// part on and off, the bookmark dialog and the song sheet's bookmarks list, Play on, Make a tone from the player, and
// the toasts the service sends. What shows on the player itself is in PlayerChips.kt; player/PlaybackExtras.kt and
// player/Sleep.kt hold the rules and the state.

// Rules, as pure functions

/** A speed as the player writes it: "1.25", "0.5", "2". */
fun speedNumber(speed: Float): String = BigDecimal(speed.toDouble()).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

/** Semitones with their sign: "+2", "−3", "0". */
fun semitonesText(semitones: Int): String = when {
    semitones > 0 -> "+$semitones"
    semitones < 0 -> "\u2212${-semitones}"
    else -> "0"
}

/** The pitch the speed chip adds after the speed: only a moved one. */
fun chipPitch(speed: Speed): String? = if (!speed.keepPitch && speed.semitones != 0) semitonesText(speed.semitones) else null

/** The Custom stepper: minutes by 1 up to 10 and by 5 above, songs by 1, within their ranges. */
fun nextCustom(value: Int, up: Boolean, songs: Boolean): Int {
    if (songs) return (value + if (up) 1 else -1).coerceIn(SLEEP_CUSTOM_SONGS)
    val step = if (value < CUSTOM_FINE_UNTIL || (value == CUSTOM_FINE_UNTIL && !up)) 1 else CUSTOM_STEP
    return (value + if (up) step else -step).coerceIn(SLEEP_CUSTOM_MINUTES)
}

private const val CUSTOM_FINE_UNTIL: Int = 10
private const val CUSTOM_STEP: Int = 5

/** Whether [part] is the song's at [path]: its chip and handles show. */
fun partShown(part: AbPart?, path: String): Boolean = part != null && part.path == path

/** The song playing as its extras know it: the library's file, else the queue's. */
internal fun PlayerUi.path(): String = song?.path ?: item.song.path

// What the player opens

/** The extras' sheets and dialogs: one at a time. */
enum class ExtrasSheet { SLEEP, SLEEP_MINUTES, SLEEP_SONGS, SPEED, BOOKMARK, TONE }

/**
 * The player's extras on screen: which sheet is open, and the actions of P2's routes ([route]). The state lives in
 * [PlayerExtras]; the service applies it. Main thread.
 */
@Stable
class PlayerExtrasUi internal constructor(
    val extras: PlayerExtras,
    private val connection: PlayerConnection,
    private val context: Context,
    private val toasts: ToastState,
    private val resources: Resources,
    private val scope: CoroutineScope,
) {
    var open: ExtrasSheet? by mutableStateOf(null)

    /** Where the bookmark being named sits. */
    var bookmarkAt: Long by mutableLongStateOf(0)
        private set

    /** P2's routes of the player's "⋯" and the sleep button; false for a route it does not fill. */
    fun route(route: PlayerRoute, ui: PlayerUi): Boolean {
        when (route) {
            PlayerRoute.SPEED -> open = ExtrasSheet.SPEED
            PlayerRoute.SLEEP -> open = ExtrasSheet.SLEEP
            PlayerRoute.MAKE_TONE -> if (ui.song != null) open = ExtrasSheet.TONE
            PlayerRoute.REPEAT_PART -> togglePart(ui)
            PlayerRoute.PLAY_ON -> if (!playOn(context)) say(Toast(resources.getString(R.string.play_on_none), R.drawable.ic_speaker))
            PlayerRoute.BOOKMARK -> {
                bookmarkAt = ui.positionAt(SystemClock.elapsedRealtime())
                open = ExtrasSheet.BOOKMARK
            }
            else -> return false
        }
        return true
    }

    fun close() {
        open = null
    }

    /** A sleep timer set: the music plays (the timer is for music playing), and the sheet closes. */
    fun setSleep(timer: SleepTimer, ui: PlayerUi) {
        extras.sleep.value = timer
        open = null
        if (!ui.playing) connection.play()
    }

    fun sleepOff() {
        extras.sleep.value = null
        open = null
        say(Toast(resources.getString(R.string.sleep_turned_off), R.drawable.ic_timer_off))
    }

    /** "Repeat a part": on at the spot playing, or off when the song's part repeats. */
    fun togglePart(ui: PlayerUi) {
        val path = ui.path()
        if (partShown(extras.state.value.part, path)) return stopPart()
        val part = abPart(path, ui.positionAt(SystemClock.elapsedRealtime()), ui.durationMs)
            ?: return say(Toast(resources.getString(R.string.part_too_short), R.drawable.ic_repeat))
        extras.change { it.copy(part = part) }
        say(Toast(resources.getString(R.string.part_started), R.drawable.ic_repeat))
    }

    fun stopPart() {
        extras.change { it.copy(part = null) }
        say(Toast(resources.getString(R.string.part_stopped), R.drawable.ic_repeat))
    }

    /** The bookmark named in the dialog, at [bookmarkAt]; its toast takes it back. */
    fun saveBookmark(ui: PlayerUi, note: String) {
        open = null
        val path = ui.path()
        val mark = extras.edit { it.withBookmark(path, bookmarkAt, note) }
            ?: return say(Toast(resources.getString(R.string.bookmark_not_saved), R.drawable.ic_error, Tone.ERROR))
        scope.launch {
            val undo = toasts.show(Toast(resources.getString(R.string.bookmark_at, clock(mark.positionMs)), R.drawable.ic_bookmark_add, action = resources.getString(R.string.player_undo)))
            if (undo) extras.change { it.withoutBookmark(path, mark.id) }
        }
    }

    private fun say(toast: Toast) {
        scope.launch { toasts.show(toast) }
    }
}

/** The player's extras for the player screen. */
@Composable
fun rememberPlayerExtras(): PlayerExtrasUi {
    val context = LocalContext.current
    val toasts = LocalToasts.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    return remember(context, toasts, resources, scope) {
        PlayerExtrasUi(PlayerExtras.of(context), PlayerGraph.connection(context), context, toasts, resources, scope)
    }
}

/** The sheet or dialog [extras] has open, over the player. */
@Composable
fun PlayerExtrasHost(extras: PlayerExtrasUi, ui: PlayerUi) {
    when (extras.open) {
        ExtrasSheet.SLEEP -> SleepSheet(extras, ui)
        ExtrasSheet.SLEEP_MINUTES -> CustomSleepSheet(extras, ui, songs = false)
        ExtrasSheet.SLEEP_SONGS -> CustomSleepSheet(extras, ui, songs = true)
        ExtrasSheet.SPEED -> SpeedSheet(extras, ui)
        ExtrasSheet.BOOKMARK -> BookmarkDialog(extras, ui)
        ExtrasSheet.TONE -> PlayerTone(ui, onClose = extras::close)
        null -> Unit
    }
}

/**
 * "Play on": your phone's own output picker (the phone, Bluetooth speakers and headphones), with no Cast library. On
 * Android 14 and later the system's switcher; before, the settings panel for media output, else Bluetooth settings.
 * False when none opened.
 */
internal fun playOn(context: Context): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && MediaRouter2.getInstance(context).showSystemOutputSwitcher()) return true
    val panel = Intent(MEDIA_OUTPUT_PANEL).putExtra(MEDIA_OUTPUT_PACKAGE, context.packageName)
    return listOf(panel, Intent(Settings.ACTION_BLUETOOTH_SETTINGS)).any { intent ->
        try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }
}

/** The settings panel for media output on Android 11 to 13 (what AndroidX's output switcher opens there). */
private const val MEDIA_OUTPUT_PANEL: String = "com.android.settings.panel.action.MEDIA_OUTPUT"
private const val MEDIA_OUTPUT_PACKAGE: String = "com.android.settings.panel.extra.PACKAGE_NAME"

// The sleep timer

/** After a time (15 min to 1 hour, Custom) or after songs (End of song, 3, 5, Custom); the readout and Turn off while one runs. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SleepSheet(extras: PlayerExtrasUi, ui: PlayerUi) {
    val timer = extras.extras.sleep.collectAsStateWithLifecycle().value
    val footer: (@Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit)? = timer?.let {
        { LyrifyButton(stringResource(R.string.sleep_turn_off), extras::sleepOff, Modifier.fillMaxWidth(), kind = ButtonKind.SECONDARY, icon = R.drawable.ic_timer_off) }
    }
    LyrifySheet(onDismiss = extras::close, footer = footer) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(painterResource(R.drawable.ic_snooze), contentDescription = null, tint = LocalBrandColors.current.highlight, modifier = Modifier.size(26.dp))
            Text(stringResource(R.string.sleep_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        }
        if (timer != null) SleepReadout(timer, ui)
        Text(stringResource(R.string.sleep_sentence), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SectionTitle(stringResource(R.string.sleep_after_time))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth().selectableGroup()) {
            val byTime = timer as? SleepTimer.ByTime
            SLEEP_MINUTES.forEach { minutes ->
                LyrifyChip(selected = byTime?.let { !it.custom && it.minutes == minutes } == true, onClick = { extras.setSleep(sleepFor(minutes, SystemClock.elapsedRealtime()), ui) }, role = Role.RadioButton) {
                    Text(if (minutes == 60) stringResource(R.string.sleep_hour) else stringResource(R.string.sleep_minutes_chip, minutes))
                }
            }
            LyrifyChip(selected = byTime?.custom == true, onClick = { extras.open = ExtrasSheet.SLEEP_MINUTES }, role = Role.RadioButton) { Text(stringResource(R.string.sleep_custom)) }
        }
        SectionTitle(stringResource(R.string.sleep_after_songs))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth().selectableGroup()) {
            val bySongs = timer as? SleepTimer.BySongs
            SLEEP_SONGS.forEach { songs ->
                LyrifyChip(selected = bySongs?.let { !it.custom && it.songs == songs } == true, onClick = { extras.setSleep(sleepAfter(songs), ui) }, role = Role.RadioButton) {
                    Text(if (songs == 1) stringResource(R.string.sleep_end_of_song) else pluralStringResource(R.plurals.sleep_songs_chip, songs, songs))
                }
            }
            LyrifyChip(selected = bySongs?.custom == true, onClick = { extras.open = ExtrasSheet.SLEEP_SONGS }, role = Role.RadioButton) { Text(stringResource(R.string.sleep_custom)) }
        }
    }
}

/** What is left: the time, the songs to go (this one too), or the time to the end of the last song. */
@Composable
private fun SleepReadout(timer: SleepTimer, ui: PlayerUi) {
    val now = rememberClock(true, everyMs = 500)
    val left = sleepLeftMs(timer, now, ui.positionAt(now), ui.durationMs, ui.speed)
    val (value, words) = when {
        timer is SleepTimer.BySongs && timer.left > 1 -> timer.left.toString() to stringResource(R.string.sleep_songs_left)
        timer is SleepTimer.BySongs -> clock(left ?: 0) to stringResource(R.string.sleep_song_left)
        else -> clock(left ?: 0) to stringResource(R.string.sleep_time_left)
    }
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(value, style = MaterialTheme.typography.displaySmall.tabular().copy(fontWeight = FontWeight.SemiBold))
        Text(words, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
    }
}

/** Custom: a number of minutes or of songs, with Less and More, then Start timer. */
@Composable
private fun CustomSleepSheet(extras: PlayerExtrasUi, ui: PlayerUi, songs: Boolean) {
    val running = extras.extras.sleep.value
    var value by rememberSaveable(songs) {
        mutableIntStateOf(
            when {
                songs && running is SleepTimer.BySongs && running.custom -> running.songs
                !songs && running is SleepTimer.ByTime && running.custom -> running.minutes
                songs -> 2
                else -> 20
            },
        )
    }
    val start = {
        extras.setSleep(if (songs) sleepAfter(value, custom = true) else sleepFor(value, SystemClock.elapsedRealtime(), custom = true), ui)
    }
    LyrifySheet(
        onDismiss = extras::close,
        footer = { LyrifyButton(stringResource(R.string.sleep_start), start, Modifier.fillMaxWidth(), icon = R.drawable.ic_snooze) },
    ) {
        Text(stringResource(if (songs) R.string.sleep_custom_songs else R.string.sleep_custom_time), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        Text(
            stringResource(if (songs) R.string.sleep_custom_songs_sentence else R.string.sleep_custom_time_sentence),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            val range = if (songs) SLEEP_CUSTOM_SONGS else SLEEP_CUSTOM_MINUTES
            StepButton(R.drawable.ic_remove, stringResource(R.string.sleep_less), enabled = value > range.first) { value = nextCustom(value, up = false, songs) }
            val unit = if (songs) pluralStringResource(R.plurals.sleep_unit_songs, value) else stringResource(R.string.sleep_unit_minutes)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.semantics(mergeDescendants = true) {}) {
                Text(value.toString(), style = MaterialTheme.typography.displayMedium.tabular().copy(fontWeight = FontWeight.SemiBold))
                Text(unit, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
            }
            StepButton(R.drawable.ic_add, stringResource(R.string.sleep_more), enabled = value < range.last) { value = nextCustom(value, up = true, songs) }
        }
    }
}

@Composable
private fun StepButton(@DrawableRes icon: Int, label: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(colors.surfaceContainerHigh, CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
    ) { Icon(painterResource(icon), contentDescription = null, tint = if (enabled) colors.onSurface else colors.onSurface.copy(alpha = 0.38f), modifier = Modifier.size(26.dp)) }
}

// Speed and pitch

/**
 * Speed: the chips and a fine slider (0.5× to 2×), "Keep pitch" (on) with a pitch slider in semitones when off, and
 * "This song only": what is set moves to the song, and the usual speed goes back to what it was when the sheet opened.
 * Sliders store their value when let go.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpeedSheet(extras: PlayerExtrasUi, ui: PlayerUi) {
    val store = extras.extras
    val state = store.state.collectAsStateWithLifecycle().value
    val path = ui.path()
    val usualAtOpen = remember { store.state.value.usual }
    val speed = state.speedOf(path)
    val own = path in state.speeds
    var sliding by remember { mutableStateOf<Float?>(null) }
    var pitching by remember { mutableStateOf<Int?>(null) }
    val shown = speed.copy(speed = sliding ?: speed.speed, semitones = pitching ?: speed.semitones)
    val set: ((Speed) -> Speed) -> Unit = { change -> store.change { it.withSpeed(path, change) } }
    LyrifySheet(
        onDismiss = extras::close,
        footer = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                LyrifyButton(stringResource(R.string.speed_reset), { set { Speed() } }, Modifier.weight(1f), kind = ButtonKind.SECONDARY, enabled = speed != Speed())
                LyrifyButton(stringResource(R.string.speed_done), extras::close, Modifier.weight(1f))
            }
        },
    ) {
        Text(stringResource(R.string.speed_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.speed_value, speedNumber(shown.speed)), style = MaterialTheme.typography.displaySmall.tabular().copy(fontWeight = FontWeight.SemiBold))
            val pitch = when {
                shown.keepPitch -> stringResource(R.string.speed_pitch_kept)
                shown.semitones != 0 -> stringResource(R.string.speed_pitch_moved, semitonesText(shown.semitones))
                else -> stringResource(R.string.speed_pitch_follows)
            }
            Text(pitch, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth().selectableGroup()) {
            SPEEDS.forEach { choice ->
                LyrifyChip(selected = shown.speed == choice, onClick = { set { it.copy(speed = choice) } }, role = Role.RadioButton) {
                    Text(stringResource(R.string.speed_value, speedNumber(choice)), style = MaterialTheme.typography.labelLarge.tabular())
                }
            }
        }
        val fine = stringResource(R.string.speed_fine)
        val fineValue = stringResource(R.string.speed_value, speedNumber(shown.speed))
        Slider(
            value = shown.speed,
            onValueChange = { sliding = it },
            onValueChangeFinished = {
                sliding?.let { value -> set { it.copy(speed = value) } }
                sliding = null
            },
            valueRange = SPEEDS.first()..SPEEDS.last(),
            steps = ((SPEEDS.last() - SPEEDS.first()) * SPEED_STEPS_PER_X).roundToInt() - 1,
            colors = sliderColors(),
            modifier = Modifier.semantics {
                contentDescription = fine
                stateDescription = fineValue
            },
        )
        SheetSwitch(R.drawable.ic_graphic_eq, stringResource(R.string.speed_keep_pitch), stringResource(if (shown.keepPitch) R.string.speed_keep_pitch_on else R.string.speed_keep_pitch_off), shown.keepPitch) { on ->
            set { it.copy(keepPitch = on) }
        }
        if (!shown.keepPitch) {
            val title = stringResource(R.string.speed_pitch, semitonesText(shown.semitones))
            Column {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(title, style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.speed_semitones), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Slider(
                    value = shown.semitones.toFloat(),
                    onValueChange = { pitching = it.roundToInt() },
                    onValueChangeFinished = {
                        pitching?.let { value -> set { it.copy(semitones = value) } }
                        pitching = null
                    },
                    valueRange = SEMITONES.first.toFloat()..SEMITONES.last.toFloat(),
                    steps = SEMITONES.last - SEMITONES.first - 1,
                    colors = sliderColors(),
                    modifier = Modifier.semantics { contentDescription = title },
                )
            }
        }
        SheetSwitch(R.drawable.ic_music_note, stringResource(R.string.speed_this_song), stringResource(if (own) R.string.speed_this_song_on else R.string.speed_this_song_off), own) { on ->
            store.change { it.thisSongOnly(path, on, usualAtOpen) }
        }
        Text(stringResource(if (own) R.string.speed_own_sentence else R.string.speed_usual_sentence), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun sliderColors() = LocalBrandColors.current.let { brand ->
    SliderDefaults.colors(thumbColor = brand.highlight, activeTrackColor = brand.highlight, inactiveTrackColor = brand.highlight.copy(alpha = 0.16f))
}

/** A switch row inside a sheet: one TalkBack stop with its name, its line and its state. */
@Composable
private fun SheetSwitch(@DrawableRes icon: Int, title: String, detail: String, checked: Boolean, onSwitch: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .toggleable(checked, role = Role.Switch, onValueChange = onSwitch)
            .heightIn(min = 64.dp)
            .padding(horizontal = 4.dp, vertical = 8.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(colors.surfaceContainerHighest)) {
            Icon(painterResource(icon), contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        }
        LyrifySwitch(checked)
    }
}

// Bookmarks

/** "Bookmark at 1:02", with an optional note. */
@Composable
private fun BookmarkDialog(extras: PlayerExtrasUi, ui: PlayerUi) {
    var note by rememberSaveable { mutableStateOf("") }
    val save = { extras.saveBookmark(ui, note) }
    ConfirmDialog(
        title = stringResource(R.string.bookmark_at, clock(extras.bookmarkAt)),
        confirm = stringResource(R.string.bookmark_save),
        dismiss = stringResource(R.string.bookmark_cancel),
        onConfirm = save,
        onDismiss = extras::close,
        icon = R.drawable.ic_bookmark_add,
        destructive = false,
        body = {
            LyrifyField(
                note,
                { note = it.take(SongState.MAX_NOTE) },
                Modifier.fillMaxWidth(),
                label = stringResource(R.string.bookmark_note),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
            )
        },
    )
}

/**
 * A song's bookmarks in its song sheet (L1's slot): each plays the song from there, inside the queue playing; × removes
 * it, with Undo. Nothing shows for a song without bookmarks. [close] closes the sheet.
 */
@Composable
fun SongBookmarks(song: SongRef, close: () -> Unit) {
    val context = LocalContext.current
    val toasts = LocalToasts.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val extras = remember(context) { PlayerExtras.of(context) }
    val marks = extras.state.collectAsStateWithLifecycle().value.bookmarksOf(song.path)
    if (marks.isEmpty()) return
    Column {
        SectionTitle(stringResource(R.string.bookmarks_title), Modifier.padding(bottom = 4.dp))
        marks.forEach { mark ->
            BookmarkRow(
                mark,
                onPlay = {
                    close()
                    jumpTo(context, song, mark.positionMs)
                },
                onRemove = {
                    extras.change { it.withoutBookmark(song.path, mark.id) }
                    scope.launch {
                        val undo = toasts.show(Toast(resources.getString(R.string.bookmark_removed), R.drawable.ic_bookmark, action = resources.getString(R.string.player_undo)))
                        if (undo) extras.change { state -> state.copy(bookmarks = state.bookmarks + (song.path to (state.bookmarksOf(song.path) + mark).sortedBy(Bookmark::positionMs))) }
                    }
                },
            )
        }
    }
}

@Composable
private fun BookmarkRow(mark: Bookmark, onPlay: () -> Unit, onRemove: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val time = clock(mark.positionMs)
    val note = mark.note.ifEmpty { stringResource(R.string.bookmark_no_note) }
    val play = stringResource(R.string.bookmark_play_from, time, note)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(14.dp))
                .clickable(role = Role.Button, onClick = onPlay)
                .semantics(mergeDescendants = true) { contentDescription = play }
                .heightIn(min = TOUCH)
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            Icon(painterResource(R.drawable.ic_bookmark), contentDescription = null, tint = LocalBrandColors.current.highlight, modifier = Modifier.size(20.dp))
            Text(time, style = MaterialTheme.typography.bodyLarge.tabular(), modifier = Modifier.width(56.dp))
            Text(note, style = MaterialTheme.typography.bodyLarge, color = if (mark.note.isEmpty()) colors.onSurfaceVariant else colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        ChromeIconButton(R.drawable.ic_close, stringResource(R.string.bookmark_remove, time), onRemove, glass = false)
    }
}

/**
 * A bookmark's jump: [song] plays from [positionMs] inside the queue playing (the song playing: a seek; a song not in
 * the queue: Play next first), and it does not also resume at a place kept for it.
 */
internal fun jumpTo(context: Context, song: SongRef, positionMs: Long) {
    val connection = PlayerGraph.connection(context)
    if (connection.ui.value?.path() == song.path) {
        connection.seekTo(positionMs)
        connection.play()
        return
    }
    val core = PlayerGraph.core(context)
    PlayerExtras.of(context).jumpingTo = song.path
    core.switch { it.toSpot(song, positionMs, QueueSource(SourceKind.PICKED, "picked:${core.now()}", ""), core.now()) }
    connection.play()
}

// The toasts the service sends, Make a tone

/** "Resumed at 23:10" with Start over, and the sleep timer's end: shown while the app is in view. */
@Composable
internal fun ExtrasToasts() {
    val context = LocalContext.current
    val toasts = LocalToasts.current
    val resources = LocalResources.current
    val extras = remember(context) { PlayerExtras.of(context) }
    LaunchedEffect(extras, toasts) {
        extras.said.collect { said ->
            launch {
                when (said) {
                    is ExtrasSaid.Resumed -> {
                        val again = toasts.show(Toast(resources.getString(R.string.place_resumed, clock(said.positionMs)), R.drawable.ic_resume, action = resources.getString(R.string.place_start_over)))
                        if (again) startOver(context, said.path)
                    }
                    ExtrasSaid.SleepEnded -> toasts.show(Toast(resources.getString(R.string.sleep_ended), R.drawable.ic_snooze))
                }
            }
        }
    }
}

/** "Start over": the song at [path] plays from its start, and its place is forgotten. */
private fun startOver(context: Context, path: String) {
    val connection = PlayerGraph.connection(context)
    if (connection.ui.value?.path() == path) connection.seekTo(0)
    PlayerExtras.of(context).change { it.withPlace(path, null) }
}

/** Make a tone from the player: today's Tone editor with the song playing. */
@Composable
private fun PlayerTone(ui: PlayerUi, onClose: () -> Unit) {
    val song = ui.song ?: return
    val context = LocalContext.current
    val toasts = LocalToasts.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val files = remember(context) { ToneFiles.of(context.applicationContext) }
    val player = remember(context, scope) { TonePlayer(context, scope) }
    DisposableEffect(player) { onDispose { player.stop() } }
    val entry = LocalLyrics.current.ledger.entries[song.path]
        ?: LedgerEntry(path = song.path, title = song.title, artist = song.artist, album = song.album, durationMs = song.durationMs, status = LyricsStatus.NOT_FOUND, updatedAt = 0)
    ToneEditor(song = entry, files = files, player = player, onSaved = { tone ->
        onClose()
        scope.launch { toasts.say(resources, R.string.tones_saved_as, tone.name) }
    }, onClose = onClose)
}
