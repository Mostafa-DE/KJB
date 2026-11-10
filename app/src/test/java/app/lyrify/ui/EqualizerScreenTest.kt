package app.lyrify.ui

import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.ui.geometry.Offset
import app.lyrify.audio.BuiltInPreset
import app.lyrify.audio.EqProfile
import app.lyrify.audio.EqProfiles
import app.lyrify.audio.EqSound
import app.lyrify.audio.FLAT_BANDS
import app.lyrify.audio.OutputKey
import app.lyrify.audio.OutputKind
import app.lyrify.audio.OwnPreset
import app.lyrify.audio.PresetChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The equalizer screen: what it shows for the output playing, and what each control does to that output only. Made-up names. */
class EqualizerScreenTest {
    private val buds = OutputKey(OutputKind.BLUETOOTH, "Pebble Buds")
    private val curve = listOf(3f, 2f, 1f, 0f, -1f, -1f, 0f, 1f, 2f, 3f)
    private val tuned = EqProfile(
        EqSound(enabled = true, bandsDb = curve, bassBoostDb = 4.5f, preampDb = -3f, balance = -0.25f, mono = true),
        preset = PresetChoice.Own("Late Night"),
    )
    private val speakerOn = EqProfile(EqSound(enabled = true, BuiltInPreset.VOCAL.bandsDb), PresetChoice.BuiltIn(BuiltInPreset.VOCAL))
    private val profiles = EqProfiles(
        profiles = mapOf(OutputKey.SPEAKER to speakerOn, buds to tuned),
        presets = listOf(OwnPreset("Late Night", curve), OwnPreset("Bright Morning", BuiltInPreset.POP.bandsDb)),
    )

    private fun EqProfiles.on(output: OutputKey, vararg actions: EqAction): EqProfiles = actions.fold(this) { all, action -> all.after(output, action) }

    // What it shows

    @Test
    fun `the screen shows the output's profile, the 11 presets then the user's, and the one in use`() {
        val state = eqScreenState(profiles, buds)
        assertEquals(buds, state.output)
        assertEquals(tuned, state.profile)
        assertEquals(BuiltInPreset.entries.map(PresetChoice::BuiltIn) + PresetChoice.Own("Late Night") + PresetChoice.Own("Bright Morning"), state.presets)
        assertEquals(11, BuiltInPreset.entries.size)
        assertEquals(PresetChoice.Own("Late Night"), state.selected)
    }

    @Test
    fun `a new output shows Flat with the equalizer off`() {
        val state = eqScreenState(profiles, OutputKey(OutputKind.WIRED))
        assertEquals(EqProfile(), state.profile)
        assertFalse(state.profile.sound.enabled)
        assertEquals(PresetChoice.BuiltIn(BuiltInPreset.FLAT), state.selected)
    }

    @Test
    fun `the preset in use is found whatever its case, bands moved by hand select none`() {
        val renamed = profiles.withProfile(buds, tuned.copy(preset = PresetChoice.Own("LATE NIGHT")))
        assertEquals(PresetChoice.Own("Late Night"), eqScreenState(renamed, buds).selected)
        assertNull(eqScreenState(profiles.on(buds, EqAction.Band(0, 6f)), buds).selected)
    }

    @Test
    fun `gains read as short signed numbers, with a true minus`() {
        assertEquals("+3", dbText(3f))
        assertEquals("+3.5", dbText(3.5f))
        assertEquals("\u221212", dbText(-12f))
        assertEquals("0", dbText(0f))
        assertEquals("0", dbText(-0f))
    }

    // Review E: the phone's sound effects

    @Test
    fun `the phone's sound effects open only for the player's own audio session, never the whole phone's output`() {
        assertNull(effectsSession(0))
        assertNull(effectsSession(-1))
        assertEquals(4_097, effectsSession(4_097))
    }

    // On and off

    @Test
    fun `the switch turns the equalizer on or off for this output only, keeping its sound`() {
        val wired = OutputKey(OutputKind.WIRED)
        val on = profiles.on(wired, EqAction.Switch(true))
        assertTrue(on.profile(wired).sound.enabled)
        assertEquals(EqProfile().copy(sound = EqSound(enabled = true)), on.profile(wired))
        assertEquals(profiles.profile(buds), on.profile(buds))
        val off = profiles.on(buds, EqAction.Switch(false))
        assertEquals(tuned.copy(sound = tuned.sound.copy(enabled = false)), off.profile(buds))
        assertEquals(speakerOn, off.profile(OutputKey.SPEAKER))
    }

    // Bands

    @Test
    fun `a band moved by hand takes half-dB steps within 12 dB and leaves no preset`() {
        val moved = profiles.on(OutputKey.SPEAKER, EqAction.Band(2, 3.3f))
        assertEquals(BuiltInPreset.VOCAL.bandsDb.toMutableList().also { it[2] = 3.5f }, moved.profile(OutputKey.SPEAKER).sound.bandsDb)
        assertNull(moved.profile(OutputKey.SPEAKER).preset)
        assertEquals(12f, profiles.on(OutputKey.SPEAKER, EqAction.Band(9, 40f)).profile(OutputKey.SPEAKER).sound.bandsDb[9])
        assertEquals(-12f, profiles.on(OutputKey.SPEAKER, EqAction.Band(0, -13.2f)).profile(OutputKey.SPEAKER).sound.bandsDb[0])
        assertEquals(profiles.profile(buds), moved.profile(buds))
    }

    @Test
    fun `a band that does not exist changes nothing`() {
        assertEquals(profiles, profiles.on(buds, EqAction.Band(10, 3f)))
        assertEquals(profiles, profiles.on(buds, EqAction.Band(-1, 3f)))
    }

    // Presets

    @Test
    fun `a preset loads its bands and keeps the rest of the profile`() {
        val rock = profiles.on(buds, EqAction.UsePreset(PresetChoice.BuiltIn(BuiltInPreset.ROCK)))
        assertEquals(tuned.sound.copy(bandsDb = BuiltInPreset.ROCK.bandsDb), rock.profile(buds).sound)
        assertEquals(PresetChoice.BuiltIn(BuiltInPreset.ROCK), eqScreenState(rock, buds).selected)
        val own = rock.on(buds, EqAction.UsePreset(PresetChoice.Own("Bright Morning")))
        assertEquals(BuiltInPreset.POP.bandsDb, own.profile(buds).sound.bandsDb)
    }

    @Test
    fun `save preset keeps the bands under a new name, which the output then uses`() {
        val shaped = profiles.on(OutputKey.SPEAKER, EqAction.Band(0, 5f))
        val saved = shaped.on(OutputKey.SPEAKER, EqAction.SavePreset("  Morning Walk "))
        val bands = shaped.profile(OutputKey.SPEAKER).sound.bandsDb
        assertEquals(OwnPreset("Morning Walk", bands), saved.presets.last())
        assertEquals(PresetChoice.Own("Morning Walk"), saved.profile(OutputKey.SPEAKER).preset)
        assertEquals(PresetChoice.Own("Morning Walk"), eqScreenState(saved, OutputKey.SPEAKER).selected)
        // The same name again replaces it in its place.
        val again = saved.on(buds, EqAction.SavePreset("morning walk"))
        assertEquals(listOf("Late Night", "Bright Morning", "morning walk"), again.presets.map { it.name })
        assertEquals(curve, again.presets.last().bandsDb)
        assertEquals(profiles, profiles.on(buds, EqAction.SavePreset("   ")))
    }

    @Test
    fun `rename and delete change the user's presets, never the sound of an output`() {
        val renamed = profiles.on(OutputKey.SPEAKER, EqAction.RenamePreset("Late Night", "Quiet Hours"))
        assertEquals(listOf("Quiet Hours", "Bright Morning"), renamed.presets.map { it.name })
        assertEquals(PresetChoice.Own("Quiet Hours"), eqScreenState(renamed, buds).selected)
        assertEquals(tuned.sound, renamed.profile(buds).sound)
        val deleted = renamed.on(OutputKey.SPEAKER, EqAction.DeletePreset("quiet hours"))
        assertEquals(listOf("Bright Morning"), deleted.presets.map { it.name })
        assertEquals(tuned.sound, deleted.profile(buds).sound)
        assertNull(eqScreenState(deleted, buds).selected)
        assertEquals(BuiltInPreset.entries.map(PresetChoice::BuiltIn) + PresetChoice.Own("Bright Morning"), eqScreenState(deleted, buds).presets)
    }

    // Bass boost, pre-amp, balance and mono

    @Test
    fun `bass boost takes half-dB steps from 0 to 12 dB, the pre-amp within 12 dB either way`() {
        assertEquals(4.5f, profiles.on(buds, EqAction.BassBoost(4.4f)).profile(buds).sound.bassBoostDb)
        assertEquals(0f, profiles.on(buds, EqAction.BassBoost(-3f)).profile(buds).sound.bassBoostDb)
        assertEquals(12f, profiles.on(buds, EqAction.BassBoost(20f)).profile(buds).sound.bassBoostDb)
        assertEquals(-6.5f, profiles.on(buds, EqAction.Preamp(-6.6f)).profile(buds).sound.preampDb)
        assertEquals(-12f, profiles.on(buds, EqAction.Preamp(-30f)).profile(buds).sound.preampDb)
        assertEquals(12f, profiles.on(buds, EqAction.Preamp(30f)).profile(buds).sound.preampDb)
        // Neither touches the preset.
        assertEquals(PresetChoice.Own("Late Night"), profiles.on(buds, EqAction.BassBoost(6f), EqAction.Preamp(2f)).profile(buds).preset)
    }

    @Test
    fun `balance takes 5 percent steps from left only to right only, mono switches`() {
        assertEquals(0.3f, profiles.on(buds, EqAction.Balance(0.31f)).profile(buds).sound.balance, 1e-6f)
        assertEquals(-1f, profiles.on(buds, EqAction.Balance(-1.4f)).profile(buds).sound.balance)
        assertEquals(1f, profiles.on(buds, EqAction.Balance(2f)).profile(buds).sound.balance)
        assertEquals(0f, profiles.on(buds, EqAction.Balance(0.02f)).profile(buds).sound.balance)
        assertFalse(profiles.on(buds, EqAction.Mono(false)).profile(buds).sound.mono)
        assertTrue(profiles.on(OutputKey.SPEAKER, EqAction.Mono(true)).profile(OutputKey.SPEAKER).sound.mono)
        assertEquals(FLAT_BANDS.size, profiles.on(buds, EqAction.Mono(false)).profile(buds).sound.bandsDb.size)
    }

    // The look (D1): the chips, the Outputs sheet, the bands panel and its curve

    @Test
    fun `the chips are the 11 presets, the user's own, then Save preset, with the one in use selected`() {
        val chips = eqChips(eqScreenState(profiles, buds))
        val presets = BuiltInPreset.entries.map { EqChip.Preset(PresetChoice.BuiltIn(it)) } +
            EqChip.Preset(PresetChoice.Own("Late Night")) + EqChip.Preset(PresetChoice.Own("Bright Morning"))
        assertEquals(presets + EqChip.Save, chips)
        assertEquals(11, selectedChip(eqScreenState(profiles, buds)))
        assertEquals(0, selectedChip(eqScreenState(profiles, OutputKey(OutputKind.WIRED))))
    }

    @Test
    fun `bands moved by hand add Custom first, selected`() {
        val moved = eqScreenState(profiles.on(buds, EqAction.Band(4, 2f)), buds)
        val chips = eqChips(moved)
        assertEquals(EqChip.Custom, chips.first())
        assertEquals(EqChip.Save, chips.last())
        assertEquals(1 + 13 + 1, chips.size)
        assertEquals(0, selectedChip(moved))
    }

    @Test
    fun `the selected chip scrolls just into view, 16 from the row's edges`() {
        assertEquals(0, intoView(start = 20, end = 100, viewStart = -16, viewEnd = 344, margin = 16))
        assertEquals(0, intoView(start = 0, end = 328, viewStart = -16, viewEnd = 344, margin = 16))
        assertEquals(-30, intoView(start = -30, end = 50, viewStart = -16, viewEnd = 344, margin = 16))
        assertEquals(72, intoView(start = 300, end = 400, viewStart = -16, viewEnd = 344, margin = 16))
        // Wider than the row: its start shows.
        assertEquals(-10, intoView(start = -10, end = 600, viewStart = -16, viewEnd = 344, margin = 16))
    }

    @Test
    fun `the Outputs sheet lists the speaker, every output with a profile and the one playing, by kind then name`() {
        val rows = eqOutputRows(profiles, buds)
        assertEquals(listOf(EqOutputRow(OutputKey.SPEAKER, speakerOn, playing = false), EqOutputRow(buds, tuned, playing = true)), rows)
        val wired = OutputKey(OutputKind.WIRED)
        assertEquals(
            listOf(EqOutputRow(OutputKey.SPEAKER, speakerOn, false), EqOutputRow(wired, EqProfile(), true), EqOutputRow(buds, tuned, false)),
            eqOutputRows(profiles, wired),
        )
        val car = OutputKey(OutputKind.BLUETOOTH, "car stereo")
        val budsOnly = EqProfiles(profiles = mapOf(buds to tuned, car to speakerOn))
        assertEquals(
            listOf(EqOutputRow(OutputKey.SPEAKER, null, false), EqOutputRow(car, speakerOn, false), EqOutputRow(buds, tuned, true)),
            eqOutputRows(budsOnly, buds),
        )
        assertEquals(listOf(EqOutputRow(OutputKey.SPEAKER, EqProfile(), true)), eqOutputRows(EqProfiles(), OutputKey.SPEAKER))
    }

    @Test
    fun `More's line shows the balance and mono while it is closed, nothing when both are as new`() {
        val balance = { value: Float -> if (value < 0) "Left" else "Right" }
        assertEquals("Left · Mono", moreLine(tuned.sound, balance, "Mono"))
        assertEquals("Mono", moreLine(EqSound(mono = true), balance, "Mono"))
        assertEquals("Right", moreLine(EqSound(balance = 0.5f), balance, "Mono"))
        assertNull(moreLine(EqSound(), balance, "Mono"))
    }

    @Test
    fun `bass boost reads as a share of its 12 dB, the balance's centre step holds under a finger`() {
        assertEquals(0, bassPercent(0f))
        assertEquals(50, bassPercent(6f))
        assertEquals(21, bassPercent(2.5f))
        assertEquals(100, bassPercent(12f))
        assertEquals(0f, balanceHeld(0.07f))
        assertEquals(0f, balanceHeld(-0.05f))
        assertEquals(0.08f, balanceHeld(0.08f))
        assertEquals(-1f, balanceHeld(-1f))
    }

    @Test
    fun `a gain's height in the plot, 44 under its top for +12, 1 dB every 8, and back`() {
        val plot = EqPlot(width = 330f)
        assertEquals(252f, plot.height)
        assertEquals(44f, plot.y(12f))
        assertEquals(140f, plot.y(0f))
        assertEquals(236f, plot.y(-12f))
        assertEquals(92f, plot.y(6f))
        assertEquals(6f, plot.db(92f))
        assertEquals(12f, plot.db(0f))
        assertEquals(-12f, plot.db(250f))
        assertEquals(16.5f, plot.x(0))
        assertEquals(313.5f, plot.x(9))
        // In px on a 2.5x screen.
        assertEquals(350f, EqPlot(width = 825f, unit = 2.5f).y(0f))
    }

    @Test
    fun `the scale's labels sit level with dB, +12, 0 and -12`() {
        assertEquals(listOf(16f, 44f, 140f, 236f), EqPlot(width = 330f).scale)
    }

    @Test
    fun `the curve runs flat from the plot's start through every knob's centre to its end`() {
        val plot = EqPlot(width = 1000f)
        val bands = listOf(6f, 5f, 4f, 2f, 0f, 0f, 0f, 0f, 0f, 0f)
        val curve = eqCurve(bands, plot)
        assertEquals(Offset(0f, 92f), curve.start)
        assertEquals(bands.indices.map { Offset(plot.x(it), plot.y(bands[it])) }, curve.knots)
        assertEquals(9, curve.steps.size)
        assertEquals(curve.knots.drop(1), curve.steps.map { it.to })
        assertEquals(Offset(1000f, 140f), curve.end)
    }

    @Test
    fun `the curve's handles are the prototype's monotone ones`() {
        val curve = eqCurve(listOf(6f, 5f, 4f, 2f, 0f, 0f, 0f, 0f, 0f, 0f), EqPlot(width = 1000f))
        assertClose(Offset(83.333f, 94.667f), curve.steps[0].c1)
        assertClose(Offset(116.667f, 97.333f), curve.steps[0].c2)
        assertClose(Offset(250f - 33.333f, 108f - 4f), curve.steps[1].c2)
        assertClose(Offset(383.333f, 129.333f), curve.steps[3].c1)
        assertClose(Offset(416.667f, 140f), curve.steps[3].c2)
        // Flat stays flat.
        assertTrue(eqCurve(List(10) { 0f }, EqPlot(width = 400f)).steps.all { it.c1.y == 140f && it.c2.y == 140f && it.to.y == 140f })
    }

    @Test
    fun `the curve never bulges past a knob, however steep`() {
        val plot = EqPlot(width = 330f)
        listOf(BuiltInPreset.SMALL_SPEAKERS.bandsDb, listOf(12f, -12f, 12f, -12f, 12f, 12f, 0f, -12f, -11.5f, 12f)).forEach { bands ->
            val curve = eqCurve(bands, plot)
            assertEquals(9, curve.steps.size)
            curve.steps.forEachIndexed { i, step ->
                val low = minOf(curve.knots[i].y, step.to.y) - 1e-3f
                val high = maxOf(curve.knots[i].y, step.to.y) + 1e-3f
                assertTrue("step $i of $bands", step.c1.y in low..high && step.c2.y in low..high)
            }
        }
    }

    @Test
    fun `knobs glide 240 ms, ease-out cubic, to a preset, and land at once under a finger or with reduced motion`() {
        val glide = glideSpec(dragging = false, reduced = false) as TweenSpec<Float>
        assertEquals(240, glide.durationMillis)
        assertEquals(Motion.Glide, glide.easing)
        assertTrue(glideSpec(dragging = true, reduced = false) is SnapSpec<Float>)
        assertTrue(glideSpec(dragging = false, reduced = true) is SnapSpec<Float>)
    }

    private fun assertClose(expected: Offset, actual: Offset) {
        assertEquals("x of $actual", expected.x, actual.x, 1e-2f)
        assertEquals("y of $actual", expected.y, actual.y, 1e-2f)
    }
}
