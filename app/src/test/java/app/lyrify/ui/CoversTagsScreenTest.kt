package app.lyrify.ui

import app.lyrify.R
import app.lyrify.tags.AlbumRecord
import app.lyrify.tags.Applied
import app.lyrify.tags.Applying
import app.lyrify.tags.Checking
import app.lyrify.tags.CoversTagsMemory
import app.lyrify.tags.CoversTagsState
import app.lyrify.tags.FileMark
import app.lyrify.tags.Settled
import app.lyrify.tags.Stopped
import app.lyrify.tags.SongOutcome
import app.lyrify.tags.SongProblem
import app.lyrify.tags.TagAlbum
import app.lyrify.tags.Undone
import app.lyrify.tags.Waiting
import app.lyrify.tags.lookup.AlbumChanges
import app.lyrify.tags.lookup.AlbumMatch
import app.lyrify.tags.lookup.Credit
import app.lyrify.tags.lookup.Doubt
import app.lyrify.tags.lookup.Fit
import app.lyrify.tags.lookup.MbMedium
import app.lyrify.tags.lookup.MbRelease
import app.lyrify.tags.lookup.MbReleaseGroup
import app.lyrify.tags.lookup.MbTrack
import app.lyrify.tags.lookup.PhoneAlbum
import app.lyrify.tags.lookup.PhoneSong
import app.lyrify.tags.lookup.Proposal
import app.lyrify.tags.lookup.TagChanges
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers & tags' page logic: the row, the sections, the dialog, the words for each outcome. Made-up names only. */
class CoversTagsScreenTest {
    private val credit = Credit(listOf(Credit.Part("Mira Solen")))

    private fun release(titles: List<String>, country: String? = "XE", date: String? = "2009", format: String? = "CD") = MbRelease(
        "0b7c1f4e-5a2d-4c3b-9e8f-1a2b3c4d5e01", "Lanterns of Ulso", credit, MbReleaseGroup("6f1e2d3c-4b5a-4968-8776-5a4b3c2d1e02", "1998"),
        date = date, country = country, trackCount = titles.size,
        media = listOf(MbMedium(1, format, titles.size, titles.mapIndexed { i, t -> MbTrack("t$i", "r$i", i + 1, t, 200_000, credit) })),
    )

    private fun album(titles: List<String>, albumTitle: String = "Lanterns"): PhoneAlbum =
        PhoneAlbum(albumTitle, "Mira Solen", titles.mapIndexed { i, t -> PhoneSong("/m/$albumTitle/$t.mp3", t, "Mira Solen", albumTitle, "Mira Solen", 1, i + 1, 200_000) })

    private fun changes(titles: List<String>, albumTitle: String = "Lanterns"): AlbumChanges =
        TagChanges.of(album(titles, albumTitle), AlbumMatch.match(album(titles, albumTitle), release(titles)))

    private fun waiting(title: String, ready: Boolean): Waiting {
        val album = album(listOf("Quiet Ember", "Salt Road"), title)
        val proposal = if (ready) Proposal.Ready(AlbumMatch.match(album, release(listOf("Quiet Ember", "Salt Road")))) else Proposal.PickOne(emptyList(), setOf(Doubt.NO_CANDIDATE))
        return Waiting(TagAlbum(album, emptyMap()), proposal)
    }

    private fun record(state: Settled, changed: Int = 0, problems: Int = 0, entryId: Long? = 7) = AlbumRecord(
        "a${state.name}", state, "Lanterns of Ulso", "Mira Solen", listOf(FileMark("/m/a.mp3", 1, 1)), at = 1, entryId = entryId, changed = changed,
        problems = List(problems) { SongProblem("/m/p$it.mp3", SongOutcome.BUSY) },
    )

    // The row on Tools and Songs

    @Test
    fun `the row reads Find covers and tags before the first check, then counts what waits`() {
        assertEquals(EntryRow(R.string.tags_find, ToolLine.Text(R.string.tags_line_start), null), coversTagsEntry(CoversTagsState(loaded = true)))
        assertEquals(ToolLine.Text(R.string.tags_line_checking), coversTagsEntry(CoversTagsState(checking = Checking(3, 84))).line)

        val checked = CoversTagsState(loaded = true, memory = CoversTagsMemory(checkedOnce = true))
        val waiting = coversTagsEntry(checked.copy(waiting = listOf(waiting("Lanterns", true), waiting("Harbour", false), waiting("Kelp", false))))
        assertEquals(EntryRow(R.string.tools_covers, ToolLine.Count(R.plurals.tags_to_review, 3), 3), waiting)

        val done = checked.copy(memory = checked.memory.copy(albums = listOf(record(Settled.DONE, 2), record(Settled.DONE, 3).copy(id = "b"))))
        assertEquals(EntryRow(R.string.tools_covers, ToolLine.Count(R.plurals.tags_albums_changed, 2), null), coversTagsEntry(done))
        assertEquals(EntryRow(R.string.tools_covers, ToolLine.Text(R.string.tags_line_none), null), coversTagsEntry(checked))
    }

    @Test
    fun `sections show in order with their counts, empty ones left out`() {
        val state = CoversTagsState(
            waiting = listOf(waiting("Kelp", false), waiting("Lanterns", true), waiting("Harbour", false)),
            memory = CoversTagsMemory(albums = listOf(record(Settled.SKIPPED))),
        )

        assertEquals(listOf(ReviewSection.READY to 1, ReviewSection.PICK_ONE to 2, ReviewSection.SKIPPED to 1), reviewSections(state))
        assertEquals(emptyList<Pair<ReviewSection, Int>>(), reviewSections(CoversTagsState()))
    }

    // The page's rows: each needs a key of its own, or the list crashes

    @Test
    fun `an album in two sections at once, or twice in one, still gives every row a key of its own`() {
        val pick = waiting("Lanterns", false)
        // "Check again" placed the album in Pick one before its Skipped record went, and History rebuilt two Done records of one album.
        val records = listOf(
            record(Settled.SKIPPED).copy(id = pick.album.id),
            record(Settled.DONE).copy(id = "harbour", entryId = 7),
            record(Settled.DONE).copy(id = "harbour", entryId = 9, at = 2),
        )
        val state = CoversTagsState(loaded = true, waiting = listOf(pick, waiting("Kelp", true)), memory = CoversTagsMemory(albums = records, checkedOnce = true))

        val keys = pageRows(state, uncheckable = 2, paused = true).map { it.key }
        assertEquals(keys.distinct(), keys)
    }

    @Test
    fun `an album's row keeps its key as albums come and go around it, and the song sheet finds it by its id`() {
        val lanterns = waiting("Lanterns", true)
        val harbour = waiting("Harbour", true)
        fun keyOf(state: CoversTagsState, id: String) = pageRows(state, 0, false).single { it.albumId == id }.key

        val alone = CoversTagsState(waiting = listOf(lanterns))
        val after = CoversTagsState(waiting = listOf(harbour, lanterns), memory = CoversTagsMemory(albums = listOf(record(Settled.DONE).copy(id = "kelp"))))
        assertEquals(keyOf(alone, lanterns.album.id), keyOf(after, lanterns.album.id))
        // The first of two Done records of one album keeps its key when the second comes.
        val done = record(Settled.DONE).copy(id = "kelp", at = 5)
        val one = CoversTagsState(memory = CoversTagsMemory(albums = listOf(done)))
        val two = CoversTagsState(memory = CoversTagsMemory(albums = listOf(done, done.copy(entryId = 9, at = 1))))
        assertEquals(pageRows(one, 0, false).single { it.albumId == "kelp" }.key, pageRows(two, 0, false).first { it.albumId == "kelp" }.key)
    }

    // Confirming

    @Test
    fun `the Apply all dialog counts the albums and the songs written, with the sync note the first time only`() {
        val albums = listOf(changes(listOf("Quiet Ember", "Salt Road")), changes(listOf("Tin Roof", "Kelp Lights", "Brennmoor"), "Harbour"))

        assertEquals(ApplyCopy(albums = 2, songs = 5, note = true), applyCopy(albums, noted = false))
        assertEquals(ApplyCopy(albums = 2, songs = 5, note = false), applyCopy(albums, noted = true))
    }

    @Test
    fun `writing is off without notification access, and while an apply runs`() {
        assertFalse(canWrite(access = false, CoversTagsState()))
        assertTrue(canWrite(access = true, CoversTagsState()))
        assertFalse(canWrite(access = true, CoversTagsState(applying = Applying(0, 1))))
    }

    // Words

    @Test
    fun `songs whose interrupted write could not be checked get one line, and none when there are none`() {
        assertEquals(null, uncheckableWords(0))
        assertEquals(Words(R.plurals.tags_uncheckable, 1, listOf(1)), uncheckableWords(1))
        assertEquals(Words(R.plurals.tags_uncheckable, 3, listOf(3)), uncheckableWords(3))
    }

    @Test
    fun `paused tag changes get one line, and none when they are not`() {
        assertEquals(null, pausedWords(false))
        assertEquals(Words(R.string.tags_paused), pausedWords(true))
    }

    @Test
    fun `every song outcome has its own words`() {
        assertEquals(R.string.tags_outcome_busy, outcomeWords(SongOutcome.BUSY))
        assertEquals(R.string.tags_outcome_recovery, outcomeWords(SongOutcome.RECOVERY_PENDING))
        assertEquals(R.string.tags_outcome_set_aside, outcomeWords(SongOutcome.SET_ASIDE))
        assertEquals(R.string.tags_paused, outcomeWords(SongOutcome.PAUSED))
        assertEquals(Words(R.string.tags_undo_failed), undoneWords(Undone.Failed))
        assertEquals(R.string.tags_outcome_left, outcomeWords(SongOutcome.LEFT_AS_IT_WAS))
        assertEquals(R.string.tags_outcome_could_not, outcomeWords(SongOutcome.COULD_NOT_CHANGE))
        assertEquals(R.string.tags_outcome_changed_since, outcomeWords(SongOutcome.CHANGED_SINCE))
        assertEquals(SongOutcome.entries.size, SongOutcome.entries.map(::outcomeWords).toSet().size)
    }

    @Test
    fun `what the toast says after an apply`() {
        assertEquals(Words(R.plurals.tags_applied, 2, listOf(2)), appliedWords(Applied.Done(albums = 2, songs = 10, problems = emptyList())))
        assertEquals(Words(R.plurals.tags_songs_left, 1, listOf(1)), appliedWords(Applied.Done(1, 2, listOf(SongProblem("/m/a.mp3", SongOutcome.BUSY)))))
        assertEquals(Words(R.plurals.tags_not_applied, 1, listOf(1)), appliedWords(Applied.Done(1, 2, emptyList(), failed = 1)))
        assertEquals(Words(R.string.tags_nothing_to_change), appliedWords(Applied.Done(0, 0, emptyList())))
        assertEquals(Words(R.string.tags_need_access), appliedWords(Applied.NoAccess))
        assertEquals(Words(R.string.tags_no_space), appliedWords(Applied.NotEnoughSpace))
        assertEquals(Words(R.string.tags_busy), appliedWords(Applied.Busy))
        assertEquals(Words(R.string.tags_failed), appliedWords(Applied.Failed))
        assertEquals(Words(R.string.tags_gone), appliedWords(Applied.Gone))
        assertEquals(Words(R.string.tags_in_progress), appliedWords(Applied.InProgress))
        assertEquals(Words(R.string.tags_changed_since), appliedWords(Applied.ChangedSince))
        // Apply all stopped after changing some: both are said.
        assertEquals(Words(R.plurals.tags_applied_then_busy, 2, listOf(2)), appliedWords(Applied.Done(2, 9, emptyList(), stopped = Stopped.BUSY)))
        assertEquals(Words(R.plurals.tags_applied_then_space, 1, listOf(1)), appliedWords(Applied.Done(1, 3, emptyList(), stopped = Stopped.NOT_ENOUGH_SPACE)))
        assertEquals(Words(R.plurals.tags_applied_then_access, 1, listOf(1)), appliedWords(Applied.Done(1, 3, emptyList(), stopped = Stopped.NO_ACCESS)))
    }

    @Test
    fun `what the toast says after an undo, fields changed since included`() {
        assertEquals(Words(R.string.tags_undone), undoneWords(Undone.Done(2, 0, 0, emptyList())))
        assertEquals(Words(R.plurals.tags_undone_kept, 1, listOf(1)), undoneWords(Undone.Done(2, 1, 0, emptyList())))
        assertEquals(Words(R.plurals.tags_undone_skipped, 2, listOf(2)), undoneWords(Undone.Done(1, 0, 2, emptyList())))
        assertEquals(Words(R.plurals.tags_songs_left, 1, listOf(1)), undoneWords(Undone.Done(1, 0, 0, listOf(SongProblem("/m/a.mp3", SongOutcome.BUSY)))))
        assertEquals(Words(R.string.tags_need_access), undoneWords(Undone.NoAccess))
        assertEquals(Words(R.string.tags_no_space), undoneWords(Undone.NotEnoughSpace))
    }

    @Test
    fun `a card says how many changes, or cover only`() {
        val renamed = changes(listOf("Quiet Ember", "Salt Road"))
        assertEquals(Words(R.plurals.tags_changes, renamed.count, listOf(renamed.count)), summaryWords(renamed))
        assertTrue(renamed.count > 0)
        assertEquals(Words(R.string.tags_cover_only), summaryWords(AlbumChanges(emptyList(), emptyList(), emptyList())))
        assertEquals(Words(R.plurals.tags_fit, 12, listOf(11, 12)), fitWords(Fit(11, 12)))
    }

    @Test
    fun `a candidate's line has its format, year and country`() {
        assertEquals("CD, 2009 · XE", releaseLine(release(listOf("Quiet Ember"))))
        assertEquals("CD, 2009", releaseLine(release(listOf("Quiet Ember"), country = null)))
        assertEquals("2009", releaseLine(release(listOf("Quiet Ember"), country = null, format = null)))
        assertEquals("", releaseLine(release(listOf("Quiet Ember"), country = null, date = null, format = null)))
    }

    @Test
    fun `a Done row says what changed and what could not, and when Undo has expired`() {
        assertEquals(listOf(Words(R.plurals.tags_songs_changed, 11, listOf(11))), doneLine(record(Settled.DONE, changed = 11), fullCopy = true))
        assertEquals(
            listOf(Words(R.plurals.tags_songs_changed, 11, listOf(11)), Words(R.plurals.tags_songs_left, 1, listOf(1)), Words(R.string.tags_undo_expired)),
            doneLine(record(Settled.DONE, changed = 11, problems = 1), fullCopy = false),
        )
        assertEquals(listOf(Words(R.string.tags_nothing_to_change)), doneLine(record(Settled.DONE, entryId = null), fullCopy = false))
    }

    // Covers in KJB

    @Test
    fun `the cover key changes with the song's version`() {
        val path = "/m/Lanterns/Quiet Ember.mp3"
        assertEquals(path, coverKey(SongArt(path, 41)))
        assertNotEquals(coverKey(SongArt(path, 41)), coverKey(SongArt(path, 41, version = 1)))
        assertNotEquals(coverKey(SongArt(path, 41, version = 1)), coverKey(SongArt(path, 41, version = 2)))
        assertEquals(coverKey(SongArt(path, 41, version = 2)), coverKey(SongArt(path, null, version = 2)))
    }
}
