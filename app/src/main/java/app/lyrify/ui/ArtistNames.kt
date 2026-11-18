package app.lyrify.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import app.lyrify.R
import app.lyrify.library.Artist
import app.lyrify.library.CollectionKind
import app.lyrify.library.FavouriteCollection
import app.lyrify.library.Library
import app.lyrify.settings.ArtistSplit

// Songs with several artists (user feedback 2026-11-17, item 18): Go to artist when a tag names several, and Settings >
// Split artists. The library reads the tags (library/ArtistSplitter.kt); nothing here writes a file.

/** What Go to artist does for [names] (the artists with a page, in the tag's order): nothing, open the one, or ask which. */
fun artistTap(names: List<String>, onOpen: (String) -> Unit, onChoose: (List<String>) -> Unit): (() -> Unit)? = when (names.size) {
    0 -> null
    1 -> { { onOpen(names.single()) } }
    else -> { { onChoose(names) } }
}

/**
 * The artists a stored artist heart ([key]) stands for: its artist, or, for a heart kept on a whole tag of several artists
 * that is no artist now (hearted in 3.0, or while Split artists was off), each artist the tag names. The heart is kept
 * as it was, so it is the tag's own again with the setting off or the name among the exceptions.
 */
fun heartedArtists(key: String, library: Library): List<Artist> = library.artist(key)?.let(::listOf) ?: library.artistsIn(key)

/** The stored hearts that make artist [name] a favourite: its own, and a whole tag's that stands for it ([heartedArtists]). */
fun artistHearts(name: String, stored: List<FavouriteCollection>, library: Library): List<FavouriteCollection> = stored.filter { heart ->
    heart.kind == CollectionKind.ARTIST && (heart.key == name || library.artist(heart.key) == null && name in library.artistNames(heart.key))
}

/** What a heart's tap changes: the hearts that go ([off]) and those that come ([on]). */
data class HeartChange(val off: List<FavouriteCollection>, val on: List<FavouriteCollection>)

/**
 * What taking artist [name]'s heart off changes in [stored]: its hearts go ([artistHearts]), and the other artists a whole
 * tag's heart stood for each get their own, so they stay favourites.
 */
fun artistHeartOff(name: String, stored: List<FavouriteCollection>, library: Library): HeartChange {
    val off = artistHearts(name, stored, library)
    val on = off.filter { it.key != name }.flatMap { whole -> heartedArtists(whole.key, library) }.map { FavouriteCollection(CollectionKind.ARTIST, it.name) }
        .filter { it.key != name && it !in stored }.distinct()
    return HeartChange(off, on)
}

/** The small menu Go to artist opens for a song of several artists while [names] is set: each name opens its page. */
@Composable
fun ArtistsMenu(names: List<String>?, onDismiss: () -> Unit, onOpen: (String) -> Unit) {
    LyrifyMenu(expanded = names != null, onDismiss = onDismiss) {
        names.orEmpty().forEach { name ->
            MenuRow(name, R.drawable.ic_person, onClick = {
                onDismiss()
                onOpen(name)
            })
        }
    }
}

/**
 * An artist's name that opens their page ([content], given what a tap does, or null without a page): one of [names]
 * opens at once, several open [ArtistsMenu] under the name first.
 */
@Composable
fun ArtistLink(names: List<String>, onOpen: (String) -> Unit, modifier: Modifier = Modifier, content: @Composable (onClick: (() -> Unit)?) -> Unit) {
    var choosing by remember { mutableStateOf<List<String>?>(null) }
    Box(modifier) {
        content(artistTap(names, onOpen) { choosing = it })
        ArtistsMenu(choosing, { choosing = null }, onOpen)
    }
}

/** Settings > Library's line for Split artists: the separators, or that it is off. */
@Composable
internal fun splitArtistsValue(split: ArtistSplit): String = when {
    !split.enabled -> stringResource(R.string.set_artists_off)
    split.separators.isEmpty() -> stringResource(R.string.set_artists_none)
    else -> split.separators.joinToString("  ")
}

/** Which list of Split artists an Add is for. */
private enum class SplitList { SEPARATORS, EXCEPTIONS }

/**
 * Settings > Split artists: the switch, then the separators a tag splits at and the names never split, each with Remove
 * and an Add row at the end. A change regroups the library (LibraryRepository); the files keep their tags.
 */
@Composable
fun SplitArtistsPage() {
    val nav = LocalNavigator.current
    val ui = rememberLibraryUi() ?: return
    val change = rememberSettingsChange()
    val split = ui.settings.library.artistSplit
    var adding by rememberSaveable { mutableStateOf<SplitList?>(null) }
    fun set(update: (ArtistSplit) -> ArtistSplit) = change { it.copy(library = it.library.copy(artistSplit = update(it.library.artistSplit))) }
    PageFrame(topBar = { under, backdrop -> BackTopBar(stringResource(R.string.set_artists), under, backdrop) { nav.back() } }) { padding ->
        val reduced = LocalReducedMotion.current
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp)) {
            item(key = "sentence", contentType = "sentence") {
                Text(
                    stringResource(R.string.set_artists_sentence),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = rowMotion(reduced).padding(start = ScreenPadding + 4.dp, end = ScreenPadding, top = 4.dp, bottom = 12.dp),
                )
            }
            item(key = "switch", contentType = "group") {
                GroupedRows(rowMotion(reduced)) {
                    GroupedRow(
                        R.drawable.ic_person,
                        stringResource(R.string.set_artists_switch),
                        tile = MaterialTheme.colorScheme.surfaceContainerHighest,
                        onTile = MaterialTheme.colorScheme.onSurfaceVariant,
                        first = true,
                        supporting = { Text(stringResource(R.string.set_artists_switch_detail)) },
                        modifier = Modifier.toggleable(split.enabled, role = Role.Switch) { on -> set { it.copy(enabled = on) } },
                        trailing = { LyrifySwitch(split.enabled) },
                    )
                }
            }
            entries(SplitList.SEPARATORS, R.string.set_artists_at, split.separators, R.drawable.ic_tune, R.string.set_artists_add_separator, { adding = it }) { gone ->
                set { it.copy(separators = it.separators - gone) }
            }
            entries(SplitList.EXCEPTIONS, R.string.set_artists_never, split.exceptions, R.drawable.ic_merge, R.string.set_artists_add_name, { adding = it }) { gone ->
                set { it.copy(exceptions = it.exceptions - gone) }
            }
        }
    }
    adding?.let { list ->
        val separators = list == SplitList.SEPARATORS
        AddDialog(
            title = if (separators) R.string.set_artists_add_separator else R.string.set_artists_add_name,
            label = if (separators) R.string.set_artists_separator else R.string.set_artists_name,
            onDismiss = { adding = null },
        ) { typed ->
            adding = null
            // Each once, whatever its case: "Feat." is "feat.".
            set { now ->
                val kept = if (separators) now.separators else now.exceptions
                when {
                    typed.isEmpty() || kept.any { it.equals(typed, ignoreCase = true) } -> now
                    separators -> now.copy(separators = now.separators + typed)
                    else -> now.copy(exceptions = now.exceptions + typed)
                }
            }
        }
    }
}

/**
 * One list of Split artists under its [title]: each of [values] with Remove ([onRemove]), a separator with how it
 * splits, then Add ([onAdd]).
 */
private fun LazyListScope.entries(
    list: SplitList,
    @StringRes title: Int,
    values: List<String>,
    @DrawableRes icon: Int,
    @StringRes add: Int,
    onAdd: (SplitList) -> Unit,
    onRemove: (String) -> Unit,
) {
    item(key = list.name, contentType = "list") {
        Column(rowMotion(LocalReducedMotion.current)) {
            SectionTitle(stringResource(title), Modifier.padding(start = ScreenPadding + 4.dp, end = ScreenPadding, top = 18.dp, bottom = 8.dp))
            GroupedRows {
                val tile = MaterialTheme.colorScheme.surfaceContainerHighest
                val onTile = MaterialTheme.colorScheme.onSurfaceVariant
                values.forEachIndexed { at, value ->
                    val word = value.any(Char::isLetterOrDigit)
                    GroupedRow(
                        icon,
                        value,
                        tile = tile,
                        onTile = onTile,
                        first = at == 0,
                        supporting = if (list == SplitList.SEPARATORS) {
                            { Text(stringResource(if (word) R.string.set_artists_word else R.string.set_artists_mark)) }
                        } else {
                            null
                        },
                        trailing = {
                            ChromeIconButton(R.drawable.ic_close, stringResource(R.string.set_artists_remove, value), { onRemove(value) }, glass = false, size = 36.dp)
                        },
                    )
                }
                GroupedRow(R.drawable.ic_add, stringResource(add), tile = tile, onTile = onTile, first = values.isEmpty(), onClick = { onAdd(list) })
            }
        }
    }
}

/** A separator or a name to add: Add waits for one. */
@Composable
private fun AddDialog(@StringRes title: Int, @StringRes label: Int, onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var typed by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    ConfirmDialog(
        title = stringResource(title),
        confirm = stringResource(R.string.set_artists_add),
        dismiss = stringResource(R.string.remove_cancel),
        onConfirm = { onAdd(typed.text.trim()) },
        onDismiss = onDismiss,
        icon = R.drawable.ic_add,
        destructive = false,
        confirmEnabled = typed.text.isNotBlank(),
    ) {
        NameField(typed, { typed = it.copy(text = it.text.take(MAX_ENTRY)) }, stringResource(label))
    }
}

private const val MAX_ENTRY = 60
