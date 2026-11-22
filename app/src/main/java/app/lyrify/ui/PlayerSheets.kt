package app.lyrify.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.LocalContentColor
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.border
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.lyrify.R
import app.lyrify.books.Book
import app.lyrify.books.BookKind
import app.lyrify.books.partTitle
import app.lyrify.books.toBookmark
import app.lyrify.huddle.CardKind
import app.lyrify.library.SongRef
import app.lyrify.library.displayTitle
import app.lyrify.player.AbPart
import app.lyrify.player.Bookmark
import app.lyrify.player.ExtrasSaid
import app.lyrify.player.PlayerConnection
import app.lyrify.player.PlayerExtras
import app.lyrify.player.PlayerGraph
import app.lyrify.player.PlayerUi
import app.lyrify.player.SEMITONES
import app.lyrify.player.SLEEP_CUSTOM_MINUTES
import app.lyrify.player.SLEEP_CUSTOM_SONGS
import app.lyrify.player.SLEEP_MINUTES
import app.lyrify.player.SLEEP_SONGS
import app.lyrify.player.SPEEDS
import app.lyrify.player.SPEED_STEPS_PER_X
import app.lyrify.player.SleepTimer
import app.lyrify.player.SongMark
import app.lyrify.player.SongState
import app.lyrify.player.Speed
import app.lyrify.player.abPart
import app.lyrify.player.sleepAfter
import app.lyrify.player.sleepFor
import app.lyrify.player.sleepLeftMs
import app.lyrify.player.pickedSource
import app.lyrify.player.toSpot
import app.lyrify.settings.LibrarySettings
import app.lyrify.stats.StatsGraph
import app.lyrify.stats.Target
import app.lyrify.stats.TargetKind
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// The player's extras (milestone P2), their sheets and actions: the sleep timer's sheets, the Speed sheet, Repeat a
// part on and off, the bookmark's note dialog and the bookmarks lists (the player's, the song sheet's, and those of Tools
// and a book's sheet), Play on, the player's Add to, Song info and
// Make a tone (the library's sheets), and the toasts the service sends. What shows on the player itself is in PlayerChips.kt; player/PlaybackExtras.kt and
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

/** The song playing as the library knows it, its id hint too. */
internal fun PlayerUi.ref(): SongRef = song?.ref ?: item.song

// What the player opens

/** The extras' sheets and dialogs: one at a time. [BOOKMARK] is the note of the bookmark just added, [BOOKMARKS] the list. */
enum class ExtrasSheet { SLEEP, SLEEP_MINUTES, SLEEP_SONGS, SPEED, BOOKMARK, BOOKMARKS }

/**
 * The player's extras on screen: which sheet is open, and the actions of P2's routes ([route]). Add to, Song info, Make a
 * tone, Cut and Move to trash open the library's sheets ([songSheets], L1's one door, drawn over the player by
 * [PlayerExtrasHost]). The state lives in [PlayerExtras]; the service applies it. Main thread.
 */
@Stable
class PlayerExtrasUi internal constructor(
    val extras: PlayerExtras,
    val songSheets: SongSheets,
    private val connection: PlayerConnection,
    private val context: Context,
    private val toasts: ToastState,
    private val resources: Resources,
    private val scope: CoroutineScope,
) {
    var open: ExtrasSheet? by mutableStateOf(null)

    /** The bookmark just added whose note the dialog writes. */
    var noting: SongMark? by mutableStateOf(null)
        private set

    /**
     * P2's routes of the player's "⋯" and the sleep chip, Share's, and Move to trash (the song sheet's own dialogs, then
     * the phone's: the song playing gives way to the next once it is in the trash); false for a route it does not fill.
     * [names]: "Show file names"; [lyrics]: what the song's lyrics can be shared as.
     */
    fun route(route: PlayerRoute, ui: PlayerUi, names: LibrarySettings, lyrics: LyricsShare?): Boolean {
        val song = ui.song?.ref ?: ui.item.song
        when (route) {
            PlayerRoute.ADD_TO -> songSheets.show(OpenSheet.AddTo(listOf(song), null, ui.song?.let { displayTitle(it, names) } ?: song.path.substringAfterLast('/')))
            PlayerRoute.SONG_INFO -> {
                StatsGraph.interactions(context).opened(Target(TargetKind.SONG, song.path))
                songSheets.show(OpenSheet.Info(song))
            }
            PlayerRoute.MAKE_TONE -> songSheets.show(OpenSheet.MakeTone(song))
            PlayerRoute.CUT -> songSheets.show(OpenSheet.Cut(song))
            PlayerRoute.SHARE -> scope.launch { toasts.say(resources, shareSongs(context, listOfNotNull(ui.song)) { displayTitle(it, names) }) }
            PlayerRoute.SHARE_LYRICS -> lyrics?.let { songSheets.show(OpenSheet.ShareLyrics(song, it == LyricsShare.SYNCED)) }
            PlayerRoute.SEND -> songSheets.show(OpenSheet.SendTo(listOf(song), CardKind.SONG))
            PlayerRoute.MOVE_TO_TRASH -> ui.song?.let { songSheets.show(OpenSheet.Trash(listOf(it.ref))) }
            PlayerRoute.SPEED -> open = ExtrasSheet.SPEED
            PlayerRoute.SLEEP -> open = ExtrasSheet.SLEEP
            PlayerRoute.REPEAT_PART -> togglePart(ui)
            PlayerRoute.PLAY_ON -> if (!playOn(context)) say(Toast(resources.getString(R.string.play_on_none), R.drawable.ic_speaker))
            PlayerRoute.BOOKMARK -> addBookmark(ui)
            PlayerRoute.BOOKMARKS -> open = ExtrasSheet.BOOKMARKS
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

    /**
     * A bookmark at the spot playing, kept at once: two taps from the player (its "⋯", Bookmark). Its toast's Add note
     * opens the note dialog for it ([noting]); the bookmarks list removes one added by mistake.
     */
    fun addBookmark(ui: PlayerUi) {
        val song = ui.ref()
        val mark = extras.edit { it.withBookmark(song, ui.positionAt(SystemClock.elapsedRealtime()), "", System.currentTimeMillis()) }
            ?: return say(Toast(resources.getString(if (extras.ready.value) R.string.bookmark_full else R.string.bookmark_not_saved), R.drawable.ic_error, Tone.ERROR))
        scope.launch {
            val note = toasts.show(Toast(resources.getString(R.string.bookmark_added, clock(mark.positionMs)), R.drawable.ic_bookmark_add, action = resources.getString(R.string.bookmark_add_note)))
            if (note) {
                noting = SongMark(song, mark)
                open = ExtrasSheet.BOOKMARK
            }
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
    val songSheets = rememberSongSheets()
    return remember(context, toasts, resources, scope, songSheets) {
        PlayerExtrasUi(PlayerExtras.of(context), songSheets, PlayerGraph.connection(context), context, toasts, resources, scope)
    }
}

/** The sheet or dialog [extras] has open over the player, P2's or the library's. */
@Composable
fun PlayerExtrasHost(extras: PlayerExtrasUi, ui: PlayerUi) {
    when (extras.open) {
        ExtrasSheet.SLEEP -> SleepSheet(extras, ui)
        ExtrasSheet.SLEEP_MINUTES -> CustomSleepSheet(extras, ui, songs = false)
        ExtrasSheet.SLEEP_SONGS -> CustomSleepSheet(extras, ui, songs = true)
        ExtrasSheet.SPEED -> SpeedSheet(extras, ui)
        ExtrasSheet.BOOKMARK -> extras.noting?.let { at ->
            BookmarkNoteDialog(at.mark, onDismiss = extras::close) { note ->
                extras.close()
                extras.extras.change { it.withNote(at.song.path, at.mark.id, note) }
            }
        }
        ExtrasSheet.BOOKMARKS -> BookmarksSheet(extras, ui)
        null -> Unit
    }
    SongSheetsHost(extras.songSheets)
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
@Composable
private fun SleepSheet(extras: PlayerExtrasUi, ui: PlayerUi) {
    val timer = extras.extras.sleep.collectAsStateWithLifecycle().value
    val footer: (@Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit)? = timer?.let {
        { LyrifyButton(stringResource(R.string.sleep_turn_off), extras::sleepOff, Modifier.fillMaxWidth(), kind = ButtonKind.SECONDARY, icon = R.drawable.ic_timer_off) }
    }
    LyrifySheet(onDismiss = extras::close, footer = footer) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(painterResource(R.drawable.ic_snooze), contentDescription = null, tint = LocalBrandColors.current.highlight, modifier = Modifier.size(26.dp))
            Text(stringResource(R.string.sleep_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        }
        if (timer != null) SleepReadout(timer, ui)
        Text(stringResource(R.string.sleep_sentence), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SectionTitle(stringResource(R.string.sleep_after_time))
        val byTime = timer as? SleepTimer.ByTime
        // The prototype's .srow2.t5: the times and Custom in five equal capsules.
        OptionRow(
            count = SLEEP_MINUTES.size + 1,
            chosen = { at -> if (at == SLEEP_MINUTES.size) byTime?.custom == true else byTime?.let { !it.custom && it.minutes == SLEEP_MINUTES[at] } == true },
            onPick = { at -> if (at == SLEEP_MINUTES.size) extras.open = ExtrasSheet.SLEEP_MINUTES else extras.setSleep(sleepFor(SLEEP_MINUTES[at], SystemClock.elapsedRealtime()), ui) },
        ) { at ->
            val minutes = SLEEP_MINUTES.getOrNull(at)
            Text(
                when (minutes) {
                    null -> stringResource(R.string.sleep_custom)
                    60 -> stringResource(R.string.sleep_hour)
                    else -> stringResource(R.string.sleep_minutes_chip, minutes)
                },
            )
        }
        SectionTitle(stringResource(R.string.sleep_after_songs))
        val bySongs = timer as? SleepTimer.BySongs
        // The prototype's .srow2.s4: "End of song" wider (1.55 to 1), then the counts and Custom.
        OptionRow(
            count = SLEEP_SONGS.size + 1,
            weights = listOf(END_OF_SONG_SHARE) + List(SLEEP_SONGS.size) { 1f },
            chosen = { at -> if (at == SLEEP_SONGS.size) bySongs?.custom == true else bySongs?.let { !it.custom && it.songs == SLEEP_SONGS[at] } == true },
            onPick = { at -> if (at == SLEEP_SONGS.size) extras.open = ExtrasSheet.SLEEP_SONGS else extras.setSleep(sleepAfter(SLEEP_SONGS[at]), ui) },
        ) { at ->
            val songs = SLEEP_SONGS.getOrNull(at)
            Text(
                when (songs) {
                    null -> stringResource(R.string.sleep_custom)
                    1 -> stringResource(R.string.sleep_end_of_song)
                    else -> pluralStringResource(R.plurals.sleep_songs_chip, songs, songs)
                },
            )
        }
        // A book: End of chapter (BooksScreen.kt).
        rememberChapterEnd(ui)?.let { chapterEnd ->
            SectionTitle(stringResource(R.string.sleep_this_book))
            OptionRow(count = 1, chosen = { timer is SleepTimer.ByChapter }, onPick = { extras.setSleep(chapterEnd(), ui) }) { Text(stringResource(R.string.sleep_end_of_chapter)) }
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
    val running = extras.extras.sleep.collectAsStateWithLifecycle().value
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
        Text(stringResource(if (songs) R.string.sleep_custom_songs else R.string.sleep_custom_time), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
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
                Text(unit, style = body(15f, 20, 500), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
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
@Composable
private fun SpeedSheet(extras: PlayerExtrasUi, ui: PlayerUi) {
    val store = extras.extras
    val state = store.state.collectAsStateWithLifecycle().value
    val path = ui.path()
    val usualAtOpen = remember { store.state.value.usual }
    // A book plays at its own speed, kept with the book (BooksScreen.kt).
    val book = rememberBookSpeed(ui)
    val speed = book?.speed ?: state.speedOf(path)
    val own = path in state.speeds
    var sliding by remember { mutableStateOf<Float?>(null) }
    var pitching by remember { mutableStateOf<Int?>(null) }
    val shown = speed.copy(speed = sliding ?: speed.speed, semitones = pitching ?: speed.semitones)
    val set: ((Speed) -> Speed) -> Unit = book?.set ?: { change -> store.change { it.withSpeed(ui.ref(), change) } }
    LyrifySheet(
        onDismiss = extras::close,
        footer = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                LyrifyButton(stringResource(R.string.speed_reset), { set { Speed() } }, Modifier.weight(1f), kind = ButtonKind.SECONDARY, enabled = speed != Speed())
                LyrifyButton(stringResource(R.string.speed_done), extras::close, Modifier.weight(1f))
            }
        },
    ) {
        Text(stringResource(R.string.speed_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.speed_value, speedNumber(shown.speed)), style = MaterialTheme.typography.displaySmall.tabular().copy(fontWeight = FontWeight.SemiBold))
            val pitch = when {
                shown.keepPitch -> stringResource(R.string.speed_pitch_kept)
                shown.semitones != 0 -> stringResource(R.string.speed_pitch_moved, semitonesText(shown.semitones))
                else -> stringResource(R.string.speed_pitch_follows)
            }
            Text(pitch, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
        }
        // The prototype's .spdchips: six equal 44 dp capsules.
        OptionRow(count = SPEEDS.size, height = 44.dp, chosen = { at -> shown.speed == SPEEDS[at] }, onPick = { at -> set { it.copy(speed = SPEEDS[at]) } }) { at ->
            Text(stringResource(R.string.speed_value, speedNumber(SPEEDS[at])), style = OptionText.tabular())
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
        if (book != null) {
            Text(stringResource(R.string.speed_book_sentence), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@LyrifySheet
        }
        SheetSwitch(R.drawable.ic_music_note, stringResource(R.string.speed_this_song), stringResource(if (own) R.string.speed_this_song_on else R.string.speed_this_song_off), own) { on ->
            store.change { it.thisSongOnly(ui.ref(), on, usualAtOpen) }
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

/** "Bookmark at 1:02" with its note to write or change ([mark]'s own to start), the keyboard up at once. */
@Composable
private fun BookmarkNoteDialog(mark: Bookmark, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var note by rememberSaveable(mark.id) { mutableStateOf(mark.note) }
    val focus = remember { FocusRequester() }
    FocusOnOpen(focus, opens = 1)
    val save = { onSave(note) }
    ConfirmDialog(
        title = stringResource(R.string.bookmark_at, clock(mark.positionMs)),
        confirm = stringResource(R.string.bookmark_save),
        dismiss = stringResource(R.string.bookmark_cancel),
        onConfirm = save,
        onDismiss = onDismiss,
        icon = R.drawable.ic_bookmark_add,
        destructive = false,
        body = {
            LyrifyField(
                note,
                { note = it.take(SongState.MAX_NOTE) },
                Modifier.fillMaxWidth().focusRequester(focus),
                label = stringResource(R.string.bookmark_note),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
            )
        },
    )
}

/** The bookmarks of what plays ([PlayerUi]): the song's, or every part's of the book it is a part of ([SongState.marksOf]). */
@Composable
internal fun rememberPlayingMarks(ui: PlayerUi): List<SongMark> {
    val context = LocalContext.current
    val extras = remember(context) { PlayerExtras.of(context) }
    val state by extras.state.collectAsStateWithLifecycle()
    val path = ui.path()
    return remember(state, path, ui.library) { state.marksOf(path, ui.library?.bookOf(path)) }
}

/** The player's "Bookmarks": what plays has ([rememberPlayingMarks]), each playing from its spot. */
@Composable
private fun BookmarksSheet(extras: PlayerExtrasUi, ui: PlayerUi) {
    val context = LocalContext.current
    val marks = rememberPlayingMarks(ui)
    LyrifySheet(onDismiss = extras::close, spacing = 4.dp, padding = 12.dp) {
        Text(stringResource(R.string.bookmarks_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp).semantics { heading() })
        // The last one removed: the sheet stays open and says so, its Undo on screen.
        if (marks.isEmpty()) Text(stringResource(R.string.tools_bookmarks_none), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
        BookmarkList(marks, ui.library?.bookOf(ui.path())) { at ->
            extras.close()
            jumpTo(context, at.song, at.mark.positionMs)
        }
    }
}

/**
 * A song's bookmarks in its song sheet (L1's slot): each plays the song from there, inside the queue playing (a book's
 * file: its book's queue). Nothing shows for a song without bookmarks. [close] closes the sheet.
 */
@Composable
fun SongBookmarks(song: SongRef, close: () -> Unit) {
    val context = LocalContext.current
    val extras = remember(context) { PlayerExtras.of(context) }
    val marks = extras.state.collectAsStateWithLifecycle().value.marksOf(song.path, null)
    if (marks.isEmpty()) return
    Column {
        SectionTitle(stringResource(R.string.bookmarks_title), Modifier.padding(bottom = 4.dp))
        BookmarkList(marks, null) { at ->
            close()
            jumpTo(context, at.song, at.mark.positionMs)
        }
    }
}

/**
 * Bookmarks as a list ([marks]: a song's, or a [book]'s parts' in the book's order, a folder book's part named under
 * the note): a tap plays from the spot ([onPlay]); the pencil changes the note in the note dialog; × removes it, with
 * Undo, the bookmark back as it was.
 */
@Composable
internal fun BookmarkList(marks: List<SongMark>, book: Book?, onPlay: (SongMark) -> Unit) {
    val context = LocalContext.current
    val toasts = LocalToasts.current
    val resources = LocalResources.current
    // The toast outlives the sheet or page the bookmark was removed in: it runs in the activity's scope, not theirs.
    val scope = LocalLifecycleOwner.current.lifecycleScope
    val extras = remember(context) { PlayerExtras.of(context) }
    var editing by rememberSaveable { mutableStateOf<Long?>(null) }
    marks.forEach { at ->
        key(at.mark.id) {
            BookmarkRow(
                at.mark,
                part = book?.takeIf { it.kind == BookKind.FOLDER }?.parts?.firstOrNull { it.path == at.song.path }?.let(::partTitle),
                onPlay = { onPlay(at) },
                onEdit = { editing = at.mark.id },
                onRemove = {
                    val path = at.song.path
                    extras.change { it.withoutBookmark(path, at.mark.id) }
                    scope.launch {
                        val undo = toasts.show(Toast(resources.getString(R.string.bookmark_removed), R.drawable.ic_bookmark, action = resources.getString(R.string.player_undo)))
                        if (undo) extras.change { state -> state.withBookmark(at.song, at.mark.positionMs, at.mark.note, at.mark.addedAt)?.first ?: state }
                    }
                },
            )
        }
    }
    marks.firstOrNull { it.mark.id == editing }?.let { at ->
        BookmarkNoteDialog(at.mark, onDismiss = { editing = null }) { note ->
            editing = null
            extras.change { it.withNote(at.song.path, at.mark.id, note) }
        }
    }
}

/** A bookmark: its time and note (a folder book's [part] under it), one TalkBack stop that plays it; the pencil and ×. */
@Composable
private fun BookmarkRow(mark: Bookmark, part: String?, onPlay: () -> Unit, onEdit: () -> Unit, onRemove: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val time = clock(mark.positionMs)
    val note = mark.note.ifEmpty { stringResource(R.string.bookmark_no_note) }
    val play = listOfNotNull(stringResource(R.string.bookmark_play_from, time, note), part).joinToString(", ")
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
            // An hour or more in, as podcasts and books often are, the time takes the room it needs.
            Text(time, style = MaterialTheme.typography.bodyLarge.tabular(), modifier = Modifier.widthIn(min = 56.dp))
            Column(Modifier.weight(1f)) {
                Text(note, style = MaterialTheme.typography.bodyLarge, color = if (mark.note.isEmpty()) colors.onSurfaceVariant else colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                part?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
        ChromeIconButton(R.drawable.ic_edit, stringResource(R.string.bookmark_edit, time), onEdit, glass = false)
        ChromeIconButton(R.drawable.ic_close, stringResource(R.string.bookmark_remove, time), onRemove, glass = false)
    }
}

/**
 * A bookmark's jump: [song] plays from [positionMs] inside the queue playing (the song playing: a seek; a song not in
 * the queue: Play next first), and it does not also resume at a place kept for it. A book's file plays its book's own
 * queue from there instead ([toBookmark]), as a book always does, also while it plays: as music, reached by playing on,
 * it goes to its book; in its book's queue that is a seek too.
 */
internal fun jumpTo(context: Context, song: SongRef, positionMs: Long) {
    val connection = PlayerGraph.connection(context)
    val library = connection.library.value
    if (connection.ui.value?.path() == song.path && library?.bookOf(song.path) == null) {
        connection.seekTo(positionMs)
        connection.play()
        return
    }
    val core = PlayerGraph.core(context)
    PlayerExtras.of(context).jumpingTo = song.path
    core.switch { it.toBookmark(song.path, positionMs, library, core.now()) ?: it.toSpot(song, positionMs, pickedSource(""), core.now()) }
    connection.play()
}

// The toasts the service sends

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
    PlayerExtras.of(context).change { it.withPlace(SongRef(path, it.ids[path]), null) }
}

/**
 * One choice of a few as equal capsules in a row (the prototype's .opt grid): [height] tall, 6 dp apart, shared by
 * [weights]; the chosen one ([chosen]) on the brand's container with a coral ring and semibold words, changing over 0.12 s.
 */
@Composable
private fun OptionRow(
    count: Int,
    chosen: (Int) -> Boolean,
    onPick: (Int) -> Unit,
    height: Dp = 48.dp,
    weights: List<Float> = List(count) { 1f },
    label: @Composable (Int) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val brand = LocalBrandColors.current
    val reduced = LocalReducedMotion.current
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().selectableGroup()) {
        repeat(count) { at ->
            val on = chosen(at)
            val fill by animateColorAsState(if (on) brand.highlightContainer else colors.surfaceContainerHigh, motion(tween(Motion.QUICK_MS), reduced), label = "option")
            val ring by animateColorAsState(if (on) brand.highlight else Color.Transparent, motion(tween(Motion.QUICK_MS), reduced), label = "option ring")
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(weights[at])
                    .height(height)
                    .clip(CircleShape)
                    .selectable(on, role = Role.RadioButton) { onPick(at) }
                    .background(fill, CircleShape)
                    .border(1.5.dp, ring, CircleShape)
                    .padding(horizontal = 2.dp),
            ) {
                CompositionLocalProvider(
                    LocalContentColor provides if (on) brand.onHighlightContainer else colors.onSurface,
                    LocalTextStyle provides OptionText.copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium),
                ) { label(at) }
            }
        }
    }
}

/** An option's words (.opt): 14/20 medium. */
private val OptionText = body(14f, 20, 500)

/** "End of song" takes this much of the row against 1 for each count (.srow2.s4). */
private const val END_OF_SONG_SHARE = 1.55f
