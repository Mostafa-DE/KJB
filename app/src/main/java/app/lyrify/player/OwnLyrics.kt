package app.lyrify.player

import app.lyrify.books.inBook
import app.lyrify.library.Song
import app.lyrify.library.SongRef
import app.lyrify.lyrics.LocalTrack
import app.lyrify.media.QueueEntry
import app.lyrify.media.SessionSnapshot
import app.lyrify.media.SessionTrack

// Lyrics for KJB's own songs: its queue is handed to a QueueSweep (media/QueueSweep.kt) as if it were another player's
// session, so the current song and the next 5 get lyrics first, then the rest, through the one pipeline (one LRCLIB
// request at a time app-wide), without notification access. The service runs that second sweep (PlaybackService.kt).

/**
 * [queue] as a session snapshot: its songs on the phone (found with [find]), each by its uid, the current one (or the
 * next on the phone) active. Each names its file by path ([SessionTrack.mediaUri]), so [ownFiles] needs no guessing. A
 * book's queue has none: a book never gets lyrics (books/).
 */
fun ownSnapshot(queue: Queue?, find: (SongRef) -> Song?): SessionSnapshot {
    if (queue == null || inBook(queue)) return SessionSnapshot.EMPTY
    val projected = projection(queue, find)
    val start = startOf(queue, projected)
    if (start < 0) return SessionSnapshot.EMPTY
    val entries = projected.map { QueueEntry(it.uid, sessionTrack(it.song)) }
    return SessionSnapshot(entries[start].track, entries[start].queueId, entries)
}

/** The file behind one of KJB's own session tracks, with its tags ([song] finds it by path); none when it is gone. */
fun ownFiles(track: SessionTrack, song: (String) -> Song?): List<LocalTrack> =
    listOfNotNull(track.mediaUri?.let(song)?.let { LocalTrack(it.path, it.title, it.artist, it.album, it.durationMs) })

/** The tags as the file has them: lookups never use "Show file names". */
private fun sessionTrack(song: Song): SessionTrack = SessionTrack(song.title, song.artist, song.album, song.durationMs, mediaUri = song.path)
