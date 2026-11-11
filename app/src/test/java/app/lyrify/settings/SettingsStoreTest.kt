package app.lyrify.settings

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
                LibraryTabSetting(LibraryTab.PLAYLISTS, false),
            ),
            foldersToSkip = listOf("Recordings/Calls", "Music/Voice Notes"),
            showFileNames = true,
            songSort = SortSongs.RECENTLY_ADDED,
            songFilter = LyricsFilter.PLAIN,
            albumSort = SortAlbums.YEAR,
            artistSort = SortArtists.MOST_SONGS,
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
        nav = NavSettings(lastTab = DockTab.PLAYLISTS, favouritesTab = FavouritesTab.ARTISTS),
    )

    // Defaults

    @Test
    fun `every setting starts at the default the specs give`() {
        val s = Settings()
        // Remember my place: All songs since user feedback 2026-11-11 (it was Long songs).
        assertEquals(PlaybackSettings(RememberPlace.ALL_SONGS, true, true, 50, 30, 10), s.playback)
        assertEquals(emptyList<ConnectDevice>(), s.playback.connectDevices)
        assertEquals(AudioSettings(Levelling.AUTO, crossfadeSeconds = 0), s.audio)
        // What Settings > Library tabs lists: Playlists right after Songs.
        assertEquals(
            listOf(LibraryTab.SONGS, LibraryTab.PLAYLISTS, LibraryTab.ALBUMS, LibraryTab.ARTISTS, LibraryTab.FOLDERS, LibraryTab.GENRES),
            s.library.tabs.map { it.tab },
        )
        assertEquals(listOf(LibraryTab.GENRES), s.library.tabs.filterNot { it.shown }.map { it.tab })
        assertEquals(emptyList<String>(), s.library.foldersToSkip)
        assertFalse(s.library.showFileNames)
        assertEquals(LyricsSettings(false, LyricsSize.M, LyricsWeight.BOLD, LyricsAlign.LEFT), s.lyrics)
        assertEquals(PlayerUiSettings(showLyrics = false, flipHintShown = false, recentQueueHintShown = false), s.playerUi)
        assertEquals(BackupSettings(automatic = false, folderUri = null), s.backup)
        assertEquals(AdvancedSettings(true, false, NotificationButtons.FAVOURITE, levellingBoostDb = 0, keepAlbumsGapless = true), s.advanced)
        assertEquals(NavSettings(DockTab.LIBRARY, FavouritesTab.SONGS, setupDone = false), s.nav)
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

        assertEquals(Settings(library = LibrarySettings(showFileNames = true)), s)
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
            ),
            tabs(1, own),
        )
        // From version 2 on, Playlists stays where the user put it.
        val last = first.joinToString(",") { tab(it, true) }
        assertEquals(first, tabs(SettingsJson.VERSION, last)?.map { it.tab })
        assertTrue(""""version":${SettingsJson.VERSION}""" in SettingsJson.encode(Settings()))
    }

    @Test
    fun `Remember my place left at the old default reads All songs, and a choice the user made is kept`() {
        fun place(version: Int?, stored: String?): RememberPlace? {
            val field = stored?.let { """"rememberPlace":"$it"""" } ?: """"headsetButtons":false"""
            val head = version?.let { """"version":$it,""" } ?: ""
            return SettingsJson.decode("""{$head"playback":{$field}}""")?.playback?.rememberPlace
        }

        assertEquals(3, SettingsJson.VERSION)
        // Long songs in a file from before version 3 is the old default, which every file wrote: never changed.
        assertEquals(RememberPlace.ALL_SONGS, place(1, "LONG_SONGS"))
        assertEquals(RememberPlace.ALL_SONGS, place(2, "LONG_SONGS"))
        // The user's own choices from then are kept.
        assertEquals(RememberPlace.OFF, place(2, "OFF"))
        assertEquals(RememberPlace.ALL_SONGS, place(2, "ALL_SONGS"))
        // From version 3, Long songs is a choice the user made.
        assertEquals(RememberPlace.LONG_SONGS, place(3, "LONG_SONGS"))
        assertEquals(RememberPlace.LONG_SONGS, place(null, "LONG_SONGS"))
        // Missing or unknown: the default.
        assertEquals(RememberPlace.ALL_SONGS, place(2, null))
        assertEquals(RememberPlace.ALL_SONGS, place(3, "FOREVER"))
        // Written back, a choice stays what it is.
        val long = Settings(playback = PlaybackSettings(rememberPlace = RememberPlace.LONG_SONGS))
        assertEquals(long, SettingsJson.decode(SettingsJson.encode(long)))
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
}
