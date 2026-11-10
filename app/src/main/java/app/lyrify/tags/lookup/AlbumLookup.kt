package app.lyrify.tags.lookup

import app.lyrify.lyrics.TrackNames
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** How a check of one album ended. */
sealed interface Lookup {
    data class Done(val proposal: Proposal) : Lookup

    /** MusicBrainz stayed busy (503) through every backoff: "MusicBrainz is busy". */
    data object Busy : Lookup

    /** Offline, or an answer was refused or unreadable; [reason] is for the log only. Never Ready on part of the answers. */
    data class Failed(val reason: String) : Lookup
}

/**
 * Checks albums against MusicBrainz: a release search by album title, artist and track count; the details of every
 * result with that track count (no rival is left out) and of the best others, and of any release id already in the
 * files, as one more candidate; then [AlbumMatch], on [compute]. A single is looked up by a recording search instead.
 * Song titles and lengths are matched on the phone, never sent one by one.
 */
class AlbumLookup(
    private val musicBrainz: MusicBrainz,
    private val log: (String) -> Unit = {},
    /** Where matching runs: never the caller's thread. */
    private val compute: CoroutineDispatcher = Dispatchers.Default,
) {
    /** Looks [album] up. [fresh] ("Check again") skips the cache for every request of this album. */
    suspend fun check(album: PhoneAlbum, fresh: Boolean = false): Lookup {
        val result = if (album.isSingle) single(album, fresh) else releases(album, searchTitle(album), searchArtist(album), fresh, searched = false)
        log("tags: album checked: ${summary(result)}")
        return result
    }

    /** "Search myself": a release search with the user's own [title] and [artist]; the answer is always Pick one. */
    suspend fun search(album: PhoneAlbum, title: String, artist: String?, fresh: Boolean = false): Lookup {
        val asked = album.copy(title = title, albumArtist = artist?.takeIf(String::isNotBlank))
        val result = releases(asked, title, asked.albumArtist, fresh, searched = true)
        log("tags: album searched: ${summary(result)}")
        return result
    }

    /** [match] with its release group's first release date, asked for only when its release did not carry it. */
    suspend fun withOriginalYear(match: ReleaseMatch, fresh: Boolean = false): MetaAnswer<ReleaseMatch> {
        val group = match.release.releaseGroup
        if (group == null || group.firstReleaseDate != null) return MetaAnswer.Ok(match)
        return when (val answer = musicBrainz.releaseGroup(group.id, fresh)) {
            is MetaAnswer.Ok -> MetaAnswer.Ok(match.copy(release = match.release.copy(releaseGroup = answer.value)), answer.fromCache)
            // Nothing more to know: no year is written.
            MetaAnswer.NotFound -> MetaAnswer.Ok(match)
            MetaAnswer.Busy -> MetaAnswer.Busy
            is MetaAnswer.Failed -> answer
        }
    }

    /** The front cover of [release]: the 1200 px image, or the 500 px one when there is no larger one. */
    suspend fun cover(release: MbRelease, fresh: Boolean = false): MetaAnswer<ByteArray> {
        // The release says it has none: nothing to ask.
        if (release.hasFront == false) return MetaAnswer.NotFound
        for (size in CoverSize.entries) {
            val answer = musicBrainz.front(release.id, size, fresh)
            if (answer != MetaAnswer.NotFound) return answer
        }
        return MetaAnswer.NotFound
    }

    /** The search, the details of what it found (and of the files' own id), then the match. */
    private suspend fun releases(album: PhoneAlbum, title: String, artist: String?, fresh: Boolean, searched: Boolean): Lookup {
        if (title.isBlank()) return Lookup.Done(AlbumMatch.propose(album, emptyList(), searched))
        val count = album.songs.size.takeUnless { album.isSingle }
        var hits = when (val answer = musicBrainz.releaseHits(title, artist, count, fresh)) {
            is MetaAnswer.Ok -> answer.value
            MetaAnswer.NotFound -> ReleaseHits(emptyList(), 0)
            else -> return stopped(answer)
        }
        // The search is narrowed to the album's track count (MusicBrainz's `tracks` field): when it found more than it
        // lists, a rival may be among those unseen, so nothing can be sure.
        val unseenRivals = count != null && hits.total > hits.releases.size
        // No edition with that many tracks: the editions that have another number can still be picked.
        if (hits.releases.isEmpty() && count != null) {
            hits = when (val answer = musicBrainz.releaseHits(title, artist, null, fresh)) {
                is MetaAnswer.Ok -> answer.value
                MetaAnswer.NotFound -> ReleaseHits(emptyList(), 0)
                else -> return stopped(answer)
            }
        }
        val found = hits.releases
        // Every hit with the album's track count could be a rival, so each is asked for; the others fill up to MAX_DETAILED.
        val (exact, other) = found.sortedByDescending { it.score ?: 0 }.partition { it.trackCount == album.songs.size }
        val best = exact + other.take((MAX_DETAILED - exact.size).coerceAtLeast(0))
        val ids = (best.map { it.id } + listOfNotNull(album.releaseId.takeUnless { searched })).distinct()
        val releases = mutableListOf<MbRelease>()
        for (id in ids) {
            when (val answer = musicBrainz.release(id, fresh)) {
                is MetaAnswer.Ok -> releases += answer.value
                // Gone from MusicBrainz (merged or removed): not a candidate.
                MetaAnswer.NotFound -> Unit
                // A missing rival could make another release look sure: no answer for this album yet.
                else -> return stopped(answer)
            }
        }
        val proposal = withContext(compute) { AlbumMatch.propose(album, releases, searched, unseenRivals) }
        if (proposal !is Proposal.Ready) return Lookup.Done(proposal)
        // Without the release group, the album stays Ready and no year is written (as when MusicBrainz has none).
        val dated = (withOriginalYear(proposal.match, fresh) as? MetaAnswer.Ok)?.value ?: proposal.match
        return Lookup.Done(Proposal.Ready(dated))
    }

    /** A single: a recording search by its title, artist and length; always Pick one. */
    private suspend fun single(album: PhoneAlbum, fresh: Boolean): Lookup {
        val song = album.songs.singleOrNull()
        val title = song?.title?.let(TrackNames::cleanTitle)?.takeIf(String::isNotBlank)
            ?: song?.path?.substringAfterLast('/')?.let(TrackNames::fileTitle)
        if (song == null || title == null) return Lookup.Done(Proposal.PickOne(emptyList(), setOf(Doubt.SINGLE, Doubt.NO_CANDIDATE)))
        val recordings = when (val answer = musicBrainz.searchRecordings(title, TrackNames.cleanArtist(song.artist), song.durationMs.takeIf { it > 0 }, fresh)) {
            is MetaAnswer.Ok -> answer.value
            MetaAnswer.NotFound -> emptyList()
            else -> return stopped(answer)
        }
        return Lookup.Done(withContext(compute) { AlbumMatch.proposeSingle(song, recordings) })
    }

    /** The album title as searched: without what downloads add ("(320kbps)"); a title that is only that stays. */
    private fun searchTitle(album: PhoneAlbum): String {
        val raw = album.title.orEmpty().trim()
        return TrackNames.cleanAlbum(raw, artist = null) ?: raw
    }

    /** The album artist as searched; "Various Artists" for a compilation tagged so, none for one tagged nothing. */
    private fun searchArtist(album: PhoneAlbum): String? =
        album.artist?.let { TrackNames.cleanArtist(it) ?: it }
            ?: Names.usable(album.albumArtist)?.takeIf(Names::isVariousName)?.let { VARIOUS_ARTISTS }

    private fun stopped(answer: MetaAnswer<*>): Lookup = when (answer) {
        MetaAnswer.Busy -> Lookup.Busy
        is MetaAnswer.Failed -> Lookup.Failed(answer.reason)
        else -> Lookup.Failed("no answer")
    }

    /** For the log: the outcome, its doubts and counts, never a name. */
    private fun summary(result: Lookup): String = when (result) {
        Lookup.Busy -> "MusicBrainz busy"
        is Lookup.Failed -> "failed"
        is Lookup.Done -> when (val proposal = result.proposal) {
            is Proposal.Ready -> "Ready"
            is Proposal.PickOne -> "Pick one, ${proposal.candidates.size} candidates (${proposal.doubts.joinToString()})"
        }
    }

    companion object {
        /**
         * Releases fetched in detail per album when fewer search hits have its track count: every hit with that count
         * is fetched whatever this says (a search lists at most [MusicBrainzUrls.SEARCH_LIMIT]), plus any id in the files.
         */
        const val MAX_DETAILED: Int = 5

        private const val VARIOUS_ARTISTS = "Various Artists"
    }
}
