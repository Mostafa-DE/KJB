package app.lyrify.books

import app.lyrify.library.Library
import app.lyrify.library.LibraryState
import app.lyrify.library.Song
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.math.ceil

// Audiobook mode (features 2026-11-15, item 13): what makes a song or a folder a book, the books grouped from the
// library's songs, their chapters, and where a listener is in one. Pure: the library builds its books with [booksOf],
// the Books tab and the player read the rest. Research: handoff/audiobook-research.md, sections 2 and 6.

/**
 * What the user said in the song and folder menus. "Mark as book" on a song ([songs], by path) makes it a book of its
 * own; on a folder ([folders], relative to its volume without the last slash, as [app.lyrify.library.Folder.path] has
 * it) it makes the folder's own files books, grouped by album. "Not a book" ([notSongs], [notFolders]) keeps them music
 * whatever the automatic signals say. A song's own mark wins over its folder's.
 */
data class BookMarks(
    val songs: Set<String> = emptySet(),
    val folders: Set<String> = emptySet(),
    val notSongs: Set<String> = emptySet(),
    val notFolders: Set<String> = emptySet(),
)

/** A book of one file (its chapters inside it, or none), or of the files of one folder and album (a file per chapter). */
enum class BookKind { SINGLE, FOLDER }

/**
 * A book in the library. [id] never changes while its files stay: the file's path for an `.m4b` or a song marked as a
 * book, else the folder's path and the album ([folderBookId]), one file of them or more, so it holds as parts are
 * hidden, added or removed. [parts] are in play order ([partOrder]); a single file has one. [title] is the file's title
 * or the folder book's album (else the folder's name); [author] the album artist or artist.
 */
data class Book(val id: String, val kind: BookKind, val title: String, val author: String?, val parts: List<Song>) {
    /** The whole book's length; a part MediaStore could not measure counts 0. */
    val durationMs: Long get() = parts.sumOf { it.durationMs.coerceAtLeast(0) }

    /** Where part [index] starts in the whole book. */
    fun partStartMs(index: Int): Long = parts.take(index.coerceAtLeast(0)).sumOf { it.durationMs.coerceAtLeast(0) }

    /** The folder its files are in, relative to the volume without the last slash: what a folder mark names. */
    val folder: String get() = folderKey(parts.first())
}

/** A chapter, in the book's own time: from [startMs] until [endMs]. [title] null when the file names none. */
data class Chapter(val startMs: Long, val endMs: Long, val title: String?)

/** The genre tags that make a song a book on their own, compared trimmed and without case. */
val BOOK_GENRES: Set<String> = setOf("audiobook", "audiobooks", "audio book", "spoken word")

/**
 * Whether [song] is a book by a signal of its own: MediaStore's IS_AUDIOBOOK, a folder named Audiobooks on the way
 * (where Android's scanner sets that flag, checked here too as phones differ), the `.m4b` extension, or a book genre.
 * Podcasts and long files only suggest "Mark as book"; they never make a book on their own.
 */
fun autoBook(song: Song): Boolean =
    song.isAudiobook ||
        inAudiobooksFolder(song.folder) ||
        song.path.endsWith(M4B, ignoreCase = true) ||
        song.genre?.trim()?.lowercase(Locale.ROOT) in BOOK_GENRES

/**
 * Whether [song], music in [library], looks like a book without being one: a podcast episode, an .m4a the song menu
 * found chapters in ([chaptered], [readsChaptersFor]), or a file of [LOOKS_LONG_MS] or more with no other song of its
 * folder and album beside it (so a folder of long mixes does not). The song menu says so under "Mark as book"; it never
 * makes a book by itself (spec 13: chapters are no book signal, and DJ mixes have them too). A song the library hides
 * (Hidden songs, opened from a playlist) suggests nothing.
 */
fun looksLikeBook(song: Song, library: Library, chaptered: Boolean = false): Boolean {
    if (library.bookOf(song.path) != null || !library.shows(song)) return false
    if (song.isPodcast || chaptered) return true
    if (song.durationMs < LOOKS_LONG_MS) return false
    val key = groupKey(song)
    return library.songs.none { it.path != song.path && groupKey(it) == key }
}

/** Whether the song menu reads [song]'s chapters, to suggest Mark as book ([looksLikeBook]): an .m4a that is no book. */
fun readsChaptersFor(song: Song, library: Library): Boolean = song.path.endsWith(M4A, ignoreCase = true) && library.bookOf(song.path) == null

/**
 * Whether the file at [path] is a book's part, for the lyrics and the AI, which never touch a book: by [library] once it
 * is read, a file it hides (Hidden songs, Folders to skip, played from a playlist) by its own mark or signal
 * ([Library.hiddenBook]); before that, and for a file it does not know, by what the path alone says ([bookByPath]).
 */
fun isBookPart(path: String, library: Library?): Boolean =
    library?.bookOf(path) != null || library?.hiddenBook(path) == true || library?.song(path) == null && bookByPath(path)

/** Whether [path] alone says it is a book: an .m4b, or a file in a folder named Audiobooks. */
private fun bookByPath(path: String): Boolean = path.endsWith(M4B, ignoreCase = true) || inAudiobooksFolder(path.substringBeforeLast('/'))

/**
 * Whether the file at [path] is a book's part as far as can be told now: [isBookPart] once [library] is read; before
 * that, true when its path says so, else null: not known yet, as a book by the user's mark or its tags looks like a
 * song until then. The lyrics views read nothing for a file not known yet.
 */
fun knownBookPart(path: String, library: Library?): Boolean? = if (library != null || bookByPath(path)) isBookPart(path, library) else null

/**
 * [isBookPart] once [library] has been read, waiting at most [waitMs] for it: until then a book known only by the user's
 * mark or its tags would pass for a song and be looked up. Never read in time, or unreadable: by its path alone.
 */
suspend fun isBookPartOnceRead(path: String, library: StateFlow<LibraryState>, waitMs: Long = LIBRARY_READ_WAIT_MS): Boolean {
    val state = withTimeoutOrNull(waitMs) { library.first { it !is LibraryState.Loading } } ?: library.value
    return isBookPart(path, (state as? LibraryState.Ready)?.library)
}

/** How long the lyrics wait for the library before they judge a file by its path alone ([isBookPartOnceRead]). */
const val LIBRARY_READ_WAIT_MS: Long = 120_000

/** A lone file this long looks like a book ([looksLikeBook]). */
const val LOOKS_LONG_MS: Long = 2 * 3_600_000

/** Whether [folder] (relative to its volume) is in a folder named Audiobooks, at any depth and in any case. */
fun inAudiobooksFolder(folder: String): Boolean = folder.split('/').any { it.equals(AUDIOBOOKS, ignoreCase = true) }

/** The folder a mark names for [song]: its folder relative to the volume, without the slashes at the ends. */
fun folderKey(song: Song): String = song.folder.trim('/')

/** What the user said of [song]: true "Mark as book", false "Not a book", null nothing. Its own mark wins over its folder's. */
fun userMark(song: Song, marks: BookMarks): Boolean? = when {
    song.path in marks.notSongs -> false
    song.path in marks.songs -> true
    folderKey(song) in marks.notFolders -> false
    folderKey(song) in marks.folders -> true
    else -> null
}

/**
 * The id of the folder book of the files in folder [dir] (an absolute path) with album [album]: the two apart by a NUL,
 * which no path holds, so the id is never a file's path, nor another folder's book's.
 */
fun folderBookId(dir: String, album: String?): String = "$dir$FOLDER_BOOK${album.orEmpty()}"

/**
 * The id [folderBookId] gave the same book before PF10, the folder and the album apart by "/|", which a file's path or
 * another folder's book could have too: what `books.json`, queues and backups written then name it by. Null for an id
 * that is no folder book's.
 */
fun oldFolderBookId(id: String): String? = id.takeIf { FOLDER_BOOK in it }?.replaceFirst(FOLDER_BOOK, OLD_FOLDER_BOOK)

/** The album folder book [id] names ([folderBookId], or [oldFolderBookId] as a backup made before PF10 has it); null for a path. */
fun folderBookAlbum(id: String): String? = when {
    FOLDER_BOOK in id -> id.substringAfter(FOLDER_BOOK)
    OLD_FOLDER_BOOK in id -> id.substringAfter(OLD_FOLDER_BOOK)
    else -> null
}

/**
 * The books among [songs] (the library's, already filtered), by [marks] and the automatic signals, by title. A song
 * marked "Mark as book" is a book of its own. The rest are grouped by folder (their absolute folder) and album tag (the
 * same, or none for all): a group of two or more files is one folder book when its folder is marked or every one of its
 * files has an automatic signal ([autoBook]), so one track tagged Spoken Word never takes its album out of the music; an
 * `.m4b` is always a book of its own, never a part. A lone file is a book when it has a signal of its own or its folder
 * is marked. "Not a book" on a song or its folder keeps it out. Never two books with one id: a file listed twice is
 * one book, as two books would share one place.
 */
fun booksOf(songs: List<Song>, marks: BookMarks): List<Book> {
    val books = ArrayList<Book>()
    val grouped = ArrayList<Song>()
    songs.forEach { song ->
        when {
            song.path in marks.notSongs || folderKey(song) in marks.notFolders && song.path !in marks.songs -> Unit
            song.path in marks.songs || song.path.endsWith(M4B, ignoreCase = true) -> books += single(song)
            else -> grouped += song
        }
    }
    grouped.groupBy(::groupKey).forEach { (key, group) ->
        val folderMarked = folderKey(group.first()) in marks.folders
        if (!folderMarked && !group.all(::autoBook)) return@forEach
        val (dir, album) = key
        // One file left of its folder and album is still that book: the same id, its chapters in the file.
        books += if (group.size == 1) {
            single(group.single()).copy(id = folderBookId(dir, album))
        } else {
            Book(folderBookId(dir, album), BookKind.FOLDER, album ?: dir.substringAfterLast('/'), authorOf(group), partOrder(group))
        }
    }
    return books.distinctBy { it.id }.sortedWith(compareBy<Book, String>(::naturalCompare) { it.title }.thenBy { it.id })
}

/** What the files of one folder book share: their absolute folder and their album tag (or none). */
private fun groupKey(song: Song): Pair<String, String?> = song.path.substringBeforeLast('/') to song.album?.trim()?.takeIf(String::isNotEmpty)

/** A book of [song] alone. */
private fun single(song: Song): Book = Book(song.path, BookKind.SINGLE, song.title, authorOf(listOf(song)), listOf(song))

/** The album artist, else the artist, of the first part that has one. */
private fun authorOf(parts: List<Song>): String? =
    parts.firstNotNullOfOrNull { it.albumArtist?.trim()?.takeIf(String::isNotEmpty) } ?: parts.firstNotNullOfOrNull { it.artist?.trim()?.takeIf(String::isNotEmpty) }

/**
 * The order of a folder book's [parts]: by disc and track number when every part has a track number, else by file name
 * with each run of digits by its value, so `2.mp3` comes before `10.mp3`.
 */
fun partOrder(parts: List<Song>): List<Song> {
    val byName = compareBy<Song, String>(::naturalCompare) { it.fileName }.thenBy { it.path }
    return if (parts.all { it.track != null }) parts.sortedWith(compareBy<Song> { it.disc ?: 1 }.thenBy { it.track }.then(byName)) else parts.sortedWith(byName)
}

/** [a] and [b] with each run of digits compared by its value, the rest without case. */
fun naturalCompare(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        val digits = a[i].isDigit()
        if (digits != b[j].isDigit()) return a[i].lowercaseChar().compareTo(b[j].lowercaseChar())
        var endA = i
        while (endA < a.length && a[endA].isDigit() == digits) endA++
        var endB = j
        while (endB < b.length && b[endB].isDigit() == digits) endB++
        val runA = a.substring(i, endA)
        val runB = b.substring(j, endB)
        val order = if (digits) {
            val valueA = runA.trimStart('0')
            val valueB = runB.trimStart('0')
            // The longer number is the bigger; of equal length, digit by digit; of one value, fewer leading zeros first.
            valueA.length.compareTo(valueB.length).takeIf { it != 0 } ?: valueA.compareTo(valueB).takeIf { it != 0 } ?: runA.length.compareTo(runB.length)
        } else {
            runA.compareTo(runB, ignoreCase = true)
        }
        if (order != 0) return order
        i = endA
        j = endB
    }
    return (a.length - i).compareTo(b.length - j).takeIf { it != 0 } ?: a.compareTo(b)
}

/** A folder book's chapter title for its part [song]: its title tag, or its file name without the number in front and the extension; null when that leaves nothing. */
fun partTitle(song: Song): String? {
    val name = song.fileName.substringBeforeLast('.')
    if (song.title != name) return song.title.trim().takeIf(String::isNotEmpty)
    return name.replace(LEADING_NUMBER, "").trim().takeIf(String::isNotEmpty)
}

/** A file name's number in front and what follows it: "01 ", "01 - ", "1. ", "01_". */
private val LEADING_NUMBER = Regex("""^\d+[\s._-]*""")

/** A folder book's chapters: one per part, each as long as its file. */
fun partChapters(book: Book): List<Chapter> {
    var start = 0L
    return book.parts.map { part ->
        val end = start + part.durationMs.coerceAtLeast(0)
        Chapter(start, end, partTitle(part)).also { start = end }
    }
}

/** Where a listener is in a book: part [partIndex] (0 for a single file), [positionMs] into it. */
data class BookPlace(val partIndex: Int = 0, val positionMs: Long = 0)

/** [place] in [book]'s own time: the parts before it, then the position in its part; within the book. */
fun bookMs(book: Book, place: BookPlace): Long {
    val index = place.partIndex.coerceIn(0, book.parts.lastIndex)
    val length = book.parts[index].durationMs.coerceAtLeast(0)
    return book.partStartMs(index) + place.positionMs.coerceIn(0, length)
}

/** The chapter playing at [ms] of the book: the last one starting at or before it; -1 before the first or without chapters. */
fun chapterAt(chapters: List<Chapter>, ms: Long): Int {
    var low = 0
    var high = chapters.lastIndex
    var found = -1
    while (low <= high) {
        val mid = (low + high) ushr 1
        if (chapters[mid].startMs <= ms) {
            found = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return found
}

/** The time left from [ms] to the end of a book [durationMs] long at [speed]: what the card's "2 h 10 m left" says. */
fun leftMs(durationMs: Long, ms: Long, speed: Float): Long = ((durationMs - ms).coerceAtLeast(0) / speed.coerceAtLeast(MIN_SPEED)).toLong()

/** [ms] in whole hours and minutes for "2 h 10 m": rounded up to the next minute, so a book with seconds left still says 1 m. */
fun hoursAndMinutes(ms: Long): Pair<Int, Int> {
    val minutes = ceil(ms.coerceAtLeast(0) / MINUTE_MS.toDouble()).toLong()
    return (minutes / 60).toInt() to (minutes % 60).toInt()
}

/** What a book's card says under its author. */
sealed interface BookStatus {
    /** Never played: the whole length. */
    data class NotStarted(val totalMs: Long) : BookStatus

    /** Played: chapter [chapter] (1 up) of [chapters] when it has chapters, else null, and the time [leftMs] at its speed. */
    data class Listening(val chapter: Int?, val chapters: Int, val leftMs: Long, val progress: Float) : BookStatus

    data object Finished : BookStatus
}

/** What sets a folder book's folder and album apart in its id ([folderBookId]), and what did before PF10 ([oldFolderBookId]). */
private const val FOLDER_BOOK: String = "\u0000"
private const val OLD_FOLDER_BOOK: String = "/|"
private const val M4B: String = ".m4b"
private const val M4A: String = ".m4a"
private const val AUDIOBOOKS: String = "Audiobooks"
private const val MINUTE_MS: Long = 60_000
private const val MIN_SPEED: Float = 0.1f

/** What [book]'s card says ([BookStatus]): finished, never played, or where it is with the time left at its speed. */
fun bookStatus(book: Book, state: BookState): BookStatus {
    val entry = state.entryOf(book)
    if (entry?.finished == true) return BookStatus.Finished
    if (entry?.lastPlayedAt == null) return BookStatus.NotStarted(book.durationMs)
    val chapters = bookChapters(book, state)
    val at = bookMs(book, state.placeOf(book))
    val total = book.durationMs
    val chapter = chapterAt(chapters, at).takeIf { it >= 0 }?.plus(1)
    return BookStatus.Listening(chapter, chapters.size, leftMs(total, at, state.speedOf(book).speed), if (total > 0) at.toFloat() / total else 0f)
}

/** The Books tab's Continue row: the book played last that is not finished; null when there is none. */
fun continueBook(books: List<Book>, state: BookState): Book? =
    books.filter { state.entryOf(it)?.let { entry -> entry.lastPlayedAt != null && !entry.finished } == true }.maxByOrNull { state.entryOf(it)?.lastPlayedAt ?: 0 }

/** The Books tab's order: the books played, the latest first, then the others as [books] has them (by title). */
fun booksInOrder(books: List<Book>, state: BookState): List<Book> = books.sortedByDescending { state.entryOf(it)?.lastPlayedAt ?: Long.MIN_VALUE }

/**
 * The book playing, as the player shows it: its part [partIndex] playing, its [chapters] (a folder book's are its
 * parts), the [chapter] playing (-1 without chapters), and where it is in the whole book ([bookMs]).
 */
data class BookNow(val book: Book, val partIndex: Int, val chapters: List<Chapter>, val chapter: Int, val bookMs: Long)

/** The book playing in part [partIndex] at [positionMs] of it, with the chapters [state] keeps. */
fun bookNow(book: Book, partIndex: Int, positionMs: Long, state: BookState): BookNow {
    val chapters = bookChapters(book, state)
    val at = bookMs(book, BookPlace(partIndex, positionMs))
    return BookNow(book, partIndex, chapters, chapterAt(chapters, at), at)
}

/** Where the progress bar marks the chapters of a single file [durationMs] long: each chapter's start but the first, as shares. */
fun chapterTicks(now: BookNow, durationMs: Long): List<Float> =
    if (now.book.kind != BookKind.SINGLE || durationMs <= 0) emptyList() else now.chapters.drop(1).map { (it.startMs.toFloat() / durationMs).coerceIn(0f, 1f) }

/** Where chapter [index] starts: a place in a single file, or a folder book's part from its start. */
fun chapterPlace(now: BookNow, index: Int): BookPlace = when (now.book.kind) {
    BookKind.SINGLE -> BookPlace(0, now.chapters.getOrNull(index)?.startMs ?: 0)
    BookKind.FOLDER -> BookPlace(index.coerceIn(0, now.book.parts.lastIndex), 0)
}

/** "Previous chapter": the start of the chapter playing, or within [CHAPTER_BACK_MS] of it the one before; null without chapters. */
fun previousChapter(now: BookNow): Int? {
    if (now.chapters.isEmpty()) return null
    val playing = now.chapter.coerceAtLeast(0)
    val into = now.bookMs - now.chapters[playing].startMs
    return if (into > CHAPTER_BACK_MS || playing == 0) playing else playing - 1
}

/** "Next chapter": the chapter after the one playing; null at the last or without chapters. */
fun nextChapter(now: BookNow): Int? = (now.chapter + 1).takeIf { now.chapters.isNotEmpty() && it < now.chapters.size }

/** Previous goes to the chapter before only this close to the start of the one playing, as a player's Previous does. */
const val CHAPTER_BACK_MS: Long = 3_000

/** [now] at [positionMs] of the part playing: where it is at a tap, not when the chapter last changed. */
fun bookNowAt(now: BookNow, positionMs: Long): BookNow {
    val at = now.book.partStartMs(now.partIndex) + positionMs
    return now.copy(bookMs = at, chapter = chapterAt(now.chapters, at))
}

/**
 * The part the player's chapter Next ([forward]) or Previous goes to from [now] ([nextChapter], [previousChapter]): the
 * part after or before in a folder book; null when it stays in the part playing (a single file's chapters, Previous
 * starting the part over) or goes nowhere (Next on the last chapter).
 */
fun chapterStepPart(now: BookNow, forward: Boolean): Int? {
    val target = (if (forward) nextChapter(now) else previousChapter(now)) ?: return null
    return chapterPlace(now, target).partIndex.takeIf { it != now.partIndex }
}
