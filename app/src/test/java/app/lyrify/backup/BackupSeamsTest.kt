package app.lyrify.backup

import app.lyrify.data.LedgerEntry
import app.lyrify.data.LedgerJson
import app.lyrify.data.LedgerRepository
import app.lyrify.data.LedgerState
import app.lyrify.data.LyricsStatus
import app.lyrify.data.ledgerStore
import app.lyrify.library.Playlist
import app.lyrify.library.PlaylistsJson
import app.lyrify.library.PlaylistsRepository
import app.lyrify.library.PlaylistsState
import app.lyrify.library.playlistsStore
import app.lyrify.stats.FakePlayLog
import app.lyrify.stats.LoggedPlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The seams a backup and a restore use in each store, on the stores' real files: one write for a whole restore, and the
 * play log's export and import. (The real `plays.db` is checked on the phone, PlaysBackupDeviceTest.) Made-up names only.
 */
class BackupSeamsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @After
    fun stop() = scope.cancel()

    private val lantern = BackupFixtures.lantern.ref
    private val bell = BackupFixtures.bell.ref

    @Test
    fun `playlists take a whole restore in one write, stored in their file`() = runBlocking {
        val file = File(folder.root, "playlists.json")
        val store = PlaylistsRepository(playlistsStore(file, folder.newFolder("files"), scope, clock = { 1 }) {}, clock = { 1 }, newId = { "x" }) {}
        assertTrue(store.put(Playlist("a", "Kitchen", listOf(bell), 1)))

        assertTrue(store.update { it.put(Playlist("b", "Road trip", listOf(lantern), 2)).favourite(listOf(lantern), favourite = true) })

        val stored = checkNotNull(PlaylistsJson.decode(file.readText()))
        assertEquals(listOf("Kitchen", "Road trip"), stored.playlists.map { it.name })
        assertEquals(listOf(lantern), stored.favouriteSongs)
        assertEquals(stored, store.state.first())
    }

    @Test
    fun `the ledger takes a whole restore in one write, stored in its file`() = runBlocking {
        val file = File(folder.root, "ledger.json")
        val store = LedgerRepository(ledgerStore(file, folder.newFolder("files"), scope, clock = { 1 }) {}) {}
        val entry = LedgerEntry(lantern.path, "Paper Lantern", "Kit Calloway", null, 201_000, LyricsStatus.NOT_FOUND, updatedAt = 1)
        val other = entry.copy(path = bell.path, title = "Tide Bell")

        assertTrue(store.update { it.put(entry).put(other) })

        assertEquals(setOf(lantern.path, bell.path), LedgerJson.decode(file.readText()).entries.keys)
        assertEquals(entry, store.entry(lantern.path))
    }

    @Test
    fun `a ledger that cannot be read takes no restore, and its file stays as it was`() = runBlocking {
        val broken = "{ not a ledger"
        val file = File(folder.root, "ledger.json").apply { writeText(broken) }
        // Its copy cannot be kept aside, so the ledger cannot be read.
        val noFolder = File(folder.root, "not-a-folder").apply { writeText("") }
        val logs = mutableListOf<String>()
        val store = LedgerRepository(ledgerStore(file, noFolder, scope, clock = { 1 }) {}) { logs += it }

        assertFalse(store.update { LedgerState() })

        assertEquals(broken, file.readText())
        assertTrue(logs.toString(), logs.isNotEmpty() && logs.all { "ledger not written" in it })
    }

    @Test
    fun `the play log's made-up stand-in exports its plays, oldest first, and takes them back once`() = runBlocking {
        val first = LoggedPlay(lantern, 1_000, 90_000, counted = true)
        val second = LoggedPlay(bell, 500, 10_000, counted = false)
        val log = FakePlayLog(listOf(first, second))

        assertEquals(listOf(second, first), log.all())
        assertTrue(log.putAll(listOf(first, second, first.copy(heardMs = 120_000))))
        assertEquals(setOf(second, first.copy(heardMs = 120_000)), log.plays.toSet())
        log.failing = true
        assertEquals(null, log.all())
        assertFalse(log.putAll(listOf(first)))
    }

    @Test
    fun `an empty playlists file takes a restore as well`() = runBlocking {
        val file = File(folder.root, "playlists.json")
        val store = PlaylistsRepository(playlistsStore(file, folder.newFolder("files"), scope, clock = { 1 }) {}, clock = { 1 }, newId = { "x" }) {}

        assertTrue(store.update { PlaylistsState(listOf(Playlist("b", "Road trip", listOf(lantern), 2))) })

        assertEquals(listOf("Road trip"), store.state.first().playlists.map { it.name })
    }
}
