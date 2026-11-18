package app.lyrify.tags

import app.lyrify.library.CollectionKind
import app.lyrify.library.FavouriteCollection
import app.lyrify.library.Library
import app.lyrify.library.Song
import app.lyrify.library.SongRef
import app.lyrify.library.albumKey
import app.lyrify.lyrics.LocalTrack
import app.lyrify.settings.Settings
import app.lyrify.tags.lookup.AlbumMatch
import app.lyrify.tags.lookup.Cover
import app.lyrify.tags.lookup.Credit
import app.lyrify.tags.lookup.Doubt
import app.lyrify.tags.lookup.ImageSize
import app.lyrify.tags.lookup.JPEG_BYTES
import app.lyrify.tags.lookup.Lookup
import app.lyrify.tags.lookup.MbMedium
import app.lyrify.tags.lookup.MbRelease
import app.lyrify.tags.lookup.MbReleaseGroup
import app.lyrify.tags.lookup.MbTrack
import app.lyrify.tags.lookup.MetaAnswer
import app.lyrify.tags.lookup.Part
import app.lyrify.tags.lookup.PhoneAlbum
import app.lyrify.tags.lookup.Proposal
import app.lyrify.tags.lookup.ReleaseMatch
import app.lyrify.tags.lookup.SongFields
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The Covers & tags flow, end to end through the real safe writer over fakes: made-up names only. */
@OptIn(ExperimentalCoroutinesApi::class)
class CoversTagsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val rig by lazy { WriterRig(folder.root) }
    private val source = FakeAlbumSource()
    private val handedToLyrics = mutableListOf<Pair<LocalTrack, Boolean>>()
    private val bumped = mutableListOf<String>()
    private var access = true
    private var library: List<Song>? = emptyList()
    private val storeFile by lazy { File(folder.root, "files/covers-tags.json") }

    private val favourites = FakeFavourites(emptyList())

    /** What the writer is told about its batch: the test's own hooks around the flow's. */
    private var progress: (Progress) -> Progress = { it }

    /** The library as the app reads [library]; null while it is not read. */
    private fun read(): Library? = library?.let { Library.of(it, Settings()) }

    private fun TestScope.flow(): CoversTags = CoversTags(
        songs = { library },
        source = source,
        write = { jobs, changed, kind, label, given -> writeEdit(rig.writer, favourites, read(), jobs, changed, kind, label, progress(given)) },
        undoEntry = { undoEdit(rig.writer, rig.undo, favourites, read(), it) },
        undo = rig.undo,
        store = CoversTagsStore(storeFile),
        lyrics = { track, changed -> handedToLyrics += track to changed },
        coversChanged = { bumped += it },
        access = { access },
        scope = this,
        io = StandardTestDispatcher(testScheduler),
        compute = StandardTestDispatcher(testScheduler),
        clock = { BASE + testScheduler.currentTime },
    )

    // Made-up songs and releases

    private fun song(
        name: String,
        id: Long,
        title: String,
        album: String?,
        artist: String = "Mira Solen",
        track: Int? = null,
        year: Int? = null,
        pictures: List<TagPicture> = emptyList(),
    ): Song {
        val fields = buildMap {
            put("TITLE", listOf(title))
            put("ARTIST", listOf(artist))
            album?.let { put("ALBUM", listOf(it)) }
            track?.let { put("TRACKNUMBER", listOf(it.toString())) }
            year?.let { put("DATE", listOf(it.toString())) }
        }
        val target = rig.song(name, id, fields, pictures)
        return Song(SongRef(target.path, id), title, artist, album, null, 200_000, track, 1, year, null, "Music/KJB test/", null, null, 0, "audio/mpeg")
    }

    private fun Song.target() = SongTarget(path, checkNotNull(ref.mediaStoreId))

    private fun release(id: String, title: String, tracks: List<String>, artist: String = "Mira Solen", firstYear: String? = "1998-04-17"): MbRelease {
        val credit = Credit(listOf(Credit.Part(artist, artistId = "a1b2c3d4-0000-4000-8000-00000000aa01")))
        val list = tracks.mapIndexed { i, t -> MbTrack("${id.dropLast(4)}000$i", "${id.take(8)}-1111-4111-8111-00000000000$i", i + 1, t, 200_000, credit) }
        return MbRelease(id, title, credit, MbReleaseGroup(GROUP, firstYear), date = "2009", country = "XE", trackCount = tracks.size, media = listOf(MbMedium(1, "CD", tracks.size, list)), hasFront = true)
    }

    private val lanterns = release(ID_A, "Lanterns of Ulso", listOf("Quiet Ember", "Salt Road"))
    private val harbour = release(ID_B, "Harbour Glass", listOf("Tin Roof", "Kelp Lights"), artist = "Kit Calloway")

    /** Lanterns (Ready): "Quiet Embers" and "Salt Road" tagged "Lanterns", no album artist. */
    private fun lanternsSongs(year: Int? = null) = listOf(
        song("Quiet Embers", 41, "Quiet Embers", "Lanterns", track = 1, year = year),
        song("Salt Road", 42, "Salt Road", "Lanterns", track = 2),
    )

    private fun harbourSongs() = listOf(
        song("Tin Roof", 51, "Tin Roof", "Harbour", artist = "Kit Calloway", track = 1),
        song("Kelp Lights", 52, "Kelp Lights", "Harbour", artist = "Kit Calloway", track = 2),
    )

    /** Ready for an album titled like a release's key, Pick one for the rest. */
    private fun answers(vararg ready: Pair<String, MbRelease>) {
        val map = ready.toMap()
        source.answer = { album ->
            val release = map[album.title]
            if (release != null) Lookup.Done(Proposal.Ready(AlbumMatch.match(album, release))) else Lookup.Done(Proposal.PickOne(emptyList(), setOf(Doubt.NO_CANDIDATE)))
        }
    }

    private suspend fun TestScope.checked(flow: CoversTags): CoversTagsState {
        advanceUntilIdle()
        flow.check()
        advanceUntilIdle()
        return flow.state.value
    }

    private fun CoversTagsState.waiting(title: String): Waiting = waiting.first { it.album.album.title == title }

    private fun Waiting.match(): ReleaseMatch = (proposal as Proposal.Ready).match

    /** Apply with every song of the album ticked and every field: what the tests of the writing itself need. */
    private suspend fun CoversTags.applyWhole(waiting: Waiting): Applied = apply(waiting.album.id, waiting.match(), waiting.album.paths.toSet(), Part.entries.toSet())

    // Checking

    @Test
    fun `a check sorts every album into Ready and Pick one, a single always Pick one`() = runTest {
        library = lanternsSongs() + harbourSongs() + song("Lone Kestrel", 61, "Lone Kestrel", album = null)
        answers("Lanterns" to lanterns)
        val flow = flow()

        val state = checked(flow)

        assertEquals(listOf("Lanterns"), state.ready.map { it.album.album.title })
        assertEquals(2, state.pickOne.size)
        // Only what has a suggestion is to review: the two with no candidate are not listed.
        assertEquals(1, state.badge)
        assertNull(state.checking)
        assertTrue(state.memory.checkedOnce)
        assertEquals(listOf(false, false, false), source.checked.map { it.second })
    }

    @Test
    fun `the check shows its progress, and Cancel stops it keeping what it found`() = runTest {
        library = lanternsSongs() + harbourSongs() + song("Lone Kestrel", 61, "Lone Kestrel", album = null)
        answers("Lanterns" to lanterns, "Harbour" to harbour)
        val answer = source.answer
        source.answer = { album -> delay(1_000).let { answer(album) } }
        val flow = flow()
        advanceUntilIdle()

        flow.check()
        advanceTimeBy(1_500)
        runCurrent()
        assertEquals(Checking(1, 3), flow.state.value.checking)
        assertEquals(1, flow.state.value.waiting.size)

        flow.cancel()
        advanceUntilIdle()
        assertNull(flow.state.value.checking)
        assertEquals(1, flow.state.value.waiting.size)
        assertEquals(2, source.checked.size)
    }

    @Test
    fun `MusicBrainz still busy never stops the check, it says it waits, asks again for the same album, and carries on`() = runTest {
        library = lanternsSongs() + harbourSongs() + song("Lone Kestrel", 61, "Lone Kestrel", album = null)
        var busy = 2
        val asked = mutableListOf<Long>()
        source.answer = { album ->
            when (album.title) {
                "Lanterns" -> Lookup.Failed("offline")
                "Harbour" -> {
                    asked += testScheduler.currentTime
                    if (busy-- > 0) Lookup.Busy else Lookup.Done(Proposal.Ready(AlbumMatch.match(album, harbour)))
                }
                else -> Lookup.Done(Proposal.PickOne(emptyList(), setOf(Doubt.SINGLE)))
            }
        }
        val flow = flow()
        advanceUntilIdle()

        flow.check()
        runCurrent()
        assertTrue(flow.state.value.waitingBusy)
        assertEquals(Checking(1, 3), flow.state.value.checking)
        advanceUntilIdle()

        val state = flow.state.value
        assertFalse(state.waitingBusy)
        assertNull(state.checking)
        // Only the album that failed is not checked; the busy one was asked again after 1 min, then 2 min.
        assertEquals(1, state.notChecked)
        assertEquals(listOf("Lanterns", "Harbour", "Harbour", "Harbour", null), source.checked.map { it.first })
        assertEquals(listOf(0L, 60_000L, 180_000L), asked.map { it - asked.first() })
        assertEquals(listOf("Harbour"), state.ready.map { it.album.album.title })
        assertEquals(listOf(60_000L, 120_000L, 240_000L, 300_000L), CoversTags.BUSY_WAITS_MS)
    }

    @Test
    fun `the waits for MusicBrainz grow to 5 minutes and stay there, and Cancel stops one at once`() = runTest {
        library = lanternsSongs()
        val asked = mutableListOf<Long>()
        source.answer = {
            asked += testScheduler.currentTime
            Lookup.Busy
        }
        val flow = flow()
        advanceUntilIdle()

        flow.check()
        advanceTimeBy(30 * 60_000L)
        runCurrent()
        assertEquals(listOf(0L, 60_000L, 180_000L, 420_000L, 720_000L, 1_020_000L, 1_320_000L, 1_620_000L), asked)
        assertTrue(flow.state.value.waitingBusy)

        flow.cancel()
        advanceUntilIdle()
        assertFalse(flow.state.value.waitingBusy)
        assertNull(flow.state.value.checking)
        assertEquals(8, asked.size)
    }

    @Test
    fun `an album opened from its song while MusicBrainz is busy waits too, and opens once it is checked`() = runTest {
        val songs = lanternsSongs()
        library = songs
        var busy = 1
        source.answer = { album -> if (busy-- > 0) Lookup.Busy else Lookup.Done(Proposal.Ready(AlbumMatch.match(album, lanterns))) }
        val flow = flow()
        advanceUntilIdle()

        flow.open(songs[0].path)
        runCurrent()
        assertTrue(flow.state.value.waitingBusy)
        advanceUntilIdle()

        assertEquals(songs[0].path, flow.state.value.focus?.song)
        assertEquals(1, flow.state.value.ready.size)
        assertFalse(flow.state.value.waitingBusy)
    }

    // Applying

    @Test
    fun `apply writes the release into each song through the safe writer and moves the album to Done`() = runTest {
        val songs = lanternsSongs()
        library = songs
        answers("Lanterns" to lanterns)
        val flow = flow()
        val waiting = checked(flow).waiting("Lanterns")

        val applied = flow.applyWhole(waiting)

        assertEquals(Applied.Done(albums = 1, songs = 2, problems = emptyList()), (applied as Applied.Done).copy(entryId = null))
        val fields = rig.fields(songs[0].target())
        assertEquals(listOf("Quiet Ember"), fields["TITLE"])
        assertEquals(listOf("Mira Solen"), fields["ARTIST"])
        assertEquals(listOf("Mira Solen"), fields["ARTISTS"])
        assertEquals(listOf("Lanterns of Ulso"), fields["ALBUM"])
        assertEquals(listOf("Mira Solen"), fields["ALBUMARTIST"])
        assertEquals(listOf("1/2"), fields["TRACKNUMBER"])
        assertEquals(listOf("1/1"), fields["DISCNUMBER"])
        assertEquals(listOf(ID_A), fields["MUSICBRAINZ_ALBUMID"])
        assertEquals(listOf("1998"), fields["DATE"])
        assertNull(fields["GENRE"])
        val cover = FakeTagFile().read(File(songs[0].path))!!.pictures.single()
        assertArrayEquals(JPEG_BYTES, cover.data)
        assertEquals(FRONT_COVER, cover.type)

        val state = flow.state.value
        assertEquals(emptyList<Waiting>(), state.waiting)
        val done = state.done.single()
        assertEquals("Lanterns of Ulso", done.title)
        assertEquals(2, done.changed)
        val entry = rig.undo.memory().entries.single()
        assertEquals(entry.id, done.entryId)
        assertEquals(EditKind.COVERS_TAGS, entry.kind)
        assertEquals(listOf(entry.id), state.history.map { it.id })
    }

    @Test
    fun `Year adds the album's first year to the songs with none, and every song with a year keeps its own`() = runTest {
        val songs = lanternsSongs(year = 2003)
        library = songs
        answers("Lanterns" to lanterns)
        val flow = flow()
        val waiting = checked(flow).waiting("Lanterns")
        val changes = flow.changes(waiting, waiting.match())
        val parts = changes.ticked(cover = true)
        assertTrue(Part.YEAR in parts)
        assertEquals(1, changes.yearsKept)

        flow.apply(waiting.album.id, waiting.match(), waiting.album.paths.toSet(), parts)

        assertEquals(listOf("2003"), rig.fields(songs[0].target())["DATE"])
        assertEquals(listOf("1998"), rig.fields(songs[1].target())["DATE"])
    }

    @Test
    fun `busy songs and songs waiting for the start-up recovery are reported and never written`() = runTest {
        val songs = lanternsSongs() + song("Brennmoor", 43, "Brennmoor", "Lanterns", track = 3)
        library = songs
        answers("Lanterns" to release(ID_A, "Lanterns of Ulso", listOf("Quiet Ember", "Salt Road", "Brennmoor")))
        val flow = flow()
        val waiting = checked(flow).waiting("Lanterns")
        rig.busy.inUse += songs[0].path
        val pending = fileState(File(songs[2].path))!!
        rig.journal.open(WriteIntent("left-1", songs[2].path, 43, "left-1.mp3", pending, pending))
        val before = listOf(rig.bytes(songs[0].target()), rig.bytes(songs[2].target()))

        val applied = flow.applyWhole(waiting) as Applied.Done

        assertEquals(1, applied.songs)
        assertEquals(setOf(SongOutcome.BUSY, SongOutcome.RECOVERY_PENDING), applied.problems.map { it.outcome }.toSet())
        assertArrayEquals(before[0], rig.bytes(songs[0].target()))
        assertArrayEquals(before[1], rig.bytes(songs[2].target()))
        assertEquals(applied.problems, flow.state.value.done.single().problems)
    }

    @Test
    fun `when nothing could be written the album stays where it was`() = runTest {
        val songs = lanternsSongs()
        library = songs
        answers("Lanterns" to lanterns)
        val flow = flow()
        val waiting = checked(flow).waiting("Lanterns")
        rig.busy.inUse += songs.map { it.path }

        val applied = flow.applyWhole(waiting) as Applied.Done

        assertEquals(0, applied.albums)
        assertEquals(listOf(SongOutcome.BUSY, SongOutcome.BUSY), applied.problems.map { it.outcome })
        assertEquals(1, flow.state.value.ready.size)
        assertEquals(emptyList<AlbumRecord>(), flow.state.value.done)
    }

    @Test
    fun `without notification access nothing is written`() = runTest {
        val songs = lanternsSongs()
        library = songs
        answers("Lanterns" to lanterns)
        val flow = flow()
        val waiting = checked(flow).waiting("Lanterns")
        val before = rig.bytes(songs[0].target())
        access = false

        assertEquals(Applied.NoAccess, flow.applyWhole(waiting))
        assertArrayEquals(before, rig.bytes(songs[0].target()))
        assertEquals(1, flow.state.value.ready.size)

        // Access on, but the sessions can't be listed after all: the writer refuses the same way.
        access = true
        rig.busy.unknown = true
        assertEquals(Applied.NoAccess, flow.applyWhole(waiting))
        assertArrayEquals(before, rig.bytes(songs[0].target()))
        assertEquals(emptyList<HistoryEntry>(), rig.undo.memory().entries)
    }

    // Skip and Check again

    @Test
    fun `Skip moves the album to Skipped, and the next check leaves it out`() = runTest {
        library = lanternsSongs()
        answers("Lanterns" to lanterns)
        val flow = flow()
        val waiting = checked(flow).waiting("Lanterns")

        assertTrue(flow.skip(waiting.album.id))

        assertEquals(emptyList<Waiting>(), flow.state.value.waiting)
        assertEquals(listOf("Lanterns"), flow.state.value.skipped.map { it.title })
        flow.check()
        advanceUntilIdle()
        assertEquals(1, source.checked.size)
        assertEquals(emptyList<Waiting>(), flow.state.value.waiting)
    }

    @Test
    fun `a skipped album is checked again once one of its files changed`() = runTest {
        val songs = lanternsSongs()
        library = songs
        answers("Lanterns" to lanterns)
        val flow = flow()
        flow.skip(checked(flow).waiting("Lanterns").album.id)
        File(songs[1].path).appendBytes(byteArrayOf(0))

        flow.check()
        advanceUntilIdle()

        assertEquals(2, source.checked.size)
        assertEquals(1, flow.state.value.ready.size)
        assertEquals(emptyList<AlbumRecord>(), flow.state.value.skipped)
    }

    @Test
    fun `Check again looks the skipped album up afresh, skipping the cache`() = runTest {
        library = lanternsSongs()
        answers("Lanterns" to lanterns)
        val flow = flow()
        flow.skip(checked(flow).waiting("Lanterns").album.id)

        flow.checkAgain(flow.state.value.skipped.single().id)
        advanceUntilIdle()

        assertEquals(listOf(false, true), source.checked.map { it.second })
        assertEquals(emptyList<AlbumRecord>(), flow.state.value.skipped)
        assertEquals(1, flow.state.value.ready.size)
    }

    @Test
    fun `Done albums are left out of the next check`() = runTest {
        library = lanternsSongs()
        answers("Lanterns" to lanterns)
        val flow = flow()
        val waiting = checked(flow).waiting("Lanterns")
        flow.applyWhole(waiting)

        flow.check()
        advanceUntilIdle()

        assertEquals(1, source.checked.size)
        assertEquals(1, flow.state.value.done.size)
    }

    @Test
    fun `Not this one moves a Ready album to Pick one, and Search myself fills its candidates`() = runTest {
        library = lanternsSongs()
        answers("Lanterns" to lanterns)
        val flow = flow()
        val waiting = checked(flow).waiting("Lanterns")

        flow.notThisOne(waiting.album.id, waiting.match())
        assertEquals(Proposal.PickOne(emptyList(), emptySet()), flow.state.value.waiting.single().proposal)

        source.searchAnswer = { album, _, _ -> Lookup.Done(Proposal.PickOne(listOf(AlbumMatch.match(album, lanterns)), setOf(Doubt.SEARCHED))) }
        assertEquals(SearchOutcome.Found(1), flow.search(waiting.album.id, "Lanterns of Ulso", "Mira Solen"))
        assertEquals(listOf("Lanterns of Ulso" to "Mira Solen"), source.searched)
        assertEquals(ID_A, (flow.state.value.waiting.single().proposal as Proposal.PickOne).candidates.single().release.id)
    }

    // Undo and History

    @Test
    fun `Undo on a Done album puts every song back and the album leaves Done`() = runTest {
        val songs = lanternsSongs()
        library = songs
        answers("Lanterns" to lanterns)
        val flow = flow()
        val waiting = checked(flow).waiting("Lanterns")
        val before = songs.map { rig.bytes(it.target()) }
        flow.applyWhole(waiting)

        val undone = flow.undoAlbum(flow.state.value.done.single().id)

        assertEquals(Undone.Done(songs = 2, skippedFields = 0, changedSince = 0, problems = emptyList()), undone)
        songs.forEachIndexed { i, song -> assertArrayEquals(before[i], rig.bytes(song.target())) }
        assertEquals(emptyList<AlbumRecord>(), flow.state.value.done)
        assertTrue(rig.undo.memory().entries.single().songs.all { it.undone })
    }

    @Test
    fun `Undo through History skips a field edited again since, and puts the rest back`() = runTest {
        val songs = lanternsSongs()
        library = songs
        answers("Lanterns" to lanterns)
        val flow = flow()
        val waiting = checked(flow).waiting("Lanterns")
        val before = rig.bytes(songs[1].target())
        flow.applyWhole(waiting)
        val entry = rig.undo.memory().entries.single().id
        rig.now += 1_000
        rig.writer.write(listOf(WriteJob(songs[0].target(), TagEdit(fields = mapOf("TITLE" to listOf("Quiet Ember (Live)"))))), EditKind.SONG, "Quiet Ember")

        val undone = flow.undo(entry) as Undone.Done

        assertEquals(2, undone.songs)
        assertEquals(1, undone.skippedFields)
        val first = rig.fields(songs[0].target())
        assertEquals(listOf("Quiet Ember (Live)"), first["TITLE"])
        assertEquals(listOf("Lanterns"), first["ALBUM"])
        assertNull(first["ALBUMARTIST"])
        assertArrayEquals(before, rig.bytes(songs[1].target()))
        assertEquals(emptyList<AlbumRecord>(), flow.state.value.done)
    }

    @Test
    fun `a favourited album keeps its heart after a Covers & tags write and gets it back after Undo through History`() = runTest {
        val songs = lanternsSongs()
        library = songs
        val heart = FavouriteCollection(CollectionKind.ALBUM, checkNotNull(albumKey(songs[0])))
        favourites.now.value = listOf(heart)
        answers("Lanterns" to lanterns)
        val flow = flow()
        val waiting = checked(flow).waiting("Lanterns")

        flow.applyWhole(waiting)

        // The album is "Lanterns of Ulso" by Mira Solen now: its heart moved there.
        val written = songs.map { it.copy(album = "Lanterns of Ulso", albumArtist = "Mira Solen") }
        val moved = FavouriteCollection(CollectionKind.ALBUM, checkNotNull(albumKey(written[0])))
        assertEquals(listOf(moved), favourites.now.value)

        // History's Undo, the library reading the names the write left.
        library = written
        val entry = flow.state.value.history.single().id
        undoEdit(rig.writer, rig.undo, favourites, read(), entry)

        assertEquals(listOf(heart), favourites.now.value)
        assertEquals(listOf("Lanterns"), rig.fields(songs[0].target())["ALBUM"])
    }

    @Test
    fun `an Undo whose History can't be read changes nothing, says so, and keeps the album Done`() = runTest {
        val songs = lanternsSongs()
        library = songs
        answers("Lanterns" to lanterns)
        val flow = flow()
        val waiting = checked(flow).waiting("Lanterns")
        flow.applyWhole(waiting)
        val written = songs.map { rig.bytes(it.target()) }
        rig.dirs.undo.setReadable(false)
        rig.restart()
        val undone = try {
            flow.undoAlbum(flow.state.value.done.single().id)
        } finally {
            rig.dirs.undo.setReadable(true)
        }

        assertEquals(Undone.Failed, undone)
        songs.forEachIndexed { i, song -> assertArrayEquals(written[i], rig.bytes(song.target())) }
        assertEquals(1, flow.state.value.done.size)
    }

    @Test
    fun `undo without notification access writes nothing`() = runTest {
        library = lanternsSongs()
        answers("Lanterns" to lanterns)
        val flow = flow()
        val waiting = checked(flow).waiting("Lanterns")
        flow.applyWhole(waiting)
        access = false

        assertEquals(Undone.NoAccess, flow.undoAlbum(flow.state.value.done.single().id))
        assertEquals(1, flow.state.value.done.size)
    }

    // Effects on the rest of KJB

    @Test
    fun `the cover key is bumped for every song written, on apply and on undo`() = runTest {
        val songs = lanternsSongs()
        library = songs
        answers("Lanterns" to lanterns)
        val flow = flow()
        val waiting = checked(flow).waiting("Lanterns")
        rig.busy.inUse += songs[1].path

        flow.applyWhole(waiting)
        assertEquals(listOf(songs[0].path), bumped)

        flow.undoAlbum(flow.state.value.done.single().id)
        assertEquals(listOf(songs[0].path, songs[0].path), bumped)

        val path = "/made/up/Quiet Ember.mp3"
        val version = CoverVersions.of(path)
        CoverVersions.bump(listOf(path))
        assertEquals(version + 1, CoverVersions.of(path))
    }

    @Test
    fun `each song written goes to the lyrics with its new names, and whether its title or artist changed`() = runTest {
        val songs = lanternsSongs() + song("Brennmoor", 43, "Brennmoor", "Lanterns", track = 3)
        library = songs
        answers("Lanterns" to release(ID_A, "Lanterns of Ulso", listOf("Quiet Ember", "Salt Road", "Brennmoor")))
        val flow = flow()
        val waiting = checked(flow).waiting("Lanterns")
        rig.busy.inUse += songs[2].path

        flow.applyWhole(waiting)
        advanceUntilIdle()

        assertEquals(
            listOf(LocalTrack(songs[0].path, "Quiet Ember", "Mira Solen", "Lanterns of Ulso", 200_000) to true, LocalTrack(songs[1].path, "Salt Road", "Mira Solen", "Lanterns of Ulso", 200_000) to false),
            handedToLyrics,
        )
    }

    @Test
    fun `an Opus song gets the small cover, and keeps its own only when even that won't fit`() {
        val main = Cover(ByteArray(300 * 1024) { 7 }, ImageSize(1000, 1000))
        val small = Cover(ByteArray(30 * 1024) { 5 }, ImageSize(400, 400))

        assertEquals(CoverEdit.Replace(TagPicture(main.jpeg, "", FRONT_COVER, "image/jpeg")), coverFor("/m/Salt Road.mp3", main, small))
        assertEquals(CoverEdit.Replace(TagPicture(small.jpeg, "", FRONT_COVER, "image/jpeg")), coverFor("/m/Salt Road.opus", main, small))
        assertEquals(CoverEdit.Keep, coverFor("/m/Salt Road.opus", main, null))
        assertEquals(CoverEdit.Keep, coverFor("/m/Salt Road.mp3", null, small))
    }

    @Test
    fun `the small Opus cover is fetched once for an album with Opus songs, and never for one without`() = runTest {
        val opus = File(rig.music, "Brennmoor.opus").apply { writeBytes(byteArrayOf(0x4F, 0x67, 0x67, 0x53)) }
        rig.songs.ids[opus.path] = 43
        val songs = lanternsSongs() + Song(SongRef(opus.path, 43), "Brennmoor", "Mira Solen", "Lanterns", null, 200_000, 3, 1, null, null, "Music/KJB test/", null, null, 0, "audio/ogg")
        library = songs + harbourSongs()
        answers("Lanterns" to release(ID_A, "Lanterns of Ulso", listOf("Quiet Ember", "Salt Road", "Brennmoor")), "Harbour" to harbour)
        val big = ByteArray(100 * 1024) { 7 }
        source.cover = MetaAnswer.Ok(Cover(big, ImageSize(1000, 1000)))
        val flow = flow()
        val state = checked(flow)

        flow.applyWhole(state.waiting("Lanterns"))
        flow.applyWhole(state.waiting("Harbour"))

        assertEquals(listOf(ID_A), source.opusCovers)
        assertArrayEquals(big, FakeTagFile().read(File(songs[0].path))!!.pictures.single().data)
    }

    // Review findings: what happens off the happy path

    /** Runs the test scheduler until [done], waiting in real time for the writer's own thread; at most about 2 s. */
    private fun TestScope.until(done: () -> Boolean) {
        repeat(200) {
            advanceUntilIdle()
            if (done()) return
            Thread.sleep(10)
        }
    }

    @Test
    fun `leaving the page while an album is written still finishes it, with Done, covers and lyrics`() = runTest {
        val songs = lanternsSongs()
        library = songs
        answers("Lanterns" to lanterns)
        val flow = flow()
        val waiting = checked(flow).waiting("Lanterns")
        val page = launch { flow.applyWhole(waiting) }
        // The page goes once the writer has started.
        rig.busy.onCheck = { page.cancel() }

        until { flow.state.value.done.isNotEmpty() && handedToLyrics.size == 2 }

        assertEquals(1, flow.state.value.done.size)
        assertEquals(songs.map { it.path }, bumped)
        assertEquals(2, handedToLyrics.size)
        assertNull(flow.state.value.applying)
    }

    @Test
    fun `a second apply of the same album while the first runs changes nothing and keeps the first one's Undo`() = runTest {
        library = lanternsSongs()
        answers("Lanterns" to lanterns)
        val flow = flow()
        val waiting = checked(flow).waiting("Lanterns")

        val first = async { flow.applyWhole(waiting) }
        val second = async { flow.applyWhole(waiting) }

        assertEquals(Applied.Done(albums = 1, songs = 2, problems = emptyList()), (first.await() as Applied.Done).copy(entryId = null))
        assertEquals(Applied.InProgress, second.await())
        val done = flow.state.value.done.single()
        assertEquals(rig.undo.memory().entries.single().id, done.entryId)
        assertEquals(2, done.changed)
    }

    @Test
    fun `Check again keeps the skipped album when its lookup fails`() = runTest {
        library = lanternsSongs()
        answers("Lanterns" to lanterns)
        val flow = flow()
        flow.skip(checked(flow).waiting("Lanterns").album.id)
        source.answer = { Lookup.Failed("offline") }

        flow.checkAgain(flow.state.value.skipped.single().id)
        advanceUntilIdle()

        assertEquals(listOf("Lanterns"), flow.state.value.skipped.map { it.title })
        assertEquals(emptyList<Waiting>(), flow.state.value.waiting)
    }

    @Test
    fun `an album one of whose files changed since the check is not written, and is checked again`() = runTest {
        val songs = lanternsSongs()
        library = songs
        answers("Lanterns" to lanterns)
        val flow = flow()
        val waiting = checked(flow).waiting("Lanterns")
        // Replaced in place: the same path and MediaStore id, other content.
        File(songs[1].path).appendBytes(byteArrayOf(1, 2, 3))
        val before = songs.map { rig.bytes(it.target()) }

        assertEquals(Applied.ChangedSince, flow.applyWhole(waiting))

        songs.forEachIndexed { i, song -> assertArrayEquals(before[i], rig.bytes(song.target())) }
        assertEquals(emptyList<HistoryEntry>(), rig.undo.memory().entries)
        assertEquals(emptyList<Waiting>(), flow.state.value.waiting)
    }

    @Test
    fun `a Done album whose record was lost to a kill is rebuilt from History at the next start`() = runTest {
        val songs = lanternsSongs()
        library = songs
        answers("Lanterns" to lanterns)
        // covers-tags.json can't be written: as if the app died right after the write.
        val blocked = File(folder.root, "not a folder").apply { writeText("") }
        val dying = CoversTags(
            songs = { library }, source = source, write = { jobs, _, kind, label, progress -> rig.writer.write(jobs, kind, label, progress) }, undoEntry = rig.writer::undo,
            undo = rig.undo, store = CoversTagsStore(File(blocked, "covers-tags.json")),
            lyrics = { _, _ -> }, coversChanged = {}, access = { true }, scope = this,
            io = StandardTestDispatcher(testScheduler), compute = StandardTestDispatcher(testScheduler), clock = { BASE },
        )
        val waiting = checked(dying).waiting("Lanterns")
        dying.applyWhole(waiting)
        val entry = rig.undo.memory().entries.single()

        val again = flow()
        advanceUntilIdle()

        val done = again.state.value.done.single()
        assertEquals(entry.id, done.entryId)
        assertEquals("Lanterns of Ulso", done.title)
        assertEquals(2, done.changed)
        again.check()
        advanceUntilIdle()
        assertEquals(1, source.checked.size)
    }

    /** A second Covers & tags History entry for the album of [first], newer by [later] ms, with the same songs. */
    private fun again(first: HistoryEntry, later: Long): HistoryEntry {
        val entry = EntryInfo(first.id + later, first.kind, first.label, first.at + later)
        first.songs.forEach { assertTrue(rig.undo.record(entry, it, null)) }
        return rig.undo.memory().entries.single { it.id == entry.id }
    }

    @Test
    fun `an old covers-tags file with no historySeen gains no Done rows from History`() = runTest {
        library = lanternsSongs()
        answers("Lanterns" to lanterns)
        val flow = flow()
        val waiting = checked(flow).waiting("Lanterns")
        flow.applyWhole(waiting)
        advanceUntilIdle()
        val entry = rig.undo.memory().entries.single()
        // As a file from before historySeen was kept: no such key, and no album the History entry is missing from it.
        storeFile.writeText("""{"version":1,"checkedOnce":true,"noted":true,"albums":[]}""")

        val reloaded = flow()
        advanceUntilIdle()

        assertEquals(emptyList<AlbumRecord>(), reloaded.state.value.done)
        // From now on only History entries newer than the newest one now count.
        assertEquals(entry.id, CoversTagsStore(storeFile).load().historySeen)
        val later = flow()
        advanceUntilIdle()
        assertEquals(emptyList<AlbumRecord>(), later.state.value.done)
    }

    @Test
    fun `two History entries for one album give two Done rows, one per apply, each with its own Undo`() = runTest {
        library = lanternsSongs()
        answers("Lanterns" to lanterns)
        val flow = flow()
        val waiting = checked(flow).waiting("Lanterns")
        flow.applyWhole(waiting)
        advanceUntilIdle()
        val older = rig.undo.memory().entries.single()
        val newer = again(older, later = 60_000)
        // Neither album record kept, nothing settled seen: both entries look lost.
        CoversTagsStore(storeFile).save(CoversTagsStore(storeFile).load().copy(albums = emptyList(), historySeen = 0))

        val reloaded = flow()
        advanceUntilIdle()

        assertEquals(listOf(newer.id, older.id), reloaded.state.value.done.map { it.entryId })
    }

    @Test
    fun `a Done row kept for an album is never replaced by an older History entry, which gets its own row`() = runTest {
        library = lanternsSongs()
        answers("Lanterns" to lanterns)
        val flow = flow()
        val waiting = checked(flow).waiting("Lanterns")
        flow.applyWhole(waiting)
        advanceUntilIdle()
        val older = rig.undo.memory().entries.single()
        val newer = again(older, later = 60_000)
        // The album's record is the newer entry's; the older entry has none, and nothing settled is seen.
        val stored = CoversTagsStore(storeFile).load()
        val record = stored.albums.single().copy(entryId = newer.id, at = newer.at)
        CoversTagsStore(storeFile).save(stored.copy(albums = listOf(record), historySeen = 0))

        val reloaded = flow()
        advanceUntilIdle()

        assertEquals(listOf(newer.id, older.id), reloaded.state.value.done.map { it.entryId })
        assertEquals(record, reloaded.state.value.done.first())
    }

    // The song sheet
    // The song sheet

    @Test
    fun `the song sheet opens the review of the song's album, checking that album when needed`() = runTest {
        val songs = lanternsSongs() + harbourSongs()
        library = songs
        answers("Lanterns" to lanterns)
        val flow = flow()
        advanceUntilIdle()

        flow.open(songs[1].path)
        advanceUntilIdle()

        val album = flow.state.value.waiting.single()
        assertEquals(Focus(album.album.id, songs[1].path), flow.state.value.focus)
        assertEquals(listOf("Lanterns"), source.checked.map { it.first })
        flow.focused()
        assertNull(flow.state.value.focus)

        flow.open(songs[0].path)
        advanceUntilIdle()
        assertEquals(Focus(album.album.id, songs[0].path), flow.state.value.focus)
        assertEquals(1, source.checked.size)
    }

    // What is kept

    @Test
    fun `Done and Skipped survive a restart`() = runTest {
        library = lanternsSongs() + harbourSongs()
        answers("Lanterns" to lanterns, "Harbour" to harbour)
        val flow = flow()
        val state = checked(flow)
        flow.applyWhole(state.waiting("Lanterns"))
        flow.skip(state.waiting("Harbour").album.id)

        val again = flow()
        advanceUntilIdle()

        assertEquals(flow.state.value.memory, again.state.value.memory)
        assertEquals(1, again.state.value.done.size)
        assertEquals(1, again.state.value.skipped.size)
        assertTrue(again.state.value.memory.checkedOnce)
    }

    @Test
    fun `what a check found stays after KJB closes, and comes back without asking MusicBrainz`() = runTest {
        library = lanternsSongs() + harbourSongs() + song("Lone Kestrel", 61, "Lone Kestrel", album = null)
        answers("Lanterns" to lanterns)
        val state = checked(flow())

        val again = flow()
        advanceUntilIdle()

        assertEquals(3, state.waiting.size)
        assertEquals(state.waiting.toSet(), again.state.value.waiting.toSet())
        assertEquals(1, again.state.value.badge)
        assertEquals(3, source.checked.size)
    }

    @Test
    fun `a check leaves out the albums checked before and unchanged since, and checks the new and the changed`() = runTest {
        val songs = lanternsSongs()
        library = songs + harbourSongs()
        answers("Lanterns" to lanterns)
        val flow = flow()
        checked(flow)
        File(songs[1].path).appendBytes(byteArrayOf(0))
        library = library!! + song("Lone Kestrel", 61, "Lone Kestrel", album = null)

        flow.check()
        advanceUntilIdle()

        assertEquals(listOf("Lanterns", "Harbour", "Lanterns", null), source.checked.map { it.first })
        assertEquals(3, flow.state.value.waiting.size)
    }

    @Test
    fun `a suggestion kept from before KJB closed can be applied at any time, with its Undo in History`() = runTest {
        val songs = lanternsSongs()
        library = songs
        answers("Lanterns" to lanterns)
        checked(flow())

        val again = flow()
        advanceUntilIdle()
        val applied = again.applyWhole(again.state.value.waiting("Lanterns")) as Applied.Done

        assertEquals(2, applied.songs)
        assertEquals(listOf("Quiet Ember"), rig.fields(songs[0].target())["TITLE"])
        assertEquals(applied.entryId, again.state.value.done.single().entryId)
        assertEquals(applied.entryId, rig.undo.memory().entries.single().id)
        // Applied, it waits no more, after the next start either.
        val third = flow()
        advanceUntilIdle()
        assertEquals(emptyList<Waiting>(), third.state.value.waiting)
        assertEquals(1, source.checked.size)
    }

    @Test
    fun `Not this one, Search myself and an album's year found later are kept after KJB closes`() = runTest {
        library = lanternsSongs() + harbourSongs()
        answers("Lanterns" to lanterns, "Harbour" to harbour)
        val flow = flow()
        val state = checked(flow)
        flow.notThisOne(state.waiting("Lanterns").album.id, state.waiting("Lanterns").match())
        source.searchAnswer = { album, _, _ -> Lookup.Done(Proposal.PickOne(listOf(AlbumMatch.match(album, harbour.copy(releaseGroup = MbReleaseGroup(GROUP)))), setOf(Doubt.SEARCHED))) }
        val harbourId = state.waiting("Harbour").album.id
        flow.search(harbourId, "Harbour Glass", "Kit Calloway")
        source.datedOf = { MetaAnswer.Ok(it.copy(release = it.release.copy(releaseGroup = MbReleaseGroup(GROUP, "2001-05-02")))) }
        flow.date(harbourId, (flow.state.value.waiting("Harbour").proposal as Proposal.PickOne).candidates.single())
        advanceUntilIdle()

        val again = flow()
        advanceUntilIdle()

        assertEquals(flow.state.value.waiting.toSet(), again.state.value.waiting.toSet())
        assertEquals(Proposal.PickOne(emptyList(), emptySet()), again.state.value.waiting("Lanterns").proposal)
        val searched = again.state.value.waiting("Harbour").proposal as Proposal.PickOne
        assertEquals(setOf(Doubt.SEARCHED), searched.doubts)
        assertEquals(2001, searched.candidates.single().release.releaseGroup?.originalYear)
    }

    @Test
    fun `an album changed or gone since its check is not brought back, and the changed one is checked again`() = runTest {
        val songs = lanternsSongs()
        library = songs + harbourSongs()
        answers("Lanterns" to lanterns)
        checked(flow())
        val kept = File(folder.root, "files/covers-tags-checks")
        assertEquals(2, kept.list()!!.size)
        File(songs[0].path).appendBytes(byteArrayOf(0))
        library = songs

        val again = flow()
        advanceUntilIdle()
        assertEquals(emptyList<Waiting>(), again.state.value.waiting)
        assertEquals(0, kept.list()!!.size)

        again.check()
        advanceUntilIdle()
        assertEquals(listOf("Lanterns", "Harbour", "Lanterns"), source.checked.map { it.first })
        assertEquals(1, again.state.value.ready.size)
        assertEquals(1, kept.list()!!.size)
    }

    @Test
    fun `the checks kept read leniently, and a file this version can't read is dropped`() {
        val store = CoversTagsStore(storeFile)
        val one = CheckedAlbum("0a1b2c", listOf(FileMark("/m/a.mp3", 10, 5)), ready = true, candidates = listOf(KeptCandidate(lanterns, 0.01)), at = 7)
        val two = CheckedAlbum("0d0e0f", emptyList(), ready = false, doubts = setOf(Doubt.SINGLE, Doubt.NO_CANDIDATE))
        assertTrue(store.saveCheck(one))
        assertTrue(store.saveCheck(two))
        assertFalse(store.saveCheck(two.copy(id = "../x")))
        val folder = File(storeFile.parentFile, "covers-tags-checks")
        File(folder, "0d0e0f.json").writeText(File(folder, "0d0e0f.json").readText().replace("{", """{"later":1,"""))
        File(folder, "0bad.json").writeText("{not json")
        File(folder, "0cad.json").writeText("""{"id":"0cad","marks":[],"ready":false,"doubts":["MADE_UP"]}""")
        File(folder, "0aaa.json").writeText("""{"id":"0bbb","marks":[],"ready":false}""")

        assertEquals(setOf(one, two), store.loadChecks().toSet())
        assertEquals(setOf("0a1b2c.json", "0d0e0f.json"), folder.list()!!.toSet())

        store.removeCheck("0a1b2c")
        assertEquals(listOf(two), store.loadChecks())
    }

    @Test
    fun `the store reads leniently, leaving out what it can't read`() {
        val file = File(folder.root, "covers-tags.json")
        val mark = """{"path":"/m/a.mp3","size":10,"modified":5}"""
        file.writeText(
            """{"version":1,"checkedOnce":true,"noted":true,"later":"x","albums":[""" +
                """{"id":"a","state":"DONE","title":"Lanterns of Ulso","songs":[$mark],"at":7,"entryId":3,"problems":[{"path":"/m/b.mp3","outcome":"BUSY"}],"newer":1},""" +
                """{"id":"b","state":"LOST","title":"x","songs":[],"at":1},""" +
                """{"title":"no id"}]}""",
        )

        val memory = CoversTagsStore(file).load()

        assertTrue(memory.checkedOnce)
        assertTrue(memory.noted)
        val record = memory.albums.single()
        assertEquals(listOf(FileMark("/m/a.mp3", 10, 5)), record.songs)
        assertEquals(listOf(SongProblem("/m/b.mp3", SongOutcome.BUSY)), record.problems)

        file.writeText("{not json")
        assertEquals(CoversTagsMemory(), CoversTagsStore(file).load())
        assertTrue(File(file.path + ".unreadable").isFile)
    }

    @Test
    fun `what is written for a song is only the fields ticked, each with its side fields, never a blank, and the cover only when ticked`() {
        val fields = SongFields(
            title = "Tin Roof", artist = "Kit Calloway feat. Mira Solen", artists = listOf("Kit Calloway", "Mira Solen"), album = "Harbour Glass",
            albumArtist = "Kit Calloway", track = 3, trackTotal = 12, disc = 1, discTotal = null, musicBrainzReleaseId = ID_B,
            musicBrainzRecordingId = "0b7c1f4e-1111-4111-8111-000000000003", year = 1998,
        )
        val cover = CoverEdit.Replace(TagPicture(JPEG_BYTES, "", FRONT_COVER, "image/jpeg"))

        val edit = coversTagsEdit(fields, Part.entries.toSet(), cover)

        assertEquals(
            mapOf(
                "TITLE" to listOf("Tin Roof"),
                "ARTIST" to listOf("Kit Calloway feat. Mira Solen"),
                "ARTISTS" to listOf("Kit Calloway", "Mira Solen"),
                "ALBUM" to listOf("Harbour Glass"),
                "ALBUMARTIST" to listOf("Kit Calloway"),
                "TRACKNUMBER" to listOf("3/12"),
                "DISCNUMBER" to listOf("1"),
                "MUSICBRAINZ_ALBUMID" to listOf(ID_B),
                "MUSICBRAINZ_TRACKID" to listOf("0b7c1f4e-1111-4111-8111-000000000003"),
                "DATE" to listOf("1998"),
            ),
            edit.fields,
        )
        assertEquals(emptySet<String>(), edit.onlyWhereEmpty)
        assertEquals(cover, edit.cover)

        assertEquals(TagEdit(cover = cover), coversTagsEdit(fields, setOf(Part.COVER), cover))
        assertEquals(mapOf("ARTIST" to listOf("Kit Calloway feat. Mira Solen"), "ARTISTS" to listOf("Kit Calloway", "Mira Solen")), coversTagsEdit(fields, setOf(Part.ARTIST), cover).fields)
        assertEquals(CoverEdit.Keep, coversTagsEdit(fields, setOf(Part.ARTIST), cover).cover)
        assertEquals(mapOf("ALBUM" to listOf("Harbour Glass"), "MUSICBRAINZ_ALBUMID" to listOf(ID_B)), coversTagsEdit(fields, setOf(Part.ALBUM), cover).fields)
        assertFalse("DATE" in coversTagsEdit(fields.copy(year = null), Part.entries.toSet(), cover).fields)
        assertEquals(emptyMap<String, List<String>>(), coversTagsEdit(fields.copy(title = " ", musicBrainzRecordingId = ""), setOf(Part.TITLE), cover).fields)
    }

    // The review, redesigned (user feedback 2026-11-11): only what you ticked changes, nothing is taken away

    /** Nine songs of one artist tagged as one album, as the phone had them: one group. */
    private val nineTitles = listOf("Ember Line", "Salt Wind", "Tin Harbour", "Kelp Bell", "Glass Pier", "Quiet Fold", "Lantern Weir", "Copper Tide", "North Gale")

    private fun nineSongs(): List<Song> = nineTitles.mapIndexed { i, title -> song(title, 80L + i, title, "Best of Tobin Vale", artist = "Tobin Vale", track = i + 1) }

    /** The candidate a check offers for the nine: a compilation with every one of their titles, other names around them. */
    private val best = release(ID_C, "The Very Best of Tobin Vale", nineTitles, artist = "Tobin Vale & The Weir")

    private fun offerBest() {
        source.answer = { album -> Lookup.Done(Proposal.PickOne(listOf(AlbumMatch.match(album, best)), setOf(Doubt.ALBUM_NAME))) }
    }

    private fun Waiting.candidate(): ReleaseMatch = (proposal as Proposal.PickOne).candidates.single()

    /** The indices of [songs] whose bytes are no longer [before]. */
    private fun changed(songs: List<Song>, before: List<ByteArray>): List<Int> = songs.indices.filterNot { rig.bytes(songs[it].target()).contentEquals(before[it]) }

    private val oldCover = TagPicture(ByteArray(64) { 3 }, "", FRONT_COVER, "image/jpeg")

    private fun pictures(song: Song): List<TagPicture> = FakeTagFile().read(File(song.path))!!.pictures

    @Test
    fun `nine songs in one group, opened from one song's sheet, only that song is ticked, and Apply writes exactly it`() = runTest {
        val songs = nineSongs()
        library = songs
        offerBest()
        val flow = flow()
        advanceUntilIdle()
        val before = songs.map { rig.bytes(it.target()) }

        flow.open(songs[2].path)
        advanceUntilIdle()
        val focus = checkNotNull(flow.state.value.focus)
        val waiting = flow.state.value.waiting.single { it.album.id == focus.albumId }
        assertEquals(9, waiting.album.paths.size)
        val ticked = firstTicked(waiting.album, focus.song)
        assertEquals(setOf(songs[2].path), ticked)

        val applied = flow.apply(waiting.album.id, waiting.candidate(), ticked, Part.entries.toSet())

        assertEquals(listOf(2), changed(songs, before))
        assertEquals(1, (applied as Applied.Done).songs)
        assertEquals(listOf("The Very Best of Tobin Vale"), rig.fields(songs[2].target())["ALBUM"])
    }

    @Test
    fun `opened from the list nothing is ticked, and Apply with 2 of the 9 ticked writes exactly those 2`() = runTest {
        val songs = nineSongs()
        library = songs
        offerBest()
        val flow = flow()
        val waiting = checked(flow).waiting.single()
        val before = songs.map { rig.bytes(it.target()) }
        assertEquals(emptySet<String>(), firstTicked(waiting.album, null))

        val applied = flow.apply(waiting.album.id, waiting.candidate(), setOf(songs[0].path, songs[5].path), Part.entries.toSet())

        assertEquals(listOf(0, 5), changed(songs, before))
        assertEquals(2, (applied as Applied.Done).songs)
    }

    @Test
    fun `only Cover ticked writes the picture and leaves every text field as it was, only Artist ticked leaves the picture`() = runTest {
        val songs = listOf(
            song("Quiet Embers", 41, "Quiet Embers", "Lanterns", artist = "Mira Solenn", track = 1, pictures = listOf(oldCover)),
            song("Salt Road", 42, "Salt Road", "Lanterns", artist = "Mira Solenn", track = 2, pictures = listOf(oldCover)),
        )
        library = songs
        answers("Lanterns" to lanterns)
        val flow = flow()
        val waiting = checked(flow).waiting("Lanterns")
        val fieldsBefore = songs.map { rig.fields(it.target()) }

        flow.apply(waiting.album.id, waiting.match(), setOf(songs[0].path), setOf(Part.COVER))

        assertEquals(fieldsBefore[0], rig.fields(songs[0].target()))
        assertArrayEquals(JPEG_BYTES, pictures(songs[0]).single().data)

        val again = checked(flow).waiting("Lanterns")
        flow.apply(again.album.id, again.match(), setOf(songs[1].path), setOf(Part.ARTIST))

        assertEquals(listOf(oldCover), pictures(songs[1]))
        assertEquals(listOf("Mira Solen"), rig.fields(songs[1].target())["ARTIST"])
        assertEquals(fieldsBefore[1] - "ARTIST" - "ARTISTS", rig.fields(songs[1].target()) - "ARTIST" - "ARTISTS")
    }

    @Test
    fun `with no new cover found a song keeps its own cover after an apply of its other fields`() = runTest {
        val songs = listOf(song("Quiet Embers", 41, "Quiet Embers", "Lanterns", track = 1, pictures = listOf(oldCover)), song("Salt Road", 42, "Salt Road", "Lanterns", track = 2))
        library = songs
        answers("Lanterns" to lanterns)
        source.cover = MetaAnswer.NotFound
        val flow = flow()
        val waiting = checked(flow).waiting("Lanterns")

        val applied = flow.apply(waiting.album.id, waiting.match(), setOf(songs[0].path), Part.entries.toSet())

        assertEquals(listOf(oldCover), pictures(songs[0]))
        assertEquals(listOf("Quiet Ember"), rig.fields(songs[0].target())["TITLE"])
        assertEquals(1, (applied as Applied.Done).songs)
    }

    @Test
    fun `an album with no suggestion is not listed in To review`() = runTest {
        library = lanternsSongs() + harbourSongs()
        answers("Lanterns" to lanterns)

        val state = checked(flow())

        assertEquals(2, state.waiting.size)
        assertEquals(listOf("Lanterns"), state.toReview.map { it.album.album.title })
        assertEquals(1, state.badge)
    }

    @Test
    fun `Undo from Done puts back exactly the songs the apply wrote, by their audio, and leaves the rest alone`() = runTest {
        val songs = nineSongs()
        library = songs
        offerBest()
        val flow = flow()
        val waiting = checked(flow).waiting.single()
        val before = songs.map { rig.bytes(it.target()) }
        val payloads = songs.map { PayloadHash.of(File(it.path).toPath()) }

        flow.apply(waiting.album.id, waiting.candidate(), setOf(songs[1].path, songs[7].path), Part.entries.toSet())
        assertEquals(listOf(1, 7), changed(songs, before))
        val done = flow.state.value.done.single()
        assertEquals(2, done.changed)

        val undone = flow.undoAlbum(done.id)

        assertEquals(2, (undone as Undone.Done).songs)
        assertEquals(emptyList<Int>(), changed(songs, before))
        assertEquals(payloads, songs.map { PayloadHash.of(File(it.path).toPath()) })
        assertEquals(setOf(songs[1].path, songs[7].path), bumped.toSet())
    }

    @Test
    fun `each apply is its own Done row with its own Undo`() = runTest {
        val songs = nineSongs()
        library = songs
        offerBest()
        val flow = flow()
        val waiting = checked(flow).waiting.single()
        flow.apply(waiting.album.id, waiting.candidate(), setOf(songs[0].path), setOf(Part.COVER))
        assertEquals(1, flow.state.value.done.single().changed)
        val again = checked(flow).waiting.single()
        flow.apply(again.album.id, again.candidate(), setOf(songs[3].path), setOf(Part.ALBUM))

        val done = flow.state.value.done
        assertEquals(2, done.size)
        assertEquals(2, done.mapNotNull { it.entryId }.distinct().size)
        assertEquals(listOf(listOf(songs[3].path), listOf(songs[0].path)), done.map { record -> record.songs.map { it.path } })
    }

    @Test
    fun `applying shows each song as it goes, and Cancel stops after the song being written`() = runTest {
        val songs = nineSongs()
        library = songs
        offerBest()
        val flow = flow()
        val waiting = checked(flow).waiting.single()
        val before = songs.map { rig.bytes(it.target()) }
        val seen = mutableListOf<Applying?>()
        progress = { asked ->
            Progress(
                song = { done ->
                    asked.song(done)
                    seen += flow.state.value.applying
                    if (done == 2) flow.stopApplying()
                },
                stop = asked.stop,
            )
        }

        val applied = flow.apply(waiting.album.id, waiting.candidate(), songs.take(4).map { it.path }.toSet(), Part.entries.toSet())

        assertEquals(listOf(Applying(1, 4), Applying(2, 4)), seen)
        assertEquals(listOf(0, 1), changed(songs, before))
        assertEquals(Applied.Done(albums = 1, songs = 2, problems = emptyList(), entryId = flow.state.value.done.single().entryId), applied)
        assertNull(flow.state.value.applying)
    }


    // Review findings, 2026-11-11 (Grok panel on v3/ct2-covers)

    @Test
    fun `an older Done row's Undo waits while a newer change to one of its songs stands, and the newest goes back first`() = runTest {
        val songs = nineSongs()
        library = songs
        offerBest()
        val flow = flow()
        val song = songs[0]
        val original = rig.fields(song.target())
        val waiting = checked(flow).waiting.single()
        flow.apply(waiting.album.id, waiting.candidate(), setOf(song.path), setOf(Part.COVER))
        val again = checked(flow).waiting.single()
        flow.apply(again.album.id, again.candidate(), setOf(song.path), setOf(Part.ARTIST))
        advanceUntilIdle()
        val (newer, older) = flow.state.value.done
        val both = rig.bytes(song.target())

        assertTrue(undoBlocked(checkNotNull(older.entryId), flow.state.value.history))
        assertFalse(undoBlocked(checkNotNull(newer.entryId), flow.state.value.history))
        assertEquals(Undone.Blocked, flow.undoAlbum(older.id))
        assertArrayEquals(both, rig.bytes(song.target()))

        // The newest first: the song is as it was before that apply, the cover of the one before kept.
        assertTrue(flow.undoAlbum(newer.id) is Undone.Done)
        assertEquals(original["ARTIST"], rig.fields(song.target())["ARTIST"])
        assertArrayEquals(JPEG_BYTES, pictures(song).single().data)
        advanceUntilIdle()
        assertFalse(undoBlocked(checkNotNull(older.entryId), flow.state.value.history))
        assertTrue(flow.undoAlbum(older.id) is Undone.Done)
        assertEquals(original, rig.fields(song.target()))
        assertEquals(emptyList<TagPicture>(), pictures(song))
    }

    @Test
    fun `the candidate picked gets its album's first year when its answer did not carry it, once, so Year can be offered`() = runTest {
        library = listOf(song("Lone Kestrel", 61, "Lone Kestrel", album = null))
        val undated = release(ID_A, "Kestrel Lights", listOf("Lone Kestrel"), firstYear = null)
        source.answer = { album -> Lookup.Done(Proposal.PickOne(listOf(AlbumMatch.match(album, undated)), setOf(Doubt.SINGLE))) }
        source.datedOf = { match -> MetaAnswer.Ok(match.copy(release = match.release.copy(releaseGroup = MbReleaseGroup(GROUP, "1998")))) }
        val flow = flow()
        val waiting = checked(flow).waiting.single()
        assertFalse(Part.YEAR in flow.changes(waiting, waiting.candidate()).parts)

        flow.date(waiting.album.id, waiting.candidate())
        flow.date(waiting.album.id, flow.state.value.waiting.single().candidate())

        val dated = flow.state.value.waiting.single()
        assertEquals(1998, dated.candidate().release.releaseGroup?.originalYear)
        assertTrue(Part.YEAR in flow.changes(dated, dated.candidate()).parts)
        assertEquals(listOf(ID_A), source.datedAsked)
    }

    @Test
    fun `a cover the Cover Art Archive turns out not to have is remembered, so an album with nothing else to change leaves To review`() = runTest {
        library = lanternsSongs()
        answers("Lanterns" to lanterns)
        source.previewOf = { MetaAnswer.NotFound }
        val flow = flow()
        val waiting = checked(flow).waiting("Lanterns")

        assertEquals(Preview.None, flow.preview(waiting.match().release))

        assertEquals(setOf(ID_A), flow.state.value.noCover)
    }

    @Test
    fun `Cancel while the cover downloads stops it at once and writes nothing`() = runTest {
        val songs = lanternsSongs()
        library = songs
        answers("Lanterns" to lanterns)
        source.coverDelay = 30_000
        val flow = flow()
        val waiting = checked(flow).waiting("Lanterns")
        val before = songs.map { rig.bytes(it.target()) }

        val applying = async { flow.apply(waiting.album.id, waiting.match(), waiting.album.paths.toSet(), Part.entries.toSet()) }
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(Applying(0, 2, cover = true), flow.state.value.applying)
        flow.stopApplying()
        advanceTimeBy(1_000)
        runCurrent()

        assertTrue(applying.isCompleted)
        assertEquals(Applied.Stopped, applying.await())
        assertEquals(0, source.coversFetched)
        assertEquals(emptyList<Int>(), changed(songs, before))
        assertNull(flow.state.value.applying)
    }

    private companion object {
        const val BASE: Long = 1_800_000_000_000
        const val ID_A = "0b7c1f4e-5a2d-4c3b-9e8f-1a2b3c4d5e01"
        const val ID_B = "0b7c1f4e-5a2d-4c3b-9e8f-1a2b3c4d5e03"
        const val ID_C = "0b7c1f4e-5a2d-4c3b-9e8f-1a2b3c4d5e04"
        const val GROUP = "6f1e2d3c-4b5a-4968-8776-5a4b3c2d1e02"
    }
}

/** MusicBrainz as a test scripts it: [answer] per album, every check and search recorded; the cover a tiny JPEG. */
class FakeAlbumSource : AlbumSource {
    override val busy = MutableStateFlow(false)
    var answer: suspend (PhoneAlbum) -> Lookup = { Lookup.Failed("no answer") }
    var searchAnswer: suspend (PhoneAlbum, String, String?) -> Lookup = { _, _, _ -> Lookup.Failed("no answer") }
    var cover: MetaAnswer<Cover> = MetaAnswer.Ok(Cover(JPEG_BYTES, ImageSize(1, 1)))
    val checked = mutableListOf<Pair<String?, Boolean>>()
    val searched = mutableListOf<Pair<String, String?>>()
    val opusCovers = mutableListOf<String>()

    override suspend fun check(album: PhoneAlbum, fresh: Boolean): Lookup {
        checked += album.title to fresh
        return answer(album)
    }

    override suspend fun search(album: PhoneAlbum, title: String, artist: String?): Lookup {
        searched += title to artist
        return searchAnswer(album, title, artist)
    }

    var previewOf: (MbRelease) -> MetaAnswer<ByteArray> = { MetaAnswer.Ok(JPEG_BYTES) }
    val previewed = mutableListOf<String>()

    override suspend fun preview(release: MbRelease): MetaAnswer<ByteArray> {
        previewed += release.id
        return previewOf(release)
    }

    var coverOf: (MbRelease) -> MetaAnswer<Cover> = { cover }

    /** How long a cover takes to come, in the test's time; and how many came. */
    var coverDelay: Long = 0
    var coversFetched = 0

    override suspend fun cover(release: MbRelease): MetaAnswer<Cover> {
        delay(coverDelay)
        coversFetched++
        return coverOf(release)
    }

    var datedOf: (ReleaseMatch) -> MetaAnswer<ReleaseMatch> = { MetaAnswer.Ok(it) }
    val datedAsked = mutableListOf<String>()

    override suspend fun dated(match: ReleaseMatch): MetaAnswer<ReleaseMatch> {
        datedAsked += match.release.id
        return datedOf(match)
    }

    override suspend fun opusCover(release: MbRelease): MetaAnswer<Cover> {
        opusCovers += release.id
        return MetaAnswer.Ok(Cover(JPEG_BYTES, ImageSize(1, 1)))
    }
}
