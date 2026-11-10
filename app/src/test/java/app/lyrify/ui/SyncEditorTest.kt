package app.lyrify.ui

import app.lyrify.lyrics.LrcFormat
import app.lyrify.lyrics.LrcLine
import app.lyrify.lyrics.LrcProblem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The synced lyrics editor's model: where it starts, Stamp, Undo, the nudge, Fix text, Preview and what Save writes. Made-up lines only. */
class SyncEditorTest {
    private val plain = listOf("Paper lanterns rise", "Over the harbour glass", "And the ferry hums", "Low tide, low tide")

    private fun making(): SyncEdit = SyncEdit(SyncMode.MAKE, plain.map { EditLine(it) })

    private fun stampedAll(vararg times: Long): SyncEdit = times.fold(making()) { edit, time -> edit.stamp(time) }

    // Where it starts

    @Test
    fun `synced lyrics open for Edit timing with their times, plain lyrics for Make synced`() {
        val synced = "[ar:Tin Roof Choir]\n[00:01.00]Paper lanterns rise\n[00:04.50]Over the harbour glass\n[00:09.25]\n[00:10.00]And the ferry hums\n"
        val edit = SyncEdit.from(synced, plain = null)!!
        assertEquals(SyncMode.EDIT, edit.mode)
        // A break without words stays, so Edit timing changes only the times.
        assertEquals(
            listOf(EditLine("Paper lanterns rise", 1_000), EditLine("Over the harbour glass", 4_500), EditLine("", 9_250), EditLine("And the ferry hums", 10_000)),
            edit.lines,
        )
        assertEquals(0, edit.cursor)
        assertFalse(edit.changed)

        val fromFile = SyncEdit.from("[ti:Harbour Lights]\nPaper lanterns rise\n\n  Over the harbour glass  \n", plain = null)!!
        assertEquals(SyncMode.MAKE, fromFile.mode)
        assertEquals(listOf(EditLine("Paper lanterns rise"), EditLine("Over the harbour glass")), fromFile.lines)

        // Without a file: the lookup's plain lyrics, kept in memory only.
        val fromLookup = SyncEdit.from(null, plain = plain.joinToString("\n\n"))!!
        assertEquals(SyncMode.MAKE, fromLookup.mode)
        assertEquals(plain.map { EditLine(it) }, fromLookup.lines)

        assertNull(SyncEdit.from(null, plain = null))
        assertNull(SyncEdit.from("  \n", plain = " \n"))
    }

    // Stamp and Undo

    @Test
    fun `Stamp sets the line's time where the song is and moves to the next line`() {
        val once = making().stamp(1_234)
        assertEquals(EditLine("Paper lanterns rise", 1_234), once.lines[0])
        assertEquals(1, once.cursor)
        assertEquals(1, once.stamped)
        assertTrue(once.changed)

        val all = once.stamp(4_000).stamp(8_000).stamp(12_000)
        assertTrue(all.complete)
        assertTrue(all.atEnd)
        assertEquals(4, all.stamped)
        // Past the last line there is nothing left to stamp.
        assertSame(all, all.stamp(15_000))
    }

    @Test
    fun `Undo takes back the last stamp, puts the old time back and goes back to that line`() {
        val two = making().stamp(1_000).stamp(4_000)
        val back = two.undo()
        assertEquals(EditLine("Over the harbour glass"), back.lines[1])
        assertEquals(1, back.cursor)
        assertEquals(1, back.stamped)
        assertEquals(0, back.undo().cursor)
        assertEquals(0, back.undo().stamped)
        // Nothing to take back.
        assertEquals(making(), making().undo())

        // Edit timing: the time before the stamp comes back.
        val edit = SyncEdit(SyncMode.EDIT, listOf(EditLine("Paper lanterns rise", 1_000), EditLine("Over the harbour glass", 4_500)))
        assertEquals(edit.lines, edit.stamp(1_400).undo().lines)
    }

    @Test
    fun `picking a line stamps from there, and the song goes to just before its time`() {
        val edit = stampedAll(1_000, 4_000, 8_000, 12_000).select(1)
        assertEquals(1, edit.cursor)
        assertEquals(2_500L, edit.seekFor(1))
        assertEquals(0L, edit.seekFor(0))
        assertNull(making().seekFor(2))
        val restamped = edit.stamp(4_400)
        assertEquals(4_400L, restamped.lines[1].timeMs)
        assertEquals(2, restamped.cursor)
    }

    // The nudge and Fix text

    @Test
    fun `a nudge moves a stamped line by a tenth of a second, never before the start`() {
        val edit = stampedAll(50, 4_000, 8_000, 12_000)
        assertEquals(4_100L, edit.nudge(1, SyncEdit.NUDGE_MS).lines[1].timeMs)
        assertEquals(3_900L, edit.nudge(1, -SyncEdit.NUDGE_MS).lines[1].timeMs)
        assertEquals(0L, edit.nudge(0, -SyncEdit.NUDGE_MS).lines[0].timeMs)
        // A line without a time has nothing to move.
        assertEquals(making(), making().nudge(2, SyncEdit.NUDGE_MS))
        assertTrue(SyncEdit(SyncMode.EDIT, listOf(EditLine("Low tide", 1_000))).nudge(0, SyncEdit.NUDGE_MS).changed)
    }

    @Test
    fun `Fix text changes a line's words and keeps its time`() {
        val edit = stampedAll(1_000).fix(0, "  Paper lanterns\u0007 rising \n")
        assertEquals(EditLine("Paper lanterns rising", 1_000), edit.lines[0])
        assertTrue(SyncEdit(SyncMode.EDIT, listOf(EditLine("Low tide", 1_000))).fix(0, "Low tides").changed)
    }

    // Preview

    @Test
    fun `Preview starts just before the first stamp and lights the line the stamps say is playing`() {
        val edit = making().stamp(3_000).stamp(6_000)
        assertEquals(1_000L, edit.previewFrom())
        assertEquals(0L, making().stamp(500).previewFrom())
        assertNull(making().previewFrom())
        assertEquals(-1, edit.lineAt(2_999))
        assertEquals(0, edit.lineAt(3_000))
        assertEquals(1, edit.lineAt(60_000))
    }

    // Save

    @Test
    fun `Save waits until every line has a time, in order`() {
        assertEquals(SaveCheck.Unstamped(4), making().check(200_000))
        assertEquals(SaveCheck.Unstamped(1), stampedAll(1_000, 4_000, 8_000).check(200_000))
        assertEquals(SaveCheck.OutOfOrder(2), stampedAll(1_000, 8_000, 4_000, 12_000).check(200_000))
    }

    @Test
    fun `what Save writes is an lrc your phone's music app reads back line for line`() {
        val check = stampedAll(1_000, 4_520, 8_000, 61_005).fix(3, "Low tide").check(200_000)
        val lrc = (check as SaveCheck.Ready).lrc
        assertEquals("[00:01.000]Paper lanterns rise\n[00:04.520]Over the harbour glass\n[00:08.000]And the ferry hums\n[01:01.005]Low tide\n", lrc)
        assertNull(LrcFormat.validate(lrc, 200_000))
        assertEquals(
            listOf(LrcLine(1_000, "Paper lanterns rise"), LrcLine(4_520, "Over the harbour glass"), LrcLine(8_000, "And the ferry hums"), LrcLine(61_005, "Low tide")),
            LrcFormat.parseLikeSamsung(lrc),
        )
        // Same times are allowed: your phone shows the lines together.
        assertTrue(stampedAll(1_000, 4_000, 4_000, 12_000).check(200_000) is SaveCheck.Ready)
    }

    @Test
    fun `lyrics your phone would not show are not saved`() {
        val two = SyncEdit(SyncMode.MAKE, listOf(EditLine("Paper lanterns rise"), EditLine("Over the harbour glass"))).stamp(1_000).stamp(2_000)
        assertEquals(SaveCheck.Rejected(LrcProblem.TOO_FEW_LINES), two.check(200_000))
        assertEquals(SaveCheck.Rejected(LrcProblem.PAST_DURATION), stampedAll(1_000, 4_000, 8_000, 120_000).check(60_000))
        // A song whose length is not known is not judged by it.
        assertTrue(stampedAll(1_000, 4_000, 8_000, 120_000).check(0) is SaveCheck.Ready)
    }
}
