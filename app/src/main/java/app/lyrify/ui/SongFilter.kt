package app.lyrify.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import app.lyrify.R
import app.lyrify.data.LedgerEntry
import app.lyrify.data.LedgerState
import app.lyrify.data.LyricsStatus
import app.lyrify.library.nameLetter
import app.lyrify.library.nameOrder
import app.lyrify.lyrics.TrackNames
import java.text.Collator
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The four groups of the status bar, the Library and the Now tiles, in the order they show. */
enum class SongGroup(@StringRes val label: Int, val statuses: List<LyricsStatus>, val tone: Tone, @DrawableRes val icon: Int) {
    HAS_LYRICS(
        R.string.group_has_lyrics,
        listOf(LyricsStatus.SYNCED, LyricsStatus.EXISTING, LyricsStatus.EXISTING_PLAIN, LyricsStatus.AI_ALIGNED, LyricsStatus.AI_TRANSCRIBED),
        Tone.SYNCED,
        R.drawable.ic_lyrics_fill,
    ),
    TO_CHECK(R.string.group_to_check, listOf(LyricsStatus.NEEDS_CHECK), Tone.CHECK, R.drawable.ic_help_fill),
    WAITING(
        R.string.group_waiting,
        listOf(LyricsStatus.PLAIN_ONLY, LyricsStatus.ERROR, LyricsStatus.AI_FAILED, LyricsStatus.WRITING),
        Tone.PLAIN,
        R.drawable.ic_hourglass_top_fill,
    ),
    NO_LYRICS(
        R.string.group_no_lyrics,
        listOf(
            LyricsStatus.NOT_FOUND,
            LyricsStatus.INSTRUMENTAL,
            LyricsStatus.AI_INSTRUMENTAL,
            LyricsStatus.AI_REJECTED,
            LyricsStatus.DURATION_MISMATCH,
            LyricsStatus.UNIDENTIFIED,
            LyricsStatus.REMOVED,
        ),
        Tone.NONE,
        R.drawable.ic_search_off,
    ),
}

val LyricsStatus.group: SongGroup get() = SongGroup.entries.first { this in it.statuses }

/** The Songs tab's order: newest first by day, or by title or artist with a section per first letter. */
enum class SongSort(@StringRes val label: Int) {
    RECENT(R.string.sort_recent),
    TITLE(R.string.sort_title),
    ARTIST(R.string.sort_artist),
}

/**
 * What the Songs tab shows: [text] in the title or the artist, then a [group] (null: all songs) and within it a [status]
 * (null: the whole group), in [sort] order.
 */
data class SongQuery(val text: String = "", val group: SongGroup? = null, val status: LyricsStatus? = null, val sort: SongSort = SongSort.RECENT) {
    /** A search or a filter narrows the list (the sort does not). */
    val filtered: Boolean get() = text.isNotBlank() || group != null || status != null

    companion object {
        fun save(query: SongQuery): List<String> = listOf(query.text, query.group?.name.orEmpty(), query.status?.name.orEmpty(), query.sort.name)

        fun restore(saved: List<String>): SongQuery = SongQuery(
            text = saved[0],
            group = SongGroup.entries.firstOrNull { it.name == saved[1] },
            status = LyricsStatus.entries.firstOrNull { it.name == saved[2] },
            sort = SongSort.entries.firstOrNull { it.name == saved[3] } ?: SongSort.RECENT,
        )

        /** Kept across configuration changes and process death, so the Now and AI tabs can open Songs already filtered. */
        val Saver: Saver<SongQuery, Any> = listSaver<SongQuery, String>(save = { save(it) }, restore = { restore(it) })
    }
}

/** A sticky header of the Songs list. */
sealed interface Section {
    data object Today : Section

    data object Yesterday : Section

    data object ThisWeek : Section

    data object Earlier : Section

    /** A first letter ([nameLetter]), "#" for digits, nothing and songs with no artist. */
    data class Letter(val letter: String) : Section
}

data class SongSection(val section: Section, val songs: List<LedgerEntry>)

/**
 * The Songs tab for a query: [sections] of the songs it shows, [total] songs matching the search, [shown] of them after
 * the group and status; [groupCounts] and [statusCounts] for the search alone (the counts on the status bar and chips);
 * [statuses], the status chips (empty when they do not show).
 */
data class SongList(
    val sections: List<SongSection>,
    val total: Int,
    val groupCounts: Map<SongGroup, Int>,
    val statusCounts: Map<LyricsStatus, Int>,
    val statuses: List<LyricsStatus>,
) {
    val songs: List<LedgerEntry> get() = sections.flatMap { it.songs }

    /** The songs [status]'s chip counts: with the statuses that read the same ([sameChip]). */
    fun chipCount(status: LyricsStatus): Int = statusCounts.filterKeys { sameChip(it, status) }.values.sum()
    val shown: Int get() = sections.sumOf { it.songs.size }
}

/**
 * The ledger's songs matching [query]: the text in the title or the artist (case and accents aside), then its group and
 * status, sorted and sectioned (days are [now]'s in [zone]). Counts follow the search. The status chips show when the
 * selected group has two or more statuses with songs (the selected status counts as one), so each of the statuses can
 * still be shown, one chip for the statuses that read the same ([sameChip]).
 */
fun songList(ledger: LedgerState, query: SongQuery, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): SongList {
    val words = TrackNames.normalize(query.text)
    val found = ledger.entries.values.filter { words.isEmpty() || matches(it, words) }
    val counted = found.groupingBy { it.status }.eachCount()
    val statusCounts = LyricsStatus.entries.associateWith { counted[it] ?: 0 }
    val chipCounts = statusCounts.keys.associateWith { status -> statusCounts.filterKeys { sameChip(it, status) }.values.sum() }
    val statuses = query.group?.statuses.orEmpty()
        .filter { status -> chipCounts.getValue(status) > 0 || query.status?.let { sameChip(it, status) } == true }
        .distinctBy { it.style.label }
    val shown = found.filter { (query.group == null || it.status.group == query.group) && (query.status == null || sameChip(it.status, query.status)) }
    return SongList(
        sections = sections(shown, query.sort, dateOf(now, zone), zone),
        total = found.size,
        groupCounts = groupCounts(statusCounts),
        statusCounts = statusCounts,
        statuses = if (statuses.size >= 2) statuses else emptyList(),
    )
}

/** Whether [a] and [b] share a chip: they read the same (a synced lyrics file already there, and KJB's own). */
fun sameChip(a: LyricsStatus, b: LyricsStatus): Boolean = a.style.label == b.style.label

/** Songs per group, from the songs per status ([LedgerState.counts]). */
fun groupCounts(counts: Map<LyricsStatus, Int>): Map<SongGroup, Int> = SongGroup.entries.associateWith { group -> group.statuses.sumOf { counts[it] ?: 0 } }

/** The songs of the "Check these" screen: those waiting for the user to pick lyrics, with something to pick, newest first. */
fun checkList(ledger: LedgerState): List<LedgerEntry> =
    ledger.entries.values
        .filter { it.status == LyricsStatus.NEEDS_CHECK && it.candidates.isNotEmpty() }
        .sortedWith(compareByDescending<LedgerEntry> { it.updatedAt }.thenBy { it.path })

private fun sections(songs: List<LedgerEntry>, sort: SongSort, today: LocalDate, zone: ZoneId): List<SongSection> = when (sort) {
    SongSort.RECENT -> songs
        .sortedWith(compareByDescending<LedgerEntry> { it.updatedAt }.thenBy { it.path })
        .groupBy { day(dateOf(it.updatedAt, zone), today) }
        .map { (section, inIt) -> SongSection(section, inIt) }
    SongSort.TITLE -> byLetter(songs) { it.title }
    SongSort.ARTIST -> byLetter(songs) { song -> song.artist?.let(TrackNames::cleanArtist).orEmpty() }
}

/** Sorted by [name] as the library's lists are ([nameOrder]), with a section per [nameLetter]: "#" (digits, nothing) first. */
private fun byLetter(songs: List<LedgerEntry>, name: (LedgerEntry) -> String): List<SongSection> =
    songs
        .sortedWith(compareBy(nameOrder(Collator.getInstance()), name).thenBy { it.title }.thenBy { it.path })
        .groupBy { nameLetter(name(it)) }
        .map { (letter, inIt) -> SongSection(Section.Letter(letter), inIt) }

private fun dateOf(millis: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

private fun day(date: LocalDate, today: LocalDate): Section = when {
    !date.isBefore(today) -> Section.Today
    date == today.minusDays(1) -> Section.Yesterday
    !date.isBefore(today.minusDays(WEEK_DAYS - 1)) -> Section.ThisWeek
    else -> Section.Earlier
}

private fun matches(entry: LedgerEntry, words: String): Boolean =
    TrackNames.normalize(entry.title).contains(words) || TrackNames.normalize(entry.artist.orEmpty()).contains(words)

/** "This week": today and the six days before. */
private const val WEEK_DAYS = 7L
