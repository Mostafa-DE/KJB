package app.lyrify.ui

import android.content.Context
import android.text.format.DateUtils
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lyrify.R
import app.lyrify.library.Song
import app.lyrify.library.SongRef
import app.lyrify.player.PlayerUi
import app.lyrify.player.Queue
import app.lyrify.player.QueueItem
import app.lyrify.player.SourceKind
import app.lyrify.player.Suggested
import app.lyrify.player.Undo
import app.lyrify.player.sourceLabel
import app.lyrify.settings.Adventure
import app.lyrify.settings.Settings
import app.lyrify.suggestions.NOT_NOW_MS
import app.lyrify.suggestions.Reason
import app.lyrify.suggestions.Suggestions
import app.lyrify.suggestions.WhyNow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

// Suggestions in lists, the player and Up next (SG1, the approved mockup suggest/preview-suggestions-v2.html): the mark
// and the reason on a suggested song, Not now and Never with Undo, Why this?, the player's line, and Autoplay's and Smart
// shuffle's cards in Up next. The pages are in SuggestionScreens.kt.

// The mark and the reason

/** The suggestion mark: the compass, and [text] ("Suggested") in the highlight colour, before a suggested song's artist. */
@Composable
internal fun SuggestedMark(text: String, modifier: Modifier = Modifier) {
    val highlight = LocalBrandColors.current.highlight
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp), modifier = modifier) {
        Icon(painterResource(R.drawable.ic_explore), contentDescription = null, tint = highlight, modifier = Modifier.size(14.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = highlight, maxLines = 1)
    }
}

/** A suggestion's reason, the short grey line under it. */
@Composable
internal fun ReasonLine(text: String, modifier: Modifier = Modifier, center: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = if (center) TextAlign.Center else null,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.padding(top = 2.dp),
    )
}

/** The mark on the corner of the Shuffle button while Smart shuffle is on: the compass on a small disc. */
@Composable
internal fun SuggestionBadge(modifier: Modifier = Modifier) {
    Box(contentAlignment = Alignment.Center, modifier = modifier.size(14.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface)) {
        Icon(painterResource(R.drawable.ic_explore), contentDescription = null, tint = LocalBrandColors.current.highlight, modifier = Modifier.size(12.dp))
    }
}

/** The month [month] in words ("October"), with its year when it is not this year's. */
private fun monthWords(context: Context, month: YearMonth): String =
    DateUtils.formatDateTime(context, month.atDay(MID_MONTH).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(), DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NO_MONTH_DAY)

private fun monthOf(at: Long): YearMonth = YearMonth.from(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()))

/** The day [at] fell on ("22 November"), with its year when it is not this year's. */
internal fun dayWords(context: Context, at: Long): String = DateUtils.formatDateTime(context, at, DateUtils.FORMAT_SHOW_DATE)

private const val MID_MONTH = 15

/** [reason] in plain words, as the grey line under a suggested song says it ("Often follows Copper Sky"); songs by [title]. */
internal fun reasonWords(context: Context, reason: Reason, title: (Song) -> String = Song::title): String {
    val res = context.resources
    return when (reason) {
        is Reason.Picked -> res.getQuantityString(R.plurals.suggest_reason_picked, reason.times, reason.times, monthWords(context, reason.month))
        is Reason.Played -> res.getQuantityString(R.plurals.suggest_reason_played, reason.times, reason.times, monthWords(context, reason.month))
        is Reason.NotSince -> res.getString(R.string.suggest_reason_not_since, monthWords(context, monthOf(reason.lastPlayedAt)))
        is Reason.Follows -> res.getString(R.string.suggest_reason_follows, title(reason.song))
        is Reason.Together -> res.getString(R.string.suggest_reason_together)
        is Reason.NewFrom -> res.getString(R.string.suggest_reason_new_from, reason.artist)
        is Reason.LikeArtist -> res.getString(R.string.suggest_reason_like_artist, reason.artist)
        is Reason.Friends -> res.getQuantityString(R.plurals.suggest_reason_friends, reason.count, reason.count)
        Reason.FriendsThisWeek -> res.getString(R.string.suggest_reason_friends_week)
        Reason.HeardToEnd -> res.getString(R.string.suggest_reason_heard_to_end)
        Reason.Favourite -> res.getString(R.string.suggest_reason_favourite)
        is Reason.Added -> {
            val added = reason.addedAt?.let { monthWords(context, monthOf(it)) }
            when {
                added == null && reason.plays == 0 -> res.getString(R.string.suggest_reason_not_played)
                added == null -> res.getQuantityString(R.plurals.suggest_reason_played_times, reason.plays, reason.plays)
                reason.plays == 0 -> res.getString(R.string.suggest_reason_added_new, added)
                else -> res.getQuantityString(R.plurals.suggest_reason_added, reason.plays, reason.plays, added)
            }
        }
        is Reason.SameArtist -> res.getString(if (reason.sameAlbum) R.string.suggest_reason_same_album else R.string.suggest_reason_same_artist)
        is Reason.SameGenre -> if (reason.sameYears) res.getString(R.string.suggest_reason_genre_years, reason.genre) else reason.genre
        Reason.SameYears -> res.getString(R.string.suggest_reason_same_years)
        is Reason.Skipped -> res.getQuantityString(R.plurals.suggest_reason_skipped, reason.times, reason.times, dayWords(context, reason.lastAt))
    }
}

/** The reason [song] is suggested right [after] a song, in words; null while it is worked out, or with Show reasons off. */
@Composable
internal fun rememberReason(song: Song, after: Song?): String? {
    val context = LocalContext.current
    val suggestions = rememberSuggestions()
    val shown = rememberSettings().suggestions.showReasons
    val reason by produceState<Reason?>(null, song, after, shown) { if (shown) value = suggestions.reasons(listOf(song to after)).firstOrNull() }
    return reason?.takeIf { shown }?.let { reasonWords(context, it) }
}

/**
 * Not now (30 days) or Never on [song], then its message with Undo, which lets the song back and takes back [taken] (a
 * queue's suggestion Not now took out with it). A choice that could not be saved says so. Launched on a scope that
 * outlives the sheet or row it came from, so the choice and its Undo are never cut short.
 */
internal suspend fun ToastState.hold(context: Context, suggestions: Suggestions, song: SongRef, never: Boolean, taken: Undo? = null) {
    val res = context.resources
    if (!(if (never) suggestions.never(song) else suggestions.notNow(song))) {
        taken?.undo()
        show(Toast(res.getString(R.string.suggest_not_saved), R.drawable.ic_sync_problem, Tone.ERROR))
        return
    }
    val text = if (never) res.getString(R.string.suggest_nevered) else res.getString(R.string.suggest_snoozed, dayWords(context, System.currentTimeMillis() + NOT_NOW_MS))
    if (!show(Toast(text, if (never) R.drawable.ic_block else R.drawable.ic_snooze, action = res.getString(R.string.player_undo)))) return
    // The queue gets its song back only when the song is really allowed again.
    if (suggestions.allow(song)) taken?.undo() else show(Toast(res.getString(R.string.suggest_not_saved), R.drawable.ic_sync_problem, Tone.ERROR))
}

// Why this?

/** "Autoplay", "Smart shuffle", or nothing: where a suggestion in a queue came from. */
@Composable
private fun byWords(by: Suggested?): String? = when (by) {
    Suggested.AUTOPLAY -> stringResource(R.string.suggest_autoplay)
    Suggested.SMART_SHUFFLE -> stringResource(R.string.set_smart_shuffle)
    null -> null
}

/** [adventure]'s sentence: how the picks lean. */
@Composable
internal fun mixWords(adventure: Adventure): String = stringResource(
    when (adventure) {
        Adventure.FAMILIAR -> R.string.suggest_mix_familiar
        Adventure.MIX -> R.string.suggest_mix_mix
        Adventure.DISCOVER -> R.string.suggest_mix_discover
    },
)

/** Familiar, Mix and Discover: one setting, in Settings and in Why this?; a tap in either moves both. */
@Composable
internal fun AdventureChoice(adventure: Adventure, modifier: Modifier = Modifier) {
    val store = rememberSettingsStore()
    val scope = rememberCoroutineScope()
    val names = listOf(stringResource(R.string.adventure_familiar), stringResource(R.string.adventure_mix), stringResource(R.string.adventure_discover))
    LyrifySegmented(names, Adventure.entries.indexOf(adventure), { at ->
        scope.launch { store.update { it.copy(suggestions = it.suggestions.copy(adventure = Adventure.entries[at])) } }
    }, modifier)
}

/**
 * Why this? for [song], right [after] a song when it came after one, from [by] in a queue: what counted (and what counted
 * against it), the mix right now with Familiar, Mix and Discover, when it can come back, and Not now and Never, which
 * close it and go to [onHold] (the caller's, whose scope outlives the sheet: [hold]).
 */
@Composable
internal fun WhySheet(song: Song, after: Song?, by: Suggested?, onDismiss: () -> Unit, onHold: (never: Boolean) -> Unit) {
    val context = LocalContext.current
    val suggestions = rememberSuggestions()
    val adventure = rememberSettings().suggestions.adventure
    val now by produceState<WhyNow?>(null, song, after, adventure) { value = suggestions.why(song, after) }
    val hold = { never: Boolean ->
        onDismiss()
        onHold(never)
    }
    val colors = MaterialTheme.colorScheme
    LyrifySheet(
        onDismiss = onDismiss,
        footer = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                LyrifyButton(stringResource(R.string.suggest_not_now), { hold(false) }, Modifier.weight(1f), kind = ButtonKind.SECONDARY, icon = R.drawable.ic_snooze)
                LyrifyButton(stringResource(R.string.suggest_never), { hold(true) }, Modifier.weight(1f), kind = ButtonKind.SECONDARY, icon = R.drawable.ic_block)
            }
        },
    ) {
        Column {
            Text(stringResource(R.string.suggest_why), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            val from = byWords(by)
            Text(if (from != null) stringResource(R.string.suggest_why_from, song.title, from) else song.title, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        }
        SectionTitle(stringResource(R.string.suggest_why_counted))
        val why = now?.why
        if (why != null && why.reasons.isEmpty() && why.against.isEmpty()) Text(stringResource(R.string.suggest_why_nothing), style = MaterialTheme.typography.bodyMedium)
        why?.let { (reasons, against) ->
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                (reasons.map { it to false } + against.map { it to true }).forEach { (reason, minus) ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Tile(reasonIcon(reason), if (minus) colors.errorContainer else colors.surfaceContainerHighest, if (minus) colors.onErrorContainer else colors.onSurfaceVariant, size = 32.dp)
                        Text(reasonWords(context, reason), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(colors.surfaceContainerHigh).padding(16.dp),
        ) {
            Text(stringResource(R.string.suggest_mix_now), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Text(mixWords(adventure), style = MaterialTheme.typography.bodyMedium)
            AdventureChoice(adventure)
            now?.mix?.let { Text(stringResource(R.string.suggest_mix_share, it.oneIn), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
        }
        Text(stringResource(R.string.suggest_why_fresh), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
    }
}

/** Each reason's icon in Why this?. */
private fun reasonIcon(reason: Reason): Int = when (reason) {
    is Reason.Picked, is Reason.Played -> R.drawable.ic_bar_chart
    is Reason.NotSince -> R.drawable.ic_history
    is Reason.Follows, is Reason.Together -> R.drawable.ic_queue_music
    is Reason.NewFrom, is Reason.LikeArtist, is Reason.SameArtist -> R.drawable.ic_person
    is Reason.Friends, Reason.FriendsThisWeek -> R.drawable.ic_favorite
    Reason.HeardToEnd -> R.drawable.ic_check
    Reason.Favourite -> R.drawable.ic_favorite_fill
    is Reason.Added -> R.drawable.ic_schedule
    is Reason.SameGenre, Reason.SameYears -> R.drawable.ic_album
    is Reason.Skipped -> R.drawable.ic_skip_next_fill
}

// The player and Up next

/**
 * Under the player's title, for a suggestion ([Queue.suggests]: not a song the user added to a suggested list): its
 * reason in grey ([showReasons]) and the pill that opens Why this? ("Suggested by Autoplay · Why this?").
 */
@Composable
internal fun SuggestedLine(ui: PlayerUi, showReasons: Boolean) {
    val context = LocalContext.current
    val toasts = LocalToasts.current
    val suggestions = rememberSuggestions()
    // Not now and Never from Why this? finish here, on the player's scope, after the sheet has gone.
    val scope = rememberCoroutineScope()
    val by = ui.item.suggested
    val song = ui.song ?: return
    if (!ui.queue.suggests(ui.item)) return
    val before = ui.queue.items.getOrNull(ui.queue.items.indexOfFirst { it.uid == ui.item.uid } - 1)
    var why by remember(ui.item.uid) { mutableStateOf(false) }
    val after = before?.let { ui.library?.resolver?.find(it.song) }
    val reason = if (showReasons) rememberReason(song, after) else null
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        if (reason != null) ReasonLine(reason, center = true)
        val mark = when (by) {
            Suggested.AUTOPLAY -> stringResource(R.string.suggest_by_autoplay)
            Suggested.SMART_SHUFFLE -> stringResource(R.string.suggest_by_smart)
            null -> stringResource(R.string.suggest_mark)
        }
        Box(contentAlignment = Alignment.Center, modifier = Modifier.heightIn(min = TOUCH).clip(CircleShape).clickable(role = Role.Button) { why = true }) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.clip(CircleShape).background(LocalBrandColors.current.highlightContainer).padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Icon(painterResource(R.drawable.ic_explore), contentDescription = null, tint = LocalBrandColors.current.onHighlightContainer, modifier = Modifier.size(16.dp))
                Text(stringResource(R.string.suggest_pill, mark), style = MaterialTheme.typography.labelLarge, color = LocalBrandColors.current.onHighlightContainer, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    if (why) WhySheet(song, after, by, onDismiss = { why = false }) { never -> scope.launch { toasts.hold(context, suggestions, song.ref, never) } }
}

/**
 * As Autoplay's first song comes on after the queue's end, the message says so, with Turn off: once per song, wherever
 * KJB is in view (the shell keeps this, [rememberOwnNow]), only for a song that has just come on.
 */
@Composable
internal fun AutoplayMessage(ui: PlayerUi?) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val toasts = LocalToasts.current
    val store = rememberSettingsStore()
    // The song already told about, kept over a turn of the phone so the message never comes twice.
    var told by rememberSaveable { mutableStateOf<Long?>(null) }
    LaunchedEffect(ui?.item?.uid) {
        val now = ui ?: return@LaunchedEffect
        val before = now.queue.items.getOrNull(now.queue.items.indexOfFirst { it.uid == now.item.uid } - 1)
        if (now.item.suggested != Suggested.AUTOPLAY || before == null || before.suggested == Suggested.AUTOPLAY) return@LaunchedEffect
        if (now.item.uid == told || now.positionMs > TOOK_OVER_MS) return@LaunchedEffect
        told = now.item.uid
        val name = now.queue.name ?: sourceLabel(context, now.queue.source, now.queue.items.size)
        if (toasts.show(Toast(resources.getString(R.string.suggest_autoplay_took_over, name), R.drawable.ic_explore, action = resources.getString(R.string.suggest_turn_off)))) {
            store.update { it.copy(suggestions = it.suggestions.copy(autoplay = false)) }
        }
    }
}

/** Autoplay's message shows only for a song that has just come on. */
private const val TOOK_OVER_MS = 5_000L

/**
 * Where Autoplay's card goes among [shown] (the queue's songs in the order shown): before the first song Autoplay put
 * there; at the end while none is there yet and the queue's last song is near; null in a book's queue, and otherwise.
 */
fun autoplayCut(queue: Queue, shown: List<QueueItem>): Int? {
    if (queue.source.kind == SourceKind.BOOK || shown.isEmpty()) return null
    val first = shown.indexOfFirst { it.suggested == Suggested.AUTOPLAY }
    return when {
        first >= 0 -> first
        queue.index >= queue.items.lastIndex - 1 -> shown.size
        else -> null
    }
}

/** A card of Up next: its [icon] (with the suggestion [badge]), title, [line] and a [trailing] control. */
@Composable
private fun UpNextCard(icon: Int, badge: Boolean, title: String, line: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    val brand = LocalBrandColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.padding(horizontal = ScreenPadding, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(colors.surfaceContainerHigh).padding(12.dp),
    ) {
        Box {
            Tile(icon, brand.highlightContainer, brand.onHighlightContainer, size = 40.dp)
            if (badge) SuggestionBadge(Modifier.align(Alignment.BottomEnd))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(line, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        trailing?.invoke()
    }
}

/** Up next's first card while Smart shuffle is on: about 1 song in so many is a suggestion, and how fresh it stays. */
@Composable
internal fun SmartShuffleCard() {
    val suggestions = rememberSuggestions()
    val mix by produceState<Int?>(null) { value = suggestions.mix().oneIn }
    UpNextCard(R.drawable.ic_shuffle, badge = true, stringResource(R.string.suggest_smart_card), stringResource(R.string.suggest_smart_card_line, mix ?: DEFAULT_ONE_IN))
}

private const val DEFAULT_ONE_IN = 3

/** Where [queue]'s own songs end: the line, then Autoplay's card with its switch, one TalkBack stop like a Settings switch. */
@Composable
internal fun AutoplayCard(queue: Queue) {
    val context = LocalContext.current
    val store = rememberSettingsStore()
    val scope = rememberCoroutineScope()
    val on = rememberSettings().suggestions.autoplay
    val name = queue.name ?: sourceLabel(context, queue.source, queue.items.size)
    Column {
        Text(
            stringResource(R.string.suggest_ends_here, name),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = ScreenPadding, vertical = 8.dp),
        )
        UpNextCard(
            R.drawable.ic_explore,
            badge = false,
            stringResource(R.string.suggest_autoplay),
            stringResource(if (on) R.string.suggest_autoplay_card else R.string.suggest_autoplay_card_off, name),
            Modifier.toggleable(on, role = Role.Switch) { value -> scope.launch { store.update { it.copy(suggestions = it.suggestions.copy(autoplay = value)) } } },
        ) { LyrifySwitch(on) }
    }
}
