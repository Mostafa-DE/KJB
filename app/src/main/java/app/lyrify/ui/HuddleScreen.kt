package app.lyrify.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.res.Resources
import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.lyrify.R
import app.lyrify.backup.SongMatcher
import app.lyrify.data.LedgerState
import app.lyrify.huddle.Card
import app.lyrify.huddle.CardKind
import app.lyrify.huddle.CardSong
import app.lyrify.huddle.Friend
import app.lyrify.huddle.FriendPlay
import app.lyrify.huddle.Huddle
import app.lyrify.huddle.HuddleBackup
import app.lyrify.huddle.HuddleGraph
import app.lyrify.huddle.HuddleState
import app.lyrify.huddle.Invites
import app.lyrify.huddle.Match
import app.lyrify.huddle.PendingInvite
import app.lyrify.huddle.Received
import app.lyrify.huddle.match
import app.lyrify.library.LibraryGraph
import app.lyrify.library.LibraryState
import app.lyrify.library.Song
import app.lyrify.lyrics.TrackNames
import app.lyrify.settings.DockTab
import app.lyrify.settings.LibraryTab
import java.text.Collator
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Huddle's page (Mix 2 of the approved mockup, handoff/huddle/preview-huddle-v4.html: friends playing now as big cards,
// what they sent as postcards, the others below), its Tools row, Library's friends row with the newest card a friend
// sent, and Huddle's host in the app (plan part 3 and 3b, user decisions 2026-11-22).

/**
 * Huddle's line on Tools: how many friends, with the cards not opened yet among the newest friends sent ([inbox],
 * [Huddle.inbox]) as its badge; what it is for before the first.
 */
fun huddleEntry(state: HuddleState, inbox: List<Received>): EntryRow = when {
    state.friends.isNotEmpty() -> {
        val here = state.friends.mapTo(HashSet()) { it.id }
        EntryRow(ToolLine.Count(R.plurals.huddle_friends, state.friends.size), inbox.count { !it.opened && it.friend in here }.takeIf { it > 0 })
    }
    state.invites.isNotEmpty() -> EntryRow(ToolLine.Text(R.string.huddle_tool_waiting), null)
    else -> EntryRow(ToolLine.Text(R.string.huddle_tool_none), null)
}

/**
 * The card the start screen's "From <name>" row shows of the newest friends sent ([inbox], [Huddle.inbox]), from a friend
 * still here: the newest not opened yet, else the newest card, opened, until it is dismissed (an older one never takes its
 * place); null for none.
 */
fun newestCard(state: HuddleState, inbox: List<Received>): Received? {
    val here = state.friends.mapTo(HashSet()) { it.id }
    return inbox.filter { !it.opened && it.friend in here }.maxByOrNull { it.at }
        ?: inbox.filter { it.friend in here }.maxByOrNull { it.at }?.takeUnless { it.dismissed }
}

/** Friends as Send to a friend lists them: the one you sent to last first, then the others by the name you see. */
fun sendOrder(friends: List<Friend>): List<Friend> {
    val collator = Collator.getInstance()
    return friends.sortedWith(compareByDescending<Friend> { it.lastSentAt ?: Long.MIN_VALUE }.thenBy(collator) { it.shownName })
}

/** A friend on the Huddle page, by the name you see: their latest play, and whether it may still be playing. */
data class FriendNow(val friend: Friend, val play: FriendPlay?, val playing: Boolean)

/**
 * Every friend with their latest play ([FriendNow]), as the friends row and the page show them: those playing now first,
 * the song begun last first, then the others by their latest play, then those who never shared one, by name. A play is
 * still playing until its length and a minute have passed.
 */
fun friendsNow(state: HuddleState, now: Long): List<FriendNow> {
    val collator = Collator.getInstance()
    val latest = state.plays.groupBy { it.friend }.mapValues { (_, plays) -> plays.maxBy { it.at } }
    return state.friends.map { friend ->
        val play = latest[friend.id]
        FriendNow(friend, play, playing = play != null && now <= play.at + play.song.durationMs + PLAYING_GRACE_MS)
    }.sortedWith(compareByDescending<FriendNow> { it.playing }.thenByDescending { it.play?.at ?: Long.MIN_VALUE }.thenBy(collator) { it.friend.shownName })
}

/** A friend playing now what you play: the same song, or another of the same artist ([sameSong] false). */
data class FriendOn(val friend: FriendNow, val sameSong: Boolean)

/**
 * The friends playing now the song [title] by [artist] (case, accents and punctuation aside, [TrackNames.normalize]), then
 * those on another song of that artist: the player's and the Now bar's faces. A song without an artist meets only the
 * same song without one.
 */
fun friendsOn(state: HuddleState, now: Long, title: String, artist: String?): List<FriendOn> {
    val song = TrackNames.normalize(title)
    val by = artist?.let(TrackNames::normalize)?.takeIf(String::isNotEmpty)
    return friendsNow(state, now).filter { it.playing }.mapNotNull { friend ->
        val play = friend.play ?: return@mapNotNull null
        when {
            play.song.artist?.let(TrackNames::normalize)?.takeIf(String::isNotEmpty) != by -> null
            TrackNames.normalize(play.song.title) == song -> FriendOn(friend, sameSong = true)
            by != null -> FriendOn(friend, sameSong = false)
            else -> null
        }
    }.sortedByDescending { it.sameSong }
}

/**
 * Where Play along starts [play] at [now]: where the friend is, from when their song began; never in its last
 * [PLAY_ALONG_END_MS], and from the top for a song whose length is not known.
 */
fun playAlongMs(play: FriendPlay, now: Long): Long {
    val length = play.song.durationMs
    if (length <= 0) return 0
    return (now - play.at).coerceIn(0, (length - PLAY_ALONG_END_MS).coerceAtLeast(0))
}

/** Play along never starts in a song's last 10 s: something is left to hear. */
const val PLAY_ALONG_END_MS: Long = 10_000

/** What a friend's song's sheet offers for it, as this library has it ([peekOffer]). */
sealed interface PeekOffer {
    /** Not matched yet (the library is being read): neither Play nor Ask. */
    data object Waiting : PeekOffer

    /** Yours: Play along and Play it. */
    data class Play(val song: Song) : PeekOffer

    /** Only a loose match ([Match.Maybe]): "Is this the song?" first. */
    data class Ask(val song: Song) : PeekOffer

    /** Not here, or not the one: Ask the friend and Search on YouTube. */
    data object Missing : PeekOffer
}

/** What a friend's song's sheet offers for its [match] here (null: not matched yet), after [said] to "Is this the song?". */
fun peekOffer(match: Match?, said: Boolean?): PeekOffer = when (match) {
    null -> PeekOffer.Waiting
    is Match.Found -> PeekOffer.Play(match.song)
    is Match.Maybe -> when (said) {
        null -> PeekOffer.Ask(match.song)
        true -> PeekOffer.Play(match.song)
        false -> PeekOffer.Missing
    }
    Match.Missing -> PeekOffer.Missing
}

/** How much of a card you have: all of it, some of an album's or playlist's songs, maybe (a loose match), or none. */
enum class Have { ALL, SOME, MAYBE, NONE }

/** How much of a card you have: a song by its [match]; an album or a playlist by its songs found, [some] (found to all). */
fun haveOf(match: Match?, some: Pair<Int, Int>?): Have = when {
    some != null -> when (some.first) {
        0 -> Have.NONE
        some.second -> Have.ALL
        else -> Have.SOME
    }
    match is Match.Found -> Have.ALL
    match is Match.Maybe -> Have.MAYBE
    else -> Have.NONE
}

/** What Huddle asks before anything else: your name ([NAME], the first time), or another one ([NEW_NAME], taken in a friend's list). */
enum class NameGate { NAME, NEW_NAME }

/** The question [state] needs answered first ([NameGate]); null for none. */
fun nameGate(state: HuddleState): NameGate? = when {
    state.name == null -> NameGate.NAME
    state.friends.any { it.asksNewName } -> NameGate.NEW_NAME
    else -> null
}

/** The bar's Huddle has its live dot: a friend still here is playing now. */
fun huddleDot(state: HuddleState, now: Long): Boolean = friendsNow(state, now).any { it.playing }

/** Whether Library's [tab] leads with the friends row: the tab Library [opensOn], once there is a friend. */
fun friendsRowShows(tab: LibraryTab, opensOn: LibraryTab, state: HuddleState): Boolean = tab == opensOn && state.friends.isNotEmpty()

/** How long past its end a friend's play still shows as playing: a pause, a seek back, a clock a little off. */
private const val PLAYING_GRACE_MS: Long = 60_000

/** How often the times and "Playing now" are worked out again: as often as friends' plays come while KJB is in front. */
private const val TICK_MS: Long = Huddle.FAST_MS


// In the composition

/** The app's [Huddle] (HuddleGraph.kt). */
@Composable
internal fun rememberHuddle(): Huddle {
    val context = LocalContext.current
    return remember(context) { HuddleGraph.huddle(context) }
}

/** What Huddle keeps, as it changes; null until it is read. */
@Composable
internal fun rememberHuddleState(): HuddleState? = rememberHuddle().state.collectAsStateWithLifecycle(initialValue = null).value

/** The newest cards friends sent, as they are kept and opened ([Huddle.inbox]); null until they are read. */
@Composable
internal fun rememberHuddleInbox(): List<Received>? = rememberHuddle().inbox.collectAsStateWithLifecycle(initialValue = null).value

/** The Tools row's state ([huddleEntry]); null until Huddle is read. */
@Composable
fun huddleRow(): EntryRow? {
    val inbox = rememberHuddleInbox()
    return rememberHuddleState()?.let { huddleEntry(it, inbox.orEmpty()) }
}

/** "Paper Lantern by Kit Calloway", or the title alone without an artist. */
internal fun songBy(resources: Resources, song: CardSong): String = song.artist?.let { resources.getString(R.string.huddle_song_by, song.title, it) } ?: song.title

/** What a card is, in a line: the song and its artist, or "Album · <name>", "Playlist · <name>". */
@Composable
internal fun cardLine(card: Card): String = when (card.kind) {
    CardKind.SONG -> card.songs.first().let { song -> listOfNotNull(song.title, song.artist).joinToString(" · ") }
    CardKind.ALBUM -> stringResource(R.string.lib_source_album, card.name ?: card.songs.first().album.orEmpty())
    CardKind.PLAYLIST -> stringResource(R.string.lib_source_playlist, card.name.orEmpty())
}

/** "2 hours ago", from [now]. */
internal fun ago(at: Long, now: Long): String = DateUtils.getRelativeTimeSpanString(at, now, DateUtils.MINUTE_IN_MILLIS).toString()

/** When a card came: its time today ("9:12"), else its day ("Yesterday", "Monday"). */
internal fun whenSent(context: Context, at: Long, now: Long): String =
    if (DateUtils.isToday(at)) DateFormat.getTimeFormat(context).format(Date(at)) else DateUtils.getRelativeTimeSpanString(at, now, DateUtils.DAY_IN_MILLIS).toString()

/**
 * The wall clock, worked out again every [everyMs]; read only where it shows, so only that redraws. It stops while its
 * layer is hidden (a kept dock root that is not showing) and catches up as it comes back.
 */
@Composable
internal fun rememberNow(everyMs: Long = TICK_MS): State<Long> {
    val shown = LocalLayerHeaded.current
    return produceState(System.currentTimeMillis(), everyMs, shown) {
        while (shown) {
            value = System.currentTimeMillis()
            delay(everyMs)
        }
    }
}

/** This library's matcher for friends' songs ([match]): made off the main thread once per library read; null until then. */
@Composable
internal fun rememberSongMatcher(): SongMatcher? {
    val context = LocalContext.current
    val library by remember(context) { LibraryGraph.repository(context).state }.collectAsStateWithLifecycle()
    return produceState<SongMatcher?>(null, library) {
        val songs = (library as? LibraryState.Ready)?.library?.songs ?: return@produceState
        value = withContext(Dispatchers.Default) { SongMatcher(songs) }
    }.value
}

/**
 * Huddle in the app, once (MainActivity, outside the shell): it syncs while KJB is in front (when it comes to the front,
 * then every 30 s), and an [invite] link another app opened asks whether to join ([AcceptInviteSheet]); [onInviteTaken]
 * once it is shown. A song that came with it plays after it ([ReceivedHost]), and [onPlayer] shows the player (the shell's
 * navigator is not here). A friend who joined your invite is greeted once ([JoinedDialog]), with Send a song (the song
 * picker reads [ledger]); a friend who says your name is taken in their list asks for another, once a run of KJB (the
 * Huddle page keeps asking until it is done).
 */
@Composable
fun HuddleHost(invite: String?, onInviteTaken: () -> Unit, onPlayer: () -> Unit, ledger: LedgerState) {
    val huddle = rememberHuddle()
    val scope = rememberCoroutineScope()
    LifecycleStartEffect(huddle) {
        val front = huddle.syncWhileInFront()
        onStopOrDispose { front.cancel() }
    }
    var link by rememberSaveable { mutableStateOf<String?>(null) }
    var opened by rememberSaveable { mutableStateOf<String?>(null) }
    var sheet by rememberSaveable(stateSaver = HuddleSheetSaver) { mutableStateOf<HuddleSheet?>(null) }
    var asked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(invite) {
        if (invite != null) {
            link = invite
            onInviteTaken()
        }
    }
    link?.let { AcceptInviteSheet(it, onDismiss = { link = null }) { received -> opened = received } }
    ReceivedHost(opened, onPlayer, onSendBack = { sheet = HuddleSheet.SendTo(it) }, playNow = true) { opened = null }
    HuddleSheetsHost(sheet, ledger, onPlayer, onPage = null) { sheet = it }
    val state = rememberHuddleState() ?: return
    state.joined.firstNotNullOfOrNull { id -> state.friends.firstOrNull { it.id == id } }?.let { friend ->
        JoinedDialog(
            friend,
            onSend = {
                scope.launch { huddle.celebrated(friend.id) }
                sheet = HuddleSheet.SendTo(friend.id)
            },
            onDone = { scope.launch { huddle.celebrated(friend.id) } },
        )
    }
    if (!asked && nameGate(state) == NameGate.NEW_NAME) NewNameDialog(state) { asked = true }
}

/** A friend says your name is the name of another of their friends: pick another ([Huddle.setName] tells every friend). */
@Composable
private fun NewNameDialog(state: HuddleState, onDone: () -> Unit) {
    val huddle = rememberHuddle()
    val scope = rememberCoroutineScope()
    val friend = state.friends.firstOrNull { it.asksNewName } ?: return
    NameDialog(
        stringResource(R.string.huddle_new_name_title),
        state.name.orEmpty(),
        R.drawable.ic_person,
        onDismiss = onDone,
        confirm = R.string.huddle_save,
        text = stringResource(R.string.huddle_new_name_text, friend.shownName, state.name.orEmpty()),
    ) { name ->
        onDone()
        scope.launch { huddle.setName(name) }
    }
}

/**
 * Huddle's page (Mix 2 of the approved mockup): your name first of all, the first time ([NameGate]); then Listen
 * privately, Listening now (each friend playing as a big card, then Invite), Sent to you (what friends sent as postcards,
 * the newest first), the friends not playing, and the invites waiting. Its "⋯" holds Paste invite, Your name, Keep friends
 * in backups and What leaves your phone. With no friend yet, an empty card invites, and an example postcard shows what
 * a song from a friend looks like. On the bar ([onBack] null) it is a root; from Tools it has Back.
 */
@Composable
fun HuddlePage(onBack: (() -> Unit)?) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val toasts = LocalToasts.current
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val huddle = rememberHuddle()
    val state = rememberHuddleState()
    val cards = rememberHuddleInbox()
    val now by rememberNow()
    val matcher = rememberSongMatcher()
    val list = rememberLazyListState()
    val player = { nav.show(FullScreen.Player) }
    if (onBack == null) ReselectToTop(nav.state.reselectsOf(DockTab.HUDDLE)) { list.animateScrollToItem(0) }
    var sheet by rememberSaveable(stateSaver = HuddleSheetSaver) { mutableStateOf<HuddleSheet?>(null) }
    var opened by rememberSaveable { mutableStateOf<String?>(null) }
    var playNow by rememberSaveable { mutableStateOf(false) }
    var naming by rememberSaveable { mutableStateOf(false) }
    var passphrase by rememberSaveable { mutableStateOf(false) }
    var privacy by rememberSaveable { mutableStateOf(false) }
    // Never in the saved state: the clipboard may hold more than the invite.
    var pasted by remember { mutableStateOf<String?>(null) }
    val paste: () -> Unit = {
        val text = clipboardText(context)
        if (text != null && huddle.parse(text) != null) pasted = text else scope.launch { toasts.show(resources.getString(R.string.huddle_paste_none)) }
    }
    val gate = state?.let(::nameGate)
    PageFrame(
        topBar = { under, backdrop ->
            val menu: @Composable RowScope.() -> Unit = {
                if (state != null && gate != NameGate.NAME) {
                    var open by remember { mutableStateOf(false) }
                    Box {
                        ChromeIconButton(R.drawable.ic_more_horiz, stringResource(R.string.menu_more), { open = true })
                        LyrifyMenu(expanded = open, onDismiss = { open = false }) {
                            MenuRow(stringResource(R.string.huddle_paste), R.drawable.ic_content_paste, onClick = {
                                open = false
                                paste()
                            })
                            MenuRow(stringResource(R.string.huddle_name_title), R.drawable.ic_person, detail = state.name, onClick = {
                                open = false
                                naming = true
                            })
                            MenuRow(stringResource(R.string.huddle_backup), R.drawable.ic_lock, detail = stringResource(if (state.backupKey != null) R.string.huddle_backup_on else R.string.huddle_backup_off), onClick = {
                                open = false
                                passphrase = true
                            })
                            MenuRow(stringResource(R.string.huddle_privacy_title), R.drawable.ic_info, onClick = {
                                open = false
                                privacy = true
                            })
                        }
                    }
                }
            }
            if (onBack == null) {
                MainTopBar(DockTab.HUDDLE, under, backdrop, actions = menu)
            } else {
                BackTopBar(stringResource(R.string.huddle_title), under, backdrop, actions = menu, onBack = onBack)
            }
        },
    ) { padding ->
        val shown = state ?: return@PageFrame
        if (gate == NameGate.NAME) {
            NameGatePage(Modifier.padding(padding))
            return@PageFrame
        }
        val friends = friendsNow(shown, now)
        val playing = friends.filter { it.playing }
        val names = shown.friends.associate { it.id to it.shownName }
        val inbox = cards?.filter { it.friend in names }
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding() + 4.dp, bottom = padding.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (gate == NameGate.NEW_NAME) {
                item(key = "new name", contentType = "banner") {
                    val asking = shown.friends.first { it.asksNewName }
                    LyrifyCard(Modifier.padding(horizontal = ScreenPadding).fillMaxWidth(), color = LocalBrandColors.current.highlightContainer, contentColor = LocalBrandColors.current.onHighlightContainer) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(stringResource(R.string.huddle_new_name_text, asking.shownName, shown.name.orEmpty()), style = MaterialTheme.typography.bodyLarge)
                            LyrifyButton(stringResource(R.string.huddle_new_name_action), onClick = { naming = true }, compact = true)
                        }
                    }
                }
            }
            if (shown.friends.isNotEmpty()) {
                item(key = "private", contentType = "private") {
                    val until = shown.privateUntil?.takeIf { now < it }
                    val chrome = LocalChromeColors.current
                    val colors = MaterialTheme.colorScheme
                    val sharing = shown.friends.count { it.sharePlays }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .padding(horizontal = ScreenPadding)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .background(if (until != null) chrome.action else colors.surfaceContainerLow)
                            .toggleable(value = until != null, role = Role.Switch) { on -> if (on) sheet = HuddleSheet.Privately else scope.launch { huddle.listenPrivately(null) } }
                            .heightIn(min = 60.dp)
                            .padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
                    ) {
                        val ink = if (until != null) chrome.onAction else colors.onSurface
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.size(40.dp).background(if (until != null) lerp(chrome.action, chrome.onAction, 0.16f) else colors.background, RoundedCornerShape(14.dp)),
                        ) {
                            Icon(painterResource(if (until != null) R.drawable.ic_lock else R.drawable.ic_headphones), contentDescription = null, tint = if (until != null) ink else colors.onSurfaceVariant, modifier = Modifier.size(22.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.huddle_private), style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold), color = ink)
                            Text(
                                when (until) {
                                    null -> if (sharing == 0) stringResource(R.string.huddle_private_nobody) else pluralStringResource(R.plurals.huddle_private_sees, sharing, sharing)
                                    HuddleState.FOREVER -> stringResource(R.string.huddle_private_row_forever)
                                    else -> stringResource(R.string.huddle_private_row_until, DateFormat.getTimeFormat(context).format(Date(until)))
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (until != null) ink.copy(alpha = 0.8f) else colors.onSurfaceVariant,
                            )
                        }
                        LyrifySwitch(until != null)
                    }
                }
            }
            item(key = "listening", contentType = "heading") {
                HuddleHeading(stringResource(R.string.huddle_listening_now), playing.size.takeIf { it > 0 }?.toString()) {
                    if (shown.friends.isNotEmpty()) LyrifyButton(stringResource(R.string.huddle_row_invite), onClick = { sheet = HuddleSheet.Ticket }, kind = ButtonKind.SECONDARY, icon = R.drawable.ic_add, compact = true)
                }
            }
            item(key = "cards", contentType = "cards") {
                if (shown.friends.isEmpty()) {
                    EmptyCard(onInvite = { sheet = HuddleSheet.Ticket }, onPaste = paste)
                } else {
                    LazyRow(contentPadding = PaddingValues(horizontal = ScreenPadding), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(playing, key = { it.friend.id }) { friend ->
                            val play = friend.play
                            val song = play?.let { (matcher?.match(it.song) as? Match.Found)?.song }
                            FriendCard(
                                friend,
                                song,
                                friend.friend.shownName,
                                // Not here: their song's sheet says so, with Ask and Search on YouTube.
                                onPlayAlong = { if (song != null && play != null) playAlong(context, song, play, friend.friend.shownName, player) else sheet = HuddleSheet.Peek(friend.friend.id) },
                                onSend = { sheet = HuddleSheet.SendTo(friend.friend.id) },
                                onMore = { sheet = HuddleSheet.Menu(friend.friend.id) },
                            )
                        }
                        if (playing.isEmpty()) {
                            item(key = "nobody") {
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                                    modifier = Modifier.width(FRIEND_CARD_WIDTH).heightIn(min = INVITE_CARD_HEIGHT).clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow).padding(18.dp),
                                ) {
                                    Equaliser(MaterialTheme.colorScheme.outline, Modifier.size(20.dp, 16.dp))
                                    Text(stringResource(R.string.huddle_nobody_playing), style = MaterialTheme.typography.titleMedium)
                                    Text(stringResource(R.string.huddle_nobody_playing_text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        item(key = "invite") {
                            val outline = MaterialTheme.colorScheme.outline
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                                modifier = Modifier
                                    .width(150.dp)
                                    .heightIn(min = INVITE_CARD_HEIGHT)
                                    .clip(RoundedCornerShape(28.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                    .drawBehind {
                                        val width = 2.dp.toPx()
                                        drawRoundRect(outline, Offset(width / 2, width / 2), Size(size.width - width, size.height - width), CornerRadius(28.dp.toPx()), Stroke(width, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))))
                                    }
                                    .clickable(role = Role.Button, onClick = { sheet = HuddleSheet.Ticket })
                                    .padding(14.dp),
                            ) {
                                SeatFace(48.dp)
                                Text(stringResource(R.string.huddle_invite), style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
                                Text(stringResource(R.string.huddle_invite_card_text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                            }
                        }
                    }
                }
            }
            item(key = "sent", contentType = "heading") {
                val fresh = inbox.orEmpty().count { !it.opened }
                HuddleHeading(stringResource(R.string.huddle_inbox_title), fresh.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.huddle_new_count, it, it) }) {
                    // Every card friends sent, kept on the phone: the row shows the newest.
                    if (!inbox.isNullOrEmpty()) LyrifyButton(stringResource(R.string.lib_view_all), onClick = { nav.open(Page.HuddleCards) }, kind = ButtonKind.GHOST, compact = true)
                }
            }
            item(key = "postcards", contentType = "postcards") {
                LazyRow(contentPadding = PaddingValues(start = ScreenPadding, end = ScreenPadding, top = 8.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (inbox?.isEmpty() == true) {
                        item(key = "example") {
                            Column(Modifier.alpha(EXAMPLE_ALPHA).clearAndSetSemantics { contentDescription = resources.getString(R.string.huddle_example) }) {
                                Text(stringResource(R.string.huddle_example).uppercase(), style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
                                Postcard(
                                    stringResource(R.string.huddle_example_note),
                                    stringResource(R.string.huddle_example_friend),
                                    FriendColors.first(),
                                    stringResource(R.string.huddle_example_song),
                                    song = null,
                                    have = Match.Missing,
                                    some = null,
                                    fresh = false,
                                    tilt = -1.6f,
                                    line = stringResource(R.string.huddle_from, stringResource(R.string.huddle_example_friend)),
                                    onOpen = {},
                                    onPlay = {},
                                )
                            }
                        }
                    }
                    itemsIndexed(inbox.orEmpty(), key = { _, it -> it.id }) { at, received ->
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
            val idle = friends.filter { !it.playing }
            if (idle.isNotEmpty()) {
                item(key = "idle", contentType = "heading") { HuddleHeading(stringResource(R.string.huddle_not_playing)) }
                items(idle, key = { "friend " + it.friend.id }, contentType = { "friend" }) { friend ->
                    val name = friend.friend.shownName
                    NavRow(
                        title = name,
                        sub = friend.play?.let { play -> stringResource(R.string.huddle_played, songBy(resources, play.song), ago(play.at, now)) } ?: stringResource(R.string.huddle_nothing_yet),
                        lead = { FriendFace(name, friendColor(friend.friend.id), 44.dp, ring = FaceRing.IDLE) },
                        trailing = { ChromeIconButton(R.drawable.ic_more_horiz, stringResource(R.string.huddle_friend_more, name), { sheet = HuddleSheet.Menu(friend.friend.id) }, glass = false) },
                    ) { sheet = HuddleSheet.Menu(friend.friend.id) }
                }
            }
            if (shown.invites.isNotEmpty()) {
                item(key = "invites", contentType = "heading") { HuddleHeading(stringResource(R.string.huddle_waiting_title)) }
                items(shown.invites, key = { "invite " + it.token }, contentType = { "invite" }) { waiting ->
                    // Asked for, it is done even if the page closes meanwhile.
                    InviteRow(waiting, now) {
                        scope.launch {
                            if (!withContext(NonCancellable) { huddle.cancel(waiting.token) }) toasts.show(Toast(resources.getString(R.string.huddle_no_answer), R.drawable.ic_error, Tone.ERROR))
                        }
                    }
                }
            }
        }
    }
    val shown = state ?: return
    if (naming) {
        if (gate == NameGate.NEW_NAME) {
            NewNameDialog(shown) { naming = false }
        } else {
            NameDialog(stringResource(R.string.huddle_name_title), shown.name.orEmpty(), R.drawable.ic_person, onDismiss = { naming = false }, confirm = R.string.huddle_save) { name ->
                naming = false
                scope.launch { huddle.setName(name) }
            }
        }
    }
    if (passphrase) PassphraseDialog(onDismiss = { passphrase = false })
    if (privacy) HelpSheet(stringResource(R.string.huddle_privacy_title), stringResource(R.string.huddle_privacy), onDismiss = { privacy = false })
    pasted?.let {
        AcceptInviteSheet(it, onDismiss = { pasted = null }) { received ->
            playNow = true
            opened = received
        }
    }
    HuddleSheetsHost(sheet, LocalLyrics.current.ledger, player, nav::open) { sheet = it }
    ReceivedHost(opened, player, onSendBack = { sheet = HuddleSheet.SendTo(it) }, playNow = playNow) { opened = null }
}

/**
 * Huddle's first door: your name, before anything else (user 2026-11-22). No skip and no way to clear it later, only to
 * change it; Back leaves the page.
 */
@Composable
private fun NameGatePage(modifier: Modifier) {
    val huddle = rememberHuddle()
    val scope = rememberCoroutineScope()
    var typed by rememberSaveable { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 24.dp, vertical = 32.dp),
    ) {
        FriendFace(typed.ifBlank { "?" }, OwnFaceColor, 76.dp, ring = FaceRing.LIVE)
        Text(stringResource(R.string.huddle_gate_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp).semantics { heading() })
        Text(stringResource(R.string.huddle_gate_text), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        LyrifyField(typed, { typed = it.take(Invites.NAME_MAX) }, Modifier.fillMaxWidth().padding(top = 8.dp), label = stringResource(R.string.huddle_name_title))
        LyrifyButton(
            stringResource(R.string.huddle_save),
            onClick = {
                saving = true
                scope.launch {
                    // Asked for, it is kept even if the page closes meanwhile.
                    withContext(NonCancellable) { huddle.setName(typed) }
                    saving = false
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !saving && Invites.cleanName(typed) != null,
        )
    }
}

/** What the clipboard holds as text, if anything (Android 12+ tells the user KJB read it): only on Paste invite's tap. */
private fun clipboardText(context: Context): String? {
    val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip ?: return null
    return (0 until clip.itemCount).firstNotNullOfOrNull { clip.getItemAt(it).coerceToText(context)?.toString()?.takeIf(String::isNotBlank) }
}

/** An invite no friend joined yet: when it was sent, and Cancel, which takes the link back. 48 dp at least. */
@Composable
private fun InviteRow(invite: PendingInvite, now: Long, onCancel: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = ScreenPadding),
    ) {
        Tile(R.drawable.ic_send)
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
            Text(stringResource(R.string.huddle_tool_waiting), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(stringResource(R.string.huddle_waiting_row, ago(invite.madeAt, now)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LyrifyButton(stringResource(R.string.huddle_cancel_invite), onClick = onCancel, kind = ButtonKind.GHOST, compact = true)
    }
}

/** The backup passphrase, typed twice and hidden; at least 8 characters. Its key is worked out off the main thread (slow on purpose). */
@Composable
private fun PassphraseDialog(onDismiss: () -> Unit) {
    val resources = LocalResources.current
    val toasts = LocalToasts.current
    val huddle = rememberHuddle()
    // The key is worked out on the activity's scope: it is set even if the page closes meanwhile.
    val lifecycle = LocalLifecycleOwner.current.lifecycleScope
    // Never in the saved state: a passphrase lives only while it is typed.
    var first by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    val short = first.isNotEmpty() && first.length < HuddleBackup.PASSPHRASE_MIN
    val differ = again.isNotEmpty() && again != first
    ConfirmDialog(
        title = stringResource(R.string.huddle_passphrase_title),
        text = stringResource(R.string.huddle_passphrase_text),
        confirm = stringResource(R.string.huddle_passphrase_set),
        dismiss = stringResource(R.string.remove_cancel),
        icon = R.drawable.ic_lock,
        destructive = false,
        confirmEnabled = first.length >= HuddleBackup.PASSPHRASE_MIN && again == first,
        onConfirm = {
            val typed = first.toCharArray()
            onDismiss()
            lifecycle.launch {
                // Set even if KJB turns or closes meanwhile; the typed characters are wiped either way.
                val set = withContext(NonCancellable + Dispatchers.Default) {
                    try {
                        huddle.setPassphrase(typed)
                    } finally {
                        typed.fill(' ')
                    }
                }
                toasts.show(if (set) Toast(resources.getString(R.string.huddle_passphrase_saved), R.drawable.ic_lock) else Toast(resources.getString(R.string.lib_not_saved), R.drawable.ic_error, Tone.ERROR))
            }
        },
        onDismiss = onDismiss,
    ) {
        PassphraseField(first, { first = it }, stringResource(R.string.huddle_passphrase_field), isError = short)
        if (short) Text(stringResource(R.string.huddle_passphrase_short), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        PassphraseField(again, { again = it }, stringResource(R.string.huddle_passphrase_again), isError = differ)
        if (differ) Text(stringResource(R.string.huddle_passphrase_differ), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

/** A passphrase field: hidden as it is typed, the keyboard's password layout, no suggestions. */
@Composable
internal fun PassphraseField(value: String, onValue: (String) -> Unit, label: String, isError: Boolean = false) {
    LyrifyField(
        value,
        onValue,
        Modifier.fillMaxWidth(),
        label = label,
        isError = isError,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        visualTransformation = PasswordVisualTransformation(),
    )
}

/**
 * The restore summary's Huddle friends (plan Q3.3), under the sections: a check box, and while it is ticked the passphrase
 * they were locked with; [wrong] after one that did not open them. Without it the rest restores and Huddle starts over.
 */
@Composable
internal fun HuddleRestoreRow(checked: Boolean, passphrase: String, wrong: Boolean, enabled: Boolean, onCheck: (Boolean) -> Unit, onPassphrase: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onCheck),
        ) {
            LyrifyCheckbox(checked)
            Text(stringResource(R.string.huddle_restore_row), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        }
        if (checked) {
            PassphraseField(passphrase, onPassphrase, stringResource(R.string.huddle_passphrase_field), isError = wrong)
            if (wrong) Text(stringResource(R.string.huddle_restore_wrong), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            Fact(R.drawable.ic_info, stringResource(R.string.huddle_restore_one_phone))
        }
    }
}

/** The invite card and the "nobody's playing" card stand as tall as a friend's card. */
private val INVITE_CARD_HEIGHT = 210.dp

/** The example postcard is faded: it is no song anyone sent. */
private const val EXAMPLE_ALPHA = 0.6f
