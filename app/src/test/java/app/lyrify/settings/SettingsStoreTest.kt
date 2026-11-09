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
            startWhenConnected = true,
            skipShortClips = false,
            countPlayAfterPercent = 70,
            skipBackSeconds = 15,
            skipForwardSeconds = 5,
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
        nav = NavSettings(lastTab = DockTab.TOOLS, libraryTab = LibraryTab.ALBUMS),
    )

    // Defaults

    @Test
    fun `every setting starts at the default the specs give`() {
        val s = Settings()
        assertEquals(PlaybackSettings(RememberPlace.LONG_SONGS, true, false, true, 50, 30, 10), s.playback)
        assertEquals(AudioSettings(Levelling.AUTO, crossfadeSeconds = 0), s.audio)
        assertEquals(
            listOf(LibraryTab.SONGS, LibraryTab.ALBUMS, LibraryTab.ARTISTS, LibraryTab.FOLDERS, LibraryTab.PLAYLISTS, LibraryTab.GENRES),
            s.library.tabs.map { it.tab },
        )
        assertEquals(listOf(LibraryTab.GENRES), s.library.tabs.filterNot { it.shown }.map { it.tab })
        assertEquals(emptyList<String>(), s.library.foldersToSkip)
        assertFalse(s.library.showFileNames)
        assertEquals(LyricsSettings(false, LyricsSize.M, LyricsWeight.BOLD, LyricsAlign.LEFT), s.lyrics)
        assertEquals(PlayerUiSettings(showLyrics = false, flipHintShown = false, recentQueueHintShown = false), s.playerUi)
        assertEquals(BackupSettings(automatic = false, folderUri = null), s.backup)
        assertEquals(AdvancedSettings(true, false, NotificationButtons.FAVOURITE, levellingBoostDb = 0, keepAlbumsGapless = true), s.advanced)
        assertEquals(NavSettings(DockTab.LIBRARY, LibraryTab.SONGS), s.nav)
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
        assertEquals(NavSettings(DockTab.LIBRARY, LibraryTab.GENRES), s?.nav)
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
    fun `folders to skip keep each folder once, and only folder names`() {
        val s = SettingsJson.decode("""{"library":{"foldersToSkip":["Recordings/Calls",5,"","  ","Recordings/Calls",null,"Music/Voice Notes"]}}""")

        assertEquals(listOf("Recordings/Calls", "Music/Voice Notes"), s?.library?.foldersToSkip)
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
