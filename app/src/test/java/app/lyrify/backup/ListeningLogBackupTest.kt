package app.lyrify.backup

import app.lyrify.backup.BackupFixtures.T0
import app.lyrify.backup.BackupFixtures.bell
import app.lyrify.backup.BackupFixtures.contents
import app.lyrify.backup.BackupFixtures.entries
import app.lyrify.backup.BackupFixtures.lantern
import app.lyrify.backup.BackupFixtures.water
import app.lyrify.backup.BackupFixtures.zip
import app.lyrify.backup.SectionJson.flag
import app.lyrify.backup.SectionJson.long
import app.lyrify.backup.SectionJson.text
import app.lyrify.library.SongRef
import app.lyrify.player.SourceKind
import app.lyrify.stats.LoggedPlay
import app.lyrify.stats.PlayEnd
import app.lyrify.stats.PlayEnded
import app.lyrify.stats.PlayStart
import app.lyrify.stats.StartedBy
import app.lyrify.ui.RestoreRow
import app.lyrify.ui.restoreRows
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The better listening log in backups, both ways (LG1): a new backup carries what each play keeps and the skips in the
 * section 3.1.0 already reads, at the same version, so 3.1.0 restores it as before; and a backup 3.1.0 made restores
 * here. Made-up names only.
 */
class ListeningLogBackupTest {
    private val picked = PlayStart(StartedBy.PICKED, SourceKind.ALBUM, shuffled = false, songKey = "paper lantern|kit calloway|harbour glass|100")

    /** Plays of every kind 3.1.0 kept, with what is kept of them now. */
    private val plays = listOf(
        LoggedPlay(lantern.ref, T0, 150_000, counted = true, start = picked, zoneOffsetMinutes = 180, end = PlayEnded(PlayEnd.FINISHED, 201_000)),
        LoggedPlay(bell.ref, T0 + 600_000, 20_000, counted = false, start = PlayStart(StartedBy.AUTO, null, shuffled = true, songKey = null), zoneOffsetMinutes = -300, end = PlayEnded(PlayEnd.NEXT, 20_000)),
        LoggedPlay(water.ref, T0 + 700_000, 0, counted = true, imported = true),
    )

    /** Skips: plays heard under a second, which 3.1.0 never kept. */
    private val skips = listOf(
        LoggedPlay(lantern.ref, T0 + 800_000, 400, counted = false, start = picked, zoneOffsetMinutes = 180, end = PlayEnded(PlayEnd.NEXT, 400)),
        LoggedPlay(bell.ref, T0 + 900_000, 0, counted = false, end = PlayEnded(PlayEnd.JUMP, 0)),
    )

    private fun backup(): ByteArray = zip(BackupFixtures.snapshot.copy(plays = plays + skips))

    private fun section(bytes: ByteArray, section: Section): String = entries(bytes).first { it.first == section.file }.second

    private fun noId(play: LoggedPlay): LoggedPlay = play.copy(song = SongRef(play.song.path))

    // A new backup here

    @Test
    fun `every play comes back with all it keeps, and the skips too`() {
        assertEquals((plays + skips).map(::noId), contents(backup()).plays)
    }

    @Test
    fun `the plays and playlists sections keep their versions, so 3_1_0 reads them and skips nothing`() {
        val manifest = Json.parseToJsonElement(entries(backup()).first { it.first == BackupBundle.MANIFEST }.second).jsonObject
        val sections = manifest["sections"]!!.jsonObject
        assertEquals(1, sections["plays"]!!.jsonObject["version"]!!.jsonPrimitive.int)
        assertEquals(1, sections["playlists"]!!.jsonObject["version"]!!.jsonPrimitive.int)
        assertEquals(1, Section.PLAYS.version)
        assertEquals(1, Section.PLAYLISTS.version)
    }

    // A new backup in 3.1.0

    @Test
    fun `3_1_0 reads a new backup's plays as it always did, every play it knew and no skip`() {
        val read = plays310(section(backup(), Section.PLAYS))
        assertEquals(plays.map { LoggedPlay(SongRef(it.song.path), it.startedAt, it.heardMs, it.counted) }, read)
    }

    // A 3.1.0 backup here

    @Test
    fun `a 3_1_0 backup's plays come back without what it did not keep, an import's plays marked as one`() {
        val old = """{"plays":[{"path":"${lantern.path}","startedAt":$T0,"heardMs":150000,"counted":true},""" +
            """{"path":"${water.path}","startedAt":${T0 + 1},"heardMs":0,"counted":true},""" +
            """{"path":"${bell.path}","startedAt":${T0 + 2},"heardMs":0,"counted":false}]}"""

        assertEquals(
            listOf(
                LoggedPlay(SongRef(lantern.path), T0, 150_000, counted = true),
                // An import made it: nothing heard, counted (backup/ImportPlan.kt), as plays.db's own step finds them.
                LoggedPlay(SongRef(water.path), T0 + 1, 0, counted = true, imported = true),
                LoggedPlay(SongRef(bell.path), T0 + 2, 0, counted = false),
            ),
            SectionJson.plays(old),
        )
    }

    @Test
    fun `what a play keeps is read leniently, the play itself always kept`() {
        val damaged = """{"plays":[{"path":"${lantern.path}","startedAt":$T0,"heardMs":150000,"counted":true,""" +
            """"startedBy":"sideways","source":"NOWHERE","shuffled":"yes","zoneOffset":99999,"songKey":7,"ended":"later","endedAtMs":-4}],""" +
            """"skips":[{"path":"${bell.path}","startedAt":${T0 + 1},"heardMs":300,"counted":false,"ended":"next"},""" +
            """{"path":"${water.path}","startedAt":${T0 + 2},"heardMs":90000,"counted":true},"not a play"]}"""

        assertEquals(
            listOf(
                LoggedPlay(SongRef(lantern.path), T0, 150_000, counted = true, start = PlayStart(StartedBy.UNKNOWN, null, shuffled = false, songKey = null), end = PlayEnded(PlayEnd.UNKNOWN, 0)),
                // How it ended without where: no end.
                LoggedPlay(SongRef(bell.path), T0 + 1, 300, counted = false),
            ),
            // Among the skips, only skips: a play that counted there is none.
            SectionJson.plays(damaged),
        )
    }

    // Restoring

    @Test
    fun `the restore summary and its result count the plays as before, never the skips`() {
        val contents = contents(backup())
        assertEquals(RestoreRow(Section.PLAYS, plays.size, null), restoreRows(contents).single { it.section == Section.PLAYS })

        val stores = MemoryStores()
        val result = runBlocking { RestorePlan(contents, BackupFixtures.library).apply(setOf(Section.PLAYS), stores, newId = { "new" }) }
        assertEquals(plays.size, result.counts[Section.PLAYS])
        assertEquals((plays + skips).map(::noId).toSet(), stores.log.plays.map(::noId).toSet())
    }

    private companion object {
        /** 3.1.0's reading of the plays section (backup/BackupSections.kt), word for word. */
        fun plays310(text: String): List<LoggedPlay>? = (SectionJson.jsonObject(text)?.get("plays") as? JsonArray)?.mapNotNull { element ->
            val play = element as? JsonObject ?: return@mapNotNull null
            LoggedPlay(
                song = SongRef(play.text("path")?.takeIf(String::isNotEmpty) ?: return@mapNotNull null),
                startedAt = play.long("startedAt")?.takeIf { it > 0 } ?: return@mapNotNull null,
                heardMs = play.long("heardMs")?.coerceAtLeast(0) ?: 0,
                counted = play.flag("counted") ?: false,
            )
        }
    }
}
