package app.lyrify.ui

import android.content.Context
import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.lyrify.Lyrify
import app.lyrify.R
import app.lyrify.data.LedgerState
import app.lyrify.huddle.CardKind
import app.lyrify.huddle.CardSong
import app.lyrify.huddle.Friend
import app.lyrify.huddle.FriendPlay
import app.lyrify.huddle.Huddle
import app.lyrify.huddle.HuddleState
import app.lyrify.huddle.Invites
import app.lyrify.huddle.Match
import app.lyrify.huddle.Messages
import app.lyrify.huddle.match
import app.lyrify.library.LibraryGraph
import app.lyrify.library.LibraryState
import app.lyrify.library.Song
import app.lyrify.player.PlayerGraph
import app.lyrify.player.QueueSource
import app.lyrify.player.SourceKind
import java.util.Date
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

// Huddle's sheets: Send to a friend, a card a friend sent, a friend's song and their "⋯", Listen privately, and the
// restore's Huddle friends.

/**
 * Send to a friend (plan Q3.5), from a song's sheet, the player, an album's and a playlist's "⋯", or to one friend [to]
 * (Send back, a friend's "⋯"): [songs] as one card of [kind] with the album's or playlist's [name], and a note. Your name
 * first when it is not set yet (typed once, Q3.4). A friend's row sends at once, with "Sent to <name>" and Undo for as long
 * as the message shows (the card waits that long); the one you sent to last comes first. Invite a friend hands over the
 * invite ticket with the card on it ([InviteTicketSheet]). The toast runs on the activity's scope, so it outlives the sheet.
 */
@Composable
internal fun SendToFriendSheet(songs: List<Song>, kind: CardKind, name: String?, to: String? = null, onDismiss: () -> Unit) {
    val resources = LocalResources.current
    val toasts = LocalToasts.current
    val a11y = LocalAccessibilityManager.current
    val lifecycle = LocalLifecycleOwner.current.lifecycleScope
    val brand = LocalBrandColors.current
    val huddle = rememberHuddle()
    val state = rememberHuddleState() ?: return
    if (songs.isEmpty()) {
        // Nothing to send: the songs left the library meanwhile, or the playlist is empty.
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    var note by rememberSaveable { mutableStateOf("") }
    var typed by rememberSaveable { mutableStateOf("") }
    var nameMissing by remember { mutableStateOf(false) }
    var ticket by rememberSaveable { mutableStateOf(false) }
    // One send per sheet: a second tap while the first goes is not a second card.
    var busy by remember { mutableStateOf(false) }
    val needsName = state.name == null
    // Your name set first when it was not; false while the field holds none.
    suspend fun named(): Boolean = !needsName || huddle.setName(typed)
    fun card() = Messages.card(kind, songs, name, note)
    fun ready(): Boolean = (!needsName || Invites.cleanName(typed) != null).also { nameMissing = !it }
    if (ticket) {
        InviteTicketSheet(card(), onDismiss = onDismiss)
        return
    }
    val first = songs.first()
    val friends = sendOrder(state.friends).filter { to == null || it.id == to }
    LyrifySheet(onDismiss = onDismiss, spacing = 12.dp) {
        Text(friends.singleOrNull()?.takeIf { to != null }?.let { stringResource(R.string.huddle_send_to, it.shownName) } ?: stringResource(R.string.huddle_send), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        Text(
            when (kind) {
                CardKind.SONG -> listOfNotNull(first.title, first.artist).joinToString(" · ")
                CardKind.ALBUM -> stringResource(R.string.lib_source_album, name.orEmpty())
                CardKind.PLAYLIST -> stringResource(R.string.lib_source_playlist, name.orEmpty())
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (needsName) {
            LyrifyField(typed, { typed = it.take(Invites.NAME_MAX) }, Modifier.fillMaxWidth(), label = stringResource(R.string.huddle_name_title), isError = nameMissing)
            Text(stringResource(R.string.huddle_name_text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LyrifyField(note, { note = it.take(Messages.NOTE_MAX) }, Modifier.fillMaxWidth(), label = stringResource(R.string.huddle_note))
        Column {
            friends.forEach { friend ->
                NavRow(
                    title = friend.shownName,
                    sub = null,
                    description = stringResource(R.string.huddle_send_to, friend.shownName),
                    lead = { FriendFace(friend.shownName, friendColor(friend.id), 44.dp) },
                    trailing = { Icon(painterResource(R.drawable.ic_send), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp)) },
                ) {
                    if (!busy && ready()) {
                        busy = true
                        val card = card()
                        onDismiss()
                        lifecycle.launch {
                            if (!named()) return@launch
                            val toast = Toast(resources.getString(R.string.huddle_sent, friend.shownName), R.drawable.ic_send, action = resources.getString(R.string.player_undo))
                            val id = huddle.send(friend.id, card, holdMs = toastMillis(toast, a11y))
                            if (id == null) toasts.say(resources, R.string.lib_not_saved) else if (toasts.show(toast)) huddle.unsend(id)
                        }
                    }
                }
            }
            if (to == null) {
                NavRow(
                    title = stringResource(R.string.huddle_invite),
                    sub = stringResource(R.string.huddle_invite_gets_it),
                    titleColor = brand.highlight,
                    lead = { Tile(R.drawable.ic_add, MaterialTheme.colorScheme.surfaceContainerHigh, brand.highlight) },
                    trailing = null,
                ) {
                    if (!busy && ready()) {
                        busy = true
                        lifecycle.launch {
                            if (named()) ticket = true
                            busy = false
                        }
                    }
                }
            }
        }
    }
}

/**
 * A card a friend sent ([id] in Huddle's inbox), opened from Library's card, the Huddle page or an invite: the sheet
 * ([ReceivedSheet]), and over it, for a song you do not have, YouTube's search ([YouTubeSearchSheet], KJB Downloads); back
 * from the search the card shows again, with the answers to "Is this the song?" given before. [playNow]: a song you have
 * plays at once. [onPlayer] shows the player; [onSendBack] answers the friend with a song of your own.
 */
@Composable
internal fun ReceivedHost(id: String?, onPlayer: () -> Unit, onSendBack: (String) -> Unit, playNow: Boolean = false, onClose: () -> Unit) {
    var youtube by rememberSaveable { mutableStateOf<String?>(null) }
    // Answers to "Is this the song?", by the song's place in the card; here, so they outlast the search.
    var said by rememberSaveable(id) { mutableStateOf(emptyMap<Int, Boolean>()) }
    val query = youtube
    if (query != null) {
        YouTubeSearchSheet(query, onDismiss = { youtube = null }, onStarted = { youtube = null })
        return
    }
    if (id != null) ReceivedSheet(id, playNow, said, onSaid = { said = said + it }, onYouTube = { youtube = it }, onPlayer = onPlayer, onSendBack = onSendBack, onDismiss = onClose)
}

/**
 * The card, opened (the mockup's 4.2 and 4.3): who sent it and when, the cover tilted like a gift (yours, else their
 * colour's pattern) with their note stuck on it, what it is, and whether you have it ([match]): a song found plays (Play,
 * Send back); one found only loosely asks "Is this the song?" first; one not here says so, with Ask <name> (your phone's
 * share sheet, any chat) and Search on YouTube. An album or a playlist lists each song with how it was found, plays the
 * ones you have, and asks for the rest at once. What plays is the card's own queue, "From <name>", and the player shows.
 */
@Composable
private fun ReceivedSheet(
    id: String,
    playNow: Boolean,
    said: Map<Int, Boolean>,
    onSaid: (Pair<Int, Boolean>) -> Unit,
    onYouTube: (String) -> Unit,
    onPlayer: () -> Unit,
    onSendBack: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val toasts = LocalToasts.current
    val scope = rememberCoroutineScope()
    val huddle = rememberHuddle()
    val state = rememberHuddleState() ?: return
    val received = state.inbox.firstOrNull { it.id == id }
    val friend = received?.let { card -> state.friends.firstOrNull { it.id == card.friend } }
    val matcher = rememberSongMatcher()
    val found = remember(matcher, received?.card) { received?.card?.let { card -> matcher?.let { card.songs.map(it::match) } } }
    var asking by rememberSaveable { mutableStateOf<Int?>(null) }
    LaunchedEffect(id) { huddle.opened(id) }
    if (received == null || friend == null) {
        // Gone meanwhile: the friend removed you, and their cards went with them.
        LaunchedEffect(id) { onDismiss() }
        return
    }
    val card = received.card
    val from = friend.shownName
    val color = friendColor(friend.id)
    val have = found?.mapIndexedNotNull { at, match ->
        when (match) {
            is Match.Found -> match.song
            is Match.Maybe -> match.song.takeIf { said[at] == true }
            Match.Missing -> null
        }
    }.orEmpty()
    val missing = found?.indices?.filter { at -> found[at] == Match.Missing || (found[at] is Match.Maybe && said[at] == false) }.orEmpty()
    val play = { songs: List<Song> ->
        Lyrify.playback(context).play(songs.map { it.ref }, 0, QueueSource(SourceKind.HUDDLE, received.id, from))
        onDismiss()
        onPlayer()
    }
    // Ask <name>: the songs not here, in one message for any chat.
    val askAll: () -> Unit = {
        val songs = missing.map { card.songs[it] }
        val text = songs.singleOrNull()?.let { resources.getString(R.string.huddle_ask_message, songBy(resources, it)) }
            ?: resources.getString(R.string.huddle_ask_message_many, songs.joinToString("\n") { songBy(resources, it) })
        scope.launch { toasts.say(resources, shareMessage(context, text)) }
    }
    val sendBack = {
        onDismiss()
        onSendBack(friend.id)
    }
    if (playNow && card.kind == CardKind.SONG) {
        LaunchedEffect(found) { (found?.singleOrNull() as? Match.Found)?.let { play(listOf(it.song)) } }
    }
    if (card.kind == CardKind.SONG) {
        val song = card.songs.first()
        val match = found?.firstOrNull()
        val unsure = match is Match.Maybe && said[0] == null
        LyrifySheet(
            onDismiss = onDismiss,
            spacing = 10.dp,
            footer = {
                when {
                    match is Match.Found -> {
                        LyrifyButton(stringResource(R.string.huddle_play), onClick = { play(listOf(match.song)) }, modifier = Modifier.fillMaxWidth(), icon = R.drawable.ic_play_arrow_fill)
                        LyrifyButton(stringResource(R.string.huddle_send_back), onClick = sendBack, modifier = Modifier.fillMaxWidth(), kind = ButtonKind.SECONDARY, icon = R.drawable.ic_send)
                    }
                    match is Match.Maybe && unsure -> {
                        LyrifyButton(stringResource(R.string.huddle_maybe_yes), onClick = { play(listOf(match.song)) }, modifier = Modifier.fillMaxWidth(), icon = R.drawable.ic_play_arrow_fill)
                        LyrifyButton(stringResource(R.string.huddle_maybe_no), onClick = { onSaid(0 to false) }, modifier = Modifier.fillMaxWidth(), kind = ButtonKind.SECONDARY)
                    }
                    match != null -> {
                        LyrifyButton(stringResource(R.string.huddle_ask, from), onClick = askAll, modifier = Modifier.fillMaxWidth(), icon = R.drawable.ic_send)
                        LyrifyButton(stringResource(R.string.huddle_youtube), onClick = { onYouTube(youTubeQuery(song)) }, modifier = Modifier.fillMaxWidth(), kind = ButtonKind.SECONDARY, icon = R.drawable.ic_search)
                    }
                    else -> LyrifyButton(stringResource(R.string.huddle_play), onClick = {}, modifier = Modifier.fillMaxWidth(), enabled = false)
                }
            },
        ) {
            SentBy(card.kind, from, color, received.at)
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp)) {
                Box(Modifier.rotate(-4f).shadow(14.dp, RoundedCornerShape(26.dp), ambientColor = LocalChromeColors.current.shadowStrong, spotColor = LocalChromeColors.current.shadowStrong)) {
                    FriendArt((match as? Match.Found)?.song ?: (match as? Match.Maybe)?.song, color, RECEIVED_COVER, RoundedCornerShape(26.dp))
                }
                card.note?.let {
                    Text(
                        stringResource(R.string.huddle_note_quoted, it),
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                        color = BrandLilac,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .offset(y = 6.dp)
                            .widthIn(max = 200.dp)
                            .rotate(2f)
                            .background(lerp(color, BrandBlack, 0.16f), RoundedCornerShape(18.dp, 18.dp, 6.dp, 18.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
            Text(song.title, style = ReceivedTitle, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            song.artist?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
            if (match != null) HaveChip(if (said[0] == false) Match.Missing else match, Modifier.align(Alignment.CenterHorizontally))
            when {
                match is Match.Maybe && unsure -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(12.dp),
                ) {
                    SongCover(SongArt(match.song.path, match.song.ref.mediaStoreId), size = 48.dp)
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.huddle_maybe_title), style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold))
                        Text(localSong(match.song), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                match != null && match !is Match.Found -> Text(stringResource(R.string.huddle_missing, from), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
        }
        return
    }
    LyrifySheet(
        onDismiss = onDismiss,
        spacing = 12.dp,
        footer = {
            if (have.isNotEmpty()) {
                LyrifyButton(pluralStringResource(R.plurals.huddle_play_found, have.size, have.size), onClick = { play(have) }, modifier = Modifier.fillMaxWidth(), icon = R.drawable.ic_play_arrow_fill)
            }
            if (missing.isNotEmpty()) LyrifyButton(stringResource(R.string.huddle_ask, from), onClick = askAll, modifier = Modifier.fillMaxWidth(), kind = ButtonKind.SECONDARY)
            LyrifyButton(stringResource(R.string.huddle_send_back), onClick = sendBack, modifier = Modifier.fillMaxWidth(), kind = ButtonKind.SECONDARY, icon = R.drawable.ic_send)
        },
    ) {
        SentBy(card.kind, from, color, received.at)
        Fact(if (card.kind == CardKind.ALBUM) R.drawable.ic_album else R.drawable.ic_queue_music, cardLine(card))
        card.note?.let { Fact(R.drawable.ic_notes, stringResource(R.string.huddle_note_quoted, it)) }
        if (missing.isNotEmpty()) Text(pluralStringResource(R.plurals.huddle_missing_some, missing.size, missing.size), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column {
            card.songs.forEachIndexed { at, song ->
                CardSongRow(song, match = found?.getOrNull(at), said = said[at], onAsk = { asking = at }, onGetIt = { onYouTube(youTubeQuery(song)) })
            }
        }
    }
    asking?.let { at ->
        (found?.getOrNull(at) as? Match.Maybe)?.let { maybe ->
            ConfirmDialog(
                title = stringResource(R.string.huddle_maybe_title),
                text = localSong(maybe.song),
                confirm = stringResource(R.string.huddle_yes),
                dismiss = stringResource(R.string.huddle_maybe_no),
                icon = R.drawable.ic_help,
                destructive = false,
                onConfirm = {
                    onSaid(at to true)
                    asking = null
                },
                onDismiss = {
                    onSaid(at to false)
                    asking = null
                },
            )
        }
    }
}

/** Who sent a card ("Wren sent you a song", on their face) and when: the top of the card opened, a heading for TalkBack. */
@Composable
private fun SentBy(kind: CardKind, from: String, color: Color, at: Long) {
    val context = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) { heading() }) {
        FriendFace(from, color, 40.dp, gap = LocalChromeColors.current.sheet)
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(
                    when (kind) {
                        CardKind.SONG -> R.string.huddle_sent_song
                        CardKind.ALBUM -> R.string.huddle_sent_album
                        CardKind.PLAYLIST -> R.string.huddle_sent_playlist
                    },
                    from,
                ),
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
            )
            Text(whenSent(context, at, System.currentTimeMillis()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** What YouTube is asked for a song a friend sent: its artist and title. */
private fun youTubeQuery(song: CardSong): String = listOfNotNull(song.artist, song.title).joinToString(" ")

/** A song of this library in one line, for "Is this the song?": its title, artist, album and length. */
@Composable
private fun localSong(song: Song): String = listOfNotNull(song.title, song.artist, song.album, songLength(song.durationMs)).joinToString(" · ")

/**
 * A song of an album or playlist a friend sent: how it was found here (an icon, read by TalkBack with the song), its title
 * and artist, and what to do: "Is this it?" for a loose match not answered yet, Get it for one not here.
 */
@Composable
private fun CardSongRow(song: CardSong, match: Match?, said: Boolean?, onAsk: () -> Unit, onGetIt: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val here = match is Match.Found || (match is Match.Maybe && said == true)
    val unsure = match is Match.Maybe && said == null
    val (icon, words) = when {
        match == null -> null to null
        here -> R.drawable.ic_check_circle to stringResource(R.string.huddle_found)
        unsure -> R.drawable.ic_help to stringResource(R.string.huddle_maybe)
        else -> R.drawable.ic_search_off to stringResource(R.string.huddle_not_found)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
        val line = listOfNotNull(song.title, song.artist).joinToString(" · ")
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = listOfNotNull(line, words).joinToString(", ") },
        ) {
            icon?.let { Icon(painterResource(it), contentDescription = null, tint = if (here) LocalBrandColors.current.suggestion else colors.onSurfaceVariant, modifier = Modifier.size(20.dp)) }
            Text(line, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        when {
            unsure -> LyrifyButton(stringResource(R.string.huddle_maybe_title), onClick = onAsk, kind = ButtonKind.GHOST, compact = true)
            match != null && !here -> LyrifyButton(stringResource(R.string.huddle_get_it), onClick = onGetIt, kind = ButtonKind.GHOST, compact = true)
        }
    }
}

/** A Huddle sheet asked for from the page, Library's friends row or the host ([HuddleSheetsHost]); kept across a turn of the phone. */
@Serializable
sealed interface HuddleSheet {
    /** A friend's song, from their face or their card: Play along, Play it, Send back ([PeekSheet]). */
    @Serializable @SerialName("peek")
    data class Peek(val friend: String) : HuddleSheet

    /** A friend's "⋯": Send a song, Stop sharing, Rename, Remove ([FriendMenuSheet]). */
    @Serializable @SerialName("menu")
    data class Menu(val friend: String) : HuddleSheet

    /** Invite a friend: the ticket ([InviteTicketSheet]). */
    @Serializable @SerialName("ticket")
    data object Ticket : HuddleSheet

    /** Listen privately's lengths ([PrivatelySheet]). */
    @Serializable @SerialName("private")
    data object Privately : HuddleSheet

    /** A song for [friend]: the song picker while [song] (its path) is not picked yet, then Send to with it. */
    @Serializable @SerialName("send")
    data class SendTo(val friend: String, val song: String? = null) : HuddleSheet

    @Serializable @SerialName("rename")
    data class Rename(val friend: String) : HuddleSheet

    @Serializable @SerialName("remove")
    data class Remove(val friend: String) : HuddleSheet
}

/** A [HuddleSheet] in the saved state: its JSON; one this version cannot read again is no sheet. */
internal val HuddleSheetSaver: Saver<HuddleSheet?, String> = Saver(
    save = { sheet -> sheet?.let { Json.encodeToString(HuddleSheet.serializer(), it) } },
    restore = { saved ->
        try {
            Json.decodeFromString(HuddleSheet.serializer(), saved)
        } catch (_: SerializationException) {
            null
        }
    },
)

/**
 * The [sheet] asked for (a friend's song or "⋯", the ticket, Listen privately, a song for a friend, Rename, Remove), and
 * YouTube's search over a friend's song you do not have; [onSheet] moves to another or closes (null). A friend gone
 * meanwhile closes theirs. [ledger] feeds the song picker; [onPlayer] shows the player.
 */
@Composable
internal fun HuddleSheetsHost(sheet: HuddleSheet?, ledger: LedgerState, onPlayer: () -> Unit, onSheet: (HuddleSheet?) -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val toasts = LocalToasts.current
    val scope = rememberCoroutineScope()
    val huddle = rememberHuddle()
    var youtube by rememberSaveable { mutableStateOf<String?>(null) }
    youtube?.let { query ->
        YouTubeSearchSheet(query, onDismiss = { youtube = null }, onStarted = { youtube = null })
        return
    }
    val state = rememberHuddleState() ?: return
    val close = { onSheet(null) }
    val friend = when (sheet) {
        is HuddleSheet.Peek -> sheet.friend
        is HuddleSheet.Menu -> sheet.friend
        is HuddleSheet.SendTo -> sheet.friend
        is HuddleSheet.Rename -> sheet.friend
        is HuddleSheet.Remove -> sheet.friend
        HuddleSheet.Ticket, HuddleSheet.Privately, null -> null
    }?.let { id ->
        state.friends.firstOrNull { it.id == id } ?: run {
            // Gone meanwhile: they removed you, or you removed them.
            LaunchedEffect(id) { close() }
            return
        }
    }
    when (sheet) {
        null -> Unit
        is HuddleSheet.Peek -> PeekSheet(checkNotNull(friend), state, onPlayer, onSheet, onYouTube = { youtube = it })
        is HuddleSheet.Menu -> FriendMenuSheet(checkNotNull(friend), onSheet)
        HuddleSheet.Ticket -> InviteTicketSheet(card = null, onDismiss = close)
        HuddleSheet.Privately -> PrivatelySheet(state, onDismiss = close)
        is HuddleSheet.SendTo -> {
            val library by remember(context) { LibraryGraph.repository(context).state }.collectAsStateWithLifecycle()
            val read = (library as? LibraryState.Ready)?.library
            val picked = sheet.song
            when {
                picked == null -> SongPickerScreen(ledger, onDismiss = close) { entry -> onSheet(sheet.copy(song = entry.path)) }
                // Picked while the library is read: Send to waits for it.
                read == null -> Unit
                // Gone from the library meanwhile: pick again.
                else -> read.song(picked)?.let { SendToFriendSheet(listOf(it), CardKind.SONG, null, to = sheet.friend, onDismiss = close) }
                    ?: SongPickerScreen(ledger, onDismiss = close) { entry -> onSheet(sheet.copy(song = entry.path)) }
            }
        }
        is HuddleSheet.Rename -> {
            val renamed = checkNotNull(friend)
            NameDialog(stringResource(R.string.huddle_rename_title, renamed.shownName), renamed.shownName, R.drawable.ic_edit, onDismiss = close, confirm = R.string.lib_rename) { name ->
                close()
                // The name they gave back when the field is left as it is.
                scope.launch { huddle.rename(renamed.id, name.takeUnless { it == renamed.name }) }
            }
        }
        is HuddleSheet.Remove -> {
            val removed = checkNotNull(friend)
            ConfirmDialog(
                title = stringResource(R.string.huddle_remove_title, removed.shownName),
                text = stringResource(R.string.huddle_remove_text),
                confirm = stringResource(R.string.huddle_remove),
                dismiss = stringResource(R.string.remove_cancel),
                icon = R.drawable.ic_person_off,
                onConfirm = {
                    close()
                    scope.launch {
                        // Asked for, it is done even if the page closes meanwhile.
                        if (withContext(NonCancellable) { huddle.remove(removed.id) }) {
                            toasts.show(Toast(resources.getString(R.string.huddle_removed, removed.shownName), R.drawable.ic_person_off))
                        } else {
                            toasts.show(Toast(resources.getString(R.string.huddle_no_answer), R.drawable.ic_error, Tone.ERROR))
                        }
                    }
                },
                onDismiss = close,
            )
        }
    }
}

/**
 * Plays [song] from where [play]'s friend is in it ([playAlongMs]), as a queue from them named [name], and shows the
 * player; their song's queue playing already (Play it, a Play along before) moves there too.
 */
internal fun playAlong(context: Context, song: Song, play: FriendPlay, name: String, onPlayer: () -> Unit) {
    val connection = PlayerGraph.connection(context)
    val source = QueueSource(SourceKind.HUDDLE, "${play.friend}:${play.at}", name)
    val at = playAlongMs(play, System.currentTimeMillis())
    // playFrom goes on where that queue is when it plays already: the friend's place is asked for, so it goes there.
    if (connection.ui.value?.queue?.from(source) == true) {
        connection.seekTo(at)
        connection.play()
    } else {
        connection.playFrom(listOf(song.ref), 0, at, source)
    }
    onPlayer()
}

/**
 * A friend's song (the mockup's 1.2), from their face or their card: their song big, its cover glowing behind (yours,
 * else their colour), where they are in it while they play ("Paused" while paused), and tiles: Play along (from where
 * they are, while they play), Play it (from the top) and Send back; for a song you do not have, Ask <name> and Search on
 * YouTube in place of the first two. Their "⋯" sits at the top. Nothing shared yet: only Send a song.
 */
@Composable
private fun PeekSheet(friend: Friend, state: HuddleState, onPlayer: () -> Unit, onSheet: (HuddleSheet?) -> Unit, onYouTube: (String) -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val toasts = LocalToasts.current
    val scope = rememberCoroutineScope()
    val sheet = LocalChromeColors.current.sheet
    val colors = MaterialTheme.colorScheme
    val now by rememberNow(PEEK_TICK_MS)
    val matcher = rememberSongMatcher()
    val current = friendsNow(state, now).firstOrNull { it.friend.id == friend.id }
    val play = current?.play
    val playing = current?.playing == true
    val found = remember(matcher, play) { play?.let { matcher?.match(it.song) } }
    // The answer to "Is this the song?" for a loose match of this play.
    var said by rememberSaveable(play?.at) { mutableStateOf<Boolean?>(null) }
    val offer = peekOffer(found, said)
    val song = (offer as? PeekOffer.Play)?.song
    val name = friend.shownName
    val color = friendColor(friend.id)
    val close = { onSheet(null) }
    LyrifySheet(onDismiss = close, spacing = 0.dp, padding = 0.dp) {
        Box(Modifier.fillMaxWidth().clipToBounds()) {
            FriendGlow(song, color, 30.dp, Modifier.alpha(0.75f))
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(sheet.copy(alpha = 0.42f), sheet.copy(alpha = 0.92f)))))
            Column(Modifier.padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FriendFace(name, color, 44.dp, ring = if (playing) FaceRing.LIVE else FaceRing.IDLE, gap = sheet)
                    Column(Modifier.weight(1f).semantics(mergeDescendants = true) { heading() }) {
                        Text(name, style = PeekName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (playing) Equaliser(lerp(color, colors.onSurface, 0.3f), Modifier.size(15.dp, 12.dp))
                            Text(
                                when {
                                    playing -> stringResource(R.string.huddle_playing_now)
                                    current?.paused == true -> stringResource(R.string.huddle_paused)
                                    play != null -> ago(play.at, now)
                                    else -> stringResource(R.string.huddle_nothing_yet)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    ChromeIconButton(R.drawable.ic_more_horiz, stringResource(R.string.huddle_friend_more, name), { onSheet(HuddleSheet.Menu(friend.id)) }, glass = false)
                }
                if (play != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 14.dp, end = 12.dp).semantics(mergeDescendants = true) {}) {
                        Box(Modifier.shadow(12.dp, RoundedCornerShape(22.dp), ambientColor = LocalChromeColors.current.shadow, spotColor = LocalChromeColors.current.shadow)) {
                            FriendArt(song, color, 104.dp, RoundedCornerShape(22.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(play.song.title, style = PeekTitle, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            Text(listOfNotNull(play.song.artist, play.song.album).joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (play.favourite) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 6.dp)) {
                                    Icon(painterResource(R.drawable.ic_favorite_fill), contentDescription = null, tint = lerp(color, colors.onSurface, 0.3f), modifier = Modifier.size(16.dp))
                                    Text(stringResource(R.string.huddle_their_favourite, name), style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold), color = lerp(color, colors.onSurface, 0.3f))
                                }
                            }
                        }
                    }
                }
                if (play != null && playing && play.song.durationMs > 0) {
                    val at = playAlongMs(play, now)
                    Box(Modifier.padding(top = 14.dp, end = 12.dp).fillMaxWidth().height(6.dp).clip(CircleShape).background(lerp(sheet, color, 0.2f))) {
                        Box(Modifier.fillMaxWidth(at.toFloat() / play.song.durationMs).height(6.dp).background(color, CircleShape))
                    }
                    Row(Modifier.padding(top = 6.dp, end = 12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(songLength(at), style = MaterialTheme.typography.bodySmall.tabular(), color = colors.onSurfaceVariant)
                        Text(songLength(play.song.durationMs), style = MaterialTheme.typography.bodySmall.tabular(), color = colors.onSurfaceVariant)
                    }
                }
            }
        }
        if (play != null && offer is PeekOffer.Ask) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 4.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(colors.surfaceContainerHigh).padding(12.dp).semantics(mergeDescendants = true) {},
                ) {
                    SongCover(SongArt(offer.song.path, offer.song.ref.mediaStoreId), size = 48.dp)
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.huddle_maybe_title), style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold))
                        Text(localSong(offer.song), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LyrifyButton(stringResource(R.string.huddle_maybe_no), onClick = { said = false }, modifier = Modifier.weight(1f), kind = ButtonKind.SECONDARY)
                    LyrifyButton(stringResource(R.string.huddle_yes), onClick = { said = true }, modifier = Modifier.weight(1f))
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp)) {
            if (play != null && song != null) {
                if (playing) {
                    PeekTile(R.drawable.ic_headphones, stringResource(R.string.huddle_play_along), color) {
                        close()
                        playAlong(context, song, play, name, onPlayer)
                    }
                }
                PeekTile(R.drawable.ic_replay, stringResource(R.string.huddle_play_it)) {
                    Lyrify.playback(context).play(listOf(song.ref), 0, QueueSource(SourceKind.HUDDLE, "${play.friend}:${play.at}", name))
                    close()
                    onPlayer()
                }
            }
            // Only once it is known not to be here: before the library is matched, nothing to ask for.
            if (play != null && offer == PeekOffer.Missing) {
                PeekTile(R.drawable.ic_send, stringResource(R.string.huddle_ask, name)) { scope.launch { toasts.say(resources, shareMessage(context, resources.getString(R.string.huddle_ask_message, songBy(resources, play.song)))) } }
                PeekTile(R.drawable.ic_search, stringResource(R.string.huddle_youtube)) { onYouTube(youTubeQuery(play.song)) }
            }
            PeekTile(R.drawable.ic_send, stringResource(if (play == null) R.string.huddle_send_song else R.string.huddle_send_back)) { onSheet(HuddleSheet.SendTo(friend.id)) }
        }
        if (play != null && song != null && playing) {
            Text(
                stringResource(R.string.huddle_play_along_foot, songLength(playAlongMs(play, now)), name),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 8.dp),
            )
        }
    }
}

/** One of a friend's song's tiles, a third of the row: an icon over its words, 80 dp tall; [hot] in the friend's colour (Play along). */
@Composable
private fun RowScope.PeekTile(@DrawableRes icon: Int, text: String, hot: Color? = null, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val ground = hot?.let { lerp(it, BrandBlack, 0.16f) } ?: colors.surfaceContainerHigh
    val ink = if (hot != null) BrandLilac else colors.onSurface
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
        modifier = Modifier
            .weight(1f)
            .heightIn(min = 80.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(ground)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 6.dp),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = ink, modifier = Modifier.size(26.dp))
        Text(text, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold), color = ink, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * A friend's "⋯" (the mockup's 5.1): their face and name, since when you are friends and whether they see what you play,
 * then Send a song, Stop sharing with them (or Share with them again; songs go both ways either way), Rename (on this
 * phone only) and Remove (asked first). A friend under a stand-in name says so.
 */
@Composable
private fun FriendMenuSheet(friend: Friend, onSheet: (HuddleSheet?) -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val toasts = LocalToasts.current
    val scope = rememberCoroutineScope()
    val huddle = rememberHuddle()
    val name = friend.shownName
    val sharing = friend.sharePlays
    LyrifySheet(onDismiss = { onSheet(null) }, spacing = 4.dp, padding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(start = 8.dp, bottom = 8.dp).semantics(mergeDescendants = true) { heading() }) {
            FriendFace(name, friendColor(friend.id), 48.dp, gap = LocalChromeColors.current.sheet)
            Column(Modifier.weight(1f)) {
                Text(name, style = PeekName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    stringResource(
                        if (sharing) R.string.huddle_friends_since_sees else R.string.huddle_friends_since,
                        DateUtils.formatDateTime(context, friend.addedAt, DateUtils.FORMAT_SHOW_DATE),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                friend.taken?.let { Text(stringResource(R.string.huddle_taken_note, it, friend.name), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        ActionRow(R.drawable.ic_send, stringResource(R.string.huddle_send_song_to, name)) { onSheet(HuddleSheet.SendTo(friend.id)) }
        ActionRow(
            if (sharing) R.drawable.ic_visibility_off else R.drawable.ic_visibility,
            stringResource(if (sharing) R.string.huddle_stop_sharing else R.string.huddle_share_again, name),
            supporting = stringResource(if (sharing) R.string.huddle_stop_sharing_text else R.string.huddle_share_plays_off, name),
        ) {
            onSheet(null)
            scope.launch {
                if (huddle.sharePlays(friend.id, !sharing)) {
                    toasts.show(Toast(resources.getString(if (sharing) R.string.huddle_share_plays_off else R.string.huddle_share_plays_on, name), if (sharing) R.drawable.ic_visibility_off else R.drawable.ic_visibility))
                } else {
                    toasts.say(resources, R.string.lib_not_saved)
                }
            }
        }
        ActionRow(R.drawable.ic_edit, stringResource(R.string.lib_rename), supporting = stringResource(R.string.huddle_rename_text)) { onSheet(HuddleSheet.Rename(friend.id)) }
        ActionRow(R.drawable.ic_person_off, stringResource(R.string.huddle_remove), supporting = stringResource(R.string.huddle_remove_row_text, name), danger = true) { onSheet(HuddleSheet.Remove(friend.id)) }
    }
}

/**
 * Listen privately (the mockup's 5.2): friends stop seeing what you play, songs they send still arrive; for an hour or
 * until you turn it off, and, while it is on, Share again now.
 */
@Composable
private fun PrivatelySheet(state: HuddleState, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val huddle = rememberHuddle()
    val hour = System.currentTimeMillis() + Huddle.HOUR_MS
    val pick = { until: Long? ->
        onDismiss()
        scope.launch { huddle.listenPrivately(until) }
    }
    LyrifySheet(onDismiss = onDismiss, spacing = 4.dp, padding = 12.dp) {
        Text(stringResource(R.string.huddle_private), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 8.dp).semantics { heading() })
        Text(stringResource(R.string.huddle_private_text), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp))
        ActionRow(R.drawable.ic_headphones, stringResource(R.string.huddle_private_hour), supporting = stringResource(R.string.huddle_private_hour_until, DateFormat.getTimeFormat(context).format(Date(hour)))) { pick(hour) }
        ActionRow(R.drawable.ic_lock, stringResource(R.string.huddle_private_always), supporting = stringResource(R.string.huddle_private_always_text)) { pick(HuddleState.FOREVER) }
        if (state.privateUntil?.let { System.currentTimeMillis() < it } == true) {
            ActionRow(R.drawable.ic_visibility, stringResource(R.string.huddle_private_stop), supporting = stringResource(R.string.huddle_private_off)) { pick(null) }
        }
    }
}

/** How often a friend's song's place moves on while it is open: each second, as a player's does. */
private const val PEEK_TICK_MS: Long = 1_000

/** The received card's tilted cover. */
private val RECEIVED_COVER = 168.dp

private val ReceivedTitle = display(24, 30, -0.01, weight = 700)
private val PeekName = display(18, 24, weight = 650)
private val PeekTitle = display(22, 28, -0.01, weight = 650)
