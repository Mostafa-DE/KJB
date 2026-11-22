package app.lyrify.settings

import app.lyrify.library.SongRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.YearMonth
import org.junit.rules.TemporaryFolder
import java.io.File

/** The 3.0 settings: their defaults, and what a stored file of any age or shape reads as. Made-up names only. */
class SettingsStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val logs = mutableListOf<String>()

    @After
    fun stop() = scope.cancel()

    private val backupDir: File by lazy { folder.newFolder("files") }

    private fun store(file: File) = settingsStore(file, backupDir, scope, clock = { NOW }) { synchronized(logs) { logs += it } }

    private fun settingsFile(text: String): File = File(folder.root, "settings.json").apply { writeText(text) }

    /** Every field away from its default. */
    private val changed = Settings(
        playback = PlaybackSettings(
            rememberPlace = RememberPlace.ALL_SONGS,
            headsetButtons = false,
            skipShortClips = false,
            countPlayAfterPercent = 70,
            skipBackSeconds = 15,
            skipForwardSeconds = 5,
            connectDevices = listOf(ConnectDevice(4, "Example Buds"), ConnectDevice(9, "Car Kit Demo")),
        ),
        audio = AudioSettings(levelling = Levelling.ALBUM, crossfadeSeconds = 6),
        library = LibrarySettings(
            tabs = listOf(
                LibraryTabSetting(LibraryTab.ALBUMS, true),
                LibraryTabSetting(LibraryTab.SONGS, true),
                LibraryTabSetting(LibraryTab.GENRES, true),
                LibraryTabSetting(LibraryTab.ARTISTS, false),
                LibraryTabSetting(LibraryTab.FOLDERS, true),
                LibraryTabSetting(LibraryTab.BOOKS, false),
                LibraryTabSetting(LibraryTab.PLAYLISTS, false),
            ),
            foldersToSkip = listOf("Recordings/Calls", "Music/Voice Notes"),
            showFileNames = true,
            songSort = SortSongs.RECENTLY_ADDED,
            songReversed = true,
            songFilter = LyricsFilter.PLAIN,
            albumSort = SortAlbums.YEAR,
            albumReversed = true,
            artistSort = SortArtists.MOST_SONGS,
            artistReversed = true,
            listSorts = mapOf(
                SongList.ALBUM to ListSort(SortSongs.FILE_NAME, reversed = true),
                SongList.PLAYLIST to ListSort(SortSongs.PLAY_COUNT),
                SongList.MOST_PLAYED to ListSort(SortSongs.TITLE),
                SongList.FAVOURITES to ListSort.OWN,
            ),
            listPeriods = mapOf(SongList.RECENTLY_ADDED to ListPeriod.ALL_TIME, SongList.RECENTLY_PLAYED to ListPeriod.DAYS_365, SongList.MOST_PLAYED to ListPeriod.DAYS_60),
            hiddenSongs = listOf(SongRef("/storage/emulated/0/Music/Odds/Glass Steps.mp3", 13), SongRef("/storage/emulated/0/Music/Singles/Copper Moon.mp3")),
            artistSplit = ArtistSplit(enabled = false, separators = listOf(";", "&", "with"), exceptions = listOf("Rowan & Fennick")),
            opensOn = LibraryTab.ALBUMS,
            findSimilarOnline = false,
        ),
        lyrics = LyricsSettings(showWhenLocked = true, textSize = LyricsSize.XL, textWeight = LyricsWeight.REGULAR, textAlign = LyricsAlign.CENTRE),
        playerUi = PlayerUiSettings(showLyrics = true, flipHintShown = true, recentQueueHintShown = true),
        backup = BackupSettings(automatic = true, folderUri = "content://com.android.externalstorage.documents/tree/primary%3ABackups"),
        advanced = AdvancedSettings(
            gaplessPlayback = false,
            audioOffload = true,
            notificationButtons = NotificationButtons.SHUFFLE_REPEAT,
            levellingBoostDb = -4,
            keepAlbumsGapless = false,
        ),
        nav = NavSettings(
            lastTab = DockTab.PLAYLISTS,
            favouritesTab = FavouritesTab.ARTISTS,
            settingsSections = mapOf(SettingsSection.LYRICS to true, SettingsSection.PLAYBACK to false),
            recapSeen = YearMonth.of(2026, 10),
        ),
        display = DisplaySettings(
            theme = ThemeChoice.DARK,
            pureBlack = true,
            dock = listOf(
                DockItemSetting(DockTab.FAVOURITES, true),
                DockItemSetting(DockTab.LIBRARY, true),
                DockItemSetting(DockTab.QUEUES, true),
                DockItemSetting(DockTab.PLAYLISTS, false),
                DockItemSetting(DockTab.BOOKS, true),
                DockItemSetting(DockTab.DOWNLOADS, false),
                DockItemSetting(DockTab.TOOLS, false),
                DockItemSetting(DockTab.INSIGHTS, true),
                DockItemSetting(DockTab.HUDDLE, false),
                DockItemSetting(DockTab.SEARCH, true),
            ),
            dockStyle = DockStyle.SLIM,
            hideDockOnScroll = true,
        ),
        suggestions = SuggestionSettings(
            autoplay = false,
            adventure = Adventure.DISCOVER,
            showReasons = false,
            forYouCard = false,
            forYouHiddenUntil = NOW + 3_600_000,
            never = listOf(HeldSong(SongRef("/storage/emulated/0/Music/Odds/Glass Steps.mp3", 13), NOW - 86_400_000)),
            notNow = listOf(HeldSong(SongRef("/storage/emulated/0/Music/Singles/Copper Moon.mp3"), NOW + 30 * 86_400_000L)),
        ),
    )

    // Defaults

    @Test
    fun `every setting starts at the default the specs give`() {
        val s = Settings()
        // Remember my place: Long songs again (user, 2026-11-12), after All songs from 2026-11-11.
        assertEquals(PlaybackSettings(RememberPlace.LONG_SONGS, true, true, 50, 30, 10), s.playback)
        assertEquals(emptyList<ConnectDevice>(), s.playback.connectDevices)
        assertEquals(AudioSettings(Levelling.AUTO, crossfadeSeconds = 0), s.audio)
        // What Settings > Library tabs lists: Playlists right after Songs.
        assertEquals(
            listOf(LibraryTab.SONGS, LibraryTab.PLAYLISTS, LibraryTab.ALBUMS, LibraryTab.ARTISTS, LibraryTab.FOLDERS, LibraryTab.BOOKS, LibraryTab.GENRES),
            s.library.tabs.map { it.tab },
        )
        assertEquals(listOf(LibraryTab.GENRES), s.library.tabs.filterNot { it.shown }.map { it.tab })
        assertEquals(emptyList<String>(), s.library.foldersToSkip)
        assertFalse(s.library.showFileNames)
        // Find similar artists online: on (user, 2026-11-22).
        assertTrue(s.library.findSimilarOnline)
        assertEquals(LyricsSettings(false, LyricsSize.M, LyricsWeight.BOLD, LyricsAlign.LEFT), s.lyrics)
        assertEquals(PlayerUiSettings(showLyrics = false, flipHintShown = false, recentQueueHintShown = false), s.playerUi)
        assertEquals(BackupSettings(automatic = false, folderUri = null), s.backup)
        assertEquals(AdvancedSettings(true, false, NotificationButtons.FAVOURITE, levellingBoostDb = 0, keepAlbumsGapless = true), s.advanced)
        assertEquals(NavSettings(DockTab.LIBRARY, FavouritesTab.SONGS, setupDone = false), s.nav)
        // Theme as the phone is, Pure black off (features 2026-11-15, item 3).
        assertEquals(DisplaySettings(ThemeChoice.SYSTEM, pureBlack = false), s.display)
        // The bottom bar as before it could change: Library, Playlists, Search and Tools, Standard, never hiding (3.1).
        assertEquals(DockTab.entries, s.display.dock.map { it.tab })
        assertEquals(listOf(DockTab.LIBRARY, DockTab.PLAYLISTS, DockTab.SEARCH, DockTab.TOOLS), s.display.dock.filter { it.shown }.map { it.tab })
        assertEquals(DockStyle.STANDARD, s.display.dockStyle)
        assertFalse(s.display.hideDockOnScroll)
        assertEquals(6, DisplaySettings.MAX_DOCK)
        // Library opens on the first tab in its order until the user picks one.
        assertNull(s.library.opensOn)
        // Every list in its usual direction, the song pages in their own order or default key; nothing hidden.
        assertEquals(listOf(false, false, false), listOf(s.library.songReversed, s.library.albumReversed, s.library.artistReversed))
        assertEquals(SongList.entries.associateWith { it.default }, SongList.entries.associateWith { s.library.sortOf(it) })
        assertEquals(emptyList<SongRef>(), s.library.hiddenSongs)
        // Artists with several names: on, commas split and "&" does not, AC/DC and Tyler, The Creator never split (2026-11-18).
        assertEquals(ArtistSplit(true, listOf(";", "/", ",", "feat.", "ft.", "featuring"), listOf("AC/DC", "Tyler, The Creator")), s.library.artistSplit)
        // Suggestions (v4): Autoplay on, the mix where Mix starts, reasons shown, the For you card shown, nothing held out.
        assertEquals(SuggestionSettings(true, Adventure.MIX, true, true, null, emptyList(), emptyList()), s.suggestions)
        assertEquals((10..100 step 10).toList(), PlaybackSettings.PLAY_SHARES)
        assertEquals(listOf(5, 10, 15, 30), PlaybackSettings.SKIP_SECONDS)
    }

    @Test
    fun `no file, or an empty object, reads as the defaults`() = runBlocking {
        assertEquals(Settings(), store(File(folder.root, "settings.json")).data.first())
        assertEquals(Settings(), SettingsJson.decode("{}"))
    }

    // The stored form

    @Test
    fun `every setting survives a round trip`() {
        assertEquals(changed, SettingsJson.decode(SettingsJson.encode(changed)))
        assertEquals(Settings(), SettingsJson.decode(SettingsJson.encode(Settings())))
        assertTrue(""""version":${SettingsJson.VERSION}""" in SettingsJson.encode(Settings()))
    }

    @Test
    fun `a change is stored in the file`() = runBlocking {
        val file = File(folder.root, "settings.json")
        val store = store(file)
        store.updateData { it.copy(playback = changed.playback) }
        store.updateData { it.copy(nav = changed.nav) }

        assertEquals(Settings(playback = changed.playback, nav = changed.nav), SettingsJson.decode(file.readText()))
    }

    @Test
    fun `keys and values this version does not know are ignored, and the rest is read`() {
        val text = """{"version":9,"future":{"a":1},"playback":{"rememberPlace":"FOREVER","headsetButtons":false,"spin":true},""" +
            """"audio":{"levelling":"SONG","crossfadeSeconds":4,"curve":"s"},"nav":{"lastTab":"RADIO","libraryTab":"GENRES"}}"""
        val s = SettingsJson.decode(text)

        assertEquals(PlaybackSettings(headsetButtons = false), s?.playback)
        assertEquals(AudioSettings(Levelling.SONG, 4), s?.audio)
        assertEquals(NavSettings(), s?.nav)
    }

    @Test
    fun `a value out of range or of another type reads as its default, the fields around it as stored`() {
        val text = """{"playback":{"countPlayAfterPercent":35,"skipBackSeconds":7,"skipForwardSeconds":15,"skipShortClips":false},""" +
            """"audio":{"levelling":"OFF","crossfadeSeconds":13},"advanced":{"levellingBoostDb":7,"audioOffload":true}}"""
        val s = SettingsJson.decode(text)

        assertEquals(PlaybackSettings(skipShortClips = false, skipForwardSeconds = 15), s?.playback)
        assertEquals(AudioSettings(Levelling.OFF, 0), s?.audio)
        assertEquals(AdvancedSettings(audioOffload = true), s?.advanced)

        // Each bad value on its own, against a stored good one.
        listOf(
            """"countPlayAfterPercent":0""", """"countPlayAfterPercent":110""", """"countPlayAfterPercent":"70"""",
            """"countPlayAfterPercent":70.5""", """"countPlayAfterPercent":true""", """"countPlayAfterPercent":null""",
        ).forEach { bad ->
            assertEquals(bad, 50, SettingsJson.decode("""{"playback":{$bad}}""")?.playback?.countPlayAfterPercent)
        }
        assertEquals(70, SettingsJson.decode("""{"playback":{"countPlayAfterPercent":70}}""")?.playback?.countPlayAfterPercent)
        assertEquals(10, SettingsJson.decode("""{"playback":{"skipForwardSeconds":20}}""")?.playback?.skipForwardSeconds)
        assertEquals(0, SettingsJson.decode("""{"audio":{"crossfadeSeconds":-1}}""")?.audio?.crossfadeSeconds)
        assertEquals(12, SettingsJson.decode("""{"audio":{"crossfadeSeconds":12}}""")?.audio?.crossfadeSeconds)
        assertEquals(0, SettingsJson.decode("""{"advanced":{"levellingBoostDb":-7}}""")?.advanced?.levellingBoostDb)
        assertEquals(-6, SettingsJson.decode("""{"advanced":{"levellingBoostDb":-6}}""")?.advanced?.levellingBoostDb)
        assertTrue(SettingsJson.decode("""{"advanced":{"gaplessPlayback":"no"}}""")?.advanced?.gaplessPlayback == true)
        assertNull(SettingsJson.decode("""{"backup":{"automatic":true,"folderUri":5}}""")?.backup?.folderUri)
    }

    @Test
    fun `a missing section, or one that is not an object, reads as its defaults`() {
        val text = """{"library":{"showFileNames":true},"playback":5,"audio":[1],"lyrics":"big"}"""
        val s = SettingsJson.decode(text)

        // Changed on purpose (RP1): a library section from before the period chips opens Recently played on all time.
        assertEquals(Settings(library = LibrarySettings(showFileNames = true, listPeriods = mapOf(SongList.RECENTLY_PLAYED to ListPeriod.ALL_TIME))), s)
    }

    @Test
    fun `library tabs keep their order, and a tab this version does not know is dropped`() {
        fun tabs(json: String) = SettingsJson.decode("""{"library":{"tabs":$json}}""")?.library?.tabs

        // Unknown and repeated tabs dropped; the tabs not stored follow in their default order, shown as by default.
        assertEquals(
            listOf(
                LibraryTabSetting(LibraryTab.PLAYLISTS, true),
                LibraryTabSetting(LibraryTab.SONGS, false),
                LibraryTabSetting(LibraryTab.ALBUMS, true),
                LibraryTabSetting(LibraryTab.ARTISTS, true),
                LibraryTabSetting(LibraryTab.FOLDERS, true),
                LibraryTabSetting(LibraryTab.BOOKS, true),
                LibraryTabSetting(LibraryTab.GENRES, false),
            ),
            tabs(
                """[{"tab":"PLAYLISTS","shown":true},{"tab":"PODCASTS","shown":true},{"tab":"SONGS","shown":false},""" +
                    """{"tab":"PLAYLISTS","shown":false},5,{"shown":true}]""",
            ),
        )
        // A tab without a usable "shown" is shown as by default.
        assertEquals(LibraryTabSetting(LibraryTab.GENRES, false), tabs("""[{"tab":"GENRES","shown":"yes"}]""")?.first())
        // At least one tab is shown: otherwise the defaults.
        assertEquals(LibrarySettings.DEFAULT_TABS, tabs(LibraryTab.entries.joinToString(",", "[", "]") { """{"tab":"$it","shown":false}""" }))
        assertEquals(LibrarySettings.DEFAULT_TABS, tabs("""{"tab":"SONGS"}"""))
    }

    @Test
    fun `a version 1 file's Library tabs take Playlists right after Songs, each shown or hidden as stored`() {
        fun tabs(version: Int, order: String) = SettingsJson.decode("""{"version":$version,"library":{"tabs":[$order]}}""")?.library?.tabs
        fun tab(name: LibraryTab, shown: Boolean) = """{"tab":"$name","shown":$shown}"""

        // 3.0's first order, Playlists last: now the new order.
        val first = listOf(LibraryTab.SONGS, LibraryTab.ALBUMS, LibraryTab.ARTISTS, LibraryTab.FOLDERS, LibraryTab.PLAYLISTS, LibraryTab.GENRES)
        assertEquals(LibrarySettings.DEFAULT_TABS, tabs(1, first.joinToString(",") { tab(it, it != LibraryTab.GENRES) }))
        // An order of the user's own, with tabs hidden: the others keep their order, Playlists follows Songs and stays hidden.
        val own = listOf(
            tab(LibraryTab.ALBUMS, true), tab(LibraryTab.SONGS, false), tab(LibraryTab.GENRES, true),
            tab(LibraryTab.ARTISTS, false), tab(LibraryTab.FOLDERS, true), tab(LibraryTab.PLAYLISTS, false),
        ).joinToString(",")
        assertEquals(
            listOf(
                LibraryTabSetting(LibraryTab.ALBUMS, true),
                LibraryTabSetting(LibraryTab.SONGS, false),
                LibraryTabSetting(LibraryTab.PLAYLISTS, false),
                LibraryTabSetting(LibraryTab.GENRES, true),
                LibraryTabSetting(LibraryTab.ARTISTS, false),
                LibraryTabSetting(LibraryTab.FOLDERS, true),
                LibraryTabSetting(LibraryTab.BOOKS, true),
            ),
            tabs(1, own),
        )
        // From version 2 on, Playlists stays where the user put it.
        val last = first.joinToString(",") { tab(it, true) }
        assertEquals(first.take(4) + LibraryTab.BOOKS + first.drop(4), tabs(SettingsJson.VERSION, last)?.map { it.tab })
        assertTrue(""""version":${SettingsJson.VERSION}""" in SettingsJson.encode(Settings()))
    }

    @Test
    fun `Remember my place left at a default it no longer has reads Long songs, and a choice the user made is kept`() {
        fun place(version: Int?, stored: String?): RememberPlace? {
            val field = stored?.let { """"rememberPlace":"$it"""" } ?: """"headsetButtons":false"""
            val head = version?.let { """"version":$it,""" } ?: ""
            return SettingsJson.decode("""{$head"playback":{$field}}""")?.playback?.rememberPlace
        }

        assertEquals(4, SettingsJson.VERSION)
        // Version 3 (dev builds only) wrote All songs, its default, into every file: no choice, so Long songs again.
        assertEquals(RememberPlace.LONG_SONGS, place(3, "ALL_SONGS"))
        // Its other values were choices the user made.
        assertEquals(RememberPlace.LONG_SONGS, place(3, "LONG_SONGS"))
        assertEquals(RememberPlace.OFF, place(3, "OFF"))
        // From version 4, every value is the user's own, All songs too.
        assertEquals(RememberPlace.ALL_SONGS, place(4, "ALL_SONGS"))
        assertEquals(RememberPlace.LONG_SONGS, place(4, "LONG_SONGS"))
        assertEquals(RememberPlace.OFF, place(4, "OFF"))
        assertEquals(RememberPlace.ALL_SONGS, place(null, "ALL_SONGS"))
        // Before version 3 the steps stay: Long songs was the default then, All songs and Off choices.
        assertEquals(RememberPlace.LONG_SONGS, place(1, "LONG_SONGS"))
        assertEquals(RememberPlace.LONG_SONGS, place(2, "LONG_SONGS"))
        assertEquals(RememberPlace.ALL_SONGS, place(2, "ALL_SONGS"))
        assertEquals(RememberPlace.OFF, place(2, "OFF"))
        // Missing or unknown: the default.
        assertEquals(RememberPlace.LONG_SONGS, place(3, null))
        assertEquals(RememberPlace.LONG_SONGS, place(4, "FOREVER"))
        // Written back, a choice stays what it is.
        val all = Settings(playback = PlaybackSettings(rememberPlace = RememberPlace.ALL_SONGS))
        assertEquals(all, SettingsJson.decode(SettingsJson.encode(all)))
    }

    @Test
    fun `a version 3 settings file on the phone reads Long songs, and is written back as version 4`() = runBlocking {
        val file = settingsFile("""{"version":3,"playback":{"rememberPlace":"ALL_SONGS","skipBackSeconds":15}}""")
        val store = store(file)

        assertEquals(PlaybackSettings(rememberPlace = RememberPlace.LONG_SONGS, skipBackSeconds = 15), store.data.first().playback)
        // The next change stores version 4, where All songs is a choice again.
        store.updateData { it.copy(playback = it.playback.copy(rememberPlace = RememberPlace.ALL_SONGS)) }
        assertTrue(file.readText().contains(""""version":4"""))
        assertEquals(RememberPlace.ALL_SONGS, SettingsJson.decode(file.readText())?.playback?.rememberPlace)
    }

    @Test
    fun `the last dock tab reads back, Playlists too, and the old Library tab is not kept`() {
        fun nav(json: String) = SettingsJson.decode("""{"version":1,"nav":$json}""")?.nav

        assertEquals(NavSettings(DockTab.PLAYLISTS, FavouritesTab.FOLDERS), nav("""{"lastTab":"PLAYLISTS","libraryTab":"PLAYLISTS","favouritesTab":"FOLDERS"}"""))
        assertEquals(DockTab.SEARCH, nav("""{"lastTab":"SEARCH","libraryTab":"ALBUMS"}""")?.lastTab)
        assertEquals(DockTab.TOOLS, nav("""{"lastTab":"TOOLS"}""")?.lastTab)
        assertEquals(NavSettings(), nav("""{"lastTab":"NOW","favouritesTab":"GENRES"}"""))
        assertFalse("libraryTab" in SettingsJson.encode(changed))
    }

    @Test
    fun `the month whose recap card was opened or closed reads back, none from a file before Insights or a damaged one`() {
        fun nav(json: String) = SettingsJson.decode("""{"nav":$json}""")?.nav

        assertEquals(YearMonth.of(2026, 10), nav("""{"recapSeen":"2026-10"}""")?.recapSeen)
        assertNull(nav("""{"lastTab":"TOOLS"}""")?.recapSeen)
        assertNull(nav("""{"recapSeen":"October"}""")?.recapSeen)
        assertNull(nav("""{"recapSeen":202610}""")?.recapSeen)
        assertNull(Settings().nav.recapSeen)
        assertTrue(""""recapSeen":"2026-10"""" in SettingsJson.encode(changed))
        // A backup from before Insights, restored over the phone's settings, keeps the month the phone closed (Grok b-ui).
        val phone = Settings(nav = NavSettings(recapSeen = YearMonth.of(2026, 10)))
        assertEquals(YearMonth.of(2026, 10), SettingsJson.decode("""{"nav":{"lastTab":"TOOLS"}}""", phone)?.nav?.recapSeen)
    }

    @Test
    fun `folders to skip keep each folder once, and only folder names`() {
        val s = SettingsJson.decode("""{"library":{"foldersToSkip":["Recordings/Calls",5,"","  ","Recordings/Calls",null,"Music/Voice Notes"]}}""")

        assertEquals(listOf("Recordings/Calls", "Music/Voice Notes"), s?.library?.foldersToSkip)
    }

    @Test
    fun `picked devices for Start when connected keep one entry per association, and only whole entries`() {
        val text = """{"playback":{"connectDevices":[{"id":4,"name":"Example Buds"},{"id":"9","name":"Car Kit Demo"},{"id":7},""" +
            """{"id":8,"name":"  "},{"id":0,"name":"Zero Demo"},{"id":2.5,"name":"Half Demo"},5,{"id":4,"name":"Example Buds Again"},""" +
            """{"id":11,"name":" Desk Speaker Demo "}]}}"""

        assertEquals(
            listOf(ConnectDevice(4, "Example Buds"), ConnectDevice(11, "Desk Speaker Demo")),
            SettingsJson.decode(text)?.playback?.connectDevices,
        )
        assertEquals(emptyList<ConnectDevice>(), SettingsJson.decode("""{"playback":{"connectDevices":{"id":4}}}""")?.playback?.connectDevices)
    }

    @Test
    fun `a settings file from before Start when connected came back still reads`() {
        // 3.0's first settings had a switch here; P2 dropped it. Neither carries a device.
        val switched = """{"version":1,"playback":{"rememberPlace":"ALL_SONGS","headsetButtons":false,"startWhenConnected":true,""" +
            """"skipShortClips":false,"countPlayAfterPercent":70,"skipBackSeconds":15,"skipForwardSeconds":5},"nav":{"lastTab":"TOOLS"}}"""
        val dropped = """{"version":1,"playback":{"rememberPlace":"ALL_SONGS","headsetButtons":false,""" +
            """"skipShortClips":false,"countPlayAfterPercent":70,"skipBackSeconds":15,"skipForwardSeconds":5},"nav":{"lastTab":"TOOLS"}}"""
        val expected = Settings(playback = changed.playback.copy(connectDevices = emptyList()), nav = NavSettings(lastTab = DockTab.TOOLS))

        assertEquals(expected, SettingsJson.decode(switched))
        assertEquals(expected, SettingsJson.decode(dropped))
        // Written back, the old switch is gone and the devices are stored.
        assertFalse("startWhenConnected" in SettingsJson.encode(expected))
        assertTrue("connectDevices" in SettingsJson.encode(expected))
    }

    // Theme, sorts and hidden songs (features 2026-11-15, items 3, 5 and 6)

    @Test
    fun `the theme and Pure black are stored, and a theme this version does not know reads as the phone's`() = runBlocking {
        val file = File(folder.root, "settings.json")
        val store = store(file)
        store.updateData { it.copy(display = DisplaySettings(ThemeChoice.LIGHT, pureBlack = true)) }

        assertEquals(DisplaySettings(ThemeChoice.LIGHT, pureBlack = true), SettingsJson.decode(file.readText())?.display)
        assertEquals(DisplaySettings(ThemeChoice.LIGHT, pureBlack = true), store.data.first().display)
        fun display(json: String) = SettingsJson.decode("""{"display":$json}""")?.display
        assertEquals(DisplaySettings(ThemeChoice.DARK), display("""{"theme":"DARK"}"""))
        assertEquals(DisplaySettings(ThemeChoice.SYSTEM, pureBlack = true), display("""{"theme":"SEPIA","pureBlack":true}"""))
        assertEquals(DisplaySettings(ThemeChoice.DARK), display("""{"theme":"DARK","pureBlack":"yes"}"""))
        assertEquals(DisplaySettings(), display("[]"))
        // A file from before the choice reads as the phone's theme.
        assertEquals(DisplaySettings(), SettingsJson.decode("""{"version":4,"library":{"showFileNames":true}}""")?.display)
    }

    @Test
    fun `each list keeps its sort and direction, and a stored order this version cannot use reads as that list's default`() {
        val text = """{"library":{"songSort":"PLAY_COUNT","songReversed":true,"albumSort":"DURATION","albumReversed":"no",""" +
            """"artistSort":"LAST_PLAYED","artistReversed":true,"listSorts":{"ALBUM":{"key":"OWN"},"ARTIST":{"key":"TITLE","reversed":true},""" +
            """"GENRE":{"key":"LOUDNESS"},"FOLDER":{"key":"OWN","reversed":true},"PLAYLIST":{"key":"OWN","reversed":true},""" +
            """"FAVOURITES":{"key":"YEAR"},"MOST_PLAYED":{"reversed":true},"RADIO":{"key":"TITLE"},"RECENTLY_ADDED":"TITLE"}}}"""
        val library = SettingsJson.decode(text)!!.library

        assertEquals(SortSongs.PLAY_COUNT to true, library.songSort to library.songReversed)
        assertEquals(SortAlbums.DURATION to false, library.albumSort to library.albumReversed)
        assertEquals(SortArtists.LAST_PLAYED to true, library.artistSort to library.artistReversed)
        assertEquals(ListSort.OWN, library.sortOf(SongList.ALBUM))
        assertEquals(ListSort(SortSongs.TITLE, reversed = true), library.sortOf(SongList.ARTIST))
        // An unknown key, an own order the page does not have, or no key at all: the page's default.
        assertEquals(SongList.GENRE.default, library.sortOf(SongList.GENRE))
        assertEquals(SongList.FOLDER.default, library.sortOf(SongList.FOLDER))
        assertEquals(SongList.MOST_PLAYED.default, library.sortOf(SongList.MOST_PLAYED))
        assertEquals(SongList.RECENTLY_ADDED.default, library.sortOf(SongList.RECENTLY_ADDED))
        // The own order is never reversed.
        assertEquals(ListSort.OWN, library.sortOf(SongList.PLAYLIST))
        assertEquals(ListSort(SortSongs.YEAR), library.sortOf(SongList.FAVOURITES))
        assertEquals(setOf(SongList.ALBUM, SongList.ARTIST, SongList.PLAYLIST, SongList.FAVOURITES), library.listSorts.keys)
    }

    @Test
    fun `a sort stored before the new keys still reads, Recently added among them`() {
        val library = SettingsJson.decode("""{"version":4,"library":{"songSort":"RECENTLY_ADDED","albumSort":"YEAR","artistSort":"MOST_SONGS"}}""")!!.library

        // Changed on purpose (RP1): a file from before the period chips opens Recently played on all time, as it was.
        val before = mapOf(SongList.RECENTLY_PLAYED to ListPeriod.ALL_TIME)
        assertEquals(LibrarySettings(songSort = SortSongs.RECENTLY_ADDED, albumSort = SortAlbums.YEAR, artistSort = SortArtists.MOST_SONGS, listPeriods = before), library)
    }

    // The period chips of Recently added, Recently played and Most played (RP1)

    @Test
    fun `Recently added starts on the past 30 days, Recently played on the past 15 and Most played on all time`() {
        val library = Settings().library
        assertEquals(ListPeriod.DAYS_30, library.periodOf(SongList.RECENTLY_ADDED))
        assertEquals(ListPeriod.DAYS_15, library.periodOf(SongList.RECENTLY_PLAYED))
        assertEquals(ListPeriod.ALL_TIME, library.periodOf(SongList.MOST_PLAYED))
        // The pages without chips show all of their songs.
        assertEquals(ListPeriod.ALL_TIME, library.periodOf(SongList.ALBUM))
        assertEquals(ListPeriod.ALL_TIME, library.periodOf(SongList.FAVOURITES))
    }

    @Test
    fun `each list keeps the chip it was left on, the others their first`() {
        val picked = Settings(library = LibrarySettings(listPeriods = mapOf(SongList.RECENTLY_ADDED to ListPeriod.ALL_TIME, SongList.MOST_PLAYED to ListPeriod.DAYS_60)))
        val read = SettingsJson.decode(SettingsJson.encode(picked))!!.library

        assertEquals(ListPeriod.ALL_TIME, read.periodOf(SongList.RECENTLY_ADDED))
        assertEquals(ListPeriod.DAYS_15, read.periodOf(SongList.RECENTLY_PLAYED))
        assertEquals(ListPeriod.DAYS_60, read.periodOf(SongList.MOST_PLAYED))
    }

    @Test
    fun `a settings file from before the chips opens each list as it was, Recently played on all time, and a new phone's on the past 15 days`() {
        val upgraded = SettingsJson.decode("""{"version":4,"library":{"showFileNames":true}}""")!!.library
        assertEquals(ListPeriod.DAYS_30, upgraded.periodOf(SongList.RECENTLY_ADDED))
        assertEquals(ListPeriod.ALL_TIME, upgraded.periodOf(SongList.RECENTLY_PLAYED))
        assertEquals(ListPeriod.ALL_TIME, upgraded.periodOf(SongList.MOST_PLAYED))
        // Once a chip is picked there, it is kept like any other.
        val moved = SettingsJson.decode(SettingsJson.encode(Settings(library = upgraded.copy(listPeriods = upgraded.listPeriods + (SongList.RECENTLY_PLAYED to ListPeriod.DAYS_30)))))!!
        assertEquals(ListPeriod.DAYS_30, moved.library.periodOf(SongList.RECENTLY_PLAYED))
        // A new phone's file is written with the chips, none picked yet.
        assertEquals(ListPeriod.DAYS_15, SettingsJson.decode(SettingsJson.encode(Settings()))!!.library.periodOf(SongList.RECENTLY_PLAYED))
        // A backup from before the chips keeps a chip the phone was left on.
        val phone = Settings(library = LibrarySettings(listPeriods = mapOf(SongList.RECENTLY_PLAYED to ListPeriod.DAYS_60)))
        assertEquals(ListPeriod.DAYS_60, SettingsJson.decode("""{"version":4,"library":{"showFileNames":true}}""", phone)!!.library.periodOf(SongList.RECENTLY_PLAYED))
    }

    @Test
    fun `a chip stored for a page without chips, or one this version does not know, is left out`() {
        val text = """{"library":{"listPeriods":{"ALBUM":"DAYS_15","RECENTLY_ADDED":"DAYS_7","MOST_PLAYED":60,"RADIO":"ALL_TIME","RECENTLY_PLAYED":"DAYS_365"}}}"""
        assertEquals(mapOf(SongList.RECENTLY_PLAYED to ListPeriod.DAYS_365), SettingsJson.decode(text)!!.library.listPeriods)
    }

    @Test
    fun `hidden songs keep each path once with its id hint, in the order hidden, and only whole entries`() {
        val b = "/storage/emulated/0/Music/b.mp3"
        val a = "/storage/emulated/0/Music/a.mp3"
        val text = """{"library":{"hiddenSongs":[{"path":"$b","id":7},4,{"path":""},{"id":9},"$a",{"path":"$b","id":8},null,""" +
            """{"path":"/storage/emulated/0/Music/c.mp3","id":"x"}]}}"""

        assertEquals(listOf(SongRef(b, 7), SongRef(a), SongRef("/storage/emulated/0/Music/c.mp3")), SettingsJson.decode(text)?.library?.hiddenSongs)
        assertEquals(emptyList<SongRef>(), SettingsJson.decode("""{"library":{"hiddenSongs":"a.mp3"}}""")?.library?.hiddenSongs)
        assertTrue(""""hiddenSongs":[{"path":"$b","id":7}]""" in SettingsJson.encode(Settings(library = LibrarySettings(hiddenSongs = listOf(SongRef(b, 7))))))
    }

    @Test
    fun `artists with several names keep each separator and exception once in any case, and only names`() {
        val text = """{"library":{"artistSplit":{"enabled":"no","separators":[" & ","",3,"&","x",null,"WITH","with"],"exceptions":"AC/DC"}}}"""

        assertEquals(ArtistSplit(enabled = true, separators = listOf("&", "x", "WITH"), exceptions = ArtistSplit.DEFAULT_EXCEPTIONS), SettingsJson.decode(text)?.library?.artistSplit)
        // Every separator taken away is a choice: none split.
        assertEquals(ArtistSplit(enabled = false, separators = emptyList()), SettingsJson.decode("""{"library":{"artistSplit":{"enabled":false,"separators":[]}}}""")?.library?.artistSplit)
        assertEquals(ArtistSplit(), SettingsJson.decode("""{"library":{"artistSplit":[]}}""")?.library?.artistSplit)
    }

    // A file that is not settings at all

    @Test
    fun `text that is not a JSON object is not settings`() {
        listOf("not json", "[]", "5", "\"settings\"", "").forEach { assertNull(it, SettingsJson.decode(it)) }
    }

    @Test
    fun `a file that is not settings is kept aside before the app starts from the defaults`() = runBlocking {
        val broken = """{"playback":{"headsetButtons":fal"""
        val file = settingsFile(broken)

        assertEquals(Settings(), store(file).data.first())

        val copy = File(backupDir, "settings-corrupt-$NOW.json")
        assertTrue(copy.isFile)
        assertArrayEquals(broken.toByteArray(), copy.readBytes())
        assertEquals(Settings(), SettingsJson.decode(file.readText()))
        // The log names the copy and its size, never what the file holds.
        assertTrue(logs.toString(), logs.any { copy.name in it && "${broken.length} bytes" in it })
        assertFalse(logs.toString(), logs.any { "headsetButtons" in it })
    }

    @Test
    fun `only the newest copy of an unreadable file is kept`() = runBlocking {
        File(backupDir, "settings-corrupt-${NOW - 5}.json").writeText("older copy")
        File(backupDir, "other.json").writeText("not a copy")

        assertEquals(Settings(), store(settingsFile("not settings")).data.first())

        assertEquals(listOf("other.json", "settings-corrupt-$NOW.json"), backupDir.list().orEmpty().sorted())
    }

    private companion object {
        const val NOW: Long = 1_800_000_000_000L
    }

    @Test
    fun `the Books tab comes right after Folders for a file that never had it, and stays where the user put it`() {
        fun tabs(order: String) = SettingsJson.decode("""{"library":{"tabs":[$order]}}""")?.library?.tabs?.map { it.tab }
        fun tab(name: LibraryTab) = """{"tab":"$name","shown":true}"""
        val before = listOf(LibraryTab.FOLDERS, LibraryTab.SONGS, LibraryTab.GENRES, LibraryTab.ALBUMS, LibraryTab.ARTISTS, LibraryTab.PLAYLISTS)
        assertEquals(
            listOf(LibraryTab.FOLDERS, LibraryTab.BOOKS, LibraryTab.SONGS, LibraryTab.GENRES, LibraryTab.ALBUMS, LibraryTab.ARTISTS, LibraryTab.PLAYLISTS),
            tabs(before.joinToString(",") { tab(it) }),
        )
        val moved = listOf(LibraryTab.BOOKS) + before
        assertEquals(moved, tabs(moved.joinToString(",") { tab(it) }))
        assertTrue(LibrarySettings.DEFAULT_TABS.single { it.tab == LibraryTab.BOOKS }.shown)
    }

    // The bottom bar and the tab Library opens on (KJB 3.1, user feedback 2026-11-17 items 1 and 14)

    @Test
    fun `the bottom bar keeps its order, Library always on, each item once, and one this version does not know is dropped`() {
        fun dock(json: String) = SettingsJson.decode("""{"display":{"dock":$json}}""")?.display?.dock
        fun item(tab: String, shown: Any?) = """{"tab":"$tab","shown":$shown}"""
        val stored = listOf(item("TOOLS", true), item("QUEUES", true), item("RADIO", true), item("LIBRARY", false), item("QUEUES", false), item("SEARCH", "\"yes\""), "7")
        val read = dock(stored.joinToString(",", "[", "]"))

        // Library switched on again, the repeated Queues and the unknown item left out, a "shown" of another type as its default.
        assertEquals(
            listOf(DockItemSetting(DockTab.TOOLS, true), DockItemSetting(DockTab.QUEUES, true), DockItemSetting(DockTab.LIBRARY, true), DockItemSetting(DockTab.SEARCH, true)),
            read?.take(4),
        )
        // The items the file does not carry follow, in their default order, on or off as by default.
        assertEquals(listOf(DockTab.PLAYLISTS, DockTab.FAVOURITES, DockTab.DOWNLOADS, DockTab.BOOKS, DockTab.INSIGHTS, DockTab.HUDDLE), read?.drop(4)?.map { it.tab })
        assertEquals(listOf(true, false, false, false, false, false), read?.drop(4)?.map { it.shown })
        // Not a list, or nothing in it: the default bar.
        assertEquals(DisplaySettings.DEFAULT_DOCK, dock("""{"tab":"QUEUES"}"""))
        assertEquals(DisplaySettings.DEFAULT_DOCK, dock("[]"))
    }

    @Test
    fun `a settings file from before the bottom bar could change reads today's bar, Standard, never hiding`() {
        val before = """{"version":4,"display":{"theme":"DARK","pureBlack":true},"nav":{"lastTab":"SEARCH"},"library":{"showFileNames":true}}"""
        val read = SettingsJson.decode(before)

        assertEquals(DisplaySettings(ThemeChoice.DARK, pureBlack = true), read?.display)
        assertEquals(DockTab.SEARCH, read?.nav?.lastTab)
        assertNull(read?.library?.opensOn)
        // A last tab only the new bar has reads back.
        assertEquals(DockTab.FAVOURITES, SettingsJson.decode("""{"nav":{"lastTab":"FAVOURITES"}}""")?.nav?.lastTab)
    }

    @Test
    fun `the bar's style and Hide while scrolling are stored, and a style this version does not know reads as Standard`() {
        fun display(json: String) = SettingsJson.decode("""{"display":$json}""")?.display
        assertEquals(DockStyle.SLIM, display("""{"dockStyle":"SLIM"}""")?.dockStyle)
        assertEquals(DockStyle.STANDARD, display("""{"dockStyle":"TINY"}""")?.dockStyle)
        assertEquals(true, display("""{"hideDockOnScroll":true}""")?.hideDockOnScroll)
        assertEquals(false, display("""{"hideDockOnScroll":"on"}""")?.hideDockOnScroll)
        val encoded = SettingsJson.encode(changed)
        assertTrue(""""dockStyle":"SLIM"""" in encoded && """"hideDockOnScroll":true""" in encoded)
    }

    @Test
    fun `the tab Library opens on reads back, none or one this version does not know reads as the first in the order`() {
        fun opensOn(json: String, base: Settings = Settings()) = SettingsJson.decode("""{"library":$json}""", base)?.library?.opensOn
        assertEquals(LibraryTab.GENRES, opensOn("""{"opensOn":"GENRES"}"""))
        assertNull(opensOn("""{"opensOn":"PODCASTS"}"""))
        assertNull(opensOn("""{"opensOn":null}"""))
        assertNull(opensOn("""{"opensOn":3}"""))
        assertNull(opensOn("{}"))
        // A backup without it keeps the phone's own; one that went back to the first in the order says so.
        val phone = Settings(library = LibrarySettings(opensOn = LibraryTab.FOLDERS))
        assertEquals(LibraryTab.FOLDERS, opensOn("{}", phone))
        assertNull(opensOn("""{"opensOn":null}""", phone))
    }

    // Settings' sections

    @Test
    fun `Find similar artists online reads back off, and a file from before it reads it on`() {
        val off = Settings(library = LibrarySettings(findSimilarOnline = false))
        assertEquals(off, SettingsJson.decode(SettingsJson.encode(off)))
        assertTrue(SettingsJson.decode("""{"version":4,"library":{"showFileNames":true}}""")!!.library.findSimilarOnline)
        assertFalse(SettingsJson.decode("""{"version":4,"library":{"findSimilarOnline":false}}""")!!.library.findSimilarOnline)
    }

    @Test
    fun `Settings' sections start with Lyrics, AI, Downloads and Backup closed and the others open`() {
        val nav = Settings().nav
        // In the screen's order (user, 2026-11-21).
        assertEquals(
            listOf(
                SettingsSection.PLAYBACK,
                SettingsSection.SUGGESTIONS,
                SettingsSection.LIBRARY,
                SettingsSection.DISPLAY,
                SettingsSection.LYRICS,
                SettingsSection.AI,
                SettingsSection.DOWNLOADS,
                SettingsSection.BACKUP,
                SettingsSection.ABOUT,
            ),
            SettingsSection.entries,
        )
        assertEquals(emptyMap<SettingsSection, Boolean>(), nav.settingsSections)
        assertEquals(listOf(SettingsSection.PLAYBACK, SettingsSection.SUGGESTIONS, SettingsSection.LIBRARY, SettingsSection.DISPLAY, SettingsSection.ABOUT), SettingsSection.entries.filter(nav::opens))
    }

    @Test
    fun `a section the user opened or closed stays so, and the others are as at first`() {
        val nav = NavSettings(settingsSections = mapOf(SettingsSection.LYRICS to true, SettingsSection.PLAYBACK to false))
        assertEquals(listOf(SettingsSection.SUGGESTIONS, SettingsSection.LIBRARY, SettingsSection.DISPLAY, SettingsSection.LYRICS, SettingsSection.ABOUT), SettingsSection.entries.filter(nav::opens))
    }

    @Test
    fun `the sections' choices read back, and one this version does not know, or not true or false, is left out`() {
        fun sections(json: String, base: Settings = Settings()) = SettingsJson.decode("""{"nav":{"settingsSections":$json}}""", base)?.nav?.settingsSections
        assertEquals(
            mapOf(SettingsSection.LYRICS to true, SettingsSection.PLAYBACK to false),
            sections("""{"LYRICS":true,"PODCASTS":true,"AI":"yes","BACKUP":1,"DOWNLOADS":null,"PLAYBACK":false}"""),
        )
        // A file without them reads every section as at first; a backup without them, or with them not an object, keeps the phone's.
        assertEquals(emptyMap<SettingsSection, Boolean>(), SettingsJson.decode("""{"version":4,"nav":{"lastTab":"TOOLS"}}""")?.nav?.settingsSections)
        val phone = Settings(nav = NavSettings(settingsSections = mapOf(SettingsSection.AI to true)))
        assertEquals(phone.nav.settingsSections, SettingsJson.decode("""{"nav":{"lastTab":"TOOLS"}}""", phone)?.nav?.settingsSections)
        assertEquals(phone.nav.settingsSections, sections("[]", phone))
    }

    // Suggestions

    @Test
    fun `songs held out of suggestions keep each path once with its id hint and time, and only whole entries`() {
        val a = "/storage/emulated/0/Music/a.mp3"
        val b = "/storage/emulated/0/Music/b.mp3"
        val text = """{"suggestions":{"never":[{"path":"$a","id":7,"at":5},{"path":"$a","at":9},{"path":"$b"},{"at":4},3,""" +
            """{"path":"$b","id":"x","at":6}],"notNow":"none","adventure":"WILD","autoplay":"no","forYouHiddenUntil":"soon"}}"""
        val read = SettingsJson.decode(text)?.suggestions
        assertEquals(listOf(HeldSong(SongRef(a, 7), 5), HeldSong(SongRef(b), 6)), read?.never)
        assertEquals(emptyList<HeldSong>(), read?.notNow)
        assertEquals(Adventure.MIX, read?.adventure)
        assertTrue(read?.autoplay == true)
        assertNull(read?.forYouHiddenUntil)
        assertTrue(""""never":[{"path":"$a","id":7,"at":5}]""" in SettingsJson.encode(Settings(suggestions = SuggestionSettings(never = listOf(HeldSong(SongRef(a, 7), 5))))))
    }

    @Test
    fun `a settings file from before Suggestions reads its defaults, and a backup without them keeps the phone's`() {
        assertEquals(SuggestionSettings(), SettingsJson.decode("""{"version":4,"playback":{"headsetButtons":false}}""")?.suggestions)
        val phone = Settings(suggestions = SuggestionSettings(autoplay = false, adventure = Adventure.FAMILIAR))
        assertEquals(phone.suggestions, SettingsJson.decode("""{"playback":{}}""", phone)?.suggestions)
    }
}
