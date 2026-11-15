package app.lyrify.addon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** KJB's side of an add-on's battery exemption: when it is offered, what Not now keeps, and the hint after a pause. */
class AddonExemptionTest {
    private val ai = KjbAddons.AI
    private val downloads = KjbAddons.DOWNLOADS

    @Test
    fun `the offer shows only while the add-on is not exempt, on every phone`() {
        val choices = ExemptionChoices()

        assertTrue(choices.offers(ai, exempt = false))
        assertFalse(choices.offers(ai, exempt = true))
        assertTrue(choices.offers(downloads, exempt = false))
    }

    @Test
    fun `Not now is kept, for that add-on only, and survives KJB starting again`() {
        var kept = ExemptionChoices()
        val store = ExemptionStore(read = { kept }, write = { kept = it })

        store.update { it.turnedDown(ai) }

        assertFalse(store.choices.value.offers(ai, exempt = false))
        assertTrue(store.choices.value.offers(downloads, exempt = false))
        val again = ExemptionStore(read = { kept }, write = { kept = it })
        assertFalse("remembered after a restart", again.choices.value.offers(ai, exempt = false))
    }

    @Test
    fun `the hint shows only once the phone paused the add-on, until it is exempt or the hint is put away`() {
        val fresh = ExemptionChoices()
        assertFalse("never before a pause", fresh.hints(ai, exempt = false))

        val paused = fresh.pausedOnce(ai)
        assertTrue(paused.hints(ai, exempt = false))
        assertFalse(paused.hints(downloads, exempt = false))
        assertFalse("exempt now: nothing to say", paused.hints(ai, exempt = true))
        // Turned down at setup: the hint after a pause is still said once.
        assertTrue(paused.turnedDown(ai).hints(ai, exempt = false))

        val seen = paused.hintSeen(ai)
        assertFalse(seen.hints(ai, exempt = false))
        assertTrue("the next pause says it again", seen.pausedOnce(ai).hints(ai, exempt = false))
    }

    @Test
    fun `each add-on asks for itself, through its own activity`() {
        assertEquals("app.lyrify.ai.AiExemptionActivity", ai.exemption)
        assertEquals("app.lyrify.dl.DlExemptionActivity", downloads.exemption)
    }
}
