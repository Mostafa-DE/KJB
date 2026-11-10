package app.lyrify.ui

import android.content.Context
import android.content.res.Resources
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.lyrify.R
import app.lyrify.library.Library
import app.lyrify.library.LibraryGraph
import app.lyrify.library.LibraryState
import app.lyrify.library.Song
import app.lyrify.library.albumKey
import app.lyrify.library.sortSongs
import app.lyrify.logFailures
import app.lyrify.lyrics.readCapped
import app.lyrify.settings.SortSongs
import app.lyrify.tags.BatchResult
import app.lyrify.tags.CoverEdit
import app.lyrify.tags.EditKind
import app.lyrify.tags.FRONT_COVER
import app.lyrify.tags.FieldProblem
import app.lyrify.tags.SongPlan
import app.lyrify.tags.TagEdits
import app.lyrify.tags.TagField
import app.lyrify.tags.TagForm
import app.lyrify.tags.TagPicture
import app.lyrify.tags.TagsGraph
import app.lyrify.tags.lookup.AlbumLookup
import app.lyrify.tags.lookup.AndroidImageCodec
import app.lyrify.tags.lookup.CoverPipeline
import app.lyrify.tags.lookup.ImageSize
import app.lyrify.tags.lookup.Lookup
import app.lyrify.tags.lookup.MbRelease
import app.lyrify.tags.lookup.MetaAnswer
import app.lyrify.tags.lookup.Proposal
import app.lyrify.tags.pickedCover
import app.lyrify.tags.report
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException

// Edit tags and Edit album (milestone T3), and how every tag edit is written: KJB's own confirmation, then the one tag
// writer (TagsGraph), one song at a time, as one History entry with Undo. History's list is Covers & tags' (T2): it
// shows these entries beside its own.

/** Edit tags for one song or many, over everything: the fields they share, and the cover. */
@Composable
fun EditTagsView(view: FullScreen.EditTags) {
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val resources = LocalResources.current
    val toasts = LocalToasts.current
    val ui = rememberLibraryUi() ?: return
    val library = ui.library ?: return
    val songs = remember(view, library) { view.songs.mapNotNull { library.resolver.find(it) } }
    if (songs.isEmpty()) {
        LaunchedEffect(Unit) { nav.close(view) }
        return
    }
    val one = songs.size == 1
    val needed = if (one) setOf(TagField.TITLE) else emptySet()
    var form by rememberSaveable(stateSaver = FORM_SAVER) { mutableStateOf(TagForm.of(songs)) }
    var cover by remember { mutableStateOf<CoverEdit>(CoverEdit.Keep) }
    var problems by remember { mutableStateOf(emptyMap<TagField, FieldProblem>()) }
    var asking by remember { mutableStateOf<List<SongPlan>?>(null) }
    var saving by remember { mutableStateOf(false) }

    EditorFrame(
        title = stringResource(R.string.lib_edit_tags),
        edited = form.edited || cover != CoverEdit.Keep,
        saving = saving,
        onClose = { nav.close(view) },
        onSave = {
            problems = form.problems(needed)
            if (problems.isEmpty()) {
                val plans = TagEdits.songs(songs, form, cover)
                if (plans.isEmpty()) {
                    nav.close(view)
                    toasts.later(Toast(resources.getString(R.string.edit_nothing_changed), R.drawable.ic_sell))
                } else {
                    asking = plans
                }
            }
        },
    ) {
        CoverEditor(songs, cover, { cover = it }, album = form.value(TagField.ALBUM), artist = form.value(TagField.ARTIST).ifBlank { form.value(TagField.ALBUM_ARTIST) })
        if (one) {
            songs.single().let { Fact(R.drawable.ic_folder_open, it.folder + it.fileName, middleEllipsis = true) }
        } else {
            Sentence(pluralStringResource(R.plurals.edit_selected, songs.size, songs.size))
        }
        val field: @Composable (TagField, Modifier) -> Unit = { which, modifier ->
            TagInput(which, form, problems, modifier) {
                form = form.with(which, it)
                problems = problems - which
            }
        }
        if (one) field(TagField.TITLE, Modifier.fillMaxWidth())
        field(TagField.ARTIST, Modifier.fillMaxWidth())
        field(TagField.ALBUM, Modifier.fillMaxWidth())
        field(TagField.ALBUM_ARTIST, Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            field(TagField.YEAR, Modifier.weight(1f))
            if (one) field(TagField.TRACK, Modifier.weight(1f))
            field(TagField.DISC, Modifier.weight(1f))
        }
        field(TagField.GENRE, Modifier.fillMaxWidth())
    }

    asking?.let { plans ->
        val label = if (one) {
            resources.getString(R.string.edit_label_song, plans.single().after.title)
        } else {
            resources.getQuantityString(R.plurals.edit_label_songs, plans.size, plans.size)
        }
        val saved = if (plans.size == 1) resources.getString(R.string.edit_saved) else resources.getQuantityString(R.plurals.edit_saved_songs, plans.size, plans.size)
        ConfirmWrite(plans.size, pluralStringResource(R.plurals.edit_confirm_tags, plans.size), onDismiss = { asking = null }) {
            asking = null
            saving = true
            startWrite(context, toasts, resources, plans, EditKind.SONG, label, saved) { nav.close(view) }
        }
    }
}

/**
 * Edit album, over everything: album, album artist, year, genre and the cover for every song of the album at once, and
 * "Number in this order" in the album page's order. What changes shows before Save; Saving asks once.
 */
@Composable
fun EditAlbumView(view: FullScreen.EditAlbum) {
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val resources = LocalResources.current
    val toasts = LocalToasts.current
    val ui = rememberLibraryUi() ?: return
    val library = ui.library ?: return
    val album = library.album(view.key)
    if (album == null) {
        LaunchedEffect(Unit) { nav.close(view) }
        return
    }
    val songs = remember(album, view.byTitle, ui.settings.library) { if (view.byTitle) sortSongs(album.songs, SortSongs.TITLE, ui.settings.library) else album.songs }
    var form by rememberSaveable(stateSaver = FORM_SAVER) { mutableStateOf(TagForm.of(songs, TagEdits.ALBUM_FIELDS)) }
    var numberInOrder by rememberSaveable { mutableStateOf(false) }
    var cover by remember { mutableStateOf<CoverEdit>(CoverEdit.Keep) }
    var problems by remember { mutableStateOf(emptyMap<TagField, FieldProblem>()) }
    var asking by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val plans = remember(songs, form, cover, numberInOrder) { TagEdits.album(songs, form, cover, numberInOrder) }

    EditorFrame(
        title = stringResource(R.string.lib_edit_album),
        edited = form.edited || cover != CoverEdit.Keep || numberInOrder,
        saving = saving,
        onClose = { nav.close(view) },
        onSave = {
            problems = form.problems(setOf(TagField.ALBUM))
            if (problems.isEmpty()) {
                if (plans.isEmpty()) {
                    nav.close(view)
                    toasts.later(Toast(resources.getString(R.string.edit_nothing_changed), R.drawable.ic_sell))
                } else {
                    asking = true
                }
            }
        },
    ) {
        CoverEditor(songs, cover, { cover = it }, album = form.value(TagField.ALBUM), artist = form.value(TagField.ALBUM_ARTIST).ifBlank { album.artist.orEmpty() })
        Sentence(stringResource(R.string.edit_album_sentence))
        val field: @Composable (TagField, Modifier) -> Unit = { which, modifier ->
            TagInput(which, form, problems, modifier) {
                form = form.with(which, it)
                problems = problems - which
            }
        }
        field(TagField.ALBUM, Modifier.fillMaxWidth())
        field(TagField.ALBUM_ARTIST, Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            field(TagField.YEAR, Modifier.weight(1f))
            field(TagField.GENRE, Modifier.weight(1f))
        }
        Text(stringResource(R.string.edit_track_numbers), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LyrifySegmented(listOf(stringResource(R.string.edit_track_keep), stringResource(R.string.edit_track_order)), if (numberInOrder) 1 else 0, { numberInOrder = it == 1 })
        Text(
            if (plans.isEmpty()) stringResource(R.string.edit_no_changes) else pluralStringResource(R.plurals.edit_changes, plans.size, plans.size),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 8.dp).semantics { heading() },
        )
        val rows = albumPreview(plans, form, cover, numberInOrder, resources)
        if (rows.isNotEmpty()) ChangeList(rows)
    }

    if (asking) {
        val name = plans.first().after.album ?: album.title
        ConfirmWrite(plans.size, stringResource(R.string.edit_confirm_album, album.title), onDismiss = { asking = false }) {
            asking = false
            saving = true
            val moved = plans.first().after.let(::albumKey)?.takeIf { it != view.key }
            startWrite(context, toasts, resources, plans, EditKind.ALBUM, resources.getString(R.string.edit_label_album, name), resources.getString(R.string.edit_album_saved)) {
                nav.close(view)
                // The album's page follows it to its new name.
                if (moved != null && awaitLibrary(context) { it.album(moved) != null }) nav.replaceTop(Page.Album(view.key), Page.Album(moved))
            }
        }
    }
}

/** Tools > Covers & tags > History: every edit, each with Undo (T3). */
@Composable
fun TagHistoryPage() {
    val nav = LocalNavigator.current
    PageStub("T3") { nav.back() }
}

// The editor's parts

/**
 * An editor over everything: Close, the title and Save in the top bar, the notification access line when it is off
 * (no tag is written without it), the scrolling [content], the footer sentence and the message slot. Closing with
 * something typed ([edited]) asks first; while [saving], Save waits.
 */
@Composable
private fun EditorFrame(title: String, edited: Boolean, saving: Boolean, onClose: () -> Unit, onSave: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val context = LocalContext.current
    var access by remember { mutableStateOf(hasNotificationAccess(context)) }
    var leaving by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        // Turned on in Settings meanwhile.
        access = hasNotificationAccess(context)
        onPauseOrDispose {}
    }
    val close = { if (edited && !saving) leaving = true else onClose() }
    BackHandler(onBack = close)
    Scaffold(
        topBar = {
            LyrifyTopBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigation = { ChromeIconButton(R.drawable.ic_close, stringResource(R.string.edit_close), close) },
                actions = {
                    LyrifyButton(
                        stringResource(if (saving) R.string.edit_saving else R.string.edit_save),
                        onClick = onSave,
                        icon = R.drawable.ic_save,
                        compact = true,
                        enabled = access && !saving,
                    )
                },
            )
        },
        bottomBar = { ToastSlot(Modifier.navigationBarsPadding().padding(horizontal = ScreenPadding), padding = PaddingValues(bottom = 12.dp)) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = ScreenPadding, vertical = 8.dp),
        ) {
            if (!access) {
                val brand = LocalBrandColors.current
                Banner(
                    R.drawable.ic_notifications,
                    stringResource(R.string.edit_no_access),
                    stringResource(R.string.edit_turn_on),
                    brand.highlightContainer,
                    brand.onHighlightContainer,
                    onAction = { context.startFirst(notificationAccessIntents(context)) },
                )
            }
            content()
            Sentence(stringResource(R.string.edit_footer))
        }
    }
    if (leaving) {
        ConfirmDialog(
            title = stringResource(R.string.edit_leave_title),
            text = stringResource(R.string.edit_leave_text),
            confirm = stringResource(R.string.edit_leave),
            dismiss = stringResource(R.string.edit_cancel),
            onConfirm = {
                leaving = false
                onClose()
            },
            onDismiss = { leaving = false },
            icon = R.drawable.ic_edit,
        )
    }
}

/** One field: its label above, "Keep as is" while the songs do not share it, its problem under it. */
@Composable
private fun TagInput(field: TagField, form: TagForm, problems: Map<TagField, FieldProblem>, modifier: Modifier, onChange: (String) -> Unit) {
    val problem = problems[field]
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LyrifyField(
            value = form.value(field),
            onValueChange = { onChange(it.take(if (field.isNumber || field == TagField.YEAR) NUMBER_LENGTH else TEXT_LENGTH)) },
            label = stringResource(field.label),
            placeholder = if (field in form.mixed) stringResource(R.string.edit_keep_as_is) else null,
            isError = problem != null,
            keyboardOptions = if (field.isNumber || field == TagField.YEAR) {
                KeyboardOptions(keyboardType = KeyboardType.Number)
            } else {
                KeyboardOptions(capitalization = KeyboardCapitalization.Words)
            },
            modifier = Modifier.fillMaxWidth(),
        )
        if (problem != null) {
            Text(stringResource(problem.words(field)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 16.dp))
        }
    }
}

private val TagField.label: Int
    get() = when (this) {
        TagField.TITLE -> R.string.edit_field_title
        TagField.ARTIST -> R.string.edit_field_artist
        TagField.ALBUM -> R.string.edit_field_album
        TagField.ALBUM_ARTIST -> R.string.edit_field_album_artist
        TagField.YEAR -> R.string.edit_field_year
        TagField.TRACK -> R.string.edit_field_track
        TagField.DISC -> R.string.edit_field_disc
        TagField.GENRE -> R.string.edit_field_genre
    }

private fun FieldProblem.words(field: TagField): Int = when (this) {
    FieldProblem.NEEDS_TEXT -> if (field == TagField.ALBUM) R.string.edit_problem_album else R.string.edit_problem_title
    FieldProblem.FOUR_DIGITS -> R.string.edit_problem_year
    FieldProblem.NUMBER -> R.string.edit_problem_number
}

@Composable
internal fun Sentence(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * The cover: as it is (the first song's), the new one, or none; Change (the phone's photo picker), Find online (Covers
 * & tags' lookup, for [album] by [artist]) and Remove. Either new cover is made by Covers & tags' cover pipeline.
 */
@Composable
private fun CoverEditor(songs: List<Song>, cover: CoverEdit, onCover: (CoverEdit) -> Unit, album: String, artist: String) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val toasts = LocalToasts.current
    val scope = rememberCoroutineScope()
    var finding by rememberSaveable { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                val picture = withContext(Dispatchers.IO) { picked(context, uri) }?.let { withContext(Dispatchers.Default) { pickedCover(it, AndroidImageCodec) } }
                if (picture != null) onCover(CoverEdit.Replace(picture)) else toasts.say(resources, R.string.edit_photo_failed)
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        val shown: Any? = when (cover) {
            CoverEdit.Keep -> songs.first().let { SongArt(it.path, it.ref.mediaStoreId) }
            is CoverEdit.Replace -> cover.picture.data
            CoverEdit.Remove -> null
        }
        SongCover(shown, size = COVER, shape = RoundedCornerShape(24.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            LyrifyButton(
                stringResource(R.string.edit_cover_change),
                onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                kind = ButtonKind.GHOST,
                icon = R.drawable.ic_photo_library,
                compact = true,
            )
            LyrifyButton(stringResource(R.string.edit_cover_find), onClick = { finding = true }, kind = ButtonKind.GHOST, icon = R.drawable.ic_travel_explore, compact = true)
            LyrifyButton(
                stringResource(R.string.edit_cover_remove),
                onClick = { onCover(CoverEdit.Remove) },
                kind = ButtonKind.GHOST,
                icon = R.drawable.ic_hide_image,
                compact = true,
                enabled = cover != CoverEdit.Remove,
            )
        }
    }
    if (cover is CoverEdit.Replace && songs.any { TagEdits.coverFor(it, cover) == CoverEdit.Keep }) Fact(R.drawable.ic_info, stringResource(R.string.edit_opus_note))
    if (finding) {
        FindOnlineSheet(songs, album, artist, onDismiss = { finding = false }) {
            finding = false
            onCover(CoverEdit.Replace(it))
        }
    }
}

/** The picked photo's bytes; null when it can't be read or is larger than any photo should be. */
private fun picked(context: Context, uri: Uri): ByteArray? = try {
    context.contentResolver.openInputStream(uri)?.use { readCapped(it, PHOTO_MAX_BYTES) }
} catch (_: IOException) {
    null
} catch (_: SecurityException) {
    null
}

/** What Find online has found so far. */
private sealed interface Found {
    val covers: List<FoundCover>

    data class Looking(override val covers: List<FoundCover> = emptyList()) : Found

    data class Done(override val covers: List<FoundCover>) : Found

    data object Busy : Found {
        override val covers: List<FoundCover> = emptyList()
    }

    data object Failed : Found {
        override val covers: List<FoundCover> = emptyList()
    }
}

private class FoundCover(val release: MbRelease, val picture: TagPicture, val size: ImageSize)

/**
 * Find online: Covers & tags' lookup for [album] by [artist] (the songs' own when nothing is typed, a single by its
 * title), and the front covers of the releases it finds, each ready to use. Leaving stops the lookup.
 */
@Composable
private fun FindOnlineSheet(songs: List<Song>, album: String, artist: String, onDismiss: () -> Unit, onUse: (TagPicture) -> Unit) {
    val context = LocalContext.current
    val found by produceState<Found>(Found.Looking(), songs, album, artist) {
        value = findCovers(TagsGraph.lookup(context), songs, album.trim(), artist.trim()) { value = it }
    }
    LyrifySheet(onDismiss = onDismiss, spacing = 12.dp) {
        Text(stringResource(R.string.edit_cover_find), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        val what = album.ifBlank { songs.first().title }
        Sentence(if (artist.isBlank()) stringResource(R.string.edit_find_sentence, what) else stringResource(R.string.edit_find_sentence_by, what, artist))
        found.covers.forEach { candidate ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SongCover(candidate.picture.data, size = 64.dp, shape = RoundedCornerShape(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(candidate.release.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val size = stringResource(R.string.edit_cover_size, candidate.size.width, candidate.size.height)
                    val about = listOfNotNull(candidate.release.year?.toString(), candidate.release.formats.firstOrNull()).joinToString(", ")
                    Text(
                        if (about.isEmpty()) size else stringResource(R.string.edit_two_parts, about, size),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val named = stringResource(R.string.edit_use_named, candidate.release.title)
                LyrifyButton(stringResource(R.string.edit_use), onClick = { onUse(candidate.picture) }, compact = true, modifier = Modifier.semantics { contentDescription = named })
            }
        }
        val looking = stringResource(R.string.edit_find_looking)
        when (val now = found) {
            is Found.Looking -> SungBar(null, Modifier.fillMaxWidth().semantics { contentDescription = looking })
            is Found.Done -> if (now.covers.isEmpty()) Sentence(stringResource(R.string.edit_find_none))
            Found.Busy -> Sentence(stringResource(R.string.edit_find_busy))
            Found.Failed -> Sentence(stringResource(R.string.edit_find_failed))
        }
    }
}

/** The lookup, then the covers of up to [MAX_COVERS] of its releases, each shown as soon as it is ready. */
private suspend fun findCovers(lookup: AlbumLookup, songs: List<Song>, album: String, artist: String, shown: (Found) -> Unit): Found {
    val phone = TagEdits.phoneAlbum(songs)
    val result = if (album.isNotEmpty()) lookup.search(phone, album, artist.ifEmpty { null }) else lookup.check(phone)
    val matches = when (result) {
        Lookup.Busy -> return Found.Busy
        is Lookup.Failed -> return Found.Failed
        is Lookup.Done -> when (val proposal = result.proposal) {
            is Proposal.Ready -> listOf(proposal.match)
            is Proposal.PickOne -> proposal.candidates
        }
    }
    val covers = mutableListOf<FoundCover>()
    for (match in matches.distinctBy { it.release.id }.take(MAX_TRIED)) {
        if (covers.size >= MAX_COVERS) break
        when (val answer = lookup.cover(match.release)) {
            is MetaAnswer.Ok -> {
                val cover = withContext(Dispatchers.Default) { CoverPipeline(AndroidImageCodec).prepare(answer.value) } ?: continue
                val picture = TagPicture(cover.jpeg, "", FRONT_COVER, "image/jpeg")
                // Editions often share their cover: each picture once.
                if (covers.none { it.picture.sha256 == picture.sha256 }) {
                    covers += FoundCover(match.release, picture, cover.size)
                    shown(Found.Looking(covers.toList()))
                }
            }
            MetaAnswer.Busy -> return if (covers.isEmpty()) Found.Busy else Found.Done(covers)
            MetaAnswer.NotFound, is MetaAnswer.Failed -> Unit
        }
    }
    return Found.Done(covers)
}

/** Edit album's "old → new" lines: each shared field that changes, the cover, then the first renumbered tracks. */
private fun albumPreview(plans: List<SongPlan>, form: TagForm, cover: CoverEdit, numberInOrder: Boolean, resources: Resources): List<ChangeLine> {
    val none = resources.getString(R.string.edit_none)
    val lines = mutableListOf<ChangeLine>()
    for (field in TagEdits.ALBUM_FIELDS) {
        val first = plans.firstOrNull { field in it.fields } ?: continue
        lines += ChangeLine(resources.getString(field.label), field.of(first.song).ifEmpty { none }, first.fields.getValue(field).ifEmpty { none })
    }
    if (plans.any { it.cover != CoverEdit.Keep }) {
        lines += ChangeLine(resources.getString(R.string.edit_cover), resources.getString(R.string.edit_old_cover), resources.getString(if (cover is CoverEdit.Replace) R.string.edit_new_cover else R.string.edit_no_cover))
    }
    if (numberInOrder) {
        plans.filter { TagField.TRACK in it.fields }.take(PREVIEW_TRACKS).forEach { plan ->
            val old = resources.getString(R.string.edit_two_parts, plan.song.track?.toString() ?: none, plan.song.title)
            lines += ChangeLine(resources.getString(R.string.edit_field_track), old, plan.fields.getValue(TagField.TRACK))
        }
    }
    return lines.take(PREVIEW_LINES)
}

/** One "old → new" line: what it is ([what], a field or a song), and its values. */
internal class ChangeLine(val what: String, val old: String, val new: String)

/** The lines of what changes, on a card: each one's name, then old → new. */
@Composable
internal fun ChangeList(lines: List<ChangeLine>, more: String? = null) {
    LyrifyCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            lines.forEach { line ->
                Column {
                    Text(line.what, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        stringResource(R.string.edit_old_new, line.old, line.new),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (more != null) Text(more, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** KJB's one confirmation before a tag write: "Change 7 songs?" with what is written. */
@Composable
internal fun ConfirmWrite(count: Int, text: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    ConfirmDialog(
        title = pluralStringResource(R.plurals.edit_confirm_title, count, count),
        text = text,
        confirm = stringResource(R.string.edit_change),
        dismiss = stringResource(R.string.edit_cancel),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        icon = R.drawable.ic_sell,
        destructive = false,
    )
}

// Writing

/** Tag edits run here: a write runs to its end, and says how it went, even when the screen that started it closes. */
private val edits = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + logFailures)

/** Shows [toast] from [edits], so it stays up after the screen that asked for it closes. */
internal fun ToastState.later(toast: Toast) {
    edits.launch { show(toast) }
}

/**
 * Writes [plans] as one History entry of [kind] shown as [label]; once done, says how it went ([saved] when every song
 * took it), with Undo, while [after] moves the screen on.
 */
internal fun startWrite(
    context: Context,
    toasts: ToastState,
    resources: Resources,
    plans: List<SongPlan>,
    kind: EditKind,
    label: String,
    saved: String,
    after: suspend () -> Unit = {},
) {
    val app = context.applicationContext
    val jobs = plans.mapNotNull { it.job }
    edits.launch {
        val writer = withContext(Dispatchers.IO) { TagsGraph.writer(app) }
        val result = writer.write(jobs, kind, label)
        edits.launch {
            val entry = (result as? BatchResult.Done)?.entryId
            val toast = writtenToast(resources, result, saved, jobs.size)
            if (toasts.show(toast.copy(action = entry?.let { resources.getString(R.string.lib_undo) })) && entry != null) {
                toasts.show(undoneToast(resources, writer.undo(entry)))
            }
        }
        after()
    }
}

/** What a write says. */
private fun writtenToast(resources: Resources, result: BatchResult, saved: String, asked: Int): Toast = when (result) {
    BatchResult.SessionsUnknown -> Toast(resources.getString(R.string.edit_need_access), R.drawable.ic_error, Tone.ERROR)
    BatchResult.NotEnoughSpace -> Toast(resources.getString(R.string.edit_no_space), R.drawable.ic_error, Tone.ERROR)
    is BatchResult.Done -> {
        val report = result.report()
        when {
            report.written == 0 && report.busy == 0 && report.failed == 0 -> Toast(resources.getString(R.string.edit_nothing_changed), R.drawable.ic_sell)
            report.written == 0 && report.failed == 0 -> Toast(resources.getString(R.string.edit_in_use), R.drawable.ic_error, Tone.ERROR)
            report.written == 0 -> Toast(resources.getString(R.string.edit_not_changed), R.drawable.ic_error, Tone.ERROR)
            report.busy + report.failed > 0 -> Toast(resources.getQuantityString(R.plurals.edit_partly, asked, report.written, asked), R.drawable.ic_sell)
            else -> Toast(saved, R.drawable.ic_sell)
        }
    }
}

/** What an Undo says: done, the fields or songs it kept because they changed since, or what it could not do. */
private fun undoneToast(resources: Resources, result: BatchResult): Toast = when (result) {
    BatchResult.SessionsUnknown -> Toast(resources.getString(R.string.edit_need_access), R.drawable.ic_error, Tone.ERROR)
    BatchResult.NotEnoughSpace -> Toast(resources.getString(R.string.edit_no_space), R.drawable.ic_error, Tone.ERROR)
    is BatchResult.Done -> {
        val report = result.report()
        when {
            report.busy + report.failed > 0 -> Toast(resources.getString(R.string.edit_undo_partly), R.drawable.ic_error, Tone.ERROR)
            report.written == 0 -> Toast(resources.getString(R.string.edit_nothing_to_undo), R.drawable.ic_undo)
            report.changedSince > 0 -> Toast(resources.getQuantityString(R.plurals.edit_undone_skipped, report.changedSince, report.changedSince), R.drawable.ic_undo)
            report.skippedFields > 0 -> Toast(resources.getQuantityString(R.plurals.edit_undone_kept, report.skippedFields, report.skippedFields), R.drawable.ic_undo)
            else -> Toast(resources.getString(R.string.edit_undone), R.drawable.ic_undo)
        }
    }
}

/**
 * Reads the library again now (a write has just changed it), and waits up to [LIBRARY_WAIT_MS] until it is [ready]:
 * then a page can follow its album or artist to the new name. False when it did not get there in time.
 */
internal suspend fun awaitLibrary(context: Context, ready: (Library) -> Boolean): Boolean {
    val repository = LibraryGraph.repository(context)
    repository.refresh()
    return withTimeoutOrNull(LIBRARY_WAIT_MS) { repository.state.first { state -> (state as? LibraryState.Ready)?.library?.let(ready) == true } } != null
}

/** Puts [new] in place of [old] when [old] is the page on top, nothing over it: a renamed album or artist. */
internal fun Navigator.replaceTop(old: Page, new: Page) {
    if (state.views.isNotEmpty() || state.pages.lastOrNull() != old) return
    back()
    open(new)
}

private val FORM_JSON = Json { ignoreUnknownKeys = true }

/** The form across a turn of the phone, as text; one this version can't read starts over. */
private val FORM_SAVER: Saver<TagForm, String> = Saver(
    save = { FORM_JSON.encodeToString(TagForm.serializer(), it) },
    restore = {
        try {
            FORM_JSON.decodeFromString(TagForm.serializer(), it)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    },
)

private val COVER = 132.dp
private const val TEXT_LENGTH = 200
private const val NUMBER_LENGTH = 4
private const val PHOTO_MAX_BYTES = 64 * 1024 * 1024
private const val MAX_COVERS = 3
private const val MAX_TRIED = 5
private const val PREVIEW_TRACKS = 3
private const val PREVIEW_LINES = 6
private const val LIBRARY_WAIT_MS = 5_000L
