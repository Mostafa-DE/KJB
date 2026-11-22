package app.lyrify.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.res.Resources
import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.lyrify.R
import app.lyrify.huddle.Card
import app.lyrify.huddle.CardKind
import app.lyrify.huddle.CardSong
import app.lyrify.huddle.Friend
import app.lyrify.huddle.FriendPlay
import app.lyrify.huddle.Huddle
import app.lyrify.huddle.HuddleBackup
import app.lyrify.huddle.HuddleGraph
import app.lyrify.huddle.HuddleState
import app.lyrify.huddle.Invited
import app.lyrify.huddle.PendingInvite
import app.lyrify.huddle.Received
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Collator
import java.util.Date

// Huddle's page, its Tools row, the start screen's "From <name>" row and its host in the app (plan part 3 and 3b).

/** Huddle's line on Tools: how many friends, with the cards not opened yet as its badge; what it is for before the first. */
fun huddleEntry(state: HuddleState): EntryRow = when {
    state.friends.isNotEmpty() -> {
        val here = state.friends.mapTo(HashSet()) { it.id }
        EntryRow(ToolLine.Count(R.plurals.huddle_friends, state.friends.size), state.inbox.count { !it.opened && it.friend in here }.takeIf { it > 0 })
    }
    state.invites.isNotEmpty() -> EntryRow(ToolLine.Text(R.string.huddle_tool_waiting), null)
    else -> EntryRow(ToolLine.Text(R.string.huddle_tool_none), null)
}

/**
 * The card the start screen's "From <name>" row shows, from a friend still here: the newest not opened yet, else the newest
 * card, opened, until it is dismissed (an older one never takes its place); null for none.
 */
fun newestCard(state: HuddleState): Received? {
    val here = state.friends.mapTo(HashSet()) { it.id }
    return state.inbox.filter { !it.opened && it.friend in here }.maxByOrNull { it.at }
        ?: state.inbox.filter { it.friend in here }.maxByOrNull { it.at }?.takeUnless { it.dismissed }
}

/** Friends as Send to a friend lists them: the one you sent to last first, then the others by the name you see. */
fun sendOrder(friends: List<Friend>): List<Friend> {
    val collator = Collator.getInstance()
    return friends.sortedWith(compareByDescending<Friend> { it.lastSentAt ?: Long.MIN_VALUE }.thenBy(collator) { it.shownName })
}

/** A friend on the Huddle page, by the name you see: their latest play, and whether it may still be playing. */
data class FriendNow(val friend: Friend, val play: FriendPlay?, val playing: Boolean)

/** Every friend with their latest play ([FriendNow]); a play is still playing until its length and a minute have passed. */
fun friendsNow(state: HuddleState, now: Long): List<FriendNow> {
    val collator = Collator.getInstance()
    val latest = state.plays.groupBy { it.friend }.mapValues { (_, plays) -> plays.maxBy { it.at } }
    return state.friends.sortedWith(compareBy(collator) { it.shownName }).map { friend ->
        val play = latest[friend.id]
        FriendNow(friend, play, playing = play != null && now <= play.at + play.song.durationMs + PLAYING_GRACE_MS)
    }
}

/** How long past its end a friend's play still shows as playing: a pause, a seek back, a clock a little off. */
private const val PLAYING_GRACE_MS: Long = 60_000

/** How often the page's times and "Playing" lines are worked out again: as often as friends' plays come while it is open. */
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

/** The Tools row's state ([huddleEntry]); null until Huddle is read. */
@Composable
fun huddleRow(): EntryRow? = rememberHuddleState()?.let(::huddleEntry)

/** "Paper Lantern by Kit Calloway", or the title alone without an artist. */
internal fun songBy(resources: Resources, song: CardSong): String = song.artist?.let { resources.getString(R.string.huddle_song_by, song.title, it) } ?: song.title

/** What a card is, in a line: the song and its artist, or "Album · <name>", "Playlist · <name>". */
@Composable
internal fun cardLine(card: Card): String = when (card.kind) {
    CardKind.SONG -> card.songs.first().let { song -> listOfNotNull(song.title, song.artist).joinToString(" · ") }
    CardKind.ALBUM -> stringResource(R.string.lib_source_album, card.name ?: card.songs.first().album.orEmpty())
    CardKind.PLAYLIST -> stringResource(R.string.lib_source_playlist, card.name.orEmpty())
}

/** A card's tile icon: a song, an album, a playlist. */
private fun cardIcon(kind: CardKind): Int = when (kind) {
    CardKind.SONG -> R.drawable.ic_music_note
    CardKind.ALBUM -> R.drawable.ic_album
    CardKind.PLAYLIST -> R.drawable.ic_queue_music
}

/** "2 hours ago", from [now]. */
private fun ago(at: Long, now: Long): String = DateUtils.getRelativeTimeSpanString(at, now, DateUtils.MINUTE_IN_MILLIS).toString()

/** The wall clock, worked out again every [TICK_MS]. */
@Composable
private fun rememberNow(): Long = produceState(System.currentTimeMillis()) {
    while (true) {
        delay(TICK_MS)
        value = System.currentTimeMillis()
    }
}.value

/**
 * Huddle in the app, once (MainActivity): it syncs while KJB is in front (when it comes to the front, then each hour, every
 * 30 s while the Huddle page is open), and an [invite] link another app opened asks whether to add the friend
 * ([AcceptInviteSheet]); [onInviteTaken] once it is shown. A song that came with the invite opens after it ([ReceivedHost]).
 */
@Composable
fun HuddleHost(invite: String?, onInviteTaken: () -> Unit) {
    val huddle = rememberHuddle()
    LifecycleStartEffect(huddle) {
        val front = huddle.syncWhileInFront()
        onStopOrDispose { front.cancel() }
    }
    var link by rememberSaveable { mutableStateOf<String?>(null) }
    var opened by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(invite) {
        if (invite != null) {
            link = invite
            onInviteTaken()
        }
    }
    link?.let { AcceptInviteSheet(it, onDismiss = { link = null }) { received -> opened = received } }
    ReceivedHost(opened, playNow = true) { opened = null }
}

/**
 * The start screen's row (plan part 3; at the top of Library's Songs, where For you sits too): the card [newestCard] picks,
 * "From <name>" over what it is, in coral until it is opened, so who sent what plays stays in view. A tap opens it
 * ([ReceivedHost]); Dismiss puts it away, and it stays on the Huddle page. Nothing without one.
 */
@Composable
fun HuddleFromRow() {
    val huddle = rememberHuddle()
    val state = rememberHuddleState() ?: return
    val scope = rememberCoroutineScope()
    val brand = LocalBrandColors.current
    var opened by rememberSaveable { mutableStateOf<String?>(null) }
    newestCard(state)?.let { received ->
        val name = state.friends.firstOrNull { it.id == received.friend }?.shownName.orEmpty()
        NavRow(
            title = stringResource(R.string.huddle_from, name),
            sub = cardLine(received.card),
            lead = { if (received.opened) Tile(R.drawable.ic_send) else Tile(R.drawable.ic_send, brand.highlightContainer, brand.onHighlightContainer) },
            trailing = { ChromeIconButton(R.drawable.ic_close, stringResource(R.string.huddle_dismiss), { scope.launch { huddle.opened(received.id, dismissed = true) } }, glass = false) },
        ) { opened = received.id }
    }
    ReceivedHost(opened) { opened = null }
}

/**
 * Tools > Huddle: Invite a friend and Paste invite, what friends sent (the newest first, those not opened in coral), the
 * friends with what they play now (each with Share what I play, Rename and Remove under its "⋯"), invites waiting with
 * Cancel, then your name, Listen privately, Keep friends in backups, and what leaves your phone. While it is open
 * friends' plays come every 30 s ([Huddle.pageOpen]).
 */
@Composable
fun HuddlePage(onBack: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val toasts = LocalToasts.current
    val scope = rememberCoroutineScope()
    val huddle = rememberHuddle()
    val state = rememberHuddleState()
    val now = rememberNow()
    LifecycleResumeEffect(huddle) {
        huddle.pageOpen.value = true
        onPauseOrDispose { huddle.pageOpen.value = false }
    }
    var naming by rememberSaveable { mutableStateOf(false) }
    var inviting by rememberSaveable { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf<String?>(null) }
    var removing by rememberSaveable { mutableStateOf<String?>(null) }
    var privately by rememberSaveable { mutableStateOf(false) }
    var passphrase by rememberSaveable { mutableStateOf(false) }
    // Never in the saved state: the clipboard may hold more than the invite.
    var pasted by remember { mutableStateOf<String?>(null) }
    var opened by rememberSaveable { mutableStateOf<String?>(null) }
    val failed = { scope.launch { toasts.show(Toast(resources.getString(R.string.huddle_no_answer), R.drawable.ic_error, Tone.ERROR)) } }
    // One invite at a time: a second tap while the first is made is not a second link.
    var making by remember { mutableStateOf(false) }
    val invite: () -> Unit = {
        if (!making) {
            making = true
            scope.launch {
                try {
                    when (val invited = huddle.invite(null)) {
                        is Invited.Link -> toasts.say(resources, shareMessage(context, resources.getString(R.string.huddle_invite_message, invited.text)))
                        Invited.NoName -> naming = true
                        Invited.NoAnswer -> failed()
                    }
                } finally {
                    making = false
                }
            }
        }
    }
    PageFrame(
        topBar = { under, backdrop ->
            LyrifyTopBar(
                title = { Text(stringResource(R.string.huddle_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                under = under,
                backdrop = backdrop,
                navigation = { ChromeIconButton(R.drawable.ic_arrow_back, stringResource(R.string.check_back), onBack) },
            )
        },
    ) { padding ->
        val shown = state ?: return@PageFrame
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "actions", contentType = "actions") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = ScreenPadding)) {
                    LyrifyButton(stringResource(R.string.huddle_invite), onClick = { if (shown.name == null) inviting = true else invite() }, modifier = Modifier.weight(1f), icon = R.drawable.ic_send)
                    LyrifyButton(stringResource(R.string.huddle_paste), onClick = {
                        val text = clipboardText(context)
                        if (text != null && huddle.parse(text) != null) pasted = text else scope.launch { toasts.show(resources.getString(R.string.huddle_paste_none)) }
                    }, modifier = Modifier.weight(1f), kind = ButtonKind.SECONDARY, icon = R.drawable.ic_content_paste)
                }
            }
            if (shown.friends.isEmpty() && shown.invites.isEmpty()) {
                item(key = "empty", contentType = "empty") {
                    EmptyState(EmptyArt.Icon(R.drawable.ic_group), stringResource(R.string.huddle_empty_title), text = stringResource(R.string.huddle_empty_text))
                }
            }
            val names = shown.friends.associate { it.id to it.shownName }
            val inbox = shown.inbox.filter { it.friend in names }
            if (inbox.isNotEmpty()) {
                item(key = "inbox", contentType = "title") { PageSectionTitle(stringResource(R.string.huddle_inbox_title)) }
                items(inbox, key = { "card " + it.id }, contentType = { "card" }) { received ->
                    ReceivedRow(received, names.getValue(received.friend), now) { opened = received.id }
                }
            }
            if (shown.friends.isNotEmpty()) {
                item(key = "friends", contentType = "title") { PageSectionTitle(stringResource(R.string.huddle_friends_title)) }
                items(friendsNow(shown, now), key = { "friend " + it.friend.id }, contentType = { "friend" }) { friend ->
                    FriendRow(
                        friend,
                        now,
                        onShare = { on -> scope.launch { huddle.sharePlays(friend.friend.id, on) } },
                        onRename = { renaming = friend.friend.id },
                        onRemove = { removing = friend.friend.id },
                    )
                }
            }
            if (shown.invites.isNotEmpty()) {
                item(key = "invites", contentType = "title") { PageSectionTitle(stringResource(R.string.huddle_waiting_title)) }
                items(shown.invites, key = { "invite " + it.token }, contentType = { "invite" }) { waiting ->
                    // Asked for, it is done even if the page closes meanwhile.
                    InviteRow(waiting, now) { scope.launch { if (!withContext(NonCancellable) { huddle.cancel(waiting.token) }) failed() } }
                }
            }
            item(key = "settings", contentType = "settings") {
                HuddleSettings(shown, now, onName = { naming = true }, onPrivately = { on -> if (on) privately = true else scope.launch { huddle.listenPrivately(null) } }) { passphrase = true }
            }
            item(key = "privacy", contentType = "privacy") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(horizontal = ScreenPadding + 4.dp, vertical = 8.dp)) {
                    SectionTitle(stringResource(R.string.huddle_privacy_title))
                    Text(stringResource(R.string.huddle_privacy), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    val shown = state ?: return
    if (naming || inviting) {
        NameDialog(stringResource(R.string.huddle_name_title), shown.name.orEmpty(), R.drawable.ic_person, onDismiss = { naming = false; inviting = false }, confirm = R.string.huddle_save) { name ->
            val then = inviting
            naming = false
            inviting = false
            scope.launch { if (huddle.setName(name) && then) invite() }
        }
    }
    shown.friends.firstOrNull { it.id == renaming }?.let { friend ->
        NameDialog(stringResource(R.string.huddle_rename_title, friend.shownName), friend.shownName, R.drawable.ic_edit, onDismiss = { renaming = null }, confirm = R.string.lib_rename) { name ->
            renaming = null
            // The name they gave back when the field is left as it is.
            scope.launch { huddle.rename(friend.id, name.takeUnless { it == friend.name }) }
        }
    }
    shown.friends.firstOrNull { it.id == removing }?.let { friend ->
        ConfirmDialog(
            title = stringResource(R.string.huddle_remove_title, friend.shownName),
            text = stringResource(R.string.huddle_remove_text),
            confirm = stringResource(R.string.huddle_remove),
            dismiss = stringResource(R.string.remove_cancel),
            icon = R.drawable.ic_person_off,
            onConfirm = {
                removing = null
                scope.launch {
                    // Asked for, it is done even if the page closes meanwhile.
                    if (withContext(NonCancellable) { huddle.remove(friend.id) }) toasts.show(Toast(resources.getString(R.string.huddle_removed, friend.shownName), R.drawable.ic_person_off)) else failed()
                }
            },
            onDismiss = { removing = null },
        )
    }
    if (privately) PrivatelySheet(onDismiss = { privately = false }) { until -> scope.launch { huddle.listenPrivately(until) } }
    if (passphrase) PassphraseDialog(onDismiss = { passphrase = false })
    pasted?.let { AcceptInviteSheet(it, onDismiss = { pasted = null }) { received -> opened = received } }
    ReceivedHost(opened) { opened = null }
}

/** A section's title on the page, as the AI tab's. */
@Composable
private fun PageSectionTitle(text: String) {
    SectionTitle(text, Modifier.padding(start = ScreenPadding + 4.dp, end = ScreenPadding, top = 8.dp))
}

/** What the clipboard holds as text, if anything (Android 12+ tells the user KJB read it): only on Paste invite's tap. */
private fun clipboardText(context: Context): String? {
    val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip ?: return null
    return (0 until clip.itemCount).firstNotNullOfOrNull { clip.getItemAt(it).coerceToText(context)?.toString()?.takeIf(String::isNotBlank) }
}

/** A card a friend sent: what it is, who sent it and when; not opened yet, its title in coral. */
@Composable
private fun ReceivedRow(received: Received, from: String, now: Long, onClick: () -> Unit) {
    val card = received.card
    val title = when (card.kind) {
        CardKind.SONG -> card.songs.first().title
        else -> card.name ?: card.songs.first().album.orEmpty()
    }
    NavRow(
        title = title,
        sub = listOf(stringResource(R.string.huddle_from, from), ago(received.at, now)).joinToString(" · "),
        titleColor = if (received.opened) Color.Unspecified else LocalBrandColors.current.highlight,
        lead = { Tile(cardIcon(card.kind)) },
        onClick = onClick,
    )
}

/**
 * A friend: the name you see, what they play now or played last (only from friends who share with you), and the "⋯"
 * with Share what I play (checked while on), Rename and Remove. A tap on the row opens the same menu.
 */
@Composable
private fun FriendRow(friend: FriendNow, now: Long, onShare: (Boolean) -> Unit, onRename: () -> Unit, onRemove: () -> Unit) {
    val resources = LocalResources.current
    var menu by remember { mutableStateOf(false) }
    val name = friend.friend.shownName
    val line = friend.play?.let { play ->
        if (friend.playing) stringResource(R.string.huddle_playing, songBy(resources, play.song)) else stringResource(R.string.huddle_played, songBy(resources, play.song), ago(play.at, now))
    }
    val sharing = friend.friend.sharePlays
    NavRow(
        title = name,
        sub = line,
        lead = { Tile(R.drawable.ic_person) },
        trailing = {
            Box {
                ChromeIconButton(R.drawable.ic_more_horiz, stringResource(R.string.huddle_friend_more, name), { menu = true }, glass = false)
                LyrifyMenu(expanded = menu, onDismiss = { menu = false }) {
                    MenuRow(
                        stringResource(R.string.huddle_share_plays),
                        R.drawable.ic_visibility,
                        selected = sharing,
                        detail = stringResource(if (sharing) R.string.huddle_share_plays_on else R.string.huddle_share_plays_off, name),
                        onClick = {
                            menu = false
                            onShare(!sharing)
                        },
                    )
                    MenuRow(stringResource(R.string.lib_rename), R.drawable.ic_edit, onClick = {
                        menu = false
                        onRename()
                    })
                    MenuRow(stringResource(R.string.huddle_remove), R.drawable.ic_person_off, destructive = true, onClick = {
                        menu = false
                        onRemove()
                    })
                }
            }
        },
    ) { menu = true }
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

/** Your name, Listen privately (on until a time, or until turned off) and Keep friends in backups, on one card. */
@Composable
private fun HuddleSettings(state: HuddleState, now: Long, onName: () -> Unit, onPrivately: (Boolean) -> Unit, onBackup: () -> Unit) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val until = state.privateUntil?.takeIf { now < it }
    GroupedRows(Modifier.padding(top = 8.dp)) {
        GroupedRow(
            R.drawable.ic_person,
            stringResource(R.string.huddle_name_title),
            tile = colors.surfaceContainerHighest,
            onTile = colors.onSurfaceVariant,
            first = true,
            supporting = state.name?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
            onClick = onName,
        )
        SwitchRow(
            R.drawable.ic_visibility_off,
            R.string.huddle_private,
            detail = when (until) {
                null -> stringResource(R.string.huddle_private_off)
                HuddleState.FOREVER -> stringResource(R.string.huddle_private_forever)
                else -> stringResource(R.string.huddle_private_until, DateFormat.getTimeFormat(context).format(Date(until)))
            },
            checked = until != null,
            onSwitch = onPrivately,
        )
        GroupedRow(
            R.drawable.ic_lock,
            stringResource(R.string.huddle_backup),
            tile = colors.surfaceContainerHighest,
            onTile = colors.onSurfaceVariant,
            supporting = { Text(stringResource(if (state.backupKey != null) R.string.huddle_backup_on else R.string.huddle_backup_off)) },
            onClick = onBackup,
        )
    }
}

/** Listen privately's two lengths: an hour, or until turned off. */
@Composable
private fun PrivatelySheet(onDismiss: () -> Unit, onPick: (Long) -> Unit) {
    LyrifySheet(
        onDismiss = onDismiss,
        spacing = 12.dp,
        footer = {
            LyrifyButton(stringResource(R.string.huddle_private_hour), onClick = {
                onDismiss()
                onPick(System.currentTimeMillis() + Huddle.HOUR_MS)
            }, modifier = Modifier.fillMaxWidth())
            LyrifyButton(stringResource(R.string.huddle_private_always), onClick = {
                onDismiss()
                onPick(HuddleState.FOREVER)
            }, modifier = Modifier.fillMaxWidth(), kind = ButtonKind.SECONDARY)
        },
    ) {
        Text(stringResource(R.string.huddle_private), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.huddle_private_off), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
