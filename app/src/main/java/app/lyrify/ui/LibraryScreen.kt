package app.lyrify.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lyrify.R
import app.lyrify.data.LyricsStatus
import app.lyrify.library.RECENTLY_ADDED_DAYS
import app.lyrify.library.Song
import app.lyrify.library.displayTitle
import app.lyrify.library.sortSongs
import app.lyrify.lyrics.TrackNames
import app.lyrify.settings.DockTab
import app.lyrify.settings.LibrarySettings
import app.lyrify.settings.LyricsFilter
import app.lyrify.settings.SortSongs
import java.time.Instant
import java.time.ZoneId

// Library's pages. Milestone L1 replaces these bodies; the shell calls them as they are declared here.

/**
 * Library, the first tab, at its root. Until L1: the songs KJB knows (the ledger) with their lyrics status, sorted
 * from the top bar; tapping Library again goes to the top.
 */
@Composable
fun LibraryPage() {
    val nav = LocalNavigator.current
    var query by rememberSaveable(stateSaver = SongQuery.Saver) { mutableStateOf(SongQuery()) }
    var sortMenu by remember { mutableStateOf(false) }
    PageFrame(
        topBar = { under, backdrop ->
            LyrifyTopBar(
                title = { Text(stringResource(R.string.nav_library), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                under = under,
                backdrop = backdrop,
                actions = {
                    Box {
                        ChromeIconButton(R.drawable.ic_sort, stringResource(R.string.sort_cd), { sortMenu = true })
                        SortMenu(sortMenu, query.sort, onDismiss = { sortMenu = false }) {
                            sortMenu = false
                            query = query.copy(sort = it)
                        }
                    }
                },
            )
        },
    ) { padding ->
        LedgerSongs(query, { query = it }, reselect = nav.state.reselectsOf(DockTab.LIBRARY), Modifier.padding(padding).consumeWindowInsets(padding))
    }
}

/** Library > Songs with a lyrics filter, from the AI's results. Until L1: the ledger's songs with that status. */
@Composable
fun SongsPage(page: Page.Songs) {
    val nav = LocalNavigator.current
    var query by rememberSaveable(stateSaver = SongQuery.Saver) { mutableStateOf(SongQuery(group = page.group, status = page.status)) }
    PageFrame(
        topBar = { under, backdrop ->
            LyrifyTopBar(
                title = { Text(stringResource(R.string.songs_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                under = under,
                backdrop = backdrop,
                navigation = { ChromeIconButton(R.drawable.ic_arrow_back, stringResource(R.string.check_back), { nav.back() }) },
            )
        },
    ) { padding ->
        LedgerSongs(query, { query = it }, reselect = 0, Modifier.padding(padding).consumeWindowInsets(padding))
    }
}

/** Settings > Folders to skip: the library's folders, each with a switch (L1). */
@Composable
fun FoldersToSkipPage() {
    val nav = LocalNavigator.current
    PageStub("L1") { nav.back() }
}

/** 2.0's song list over the ledger, with its status bar and chips, for Library and Search until L1 and L2. */
@Composable
internal fun LedgerSongs(query: SongQuery, onQuery: (SongQuery) -> Unit, reselect: Int, modifier: Modifier) {
    val nav = LocalNavigator.current
    val lyrics = LocalLyrics.current
    Box(modifier.fillMaxSize().imePadding()) {
        SongsScreen(
            ledger = lyrics.ledger,
            query = query,
            onQuery = onQuery,
            timing = lyrics.timing,
            removal = lyrics.removal,
            retrying = lyrics.retrying,
            connected = lyrics.connected,
            reselect = reselect,
            onRetryFailed = lyrics.onRetryFailed,
            onPickLyrics = { nav.open(Page.Check(it)) },
            banner = { LyricsBanners(lyrics, Modifier.padding(start = ScreenPadding, end = ScreenPadding, bottom = 8.dp), listener = false) },
        )
    }
}

// The lists, as pure functions

/** A song row's lyrics mark, which never relies on colour alone: a filled dot for synced lyrics, a ring for plain, nothing for none. */
enum class LyricsMark { SYNCED, PLAIN, NONE }

/** The mark for a song whose ledger status is [status] (null: KJB has not looked it up). */
fun lyricsMark(status: LyricsStatus?): LyricsMark = when (status) {
    LyricsStatus.SYNCED, LyricsStatus.EXISTING, LyricsStatus.AI_ALIGNED, LyricsStatus.AI_TRANSCRIBED -> LyricsMark.SYNCED
    LyricsStatus.PLAIN_ONLY -> LyricsMark.PLAIN
    else -> LyricsMark.NONE
}

/** Whether a song with [mark] shows under the Songs tab's "Show" filter. */
fun LyricsFilter.shows(mark: LyricsMark): Boolean = when (this) {
    LyricsFilter.ALL -> true
    LyricsFilter.SYNCED -> mark == LyricsMark.SYNCED
    LyricsFilter.PLAIN -> mark == LyricsMark.PLAIN
    LyricsFilter.NONE -> mark == LyricsMark.NONE
}

/** The Songs tab: [songs] that pass the filter, in the sort ([LibrarySettings.songFilter], [LibrarySettings.songSort]). */
fun songsTab(songs: List<Song>, statuses: (Song) -> LyricsStatus?, settings: LibrarySettings): List<Song> =
    sortSongs(songs.filter { settings.songFilter.shows(lyricsMark(statuses(it))) }, settings.songSort, settings)

/** How many of [songs] each "Show" filter shows, for the sort and filter sheet. */
fun filterCounts(songs: List<Song>, statuses: (Song) -> LyricsStatus?): Map<LyricsFilter, Int> {
    val marks = songs.groupingBy { lyricsMark(statuses(it)) }.eachCount()
    return LyricsFilter.entries.associateWith { filter -> marks.filterKeys(filter::shows).values.sum() }
}

/** When a song was added, as the Songs tab groups it in "Recently added". */
enum class AddedWhen { TODAY, THIS_WEEK, THIS_MONTH, EARLIER }

/** A heading over the Songs tab's rows: the first letter by title, the artist, the album, or when it was added. */
sealed interface ListSection {
    /** A letter, or "#" for digits, symbols and nothing. */
    data class Letter(val letter: String) : ListSection

    /** An artist or album name; null for songs without one. */
    data class Name(val name: String?) : ListSection

    data class Added(val time: AddedWhen) : ListSection
}

/** [song]'s heading under [sort], with "Recently added" by [now]'s day in [zone]. */
fun listSection(song: Song, sort: SortSongs, settings: LibrarySettings, now: Long, zone: ZoneId): ListSection = when (sort) {
    SortSongs.TITLE -> ListSection.Letter(
        TrackNames.normalize(displayTitle(song, settings)).trim().firstOrNull()?.takeIf { it.isLetter() }?.uppercase() ?: "#",
    )
    SortSongs.ARTIST -> ListSection.Name(song.artist?.trim()?.takeIf(String::isNotEmpty))
    SortSongs.ALBUM -> ListSection.Name(song.album?.trim()?.takeIf(String::isNotEmpty))
    SortSongs.RECENTLY_ADDED -> {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val day = song.addedAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
        ListSection.Added(
            when {
                day == null -> AddedWhen.EARLIER
                !day.isBefore(today) -> AddedWhen.TODAY
                !day.isBefore(today.minusDays(WEEK_DAYS - 1)) -> AddedWhen.THIS_WEEK
                !day.isBefore(today.minusDays(RECENTLY_ADDED_DAYS - 1)) -> AddedWhen.THIS_MONTH
                else -> AddedWhen.EARLIER
            },
        )
    }
}

/** "This week": today and the six days before. */
private const val WEEK_DAYS = 7L
