package app.lyrify.dupes

import android.database.Cursor
import android.provider.MediaStore
import app.lyrify.data.SharedWrites
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.TimeUnit

/**
 * Tools > Trash's songs from MediaStore's trashed audio ([trashedSongs]): only what KJB's library would list were it not
 * trashed, whichever app moved it there. Made-up names only.
 */
class TrashListTest {
    /** A MediaStore cursor over [rows], each a row's values by column; a column a row leaves out is null. */
    private fun cursor(rows: List<Map<String, Any?>>): Cursor {
        val columns = mutableListOf<String>()
        var at = -1
        return Proxy.newProxyInstance(Cursor::class.java.classLoader, arrayOf(Cursor::class.java)) { _, method, args ->
            val value = { rows[at][columns[args[0] as Int]] }
            when (method.name) {
                "getColumnIndexOrThrow" -> columns.indexOf(args[0] as String).takeIf { it >= 0 } ?: columns.size.also { columns += args[0] as String }
                "moveToNext" -> ++at < rows.size
                "getString" -> value() as String?
                "getInt" -> (value() as Number?)?.toInt() ?: 0
                "getLong" -> (value() as Number?)?.toLong() ?: 0L
                "isNull" -> value() == null
                "close" -> null
                else -> error("not in the fake: ${method.name}")
            }
        } as Cursor
    }

    /** A trashed row as MediaProvider keeps it: the file renamed `.trashed-<expiry>-<name>` in [folder], its name as it was. */
    private fun row(
        id: Long,
        folder: String,
        name: String,
        title: String? = name.substringBeforeLast('.'),
        music: Boolean = true,
        audiobook: Boolean = false,
        expires: Long? = EXPIRES_S,
        artist: String? = "Kit Calloway",
    ): Map<String, Any?> = mapOf(
        MediaStore.Audio.Media._ID to id,
        "_data" to "/storage/emulated/0/$folder.trashed-$EXPIRES_S-$name",
        MediaStore.Audio.Media.RELATIVE_PATH to folder,
        MediaStore.Audio.Media.DISPLAY_NAME to name,
        MediaStore.Audio.Media.TITLE to title,
        MediaStore.Audio.Media.ARTIST to artist,
        MediaStore.Audio.Media.ALBUM to "Harbour Glass",
        MediaStore.Audio.Media.DURATION to 201_000L,
        MediaStore.Audio.Media.SIZE to 4_000_000L + id,
        MediaStore.Audio.Media.MIME_TYPE to "audio/mpeg",
        MediaStore.Audio.Media.DATE_EXPIRES to expires,
        MediaStore.Audio.Media.IS_MUSIC to if (music) 1 else 0,
        MediaStore.Audio.Media.IS_AUDIOBOOK to if (audiobook) 1 else 0,
    )

    @Test
    fun `the songs the library would list are listed, never recordings, ringtones or other sounds`() {
        val rows = listOf(
            row(1, "Music/Harbour Glass/", "Paper Lantern.mp3"),
            // In a Music folder, as the library lists it, though MediaStore did not mark it as music.
            row(2, "Music/Rips/", "Tide Bell.mp3", music = false),
            row(3, "Audiobooks/Salt Road/", "Part 1.m4a", music = false, audiobook = true),
            row(4, "Recordings/", "Voice 001.m4a", music = false),
            row(5, "Ringtones/", "Tin Roof (KJB).m4a", music = false),
            row(6, "Notifications/", "Chime.ogg", music = false),
            row(7, "Download/", "Podcast 12.mp3", music = false),
        )
        assertEquals(listOf(1L, 2L, 3L), trashedSongs(cursor(rows), SharedWrites.ANYWHERE).map { it.file.id })
    }

    @Test
    fun `each song is named as the library named it, and goes for good at its expiry`() {
        val rows = listOf(
            row(1, "Music/Harbour Glass/", "Paper Lantern.mp3"),
            // No title: the file's name as it was, without the trash's prefix.
            row(2, "Music/Rips/", "Tide Bell.mp3", title = " ", artist = "<unknown>"),
            // No expiry: no date to show, so it is left out.
            row(3, "Music/Rips/", "Salt Road.mp3", expires = null),
        )
        val songs = trashedSongs(cursor(rows), SharedWrites.ANYWHERE)
        val lantern = songs.first()
        assertEquals("Paper Lantern", lantern.file.title)
        assertEquals("Kit Calloway", lantern.file.artist)
        assertEquals("Paper Lantern.mp3", lantern.file.displayName)
        assertEquals("/storage/emulated/0/Music/Harbour Glass/.trashed-$EXPIRES_S-Paper Lantern.mp3", lantern.file.path)
        assertEquals(4_000_001L, lantern.file.size)
        assertEquals(TimeUnit.SECONDS.toMillis(EXPIRES_S), lantern.expiresAt)
        assertEquals(TimeUnit.SECONDS.toMillis(EXPIRES_S) - 30 * DAY, lantern.trashedAt)
        assertEquals("Tide Bell", songs[1].file.title)
        assertNull(songs[1].file.artist)
        assertEquals(2, songs.size)
    }

    @Test
    fun `a debug build lists only the songs in its test folder`() {
        val rows = listOf(row(1, "Music/KJB test/", "Paper Lantern.mp3"), row(2, "Music/Harbour Glass/", "Tide Bell.mp3"))
        assertEquals(listOf(1L), trashedSongs(cursor(rows), SharedWrites(testFolderOnly = true)).map { it.file.id })
    }

    private companion object {
        const val EXPIRES_S: Long = 1_765_000_000
        const val DAY: Long = 24 * 60 * 60_000L
    }
}
