package app.lyrify.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.lyrify.Lyrify
import app.lyrify.R
import app.lyrify.backup.SongMatcher
import app.lyrify.huddle.Accepted
import app.lyrify.huddle.CardKind
import app.lyrify.huddle.CardSong
import app.lyrify.huddle.Invited
import app.lyrify.huddle.Invites
import app.lyrify.huddle.Match
import app.lyrify.huddle.Messages
import app.lyrify.huddle.match
import app.lyrify.library.LibraryGraph
import app.lyrify.library.LibraryState
import app.lyrify.library.Song
import app.lyrify.player.QueueSource
import app.lyrify.player.SourceKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Huddle's sheets: Send to a friend, a card a friend sent, an invite opened, and the restore's Huddle friends.

/**
 * Send to a friend (plan Q3.5), from a song's sheet, the player, an album's and a playlist's "⋯": [songs] as one card of
 * [kind] with the album's or playlist's [name], and a note. Your name first when it is not set yet (typed once, Q3.4).
 * A friend's row sends at once, with "Sent to <name>" and Undo for as long as the message shows (the card waits that
 * long); the one you sent to last comes first. Invite a friend puts the card in a new invite's link and opens your
 * phone's share sheet. The toast runs on the activity's scope, so it outlives the sheet.
 */
@Composable
internal fun SendToFriendSheet(songs: List<Song>, kind: CardKind, name: String?, onDismiss: () -> Unit) {
    val context = LocalContext.current
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
    // One send or invite per sheet: a second tap while the first goes is not a second card.
    var busy by remember { mutableStateOf(false) }
    val needsName = state.name == null
    // Your name set first when it was not; false while the field holds none.
    suspend fun named(): Boolean = !needsName || huddle.setName(typed)
    fun card() = Messages.card(kind, songs, name, note)
    fun ready(): Boolean = (!needsName || Invites.cleanName(typed) != null).also { nameMissing = !it }
    val first = songs.first()
    LyrifySheet(onDismiss = onDismiss, spacing = 12.dp) {
        Text(stringResource(R.string.huddle_send), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
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
            sendOrder(state.friends).forEach { friend ->
                NavRow(
                    title = friend.shownName,
                    sub = null,
                    description = stringResource(R.string.huddle_send_to, friend.shownName),
                    lead = { Tile(R.drawable.ic_person) },
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
            NavRow(
                title = stringResource(R.string.huddle_invite),
                sub = stringResource(R.string.huddle_invite_gets_it),
                titleColor = brand.highlight,
                lead = { Tile(R.drawable.ic_add, MaterialTheme.colorScheme.surfaceContainerHigh, brand.highlight) },
                trailing = null,
            ) {
                if (!busy && ready()) {
                    busy = true
                    val card = card()
                    lifecycle.launch {
                        if (!named()) {
                            busy = false
                            return@launch
                        }
                        when (val invited = huddle.invite(card)) {
                            is Invited.Link -> {
                                onDismiss()
                                val what = when (kind) {
                                    CardKind.SONG -> resources.getString(R.string.huddle_invite_message_song, songBy(resources, card.songs.first()), invited.text)
                                    CardKind.ALBUM -> resources.getString(R.string.huddle_invite_message_album, name.orEmpty(), invited.text)
                                    CardKind.PLAYLIST -> resources.getString(R.string.huddle_invite_message_playlist, name.orEmpty(), invited.text)
                                }
                                toasts.say(resources, shareMessage(context, what))
                            }
                            Invited.NoName, Invited.NoAnswer -> {
                                busy = false
                                toasts.show(Toast(resources.getString(R.string.huddle_no_answer), R.drawable.ic_error, Tone.ERROR))
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * A card a friend sent ([id] in Huddle's inbox), opened from the start screen's row, the Huddle page or an invite: the
 * sheet ([ReceivedSheet]), and over it, for a song you do not have, YouTube's search ([YouTubeSearchSheet], KJB
 * Downloads); back from the search the card shows again, with the answers to "Is this the song?" given before.
 * [playNow]: a song you have plays at once (Play and add).
 */
@Composable
internal fun ReceivedHost(id: String?, playNow: Boolean = false, onClose: () -> Unit) {
    var youtube by rememberSaveable { mutableStateOf<String?>(null) }
    // Answers to "Is this the song?", by the song's place in the card; here, so they outlast the search.
    var said by rememberSaveable(id) { mutableStateOf(emptyMap<Int, Boolean>()) }
    val query = youtube
    if (query != null) {
        YouTubeSearchSheet(query, onDismiss = { youtube = null }, onStarted = { youtube = null })
        return
    }
    if (id != null) ReceivedSheet(id, playNow, said, onSaid = { said = said + it }, onYouTube = { youtube = it }, onDismiss = onClose)
}

/**
 * The card, opened (the start screen keeps it, out of coral): from whom, the note, and its songs found in this library
 * ([match]): a song found plays; one found only loosely asks "Is this the song?" first; one not here says so, with Ask
 * <name> (your phone's share sheet, any chat) and Search on YouTube. An album or a playlist lists each song with how it was
 * found, plays the ones you have, and asks for the rest at once. What plays is the card's own queue, "From <name>" (the
 * name you see), and the player shows.
 */
@Composable
private fun ReceivedSheet(id: String, playNow: Boolean, said: Map<Int, Boolean>, onSaid: (Pair<Int, Boolean>) -> Unit, onYouTube: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val toasts = LocalToasts.current
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val huddle = rememberHuddle()
    val state = rememberHuddleState() ?: return
    val received = state.inbox.firstOrNull { it.id == id }
    val friend = received?.let { card -> state.friends.firstOrNull { it.id == card.friend } }
    val library by remember(context) { LibraryGraph.repository(context).state }.collectAsStateWithLifecycle()
    val matches by produceState<List<Match>?>(null, received?.card, library) {
        val songs = (library as? LibraryState.Ready)?.library?.songs ?: return@produceState
        val card = received?.card ?: return@produceState
        value = withContext(Dispatchers.Default) { SongMatcher(songs).let { matcher -> card.songs.map(matcher::match) } }
    }
    var asking by rememberSaveable { mutableStateOf<Int?>(null) }
    LaunchedEffect(id) { huddle.opened(id) }
    if (received == null || friend == null) {
        // Gone meanwhile: the friend removed you, and their cards went with them.
        LaunchedEffect(id) { onDismiss() }
        return
    }
    val card = received.card
    val from = friend.shownName
    val found = matches
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
        nav.show(FullScreen.Player)
    }
    // Ask <name>: the songs not here, in one message for any chat.
    val askAll: () -> Unit = {
        val songs = missing.map { card.songs[it] }
        val text = songs.singleOrNull()?.let { resources.getString(R.string.huddle_ask_message, songBy(resources, it)) }
            ?: resources.getString(R.string.huddle_ask_message_many, songs.joinToString("\n") { songBy(resources, it) })
        scope.launch { toasts.say(resources, shareMessage(context, text)) }
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
            spacing = 12.dp,
            footer = {
                when {
                    match is Match.Found -> LyrifyButton(stringResource(R.string.huddle_play), onClick = { play(listOf(match.song)) }, modifier = Modifier.fillMaxWidth(), icon = R.drawable.ic_play_arrow_fill)
                    match is Match.Maybe && unsure -> {
                        LyrifyButton(stringResource(R.string.huddle_maybe_yes), onClick = { play(listOf(match.song)) }, modifier = Modifier.fillMaxWidth(), icon = R.drawable.ic_play_arrow_fill)
                        LyrifyButton(stringResource(R.string.huddle_maybe_no), onClick = { onSaid(0 to false) }, modifier = Modifier.fillMaxWidth(), kind = ButtonKind.SECONDARY)
                    }
                    match != null -> {
                        LyrifyButton(stringResource(R.string.huddle_ask, from), onClick = askAll, modifier = Modifier.fillMaxWidth())
                        LyrifyButton(stringResource(R.string.huddle_youtube), onClick = { onYouTube(youTubeQuery(song)) }, modifier = Modifier.fillMaxWidth(), kind = ButtonKind.SECONDARY, icon = R.drawable.ic_search)
                    }
                    else -> LyrifyButton(stringResource(R.string.huddle_play), onClick = {}, modifier = Modifier.fillMaxWidth(), enabled = false)
                }
            },
        ) {
            Text(stringResource(R.string.huddle_from, from), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Fact(R.drawable.ic_music_note, cardLine(card))
            card.note?.let { Fact(R.drawable.ic_notes, it) }
            when {
                match is Match.Maybe && unsure -> {
                    SectionTitle(stringResource(R.string.huddle_maybe_title))
                    Text(localSong(match.song), style = MaterialTheme.typography.bodyLarge)
                }
                match != null && match !is Match.Found -> Text(stringResource(R.string.huddle_missing, from), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        },
    ) {
        Text(stringResource(R.string.huddle_from, from), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        Fact(if (card.kind == CardKind.ALBUM) R.drawable.ic_album else R.drawable.ic_queue_music, cardLine(card))
        card.note?.let { Fact(R.drawable.ic_notes, it) }
        if (missing.isNotEmpty()) Text(pluralStringResource(R.plurals.huddle_missing_some, missing.size, missing.size), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column {
            card.songs.forEachIndexed { at, song ->
                val match = found?.getOrNull(at)
                CardSongRow(
                    song,
                    match = match,
                    said = said[at],
                    onAsk = { asking = at },
                    onGetIt = { onYouTube(youTubeQuery(song)) },
                )
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
            icon?.let { Icon(painterResource(it), contentDescription = null, tint = if (here) LocalBrandColors.current.highlight else colors.onSurfaceVariant, modifier = Modifier.size(20.dp)) }
            Text(line, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        when {
            unsure -> LyrifyButton(stringResource(R.string.huddle_maybe_title), onClick = onAsk, kind = ButtonKind.GHOST, compact = true)
            match != null && !here -> LyrifyButton(stringResource(R.string.huddle_get_it), onClick = onGetIt, kind = ButtonKind.GHOST, compact = true)
        }
    }
}

/**
 * An invite opened ([link]: from another app, or pasted): "<name> invites you to Huddle", the song that came with it, and
 * Add <name> (Play and add <name> with a song). Your name first when it is not set yet. Someone else's invite used
 * already, one expired, your own, or no answer from Huddle says so here. [onPlay]: the card that came with it, to open.
 */
@Composable
internal fun AcceptInviteSheet(link: String, onDismiss: () -> Unit, onPlay: (String) -> Unit) {
    val resources = LocalResources.current
    val toasts = LocalToasts.current
    val lifecycle = LocalLifecycleOwner.current.lifecycleScope
    val huddle = rememberHuddle()
    val state = rememberHuddleState() ?: return
    val invite = remember(link) { huddle.parse(link) }
    if (invite == null) {
        HelpSheet(stringResource(R.string.huddle_title), stringResource(R.string.huddle_accept_bad), onDismiss = onDismiss)
        return
    }
    var typed by rememberSaveable { mutableStateOf("") }
    var answer by remember { mutableStateOf<Accepted?>(null) }
    var busy by remember { mutableStateOf(false) }
    val needsName = state.name == null
    val card = invite.card
    val failed = answer?.let {
        when (it) {
            Accepted.Taken -> stringResource(R.string.huddle_accept_taken, invite.name)
            Accepted.Expired -> stringResource(R.string.huddle_accept_expired, invite.name)
            Accepted.Own -> stringResource(R.string.huddle_accept_own)
            else -> stringResource(R.string.huddle_no_answer)
        }
    }
    LyrifySheet(
        onDismiss = onDismiss,
        spacing = 12.dp,
        footer = {
            if (failed != null && answer != Accepted.NoAnswer) {
                LyrifyButton(stringResource(R.string.huddle_done), onClick = onDismiss, modifier = Modifier.fillMaxWidth(), kind = ButtonKind.SECONDARY)
            } else {
                LyrifyButton(
                    stringResource(if (card != null) R.string.huddle_accept_play else R.string.huddle_accept, invite.name),
                    onClick = {
                        busy = true
                        lifecycle.launch {
                            if (needsName && !huddle.setName(typed)) {
                                busy = false
                                return@launch
                            }
                            when (val accepted = huddle.accept(invite)) {
                                is Accepted.Joined -> {
                                    onDismiss()
                                    // The song first; the message shows meanwhile.
                                    accepted.received?.let(onPlay)
                                    lifecycle.launch { toasts.show(Toast(resources.getString(R.string.huddle_joined, accepted.friend.shownName), R.drawable.ic_group)) }
                                }
                                is Accepted.Already -> {
                                    onDismiss()
                                    lifecycle.launch { toasts.show(Toast(resources.getString(R.string.huddle_already, accepted.friend.shownName), R.drawable.ic_group)) }
                                }
                                else -> answer = accepted
                            }
                            busy = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy && (!needsName || Invites.cleanName(typed) != null),
                )
                LyrifyButton(stringResource(R.string.huddle_not_now), onClick = onDismiss, modifier = Modifier.fillMaxWidth(), kind = ButtonKind.SECONDARY, enabled = !busy)
            }
        },
    ) {
        Text(stringResource(R.string.huddle_accept_title, invite.name), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.huddle_accept_text), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        card?.let {
            Fact(R.drawable.ic_music_note, stringResource(R.string.huddle_accept_song, invite.name, cardLine(it)))
            it.note?.let { note -> Fact(R.drawable.ic_notes, note) }
        }
        if (needsName && failed == null) {
            LyrifyField(typed, { typed = it.take(Invites.NAME_MAX) }, Modifier.fillMaxWidth(), label = stringResource(R.string.huddle_name_title))
            Text(stringResource(R.string.huddle_name_text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        failed?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error) }
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
