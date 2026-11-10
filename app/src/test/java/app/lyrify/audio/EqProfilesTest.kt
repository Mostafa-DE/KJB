package app.lyrify.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Profiles per output, the user's presets, and `eq-profiles.json` read leniently. Made-up device and preset names. */
class EqProfilesTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val buds = OutputKey(OutputKind.BLUETOOTH, "Pebble Buds")
    private val curve = listOf(3f, 2f, 1f, 0f, -1f, -1f, 0f, 1f, 2f, 3f)

    private val tuned = EqProfile(
        EqSound(enabled = true, bandsDb = curve, bassBoostDb = 4.5f, preampDb = -3f, balance = -0.25f, mono = true),
        preset = PresetChoice.Own("Late Night"),
    )

    private val profiles = EqProfiles(
        profiles = mapOf(OutputKey.SPEAKER to EqProfile(EqSound(enabled = true, BuiltInPreset.VOCAL.bandsDb), PresetChoice.BuiltIn(BuiltInPreset.VOCAL)), buds to tuned),
        presets = listOf(OwnPreset("Late Night", curve), OwnPreset("Bright Morning", BuiltInPreset.POP.bandsDb)),
    )

    // Profiles and presets

    @Test
    fun `an output without a profile plays Flat with the equalizer off`() {
        val fresh = EqProfiles().profile(OutputKey(OutputKind.WIRED))
        assertFalse(fresh.sound.enabled)
        assertEquals(FLAT_BANDS, fresh.sound.bandsDb)
        assertEquals(PresetChoice.BuiltIn(BuiltInPreset.FLAT), fresh.preset)
        assertEquals(EqSound(), fresh.sound)
    }

    @Test
    fun `each output keeps its own profile`() {
        val wired = OutputKey(OutputKind.WIRED)
        val edited = EqProfiles().withProfile(buds, tuned)
        assertEquals(tuned, edited.profile(buds))
        assertEquals(EqProfile(), edited.profile(wired))
        assertEquals(EqProfile(), edited.profile(OutputKey(OutputKind.BLUETOOTH, "Other Buds")))
    }

    @Test
    fun `a saved preset loads onto any output's bands and is remembered as its preset`() {
        val saved = EqProfiles().savePreset("Late Night", curve)
        assertEquals(listOf(OwnPreset("Late Night", curve)), saved.presets)
        val loaded = saved.usePreset(OutputKey.SPEAKER, PresetChoice.Own("Late Night"))
        assertEquals(curve, loaded.profile(OutputKey.SPEAKER).sound.bandsDb)
        assertEquals(PresetChoice.Own("Late Night"), loaded.profile(OutputKey.SPEAKER).preset)
        // Loading a preset never switches the equalizer on.
        assertFalse(loaded.profile(OutputKey.SPEAKER).sound.enabled)
    }

    @Test
    fun `a built-in preset loads its bands and keeps the rest of the profile`() {
        val loaded = EqProfiles().withProfile(buds, tuned).usePreset(buds, PresetChoice.BuiltIn(BuiltInPreset.JAZZ))
        assertEquals(tuned.sound.copy(bandsDb = BuiltInPreset.JAZZ.bandsDb), loaded.profile(buds).sound)
        assertEquals(PresetChoice.BuiltIn(BuiltInPreset.JAZZ), loaded.profile(buds).preset)
    }

    @Test
    fun `saving under an existing name replaces that preset in its place, deleting removes it`() {
        val replaced = profiles.savePreset("late night", FLAT_BANDS)
        assertEquals(listOf(OwnPreset("late night", FLAT_BANDS), OwnPreset("Bright Morning", BuiltInPreset.POP.bandsDb)), replaced.presets)
        assertEquals(FLAT_BANDS, replaced.bandsOf(PresetChoice.Own("Late Night")))
        val deleted = profiles.deletePreset("Bright Morning")
        assertEquals(listOf(OwnPreset("Late Night", curve)), deleted.presets)
        assertNull(deleted.bandsOf(PresetChoice.Own("Bright Morning")))
    }

    @Test
    fun `deleting a preset leaves the profiles that used it with their bands and no preset`() {
        val deleted = profiles.deletePreset("late night")
        assertEquals(tuned.copy(preset = null), deleted.profile(buds))
        assertEquals(profiles.profile(OutputKey.SPEAKER), deleted.profile(OutputKey.SPEAKER))
    }

    @Test
    fun `a preset that is gone loads nothing`() {
        assertEquals(profiles, profiles.usePreset(buds, PresetChoice.Own("Gone Away")))
    }

    // The stored form

    @Test
    fun `profiles and presets round trip`() {
        assertEquals(profiles, EqProfilesJson.decode(EqProfilesJson.encode(profiles)))
        assertEquals(EqProfiles(), EqProfilesJson.decode(EqProfilesJson.encode(EqProfiles())))
    }

    @Test
    fun `the stored form keys profiles by output kind and Bluetooth name only`() {
        val stored = EqProfilesJson.encode(profiles)
        assertTrue(stored, stored.contains("\"speaker\":"))
        assertTrue(stored, stored.contains("\"bluetooth:Pebble Buds\":"))
    }

    @Test
    fun `fields read leniently, missing, of another type or out of range`() {
        val stored = """
            {"version":1,"profiles":{
              "wired":{"enabled":"yes","bands":[1,2,3],"bassBoost":30,"preamp":-40,"balance":7,"mono":true,"preset":"DISCO","newField":{}},
              "usb":{"enabled":true,"bands":[20,-20,0,0,0,0,0,0,0,"x"],"ownPreset":"Late Night"},
              "speaker":{"bands":[1,1,1,1,1,1,1,1,1,1],"preset":"ROCK"}
            },"presets":[],"newSection":[1,2]}
        """.trimIndent()
        val read = EqProfilesJson.decode(stored) ?: error("not read")
        assertEquals(
            EqProfile(EqSound(enabled = false, bandsDb = FLAT_BANDS, bassBoostDb = 12f, preampDb = -12f, balance = 1f, mono = true), preset = null),
            read.profile(OutputKey(OutputKind.WIRED)),
        )
        assertEquals(
            EqProfile(EqSound(enabled = true, bandsDb = FLAT_BANDS), PresetChoice.Own("Late Night")),
            read.profile(OutputKey(OutputKind.USB)),
        )
        assertEquals(EqProfile(EqSound(bandsDb = List(10) { 1f }), PresetChoice.BuiltIn(BuiltInPreset.ROCK)), read.profile(OutputKey.SPEAKER))
    }

    @Test
    fun `band gains out of range are held to 12 dB`() {
        val stored = """{"version":1,"profiles":{"usb":{"enabled":true,"bands":[20,-20,0,0,0,0,0,0,0,0]}}}"""
        assertEquals(listOf(12f, -12f) + List(8) { 0f }, EqProfilesJson.decode(stored)?.profile(OutputKey(OutputKind.USB))?.sound?.bandsDb)
    }

    @Test
    fun `a profile this version cannot read is kept as stored and written back`() {
        val stored = """{"version":2,"profiles":{"hologram":{"enabled":true,"depth":3},"speaker":"loud","bluetooth:Pebble Buds":{"enabled":true}}}"""
        val read = EqProfilesJson.decode(stored) ?: error("not read")
        assertEquals(setOf("hologram", "speaker"), read.unknownProfiles.keys)
        assertEquals(EqProfile(EqSound(enabled = true)), read.profile(buds))
        val again = EqProfilesJson.decode(EqProfilesJson.encode(read)) ?: error("not read")
        assertEquals(read, again)
    }

    @Test
    fun `presets that cannot be read are left out, the rest kept`() {
        val stored = """
            {"version":1,"presets":[{"name":"Late Night","bands":[3,2,1,0,-1,-1,0,1,2,3]},{"name":"","bands":[0,0,0,0,0,0,0,0,0,0]},
            {"name":"Short","bands":[1,2]},"Plain","Late Night",{"name":"Late Night","bands":[0,0,0,0,0,0,0,0,0,0]}]}
        """.trimIndent()
        assertEquals(listOf(OwnPreset("Late Night", curve)), EqProfilesJson.decode(stored)?.presets)
    }

    @Test
    fun `text that is not a JSON object is not profiles, a missing section is empty`() {
        assertNull(EqProfilesJson.decode("Pebble Buds, Harbour Box"))
        assertNull(EqProfilesJson.decode("[1,2,3]"))
        assertEquals(EqProfiles(), EqProfilesJson.decode("{}"))
        assertEquals(EqProfiles(), EqProfilesJson.decode("""{"profiles":[],"presets":{}}"""))
    }

    // The file

    private val logs = mutableListOf<String>()

    private fun store(file: File = File(folder.root, "eq-profiles.json")) = EqProfilesStore(file, clock = { 1_762_700_000_000 }) { logs += it }

    @Test
    fun `what is saved loads back the same, nothing saved is every output Flat and off`() {
        val store = store()
        assertEquals(EqProfiles(), store.load())
        assertTrue(store.save(profiles))
        assertEquals(profiles, store.load())
        assertEquals(listOf("eq-profiles.json"), folder.root.list()?.toList())
    }

    @Test
    fun `a file that is not profiles is copied aside, then the store starts over, logging no names`() {
        val file = File(folder.root, "eq-profiles.json")
        file.writeText("Pebble Buds")
        assertEquals(EqProfiles(), store(file).load())
        assertEquals("Pebble Buds", File(folder.root, "eq-profiles-corrupt-1762700000000.json").readText())
        assertFalse(logs.joinToString().contains("Pebble"))
        assertTrue(logs.isNotEmpty())
    }

    @Test
    fun `saves and loads from many threads at once never mix, fail or leave a file aside`() {
        val store = store()
        val versions = (1..8).map { n -> profiles.savePreset("Version $n", List(10) { n.toFloat() }) }
        val results = java.util.concurrent.ConcurrentLinkedQueue<Boolean>()
        val threads = versions.map { version ->
            Thread {
                repeat(40) {
                    results += store.save(version)
                    results += store.load() in versions
                }
            }
        }
        threads.forEach(Thread::start)
        threads.forEach(Thread::join)
        assertTrue(results.all { it })
        assertTrue(store.load() in versions)
        assertEquals(listOf("eq-profiles.json"), folder.root.list()?.toList())
    }

    @Test
    fun `a write that fails keeps the file before`() {
        val file = File(folder.root, "sub/eq-profiles.json")
        val store = store(file)
        assertTrue(store.save(profiles))
        val parent = file.parentFile ?: error("no folder")
        assumeTrue(parent.setWritable(false) && !parent.canWrite())
        try {
            assertFalse(store.save(EqProfiles()))
        } finally {
            parent.setWritable(true)
        }
        assertEquals(profiles, store.load())
    }
}
