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
import app.lyrify.tags.SongOutcome
import app.lyrify.tags.SongProblem
import app.lyrify.tags.TagAlbum
import app.lyrify.tags.Undone
import app.lyrify.tags.Waiting
import app.lyrify.tags.lookup.AlbumChanges
import app.lyrify.tags.lookup.AlbumMatch
import app.lyrify.tags.lookup.Credit
import app.lyrify.tags.lookup.Doubt
import app.lyrify.tags.lookup.Field
import app.lyrify.tags.lookup.FieldChange
import app.lyrify.tags.lookup.MbMedium
import app.lyrify.tags.lookup.MbRelease
import app.lyrify.tags.lookup.MbReleaseGroup
import app.lyrify.tags.lookup.MbTrack
import app.lyrify.tags.lookup.Part
import app.lyrify.tags.lookup.PhoneAlbum
import app.lyrify.tags.lookup.PhoneSong
import app.lyrify.tags.lookup.Proposal
import app.lyrify.tags.lookup.TagChanges
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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

    // The row on Tools

    @Test
    fun `the row reads where names and covers come from before the first check, then counts what waits`() {
        assertEquals(EntryRow(ToolLine.Text(R.string.tags_line_start), null), coversTagsEntry(CoversTagsState(loaded = true)))
        assertEquals(ToolLine.Text(R.string.tags_line_checking), coversTagsEntry(CoversTagsState(checking = Checking(3, 84))).line)

        val checked = CoversTagsState(loaded = true, memory = CoversTagsMemory(checkedOnce = true))
        // Only what has a suggestion is to review: Harbour and Kelp have none.
        val waiting = coversTagsEntry(checked.copy(waiting = listOf(waiting("Lanterns", true), waiting("Harbour", false), waiting("Kelp", false))))
        assertEquals(EntryRow(ToolLine.Count(R.plurals.tags_to_review, 1), 1), waiting)

        val done = checked.copy(memory = checked.memory.copy(albums = listOf(record(Settled.DONE, 2), record(Settled.DONE, 3).copy(id = "b"))))
        assertEquals(EntryRow(ToolLine.Count(R.plurals.tags_albums_changed, 2), null), coversTagsEntry(done))
        assertEquals(EntryRow(ToolLine.Text(R.string.tags_line_none), null), coversTagsEntry(checked))
    }

    // The page's rows: each needs a key of its own, or the list crashes

    @Test
    fun `two rows of one id still get keys of their own, and the first keeps its key when the second comes`() {
        assertEquals(listOf("done a", "done b", "done a#2"), rowKeys("done", listOf("a", "b", "a")))
        assertEquals(rowKeys("done", listOf("a")).first(), rowKeys("done", listOf("a", "a")).first())
    }

    // Confirming

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
        assertEquals(Words(R.plurals.tags_songs_left, 1, listOf(1)), appliedWords(Applied.Done(1, 2, listOf(SongProblem("/m/a.mp3", SongOutcome.BUSY)))))
        assertEquals(Words(R.string.tags_nothing_to_change), appliedWords(Applied.Done(0, 0, emptyList())))
        assertEquals(Words(R.string.tags_need_access), appliedWords(Applied.NoAccess))
        assertEquals(Words(R.string.tags_no_space), appliedWords(Applied.NotEnoughSpace))
        assertEquals(Words(R.string.tags_cover_failed), appliedWords(Applied.CoverFailed))
        assertEquals(Words(R.string.tags_gone), appliedWords(Applied.Gone))
        assertEquals(Words(R.string.tags_in_progress), appliedWords(Applied.InProgress))
        assertEquals(Words(R.string.tags_changed_since), appliedWords(Applied.ChangedSince))
    }

    @Test
    fun `what the toast says after an undo, fields changed since included`() {
        assertEquals(Words(R.plurals.tags_undone_kept, 1, listOf(1)), undoneWords(Undone.Done(2, 1, 0, emptyList())))
        assertEquals(Words(R.plurals.tags_undone_skipped, 2, listOf(2)), undoneWords(Undone.Done(1, 0, 2, emptyList())))
        assertEquals(Words(R.plurals.tags_songs_left, 1, listOf(1)), undoneWords(Undone.Done(1, 0, 0, listOf(SongProblem("/m/a.mp3", SongOutcome.BUSY)))))
        assertEquals(Words(R.string.tags_need_access), undoneWords(Undone.NoAccess))
        assertEquals(Words(R.string.tags_no_space), undoneWords(Undone.NotEnoughSpace))
    }

    @Test
    fun `a candidate's line has its format, year and country`() {
        assertEquals("CD, 2009 · XE", releaseLine(release(listOf("Quiet Ember"))))
        assertEquals("CD, 2009", releaseLine(release(listOf("Quiet Ember"), country = null)))
        assertEquals("2009", releaseLine(release(listOf("Quiet Ember"), country = null, format = null)))
        assertEquals("", releaseLine(release(listOf("Quiet Ember"), country = null, date = null, format = null)))
    }

    @Test
    fun `a Done row says how many songs it changed, what could not, and when Undo has expired`() {
        assertEquals(listOf(Words(R.plurals.tags_songs, 11, listOf(11))), doneLine(record(Settled.DONE, changed = 11), fullCopy = true))
        assertEquals(
            listOf(Words(R.plurals.tags_songs, 11, listOf(11)), Words(R.plurals.tags_songs_left, 1, listOf(1)), Words(R.string.tags_undo_expired)),
            doneLine(record(Settled.DONE, changed = 11, problems = 1), fullCopy = false),
        )
        assertEquals(listOf(Words(R.string.tags_nothing_to_change)), doneLine(record(Settled.DONE, entryId = null), fullCopy = false))
    }

    /** A restore brings a Done row without its Undo, which stayed on the other phone: it says what the apply did there. */
    @Test
    fun `a restored Done row says the songs it changed and that it was restored, never Nothing to change or Undo`() {
        val restored = { changed: Int, problems: Int -> record(Settled.DONE, changed = changed, problems = problems, entryId = null).copy(restored = true) }
        assertEquals(listOf(Words(R.plurals.tags_changed_songs, 3, listOf(3)), Words(R.string.tags_restored_row)), doneLine(restored(3, 0), fullCopy = false))
        assertEquals(
            listOf(Words(R.plurals.tags_changed_songs, 3, listOf(3)), Words(R.plurals.tags_songs_left, 1, listOf(1)), Words(R.string.tags_restored_row)),
            doneLine(restored(3, 1), fullCopy = false, blocked = true),
        )
        // An apply that had nothing to change on the other phone still says so.
        assertEquals(listOf(Words(R.string.tags_nothing_to_change), Words(R.string.tags_restored_row)), doneLine(restored(0, 0), fullCopy = false))
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

    // The redesign (user feedback 2026-11-11)

    @Test
    fun `the page's top tabs are To review, Done and Skipped, always, with their counts`() {
        val state = CoversTagsState(
            // Two of the three have no suggestion: they are not to review.
            waiting = listOf(waiting("Kelp", false), waiting("Lanterns", true), waiting("Harbour", false)),
            memory = CoversTagsMemory(albums = listOf(record(Settled.SKIPPED), record(Settled.DONE).copy(id = "d1"), record(Settled.DONE).copy(id = "d2", entryId = 9))),
        )

        assertEquals(listOf(ReviewTab.TO_REVIEW to 1, ReviewTab.DONE to 2, ReviewTab.SKIPPED to 1), reviewTabs(state))
        assertEquals(listOf(ReviewTab.TO_REVIEW to 0, ReviewTab.DONE to 0, ReviewTab.SKIPPED to 0), reviewTabs(CoversTagsState()))
    }

    @Test
    fun `the tabs are the swiping pager Library and Favourites use, each tab its own list, and the plain row is gone`() {
        // Unit tests run in the module's folder.
        val page = File("src/main/java/app/lyrify/ui/CoversTagsScreen.kt").readText()
        assertTrue("PagerTabs(" in page && "TabPages(" in page && "CollapsingTabs(" in page)
        assertFalse("LibraryTabsRow(" in page)
        assertFalse("fun LibraryTabsRow" in File("src/main/java/app/lyrify/ui/TabPager.kt").readText())
    }

    @Test
    fun `Apply names the count, and is off while no song or no field is ticked`() {
        assertEquals(Words(R.plurals.tags_change_songs, 1, listOf(1)), applyWords(1))
        assertEquals(Words(R.plurals.tags_change_songs, 9, listOf(9)), applyWords(9))
        val one = setOf("/m/a.mp3")
        assertTrue(canApply(one, setOf(Part.COVER), writable = true))
        assertFalse(canApply(emptySet(), setOf(Part.COVER), writable = true))
        assertFalse(canApply(one, emptySet(), writable = true))
        assertFalse(canApply(one, setOf(Part.COVER), writable = false))
    }

    @Test
    fun `more than one song asks once before writing, and so does the first apply ever, for its sync note`() {
        assertFalse(asksFirst(songs = 1, noted = true))
        assertTrue(asksFirst(songs = 1, noted = false))
        assertTrue(asksFirst(songs = 2, noted = true))
    }

    @Test
    fun `a card's chip says sure match, or how many choices`() {
        assertEquals(Words(R.string.tags_sure), choiceWords(waiting("Lanterns", true).proposal))
        val match = AlbumMatch.match(album(listOf("Quiet Ember")), release(listOf("Quiet Ember")))
        assertEquals(Words(R.plurals.tags_choices, 3, listOf(3)), choiceWords(Proposal.PickOne(List(3) { match }, setOf(Doubt.GAP))))
    }

    @Test
    fun `progress reads getting the cover, then the song being changed of all of them`() {
        assertEquals(Words(R.string.tags_getting_cover), progressWords(Applying(0, 9, cover = true)))
        assertEquals(Words(R.plurals.tags_changing, 9, listOf(1, 9)), progressWords(Applying(0, 9)))
        assertEquals(Words(R.plurals.tags_changing, 9, listOf(3, 9)), progressWords(Applying(2, 9)))
        assertEquals(Words(R.plurals.tags_changing, 9, listOf(9, 9)), progressWords(Applying(9, 9)))
    }

    @Test
    fun `after an apply the toast counts the songs changed, after an Undo the songs restored`() {
        assertEquals(Words(R.plurals.tags_changed_songs, 9, listOf(9)), appliedWords(Applied.Done(albums = 1, songs = 9, problems = emptyList())))
        assertEquals(Words(R.plurals.tags_changed_songs, 1, listOf(1)), appliedWords(Applied.Done(albums = 1, songs = 1, problems = emptyList(), entryId = 4)))
        assertEquals(Words(R.plurals.tags_restored, 1, listOf(1)), undoneWords(Undone.Done(1, 0, 0, emptyList())))
        assertEquals(Words(R.plurals.tags_restored, 9, listOf(9)), undoneWords(Undone.Done(9, 0, 0, emptyList())))
    }

    @Test
    fun `the review's rows are only what changes, each with the old and new its songs share, and the user's own ticks win`() {
        val changes = changes(listOf("Quiet Ember", "Salt Road"))

        assertEquals(
            listOf(FieldRow(Part.ALBUM, FieldChange(Field.ALBUM, "Lanterns", "Lanterns of Ulso"), 2), FieldRow(Part.YEAR, FieldChange(Field.YEAR, null, "1998"), 2)),
            fieldRows(changes),
        )
        assertEquals(setOf(Part.ALBUM, Part.YEAR), pickedParts(changes, loaded = false, overrides = emptyMap()))
        assertEquals(setOf(Part.ALBUM, Part.YEAR, Part.COVER), pickedParts(changes, loaded = true, overrides = emptyMap()))
        assertEquals(setOf(Part.ALBUM, Part.COVER), pickedParts(changes, loaded = true, overrides = mapOf(Part.YEAR to false)))
        // A tick on a row not offered (no new title, no cover loaded) writes nothing.
        assertEquals(setOf(Part.ALBUM, Part.YEAR), pickedParts(changes, loaded = false, overrides = mapOf(Part.TITLE to true, Part.COVER to true)))
        assertEquals(listOf(FieldChange(Field.ALBUM, "Lanterns", "Lanterns of Ulso")), songLines(changes.songs.first(), setOf(Part.ALBUM, Part.TITLE)))
    }

    // Review findings, 2026-11-11 (Grok panel on v3/ct2-covers)

    @Test
    fun `a candidate that goes away is never swapped for another, and the user's own ticks stay through a new pick`() {
        val album = album(listOf("Quiet Ember", "Salt Road"))
        val a = AlbumMatch.match(album, release(listOf("Quiet Ember", "Salt Road")))
        val b = AlbumMatch.match(album, release(listOf("Quiet Ember", "Salt Road")).copy(id = "0b7c1f4e-5a2d-4c3b-9e8f-1a2b3c4d5e03"))
        var choice = ReviewChoice(picked = a.release.id, songs = setOf("/m/Lanterns/Quiet Ember.mp3")).tick(Part.ALBUM, false)
        assertEquals(a, choice.match(listOf(a, b)))

        // A check placed other candidates: the one picked is gone, and none is picked in its place.
        assertNull(choice.match(listOf(b)))
        choice = choice.pick(b.release.id)

        assertEquals(b, choice.match(listOf(b)))
        assertEquals(mapOf(Part.ALBUM to false), choice.overrides)
        assertFalse(Part.ALBUM in pickedParts(TagChanges.of(album, b), loaded = true, overrides = choice.overrides))
    }

    @Test
    fun `an album is to review only with a field to change or a cover confirmed, and leaves once its cover turns out missing`() {
        val album = album(listOf("Quiet Ember", "Salt Road"), "Lanterns of Ulso")
        val bare = release(listOf("Quiet Ember", "Salt Road")).copy(releaseGroup = MbReleaseGroup("6f1e2d3c-4b5a-4968-8776-5a4b3c2d1e02", null))
        fun waiting(front: Boolean?) = Waiting(TagAlbum(album, emptyMap()), Proposal.Ready(AlbumMatch.match(album, bare.copy(hasFront = front))))
        assertTrue(TagChanges.of(album, AlbumMatch.match(album, bare)).parts.isEmpty())

        assertEquals(emptyList<Waiting>(), CoversTagsState(waiting = listOf(waiting(null))).toReview)
        assertEquals(1, CoversTagsState(waiting = listOf(waiting(true))).toReview.size)
        assertEquals(emptyList<Waiting>(), CoversTagsState(waiting = listOf(waiting(true)), noCover = setOf(bare.id)).toReview)
        // A field to change keeps it listed, cover or not.
        val messy = album(listOf("quiet ember", "Salt Road"), "Lanterns of Ulso")
        val renamed = Waiting(TagAlbum(messy, emptyMap()), Proposal.Ready(AlbumMatch.match(messy, bare)))
        assertEquals(1, CoversTagsState(waiting = listOf(renamed), noCover = setOf(bare.id)).toReview.size)
    }

    @Test
    fun `the Year row adds the year to the songs with none and says how many keep their own`() {
        val dated = PhoneAlbum("Lanterns", "Mira Solen", album(listOf("Quiet Ember", "Salt Road", "Brennmoor")).songs.mapIndexed { i, song -> song.copy(year = if (i == 0) 2011 else null) })
        val changes = TagChanges.of(dated, AlbumMatch.match(dated, release(listOf("Quiet Ember", "Salt Road", "Brennmoor"))))

        assertEquals(FieldRow(Part.YEAR, FieldChange(Field.YEAR, null, "1998"), songs = 2, kept = 1), fieldRows(changes).single { it.part == Part.YEAR })
    }
}
