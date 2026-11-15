package app.lyrify.ui

import app.lyrify.addon.AddonState
import app.lyrify.addon.ExemptionChoices
import app.lyrify.addon.KjbAddons
import app.lyrify.models.NpuChips
import app.lyrify.overnight.AiHold
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * An add-on's battery exemption where it is set up (AD9): offered once on its page while it is ready and not exempt, Not
 * now kept, a row with Allowed or Not allowed, and a plain hint only after the phone paused it. The AI page and the
 * Downloads page alike, each for its own add-on; and the copy as it ships.
 */
class LockedPhoneTest {
    private val ai = KjbAddons.AI
    private val downloads = KjbAddons.DOWNLOADS
    private val none = ExemptionChoices()

    // The offer

    @Test
    fun `the offer shows only for a ready add-on that is not exempt`() {
        assertTrue(lockedPhone(ai, ready = true, exempt = false, none).offer)
        assertFalse(lockedPhone(ai, ready = true, exempt = true, none).offer)
        assertFalse(lockedPhone(ai, ready = false, exempt = false, none).offer)
        assertTrue(lockedPhone(downloads, ready = true, exempt = false, none).offer)
    }

    @Test
    fun `Not now is kept, and the offer does not come back, for that add-on only`() {
        val notNow = none.turnedDown(ai)

        assertFalse(lockedPhone(ai, ready = true, exempt = false, notNow).offer)
        assertTrue(lockedPhone(downloads, ready = true, exempt = false, notNow).offer)
        assertFalse(lockedPhone(downloads, ready = true, exempt = false, notNow.turnedDown(downloads)).offer)
    }

    // The hint

    @Test
    fun `the hint shows only after a pause`() {
        val turnedDown = none.turnedDown(ai)

        assertFalse(lockedPhone(ai, ready = true, exempt = false, turnedDown).hint)
        assertTrue(lockedPhone(ai, ready = true, exempt = false, turnedDown.pausedOnce(ai)).hint)
        assertFalse(lockedPhone(downloads, ready = true, exempt = false, turnedDown.turnedDown(downloads).pausedOnce(ai)).hint)
    }

    @Test
    fun `the hint goes after Allow or Not now, and once the add-on is exempt`() {
        val paused = none.turnedDown(downloads).pausedOnce(downloads)

        assertTrue(lockedPhone(downloads, ready = true, exempt = false, paused).hint)
        assertFalse(lockedPhone(downloads, ready = true, exempt = false, paused.hintSeen(downloads)).hint)
        assertFalse(lockedPhone(downloads, ready = true, exempt = true, paused).hint)
        // The next pause says it again.
        assertTrue(lockedPhone(downloads, ready = true, exempt = false, paused.hintSeen(downloads).pausedOnce(downloads)).hint)
    }

    @Test
    fun `the hint waits while the offer is still due, and nothing shows for an add-on that is not ready`() {
        // Paused before the offer was answered: the offer asks, the hint does not ask it twice.
        val paused = none.pausedOnce(ai)
        assertEquals(LockedPhone(offer = true, row = LockedRow.NOT_ALLOWED, hint = false), lockedPhone(ai, ready = true, exempt = false, paused))
        assertEquals(LockedPhone(offer = false, row = null, hint = false), lockedPhone(ai, ready = false, exempt = false, paused.turnedDown(ai)))
    }

    // The row

    @Test
    fun `the row says Allowed once exempt, else Not allowed, and shows while the add-on is ready`() {
        assertEquals(LockedRow.ALLOWED, lockedPhone(ai, ready = true, exempt = true, none).row)
        assertEquals(LockedRow.NOT_ALLOWED, lockedPhone(ai, ready = true, exempt = false, none).row)
        assertEquals(LockedRow.NOT_ALLOWED, lockedPhone(downloads, ready = true, exempt = false, none.turnedDown(downloads)).row)
        assertNull(lockedPhone(ai, ready = false, exempt = true, none).row)
    }

    // The AI page

    private fun aiState(addon: AddonState?, exempt: Boolean, choices: ExemptionChoices, hold: AiHold? = null) = AiState(
        chip = NpuChips.ALL.first(),
        newEnough = true,
        models = null,
        enabled = true,
        progress = null,
        hold = hold,
        pluggedIn = true,
        unrestricted = true,
        stopping = false,
        addon = addon,
        addonExempt = exempt,
        exemptions = choices,
    )

    @Test
    fun `the AI page reads the AI add-on's own exemption`() {
        assertEquals(LockedPhone(offer = true, row = LockedRow.NOT_ALLOWED, hint = false), aiState(AddonState.Ready, exempt = false, none).locked)
        assertEquals(LockedPhone(offer = false, row = LockedRow.ALLOWED, hint = false), aiState(AddonState.Ready, exempt = true, none.pausedOnce(ai)).locked)
        assertEquals(LockedPhone(offer = false, row = LockedRow.NOT_ALLOWED, hint = true), aiState(AddonState.Ready, exempt = false, none.turnedDown(ai).pausedOnce(ai)).locked)
        // Downloads' answers are not the AI's.
        assertTrue(aiState(AddonState.Ready, exempt = false, none.turnedDown(downloads)).locked.offer)
        for (addon in listOf(null, AddonState.Missing, AddonState.Unreachable, AddonState.Untrusted, AddonState.Outdated(1))) {
            assertEquals("$addon", LockedPhone(offer = false, row = null, hint = false), aiState(addon, exempt = false, none.pausedOnce(ai)).locked)
        }
    }

    @Test
    fun `while Paused by your phone offers Allow itself, the hint does not ask the same`() {
        // Turned down at setup, so the offer is not what holds the hint back.
        val paused = none.turnedDown(ai).pausedOnce(ai)

        assertFalse(aiState(AddonState.Ready, exempt = false, paused, hold = AiHold.FROZEN).locked.hint)
        assertTrue("the hold gone (the next run started), the hint stays", aiState(AddonState.Ready, exempt = false, paused).locked.hint)
    }

    // The copy as it ships

    private val addonStrings = File("src/main/res/values/strings_addon.xml").readText()
    private val shared = File("src/main/res/values/strings.xml").readText()

    private fun string(name: String, text: String = addonStrings): String =
        checkNotNull(Regex("""<string name="$name">(.*?)</string>""").find(text)) { "no string $name" }.groupValues[1].replace("\\'", "'")

    @Test
    fun `the offer, the row and the hints say it plainly, labels in 3 words or fewer`() {
        assertEquals("Run when locked", string("addon_locked_title"))
        assertEquals("Locked phone", string("addon_locked_row"))
        assertEquals("Allowed", string("addon_locked_allowed"))
        assertEquals("Not allowed", string("addon_locked_not_allowed"))
        for (label in listOf("addon_locked_title", "addon_locked_row", "addon_locked_allowed", "addon_locked_not_allowed")) {
            assertTrue(label, string(label).split(' ').count { it.isNotBlank() } <= 3)
        }
        assertEquals("So AI can finish while your phone is locked.", string("ai_locked_text"))
        assertEquals("So downloads can finish while your phone is locked.", string("dl_locked_text"))
        assertEquals("A locked phone paused the AI. Allow it to keep running?", string("ai_locked_hint"))
        assertEquals("A locked phone paused a download. Allow it to keep running?", string("dl_locked_hint"))
        assertFalse("an em-dash", '—' in addonStrings)
    }

    @Test
    fun `the AI paused by the phone says a locked phone may pause it`() {
        assertEquals("Paused by your phone", string("ai_held_frozen", shared))
        assertEquals("A locked phone may pause the AI. KJB tries again within the hour.", string("ai_held_frozen_detail", shared))
    }
}
