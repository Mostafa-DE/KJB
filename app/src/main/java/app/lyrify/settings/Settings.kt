package app.lyrify.settings

import app.lyrify.library.SongRef
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Everything the user sets in the app from 3.0 on, one section per feature, every field with its default (from
 * `player-spec.md`, `covers-tags-spec.md`, `v3-extras-spec.md` and the prototype). Stored by [SettingsJson] in
 * `settings.json`. Later milestones only read these; a field found missing is added here.
 *
 * Not here: the AI switch, which stays in its SharedPreferences (overnight/AiWorker.kt, read and written through
 * AiLyrics); the phone's grants (notification access, All files access, battery), which are never stored; the repeat
 * mode and the queues (`playback.json`, `queues.json`); speeds, places and bookmarks per song (`song-state.json`); the
 * equalizer's profiles (`eq-profiles.json`).
 */
data class Settings(
    val playback: PlaybackSettings = PlaybackSettings(),
    val audio: AudioSettings = AudioSettings(),
    val library: LibrarySettings = LibrarySettings(),
    val lyrics: LyricsSettings = LyricsSettings(),
    val playerUi: PlayerUiSettings = PlayerUiSettings(),
    val backup: BackupSettings = BackupSettings(),
    val advanced: AdvancedSettings = AdvancedSettings(),
    val nav: NavSettings = NavSettings(),
    val display: DisplaySettings = DisplaySettings(),
    val about: AboutSettings = AboutSettings(),
)

/** Settings > Playback. */
data class PlaybackSettings(
    /** "Remember my place": which songs resume where the user left them; Long songs (user, 2026-11-12). */
    val rememberPlace: RememberPlace = RememberPlace.LONG_SONGS,
    /** "Headset buttons": one press plays or pauses, two skip, three go back. "Pause when disconnected" is always on. */
    val headsetButtons: Boolean = true,
    /** "Skip short clips": songs under 30 s leave the library. */
    val skipShortClips: Boolean = true,
    /** "Count a play after": the share of a song heard, in percent, before it counts as played. One of [PLAY_SHARES]. */
    val countPlayAfterPercent: Int = 50,
    /** "Skip amounts", Back: the jump back of the seek button beside Previous, in seconds. One of [SKIP_SECONDS]. */
    val skipBackSeconds: Int = 30,
    /** "Skip amounts", Forward: the jump of the seek button beside Next, in seconds. One of [SKIP_SECONDS]. */
    val skipForwardSeconds: Int = 10,
    /** "Start when connected": the Bluetooth devices picked for it, in the order added; Off with none. */
    val connectDevices: List<ConnectDevice> = emptyList(),
) {
    companion object {
        /** 10% to 100% in 10% steps. */
        val PLAY_SHARES: List<Int> = (10..100 step 10).toList()
        val SKIP_SECONDS: List<Int> = listOf(5, 10, 15, 30)
    }
}

/**
 * A device picked for "Start when connected": Android's association with KJB ([associationId], from the Companion
 * Device Manager) and the name Android's picker showed. Never its Bluetooth address.
 */
data class ConnectDevice(val associationId: Int, val name: String)

/**
 * "Remember my place": Long songs (10 minutes or longer, the default; All songs was in version 3, dev builds only), All
 * songs, or Off.
 */
enum class RememberPlace { LONG_SONGS, ALL_SONGS, OFF }

/** Playback > Volume levelling and Crossfade: what the audio chain does, shown under Playback. */
data class AudioSettings(
    /** "Volume levelling" (ReplayGain). */
    val levelling: Levelling = Levelling.AUTO,
    /** "Crossfade" in seconds: 0 is Off, else 1 to 12. One of [CROSSFADE_SECONDS]. */
    val crossfadeSeconds: Int = 0,
) {
    companion object {
        /** Off, then 1 to 12 s. */
        val CROSSFADE_SECONDS: IntRange = 0..12
    }
}

/** "Volume levelling": Auto uses album gain for an album played in track order, else song gain. */
enum class Levelling { OFF, AUTO, SONG, ALBUM }

/** Settings > Library, and the sort chips the lists remember. */
data class LibrarySettings(
    /** "Library tabs": every tab once, in the order shown, each shown or hidden; at least one is shown. */
    val tabs: List<LibraryTabSetting> = DEFAULT_TABS,
    /**
     * "Folders to skip": their songs stay on the phone but leave the library. Each is a folder relative to its volume, as
     * MediaStore's RELATIVE_PATH without the last slash ("WhatsApp/Media/WhatsApp Audio").
     */
    val foldersToSkip: List<String> = emptyList(),
    /** "Show file names": file names instead of titles everywhere. */
    val showFileNames: Boolean = false,
    /** Library > Songs: its sort, the other way round or not ([SortSongs]), and which songs it shows by their lyrics. */
    val songSort: SortSongs = SortSongs.TITLE,
    val songReversed: Boolean = false,
    val songFilter: LyricsFilter = LyricsFilter.ALL,
    val albumSort: SortAlbums = SortAlbums.TITLE,
    val albumReversed: Boolean = false,
    val artistSort: SortArtists = SortArtists.NAME,
    val artistReversed: Boolean = false,
    /** Each song page's order, remembered per kind of page; a page not here has its [SongList.default]. */
    val listSorts: Map<SongList, ListSort> = emptyMap(),
    /**
     * "Hidden songs": the paths of songs that leave every list, search, shuffle and stats view; their files stay on the
     * phone and their plays stay counted. In the order hidden, each once.
     */
    val hiddenSongs: List<SongRef> = emptyList(),
    /** "Artists with several names": how the lists read a tag that names several artists. */
    val artistSplit: ArtistSplit = ArtistSplit(),
    /**
     * The tab Library opens on, chosen by holding it in Library ("Open here first"); null for the first tab shown in
     * [tabs]' order. A chosen tab that is hidden, or Books while the library holds no book, gives way to that first one.
     */
    val opensOn: LibraryTab? = null,
) {
    /** [list]'s order: as the user left it, else its default. */
    fun sortOf(list: SongList): ListSort = listSorts[list] ?: list.default

    companion object {
        /** Songs, Playlists, Albums, Artists, Folders and Books shown; Genres hidden. */
        val DEFAULT_TABS: List<LibraryTabSetting> = LibraryTab.entries.map { LibraryTabSetting(it, shown = it != LibraryTab.GENRES) }
    }
}

/**
 * "Artists with several names" (user feedback 2026-11-17, item 18): a tag such as "Kit Calloway feat. Mira Solen" puts
 * the song on both artists' pages, for the lists, pages and Search only; KJB never writes a tag for it. Each of the
 * [separators] splits a tag, a mark (";") wherever it is, a word ("feat.") only as a whole word (library/ArtistNames.kt).
 * The [exceptions] are names never split ("AC/DC"). Commas split by default and "&" does not (lead's default, 2026-11-18).
 */
data class ArtistSplit(
    val enabled: Boolean = true,
    val separators: List<String> = DEFAULT_SEPARATORS,
    val exceptions: List<String> = DEFAULT_EXCEPTIONS,
) {
    companion object {
        val DEFAULT_SEPARATORS: List<String> = listOf(";", "/", ",", "feat.", "ft.", "featuring")
        val DEFAULT_EXCEPTIONS: List<String> = listOf("AC/DC", "Tyler, The Creator")
    }
}

/** The Library tabs. [BOOKS] shows only while the library holds a book (books/), whatever this says. */
enum class LibraryTab { SONGS, PLAYLISTS, ALBUMS, ARTISTS, FOLDERS, BOOKS, GENRES }

data class LibraryTabSetting(val tab: LibraryTab, val shown: Boolean)

/**
 * What a list of songs is sorted by, each first in its own direction: title, artist, album and file name A to Z (in
 * natural number order, "Track 2" before "Track 10"); year, date added and last played the newest first; play count the
 * most first; length the longest first. Reversed, each goes the other way; songs without a value go last either way.
 */
enum class SortSongs { TITLE, ARTIST, ALBUM, YEAR, RECENTLY_ADDED, PLAY_COUNT, LAST_PLAYED, DURATION, FILE_NAME }

/** Songs with synced lyrics, plain lyrics, none, or all of them. */
enum class LyricsFilter { ALL, SYNCED, PLAIN, NONE }

/** What the Albums tab is sorted by, each first in its direction as [SortSongs]; an album was added when its last song was. */
enum class SortAlbums { TITLE, ARTIST, YEAR, RECENTLY_ADDED, PLAY_COUNT, LAST_PLAYED, DURATION }

/** What the Artists tab is sorted by, each first in its direction as [SortSongs]; the most songs first. */
enum class SortArtists { NAME, MOST_SONGS, RECENTLY_ADDED, PLAY_COUNT, LAST_PLAYED }

/**
 * A song page's order: its own while [key] is null (an album's track order, a playlist's custom order, which the handles
 * change), else by [key], [reversed] or not. The own order is never reversed.
 */
data class ListSort(val key: SortSongs? = null, val reversed: Boolean = false) {
    companion object {
        val OWN: ListSort = ListSort()
    }
}

/**
 * The song pages that remember their order, each kind once (every album shares one): those with an own order ([own])
 * open in it; the others by their [default] key. Every page offers every [SortSongs] key.
 */
enum class SongList(val own: Boolean, val default: ListSort) {
    ALBUM(true, ListSort.OWN),
    /** Its own order: the newest album first, each in track order, songs without an album last. */
    ARTIST(true, ListSort.OWN),
    FOLDER(false, ListSort(SortSongs.FILE_NAME)),
    GENRE(false, ListSort(SortSongs.TITLE)),
    UNKNOWN_ALBUM(false, ListSort(SortSongs.TITLE)),
    PLAYLIST(true, ListSort.OWN),
    FAVOURITES(true, ListSort.OWN),
    MOST_PLAYED(false, ListSort(SortSongs.PLAY_COUNT)),
    RECENTLY_PLAYED(false, ListSort(SortSongs.LAST_PLAYED)),
    RECENTLY_ADDED(false, ListSort(SortSongs.RECENTLY_ADDED)),
}

/** Settings > Lyrics, and the "Aa" choices of full lyrics. */
data class LyricsSettings(
    /**
     * "Show automatically when locked": the lock screen lyrics open by themselves while the app plays. Needs "Display
     * over other apps", asked only when this is switched on. Tapping the music notification works either way.
     */
    val showWhenLocked: Boolean = false,
    /** "Aa": the size, weight and alignment of the lyrics text, remembered. */
    val textSize: LyricsSize = LyricsSize.M,
    val textWeight: LyricsWeight = LyricsWeight.BOLD,
    val textAlign: LyricsAlign = LyricsAlign.LEFT,
)

enum class LyricsSize { S, M, L, XL }

enum class LyricsWeight { REGULAR, BOLD }

enum class LyricsAlign { LEFT, CENTRE }

/** What the player remembers about itself. */
data class PlayerUiSettings(
    /** The flip area shows the lyrics panel instead of the cover: the side last shown. */
    val showLyrics: Boolean = false,
    /** "Tap the cover for lyrics" was shown (once). */
    val flipHintShown: Boolean = false,
    /** "Your last queue is in Recent" was shown (once). */
    val recentQueueHintShown: Boolean = false,
)

/** Settings > Backup. "Back up now" and "Restore" are actions, not settings. */
data class BackupSettings(
    /** "Automatic backup": weekly while charging, the last 5 kept. */
    val automatic: Boolean = false,
    /** Its folder: a Storage Access Framework tree URI with a persisted permission; null until one is picked. */
    val folderUri: String? = null,
)

/** Settings > Advanced (collapsed): engine detail. */
data class AdvancedSettings(
    /** "Gapless playback": no pause between songs. */
    val gaplessPlayback: Boolean = true,
    /** "Audio offload": saves battery; forced off whenever any sound processing is on. */
    val audioOffload: Boolean = false,
    /** "Notification buttons": the extra buttons the phone's media player shows. */
    val notificationButtons: NotificationButtons = NotificationButtons.FAVOURITE,
    /** "Levelling boost" in dB, added to Volume levelling's gain. One of [LEVELLING_BOOST_DB]. */
    val levellingBoostDb: Int = 0,
    /** "Keep albums gapless": songs of one album in track order never crossfade. */
    val keepAlbumsGapless: Boolean = true,
) {
    companion object {
        val LEVELLING_BOOST_DB: IntRange = -6..6
    }
}

/** Settings > About. */
data class AboutSettings(
    /** "Check for updates": once a day when KJB opens, one request to KJB's latest release on github.com (update/). */
    val checkForUpdates: Boolean = true,
)

/** "Notification buttons": a heart beside Next, or Shuffle and Repeat. */
enum class NotificationButtons { FAVOURITE, SHUFFLE_REPEAT }

/**
 * Where the app reopens, how Settings' sections were left, and what the first run and its asks have shown. Library's own
 * tab is not kept here: it opens on [LibrarySettings.opensOn].
 */
data class NavSettings(
    /** The dock tab last open. */
    val lastTab: DockTab = DockTab.LIBRARY,
    /** The Favourites page's tab last open. */
    val favouritesTab: FavouritesTab = FavouritesTab.SONGS,
    /** The first-run screen was finished with Continue, or skipped on a phone that already had both grants. */
    val setupDone: Boolean = false,
    /**
     * The tour was finished or skipped. Missing in older files, so it reads false: a phone updated from an earlier
     * version sees the tour once (user decision, 2026-11-13), and its [setupDone] stays as it was.
     */
    val tourSeen: Boolean = false,
    /** Notifications (the AI's progress) were denied for good: Android asks no more, only the phone's settings can allow them. */
    val notificationsDenied: Boolean = false,
    /** How often a request for notifications came back no: the second no is for good. */
    val notificationDenials: Int = 0,
    /** Settings' sections the user opened (true) or closed (false) by tapping their titles; the others are as at first. */
    val settingsSections: Map<SettingsSection, Boolean> = emptyMap(),
) {
    /** Whether Settings shows [section]'s rows: as the user left it, else as [SettingsSection.openAtFirst] says. */
    fun opens(section: SettingsSection): Boolean = settingsSections[section] ?: section.openAtFirst
}

/**
 * Settings' sections, in the screen's order, each opened and closed by its title. Lyrics, AI, Downloads and Backup start
 * closed, the others open (user, 2026-11-21); Advanced, under About, is a row of its own that always starts closed.
 */
enum class SettingsSection(val openAtFirst: Boolean) { PLAYBACK(true), LIBRARY(true), DISPLAY(true), LYRICS(false), AI(false), DOWNLOADS(false), BACKUP(false), ABOUT(true) }

/**
 * What the bottom bar (the dock) can hold, each with its own page stack: Library, Playlists, Search and Tools by default,
 * and Queues, Favourites, Downloads (only with KJB Downloads installed) and Books (only while the library holds a book)
 * when the user puts them there (Settings > Display > Bottom bar).
 */
enum class DockTab { LIBRARY, PLAYLISTS, QUEUES, FAVOURITES, DOWNLOADS, BOOKS, SEARCH, TOOLS }

/** Settings > Display. */
data class DisplaySettings(
    /** "Theme": as the phone is (the default), or always light, or always dark. */
    val theme: ThemeChoice = ThemeChoice.SYSTEM,
    /** "Pure black": the dark theme's pages on black, for OLED screens; nothing changes while the theme is light. */
    val pureBlack: Boolean = false,
    /**
     * "Bottom bar": every item once, in the order shown, each on the bar or not; Library always on. An item the phone
     * can't offer now (Downloads without its add-on, Books without a book) keeps its place here and comes back with it.
     */
    val dock: List<DockItemSetting> = DEFAULT_DOCK,
    /** "Bottom bar" style: Standard, each icon over its name, or Slim. */
    val dockStyle: DockStyle = DockStyle.STANDARD,
    /** "Hide while scrolling": scrolling a page down slides the bottom bar away (off by default, user 2026-11-18). */
    val hideDockOnScroll: Boolean = false,
) {
    companion object {
        // First: the default below reads it as it is made.
        private val DEFAULT_ON = setOf(DockTab.LIBRARY, DockTab.PLAYLISTS, DockTab.SEARCH, DockTab.TOOLS)

        /** Library, Playlists, Search and Tools on the bar, as before it could change; the others off, in their places. */
        val DEFAULT_DOCK: List<DockItemSetting> = DockTab.entries.map { DockItemSetting(it, shown = it in DEFAULT_ON) }

        /** The most items the bar holds (user 2026-11-18). */
        const val MAX_DOCK: Int = 6
    }
}

data class DockItemSetting(val tab: DockTab, val shown: Boolean)

/** The bottom bar's look: Standard (64 dp, each icon over its name) or Slim (48 dp, icons only; a hold shows the name). */
enum class DockStyle { STANDARD, SLIM }

enum class ThemeChoice { SYSTEM, LIGHT, DARK }

/** The Favourites page's tabs: the favourite songs, then the albums, artists, folders and playlists with a heart. */
enum class FavouritesTab { SONGS, ALBUMS, ARTISTS, FOLDERS, PLAYLISTS }

/**
 * The stored form of [Settings]: a JSON object with a version and one object per section, enums by name. Read leniently,
 * field by field: a key this version does not know is ignored, and a field missing, of another type, out of range or
 * naming an unknown choice reads as its default while the fields around it are read as stored. A section that is
 * missing or not an object reads as its defaults. Only text that is not a JSON object at all is not settings.
 * Version 2 put Playlists right after Songs in the Library tabs. Remember my place was written into every file, so a
 * value that was its version's default is no choice: Long songs before version 3, and All songs in version 3 (the
 * default in dev builds only, user feedback 2026-11-11, until Long songs came back: user, 2026-11-12). Such a value reads
 * as a missing one does (the default, Long songs), while the other values are kept; from version 4 every value is kept.
 * A file without a version reads as this version. A backup's settings are read the same way over the
 * phone's own ([decode]'s base), so what the backup does not carry stays as the phone has it. The sort keys, the song
 * pages' orders, the hidden songs and Display came later as new keys (features 2026-11-15), and "Artists with several
 * names" (3.1): a file without them reads as the defaults, a page order this version cannot use as that page's default.
 * The bottom bar, its style, Hide while scrolling and the tab Library opens on came in 3.1 as new keys too: a file
 * without them reads today's bar, Standard, never hiding, and Library on the first tab in its order. So did Settings'
 * open and closed sections, after 3.1.0: a file without them reads each section as at first.
 */
object SettingsJson {
    const val VERSION: Int = 4

    fun encode(settings: Settings): String = Json.encodeToString(
        JsonObject.serializer(),
        JsonObject(
            mapOf(
                "version" to JsonPrimitive(VERSION),
                "playback" to settings.playback.let {
                    obj(
                        "rememberPlace" to it.rememberPlace.name,
                        "headsetButtons" to it.headsetButtons,
                        "skipShortClips" to it.skipShortClips,
                        "countPlayAfterPercent" to it.countPlayAfterPercent,
                        "skipBackSeconds" to it.skipBackSeconds,
                        "skipForwardSeconds" to it.skipForwardSeconds,
                        "connectDevices" to JsonArray(it.connectDevices.map { device -> obj("id" to device.associationId, "name" to device.name) }),
                    )
                },
                "audio" to settings.audio.let { obj("levelling" to it.levelling.name, "crossfadeSeconds" to it.crossfadeSeconds) },
                "library" to settings.library.let {
                    JsonObject(
                        mapOf(
                            "tabs" to JsonArray(it.tabs.map { tab -> obj("tab" to tab.tab.name, "shown" to tab.shown) }),
                            "foldersToSkip" to JsonArray(it.foldersToSkip.map(::JsonPrimitive)),
                            "showFileNames" to JsonPrimitive(it.showFileNames),
                            "songSort" to JsonPrimitive(it.songSort.name),
                            "songReversed" to JsonPrimitive(it.songReversed),
                            "songFilter" to JsonPrimitive(it.songFilter.name),
                            "albumSort" to JsonPrimitive(it.albumSort.name),
                            "albumReversed" to JsonPrimitive(it.albumReversed),
                            "artistSort" to JsonPrimitive(it.artistSort.name),
                            "artistReversed" to JsonPrimitive(it.artistReversed),
                            "listSorts" to JsonObject(
                                it.listSorts.entries.associate { (list, sort) -> list.name to obj("key" to (sort.key?.name ?: OWN_ORDER), "reversed" to sort.reversed) },
                            ),
                            "hiddenSongs" to JsonArray(it.hiddenSongs.map { song -> obj("path" to song.path, "id" to song.mediaStoreId?.let(::JsonPrimitive)).withoutNulls() }),
                            "artistSplit" to it.artistSplit.let { split ->
                                obj("enabled" to split.enabled, "separators" to JsonArray(split.separators.map(::JsonPrimitive)), "exceptions" to JsonArray(split.exceptions.map(::JsonPrimitive)))
                            },
                            "opensOn" to (it.opensOn?.name?.let(::JsonPrimitive) ?: JsonNull),
                        ),
                    )
                },
                "lyrics" to settings.lyrics.let {
                    obj(
                        "showWhenLocked" to it.showWhenLocked,
                        "textSize" to it.textSize.name,
                        "textWeight" to it.textWeight.name,
                        "textAlign" to it.textAlign.name,
                    )
                },
                "playerUi" to settings.playerUi.let {
                    obj("showLyrics" to it.showLyrics, "flipHintShown" to it.flipHintShown, "recentQueueHintShown" to it.recentQueueHintShown)
                },
                "backup" to settings.backup.let { obj("automatic" to it.automatic, "folderUri" to it.folderUri) },
                "advanced" to settings.advanced.let {
                    obj(
                        "gaplessPlayback" to it.gaplessPlayback,
                        "audioOffload" to it.audioOffload,
                        "notificationButtons" to it.notificationButtons.name,
                        "levellingBoostDb" to it.levellingBoostDb,
                        "keepAlbumsGapless" to it.keepAlbumsGapless,
                    )
                },
                "nav" to settings.nav.let {
                    obj(
                        "lastTab" to it.lastTab.name,
                        "favouritesTab" to it.favouritesTab.name,
                        "setupDone" to it.setupDone,
                        "tourSeen" to it.tourSeen,
                        "notificationsDenied" to it.notificationsDenied,
                        "notificationDenials" to it.notificationDenials,
                        "settingsSections" to JsonObject(it.settingsSections.entries.associate { (section, open) -> section.name to JsonPrimitive(open) }),
                    )
                },
                "display" to settings.display.let {
                    obj(
                        "theme" to it.theme.name,
                        "pureBlack" to it.pureBlack,
                        "dock" to JsonArray(it.dock.map { item -> obj("tab" to item.tab.name, "shown" to item.shown) }),
                        "dockStyle" to it.dockStyle.name,
                        "hideDockOnScroll" to it.hideDockOnScroll,
                    )
                },
                "about" to settings.about.let { obj("checkForUpdates" to it.checkForUpdates) },
            ),
        ),
    )

    /**
     * The settings in [text]; null when it is not a JSON object at all. A field or section [text] does not carry, or
     * carries in a form this version can't use, reads as [base] has it: the defaults for `settings.json`, the phone's
     * own settings for a backup's.
     */
    fun decode(text: String, base: Settings = Settings()): Settings? {
        val root = try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (_: SerializationException) {
            null
        } ?: return null
        val version = (root["version"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: VERSION
        val d = base
        return Settings(
            playback = root.section("playback")?.let {
                PlaybackSettings(
                    rememberPlace = rememberPlace(it, version, d.playback.rememberPlace),
                    headsetButtons = it.flag("headsetButtons", d.playback.headsetButtons),
                    skipShortClips = it.flag("skipShortClips", d.playback.skipShortClips),
                    countPlayAfterPercent = it.number("countPlayAfterPercent", d.playback.countPlayAfterPercent, PlaybackSettings.PLAY_SHARES::contains),
                    skipBackSeconds = it.number("skipBackSeconds", d.playback.skipBackSeconds, PlaybackSettings.SKIP_SECONDS::contains),
                    skipForwardSeconds = it.number("skipForwardSeconds", d.playback.skipForwardSeconds, PlaybackSettings.SKIP_SECONDS::contains),
                    connectDevices = (it["connectDevices"] as? JsonArray)?.let(::connectDevices) ?: d.playback.connectDevices,
                )
            } ?: d.playback,
            audio = root.section("audio")?.let {
                AudioSettings(
                    levelling = it.choice("levelling", d.audio.levelling),
                    crossfadeSeconds = it.number("crossfadeSeconds", d.audio.crossfadeSeconds, AudioSettings.CROSSFADE_SECONDS::contains),
                )
            } ?: d.audio,
            library = root.section("library")?.let {
                LibrarySettings(
                    tabs = (it["tabs"] as? JsonArray)?.let { stored -> tabs(stored, version) } ?: d.library.tabs,
                    foldersToSkip = (it["foldersToSkip"] as? JsonArray)?.mapNotNull { folder -> folder.text()?.takeIf(String::isNotBlank) }?.distinct() ?: d.library.foldersToSkip,
                    showFileNames = it.flag("showFileNames", d.library.showFileNames),
                    songSort = it.choice("songSort", d.library.songSort),
                    songReversed = it.flag("songReversed", d.library.songReversed),
                    songFilter = it.choice("songFilter", d.library.songFilter),
                    albumSort = it.choice("albumSort", d.library.albumSort),
                    albumReversed = it.flag("albumReversed", d.library.albumReversed),
                    artistSort = it.choice("artistSort", d.library.artistSort),
                    artistReversed = it.flag("artistReversed", d.library.artistReversed),
                    listSorts = (it["listSorts"] as? JsonObject)?.let(::listSorts) ?: d.library.listSorts,
                    hiddenSongs = (it["hiddenSongs"] as? JsonArray)?.let(::hiddenSongs) ?: d.library.hiddenSongs,
                    artistSplit = (it["artistSplit"] as? JsonObject)?.let { split -> artistSplit(split, d.library.artistSplit) } ?: d.library.artistSplit,
                    opensOn = if ("opensOn" in it) it.choiceOrNull<LibraryTab>("opensOn") else d.library.opensOn,
                )
            } ?: d.library,
            lyrics = root.section("lyrics")?.let {
                LyricsSettings(
                    showWhenLocked = it.flag("showWhenLocked", d.lyrics.showWhenLocked),
                    textSize = it.choice("textSize", d.lyrics.textSize),
                    textWeight = it.choice("textWeight", d.lyrics.textWeight),
                    textAlign = it.choice("textAlign", d.lyrics.textAlign),
                )
            } ?: d.lyrics,
            playerUi = root.section("playerUi")?.let {
                PlayerUiSettings(
                    showLyrics = it.flag("showLyrics", d.playerUi.showLyrics),
                    flipHintShown = it.flag("flipHintShown", d.playerUi.flipHintShown),
                    recentQueueHintShown = it.flag("recentQueueHintShown", d.playerUi.recentQueueHintShown),
                )
            } ?: d.playerUi,
            backup = root.section("backup")?.let {
                BackupSettings(automatic = it.flag("automatic", d.backup.automatic), folderUri = if ("folderUri" in it) it["folderUri"]?.text() else d.backup.folderUri)
            } ?: d.backup,
            advanced = root.section("advanced")?.let {
                AdvancedSettings(
                    gaplessPlayback = it.flag("gaplessPlayback", d.advanced.gaplessPlayback),
                    audioOffload = it.flag("audioOffload", d.advanced.audioOffload),
                    notificationButtons = it.choice("notificationButtons", d.advanced.notificationButtons),
                    levellingBoostDb = it.number("levellingBoostDb", d.advanced.levellingBoostDb, AdvancedSettings.LEVELLING_BOOST_DB::contains),
                    keepAlbumsGapless = it.flag("keepAlbumsGapless", d.advanced.keepAlbumsGapless),
                )
            } ?: d.advanced,
            nav = root.section("nav")?.let {
                NavSettings(
                    lastTab = it.choice("lastTab", d.nav.lastTab),
                    favouritesTab = it.choice("favouritesTab", d.nav.favouritesTab),
                    setupDone = it.flag("setupDone", d.nav.setupDone),
                    tourSeen = it.flag("tourSeen", d.nav.tourSeen),
                    notificationsDenied = it.flag("notificationsDenied", d.nav.notificationsDenied),
                    notificationDenials = it.number("notificationDenials", d.nav.notificationDenials) { count -> count >= 0 },
                    settingsSections = (it["settingsSections"] as? JsonObject)?.let(::settingsSections) ?: d.nav.settingsSections,
                )
            } ?: d.nav,
            display = root.section("display")?.let {
                DisplaySettings(
                    theme = it.choice("theme", d.display.theme),
                    pureBlack = it.flag("pureBlack", d.display.pureBlack),
                    dock = (it["dock"] as? JsonArray)?.let(::dock) ?: d.display.dock,
                    dockStyle = it.choice("dockStyle", d.display.dockStyle),
                    hideDockOnScroll = it.flag("hideDockOnScroll", d.display.hideDockOnScroll),
                )
            } ?: d.display,
            about = root.section("about")?.let { AboutSettings(checkForUpdates = it.flag("checkForUpdates", d.about.checkForUpdates)) } ?: d.about,
        )
    }

    /**
     * The song pages' orders as stored, each page once: a page this version does not know, a key it does not know, an own
     * order on a page without one, or an entry without a key is left out, so the page opens in its default. The own order
     * is never reversed.
     */
    private fun listSorts(stored: JsonObject): Map<SongList, ListSort> = stored.entries.mapNotNull { (name, value) ->
        val list = SongList.entries.firstOrNull { it.name == name } ?: return@mapNotNull null
        val sort = value as? JsonObject ?: return@mapNotNull null
        val key = sort["key"]?.text() ?: return@mapNotNull null
        list to when (key) {
            OWN_ORDER -> ListSort.OWN.takeIf { list.own } ?: return@mapNotNull null
            else -> ListSort(SortSongs.entries.firstOrNull { it.name == key } ?: return@mapNotNull null, sort.flag("reversed", false))
        }
    }.toMap()

    /** Settings' sections as the user left them: a section this version does not know, or a choice not true or false, is left out. */
    private fun settingsSections(stored: JsonObject): Map<SettingsSection, Boolean> = stored.entries.mapNotNull { (name, open) ->
        val section = SettingsSection.entries.firstOrNull { it.name == name } ?: return@mapNotNull null
        section to ((open as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: return@mapNotNull null)
    }.toMap()

    /**
     * The hidden songs as stored, each by its path with MediaStore's id as a hint (the library finds a song renamed or
     * moved by it, as it finds a playlist's), the first entry of each path; a bare path reads as one without an id.
     */
    private fun hiddenSongs(stored: JsonArray): List<SongRef> = stored.mapNotNull { item ->
        val song = item as? JsonObject ?: return@mapNotNull item.text()?.takeIf(String::isNotBlank)?.let(::SongRef)
        val path = song["path"]?.text()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        SongRef(path, (song["id"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull)
    }.distinctBy { it.path }

    /**
     * "Artists with several names" as stored: a list missing or not a list reads as [base]'s, an empty one is the user's
     * (nothing splits); its entries trimmed, each once in any case, only text.
     */
    private fun artistSplit(stored: JsonObject, base: ArtistSplit): ArtistSplit {
        fun names(key: String, default: List<String>): List<String> =
            (stored[key] as? JsonArray)?.mapNotNull { it.text()?.trim()?.takeIf(String::isNotEmpty) }?.distinctBy { it.lowercase() } ?: default
        return ArtistSplit(stored.flag("enabled", base.enabled), names("separators", base.separators), names("exceptions", base.exceptions))
    }

    /** A page's own order (an album's tracks, a playlist's custom order) as stored. */
    private const val OWN_ORDER: String = "OWN"

    /**
     * The stored tabs in their order: unknown and repeated ones dropped, a missing "shown" read as the tab's default, and
     * the tabs not stored (new in a later version) after them in their default order. A file of [version] 1 had Playlists
     * last by default: it moves right after Songs, shown or hidden as stored, the others in their stored order. The
     * defaults when none is shown.
     */
    private fun tabs(stored: JsonArray, version: Int): List<LibraryTabSetting> {
        val read = stored.mapNotNull { item ->
            val tab = item as? JsonObject ?: return@mapNotNull null
            val name = tab.choiceOrNull<LibraryTab>("tab") ?: return@mapNotNull null
            LibraryTabSetting(name, tab.flag("shown", LibrarySettings.DEFAULT_TABS.first { it.tab == name }.shown))
        }.distinctBy { it.tab }
        val all = (read + LibrarySettings.DEFAULT_TABS.filter { default -> read.none { it.tab == default.tab } })
            .let { if (read.none { it.tab == LibraryTab.BOOKS }) booksAfterFolders(it) else it }
            .let { if (version < PLAYLISTS_AFTER_SONGS) playlistsAfterSongs(it) else it }
        return if (all.any { it.shown }) all else LibrarySettings.DEFAULT_TABS
    }

    /**
     * The bottom bar as stored, every item once in its order: unknown and repeated ones dropped, a missing "shown" read as
     * the item's default, Library always on, and the items not stored (new in a later version) after them in their
     * default order. How many fit on the bar is the bar's own rule (ui/BottomBar.kt), so an item the phone can't offer
     * for a while keeps its place here.
     */
    private fun dock(stored: JsonArray): List<DockItemSetting> {
        val read = stored.mapNotNull { item ->
            val entry = item as? JsonObject ?: return@mapNotNull null
            val tab = entry.choiceOrNull<DockTab>("tab") ?: return@mapNotNull null
            DockItemSetting(tab, tab == DockTab.LIBRARY || entry.flag("shown", DisplaySettings.DEFAULT_DOCK.first { it.tab == tab }.shown))
        }.distinctBy { it.tab }
        return read + DisplaySettings.DEFAULT_DOCK.filter { default -> read.none { it.tab == default.tab } }
    }

    /** [tabs], every tab once, with Playlists moved right after Songs. */
    private fun playlistsAfterSongs(tabs: List<LibraryTabSetting>): List<LibraryTabSetting> {
        val playlists = tabs.first { it.tab == LibraryTab.PLAYLISTS }
        val others = tabs - playlists
        val at = others.indexOfFirst { it.tab == LibraryTab.SONGS } + 1
        return others.take(at) + playlists + others.drop(at)
    }

    /** [tabs], every tab once, with Books moved right after Folders: where a file from before Books had it comes. */
    private fun booksAfterFolders(tabs: List<LibraryTabSetting>): List<LibraryTabSetting> {
        val books = tabs.first { it.tab == LibraryTab.BOOKS }
        val others = tabs - books
        val at = others.indexOfFirst { it.tab == LibraryTab.FOLDERS } + 1
        return others.take(at) + books + others.drop(at)
    }

    /** The version from which Playlists follows Songs in the Library tabs. */
    private const val PLAYLISTS_AFTER_SONGS: Int = 2

    /** The version that made All songs the default of Remember my place, in dev builds only. */
    private const val ALL_SONGS_DEFAULT: Int = 3

    /** The version from which every value of Remember my place is the user's choice, Long songs the default again. */
    private const val LONG_SONGS_AGAIN: Int = 4

    /**
     * Remember my place as stored: the default of the file's [version] (Long songs before [ALL_SONGS_DEFAULT], All songs
     * until [LONG_SONGS_AGAIN]) was no choice, so it reads as [base] (today's default, Long songs, for `settings.json`;
     * the phone's own for a backup), as a missing one does.
     */
    private fun rememberPlace(playback: JsonObject, version: Int, base: RememberPlace): RememberPlace {
        val stored = playback.choice("rememberPlace", base)
        val written = when {
            version < ALL_SONGS_DEFAULT -> RememberPlace.LONG_SONGS
            version < LONG_SONGS_AGAIN -> RememberPlace.ALL_SONGS
            else -> null
        }
        return if (stored == written) base else stored
    }

    /** The picked devices: entries with a whole positive id and a name, the first one of each id. */
    private fun connectDevices(stored: JsonArray): List<ConnectDevice> = stored.mapNotNull { item ->
        val device = item as? JsonObject ?: return@mapNotNull null
        val id = device.number("id", 0) { it > 0 }.takeIf { it > 0 } ?: return@mapNotNull null
        val name = device["name"]?.text()?.trim()?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
        ConnectDevice(id, name)
    }.distinctBy { it.associationId }

    private fun JsonObject.section(key: String): JsonObject? = this[key] as? JsonObject

    /** [this] without its null values: a hidden song without an id hint has no "id". */
    private fun JsonObject.withoutNulls(): JsonObject = JsonObject(filterValues { it !is JsonNull })

    private fun JsonObject.flag(key: String, default: Boolean): Boolean = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: default

    /** A whole number that [valid] accepts; [default] for anything else (a string, a fraction, out of range). */
    private fun JsonObject.number(key: String, default: Int, valid: (Int) -> Boolean): Int =
        (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull?.takeIf(valid) ?: default

    private inline fun <reified E : Enum<E>> JsonObject.choice(key: String, default: E): E = choiceOrNull<E>(key) ?: default

    /** The choice named at [key]; null without one this version knows. */
    private inline fun <reified E : Enum<E>> JsonObject.choiceOrNull(key: String): E? {
        val name = this[key]?.text() ?: return null
        return enumValues<E>().firstOrNull { it.name == name }
    }

    private fun JsonElement.text(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun obj(vararg fields: Pair<String, Any?>): JsonObject = JsonObject(
        fields.associate { (key, value) ->
            key to when (value) {
                null -> JsonNull
                is Boolean -> JsonPrimitive(value)
                is Int -> JsonPrimitive(value)
                is String -> JsonPrimitive(value)
                is JsonElement -> value
                else -> throw IllegalArgumentException("unsupported value for $key")
            }
        },
    )
}
