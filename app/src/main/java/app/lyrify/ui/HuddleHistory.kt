package app.lyrify.ui

import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.lyrify.R
import app.lyrify.huddle.FriendPlay
import app.lyrify.huddle.Match
import app.lyrify.huddle.Sent
import app.lyrify.huddle.match
import app.lyrify.library.Song
import app.lyrify.stats.dayOf
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Huddle's history, kept on the phone (user 2026-11-23, item 55; lane HY1): what went between you and a friend ("You
// and <name>"), every card friends sent (Sent to you's View all), and what a friend played in the last year, each read
// from plays.db in pages as the list scrolls. A friend who left keeps theirs, under Past friends (item 59; lane HY2).

/** Huddle's history pages ([Page.HuddlePages]). */
@Composable
fun HuddleHistoryPage(page: Page.HuddlePages) {
    val nav = LocalNavigator.current
    when (page) {
        is Page.HuddleThread -> ThreadPage(page.friend) { nav.back() }
        Page.HuddleCards -> CardsPage { nav.back() }
        is Page.HuddlePlays -> PlaysPage(page.friend) { nav.back() }
        Page.HuddlePast -> PastPage { nav.back() }
    }
}

/** [plays] (the newest first) by the day each began on the phone's clock ([zone]), the newest day first. */
fun playDays(plays: List<FriendPlay>, zone: ZoneId): List<Pair<LocalDate, List<FriendPlay>>> =
    plays.groupBy { dayOf(it.at, zone).date }.toList()

/**
 * "You and <name>": every song and note between you and the friend [friendId], the newest first, as postcards, yours at
 * the end ("From you", and Not delivered when one never got there) and theirs at the start, as a conversation reads. A
 * tap opens the card ([ReceivedHost]: Play, and Send back for theirs); its play button plays it. Nothing yet: Send <name>
 * a song. A past friend's (they left, or you removed them) stays under the name you saw them by, says they are no longer
 * friends, and its "⋯" opens What <name> played and Delete history, asked first. Once they are gone with their history,
 * the page says so under Back.
 */
@Composable
private fun ThreadPage(friendId: String, onBack: () -> Unit) {
    val nav = LocalNavigator.current
    val resources = LocalResources.current
    val toasts = LocalToasts.current
    // Delete history leaves the page first: it runs on the activity's scope, and its toast outlives the page.
    val lifecycle = LocalLifecycleOwner.current.lifecycleScope
    val huddle = rememberHuddle()
    val state = rememberHuddleState()
    val matcher = rememberSongMatcher()
    val list = rememberLazyListState()
    val written by huddle.cardsWritten.collectAsStateWithLifecycle()
    val cards = rememberPages(list, written) { from, limit -> huddle.thread(friendId, from, limit) }
    var sheet by rememberSaveable(stateSaver = HuddleSheetSaver) { mutableStateOf<HuddleSheet?>(null) }
    var opened by rememberSaveable { mutableStateOf<String?>(null) }
    var playNow by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    val player = { nav.show(FullScreen.Player) }
    val friend = state?.friends?.firstOrNull { it.id == friendId }
    val past = state?.past?.firstOrNull { it.id == friendId }
    val name = friend?.shownName ?: past?.name.orEmpty()
    PageFrame(
        topBar = { under, backdrop ->
            BackTopBar(
                if (friend == null && past == null) "" else stringResource(R.string.huddle_thread_title, name),
                under,
                backdrop,
                actions = {
                    if (past != null) {
                        var open by remember { mutableStateOf(false) }
                        Box {
                            ChromeIconButton(R.drawable.ic_more_horiz, stringResource(R.string.menu_more), { open = true })
                            LyrifyMenu(expanded = open, onDismiss = { open = false }) {
                                MenuRow(stringResource(R.string.huddle_played_title, name), R.drawable.ic_history, onClick = {
                                    open = false
                                    nav.open(Page.HuddlePlays(friendId))
                                })
                                MenuRow(stringResource(R.string.huddle_delete_history), R.drawable.ic_delete, destructive = true, onClick = {
                                    open = false
                                    deleting = true
                                })
                            }
                        }
                    }
                },
                onBack = onBack,
            )
        },
    ) { padding ->
        if (state != null && friend == null && past == null) {
            // Gone meanwhile with their history (deleted): said under Back, as an album that went is (MissingPage).
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                EmptyState(EmptyArt.Icon(R.drawable.ic_person_off), stringResource(R.string.huddle_gone_title), text = stringResource(R.string.huddle_gone))
            }
        } else {
            LazyColumn(
                state = list,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 24.dp),
            ) {
                if (past != null) {
                    item(key = "past", contentType = "past") { Fact(R.drawable.ic_person_off, stringResource(R.string.huddle_past_note), Modifier.padding(horizontal = ScreenPadding, vertical = 8.dp)) }
                }
                if (cards?.isEmpty() == true) {
                    item(key = "empty", contentType = "empty") {
                        EmptyState(
                            EmptyArt.Icon(R.drawable.ic_swap_horiz),
                            stringResource(R.string.huddle_thread_empty),
                            text = stringResource(R.string.huddle_thread_empty_text, name),
                            // Nothing goes to a past friend.
                            action = stringResource(R.string.huddle_send_song_to, name).takeIf { past == null },
                        ) { sheet = HuddleSheet.SendTo(friendId) }
                    }
                }
                itemsIndexed(cards.orEmpty(), key = { _, it -> it.id }, contentType = { _, _ -> "card" }) { at, kept ->
                    val mine = kept is Sent
                    Box(Modifier.fillMaxWidth().padding(horizontal = ScreenPadding, vertical = 8.dp), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
                        KeptPostcard(
                            kept,
                            if (mine) state?.name ?: name else name,
                            if (mine) OwnFaceColor else friendColor(friendId),
                            matcher,
                            tilt = if (at % 2 == 0) -1.6f else 1.4f,
                            onOpen = {
                                playNow = false
                                opened = kept.id
                            },
                            onPlay = {
                                playNow = true
                                opened = kept.id
                            },
                        )
                    }
                }
            }
        }
    }
    HuddleSheetsHost(sheet, LocalLyrics.current.ledger, player, nav::open) { sheet = it }
    ReceivedHost(opened, player, onSendBack = { sheet = HuddleSheet.SendTo(it) }, playNow = playNow) { opened = null }
    if (deleting && past != null) {
        ConfirmDialog(
            title = stringResource(R.string.huddle_delete_history_title, name),
            text = stringResource(R.string.huddle_delete_history_text),
            confirm = stringResource(R.string.lib_delete),
            dismiss = stringResource(R.string.remove_cancel),
            onConfirm = {
                deleting = false
                onBack()
                lifecycle.launch {
                    if (withContext(NonCancellable) { huddle.deleteHistory(friendId) }) {
                        toasts.show(Toast(resources.getString(R.string.huddle_history_deleted, name), R.drawable.ic_delete))
                    } else {
                        toasts.show(Toast(resources.getString(R.string.lib_not_saved), R.drawable.ic_error, Tone.ERROR))
                    }
                }
            },
            onDismiss = { deleting = false },
        )
    }
}

/**
 * Sent to you's View all: every card friends sent, kept on the phone, the newest first, as the page's postcards (New until
 * opened), a past friend's too. A tap opens the card, its play button plays it, Send back answers the friend.
 */
@Composable
private fun CardsPage(onBack: () -> Unit) {
    val nav = LocalNavigator.current
    val huddle = rememberHuddle()
    val state = rememberHuddleState()
    val matcher = rememberSongMatcher()
    val list = rememberLazyListState()
    val written by huddle.cardsWritten.collectAsStateWithLifecycle()
    val cards = rememberPages(list, written) { from, limit -> huddle.received(from, limit) }
    var sheet by rememberSaveable(stateSaver = HuddleSheetSaver) { mutableStateOf<HuddleSheet?>(null) }
    var opened by rememberSaveable { mutableStateOf<String?>(null) }
    var playNow by rememberSaveable { mutableStateOf(false) }
    val player = { nav.show(FullScreen.Player) }
    // Friends here and past friends, whose cards stay: each card under the name you saw them by.
    val names = state?.friends.orEmpty().associate { it.id to it.shownName } + state?.past.orEmpty().associate { it.id to it.name }
    PageFrame(topBar = { under, backdrop -> BackTopBar(stringResource(R.string.huddle_inbox_title), under, backdrop, onBack = onBack) }) { padding ->
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 24.dp),
        ) {
            if (cards?.isEmpty() == true) {
                item(key = "empty", contentType = "empty") {
                    EmptyState(EmptyArt.Icon(R.drawable.ic_send), stringResource(R.string.huddle_cards_empty), text = stringResource(R.string.huddle_cards_empty_text))
                }
            }
            // Once Huddle is read; a card whose history was deleted meanwhile goes as the list is read again.
            itemsIndexed(cards.orEmpty().filter { it.friend in names }, key = { _, it -> it.id }, contentType = { _, _ -> "card" }) { at, received ->
                Box(Modifier.fillMaxWidth().padding(horizontal = ScreenPadding, vertical = 8.dp)) {
                    KeptPostcard(
                        received,
                        names.getValue(received.friend),
                        friendColor(received.friend),
                        matcher,
                        tilt = if (at % 2 == 0) -1.6f else 1.4f,
                        onOpen = {
                            playNow = false
                            opened = received.id
                        },
                        onPlay = {
                            playNow = true
                            opened = received.id
                        },
                    )
                }
            }
        }
    }
    HuddleSheetsHost(sheet, LocalLyrics.current.ledger, player, nav::open) { sheet = it }
    ReceivedHost(opened, player, onSendBack = { sheet = HuddleSheet.SendTo(it) }, playNow = playNow) { opened = null }
}

/**
 * "What <name> played": the friend [friendId]'s plays of the last year, by day (Today, Yesterday, then the date), the
 * newest first, each with its time: your cover when you have the song, else their colour's pattern, with whether it is
 * here and when it was one of their favourites. A tap plays it as their song's Play it does ([playIt]); a loose match
 * asks "Is this the song?" first, and one not here opens YouTube's search. A past friend's stays, under the name you saw
 * them by, and says they are no longer friends. Once they are gone with their history, the page says so under Back.
 */
@Composable
private fun PlaysPage(friendId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val nav = LocalNavigator.current
    val huddle = rememberHuddle()
    val state = rememberHuddleState()
    val matcher = rememberSongMatcher()
    val list = rememberLazyListState()
    val today = rememberToday()
    val written by huddle.playsWritten.collectAsStateWithLifecycle()
    val plays = rememberPages(list, written) { from, limit -> huddle.friendPlays(friendId, from, limit) }
    var youtube by rememberSaveable { mutableStateOf<String?>(null) }
    // "Is this the song?" for a loose match: the play and the song it may be; not kept across a turn of the phone.
    var asking by remember { mutableStateOf<Pair<FriendPlay, Song>?>(null) }
    val player = { nav.show(FullScreen.Player) }
    val friend = state?.friends?.firstOrNull { it.id == friendId }
    val past = state?.past?.firstOrNull { it.id == friendId }
    val name = friend?.shownName ?: past?.name.orEmpty()
    val color = friendColor(friendId)
    val zone = ZoneId.systemDefault()
    PageFrame(topBar = { under, backdrop -> BackTopBar(if (friend == null && past == null) "" else stringResource(R.string.huddle_played_title, name), under, backdrop, onBack = onBack) }) { padding ->
        if (state != null && friend == null && past == null) {
            // Gone meanwhile with their history (deleted): said under Back, as an album that went is (MissingPage).
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                EmptyState(EmptyArt.Icon(R.drawable.ic_person_off), stringResource(R.string.huddle_gone_title), text = stringResource(R.string.huddle_gone))
            }
        } else {
            LazyColumn(
                state = list,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 24.dp),
            ) {
                if (past != null) {
                    item(key = "past", contentType = "past") { Fact(R.drawable.ic_person_off, stringResource(R.string.huddle_past_note), Modifier.padding(horizontal = ScreenPadding, vertical = 8.dp)) }
                }
                if (plays?.isEmpty() == true) {
                    item(key = "empty", contentType = "empty") {
                        EmptyState(EmptyArt.Icon(R.drawable.ic_history), stringResource(R.string.huddle_played_empty), text = stringResource(R.string.huddle_played_empty_text, name))
                    }
                }
                playDays(plays.orEmpty(), zone).forEach { (day, onDay) ->
                    stickyHeader(key = "day $day", contentType = "day") {
                        SectionChip(
                            when (day) {
                                today -> stringResource(R.string.header_today)
                                today.minusDays(1) -> stringResource(R.string.header_yesterday)
                                else -> dateText(day.atStartOfDay(zone).toInstant().toEpochMilli())
                            },
                        )
                    }
                    // A play is one of its kind: two with all the same values are kept once (the history).
                    items(onDay, key = { play -> "play ${play.at} ${play.song} ${play.favourite}" }, contentType = { "play" }) { play ->
                        val match = remember(matcher, play.song) { matcher?.match(play.song) }
                        val time = DateFormat.getTimeFormat(context).format(Date(play.at))
                        NavRow(
                            title = play.song.title,
                            sub = listOfNotNull(play.song.artist, time).joinToString(" · "),
                            lengthMs = play.song.durationMs,
                            description = listOfNotNull(
                                play.song.title,
                                play.song.artist,
                                time,
                                lengthWords(play.song.durationMs).ifEmpty { null },
                                stringResource(R.string.huddle_their_favourite, name).takeIf { play.favourite },
                                when (match) {
                                    is Match.Maybe -> stringResource(R.string.huddle_maybe)
                                    Match.Missing -> stringResource(R.string.huddle_not_found)
                                    is Match.Found, null -> null
                                },
                            ).joinToString(", "),
                            lead = { FriendArt((match as? Match.Found)?.song ?: (match as? Match.Maybe)?.song, color, 48.dp, RoundedCornerShape(12.dp)) },
                            trailing = {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (play.favourite) Icon(painterResource(R.drawable.ic_favorite_fill), contentDescription = null, tint = lerp(color, MaterialTheme.colorScheme.onSurface, 0.3f), modifier = Modifier.size(16.dp))
                                    if (match != null && match !is Match.Found) HaveChip(match)
                                }
                            },
                        ) {
                            when (match) {
                                is Match.Found -> playIt(context, match.song, play, name, player)
                                is Match.Maybe -> asking = play to match.song
                                Match.Missing -> youtube = youTubeQuery(play.song)
                                // Not matched yet (the library is being read): nothing to play or ask for.
                                null -> Unit
                            }
                        }
                    }
                }
            }
        }
    }
    asking?.let { (play, song) ->
        ConfirmDialog(
            title = stringResource(R.string.huddle_maybe_title),
            text = localSong(song),
            confirm = stringResource(R.string.huddle_yes),
            dismiss = stringResource(R.string.huddle_maybe_no),
            icon = R.drawable.ic_help,
            destructive = false,
            onConfirm = {
                asking = null
                playIt(context, song, play, name, player)
            },
            onDismiss = { asking = null },
        )
    }
    youtube?.let { query -> YouTubeSearchSheet(query, onDismiss = { youtube = null }, onStarted = { youtube = null }) }
}

/**
 * Past friends: friends who left Huddle or whom you removed, the last to leave first, under the name you saw them by and
 * until when you were friends, as the Huddle page lists friends; each opens "You and <name>", where your history with them
 * stays until you delete it.
 */
@Composable
private fun PastPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val nav = LocalNavigator.current
    val state = rememberHuddleState()
    PageFrame(topBar = { under, backdrop -> BackTopBar(stringResource(R.string.huddle_past_title), under, backdrop, onBack = onBack) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 24.dp),
        ) {
            if (state?.past?.isEmpty() == true) {
                item(key = "empty", contentType = "empty") { EmptyState(EmptyArt.Icon(R.drawable.ic_person_off), stringResource(R.string.huddle_past_empty)) }
            }
            items(state?.past.orEmpty().sortedByDescending { it.leftAt }, key = { it.id }, contentType = { "friend" }) { past ->
                NavRow(
                    title = past.name,
                    sub = stringResource(R.string.huddle_friends_until, DateUtils.formatDateTime(context, past.leftAt, DateUtils.FORMAT_SHOW_DATE)),
                    lead = { FriendFace(past.name, friendColor(past.id), 44.dp, ring = FaceRing.IDLE) },
                ) { nav.open(Page.HuddleThread(past.id)) }
            }
        }
    }
}

/**
 * [read]'s rows in pages of [PAGE] as [list] scrolls (the next once it comes within half a page of the rows read), read
 * again from the top as [written] changes, as many as were read before, so a card or a play coming in never shortens the
 * list under the user, and as many again after a turn of the phone; null until the first page is read. A row read twice
 * (one kept at the top between two pages moves the next page down by one) shows once: the lists' keys must not repeat.
 */
@Composable
private fun <T> rememberPages(list: LazyListState, written: Int, read: suspend (from: Int, limit: Int) -> List<T>): List<T>? {
    // How many rows were read, kept across a turn of the phone: read again at once, the list comes back where it was.
    var reached by rememberSaveable { mutableIntStateOf(0) }
    return produceState<List<T>?>(null, written) {
        val asked = maxOf(PAGE, value?.size ?: reached)
        var rows = read(0, asked)
        value = rows
        reached = rows.size
        // A read short of what it asked for reached the end.
        var more = rows.size == asked
        while (more) {
            val shown = rows.size
            snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.first { it >= shown - PAGE / 2 }
            val page = read(shown, PAGE)
            rows = withContext(Dispatchers.Default) { (rows + page).distinct() }
            value = rows
            reached = rows.size
            more = page.size == PAGE
        }
    }.value
}

/** Rows of the history read at a time: a few screens of postcards or plays. */
private const val PAGE: Int = 60
