package app.lyrify.audio

import android.content.Context
import android.util.Log
import app.lyrify.LOG_TAG
import app.lyrify.logFailures
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import java.util.concurrent.CopyOnWriteArrayList

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

    /** Removes an own preset; the profiles that used it keep their bands, with no preset. */
    fun deletePreset(name: String): EqProfiles {
        fun named(other: String) = other.equals(name.trim(), ignoreCase = true)
        return copy(
            profiles = profiles.mapValues { (_, profile) ->
                val preset = profile.preset
                if (preset is PresetChoice.Own && named(preset.name)) profile.copy(preset = null) else profile
            },
            presets = presets.filterNot { named(it.name) },
        )
    }

    /**
     * Renames an own preset, in every profile that uses it too; unchanged when it is gone, or [to] is blank or another
     * preset's name (in any case).
     */
    fun renamePreset(from: String, to: String): EqProfiles {
        val name = to.trim()
        val at = presets.indexOfFirst { it.name.equals(from.trim(), ignoreCase = true) }
        if (at < 0 || name.isEmpty()) return this
        if (presets.withIndex().any { (index, other) -> index != at && other.name.equals(name, ignoreCase = true) }) return this
        val old = presets[at].name
        return copy(
            profiles = profiles.mapValues { (_, profile) ->
                val preset = profile.preset
                if (preset is PresetChoice.Own && preset.name.equals(old, ignoreCase = true)) profile.copy(preset = PresetChoice.Own(name)) else profile
            },
            presets = presets.toMutableList().also { it[at] = it[at].copy(name = name) },
        )
    }

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
 * output Flat and off. Loads and saves take turns, and each save writes its own file aside; every save that was written
 * is told to the [watch]ers, in turn, so the equalizer playing hears a restore. Logs carry sizes and exception names
 * only, never a device name. Blocking.
 */
class EqProfilesStore(private val file: File, private val clock: () -> Long, private val log: (String) -> Unit = {}) {
    private val lock = Any()
    private val watchers = CopyOnWriteArrayList<(EqProfiles) -> Unit>()

    fun load(): EqProfiles = synchronized(lock) { read() }

    /**
     * False when it could not be written, or when [still], asked in this save's turn, says it is no longer wanted; the
     * file before stays then.
     */
    fun save(profiles: EqProfiles, still: () -> Boolean = { true }): Boolean = synchronized(lock) {
        still() && write(profiles).also { written -> if (written) watchers.forEach { it(profiles) } }
    }

    /** [onSaved] hears every save that was written, from any caller (a restore too), on the saving thread. */
    fun watch(onSaved: (EqProfiles) -> Unit) {
        watchers += onSaved
    }

    private fun read(): EqProfiles {
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

    private fun write(profiles: EqProfiles): Boolean {
        var aside: File? = null
        return try {
            val folder = file.absoluteFile.parentFile ?: throw IOException("no folder")
            folder.mkdirs()
            aside = File.createTempFile(file.name, TMP, folder)
            aside.writeText(EqProfilesJson.encode(profiles))
            Files.move(aside.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            true
        } catch (e: IOException) {
            aside?.delete()
            log("eq profiles: not written (${e.javaClass.simpleName})")
            false
        }
    }

    companion object {
        private const val TMP: String = ".tmp"
        private const val CORRUPT_PREFIX: String = "eq-profiles-corrupt-"
        private const val CORRUPT_SUFFIX: String = ".json"
        private var made: EqProfilesStore? = null

        /**
         * The app's profiles: app-private `files/eq-profiles.json`, one store per process, shared by the equalizer and a
         * backup and its restore (backup/), so their loads and saves take turns.
         */
        @Synchronized
        fun of(context: Context): EqProfilesStore = made ?: EqProfilesStore(File(context.applicationContext.filesDir, "eq-profiles.json"), System::currentTimeMillis) {
            Log.w(LOG_TAG, it)
        }.also { made = it }
    }
}

/**
 * The equalizer as it plays, one per process ([of]): every output's profile and the user's presets ([profiles], null
 * until the store is read), the output music plays on ([output]: OutputRoutesPart follows it while the player runs, the
 * equalizer screen when it opens), and what the chain plays ([sound]: that output's profile, off until the profiles are
 * read). A change is heard at the chain's next buffer and written [SAVE_DELAY_MS] after the changes stop, so a band
 * dragged is one write; a write that fails leaves them unsaved, for the next change or [flush]. A save by anyone else,
 * a backup's restore, becomes the profiles playing and is never written over. Changes and switches on the main thread.
 */
class LiveEq(private val store: EqProfilesStore, private val scope: CoroutineScope) {
    private val _profiles = MutableStateFlow<EqProfiles?>(null)
    val profiles: StateFlow<EqProfiles?> = _profiles.asStateFlow()
    private val _output = MutableStateFlow(OutputKey.SPEAKER)
    val output: StateFlow<OutputKey> = _output.asStateFlow()
    private val _sound = MutableStateFlow(EqSound())

    /** What every chain plays now; its value is read once per buffer on the playback threads. */
    val sound: StateFlow<EqSound> = _sound.asStateFlow()

    /** What the file holds as far as this knows: the last save written, this one's or another's. */
    @Volatile
    private var stored: EqProfiles? = null

    /** This one's save in its turn, told apart from another's (a restore) when the store says it was written. */
    @Volatile
    private var saving: EqProfiles? = null
    private val writing = Mutex()

    init {
        store.watch { saved ->
            stored = saved
            if (saved !== saving) {
                _profiles.value = saved
                publish()
            }
        }
        scope.launch {
            val loaded = store.load()
            // A restore saved while this read wins.
            if (_profiles.compareAndSet(null, loaded)) stored = loaded
            publish()
            _profiles.collectLatest { profiles ->
                if (profiles == null || profiles === stored) return@collectLatest
                delay(SAVE_DELAY_MS)
                write(profiles)
            }
        }
    }

    /** Writes the profiles playing if a failed write left them unsaved: when the player stops. */
    fun flush() {
        scope.launch { _profiles.value?.let { write(it) } }
    }

    /** Writes [profiles] if they are unsaved, and only while they are still the ones playing when the store's turn comes. */
    private suspend fun write(profiles: EqProfiles) {
        writing.withLock {
            if (profiles === stored) return
            saving = profiles
            try {
                store.save(profiles) { _profiles.value === profiles }
            } finally {
                saving = null
            }
        }
    }

    /** [edit] applied to the profiles, with the output playing; nothing before the profiles are read. Heard at once. */
    fun change(edit: (EqProfiles, OutputKey) -> EqProfiles) {
        _profiles.update { profiles -> profiles?.let { edit(it, _output.value) } }
        publish()
    }

    /** Music plays on [output] now, so its profile plays; false when it already did. */
    fun switchTo(output: OutputKey): Boolean {
        if (_output.value == output) return false
        _output.value = output
        publish()
        return true
    }

    @Synchronized
    private fun publish() {
        _sound.value = _profiles.value?.profile(_output.value)?.sound ?: EqSound()
    }

    companion object {
        /** How long after the last change the profiles are written. */
        const val SAVE_DELAY_MS: Long = 300
        private var made: LiveEq? = null

        /** The app's: [EqProfilesStore.of]'s profiles, read and written on the IO threads. */
        @Synchronized
        fun of(context: Context): LiveEq = made ?: LiveEq(EqProfilesStore.of(context), CoroutineScope(SupervisorJob() + Dispatchers.IO + logFailures)).also { made = it }
    }
}
