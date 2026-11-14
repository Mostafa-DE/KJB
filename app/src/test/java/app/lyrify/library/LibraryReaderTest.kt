package app.lyrify.library

import android.database.Cursor
import android.provider.MediaStore
import app.lyrify.data.SharedWrites
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.lang.reflect.Proxy
import java.nio.file.Files

/** Reading the phone's songs from MediaStore's rows, made up here. Made-up names only. */
class LibraryReaderTest {
    @get:Rule
    val folder = TemporaryFolder()

    /** A MediaStore cursor over [rows], each a row's values by column; a column a row leaves out is null. */
    private fun cursor(rows: List<Map<String, Any?>>): Cursor {
        val columns = mutableListOf<String>()
        var at = -1
        return Proxy.newProxyInstance(Cursor::class.java.classLoader, arrayOf(Cursor::class.java)) { _, method, args ->
            val value = { rows[at][columns[args[0] as Int]] }
            when (method.name) {
                "getColumnIndexOrThrow" -> columns.indexOf(args[0] as String).takeIf { it >= 0 } ?: columns.size.also { columns += args[0] as String }
                "moveToNext" -> ++at < rows.size
                "getString" -> value()?.toString()
                "getInt" -> (value() as Number?)?.toInt() ?: 0
                "getLong" -> (value() as Number?)?.toLong() ?: 0L
                "isNull" -> value() == null
                "close" -> null
                else -> error("not in the fake: ${method.name}")
            }
        } as Cursor
    }

    private fun row(relativePath: String, vararg extra: Pair<String, Any?>, id: Long = 1): Map<String, Any?> = mapOf(
        MediaStore.Audio.Media._ID to id,
        "_data" to VOLUME + relativePath,
        MediaStore.Audio.Media.RELATIVE_PATH to relativePath.substringBeforeLast('/') + "/",
        MediaStore.Audio.Media.TITLE to "Paper Lantern",
        MediaStore.Audio.Media.DURATION to 201_000L,
        MediaStore.Audio.Media.SIZE to 4_000_000L,
        MediaStore.Audio.Media.IS_MUSIC to 1,
    ) + extra

    private fun reader(rows: List<Map<String, Any?>>, writes: SharedWrites = SharedWrites.ANYWHERE) =
        LibraryReader(query = { cursor(rows) }, readStamp = { "v1:1" }, changes = emptyFlow(), writes = writes)

    private fun songs(read: SongsRead): List<Song> = (read as SongsRead.Songs).songs

    /** MediaStore.UNKNOWN_STRING: what MediaStore writes for a song without an artist. */
    private val UNKNOWN = "<unknown>"

    @Test
    fun `every column MediaStore gives is read into the song`() {
        val read = reader(
            listOf(
                row(
                    "Music/Harbour Glass/CD2/03 Paper Lantern.flac",
                    MediaStore.Audio.Media.ARTIST to "Kit Calloway",
                    MediaStore.Audio.Media.ALBUM to "Harbour Glass",
                    MediaStore.MediaColumns.ALBUM_ARTIST to "Various Artists",
                    MediaStore.Audio.Media.ALBUM_ID to 7L,
                    MediaStore.MediaColumns.CD_TRACK_NUMBER to "3/12",
                    MediaStore.MediaColumns.DISC_NUMBER to "2/2",
                    MediaStore.MediaColumns.YEAR to 2024,
                    MediaStore.MediaColumns.GENRE to "Folk",
                    MediaStore.Audio.Media.DATE_ADDED to 1_800_000_000L,
                    MediaStore.Audio.Media.DATE_MODIFIED to 1_800_000_100L,
                    MediaStore.Audio.Media.MIME_TYPE to "audio/flac",
                    id = 41,
                ),
            ),
        ).read()

        assertEquals(
            listOf(
                Song(
                    ref = SongRef(VOLUME + "Music/Harbour Glass/CD2/03 Paper Lantern.flac", 41),
                    title = "Paper Lantern",
                    artist = "Kit Calloway",
                    album = "Harbour Glass",
                    albumArtist = "Various Artists",
                    durationMs = 201_000,
                    track = 3,
                    disc = 2,
                    year = 2024,
                    genre = "Folk",
                    folder = "Music/Harbour Glass/CD2/",
                    addedAt = 1_800_000_000_000L,
                    modifiedAt = 1_800_000_100_000L,
                    sizeBytes = 4_000_000,
                    mimeType = "audio/flac",
                    albumId = 7,
                ),
            ),
            songs(read),
        )
    }

    @Test
    fun `MediaStore's unknown and blank values read as missing tags, and a missing title as the file's name`() {
        val song = songs(
            reader(
                listOf(
                    row(
                        "Music/Loose/04 Tide Bell.mp3",
                        MediaStore.Audio.Media.TITLE to "  ",
                        MediaStore.Audio.Media.ARTIST to UNKNOWN,
                        MediaStore.Audio.Media.ALBUM to "",
                        MediaStore.MediaColumns.ALBUM_ARTIST to UNKNOWN,
                        MediaStore.MediaColumns.GENRE to " ",
                        MediaStore.MediaColumns.CD_TRACK_NUMBER to "0",
                        MediaStore.MediaColumns.DISC_NUMBER to "A",
                        MediaStore.MediaColumns.YEAR to 0,
                        MediaStore.Audio.Media.DATE_ADDED to 0L,
                    ),
                ),
            ).read(),
        ).single()

        assertEquals("04 Tide Bell", song.title)
        assertNull(song.artist)
        assertNull(song.album)
        assertNull(song.albumArtist)
        assertNull(song.genre)
        assertNull(song.track)
        assertNull(song.disc)
        assertNull(song.year)
        assertNull(song.addedAt)
        assertNull(song.modifiedAt)
        assertNull(song.albumId)
    }

    @Test
    fun `tags are read trimmed, and track numbers as MediaStore writes them`() {
        val song = songs(
            reader(
                listOf(
                    row(
                        "Music/A/x.mp3",
                        MediaStore.Audio.Media.TITLE to " Paper Lantern ",
                        MediaStore.Audio.Media.ARTIST to " Kit Calloway",
                        MediaStore.MediaColumns.CD_TRACK_NUMBER to "07",
                        MediaStore.MediaColumns.DISC_NUMBER to "1",
                    ),
                ),
            ).read(),
        ).single()

        assertEquals("Paper Lantern", song.title)
        assertEquals("Kit Calloway", song.artist)
        assertEquals(7, song.track)
        assertEquals(1, song.disc)
    }

    @Test
    fun `only music is listed, as Duplicates lists it, and anything in a Music folder`() {
        val rows = listOf(
            row("Music/A/song.mp3", id = 1),
            row("Recordings/call.m4a", MediaStore.Audio.Media.IS_MUSIC to 0, id = 2),
            row("Music/Voice/memo.m4a", MediaStore.Audio.Media.IS_MUSIC to 0, id = 3),
        )

        assertEquals(listOf(1L, 3L), songs(reader(rows).read()).map { it.ref.mediaStoreId })
    }

    @Test
    fun `audiobooks are listed, marked by MediaStore or in an Audiobooks folder, and podcasts stay out`() {
        val rows = listOf(
            row("Audiobooks/Lantern Keeper/1.mp3", MediaStore.Audio.Media.IS_MUSIC to 0, MediaStore.Audio.AudioColumns.IS_AUDIOBOOK to 1, id = 1),
            row("Books/Quiet Orchard.m4b", MediaStore.Audio.Media.IS_MUSIC to 0, MediaStore.Audio.AudioColumns.IS_AUDIOBOOK to 1, id = 2),
            row("Download/audiobooks/Tin Roof.mp3", MediaStore.Audio.Media.IS_MUSIC to 0, id = 3),
            row("Podcasts/Glass Talk/Episode 4.mp3", MediaStore.Audio.Media.IS_MUSIC to 0, MediaStore.Audio.AudioColumns.IS_PODCAST to 1, id = 4),
            row("Music/A/song.mp3", id = 5),
        )

        val read = songs(reader(rows).read())
        assertEquals(listOf(1L, 2L, 3L, 5L), read.map { it.ref.mediaStoreId })
        assertEquals(listOf(true, true, false, false), read.map { it.isAudiobook })
    }

    @Test
    fun `a debug build lists only the songs in its test folder`() {
        val root = folder.root.toPath()
        val debug = SharedWrites(testFolderOnly = true, root = root)
        val inTest = "Music/KJB test/a.mp3"
        Files.createDirectories(root.resolve(VOLUME.trimStart('/') + "Music/KJB test"))
        val rows = listOf(row(inTest, id = 1), row("Music/A/b.mp3", id = 2))

        assertEquals(listOf(1L), songs(reader(rows, debug).read()).map { it.ref.mediaStoreId })
        assertEquals(listOf(1L, 2L), songs(reader(rows).read()).map { it.ref.mediaStoreId })
    }

    @Test
    fun `MediaStore refusing or giving no cursor is unavailable, never an empty library`() {
        val refused = LibraryReader(query = { throw SecurityException("no grant") }, readStamp = { null }, changes = emptyFlow(), writes = SharedWrites.ANYWHERE)
        val none = LibraryReader(query = { null }, readStamp = { null }, changes = emptyFlow(), writes = SharedWrites.ANYWHERE)

        assertTrue(refused.read() is SongsRead.Unavailable)
        assertTrue(none.read() is SongsRead.Unavailable)
        assertEquals("refused: SecurityException", (refused.read() as SongsRead.Unavailable).cause)
    }
}
