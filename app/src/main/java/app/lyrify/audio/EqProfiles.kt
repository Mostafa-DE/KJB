package app.lyrify.audio

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** One output's equalizer: what it does, and the preset its bands came from (none once a band was moved by hand). */
data class EqProfile(val sound: EqSound = EqSound(), val preset: PresetChoice? = PresetChoice.BuiltIn(BuiltInPreset.FLAT))

/**
 * Every output's equalizer profile and the user's own presets, as `eq-profiles.json` keeps them. An output without a
 * profile yet plays with a new one: Flat, with the equalizer off.
 */
data class EqProfiles(
    val profiles: Map<OutputKey, EqProfile> = emptyMap(),
    /** "Save preset": the user's own, in the order saved. */
    val presets: List<OwnPreset> = emptyList(),
    /** Profiles a newer version stored that this one cannot read, by their stored key, written back as they were. */
    val unknownProfiles: Map<String, JsonElement> = emptyMap(),
) {
    fun profile(output: OutputKey): EqProfile = profiles[output] ?: EqProfile()

    fun withProfile(output: OutputKey, profile: EqProfile): EqProfiles =
        copy(profiles = profiles + (output to profile), unknownProfiles = unknownProfiles - output.id)

    /** [output]'s bands set to [choice]'s; unchanged when it is an own preset that is gone. */
    fun usePreset(output: OutputKey, choice: PresetChoice): EqProfiles {
        val bands = bandsOf(choice) ?: return this
        val profile = profile(output)
        return withProfile(output, profile.copy(sound = profile.sound.copy(bandsDb = bands), preset = choice))
    }

    /** Adds an own preset, or replaces the one with the same name (in any case), keeping its place. */
    fun savePreset(name: String, bandsDb: List<Float>): EqProfiles {
        val preset = OwnPreset(name.trim(), bandsDb)
        val at = presets.indexOfFirst { it.name.equals(preset.name, ignoreCase = true) }
        return copy(presets = if (at < 0) presets + preset else presets.toMutableList().also { it[at] = preset })
    }

    fun deletePreset(name: String): EqProfiles = copy(presets = presets.filterNot { it.name.equals(name.trim(), ignoreCase = true) })

    /** The bands of [choice]; null for an own preset that is gone. */
    fun bandsOf(choice: PresetChoice): List<Float>? = when (choice) {
        is PresetChoice.BuiltIn -> choice.preset.bandsDb
        is PresetChoice.Own -> presets.firstOrNull { it.name.equals(choice.name, ignoreCase = true) }?.bandsDb
    }
}

/**
 * The stored form of [EqProfiles]: JSON with a version, profiles by their output's [OutputKey.id], presets in a list.
 * Read leniently, as the ledger is: each profile and preset is read on its own, a field missing, of another type or not
 * a number reads as its default, numbers out of range are held to their range, and a profile this version cannot read
 * (an output kind from a newer version) is kept as it was stored. Only text that is not a JSON object is not profiles.
 */
object EqProfilesJson {
    const val VERSION: Int = 1

    fun encode(profiles: EqProfiles): String {
        val stored = profiles.unknownProfiles + profiles.profiles.entries.associate { (output, profile) -> output.id to profile(profile) }
        val root = JsonObject(
            mapOf(
                VERSION_KEY to JsonPrimitive(VERSION),
                PROFILES_KEY to JsonObject(stored),
                PRESETS_KEY to JsonArray(profiles.presets.map { JsonObject(mapOf(NAME to JsonPrimitive(it.name), BANDS to bands(it.bandsDb))) }),
            ),
        )
        return Json.encodeToString(JsonObject.serializer(), root)
    }

    /** The profiles in [text]; null when it is not a JSON object at all. */
    fun decode(text: String): EqProfiles? {
        val root = try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (_: SerializationException) {
            null
        } ?: return null
        val stored = root[PROFILES_KEY] as? JsonObject ?: JsonObject(emptyMap())
        val read = stored.mapValues { (id, element) -> OutputKey.parse(id)?.let { output -> (element as? JsonObject)?.let { output to profile(it) } } }
        return EqProfiles(
            profiles = read.values.filterNotNull().toMap(),
            presets = (root[PRESETS_KEY] as? JsonArray).orEmpty().mapNotNull(::preset).distinctBy { it.name.lowercase() },
            unknownProfiles = stored.filterKeys { read[it] == null },
        )
    }

    private fun profile(profile: EqProfile): JsonObject {
        val sound = profile.sound
        val fields = mutableMapOf(
            ENABLED to JsonPrimitive(sound.enabled),
            BANDS to bands(sound.bandsDb),
            BASS_BOOST to JsonPrimitive(sound.bassBoostDb),
            PREAMP to JsonPrimitive(sound.preampDb),
            BALANCE to JsonPrimitive(sound.balance),
            MONO to JsonPrimitive(sound.mono),
        )
        when (val preset = profile.preset) {
            is PresetChoice.BuiltIn -> fields[PRESET] = JsonPrimitive(preset.preset.name)
            is PresetChoice.Own -> fields[OWN_PRESET] = JsonPrimitive(preset.name)
            // Bands moved by hand: stored as no preset, which reads back as none, not as the default Flat.
            null -> fields[PRESET] = JsonNull
        }
        return JsonObject(fields)
    }

    private fun profile(stored: JsonObject): EqProfile {
        val d = EqSound()
        val sound = EqSound(
            enabled = stored.flag(ENABLED, d.enabled),
            bandsDb = bands(stored[BANDS]) ?: d.bandsDb,
            bassBoostDb = stored.number(BASS_BOOST, d.bassBoostDb, 0f, EqSound.BASS_BOOST_DB),
            preampDb = stored.number(PREAMP, d.preampDb, -EqSound.PREAMP_DB, EqSound.PREAMP_DB),
            balance = stored.number(BALANCE, d.balance, -1f, 1f),
            mono = stored.flag(MONO, d.mono),
        )
        val own = stored[OWN_PRESET]?.text()?.takeIf(String::isNotBlank)
        val preset = when {
            own != null -> PresetChoice.Own(own)
            PRESET !in stored -> EqProfile().preset
            else -> stored[PRESET]?.text()?.let { name -> BuiltInPreset.entries.firstOrNull { it.name == name } }?.let(PresetChoice::BuiltIn)
        }
        return EqProfile(sound, preset)
    }

    private fun preset(element: JsonElement): OwnPreset? {
        val stored = element as? JsonObject ?: return null
        val name = stored[NAME]?.text()?.trim()?.takeIf(String::isNotEmpty) ?: return null
        return OwnPreset(name, bands(stored[BANDS]) ?: return null)
    }

    private fun bands(values: List<Float>): JsonArray = JsonArray(values.map(::JsonPrimitive))

    /** One finite number per band, each held to ±12 dB; null for anything else. */
    private fun bands(element: JsonElement?): List<Float>? {
        val values = (element as? JsonArray)?.map { it.finite() ?: return null } ?: return null
        return values.takeIf { it.size == EQ_BANDS_HZ.size }?.map { it.coerceIn(-EqSound.BAND_DB, EqSound.BAND_DB) }
    }

    private fun JsonObject.flag(key: String, default: Boolean): Boolean = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: default

    private fun JsonObject.number(key: String, default: Float, min: Float, max: Float): Float = this[key]?.finite()?.coerceIn(min, max) ?: default

    private fun JsonElement.finite(): Float? = (this as? JsonPrimitive)?.takeUnless { it.isString }?.floatOrNull?.takeIf { it.isFinite() }

    private fun JsonElement.text(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    private const val VERSION_KEY: String = "version"
    private const val PROFILES_KEY: String = "profiles"
    private const val PRESETS_KEY: String = "presets"
    private const val ENABLED: String = "enabled"
    private const val BANDS: String = "bands"
    private const val BASS_BOOST: String = "bassBoost"
    private const val PREAMP: String = "preamp"
    private const val BALANCE: String = "balance"
    private const val MONO: String = "mono"
    private const val PRESET: String = "preset"
    private const val OWN_PRESET: String = "ownPreset"
    private const val NAME: String = "name"
}

/**
 * [EqProfiles] in one app-private file, written whole or not at all (written aside, then moved over). A file that is
 * not profiles at all is moved aside as `eq-profiles-corrupt-<epochMillis>.json`, then the store starts over: every
 * output Flat and off. Logs carry sizes and exception names only, never a device name. Blocking.
 */
class EqProfilesStore(private val file: File, private val clock: () -> Long, private val log: (String) -> Unit = {}) {
    fun load(): EqProfiles {
        if (!file.isFile) return EqProfiles()
        val text = try {
            file.readText()
        } catch (e: IOException) {
            log("eq profiles: not readable (${e.javaClass.simpleName}), every output Flat")
            return EqProfiles()
        }
        val profiles = EqProfilesJson.decode(text)
        if (profiles != null) {
            if (profiles.unknownProfiles.isNotEmpty()) log("eq profiles: ${profiles.unknownProfiles.size} from a newer version kept as they are")
            return profiles
        }
        val copy = File(file.parentFile, "$CORRUPT_PREFIX${clock()}$CORRUPT_SUFFIX")
        try {
            Files.move(file.toPath(), copy.toPath(), StandardCopyOption.REPLACE_EXISTING)
            log("eq profiles unreadable: kept as ${copy.name} (${text.length} chars), every output Flat")
        } catch (e: IOException) {
            log("eq profiles unreadable (${text.length} chars), not kept (${e.javaClass.simpleName}), every output Flat")
        }
        return EqProfiles()
    }

    /** False when it could not be written; the file before stays then. */
    fun save(profiles: EqProfiles): Boolean {
        val aside = File(file.path + TMP)
        return try {
            file.parentFile?.mkdirs()
            aside.writeText(EqProfilesJson.encode(profiles))
            Files.move(aside.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            true
        } catch (e: IOException) {
            aside.delete()
            log("eq profiles: not written (${e.javaClass.simpleName})")
            false
        }
    }

    private companion object {
        const val TMP: String = ".tmp"
        const val CORRUPT_PREFIX: String = "eq-profiles-corrupt-"
        const val CORRUPT_SUFFIX: String = ".json"
    }
}
