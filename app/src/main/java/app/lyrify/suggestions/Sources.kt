package app.lyrify.suggestions

import app.lyrify.library.SongRef

// What Suggestions reads besides the play log and Favourites: small read interfaces, each owned by another part of KJB
// (the interaction log, similar artists, Huddle's friends). Each has a "No..." object that gives nothing: a source that is
// missing, off or failing simply adds nothing, and suggestions never wait on it.

/** What the interaction log tells suggestions: what the user looked at. Local only; search words never come here. */
interface InteractionSource {
    /** The interactions from [from] (wall-clock millis, inclusive), the oldest first; none when the log is missing or unreadable. */
    suspend fun since(from: Long): List<Interaction>
}

/**
 * One interaction: at [at] (wall-clock millis), of [kind], about a song ([song]), an album (its key, as
 * [app.lyrify.library.albumKey] gives it) or an artist (the name as the library shows it).
 */
data class Interaction(val at: Long, val kind: InteractionKind, val song: SongRef? = null, val album: String? = null, val artist: String? = null)

enum class InteractionKind {
    /** A song tapped in Search's results. */
    SEARCH_PICK,

    /** A song's page or Song info opened. */
    SONG_OPENED,
    ALBUM_OPENED,
    ARTIST_OPENED,

    /** A song's lyrics opened. */
    LYRICS_OPENED,
}

/** No interaction log: nothing was looked at. */
object NoInteractions : InteractionSource {
    override suspend fun since(from: Long): List<Interaction> = emptyList()
}

/** Similar artists from an on-phone cache; never the network, never waits for a lookup. */
interface SimilarArtistSource {
    /** For each of [artists] (names as the library shows them), its similar artists, the closest first; one with nothing cached is left out. */
    suspend fun similar(artists: Collection<String>): Map<String, List<SimilarArtist>>
}

/** An artist like another: its [name] as the source gives it, and how close, 0 to 1 (1 the closest). */
data class SimilarArtist(val name: String, val score: Float)

/** No similar artists known. */
object NoSimilarArtists : SimilarArtistSource {
    override suspend fun similar(artists: Collection<String>): Map<String, List<SimilarArtist>> = emptyMap()
}

/** Friends' plays as Huddle received them (only from friends who share with this phone), kept on the phone. */
interface FriendsSource {
    /** Friends' plays from [from] (wall-clock millis, inclusive), the oldest first; none without Huddle or friends. */
    suspend fun since(from: Long): List<FriendPlay>
}

/**
 * One play by a friend ([friend]: a stable local id, never shown): the song's details as sent (never audio), when, and
 * whether the song was a favourite on the friend's phone then ([favourite]; false when not sent).
 */
data class FriendPlay(val friend: String, val title: String, val artist: String?, val album: String?, val durationMs: Long?, val at: Long, val favourite: Boolean = false)

/** No friends share their plays. */
object NoFriends : FriendsSource {
    override suspend fun since(from: Long): List<FriendPlay> = emptyList()
}
