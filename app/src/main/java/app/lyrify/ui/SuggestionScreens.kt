package app.lyrify.ui

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lyrify.R
import app.lyrify.library.Library
import app.lyrify.library.Song
import app.lyrify.library.SongRef
import app.lyrify.player.PlayerUi
import app.lyrify.player.Queue
import app.lyrify.player.QueueItem
import app.lyrify.player.QueueSource
import app.lyrify.player.SourceKind
import app.lyrify.player.Suggested
import app.lyrify.player.Undo
import app.lyrify.player.sourceLabel
import app.lyrify.settings.Adventure
import app.lyrify.settings.Settings
import app.lyrify.settings.SettingsSection
import app.lyrify.settings.SuggestionSettings
import app.lyrify.suggestions.Basis
import app.lyrify.suggestions.HealthNow
import app.lyrify.suggestions.MoreLike
import app.lyrify.suggestions.NOT_NOW_MS
import app.lyrify.suggestions.Pick
import app.lyrify.suggestions.Reason
import app.lyrify.suggestions.Seed
import app.lyrify.suggestions.Suggestions
import app.lyrify.suggestions.SuggestionsGraph
import app.lyrify.suggestions.WhyNow
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.concurrent.TimeUnit

// Suggestions on screen (SG1, the approved mockup suggest/preview-suggestions-v2.html): the mark and the reason on a
// suggested song, Why this?, Autoplay's and Smart shuffle's cards in Up next, the player's line, For you (the Library
// card, its page and the Insights section), More like this, Settings > Suggestions, Never suggest and Suggestions health.

/** What More like this starts from ([Page.MoreLike]): a song by its path, an album by its key, an artist by name. */
enum class SeedKind { SONG, ALBUM, ARTIST }

/** The app's one [Suggestions]. */
@Composable
internal fun rememberSuggestions(): Suggestions {
    val context = LocalContext.current
    return remember(context) { SuggestionsGraph.suggestions(context) }
}

/** The settings now (null while they are read), for the screens here that change them. */
@Composable
private fun rememberSettings(): Settings? {
    val store = rememberSettingsStore()
    return store.settings.collectAsStateWithLifecycle(initialValue = null).value
}

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
private fun dayWords(context: Context, at: Long): String = DateUtils.formatDateTime(context, at, DateUtils.FORMAT_SHOW_DATE)

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
    val shown = rememberSettings()?.suggestions?.showReasons ?: return null
    val reason by produceState<Reason?>(null, song, after, shown) { if (shown) value = suggestions.reasons(listOf(song to after)).firstOrNull() }
    return reason?.takeIf { shown }?.let { reasonWords(context, it) }
}

/**
 * Not now (30 days) or Never on [song], then its message with Undo, which lets the song back and takes back [taken] (a
 * queue's suggestion Not now took out with it). A choice that could not be saved says so.
 */
internal suspend fun ToastState.hold(context: Context, suggestions: Suggestions, song: SongRef, never: Boolean, taken: Undo? = null) {
    val res = context.resources
    if (!(if (never) suggestions.never(song) else suggestions.notNow(song))) {
        taken?.undo()
        show(Toast(res.getString(R.string.suggest_not_saved), R.drawable.ic_sync_problem, Tone.ERROR))
        return
    }
    val text = if (never) res.getString(R.string.suggest_nevered) else res.getString(R.string.suggest_snoozed, dayWords(context, System.currentTimeMillis() + NOT_NOW_MS))
    if (show(Toast(text, if (never) R.drawable.ic_block else R.drawable.ic_snooze, action = res.getString(R.string.player_undo)))) {
        suggestions.allow(song)
        taken?.undo()
    }
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
private fun mixWords(adventure: Adventure): String = stringResource(
    when (adventure) {
        Adventure.FAMILIAR -> R.string.suggest_mix_familiar
        Adventure.MIX -> R.string.suggest_mix_mix
        Adventure.DISCOVER -> R.string.suggest_mix_discover
    },
)

/** Familiar, Mix and Discover: one setting, in Settings and in Why this?; a tap in either moves both. */
@Composable
private fun AdventureChoice(adventure: Adventure, modifier: Modifier = Modifier) {
    val store = rememberSettingsStore()
    val scope = rememberCoroutineScope()
    val names = listOf(stringResource(R.string.adventure_familiar), stringResource(R.string.adventure_mix), stringResource(R.string.adventure_discover))
    LyrifySegmented(names, Adventure.entries.indexOf(adventure), { at ->
        scope.launch { store.update { it.copy(suggestions = it.suggestions.copy(adventure = Adventure.entries[at])) } }
    }, modifier)
}

/**
 * Why this? for [song], right [after] a song when it came after one, from [by] in a queue: what counted (and what counted
 * against it), the mix right now with Familiar, Mix and Discover, when it can come back, and Not now and Never.
 */
@Composable
internal fun WhySheet(song: Song, after: Song?, by: Suggested?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val suggestions = rememberSuggestions()
    val toasts = LocalToasts.current
    val scope = rememberCoroutineScope()
    val adventure = rememberSettings()?.suggestions?.adventure ?: Adventure.MIX
    val now by produceState<WhyNow?>(null, song, after, adventure) { value = suggestions.why(song, after) }
    val hold = { never: Boolean ->
        onDismiss()
        scope.launch { toasts.hold(context, suggestions, song.ref, never) }
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
 * Under the player's title, for a suggested song: its reason in grey ([showReasons]) and the pill that opens Why this?
 * ("Suggested by Autoplay · Why this?"). As Autoplay's first song comes on after the queue's end, the message says so,
 * with Turn off.
 */
@Composable
internal fun SuggestedLine(ui: PlayerUi, showReasons: Boolean) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val toasts = LocalToasts.current
    val store = rememberSettingsStore()
    val by = ui.item.suggested
    val index = ui.queue.items.indexOfFirst { it.uid == ui.item.uid }
    val before = ui.queue.items.getOrNull(index - 1)
    LaunchedEffect(ui.item.uid) {
        if (by != Suggested.AUTOPLAY || before == null || before.suggested == Suggested.AUTOPLAY || ui.positionMs > TOOK_OVER_MS) return@LaunchedEffect
        val name = ui.queue.name ?: sourceLabel(context, ui.queue.source, ui.queue.items.size)
        if (toasts.show(Toast(resources.getString(R.string.suggest_autoplay_took_over, name), R.drawable.ic_explore, action = resources.getString(R.string.suggest_turn_off)))) {
            store.update { it.copy(suggestions = it.suggestions.copy(autoplay = false)) }
        }
    }
    val song = ui.song ?: return
    if (by == null && ui.queue.source.kind != SourceKind.SUGGESTIONS) return
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
    if (why) WhySheet(song, after, by, onDismiss = { why = false })
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
    val on = rememberSettings()?.suggestions?.autoplay ?: return
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

// For you

/** For you plays as one list, "From For you". */
@Composable
private fun forYouSource(): QueueSource = QueueSource(SourceKind.SUGGESTIONS, FOR_YOU_KEY, stringResource(R.string.suggest_for_you))

private const val FOR_YOU_KEY = "for-you"

/** Today's For you; null while it is worked out. */
@Composable
private fun rememberForYou(library: Library?, settings: SuggestionSettings?): List<Pick>? {
    val suggestions = rememberSuggestions()
    return produceState<List<Pick>?>(null, library, settings?.never, settings?.notNow, settings?.adventure) {
        if (library != null) value = suggestions.forYou()
    }.value
}

/**
 * For you's card at the top of Library (the mockup's): a mosaic, the first songs' names, Play, Shuffle and Hide (until
 * tomorrow's mix); a tap on it opens For you. Nothing while there is no mix, or the card is off or hidden.
 */
@Composable
internal fun ForYouCard(ui: LibraryUi) {
    val nav = LocalNavigator.current
    val toasts = LocalToasts.current
    val resources = LocalResources.current
    val store = rememberSettingsStore()
    val scope = rememberCoroutineScope()
    val playback = rememberPlayback()
    val settings = ui.settings.suggestions
    val picks = rememberForYou(ui.library, settings).orEmpty()
    val hidden = settings.forYouHiddenUntil?.let { System.currentTimeMillis() < it } == true
    if (!settings.forYouCard || hidden || picks.isEmpty()) return
    val refs = picks.map { it.song.ref }
    val source = forYouSource()
    val colors = MaterialTheme.colorScheme
    Column(Modifier.padding(horizontal = ScreenPadding, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(colors.surfaceContainerLow)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxWidth().clickable { nav.open(Page.ForYou) }.padding(12.dp),
        ) {
            MosaicTile(refs, size = 64.dp)
            Column(Modifier.weight(1f)) {
                SuggestedMark(stringResource(R.string.suggest_for_you))
                Text(
                    stringResource(R.string.suggest_for_you_card, picks.take(CARD_NAMES).joinToString(", ") { ui.title(it.song) }, (picks.size - CARD_NAMES).coerceAtLeast(0)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
            LyrifyButton(stringResource(R.string.lib_play), { playback.play(refs, 0, source) }, icon = R.drawable.ic_play_arrow_fill, compact = true)
            LyrifyButton(stringResource(R.string.lib_shuffle), { playback.shuffle(refs, source) }, kind = ButtonKind.SECONDARY, icon = R.drawable.ic_shuffle, compact = true)
            Spacer(Modifier.weight(1f))
            LyrifyButton(stringResource(R.string.suggest_hide), {
                val tomorrow = LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                scope.launch {
                    store.update { it.copy(suggestions = it.suggestions.copy(forYouHiddenUntil = tomorrow)) }
                    toasts.show(Toast(resources.getString(R.string.suggest_hidden_till_tomorrow), R.drawable.ic_explore))
                }
            }, kind = ButtonKind.GHOST, compact = true)
        }
    }
}

/** The card names this many songs, then "and 17 more". */
private const val CARD_NAMES = 3

/**
 * For you on the Insights page (lane in1 places it): its name and size, View all, Play and Shuffle, and its first songs
 * with their reasons; a tap on one plays For you from it. Nothing while there is no mix.
 */
@Composable
fun ForYouSection(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val ui = rememberLibraryUi() ?: return
    val playback = rememberPlayback()
    val picks = rememberForYou(ui.library, ui.settings.suggestions).orEmpty()
    if (picks.isEmpty()) return
    val refs = picks.map { it.song.ref }
    val source = forYouSource()
    val colors = MaterialTheme.colorScheme
    Column(modifier.padding(horizontal = ScreenPadding, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(colors.surfaceContainerLow).padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 8.dp)) {
            Column(Modifier.weight(1f)) {
                SuggestedMark(stringResource(R.string.suggest_for_you), Modifier.semantics { heading() })
                Text(
                    listOf(stringResource(R.string.suggest_for_you_new_today), pluralStringResource(R.plurals.lib_songs, picks.size, picks.size)).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
            LyrifyButton(stringResource(R.string.lib_view_all), onOpen, kind = ButtonKind.GHOST, compact = true)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            LyrifyButton(stringResource(R.string.lib_play), { playback.play(refs, 0, source) }, icon = R.drawable.ic_play_arrow_fill, compact = true)
            LyrifyButton(stringResource(R.string.lib_shuffle), { playback.shuffle(refs, source) }, kind = ButtonKind.SECONDARY, icon = R.drawable.ic_shuffle, compact = true)
        }
        val reasons = ui.settings.suggestions.showReasons
        picks.take(SECTION_SONGS).forEachIndexed { at, pick ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth().clickable { playback.play(refs, at, source) }.heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                SongCover(SongArt(pick.song.path, pick.song.ref.mediaStoreId), size = 44.dp)
                Column(Modifier.weight(1f)) {
                    Text(ui.title(pick.song), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(pick.song.artist.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (reasons) ReasonLine(reasonWords(context, pick.reason, ui::title))
                }
            }
        }
    }
}

/** The Insights section shows this many of For you's songs. */
private const val SECTION_SONGS = 3

/** For you or More like this. */
@Composable
fun SuggestionPage(page: Page.SuggestionPages) {
    val ui = rememberLibraryUi() ?: return
    val library = ui.library ?: return MissingPage(ui)
    when (page) {
        Page.ForYou -> ForYouPage(ui, library)
        is Page.MoreLike -> MoreLikePage(ui, library, page)
    }
}

/** Each pick's reason in words, by its song's path; none with Show reasons off. */
private fun reasonsOf(context: Context, picks: List<Pick>, ui: LibraryUi): Map<String, String> =
    if (ui.settings.suggestions.showReasons) picks.associate { it.song.path to reasonWords(context, it.reason, ui::title) } else emptyMap()

/**
 * The For you page: today's date, its size and length, Play, Shuffle and Add all, then its songs each with its reason; ⋯
 * on a song has Not now, Never suggest this, More like this and Why this?. The page's ⋯ hides or shows the Library card.
 */
@Composable
private fun ForYouPage(ui: LibraryUi, library: Library) {
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val store = rememberSettingsStore()
    val scope = rememberCoroutineScope()
    val listed = rememberForYou(library, ui.settings.suggestions)
    val picks = listed.orEmpty()
    val songs = picks.map { it.song }
    val reasons = reasonsOf(context, picks, ui)
    var menu by rememberSaveable { mutableStateOf(false) }
    val card = ui.settings.suggestions.forYouCard
    val title = stringResource(R.string.suggest_for_you)
    SongListFrame(ui, songs, forYouSource(), loaded = listed != null, topBar = { under, backdrop ->
        BackTopBar(title, under, backdrop, actions = {
            Box {
                ChromeIconButton(R.drawable.ic_more_horiz, stringResource(R.string.suggest_for_you_more), { menu = true })
                LyrifyMenu(expanded = menu, onDismiss = { menu = false }) {
                    MenuRow(stringResource(if (card) R.string.suggest_card_hide else R.string.suggest_card_show), R.drawable.ic_explore, {
                        menu = false
                        scope.launch { store.update { it.copy(suggestions = it.suggestions.copy(forYouCard = !card, forYouHiddenUntil = null)) } }
                    })
                }
            }
        }) { nav.back() }
    }) { list ->
        item(key = "meta", contentType = "meta") {
            val minutes = TimeUnit.MILLISECONDS.toMinutes(songs.sumOf { it.durationMs.coerceAtLeast(0) }).toInt()
            Text(
                listOf(
                    DateUtils.formatDateTime(context, System.currentTimeMillis(), DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY),
                    pluralStringResource(R.plurals.lib_songs, songs.size, songs.size),
                    pluralStringResource(R.plurals.lib_minutes, minutes, minutes),
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = ScreenPadding + 4.dp, vertical = 4.dp),
            )
        }
        item(key = "header", contentType = "header") { ListHeader(songs.size, R.plurals.lib_noun_songs, list::shuffle, { list.play() }, onAddAll = list::addAll) }
        if (listed != null && songs.isEmpty()) {
            item(key = "empty", contentType = "empty") { EmptyState(EmptyArt.Icon(R.drawable.ic_explore), title, text = stringResource(R.string.suggest_for_you_empty)) }
        }
        songRows(list, place = { SheetPlace.Suggestion }, reason = { reasons[it.path] })
    }
}

// More like this

/** What [page] starts from in [library]; null when it is gone. */
private fun seedOf(page: Page.MoreLike, library: Library): Seed? = when (page.seed) {
    SeedKind.SONG -> library.resolver.find(SongRef(page.key))?.let(Seed::OfSong)
    SeedKind.ALBUM -> library.album(page.key)?.let(Seed::OfAlbum)
    SeedKind.ARTIST -> library.artist(page.key)?.let(Seed::OfArtist)
}

/**
 * More like this: the song, album or artist it starts from, what it is based on (a chip for each part found, each turning
 * that part on or off), Play, Shuffle and Add all, then the songs with their reasons.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MoreLikePage(ui: LibraryUi, library: Library, page: Page.MoreLike) {
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val suggestions = rememberSuggestions()
    val seed = remember(library, page) { seedOf(page, library) } ?: return MissingPage(ui)
    // The parts turned off, by name, so a turn of the phone keeps them.
    var off by rememberSaveable { mutableStateOf("") }
    val bases = Basis.entries.filterNot { it.name in off.split(',') }.toSet()
    val found by produceState<MoreLike?>(null, seed, off) { value = suggestions.moreLike(seed, bases) }
    val picks = found?.picks.orEmpty()
    val songs = picks.map { it.song }
    val reasons = reasonsOf(context, picks, ui)
    val (name, line) = when (seed) {
        is Seed.OfSong -> ui.title(seed.song) to listOfNotNull(seed.song.artist, seed.song.album).joinToString(" · ")
        is Seed.OfAlbum -> seed.album.title to seed.album.artist.orEmpty()
        is Seed.OfArtist -> seed.artist.name to pluralStringResource(R.plurals.lib_songs, seed.artist.songs.size, seed.artist.songs.size)
    }
    val title = stringResource(R.string.suggest_like_title, name)
    val source = QueueSource(SourceKind.SUGGESTIONS, "more-like ${page.seed} ${page.key}", title)
    SongListFrame(ui, songs, source, loaded = found != null, topBar = { under, backdrop -> BackTopBar(stringResource(R.string.suggest_more_like), under, backdrop) { nav.back() } }) { list ->
        item(key = "seed", contentType = "seed") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 8.dp)) {
                when (seed) {
                    is Seed.OfSong -> SongCover(SongArt(seed.song.path, seed.song.ref.mediaStoreId), size = 56.dp)
                    is Seed.OfAlbum -> seed.album.songs.firstOrNull()?.let { SongCover(SongArt(it.path, it.ref.mediaStoreId), size = 56.dp) }
                    is Seed.OfArtist -> ArtistAvatar(seed.artist, size = 56.dp)
                }
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
                    if (line.isNotEmpty()) Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        val shown = found?.bases.orEmpty()
        if (shown.isNotEmpty()) {
            item(key = "based on", contentType = "based on") {
                Column(Modifier.padding(horizontal = ScreenPadding, vertical = 4.dp)) {
                    SectionTitle(stringResource(R.string.suggest_based_on))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        shown.forEach { basis ->
                            val on = basis in bases
                            LyrifyChip(on, { off = (if (on) off.split(',') + basis.name else off.split(',') - basis.name).filter(String::isNotEmpty).joinToString(",") }, Role.Checkbox, icon = if (on) R.drawable.ic_check else null) {
                                Text(
                                    when (basis) {
                                        Basis.TOGETHER -> stringResource(R.string.suggest_basis_together)
                                        Basis.ARTIST -> stringResource(if (seed is Seed.OfArtist) R.string.suggest_basis_like_artist else R.string.suggest_basis_artist)
                                        Basis.GENRE -> found?.genre ?: stringResource(R.string.suggest_basis_genre)
                                        Basis.YEARS -> stringResource(R.string.suggest_basis_years)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
        item(key = "header", contentType = "header") { ListHeader(songs.size, R.plurals.lib_noun_songs, list::shuffle, { list.play() }, onAddAll = list::addAll) }
        if (found != null && songs.isEmpty()) {
            item(key = "empty", contentType = "empty") { EmptyState(EmptyArt.Icon(R.drawable.ic_explore), stringResource(R.string.suggest_more_like), text = stringResource(R.string.suggest_more_like_empty)) }
        }
        songRows(list, place = { SheetPlace.Suggestion }, reason = { reasons[it.path] })
    }
}

/** An album's or an artist's More like this, beside its page's own buttons (the mockup's version 1 button, kept). */
@Composable
internal fun MoreLikeButton(onClick: () -> Unit) {
    Box(Modifier.padding(horizontal = ScreenPadding, vertical = 4.dp)) {
        LyrifyButton(stringResource(R.string.suggest_more_like), onClick, kind = ButtonKind.SECONDARY, icon = R.drawable.ic_explore, compact = true)
    }
}

// Settings

/** Settings > Suggestions, after Playback: Autoplay, where Smart shuffle is, How adventurous, Show reasons, the never list and Suggestions health. */
internal fun LazyListScope.suggestionSettings(sections: SettingsSections, onOpen: (Page) -> Unit) {
    item(key = SettingsSection.SUGGESTIONS.name, contentType = "section") {
        SettingsSectionCard(SettingsSection.SUGGESTIONS, sections) { SuggestionSettingsRows(onOpen) }
    }
}

@Composable
private fun SuggestionSettingsRows(onOpen: (Page) -> Unit) {
    val store = rememberSettingsStore()
    val scope = rememberCoroutineScope()
    val settings = rememberSettings()?.suggestions ?: SuggestionSettings()
    val change = { block: (SuggestionSettings) -> SuggestionSettings -> scope.launch { store.update { it.copy(suggestions = block(it.suggestions)) } } }
    val colors = MaterialTheme.colorScheme
    GroupedRows {
        SwitchRow(R.drawable.ic_explore, R.string.suggest_autoplay, stringResource(R.string.set_autoplay_detail), settings.autoplay, first = true) { on -> change { it.copy(autoplay = on) } }
        GroupedRow(R.drawable.ic_shuffle, stringResource(R.string.set_smart_shuffle), colors.surfaceContainerHighest, colors.onSurfaceVariant, supporting = { Text(stringResource(R.string.set_smart_shuffle_detail)) })
        GroupedRow(R.drawable.ic_tune, stringResource(R.string.set_adventure), colors.surfaceContainerHighest, colors.onSurfaceVariant, supporting = { Text(stringResource(R.string.set_adventure_detail)) })
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
            AdventureChoice(settings.adventure)
            Text(mixWords(settings.adventure), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        SwitchRow(R.drawable.ic_explore, R.string.set_reasons, stringResource(R.string.set_reasons_detail), settings.showReasons) { on -> change { it.copy(showReasons = on) } }
        val now = System.currentTimeMillis()
        val snoozed = settings.notNow.count { it.at > now }
        val never = settings.never.size
        val value = if (never + snoozed == 0) {
            stringResource(R.string.set_hidden_none)
        } else {
            listOfNotNull(
                pluralStringResource(R.plurals.set_never_value, never, never),
                snoozed.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.set_not_now_value, it, it) },
            ).joinToString(" · ")
        }
        ValueRow(R.drawable.ic_block, R.string.set_never, value) { onOpen(Page.NeverSuggest) }
        ValueRow(R.drawable.ic_bar_chart, R.string.set_health, stringResource(R.string.health_sentence)) { onOpen(Page.SuggestionsHealth) }
    }
}

/**
 * Songs you said never to suggest, the latest first, each with Allow again; then the songs on Not now, the soonest back
 * first, each with the day it comes back and Allow now. Songs no longer on the phone are left out.
 */
@Composable
fun NeverSuggestPage() {
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val ui = rememberLibraryUi() ?: return
    val suggestions = rememberSuggestions()
    val scope = rememberCoroutineScope()
    val library = ui.library
    val settings = ui.settings.suggestions
    val now = remember { System.currentTimeMillis() }
    val never = settings.never.sortedByDescending { it.at }.mapNotNull { held -> library?.resolver?.find(held.song)?.let { held to it } }
    val snoozed = settings.notNow.filter { it.at > now }.sortedBy { it.at }.mapNotNull { held -> library?.resolver?.find(held.song)?.let { held to it } }
    PageFrame(topBar = { under, backdrop -> BackTopBar(stringResource(R.string.never_title), under, backdrop) { nav.back() } }) { padding ->
        val reduced = LocalReducedMotion.current
        val muted = MaterialTheme.colorScheme.onSurfaceVariant
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp)) {
            item(key = "sentence", contentType = "sentence") {
                Text(stringResource(R.string.never_sentence), style = MaterialTheme.typography.bodyMedium, color = muted, modifier = rowMotion(reduced).padding(start = ScreenPadding + 4.dp, end = ScreenPadding, top = 4.dp, bottom = 12.dp))
            }
            if (library != null && never.isEmpty() && snoozed.isEmpty()) {
                item(key = "empty", contentType = "empty") { EmptyState(EmptyArt.Icon(R.drawable.ic_block), stringResource(R.string.never_empty_title), rowMotion(reduced), text = stringResource(R.string.never_empty)) }
            }
            items(never.size, key = { "never ${never[it].first.song.path}" }, contentType = { "row" }) { at ->
                val (held, song) = never[at]
                val title = ui.title(song)
                HiddenRow(song, title, rowMotion(reduced), line = stringResource(R.string.never_since, dayWords(context, held.at)), action = stringResource(R.string.never_allow), words = stringResource(R.string.never_allow_song, title)) {
                    scope.launch { suggestions.allow(held.song) }
                }
            }
            if (snoozed.isNotEmpty()) {
                item(key = "not now", contentType = "heading") {
                    Column(rowMotion(reduced).padding(start = ScreenPadding + 4.dp, end = ScreenPadding, top = 16.dp)) {
                        SectionTitle(stringResource(R.string.suggest_not_now))
                        Text(stringResource(R.string.never_not_now_sentence), style = MaterialTheme.typography.bodyMedium, color = muted, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
                    }
                }
            }
            items(snoozed.size, key = { "not now ${snoozed[it].first.song.path}" }, contentType = { "row" }) { at ->
                val (held, song) = snoozed[at]
                val title = ui.title(song)
                HiddenRow(song, title, rowMotion(reduced), line = stringResource(R.string.never_back_on, dayWords(context, held.at)), action = stringResource(R.string.never_allow_now), words = stringResource(R.string.never_allow_now_song, title)) {
                    scope.launch { suggestions.allow(held.song) }
                }
            }
        }
    }
}

/**
 * Suggestions health (plan Q2.5): three plain numbers over the last 30 days, worked out on the phone. How often the next
 * song was in KJB's top 10 beside simple guessing, suggested songs skipped beside plain Shuffle's, and discoveries kept;
 * when they were last worked out, and Check again.
 */
@Composable
fun SuggestionsHealthPage() {
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val suggestions = rememberSuggestions()
    var asked by remember { mutableIntStateOf(0) }
    val checked by produceState<HealthNow?>(null, asked) {
        value = null
        value = suggestions.health(again = asked > 0)
    }
    val percent = remember { NumberFormat.getPercentInstance() }
    PageFrame(topBar = { under, backdrop -> BackTopBar(stringResource(R.string.set_health), under, backdrop) { nav.back() } }) { padding ->
        val reduced = LocalReducedMotion.current
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp)) {
            item(key = "sentence", contentType = "sentence") {
                Text(
                    stringResource(R.string.health_sentence),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = rowMotion(reduced).padding(start = ScreenPadding + 4.dp, end = ScreenPadding, top = 4.dp, bottom = 12.dp),
                )
            }
            val (health, at) = checked ?: return@LazyColumn
            item(key = "guessed", contentType = "metric") {
                val enough = health.steps > 0
                HealthCard(
                    stringResource(R.string.health_guessed),
                    if (enough) percent.format(health.hitRate) else stringResource(R.string.health_not_yet),
                    if (enough) stringResource(R.string.health_guessing, percent.format(health.baseline)) else null,
                    stringResource(R.string.health_guessed_line),
                    if (enough) stringResource(if (health.hitRate > health.baseline) R.string.health_guessed_better else R.string.health_guessed_not_yet) else null,
                    rowMotion(reduced),
                )
            }
            item(key = "skipped", contentType = "metric") {
                val enough = health.suggestedPlays > 0
                val mine = if (enough) health.suggestedSkips.toDouble() / health.suggestedPlays else 0.0
                val shuffle = if (health.shufflePlays > 0) health.shuffleSkips.toDouble() / health.shufflePlays else null
                HealthCard(
                    stringResource(R.string.health_skipped),
                    if (enough) percent.format(mine) else stringResource(R.string.health_not_yet),
                    shuffle?.let { stringResource(R.string.health_shuffle_skipped, percent.format(it)) },
                    null,
                    if (enough && shuffle != null) stringResource(if (mine < shuffle) R.string.health_skipped_fewer else R.string.health_skipped_more) else null,
                    rowMotion(reduced),
                )
            }
            item(key = "kept", contentType = "metric") {
                HealthCard(stringResource(R.string.health_kept), stringResource(R.string.health_kept_value, health.kept, health.discoveries), null, stringResource(R.string.health_kept_line), null, rowMotion(reduced))
            }
            item(key = "checked", contentType = "foot") {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = rowMotion(reduced).padding(horizontal = ScreenPadding + 4.dp, vertical = 12.dp)) {
                    Text(
                        stringResource(R.string.health_checked, DateUtils.formatDateTime(context, at, DateUtils.FORMAT_SHOW_TIME)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    LyrifyButton(stringResource(R.string.health_check_again), { asked++ }, kind = ButtonKind.SECONDARY, compact = true)
                }
            }
        }
    }
}

/** One of Suggestions health's numbers: its [name], [value] and what it is beside ([compare]), a [line] and its [verdict]. */
@Composable
private fun HealthCard(name: String, value: String, compare: String?, line: String?, verdict: String?, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier.padding(horizontal = ScreenPadding, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(colors.surfaceContainerLow).padding(16.dp),
    ) {
        Text(name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        compare?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant) }
        line?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
        verdict?.let {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = LocalBrandColors.current.highlight, modifier = Modifier.size(18.dp))
                Text(it, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
