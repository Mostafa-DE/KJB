package app.lyrify.tags

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A change to a song's tags, applied to what the file holds: only the fields asked for change, every other field and
 * picture stays as it was, and the front cover is replaced alone. Made-up names only.
 */
class TagEditTest {
    private val back = TagPicture(byteArrayOf(1, 2, 3), "", "Back Cover", "image/jpeg")
    private val front = TagPicture(byteArrayOf(4, 5, 6), "", FRONT_COVER, "image/jpeg")
    private val newFront = TagPicture(byteArrayOf(7, 8, 9), "", FRONT_COVER, "image/jpeg")

    private val tags = SongTags(
        fields = mapOf(
            "TITLE" to listOf("paper lantern"),
            "ARTIST" to listOf("Kit Calloway"),
            "DATE" to listOf("2009"),
            "LYRICS" to listOf("la la la"),
            "REPLAYGAIN_TRACK_GAIN" to listOf("-6.20 dB"),
            "COMMENT" to listOf("made up"),
        ),
        pictures = listOf(back, front),
        durationMs = 201_000,
    )

    @Test
    fun `only the fields asked for change, and every other field stays`() {
        val applied = TagEdit(
            fields = mapOf("title" to listOf("Paper Lantern"), "ALBUM" to listOf("Harbour Glass"), "ARTIST" to listOf("Kit Calloway")),
        ).applyTo(tags)

        assertEquals(tags.fields + mapOf("TITLE" to listOf("Paper Lantern"), "ALBUM" to listOf("Harbour Glass")), applied.fields)
        assertEquals(
            listOf(FieldChange("ALBUM", emptyList(), listOf("Harbour Glass")), FieldChange("TITLE", listOf("paper lantern"), listOf("Paper Lantern"))),
            applied.changes.sortedBy { it.key },
        )
        assertEquals(tags.pictures, applied.pictures)
        assertFalse(applied.coverChanged)
        assertTrue(applied.changesSomething)
    }

    @Test
    fun `an empty list removes a field, and an edit that changes nothing says so`() {
        val removed = TagEdit(fields = mapOf("COMMENT" to emptyList())).applyTo(tags)
        assertFalse("COMMENT" in removed.fields)
        assertEquals(listOf(FieldChange("COMMENT", listOf("made up"), emptyList())), removed.changes)

        val same = TagEdit(fields = mapOf("TITLE" to listOf("paper lantern"), "ALBUM" to emptyList())).applyTo(tags)
        assertFalse(same.changesSomething)
        assertEquals(tags.fields, same.fields)
    }

    @Test
    fun `a field written only where empty keeps the song's own value, and fills it when there is none`() {
        val keep = TagEdit(fields = mapOf("DATE" to listOf("2004")), onlyWhereEmpty = setOf("DATE")).applyTo(tags)
        assertEquals(listOf("2009"), keep.fields["DATE"])
        assertFalse(keep.changesSomething)

        val fill = TagEdit(fields = mapOf("DATE" to listOf("2004")), onlyWhereEmpty = setOf("date")).applyTo(tags.copy(fields = tags.fields - "DATE"))
        assertEquals(listOf("2004"), fill.fields["DATE"])

        // "Keep my year" off: written over the song's year.
        assertEquals(listOf("2004"), TagEdit(fields = mapOf("DATE" to listOf("2004"))).applyTo(tags).fields["DATE"])
    }

    @Test
    fun `a new front cover replaces only the front cover, in its place, and keeps the back cover`() {
        val applied = TagEdit(cover = CoverEdit.Replace(newFront)).applyTo(tags)
        assertEquals(listOf(back, newFront), applied.pictures)
        assertTrue(applied.coverChanged)
        assertEquals(front, applied.frontBefore)
        assertEquals(newFront, applied.frontAfter)

        // A song with no front cover gets one first.
        val added = TagEdit(cover = CoverEdit.Replace(newFront)).applyTo(tags.copy(pictures = listOf(back)))
        assertEquals(listOf(newFront, back), added.pictures)
        assertNull(added.frontBefore)

        // The same cover again changes nothing.
        assertFalse(TagEdit(cover = CoverEdit.Replace(front.copy())).applyTo(tags).changesSomething)
    }

    @Test
    fun `in an M4A, whose covers have no type, the first picture is the front cover`() {
        val first = TagPicture(byteArrayOf(1), "", "", "image/jpeg")
        val second = TagPicture(byteArrayOf(2), "", "", "image/png")
        val applied = TagEdit(cover = CoverEdit.Replace(newFront)).applyTo(tags.copy(pictures = listOf(first, second)))
        assertEquals(listOf(newFront, second), applied.pictures)
        assertEquals(first, applied.frontBefore)
    }

    @Test
    fun `removing the cover removes the front cover only`() {
        val applied = TagEdit(cover = CoverEdit.Remove).applyTo(tags)
        assertEquals(listOf(back), applied.pictures)
        assertEquals(front, applied.frontBefore)
        assertNull(applied.frontAfter)
        assertFalse(TagEdit(cover = CoverEdit.Remove).applyTo(tags.copy(pictures = listOf(back))).changesSomething)
    }

    @Test
    fun `pictures are equal by their bytes and fields`() {
        assertEquals(front, TagPicture(byteArrayOf(4, 5, 6), "", FRONT_COVER, "image/jpeg"))
        assertEquals(front.hashCode(), TagPicture(byteArrayOf(4, 5, 6), "", FRONT_COVER, "image/jpeg").hashCode())
        assertFalse(front == newFront)
        assertEquals(64, front.sha256.length)
    }
}
