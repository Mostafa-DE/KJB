package app.lyrify.settings

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

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
)

/** Settings > Playback. */
data class PlaybackSettings(
    /** "Remember my place": which songs resume where the user left them; All songs (user feedback 2026-11-11). */
    val rememberPlace: RememberPlace = RememberPlace.ALL_SONGS,
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

/** "Remember my place": Long songs (10 minutes or longer, the default until version 3), All songs (the default), or Off. */
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
    /** Library > Songs: its sort, and which songs it shows by their lyrics. */
    val songSort: SortSongs = SortSongs.TITLE,
    val songFilter: LyricsFilter = LyricsFilter.ALL,
    val albumSort: SortAlbums = SortAlbums.TITLE,
    val artistSort: SortArtists = SortArtists.NAME,
) {
    companion object {
        /** Songs, Playlists, Albums, Artists and Folders shown; Genres hidden. */
        val DEFAULT_TABS: List<LibraryTabSetting> = LibraryTab.entries.map { LibraryTabSetting(it, shown = it != LibraryTab.GENRES) }
    }
}

enum class LibraryTab { SONGS, PLAYLISTS, ALBUMS, ARTISTS, FOLDERS, GENRES }

data class LibraryTabSetting(val tab: LibraryTab, val shown: Boolean)

enum class SortSongs { TITLE, ARTIST, ALBUM, RECENTLY_ADDED }

/** Songs with synced lyrics, plain lyrics, none, or all of them. */
enum class LyricsFilter { ALL, SYNCED, PLAIN, NONE }

enum class SortAlbums { TITLE, ARTIST, YEAR }

enum class SortArtists { NAME, MOST_SONGS }

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

/** "Notification buttons": a heart beside Next, or Shuffle and Repeat. */
enum class NotificationButtons { FAVOURITE, SHUFFLE_REPEAT }

/** Where the app reopens. Library itself always opens on Songs, so its tab is not kept here. */
data class NavSettings(
    /** The dock tab last open. */
    val lastTab: DockTab = DockTab.LIBRARY,
    /** The Favourites page's tab last open. */
    val favouritesTab: FavouritesTab = FavouritesTab.SONGS,
    /** The first-run screen was finished with Continue, or skipped on a phone that already had both grants. */
    val setupDone: Boolean = false,
)

enum class DockTab { LIBRARY, PLAYLISTS, SEARCH, TOOLS }

/** The Favourites page's tabs: the favourite songs, then the albums, artists, folders and playlists with a heart. */
enum class FavouritesTab { SONGS, ALBUMS, ARTISTS, FOLDERS, PLAYLISTS }

/**
 * The stored form of [Settings]: a JSON object with a version and one object per section, enums by name. Read leniently,
 * field by field: a key this version does not know is ignored, and a field missing, of another type, out of range or
 * naming an unknown choice reads as its default while the fields around it are read as stored. A section that is
 * missing or not an object reads as its defaults. Only text that is not a JSON object at all is not settings.
 * Version 2 put Playlists right after Songs in the Library tabs. Version 3 made All songs the default of Remember my
 * place: an older file's Long songs was the old default (every file wrote it), so it reads as All songs, while Off and
 * All songs are kept. A file without a version reads as this version. A backup's settings are read the same way over the
 * phone's own ([decode]'s base), so what the backup does not carry stays as the phone has it.
 */
object SettingsJson {
    const val VERSION: Int = 3

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
                            "songFilter" to JsonPrimitive(it.songFilter.name),
                            "albumSort" to JsonPrimitive(it.albumSort.name),
                            "artistSort" to JsonPrimitive(it.artistSort.name),
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
                "nav" to settings.nav.let { obj("lastTab" to it.lastTab.name, "favouritesTab" to it.favouritesTab.name, "setupDone" to it.setupDone) },
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
                    songFilter = it.choice("songFilter", d.library.songFilter),
                    albumSort = it.choice("albumSort", d.library.albumSort),
                    artistSort = it.choice("artistSort", d.library.artistSort),
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
                )
            } ?: d.nav,
        )
    }

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
            .let { if (version < PLAYLISTS_AFTER_SONGS) playlistsAfterSongs(it) else it }
        return if (all.any { it.shown }) all else LibrarySettings.DEFAULT_TABS
    }

    /** [tabs], every tab once, with Playlists moved right after Songs. */
    private fun playlistsAfterSongs(tabs: List<LibraryTabSetting>): List<LibraryTabSetting> {
        val playlists = tabs.first { it.tab == LibraryTab.PLAYLISTS }
        val others = tabs - playlists
        val at = others.indexOfFirst { it.tab == LibraryTab.SONGS } + 1
        return others.take(at) + playlists + others.drop(at)
    }

    /** The version from which Playlists follows Songs in the Library tabs. */
    private const val PLAYLISTS_AFTER_SONGS: Int = 2

    /** The version from which Long songs in Remember my place is the user's choice, not the old default. */
    private const val ALL_SONGS_DEFAULT: Int = 3

    /**
     * Remember my place as stored: the old default (Long songs before [ALL_SONGS_DEFAULT]) was no choice, so it reads as
     * [base] (today's default for `settings.json`, the phone's own for a backup), as a missing one does.
     */
    private fun rememberPlace(playback: JsonObject, version: Int, base: RememberPlace): RememberPlace {
        val stored = playback.choice("rememberPlace", base)
        return if (version < ALL_SONGS_DEFAULT && stored == RememberPlace.LONG_SONGS) base else stored
    }

    /** The picked devices: entries with a whole positive id and a name, the first one of each id. */
    private fun connectDevices(stored: JsonArray): List<ConnectDevice> = stored.mapNotNull { item ->
        val device = item as? JsonObject ?: return@mapNotNull null
        val id = device.number("id", 0) { it > 0 }.takeIf { it > 0 } ?: return@mapNotNull null
        val name = device["name"]?.text()?.trim()?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
        ConnectDevice(id, name)
    }.distinctBy { it.associationId }

    private fun JsonObject.section(key: String): JsonObject? = this[key] as? JsonObject

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
