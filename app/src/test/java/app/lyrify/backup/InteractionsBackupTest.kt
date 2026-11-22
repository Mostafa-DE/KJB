package app.lyrify.backup

import app.lyrify.backup.BackupFixtures.T0
import app.lyrify.backup.BackupFixtures.bell
import app.lyrify.backup.BackupFixtures.contents
import app.lyrify.backup.BackupFixtures.entries
import app.lyrify.backup.BackupFixtures.lantern
import app.lyrify.backup.BackupFixtures.newBell
import app.lyrify.backup.BackupFixtures.newLantern
import app.lyrify.backup.BackupFixtures.newLibrary
import app.lyrify.backup.BackupFixtures.plays311
import app.lyrify.backup.BackupFixtures.water
import app.lyrify.backup.BackupFixtures.zip
import app.lyrify.library.SongRef
import app.lyrify.library.song
import app.lyrify.stats.Interaction
import app.lyrify.stats.InteractionKind
import app.lyrify.stats.LoggedPlay
import app.lyrify.stats.Target
import app.lyrify.stats.TargetKind
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The interaction log in backups (IL1): with the plays, in the plays section 3.1.1 already reads, under a key of its
 * own that 3.1.1 never reads, so 3.1.1 restores the plays as before; on a new phone each interaction follows its song.
 * Made-up names only.
 */
class InteractionsBackupTest {
    /** A song on the old phone only. */
    private val gone = song("Music/Gone/Salt Road.mp3", title = "Salt Road", artist = "Mira Solen", durationMs = 199_000, id = 31)
    private val album = Target(TargetKind.ALBUM, """["Kit Calloway","Harbour Glass"]""")

    private val interactions = listOf(
        Interaction(T0, InteractionKind.SEARCH, 180, Target(TargetKind.SONG, lantern.path), "paper lantern"),
        Interaction(T0 + 1_000, InteractionKind.SEARCH, 180, Target(TargetKind.SONG, gone.path), "salt road"),
        Interaction(T0 + 2_000, InteractionKind.SEARCH, 180, words = "harbour"),
        Interaction(T0 + 3_000, InteractionKind.OPEN, 180, album),
        Interaction(T0 + 4_000, InteractionKind.OPEN, -300, Target(TargetKind.SONG, gone.path)),
        Interaction(T0 + 5_000, InteractionKind.LYRICS, -300, Target(TargetKind.SONG, bell.path)),
        Interaction(T0 + 6_000, InteractionKind.OPEN, 0, Target(TargetKind.PLAYLIST, "p1")),
        // Its words forgotten (Recent searches' "×").
        Interaction(T0 + 7_000, InteractionKind.SEARCH, 0, Target(TargetKind.FOLDER, "Music/Night Ferry")),
    )

    private fun backup(): ByteArray = zip(BackupFixtures.snapshot.copy(interactions = interactions), BackupFixtures.library + gone)

    private fun playsText(bytes: ByteArray): String = entries(bytes).first { it.first == Section.PLAYS.file }.second

    private fun noId(play: LoggedPlay): LoggedPlay = play.copy(song = SongRef(play.song.path))

    // A new backup here

    @Test
    fun `the interaction log comes back with the plays, each interaction with all it keeps`() {
        val read = contents(backup())
        assertEquals(interactions, read.interactions)
        assertEquals(BackupFixtures.plays.map(::noId), read.plays)
    }

    @Test
    fun `the backup lists the songs only the interactions name, so another phone finds them`() {
        val bytes = zip(BackupSnapshot(plays = emptyList(), interactions = listOf(Interaction(T0, InteractionKind.LYRICS, 180, Target(TargetKind.SONG, water.path)))))
        assertEquals(listOf(water.path), contents(bytes).songs.map { it.path })
    }

    // A new backup in 3.1.1

    @Test
    fun `the plays section keeps its version, and 3_1_1 reads its plays as before without the interactions`() {
        val bytes = backup()
        val manifest = Json.parseToJsonElement(entries(bytes).first { it.first == BackupBundle.MANIFEST }.second).jsonObject
        assertEquals(1, manifest["sections"]!!.jsonObject["plays"]!!.jsonObject["version"]!!.jsonPrimitive.int)
        assertEquals(BackupFixtures.plays.map(::noId), plays311(playsText(bytes)))
    }

    // A backup from before here

    @Test
    fun `a backup from before the interaction log restores its plays, and no interactions`() {
        val bytes = zip(BackupFixtures.snapshot)
        assertFalse("interactions" in playsText(bytes))
        val read = contents(bytes)
        assertEquals(BackupFixtures.plays.map(::noId), read.plays)
        assertEquals(emptyList<Interaction>(), read.interactions)
    }

    @Test
    fun `an interaction is read leniently, one without a time or of a kind unknown here left out`() {
        val text = """{"plays":[],"interactions":[""" +
            """{"at":$T0,"kind":"search","zoneOffset":99999,"words":"  ","target":"shelf","targetKey":"x"},""" +
            """{"at":${T0 + 1},"kind":"open","zoneOffset":"east","target":"album"},""" +
            """{"at":${T0 + 2},"kind":"lyrics","zoneOffset":60,"target":"song","targetKey":"${water.path}"},""" +
            """{"at":${T0 + 3},"kind":"share","target":"song","targetKey":"${water.path}"},""" +
            """{"kind":"open","target":"album","targetKey":"a"},{"at":0,"kind":"open"},"not one"]}"""

        assertEquals(
            listOf(
                Interaction(T0, InteractionKind.SEARCH, null),
                Interaction(T0 + 1, InteractionKind.OPEN, null),
                Interaction(T0 + 2, InteractionKind.LYRICS, 60, Target(TargetKind.SONG, water.path)),
            ),
            SectionJson.plays(text)?.interactions,
        )
    }

    // Restoring

    @Test
    fun `on a new phone each interaction follows its song, one about a song not here is left out and a search keeps its words`() {
        val plan = RestorePlan(contents(backup()), newLibrary)
        val phone = MemoryStores()
        runBlocking { plan.apply(setOf(Section.PLAYS), phone, newId = { "new" }) }

        assertEquals(
            listOf(
                Interaction(T0, InteractionKind.SEARCH, 180, Target(TargetKind.SONG, newLantern.path), "paper lantern"),
                Interaction(T0 + 1_000, InteractionKind.SEARCH, 180, words = "salt road"),
                Interaction(T0 + 2_000, InteractionKind.SEARCH, 180, words = "harbour"),
                Interaction(T0 + 3_000, InteractionKind.OPEN, 180, album),
                Interaction(T0 + 5_000, InteractionKind.LYRICS, -300, Target(TargetKind.SONG, newBell.path)),
                Interaction(T0 + 6_000, InteractionKind.OPEN, 0, Target(TargetKind.PLAYLIST, "p1")),
                Interaction(T0 + 7_000, InteractionKind.SEARCH, 0, Target(TargetKind.FOLDER, "Music/Night Ferry")),
            ),
            phone.log.interactions,
        )
        // A song only an interaction named is no song missing from the plays.
        assertFalse(plan.missing(setOf(Section.PLAYS)).any { it.path == gone.path })
    }

    @Test
    fun `the plays and the interactions are restored in one go, a second restore adding nothing, a failing log neither`() {
        val contents = contents(backup())
        val phone = MemoryStores()
        runBlocking {
            RestorePlan(contents, BackupFixtures.library).apply(setOf(Section.PLAYS), phone, newId = { "new" })
            RestorePlan(contents, BackupFixtures.library).apply(setOf(Section.PLAYS), phone, newId = { "new" })
        }
        // All but the Song info of the song this phone lacks.
        assertEquals(interactions.size - 1, phone.log.interactions.size)
        assertEquals(BackupFixtures.plays.size, phone.log.plays.size)

        val failing = MemoryStores(failing = mutableSetOf(Section.PLAYS))
        val result = runBlocking { RestorePlan(contents, BackupFixtures.library).apply(setOf(Section.PLAYS), failing, newId = { "new" }) }
        assertEquals(listOf(Section.PLAYS), result.failed)
        assertEquals(emptyList<Interaction>(), failing.log.interactions)
        assertEquals(emptyList<LoggedPlay>(), failing.log.plays)
    }
}
