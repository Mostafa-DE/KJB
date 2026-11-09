package app.lyrify

import app.lyrify.library.CollectionKind
import app.lyrify.library.FavouriteCollection
import app.lyrify.library.NoFavourites
import app.lyrify.library.Song
import app.lyrify.library.SongRef
import app.lyrify.player.NoPlayback
import app.lyrify.player.QueueSource
import app.lyrify.player.SourceKind
import app.lyrify.stats.NoPlayEvents
import app.lyrify.stats.NoPlays
import app.lyrify.stats.PlayEvent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The 3.0 contracts that lists, the player and the play counter code against, and what they do until their milestone
 * replaces the stub: nothing, and never anything that looks done. Made-up names only.
 */
class ContractsTest {
    private val songs = listOf(
        SongRef("/storage/emulated/0/Music/Lyrify test/Paper Lantern.mp3", mediaStoreId = 12),
        SongRef("/storage/emulated/0/Music/Lyrify test/Tin Roof.flac"),
    )
    private val album = QueueSource(SourceKind.ALBUM, "Harbour Glass|Kit Calloway", "Harbour Glass")

    @Test
    fun `a song's file name is the last part of its path`() {
        val song = Song(
            songs[0], "Paper Lantern", "Kit Calloway", "Harbour Glass", "Kit Calloway", 201_000, 3, 1, 2021, "Folk",
            "Music/Lyrify test/", addedAt = 5, modifiedAt = 6, sizeBytes = 4_000_000, mimeType = "audio/mpeg",
        )
        assertEquals("Paper Lantern.mp3", song.fileName)
        assertEquals(songs[0].path, song.path)
    }

    @Test
    fun `until the player lands, playing changes nothing and offers no Undo`() {
        NoPlayback.play(songs, 1, album)
        NoPlayback.shuffle(songs, album)
        assertNull(NoPlayback.playNext(songs))
        assertNull(NoPlayback.addToQueue(songs))
    }

    @Test
    fun `until plays are counted, no song has any`() = runBlocking {
        NoPlayEvents.onEvent(PlayEvent.Started(1, songs[0], at = 5, durationMs = 201_000))
        assertEquals(emptyList<Any>(), NoPlays.mostPlayed())
        assertEquals(emptyList<Any>(), NoPlays.recentlyPlayed())
        assertNull(NoPlays.plays(songs[0]))
    }

    @Test
    fun `until Favourites are stored, nothing is a favourite and a heart says it was not saved`() = runBlocking {
        assertEquals(emptyList<SongRef>(), NoFavourites.songs.first())
        assertEquals(emptyList<FavouriteCollection>(), NoFavourites.collections.first())
        assertFalse(NoFavourites.setSongs(songs, favourite = true))
        assertFalse(NoFavourites.setCollection(FavouriteCollection(CollectionKind.ALBUM, album.key), favourite = true))
    }
}
