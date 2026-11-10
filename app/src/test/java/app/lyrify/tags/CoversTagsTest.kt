package app.lyrify.tags

import app.lyrify.library.Song
import app.lyrify.library.SongRef
import app.lyrify.lyrics.LocalTrack
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
import app.lyrify.tags.lookup.PhoneAlbum
import app.lyrify.tags.lookup.Proposal
import app.lyrify.tags.lookup.ReleaseMatch
import app.lyrify.tags.lookup.SongFields
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
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

    private fun TestScope.flow(): CoversTags = CoversTags(
        songs = { library },
        source = source,
        writer = rig.writer,
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

    private fun song(name: String, id: Long, title: String, album: String?, artist: String = "Mira Solen", track: Int? = null, year: Int? = null): Song {
        val fields = buildMap {
            put("TITLE", listOf(title))
            put("ARTIST", listOf(artist))
            album?.let { put("ALBUM", listOf(it)) }
            track?.let { put("TRACKNUMBER", listOf(it.toString())) }
            year?.let { put("DATE", listOf(it.toString())) }
        }
        val target = rig.song(name, id, fields)
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

    // Checking

    @Test
    fun `a check sorts every album into Ready and Pick one, a single always Pick one`() = runTest {
        library = lanternsSongs() + harbourSongs() + song("Lone Kestrel", 61, "Lone Kestrel", album = null)
        answers("Lanterns" to lanterns)
        val flow = flow()

        val state = checked(flow)

        assertEquals(listOf("Lanterns"), state.ready.map { it.album.album.title })
        assertEquals(2, state.pickOne.size)
        assertEquals(3, state.badge)
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
    fun `MusicBrainz still busy stops the check and says so, a failed album is counted and the check goes on`() = runTest {
        library = lanternsSongs() + harbourSongs() + song("Lone Kestrel", 61, "Lone Kestrel", album = null)
        source.answer = { album ->
            when (album.title) {
                "Lanterns" -> Lookup.Failed("offline")
                "Harbour" -> Lookup.Busy
                else -> Lookup.Done(Proposal.PickOne(emptyList(), setOf(Doubt.SINGLE)))
            }
        }

        val state = checked(flow())

        assertTrue(state.stoppedBusy)
        assertEquals(3, state.notChecked)
        assertEquals(2, source.checked.size)
    }

    // Applying

    @Test
    fun `apply writes the release into each song through the safe writer and moves the album to Done`() = runTest {
        val songs = lanternsSongs()
        library = songs
        answers("Lanterns" to lanterns)
        val flow = flow()
        val waiting = checked(flow).waiting("Lanterns")

        val applied = flow.apply(waiting.album.id, waiting.match())

        assertEquals(Applied.Done(albums = 1, songs = 2, problems = emptyList()), applied)
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
    fun `the year is kept where a song has one, filled where it has none, and Keep my year off writes it everywhere`() = runTest {
        val songs = lanternsSongs(year = 2003)
        library = songs
        answers("Lanterns" to lanterns)
        val flow = flow()
        val waiting = checked(flow).waiting("Lanterns")

        flow.apply(waiting.album.id, waiting.match())

        assertEquals(listOf("2003"), rig.fields(songs[0].target())["DATE"])
        assertEquals(listOf("1998"), rig.fields(songs[1].target())["DATE"])

        val again = lanternsSongs(year = 2003).map { it }
        library = again
        val other = flow()
        val next = checked(other).waiting("Lanterns")
        other.keepMyYear(next.album.id, keep = false)
        other.apply(next.album.id, other.state.value.waiting("Lanterns").match())
        assertEquals(listOf("1998"), rig.fields(again[0].target())["DATE"])
    }

    @Test
    fun `Apply all applies every Ready album, one at a time, and leaves Pick one`() = runTest {
        library = lanternsSongs() + harbourSongs() + song("Lone Kestrel", 61, "Lone Kestrel", album = null)
        answers("Lanterns" to lanterns, "Harbour" to harbour)
        val flow = flow()
        checked(flow)

        val applied = flow.applyAll()

        assertEquals(Applied.Done(albums = 2, songs = 4, problems = emptyList()), applied)
        assertEquals(2, flow.state.value.done.size)
        assertEquals(1, flow.state.value.pickOne.size)
        assertEquals(emptyList<Waiting>(), flow.state.value.ready)
        assertNull(flow.state.value.applying)
        assertEquals(1, rig.tags.mostAtOnce)
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

        val applied = flow.apply(waiting.album.id, waiting.match()) as Applied.Done

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

        val applied = flow.apply(waiting.album.id, waiting.match()) as Applied.Done

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

        assertEquals(Applied.NoAccess, flow.apply(waiting.album.id, waiting.match()))
        assertEquals(Applied.NoAccess, flow.applyAll())
        assertArrayEquals(before, rig.bytes(songs[0].target()))
        assertEquals(1, flow.state.value.ready.size)

        // Access on, but the sessions can't be listed after all: the writer refuses the same way.
        access = true
        rig.busy.unknown = true
        assertEquals(Applied.NoAccess, flow.apply(waiting.album.id, waiting.match()))
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
        flow.apply(waiting.album.id, waiting.match())

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
        flow.apply(waiting.album.id, waiting.match())

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
        flow.apply(waiting.album.id, waiting.match())
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
    fun `undo without notification access writes nothing`() = runTest {
        library = lanternsSongs()
        answers("Lanterns" to lanterns)
        val flow = flow()
        val waiting = checked(flow).waiting("Lanterns")
        flow.apply(waiting.album.id, waiting.match())
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

        flow.apply(waiting.album.id, waiting.match())
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

        flow.apply(waiting.album.id, waiting.match())
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
        checked(flow)

        flow.applyAll()

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
        val page = launch { flow.apply(waiting.album.id, waiting.match()) }
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

        val first = async { flow.apply(waiting.album.id, waiting.match()) }
        val second = async { flow.apply(waiting.album.id, waiting.match()) }

        assertEquals(Applied.Done(albums = 1, songs = 2, problems = emptyList()), first.await())
        assertEquals(Applied.InProgress, second.await())
        val done = flow.state.value.done.single()
        assertEquals(rig.undo.memory().entries.single().id, done.entryId)
        assertEquals(2, done.changed)
    }

    @Test
    fun `Apply all stopped by a busy MusicBrainz still reports the albums it changed`() = runTest {
        library = lanternsSongs() + harbourSongs()
        answers("Lanterns" to lanterns, "Harbour" to harbour)
        source.coverOf = { release -> if (release.id == ID_B) MetaAnswer.Busy else source.cover }
        val flow = flow()
        checked(flow)

        val applied = flow.applyAll()

        assertEquals(Applied.Done(albums = 1, songs = 2, problems = emptyList(), stopped = Stopped.BUSY), applied)
        assertEquals(1, flow.state.value.done.size)
        assertEquals(1, flow.state.value.ready.size)
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

        assertEquals(Applied.ChangedSince, flow.apply(waiting.album.id, waiting.match()))

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
            songs = { library }, source = source, writer = rig.writer, undo = rig.undo, store = CoversTagsStore(File(blocked, "covers-tags.json")),
            lyrics = { _, _ -> }, coversChanged = {}, access = { true }, scope = this,
            io = StandardTestDispatcher(testScheduler), compute = StandardTestDispatcher(testScheduler), clock = { BASE },
        )
        val waiting = checked(dying).waiting("Lanterns")
        dying.apply(waiting.album.id, waiting.match())
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
        assertEquals(album.album.id, flow.state.value.focus)
        assertEquals(listOf("Lanterns"), source.checked.map { it.first })
        flow.focused()
        assertNull(flow.state.value.focus)

        flow.open(songs[0].path)
        advanceUntilIdle()
        assertEquals(album.album.id, flow.state.value.focus)
        assertEquals(1, source.checked.size)
    }

    // What is kept

    @Test
    fun `Done and Skipped survive a restart`() = runTest {
        library = lanternsSongs() + harbourSongs()
        answers("Lanterns" to lanterns, "Harbour" to harbour)
        val flow = flow()
        val state = checked(flow)
        flow.apply(state.waiting("Lanterns").album.id, state.waiting("Lanterns").match())
        flow.skip(state.waiting("Harbour").album.id)

        val again = flow()
        advanceUntilIdle()

        assertEquals(flow.state.value.memory, again.state.value.memory)
        assertEquals(1, again.state.value.done.size)
        assertEquals(1, again.state.value.skipped.size)
        assertTrue(again.state.value.memory.checkedOnce)
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
    fun `what is written for a song is the fields Covers and tags owns, with the year rule and the cover`() {
        val fields = SongFields(
            title = "Tin Roof", artist = "Kit Calloway feat. Mira Solen", artists = listOf("Kit Calloway", "Mira Solen"), album = "Harbour Glass",
            albumArtist = "Kit Calloway", track = 3, trackTotal = 12, disc = 1, discTotal = null, musicBrainzReleaseId = ID_B,
            musicBrainzRecordingId = "0b7c1f4e-1111-4111-8111-000000000003", year = null,
        )
        val cover = CoverEdit.Replace(TagPicture(JPEG_BYTES, "", FRONT_COVER, "image/jpeg"))

        val edit = coversTagsEdit(fields, originalYear = 1998, keepMyYear = true, cover = cover)

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
        assertEquals(setOf("DATE"), edit.onlyWhereEmpty)
        assertEquals(cover, edit.cover)
        assertEquals(emptySet<String>(), coversTagsEdit(fields, 1998, keepMyYear = false, cover = cover).onlyWhereEmpty)
        assertFalse("DATE" in coversTagsEdit(fields, originalYear = null, keepMyYear = true, cover = cover).fields)
    }

    private companion object {
        const val BASE: Long = 1_800_000_000_000
        const val ID_A = "0b7c1f4e-5a2d-4c3b-9e8f-1a2b3c4d5e01"
        const val ID_B = "0b7c1f4e-5a2d-4c3b-9e8f-1a2b3c4d5e03"
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

    override suspend fun dated(match: ReleaseMatch): MetaAnswer<ReleaseMatch> = MetaAnswer.Ok(match)

    var coverOf: (MbRelease) -> MetaAnswer<Cover> = { cover }

    override suspend fun cover(release: MbRelease): MetaAnswer<Cover> = coverOf(release)

    override suspend fun opusCover(release: MbRelease): MetaAnswer<Cover> {
        opusCovers += release.id
        return MetaAnswer.Ok(Cover(JPEG_BYTES, ImageSize(1, 1)))
    }
}
