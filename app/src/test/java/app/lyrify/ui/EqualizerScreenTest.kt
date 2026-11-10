package app.lyrify.ui

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
}
