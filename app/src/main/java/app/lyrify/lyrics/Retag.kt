package app.lyrify.lyrics

import app.lyrify.data.LedgerEntry
import app.lyrify.data.LyricsStatus
import app.lyrify.data.Retry

// After Covers & tags changed a song's tags (covers-tags-spec, "Effects on the rest of KJB"): what its lyrics get.

/** What a retag does to a song's lyrics. The `.lrc` itself is never touched by it. */
enum class Retag {
    /** Nothing: lyrics on disk stay as they are, and every other status keeps its own schedule. */
    NONE,

    /** Not found, or no artist: looked up again under the new names, through the normal pipeline and its rules. */
    LOOK_UP,

    /** KJB's own `.lrc` from LRCLIB, and the title or artist changed: it waits for a check, its lyrics one option. */
    NEEDS_CHECK,
}

/**
 * The rule. Only NOT_FOUND and UNIDENTIFIED are looked up again; REMOVED, instrumental, rejected and another length
 * never are. KJB's own `.lrc` made from LRCLIB's lyrics found by name (synced, or plain lyrics the AI timed) waits for
 * a check when the song's normalised title or artist [namesChanged]. Lyrics the user made or edited, a `.lrc` that was
 * there before, and lyrics the AI heard (they follow the audio, not the names) never do.
 */
fun retagEffect(entry: LedgerEntry?, namesChanged: Boolean): Retag = when {
    entry == null -> Retag.NONE
    entry.status == LyricsStatus.NOT_FOUND || entry.status == LyricsStatus.UNIDENTIFIED -> Retag.LOOK_UP
    namesChanged && !entry.mine && entry.sha256 != null && entry.lrclibId != null &&
        (entry.status == LyricsStatus.SYNCED || entry.status == LyricsStatus.AI_ALIGNED) -> Retag.NEEDS_CHECK
    else -> Retag.NONE
}

/** Whether the title or the artist changed after [TrackNames.normalize] (case, accents, punctuation are no change). */
fun namesChanged(oldTitle: String?, oldArtist: String?, newTitle: String?, newArtist: String?): Boolean =
    TrackNames.normalize(oldTitle.orEmpty()) != TrackNames.normalize(newTitle.orEmpty()) ||
        TrackNames.normalize(oldArtist.orEmpty()) != TrackNames.normalize(newArtist.orEmpty())

/** Waiting for a check after a retag: its `.lrc` is still KJB's own, so its hash, record and source are kept. */
val LedgerEntry.retagCheck: Boolean get() = status == LyricsStatus.NEEDS_CHECK && sha256 != null && candidates.isNotEmpty()

/**
 * This entry, KJB's own `.lrc` from LRCLIB, as a retag check at [now] under [track]'s new names: its record, hash and
 * source kept, its lyrics offered as the one option (the record, under the names they were found for). Its candidates
 * need no comparing; it is looked up again by itself only after [Retry.RECHECK_AFTER_MS], and only if its `.lrc` went.
 */
fun LedgerEntry.retagged(track: LocalTrack, now: Long): LedgerEntry = copy(
    title = track.title,
    artist = track.artist,
    album = track.album,
    status = LyricsStatus.NEEDS_CHECK,
    candidates = listOf(
        Candidate(checkNotNull(lrclibId), title, artist.orEmpty(), album, durationMs / MS_PER_SECOND, synced = status == LyricsStatus.SYNCED),
    ),
    candidatesCompared = true,
    declined = false,
    nextAttemptAt = now + Retry.RECHECK_AFTER_MS,
    updatedAt = now,
)

/** "This one" on a retag check's own lyrics at [now]: KJB's own `.lrc` again, as it was before the check. */
fun LedgerEntry.kept(now: Long): LedgerEntry = copy(status = writtenStatus(), candidates = emptyList(), nextAttemptAt = null, updatedAt = now)

private const val MS_PER_SECOND: Double = 1_000.0
