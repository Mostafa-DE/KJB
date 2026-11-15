package app.lyrify.books

import androidx.media3.common.C
import androidx.media3.common.Label
import androidx.media3.common.Metadata
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import app.lyrify.library.Library
import app.lyrify.library.Song
import app.lyrify.library.song
import app.lyrify.player.Speed
import app.lyrify.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random
import androidx.media3.extractor.metadata.Chapter as Media3Chapter

/**
 * The book formats end to end: one m4b or m4a with its chapters inside, one mp3 per chapter in a folder, one long mp3
 * without chapters, and files that look like a book only by their length or their folder. For each: whether it is a
 * book (and out of the music), its chapters, where it resumes, and what its card says. Media3's entries are built by
 * hand as its extractors give them; the real files are read on the phone (BookChaptersDeviceTest). Made-up names only.
 */
class BookFormatsTest {
    private val music = song("Music/Harbour Glass/01 Paper Lantern.mp3", album = "Harbour Glass", track = 1)

    private fun library(vararg songs: Song, marks: BookMarks = BookMarks()): Library = Library.of(songs.toList() + music, Settings(), marks)

    /** An MP4 chapter as Media3 reads a QuickTime or Nero one: its start and title, its end unknown. */
    private fun mp4Chapter(startMs: Long, title: String?): Metadata.Entry =
        Media3Chapter.Builder().setStartTimeMs(startMs).setEndTimeMs(C.TIME_UNSET).apply { title?.let { setTitle(Label(null, it)) } }.build()

    /** [song] is a book's, and in no music list: Songs, Albums, Artists, Genres, search and Shuffle all read [Library.songs]. */
    private fun assertOutOfMusic(library: Library, song: Song) {
        assertTrue(library.bookOf(song.path) != null)
        assertFalse(library.shows(song))
        assertTrue(library.songs.none { it.path == song.path })
        assertTrue(library.albums.none { album -> album.songs.any { it.path == song.path } })
    }

    // One m4b or m4a, its chapters inside

    private val m4b = song("Music/Books/Lantern Keeper.m4b", title = "Lantern Keeper", artist = "Ada Quill", album = "Lantern Keeper", durationMs = 3 * HOUR, addedAt = 1)

    @Test
    fun `an m4b with chapters inside is a book wherever it is, its card following the chapter and the book's speed`() {
        val library = library(m4b)
        val book = library.bookOf(m4b.path)!!
        assertEquals(BookKind.SINGLE, book.kind)
        assertEquals("Lantern Keeper", book.title)
        assertEquals("Ada Quill", book.author)
        assertOutOfMusic(library, m4b)

        val chapters = chaptersOf(listOf(mp4Chapter(0, "Chapter One"), mp4Chapter(HOUR, "Chapter Two"), mp4Chapter(2 * HOUR, "Chapter Three")), m4b.durationMs)
        assertEquals(listOf(Chapter(0, HOUR, "Chapter One"), Chapter(HOUR, 2 * HOUR, "Chapter Two"), Chapter(2 * HOUR, 3 * HOUR, "Chapter Three")), chapters)
        var state = BookState().withChapters(book, chaptersStamp(m4b), chapters)
        assertEquals(BookStatus.NotStarted(3 * HOUR), bookStatus(book, state))

        state = state.withPlace(book, BookPlace(0, 90 * MINUTE), now = 1)
        assertEquals(BookStatus.Listening(chapter = 2, chapters = 3, leftMs = 90 * MINUTE, progress = 0.5f), bookStatus(book, state))
        state = state.withSpeed(book, Speed(1.5f))
        assertEquals(60 * MINUTE, (bookStatus(book, state) as BookStatus.Listening).leftMs)

        // It resumes in chapter Two, the bar marking where Two and Three start.
        val now = bookNow(book, 0, state.placeOf(book).positionMs, state)
        assertEquals(1, now.chapter)
        assertEquals(listOf(1f / 3, 2f / 3), chapterTicks(now, m4b.durationMs))

        // The file changed on the phone (new tags, say): the place holds by its path, the chapters are read again.
        val changed = m4b.copy(modifiedAt = 99)
        val again = library(changed).bookOf(m4b.path)!!
        assertEquals(BookPlace(0, 90 * MINUTE), state.placeOf(again))
        assertNotEquals(chaptersStamp(changed), state.entryOf(again)?.chaptersStamp)
    }

    @Test
    fun `an m4a with chapters is a book only by a sign of its own or the user's mark, and Not a book keeps its place`() {
        val plain = song("Music/Rips/Quiet Orchard.m4a", title = "Quiet Orchard", durationMs = 50 * MINUTE)
        assertNull(library(plain).bookOf(plain.path))
        listOf(
            song("Audiobooks/Quiet Orchard.m4a", durationMs = 50 * MINUTE),
            song("Music/Rips/Quiet Orchard.m4a", genre = "Audiobook", durationMs = 50 * MINUTE),
            song("Music/Rips/Quiet Orchard.m4a", isAudiobook = true, durationMs = 50 * MINUTE),
        ).forEach { signed -> assertEquals(signed.path, BookKind.SINGLE, library(signed).bookOf(signed.path)?.kind) }

        val marks = BookState().markSongs(listOf(plain.path), on = true)
        val marked = library(plain, marks = marks.marks)
        val book = marked.bookOf(plain.path)!!
        assertOutOfMusic(marked, plain)
        // An m4a's chapters come as an m4b's do; one without a title reads "Chapter 2" on screen.
        val chapters = chaptersOf(listOf(mp4Chapter(0, "Chapter One"), mp4Chapter(20 * MINUTE, null)), plain.durationMs)
        assertEquals(listOf(Chapter(0, 20 * MINUTE, "Chapter One"), Chapter(20 * MINUTE, 50 * MINUTE, null)), chapters)
        val state = marks.withChapters(book, chaptersStamp(plain), chapters).withPlace(book, BookPlace(0, 30 * MINUTE), now = 1)
        assertEquals(BookStatus.Listening(chapter = 2, chapters = 2, leftMs = 20 * MINUTE, progress = 0.6f), bookStatus(book, state))

        // "Not a book": music again; its place stays kept for a mark later.
        val unmarked = state.notABook(book)
        val back = library(plain, marks = unmarked.marks)
        assertNull(back.bookOf(plain.path))
        assertTrue(back.shows(plain))
        assertEquals(BookPlace(0, 30 * MINUTE), unmarked.placeOf(book))
    }

    // One mp3 per chapter

    private val parts = (1..12).map { n ->
        song("Audiobooks/Tin Roof Tales/%02d Chapter %d.mp3".format(n, n), title = "Chapter $n", artist = "Ada Quill", album = "Tin Roof Tales", track = n, durationMs = 20 * MINUTE)
    }

    @Test
    fun `an mp3 per chapter in one folder is one book in track order, a chapter per file`() {
        val library = library(*parts.shuffled(Random(4)).toTypedArray())
        val book = library.bookOf(parts.first().path)!!
        assertEquals(BookKind.FOLDER, book.kind)
        assertEquals(parts.map { it.path }, book.parts.map { it.path })
        assertEquals("Tin Roof Tales", book.title)
        assertEquals("Ada Quill", book.author)
        assertEquals(4 * HOUR, book.durationMs)
        parts.forEach { assertOutOfMusic(library, it) }
        assertTrue(library.artists.none { it.name == "Ada Quill" })

        val chapters = bookChapters(book, BookState())
        assertEquals((1..12).map { "Chapter $it" }, chapters.map { it.title })
        assertEquals(listOf(0L, 20 * MINUTE, 40 * MINUTE), chapters.take(3).map { it.startMs })
        assertEquals(4 * HOUR, chapters.last().endMs)

        // Part 7, 5 minutes in: chapter 7 of 12, 1 h 55 m left of 4 h.
        val state = BookState().withPlace(book, BookPlace(6, 5 * MINUTE), now = 1)
        val status = bookStatus(book, state) as BookStatus.Listening
        assertEquals(7, status.chapter)
        assertEquals(12, status.chapters)
        assertEquals(115 * MINUTE, status.leftMs)
        assertEquals(1 to 55, hoursAndMinutes(status.leftMs))
        assertEquals(125f / 240, status.progress, 0.0001f)
        val now = bookNow(book, 6, 5 * MINUTE, state)
        assertEquals(6, now.chapter)
        // No ticks on a folder book's bar: each file is its own bar. Next goes to part 8, Previous to the start of part 7.
        assertEquals(emptyList<Float>(), chapterTicks(now, 20 * MINUTE))
        assertEquals(BookPlace(7, 0), chapterPlace(now, nextChapter(now)!!))
        assertEquals(BookPlace(6, 0), chapterPlace(now, previousChapter(now)!!))
    }

    @Test
    fun `a folder book resumes in the same file when a part is added before it, and by number when its file is renamed`() {
        val book = library(*parts.toTypedArray()).bookOf(parts.first().path)!!
        val state = BookState().withPlace(book, BookPlace(6, 5 * MINUTE), now = 1)

        // A new first file without a track number: the order goes by names, part 7 is now the 8th file.
        val opening = song("Audiobooks/Tin Roof Tales/00 Opening.mp3", album = "Tin Roof Tales", durationMs = 2 * MINUTE)
        val grown = library(*(parts + opening).toTypedArray()).bookOf(opening.path)!!
        assertEquals(book.id, grown.id)
        assertEquals(opening.path, grown.parts.first().path)
        assertEquals(BookPlace(7, 5 * MINUTE), state.placeOf(grown))
        assertEquals(parts[6].path, grown.parts[state.placeOf(grown).partIndex].path)

        // Part 7 renamed: found by its number.
        val renamed = parts.mapIndexed { i, part -> if (i == 6) song("Audiobooks/Tin Roof Tales/07 The Long Night.mp3", title = "Chapter 7", album = "Tin Roof Tales", track = 7, durationMs = 20 * MINUTE) else part }
        val moved = library(*renamed.toTypedArray()).bookOf(parts.first().path)!!
        assertEquals(BookPlace(6, 5 * MINUTE), state.placeOf(moved))
    }

    @Test
    fun `parts on two discs play disc by disc, and parts without track numbers by their names`() {
        val discs = listOf(2 to 1, 1 to 2, 1 to 1, 2 to 2).map { (disc, track) ->
            song("Audiobooks/Glass Ferry/Disc $disc Track $track.mp3", album = "Glass Ferry", disc = disc, track = track, durationMs = 10 * MINUTE)
        }
        assertEquals(listOf("1-1", "1-2", "2-1", "2-2"), library(*discs.toTypedArray()).bookOf(discs.first().path)!!.parts.map { "${it.disc}-${it.track}" })

        val untagged = listOf("Part 10", "Part 2", "part 1").map { song("Audiobooks/Paper Harbour/$it.mp3", album = "Paper Harbour", durationMs = 10 * MINUTE) }
        assertEquals(listOf("part 1.mp3", "Part 2.mp3", "Part 10.mp3"), library(*untagged.toTypedArray()).bookOf(untagged.first().path)!!.parts.map { it.fileName })
    }

    // One long mp3 without chapters

    @Test
    fun `one long mp3 without chapters is a book in an Audiobooks folder, with no made-up chapters`() {
        val long = song("Audiobooks/Paper Harbour.mp3", title = "Paper Harbour", durationMs = 5 * HOUR)
        val library = library(long)
        val book = library.bookOf(long.path)!!
        assertEquals(BookKind.SINGLE, book.kind)
        assertOutOfMusic(library, long)

        // An ID3 tag without CHAP frames, as Media3 reads it: no chapters, and none made up every ten minutes.
        val chapters = chaptersOf(listOf(TextInformationFrame("TIT2", null, listOf("Paper Harbour"))), long.durationMs)
        assertEquals(emptyList<Chapter>(), chapters)
        val state = BookState().withChapters(book, chaptersStamp(long), chapters).withPlace(book, BookPlace(0, 2 * HOUR), now = 1)
        assertEquals(BookStatus.Listening(chapter = null, chapters = 0, leftMs = 3 * HOUR, progress = 0.4f), bookStatus(book, state))
        assertEquals(BookPlace(0, 2 * HOUR), state.placeOf(book))

        val now = bookNow(book, 0, 2 * HOUR, state)
        assertEquals(-1, now.chapter)
        assertEquals(emptyList<Float>(), chapterTicks(now, long.durationMs))
        assertNull(previousChapter(now))
        assertNull(nextChapter(now))
        assertNull(sleepToChapterEnd(chapters, 2 * HOUR))

        // Played to its end: finished, at its start again.
        val done = state.finished(book, now = 2)
        assertEquals(BookStatus.Finished, bookStatus(book, done))
        assertEquals(BookPlace(), done.placeOf(book))
    }

    // Files that look like a book only by their length or their folder

    @Test
    fun `a file long like a book stays music, the song menu suggesting Mark as book`() {
        val set = song("Music/Live/Tin Roof Set.mp3", title = "Tin Roof Set", album = "Tin Roof Live", durationMs = 3 * HOUR)
        val library = library(set)
        assertNull(library.bookOf(set.path))
        assertTrue(library.shows(set))
        assertTrue(looksLikeBook(set, library))

        // Long mixes of one album side by side: an album, not a book.
        val mixes = (1..3).map { song("Music/Mixes/Mix $it.mp3", album = "Night Mixes", durationMs = 2 * HOUR) }
        val withMixes = library(*mixes.toTypedArray())
        assertTrue(withMixes.books.isEmpty())
        assertTrue(mixes.none { looksLikeBook(it, withMixes) })

        // Under two hours, or a book already: no suggestion.
        val shorter = song("Music/Live/Short Set.mp3", durationMs = LOOKS_LONG_MS - 1)
        assertFalse(looksLikeBook(shorter, library(shorter)))
        val tale = song("Audiobooks/Lantern Keeper.mp3", durationMs = 3 * HOUR)
        assertFalse(looksLikeBook(tale, library(tale)))

        // A podcast episode suggests it whatever its length, and is music until marked.
        val episode = song("Music/Podcasts/Glass Talk/Episode 5.mp3", durationMs = 40 * MINUTE, isPodcast = true)
        val withEpisode = library(episode)
        assertNull(withEpisode.bookOf(episode.path))
        assertTrue(looksLikeBook(episode, withEpisode))
        assertFalse(looksLikeBook(music, withEpisode))

        // Marked: a book of its own, with nothing more to suggest.
        val marked = library(set, marks = BookMarks(songs = setOf(set.path)))
        assertEquals(BookKind.SINGLE, marked.bookOf(set.path)?.kind)
        assertFalse(looksLikeBook(set, marked))
    }

    @Test
    fun `an m4a with chapters is suggested as a book whatever its length, and never made one by them`() {
        val mix = song("Music/Mixes/Night Ferry Mix.m4a", album = "Night Ferry", durationMs = 50 * MINUTE)
        val library = library(mix)
        assertNull(library.bookOf(mix.path))
        assertFalse(looksLikeBook(mix, library))
        assertTrue(looksLikeBook(mix, library, chaptered = true))
        val shelved = song("Audiobooks/Night Ferry.m4a", durationMs = 50 * MINUTE)
        assertFalse(looksLikeBook(shelved, library(shelved), chaptered = true))
        // The song menu reads chapters for an .m4a that is no book only.
        assertTrue(readsChaptersFor(mix, library))
        assertFalse(readsChaptersFor(music, library))
        assertFalse(readsChaptersFor(shelved, library(shelved)))
    }

    @Test
    fun `a book's files are known as books for the lyrics, by their path alone while the library loads`() {
        val tale = song("Audiobooks/Lantern Keeper/01.mp3", album = "Lantern Keeper", durationMs = 30 * MINUTE)
        val read = library(m4b, tale)
        assertTrue(isBookPart(m4b.path, read))
        assertTrue(isBookPart(tale.path, read))
        assertFalse(isBookPart(music.path, read))
        // Not read yet: an .m4b or a file in an Audiobooks folder counts as a book, music does not.
        assertTrue(isBookPart(m4b.path, null))
        assertTrue(isBookPart(tale.path, null))
        assertFalse(isBookPart(music.path, null))
        // Read, the user's word wins: an Audiobooks file marked Not a book is music.
        val kept = library(tale, marks = BookMarks(notSongs = setOf(tale.path)))
        assertFalse(isBookPart(tale.path, kept))
    }

    @Test
    fun `a folder makes a book by its name or by the user's mark, its files grouped by album`() {
        // An Audiobooks folder at any depth, in any case: a book with no other sign.
        val deep = (1..3).map { song("Music/Spoken/audiobooks/Quiet Orchard/$it.mp3", album = "Quiet Orchard", durationMs = 30 * MINUTE) }
        assertEquals(BookKind.FOLDER, library(*deep.toTypedArray()).bookOf(deep.first().path)?.kind)
        // "Not a book" on that folder wins over its name.
        val kept = library(*deep.toTypedArray(), marks = BookMarks(notFolders = setOf("Music/Spoken/audiobooks/Quiet Orchard")))
        assertTrue(kept.books.isEmpty())
        assertTrue(deep.all(kept::shows))
        // A folder named nearly so stays music.
        val near = song("Music/Audiobook Rips/Glass Ferry.mp3", durationMs = 40 * MINUTE)
        assertNull(library(near).bookOf(near.path))

        // Plain parts are an album until their folder is marked; then one book per album there.
        val ferry = (1..3).map { song("Music/Rips/Glass Ferry/Part $it.mp3", album = "Glass Ferry", track = it, durationMs = 40 * MINUTE) }
        val bonus = song("Music/Rips/Glass Ferry/Bonus.mp3", album = "Ferry Extras", durationMs = 10 * MINUTE)
        val unmarked = library(*(ferry + bonus).toTypedArray())
        assertTrue(unmarked.books.isEmpty())
        assertTrue(unmarked.albums.any { it.title == "Glass Ferry" })
        val folder = BookState().markFolder("Music/Rips/Glass Ferry", (ferry + bonus).map { it.path }, on = true)
        val marked = library(*(ferry + bonus).toTypedArray(), marks = folder.marks)
        assertEquals(2, marked.books.size)
        assertEquals(ferry.map { it.path }, marked.bookOf(ferry.first().path)!!.parts.map { it.path })
        assertEquals(BookKind.SINGLE, marked.bookOf(bonus.path)?.kind)
        assertTrue((ferry + bonus).none(marked::shows))
        assertTrue(marked.shows(music))
    }

    private companion object {
        const val MINUTE: Long = 60_000
        const val HOUR: Long = 60 * MINUTE
    }
}
