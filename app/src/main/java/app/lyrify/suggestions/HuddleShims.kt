package app.lyrify.suggestions

// STAND-IN, to DELETE when lane sg1 (v4/sg1-suggestions) is merged: a copy of the friends source in sg1's
// suggestions/Sources.kt (handoff/sg1-interfaces.md, section 3), so Huddle's own (huddle/Huddle.kt, HuddleFriends) builds
// before the merge. Left in after it, the build fails on the two declarations twice, which is how it is found.

/** Friends' plays as Huddle received them (only friends who share with this phone), kept on the phone. */
interface FriendsSource {
    /** Friends' plays from [from] (wall-clock millis, inclusive), the oldest first; none without Huddle or friends. */
    suspend fun since(from: Long): List<FriendPlay>
}

/**
 * One play by a friend ([friend]: a stable local id, never shown): the song's details as sent (never audio), when, and
 * whether the song was a favourite on the friend's phone then ([favourite]; false when not sent).
 */
data class FriendPlay(val friend: String, val title: String, val artist: String?, val album: String?, val durationMs: Long?, val at: Long, val favourite: Boolean = false)
