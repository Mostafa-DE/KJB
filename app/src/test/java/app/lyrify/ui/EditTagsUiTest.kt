package app.lyrify.ui

import androidx.compose.runtime.saveable.SaverScope
import app.lyrify.tags.CoverEdit
import app.lyrify.tags.FRONT_COVER
import app.lyrify.tags.TagPicture
import org.junit.Assert.assertEquals
import org.junit.Test

/** Edit tags' cover choice across a turn of the phone: only a word goes into the saved state, the picture stays in memory. */
class EditTagsUiTest {
    private val scope = SaverScope { true }

    private fun roundTrip(token: String, cover: CoverEdit): CoverEdit? {
        val saver = coverSaver(token)
        val saved = with(saver) { scope.save(cover) } ?: return null
        return coverSaver(token).restore(saved)
    }

    @Test
    fun `a chosen cover survives the editor being made again, as do Remove and Keep`() {
        val picture = TagPicture(ByteArray(300 * 1024) { 7 }, "", FRONT_COVER, "image/jpeg")

        assertEquals(CoverEdit.Replace(picture), roundTrip("editor-1", CoverEdit.Replace(picture)))
        assertEquals(CoverEdit.Remove, roundTrip("editor-1", CoverEdit.Remove))
        assertEquals(CoverEdit.Keep, roundTrip("editor-1", CoverEdit.Keep))
    }

    @Test
    fun `the saved state holds a word, never the picture, and a picture lost with the process restores as Keep`() {
        val picture = TagPicture(ByteArray(300 * 1024) { 7 }, "", FRONT_COVER, "image/jpeg")

        val saved = with(coverSaver("editor-2")) { scope.save(CoverEdit.Replace(picture)) }

        assertEquals(true, saved!!.length < 64)
        assertEquals(CoverEdit.Keep, coverSaver("editor-from-a-dead-process").restore(saved))
    }
}
