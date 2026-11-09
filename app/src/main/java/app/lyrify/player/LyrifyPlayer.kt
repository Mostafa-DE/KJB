package app.lyrify.player

/** What the media session shows: the songs the player holds (by uid), the current one and its position, and the toggles. */
data class SessionView(val uids: List<Long>, val index: Int, val positionMs: Long, val playing: Boolean, val shuffled: Boolean, val repeat: Repeat)

/**
 * The session's view of KJB's player: the songs and place come from the player ([port]), Shuffle from the queue playing
 * (KJB shuffles the queue itself, never the player), Repeat from the app's setting. Nothing loaded never plays.
 */
fun sessionView(port: PlayerPort, playing: Boolean, book: QueueBook, repeat: Repeat): SessionView =
    if (port.loaded.isEmpty()) {
        SessionView(emptyList(), -1, 0, playing = false, shuffled = false, repeat = repeat)
    } else {
        SessionView(port.loaded, port.index, port.positionMs, playing, book.current?.shuffled == true, repeat)
    }
