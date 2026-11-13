package app.lyrify.ui

import app.lyrify.R
import app.lyrify.addon.AddonState
import app.lyrify.addon.HandoverState
import app.lyrify.addon.InstallFailure
import app.lyrify.addon.InstallState
import app.lyrify.models.ModelState
import app.lyrify.models.NpuChip
import app.lyrify.models.NpuChips
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The AI page, its Tools row and its Now tile while the AI add-on is missing, being got, out of date or taking over the models. */
class AddonGlanceTest {
    private val chip = NpuChips.ALL.first()
    private val total = 980_000_000L

    private fun state(
        addon: AddonState? = AddonState.Missing,
        install: InstallState = InstallState.Idle,
        handover: HandoverState = HandoverState.None,
        chip: NpuChip? = this.chip,
        newEnough: Boolean = true,
        enabled: Boolean = true,
        models: ModelState? = null,
    ) = AiState(chip, newEnough, models, enabled, progress = null, hold = null, pluggedIn = true, unrestricted = true, stopping = false, addon, install, handover)

    private fun text(res: Int, vararg args: GlanceArg) = GlanceText(res, args.toList())

    private fun toolsLine(ai: AiState) = toolRows(0, DupesView.Loading, 0, 0, null, ai, aiWaiting = 3).firstOrNull { it.tool == Tool.AI }?.line

    // No add-on

    @Test
    fun `a supported phone without the add-on offers Get AI, on and off alike, with the reason behind the line`() {
        for (enabled in listOf(true, false)) {
            val glance = aiGlance(state(enabled = enabled), waiting = 3)
            assertEquals(text(R.string.ai_addon_needed), glance.line)
            assertEquals(text(R.string.ai_addon_needed_detail), glance.sheet)
            assertEquals(GlanceAction(AiActionKind.GET_ADDON, text(R.string.ai_addon_get)), glance.action)
        }
        assertEquals(ToolLine.Text(R.string.ai_addon_get), toolsLine(state()))
    }

    @Test
    fun `a user who had the AI is asked to get the add-on to keep using it, with a dot`() {
        val ai = state(handover = HandoverState.Waiting)

        val glance = aiGlance(ai, waiting = 3)

        assertEquals(text(R.string.ai_addon_keep), glance.line)
        assertEquals(text(R.string.ai_addon_keep_detail), glance.sheet)
        assertEquals(AiActionKind.GET_ADDON, glance.action?.kind)
        assertTrue(aiAttention(ai, waiting = 3))
        assertFalse("a new user is not nagged", aiAttention(state(), waiting = 3))
    }

    @Test
    fun `an add-on too old for this KJB asks for an update, never a crash`() {
        val ai = state(addon = AddonState.Outdated(api = 0))

        val glance = aiGlance(ai, waiting = 3)

        assertEquals(text(R.string.ai_addon_update), glance.line)
        assertEquals(text(R.string.ai_addon_update_detail), glance.sheet)
        assertEquals(GlanceAction(AiActionKind.GET_ADDON, text(R.string.ai_addon_update_action)), glance.action)
        assertEquals(ToolLine.Text(R.string.tools_ai_update), toolsLine(ai))
        assertTrue(aiAttention(ai, waiting = 3))
    }

    @Test
    fun `an add-on not signed with KJB's key opens its app info, to be uninstalled`() {
        val glance = aiGlance(state(addon = AddonState.Untrusted), waiting = 3)

        assertEquals(text(R.string.ai_addon_untrusted), glance.line)
        assertEquals(text(R.string.ai_addon_untrusted_detail), glance.sheet)
        assertEquals(AiActionKind.OPEN_ADDON_INFO, glance.action?.kind)
    }

    @Test
    fun `an add-on that does not answer says so and offers nothing to tap`() {
        val glance = aiGlance(state(addon = AddonState.Unreachable), waiting = 3)

        assertEquals(text(R.string.ai_addon_unreachable), glance.line)
        assertNull(glance.action)
    }

    @Test
    fun `with the add-on ready the page is the AI's own again`() {
        val glance = aiGlance(state(addon = AddonState.Ready, models = ModelState.Missing(0, total)), waiting = 3)

        assertEquals(text(R.string.ai_models_missing), glance.line)
        assertEquals(AiActionKind.DOWNLOAD, glance.action?.kind)
    }

    // Getting it

    @Test
    fun `the download shows its share with Cancel`() {
        val glance = aiGlance(state(install = InstallState.Downloading(23_000_000, 69_000_000)), waiting = 3)

        assertEquals(Ring(1f / 3, RingColor.HIGHLIGHT), glance.ring)
        assertEquals(Centre.Value(CentreValue.Percent(33)), glance.centre)
        assertEquals(text(R.string.ai_addon_downloading, GlanceArg.Bytes(23_000_000), GlanceArg.Bytes(69_000_000)), glance.line)
        assertEquals(GlanceAction(AiActionKind.CANCEL_INSTALL, text(R.string.ai_addon_cancel)), glance.action)
    }

    @Test
    fun `the phone's install toggle is one tap away, with the reason behind the line`() {
        val ai = state(install = InstallState.NeedsPermission)

        val glance = aiGlance(ai, waiting = 3)

        assertEquals(text(R.string.ai_addon_allow), glance.line)
        assertEquals(text(R.string.ai_addon_allow_detail), glance.sheet)
        assertEquals(GlanceAction(AiActionKind.OPEN_INSTALL_SETTINGS, text(R.string.ai_addon_open_settings)), glance.action)
        assertTrue(aiAttention(ai, waiting = 3))
    }

    @Test
    fun `while Android asks to confirm, the page says so, with Cancel`() {
        val glance = aiGlance(state(install = InstallState.Installing), waiting = 3)

        assertEquals(text(R.string.ai_addon_confirm), glance.line)
        assertEquals(text(R.string.ai_addon_confirm_detail), glance.sheet)
        assertEquals(AiActionKind.CANCEL_INSTALL, glance.action?.kind)
    }

    @Test
    fun `each failure says why behind the line, and Try again starts over`() {
        val reasons = mapOf(
            InstallFailure.NOT_PUBLISHED to R.string.ai_addon_failed_not_published,
            InstallFailure.NETWORK to R.string.ai_addon_failed_network,
            InstallFailure.DOWNLOAD to R.string.ai_addon_failed_download,
            InstallFailure.NOT_KJBS to R.string.ai_addon_failed_not_kjbs,
            InstallFailure.INSTALL to R.string.ai_addon_failed_install,
        )
        assertEquals(InstallFailure.entries.toSet(), reasons.keys)
        for ((reason, sheet) in reasons) {
            val ai = state(install = InstallState.Failed(reason))
            val glance = aiGlance(ai, waiting = 3)
            assertEquals(Ring(1f, RingColor.ERROR), glance.ring)
            assertEquals(text(R.string.ai_addon_failed), glance.line)
            assertEquals(text(sheet), glance.sheet)
            assertEquals(GlanceAction(AiActionKind.GET_ADDON, text(R.string.ai_try_again)), glance.action)
            assertTrue(aiAttention(ai, waiting = 3))
        }
    }

    @Test
    fun `an update in progress shows as getting it, not as out of date`() {
        val glance = aiGlance(state(addon = AddonState.Outdated(0), install = InstallState.Installing), waiting = 3)

        assertEquals(text(R.string.ai_addon_confirm), glance.line)
    }

    // The models KJB had

    @Test
    fun `the handover shows its share, with nothing to tap`() {
        val glance = aiGlance(state(addon = AddonState.Ready, handover = HandoverState.Moving(490_000_000, total)), waiting = 3)

        assertEquals(Centre.Value(CentreValue.Percent(50)), glance.centre)
        assertEquals(text(R.string.ai_addon_moving), glance.line)
        assertEquals(text(R.string.ai_addon_moving_detail), glance.sheet)
        assertNull(glance.action)
    }

    // Other phones, and the Now tile

    @Test
    fun `on a phone the AI does not run on, Tools shows nothing about it`() {
        assertNull(toolsLine(state(chip = null)))
        assertNull(toolsLine(state(newEnough = false)))
        assertFalse(toolRows(0, DupesView.Loading, 0, 0, null, state(chip = null), 0).any { it.tool == Tool.AI })
    }

    @Test
    fun `the Now tile asks to get or update the add-on while the AI is on`() {
        assertEquals(TileValue.Text(R.string.ai_tile_get), aiTile(state(), waiting = 3)?.value)
        assertEquals(R.string.ai_tile_addon, aiTile(state(), waiting = 3)?.label)
        assertEquals(TileValue.Text(R.string.ai_addon_update_action), aiTile(state(addon = AddonState.Outdated(0)), waiting = 3)?.value)
        assertNull(aiTile(state(enabled = false), waiting = 3))
    }

    @Test
    fun `the switch shows only once the add-on is ready`() {
        assertFalse(state().showsSwitch)
        assertFalse(state(addon = AddonState.Outdated(0)).showsSwitch)
        assertTrue(state(addon = AddonState.Ready).showsSwitch)
        assertFalse(state(addon = AddonState.Ready, chip = null).showsSwitch)
    }
}
