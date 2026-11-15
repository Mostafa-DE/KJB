package app.lyrify.ui

import app.lyrify.R
import app.lyrify.dupes.Confidence
import app.lyrify.dupes.DuplicateGroup
import app.lyrify.dupes.ScanResults
import app.lyrify.models.ModelState
import app.lyrify.models.NpuChip
import app.lyrify.models.NpuChips
import app.lyrify.overnight.AiProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tools: each row's one-line status and badge for every state it can be in. */
class ToolsRowsTest {
    private val chip = NpuChips.ALL.first()

    private fun ai(chip: NpuChip? = this.chip, newEnough: Boolean = true, models: ModelState? = ModelState.Ready(TOTAL), enabled: Boolean = true, progress: AiProgress? = null) =
        AiState(chip, newEnough, models, enabled, progress, hold = null, pluggedIn = true, unrestricted = true, stopping = false)

    private fun rows(
        checks: Int = 0,
        dupes: DupesView = DupesView.FirstScan,
        dupesBadge: Int = 0,
        tones: Int = 0,
        coversTags: EntryRow? = null,
        ai: AiState = ai(enabled = false),
        aiWaiting: Int = 0,
    ): Map<Tool, ToolRow> = toolRows(checks, dupes, dupesBadge, tones, coversTags, ai, aiWaiting).associateBy { it.tool }

    @Test
    fun `the rows come in the prototype's order, Settings last`() {
        assertEquals(listOf(Tool.CHECK, Tool.DUPLICATES, Tool.TONES, Tool.COVERS_TAGS, Tool.DOWNLOADS, Tool.AI, Tool.SETTINGS), toolRows(0, DupesView.Loading, 0, 0, null, ai(), 0).map { it.tool })
    }

    @Test
    fun `Check these counts the songs to check, with a badge, or says all checked`() {
        assertEquals(ToolRow(Tool.CHECK, ToolLine.Count(R.plurals.tools_check, 3), badge = 3), rows(checks = 3).getValue(Tool.CHECK))
        assertEquals(ToolRow(Tool.CHECK, ToolLine.Text(R.string.tools_check_none)), rows(checks = 0).getValue(Tool.CHECK))
    }

    @Test
    fun `Duplicates says how its scan stands, with the groups not opened yet as its badge`() {
        assertNull(rows(dupes = DupesView.Loading).getValue(Tool.DUPLICATES).line)
        assertEquals(ToolLine.Text(R.string.tools_dupes_not_scanned), rows(dupes = DupesView.FirstScan).getValue(Tool.DUPLICATES).line)
        assertEquals(ToolLine.Text(R.string.tools_dupes_failed), rows(dupes = DupesView.Failed).getValue(Tool.DUPLICATES).line)
        assertEquals(ToolLine.Text(R.string.tools_dupes_scanning), rows(dupes = DupesView.Scanning(10, 400)).getValue(Tool.DUPLICATES).line)
        val none = DupesView.Results(ScanResults(scannedAt = 0, scanned = 400, groups = emptyList()), trashed = emptyList(), failed = false)
        assertEquals(ToolRow(Tool.DUPLICATES, ToolLine.Text(R.string.tools_dupes_none)), rows(dupes = none).getValue(Tool.DUPLICATES))
        val twelve = DupesView.Results(ScanResults(scannedAt = 0, scanned = 400, groups = List(12) { DuplicateGroup(Confidence.IDENTICAL, emptyList()) }), trashed = emptyList(), failed = false)
        assertEquals(ToolRow(Tool.DUPLICATES, ToolLine.Count(R.plurals.dupes_groups, 12), badge = 4), rows(dupes = twelve, dupesBadge = 4).getValue(Tool.DUPLICATES))
    }

    @Test
    fun `Tones counts the tones made`() {
        assertEquals(ToolRow(Tool.TONES, ToolLine.Count(R.plurals.tools_tones, 2)), rows(tones = 2).getValue(Tool.TONES))
        assertEquals(ToolRow(Tool.TONES, ToolLine.Text(R.string.tools_tones_none)), rows(tones = 0).getValue(Tool.TONES))
    }

    @Test
    fun `Covers and tags shows the line its milestone gives it, and the albums waiting as its badge`() {
        assertNull(rows().getValue(Tool.COVERS_TAGS).line)
        assertEquals(
            ToolRow(Tool.COVERS_TAGS, ToolLine.Text(R.string.tags_line_none)),
            rows(coversTags = EntryRow(ToolLine.Text(R.string.tags_line_none), badge = null)).getValue(Tool.COVERS_TAGS),
        )
        assertEquals(
            ToolRow(Tool.COVERS_TAGS, ToolLine.Count(R.plurals.tags_to_review, 3), badge = 3),
            rows(coversTags = EntryRow(ToolLine.Count(R.plurals.tags_to_review, 3), badge = 3)).getValue(Tool.COVERS_TAGS),
        )
    }

    @Test
    fun `AI lyrics says off, else what its tile says, and is not there on a phone the AI does not run on`() {
        // The AI add-on design (2026-11-13): on other phones Tools shows nothing about the AI, no dead button.
        assertFalse(Tool.AI in rows(ai = ai(chip = null)))
        assertFalse(Tool.AI in rows(ai = ai(newEnough = false)))
        assertEquals(ToolLine.Text(R.string.tools_ai_off), rows(ai = ai(enabled = false)).getValue(Tool.AI).line)
        assertEquals(
            ToolLine.Ai(TileValue.Fraction(12, 40), R.string.ai_tile_tonight),
            rows(ai = ai(progress = AiProgress(12, 40, null)), aiWaiting = 28).getValue(Tool.AI).line,
        )
        assertEquals(ToolLine.Ai(TileValue.Text(R.string.ai_tile_get), R.string.ai_tile_download), rows(ai = ai(models = ModelState.Missing(0, TOTAL))).getValue(Tool.AI).line)
        assertEquals(ToolLine.Text(R.string.ai_all_done), rows(ai = ai(), aiWaiting = 0).getValue(Tool.AI).line)
    }

    @Test
    fun `AI lyrics gets a dot when it needs the user, and no other row does`() {
        val failed = rows(ai = ai(models = ModelState.Failed(0, TOTAL)))
        assertTrue(failed.getValue(Tool.AI).dot)
        assertFalse(rows(ai = ai()).getValue(Tool.AI).dot)
        assertTrue(failed.values.filter { it.tool != Tool.AI }.none { it.dot })
    }

    @Test
    fun `Settings has no status`() {
        assertEquals(ToolRow(Tool.SETTINGS, null), rows().getValue(Tool.SETTINGS))
    }

    private companion object {
        const val TOTAL = 1_000_000_000L
    }
}
