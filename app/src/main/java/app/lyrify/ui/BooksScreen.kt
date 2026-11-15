package app.lyrify.ui

import android.content.Context
import android.content.res.Resources
import android.os.SystemClock
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lyrify.R
import app.lyrify.books.Book
import app.lyrify.books.BookKind
import app.lyrify.books.BookNow
import app.lyrify.books.BookPlace
import app.lyrify.books.BookState
import app.lyrify.books.BookStatus
import app.lyrify.books.BooksGraph
import app.lyrify.books.Chapter
import app.lyrify.books.bookNow
import app.lyrify.books.bookSource
import app.lyrify.books.bookStatus
import app.lyrify.books.chapterAt
import app.lyrify.books.booksInOrder
import app.lyrify.books.chapterPlace
import app.lyrify.books.chapterTicks
import app.lyrify.books.continueBook
import app.lyrify.books.hoursAndMinutes
import app.lyrify.books.nextChapter
import app.lyrify.books.playingBook
import app.lyrify.books.previousChapter
import app.lyrify.books.sleepToChapterEnd
import app.lyrify.library.Folder
import app.lyrify.library.Library
import app.lyrify.player.PlayerConnection
import app.lyrify.player.PlayerGraph
import app.lyrify.player.PlayerUi
import app.lyrify.player.SleepTimer
import app.lyrify.player.Speed
import app.lyrify.player.sleepAfter
import app.lyrify.settings.LibraryTab
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Audiobook mode on screen (features 2026-11-15, item 13): "Mark as book" and "Not a book" in the song and folder menus,
// the Books tab with its Continue row and cards, the chapter sheet, and the player's book parts.

/** Whether Library shows [tab] now: the Books tab only while the library holds a book. */
fun libraryTabShows(tab: LibraryTab, library: Library?): Boolean = tab != LibraryTab.BOOKS || library?.books?.isNotEmpty() == true

// The menus

/** A book row of the song or folder menu acted on: [change] to `books.json`, then its toast, whose Undo puts the marks back. */
internal suspend fun ToastState.markBooks(context: Context, resources: Resources, marked: Boolean, change: (BookState) -> BookState) {
    val store = BooksGraph.store(context)
    val before = store.state.value.marks
    store.change(change)
    val undo = show(Toast(resources.getString(if (marked) R.string.book_marked else R.string.book_unmarked), R.drawable.ic_auto_stories, action = resources.getString(R.string.player_undo)))
    if (undo) store.change { it.copy(marks = before) }
}

/** A folder page's "⋯": "Mark as book", or "Not a book" while one of its own files is in a book. */
@Composable
internal fun BookFolderMenu(folder: Folder, library: Library) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val toasts = LocalToasts.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    val holdsBook = remember(folder, library) { folder.songs.any { library.bookOf(it.path) != null } }
    Box {
        ChromeIconButton(R.drawable.ic_more_horiz, stringResource(R.string.book_folder_menu), { open = true })
        LyrifyMenu(expanded = open, onDismiss = { open = false }) {
            MenuRow(stringResource(if (holdsBook) R.string.book_not else R.string.book_mark), R.drawable.ic_auto_stories, onClick = {
                open = false
                val own = folder.songs.map { it.path }
                scope.launch { toasts.markBooks(context, resources, marked = !holdsBook) { it.toggledFolder(folder.path, own, holdsBook) } }
            })
        }
    }
}

// The Books tab

/** `books.json` as it is now, for the screens. */
@Composable
private fun rememberBooks(): BookState {
    val context = LocalContext.current
    return remember(context) { BooksGraph.store(context).state }.collectAsStateWithLifecycle().value
}

/** [book] plays from where it stands ("Continue", a tap on its card), and the player opens. */
private fun play(connection: PlayerConnection, book: Book, state: BookState, nav: Navigator) {
    val place = state.placeOf(book)
    connection.playFrom(book.parts.map { it.ref }, place.partIndex, place.positionMs, bookSource(book))
    nav.show(FullScreen.Player)
}

/**
 * Books, after Folders in Library, shown while the library holds a book: the Continue row (the book played last, not
 * finished), then every book as a card with its cover, title, author and where it stands, played ones first. A tap
 * plays from the book's place; a long press offers Start over, Mark finished and Not a book.
 */
@Composable
internal fun BooksTab(library: Library, padding: PaddingValues, reselect: Int) {
    val context = LocalContext.current
    val nav = LocalNavigator.current
    val state = rememberBooks()
    val chapters = remember(context) { BooksGraph.chapters(context) }
    val connection = remember(context) { PlayerGraph.connection(context) }
    // The single files' chapters, read once each for their cards.
    LaunchedEffect(library) { library.books.forEach(chapters::ensure) }
    val books = remember(library, state) { booksInOrder(library.books, state) }
    val going = remember(library, state) { continueBook(library.books, state) }
    var acting by remember { mutableStateOf<Book?>(null) }
    val grid = rememberLazyGridState()
    ReselectToTop(reselect) { grid.animateScrollToItem(0) }
    val reduced = LocalReducedMotion.current
    LazyVerticalGrid(
        columns = GridCells.Adaptive(BOOK_CARD_MIN),
        state = grid,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = ScreenPadding, end = ScreenPadding, top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (going != null) {
            item(key = "continue heading", span = { GridItemSpan(maxLineSpan) }, contentType = "heading") {
                Box(rowMotion(reduced)) { BooksHeading(stringResource(R.string.books_continue)) }
            }
            item(key = "continue", span = { GridItemSpan(maxLineSpan) }, contentType = "continue") {
                ContinueCard(going, bookStatus(going, state), rowMotion(reduced)) { play(connection, going, state, nav) }
            }
        }
        item(key = "books heading", span = { GridItemSpan(maxLineSpan) }, contentType = "heading") {
            Box(rowMotion(reduced)) { BooksHeading(stringResource(R.string.library_tab_books)) }
        }
        items(books, key = { "book ${it.id}" }, contentType = { "book" }) { book ->
            BookCard(book, bookStatus(book, state), rowMotion(reduced), onLongPress = { acting = book }) { play(connection, book, state, nav) }
        }
    }
    acting?.let { book -> BookActionsSheet(book, state) { acting = null } }
}

@Composable
private fun BooksHeading(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(top = 12.dp).semantics { heading() })
}

/** The Continue row: one wide card with the cover, the title, where it is, a thin progress bar and the time left. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContinueCard(book: Book, status: BookStatus, modifier: Modifier, onPlay: () -> Unit) {
    val words = listOfNotNull(book.title, statusWords(status)).joinToString(", ")
    val action = stringResource(R.string.books_continue)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .combinedClickable(role = Role.Button, onClickLabel = action, onClick = onPlay)
            .semantics(mergeDescendants = true) { contentDescription = words }
            .padding(12.dp),
    ) {
        Box(Modifier.clearAndSetSemantics {}) { SongCover(SongArt(book.parts.first().path, book.parts.first().ref.mediaStoreId), size = 72.dp, shape = RoundedCornerShape(16.dp)) }
        Column(Modifier.weight(1f).clearAndSetSemantics {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            StatusLines(status)
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(48.dp).clip(CircleShape).background(LocalChromeColors.current.action).clearAndSetSemantics {},
        ) { PlayPauseIcon(false, tint = LocalChromeColors.current.onAction, modifier = Modifier.size(26.dp)) }
    }
}

/** A book's card: its cover filling the width, its title, its author and where it stands. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookCard(book: Book, status: BookStatus, modifier: Modifier, onLongPress: () -> Unit, onPlay: () -> Unit) {
    val words = listOfNotNull(book.title, book.author, statusWords(status)).joinToString(", ")
    val play = stringResource(R.string.player_play)
    val more = stringResource(R.string.books_more)
    Column(
        modifier
            .clip(RoundedCornerShape(22.dp))
            .combinedClickable(role = Role.Button, onClickLabel = play, onLongClickLabel = more, onLongClick = onLongPress, onClick = onPlay)
            .semantics(mergeDescendants = true) { contentDescription = words }
            .padding(bottom = 8.dp),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth().clearAndSetSemantics {}) {
            SongCover(SongArt(book.parts.first().path, book.parts.first().ref.mediaStoreId), size = maxWidth, shape = RoundedCornerShape(20.dp))
        }
        Column(Modifier.clearAndSetSemantics {}.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(book.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            book.author?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            StatusLines(status)
        }
    }
}

/** Where a book stands, in the card's quieter ink: "Chapter 7 of 22", a thin progress bar and "2 h 10 m left". */
@Composable
private fun StatusLines(status: BookStatus) {
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant
    val style = MaterialTheme.typography.bodySmall.tabular()
    when (status) {
        is BookStatus.NotStarted -> Text(stringResource(R.string.lib_after, stringResource(R.string.book_not_started), duration(status.totalMs)), style = style, color = quiet, maxLines = 1)
        BookStatus.Finished -> Text(stringResource(R.string.book_finished), style = style, color = quiet, maxLines = 1)
        is BookStatus.Listening -> {
            status.chapter?.let { Text(stringResource(R.string.book_chapter_of, it, status.chapters), style = style, color = quiet, maxLines = 1) }
            ThinProgress(status.progress)
            Text(stringResource(R.string.book_left, duration(status.leftMs)), style = style, color = quiet, maxLines = 1)
        }
    }
}

/** What TalkBack reads for a book's state, one line. */
@Composable
private fun statusWords(status: BookStatus): String = when (status) {
    is BookStatus.NotStarted -> stringResource(R.string.lib_after, stringResource(R.string.book_not_started), duration(status.totalMs))
    BookStatus.Finished -> stringResource(R.string.book_finished)
    is BookStatus.Listening -> listOfNotNull(
        status.chapter?.let { stringResource(R.string.book_chapter_of, it, status.chapters) },
        stringResource(R.string.book_left, duration(status.leftMs)),
    ).joinToString(", ")
}

/** "2 h 10 m", or "45 m" under an hour. */
@Composable
private fun duration(ms: Long): String {
    val (hours, minutes) = hoursAndMinutes(ms)
    return if (hours > 0) stringResource(R.string.book_hours_minutes, hours, minutes) else stringResource(R.string.book_minutes, minutes)
}

@Composable
private fun ThinProgress(share: Float) {
    val highlight = LocalBrandColors.current.highlight
    Box(Modifier.padding(vertical = 4.dp).fillMaxWidth().height(4.dp).clip(CircleShape).background(highlight.copy(alpha = TRACK_ALPHA))) {
        Box(Modifier.fillMaxWidth(share.coerceIn(0f, 1f)).height(4.dp).clip(CircleShape).background(highlight))
    }
}

/** A long press on a card: Start over, Mark finished, Not a book. */
@Composable
private fun BookActionsSheet(book: Book, state: BookState, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val toasts = LocalToasts.current
    val scope = rememberCoroutineScope()
    val store = remember(context) { BooksGraph.store(context) }
    LyrifySheet(onDismiss = onDismiss, spacing = 4.dp, padding = 12.dp) {
        Text(book.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp).semantics { heading() })
        SheetAction(R.drawable.ic_replay, stringResource(R.string.book_start_over)) {
            store.change { it.startOver(book) }
            onDismiss()
        }
        if (state.entryOf(book)?.finished != true) {
            SheetAction(R.drawable.ic_check, stringResource(R.string.book_mark_finished)) {
                store.change { it.markFinished(book) }
                onDismiss()
            }
        }
        SheetAction(R.drawable.ic_auto_stories, stringResource(R.string.book_not)) {
            onDismiss()
            scope.launch { toasts.markBooks(context, resources, marked = false) { it.notABook(book) } }
        }
    }
}

/** A row of a book's sheets: its icon and words, 52 dp tall. */
@Composable
private fun SheetAction(icon: Int, text: String, selected: Boolean = false, trailing: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    val brand = LocalBrandColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) brand.highlightContainer else MaterialTheme.colorScheme.surface.copy(alpha = 0f))
            .combinedClickable(role = Role.Button, onClick = onClick)
            .then(if (selected) Modifier.semantics { this.selected = true } else Modifier)
            .heightIn(min = 52.dp)
            .padding(horizontal = 8.dp),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = if (selected) brand.onHighlightContainer else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = if (selected) brand.onHighlightContainer else MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

// The player in book mode

/**
 * The book playing in the player now; null for music. It follows the chapter playing, looked at twice a second while it
 * plays, and changes (so its callers draw again) only when the chapter does: the player is not redrawn on every tick.
 */
@Composable
internal fun rememberBookNow(ui: PlayerUi): BookNow? {
    val state = rememberBooks()
    val playing = remember(ui.queue, ui.item, ui.library) { playingBook(ui.queue, ui.library, ui.item.uid) } ?: return null
    val latest by rememberUpdatedState(ui)
    fun look(): BookNow = bookNow(playing.book, playing.partIndex, latest.positionAt(SystemClock.elapsedRealtime()), state)
    val chapter by produceState(look().chapter, playing, state, ui) {
        while (true) {
            value = look().chapter
            if (!latest.playing) break
            delay(CHAPTER_LOOK_MS)
        }
    }
    return remember(playing, state, chapter) { look() }
}

/** Chapter [index] of the book playing ([now]) plays: a seek in a single file, or its part from the start. */
internal fun goToChapter(connection: PlayerConnection, ui: PlayerUi, now: BookNow, index: Int) {
    val place: BookPlace = chapterPlace(now, index)
    if (place.partIndex == now.partIndex) return connection.seekTo(place.positionMs)
    val part = now.book.parts.getOrNull(place.partIndex) ?: return
    val at = ui.queue.items.indexOfFirst { it.song.path == part.path }
    if (at >= 0) connection.jump(ui.queue.id, at)
}

/** The player's Previous and Next in a book: the chapter before (or this one's start) and the one after. */
internal fun chapterStep(connection: PlayerConnection, ui: PlayerUi, now: BookNow, forward: Boolean) {
    // Where it is at the tap, not when the chapter last changed.
    val at = now.book.partStartMs(now.partIndex) + ui.positionAt(SystemClock.elapsedRealtime())
    val fresh = now.copy(bookMs = at, chapter = chapterAt(now.chapters, at))
    val target = if (forward) nextChapter(fresh) else previousChapter(fresh)
    if (target != null) goToChapter(connection, ui, fresh, target) else if (!forward) connection.seekTo(0)
}

/** A chapter's title, or "Chapter 7" when the file names none. */
@Composable
internal fun chapterTitle(chapters: List<Chapter>, index: Int): String =
    chapters.getOrNull(index)?.title ?: stringResource(R.string.book_chapter, index + 1)

/** "Chapter 7 of 22" for the book playing; null without chapters. */
@Composable
internal fun chapterOf(now: BookNow): String? = now.chapter.takeIf { it >= 0 }?.let { stringResource(R.string.book_chapter_of, it + 1, now.chapters.size) }

/** On the progress bar of a single file with chapters: a tick where each chapter starts. Touches go to the bar. */
@Composable
internal fun ChapterTicks(ui: PlayerUi) {
    val now = rememberBookNow(ui) ?: return
    val ticks = remember(now.book, now.chapters, ui.durationMs) { chapterTicks(now, ui.durationMs) }
    if (ticks.isEmpty()) return
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.fillMaxWidth().height(TOUCH)) {
        val tick = Size(2.dp.toPx(), 10.dp.toPx())
        val mid = size.height / 2
        ticks.forEach { share -> drawRoundRect(ink.copy(alpha = TICK_ALPHA), Offset(size.width * share - tick.width / 2, mid - tick.height / 2), tick, CornerRadius(1.dp.toPx())) }
    }
}

/**
 * "Chapters", from the player: a row per chapter with its number, title and start, a check on those heard, the one
 * playing highlighted and in view. A tap plays it.
 */
@Composable
internal fun ChapterSheet(ui: PlayerUi, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val connection = remember(context) { PlayerGraph.connection(context) }
    val now = rememberBookNow(ui) ?: return onDismiss()
    val scroll = rememberScrollState()
    val row = with(LocalDensity.current) { CHAPTER_ROW.toPx() }
    // The chapter playing in view when the sheet opens.
    LaunchedEffect(Unit) { if (now.chapter > 2) scroll.scrollTo(((now.chapter - 2) * row).toInt()) }
    LyrifySheet(onDismiss = onDismiss, spacing = 0.dp, padding = 12.dp, scroll = scroll) {
        Text(stringResource(R.string.books_chapters), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp).semantics { heading() })
        now.chapters.forEachIndexed { index, chapter ->
            val heard = index < now.chapter
            val shown = chapterTitle(now.chapters, index)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = CHAPTER_ROW)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (index == now.chapter) LocalBrandColors.current.highlightContainer else MaterialTheme.colorScheme.surface.copy(alpha = 0f))
                    .combinedClickable(role = Role.Button) {
                        goToChapter(connection, ui, now, index)
                        onDismiss()
                    }
                    .semantics(mergeDescendants = true) { if (index == now.chapter) selected = true }
                    .padding(horizontal = 8.dp),
            ) {
                val ink = if (index == now.chapter) LocalBrandColors.current.onHighlightContainer else MaterialTheme.colorScheme.onSurface
                Text("${index + 1}", style = MaterialTheme.typography.bodyMedium.tabular(), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.widthIn(min = 28.dp))
                Text(shown, style = MaterialTheme.typography.bodyLarge, color = ink, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(clock(chapter.startMs), style = MaterialTheme.typography.bodySmall.tabular(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (heard) {
                    Icon(painterResource(R.drawable.ic_check), contentDescription = stringResource(R.string.book_heard), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                } else {
                    Box(Modifier.size(18.dp))
                }
            }
        }
    }
}

/** The speed of the book playing, for the speed sheet and its chip, and the change of it, kept as the book's own. */
internal class BookSpeedUi(val speed: Speed, val set: ((Speed) -> Speed) -> Unit)

/** The book playing's speed ([BookSpeedUi]); null while music plays. */
@Composable
internal fun rememberBookSpeed(ui: PlayerUi): BookSpeedUi? {
    val context = LocalContext.current
    val state = rememberBooks()
    val book = remember(ui.queue, ui.item, ui.library) { playingBook(ui.queue, ui.library, ui.item.uid)?.book } ?: return null
    val store = remember(context) { BooksGraph.store(context) }
    return BookSpeedUi(state.speedOf(book)) { change -> store.change { it.withSpeed(book, change(it.speedOf(book))) } }
}

/**
 * The sleep timer's "End of chapter" for the book playing: a folder book's part is a chapter (to the end of the file),
 * a single file stops at the end of the chapter playing. Null for music and a book without chapters.
 */
@Composable
internal fun rememberChapterEnd(ui: PlayerUi): (() -> SleepTimer)? {
    val now = rememberBookNow(ui) ?: return null
    if (now.chapters.isEmpty()) return null
    return when (now.book.kind) {
        BookKind.FOLDER -> { { sleepAfter(1) } }
        BookKind.SINGLE -> { { sleepToChapterEnd(now.chapters, ui.positionAt(SystemClock.elapsedRealtime())) ?: sleepAfter(1) } }
    }
}

/** A book card's least width: two columns on a phone held upright, more on a wide screen. */
private val BOOK_CARD_MIN = 150.dp

/** A row of the chapter sheet. */
private val CHAPTER_ROW = 52.dp

/** How often the player looks which chapter plays. */
private const val CHAPTER_LOOK_MS: Long = 500

private const val TRACK_ALPHA = 0.16f
private const val TICK_ALPHA = 0.7f
