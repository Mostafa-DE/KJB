package app.lyrify.library.search

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** How many recent searches Search keeps. */
const val MAX_RECENT_SEARCHES: Int = 8

/**
 * [recent] after a search for [query]: it first, as typed but trimmed, without an earlier one of the same words (case
 * and accents aside), at most [MAX_RECENT_SEARCHES]. Blank words change nothing.
 */
fun withSearch(recent: List<String>, query: String): List<String> {
    val words = query.trim().takeIf(String::isNotEmpty) ?: return recent
    val same = fold(words)
    return (listOf(words) + recent.filterNot { fold(it) == same }).take(MAX_RECENT_SEARCHES)
}

/** [recent] without [query]: its "×". */
fun withoutSearch(recent: List<String>, query: String): List<String> = recent.filterNot { it == query }

/**
 * The stored form of the recent searches: JSON with a version, read leniently. Keys this version does not know are
 * ignored, an entry that is not text, or is blank, or repeats an earlier one is left out, and only the newest
 * [MAX_RECENT_SEARCHES] are kept. Only text that is not a JSON object at all is unreadable.
 */
object RecentSearchesJson {
    const val VERSION: Int = 1

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(searches: List<String>): String = json.encodeToString(
        JsonObject.serializer(),
        JsonObject(mapOf(VERSION_KEY to JsonPrimitive(VERSION), SEARCHES_KEY to JsonArray(searches.map(::JsonPrimitive)))),
    )

    /** The searches in [text], the newest first; null when it is not a JSON object. */
    fun decode(text: String): List<String>? {
        val root = try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (_: SerializationException) {
            null
        } ?: return null
        val stored = (root[SEARCHES_KEY] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content }
        return stored.asReversed().fold(emptyList<String>(), ::withSearch)
    }

    private const val VERSION_KEY: String = "version"
    private const val SEARCHES_KEY: String = "searches"
}

/**
 * The recent searches in one app-private file (never backed up: allowBackup is off, and KJB's own backup leaves them
 * out), written whole or not at all (written aside, then moved over). One that cannot be read is logged by its size
 * only, and the list starts over. Blocking.
 */
class RecentSearchesStore(private val file: File, private val log: (String) -> Unit = {}) {
    fun load(): List<String> {
        if (!file.isFile) return emptyList()
        val text = try {
            file.readText()
        } catch (e: IOException) {
            log("recent searches: not readable (${e.javaClass.simpleName}), starting over")
            return emptyList()
        }
        return RecentSearchesJson.decode(text) ?: emptyList<String>().also { log("recent searches: stored file unreadable (${text.length} chars), starting over") }
    }

    /** False when they could not be written; the file before stays then. */
    fun save(searches: List<String>): Boolean {
        val aside = File(file.path + TMP)
        return try {
            file.parentFile?.mkdirs()
            aside.writeText(RecentSearchesJson.encode(searches))
            Files.move(aside.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            true
        } catch (e: IOException) {
            aside.delete()
            log("recent searches: not written (${e.javaClass.simpleName})")
            false
        }
    }

    private companion object {
        const val TMP: String = ".tmp"
    }
}

/**
 * Search's recent searches, read once from [store] on [io], then changed in the order asked and written after each
 * change. Built once per process by [SearchGraph].
 */
class RecentSearches(private val store: RecentSearchesStore, scope: CoroutineScope, private val io: CoroutineDispatcher = Dispatchers.IO) {
    private val _state = MutableStateFlow<List<String>?>(null)

    /** The newest first; null until the stored ones are read. */
    val state: StateFlow<List<String>?> = _state.asStateFlow()

    /** A change, and for one that counts only once stored (a restore's), where to say whether it was. */
    private class Change(val apply: (List<String>) -> List<String>, val stored: CompletableDeferred<Boolean>? = null)

    private val changes = Channel<Change>(Channel.UNLIMITED)

    init {
        scope.launch {
            var searches = withContext(io) { store.load() }
            _state.value = searches
            for (change in changes) {
                val changed = change.apply(searches)
                if (changed == searches) {
                    change.stored?.complete(true)
                    continue
                }
                if (change.stored == null) {
                    searches = changed
                    _state.value = changed
                    withContext(io) { store.save(changed) }
                } else {
                    val saved = withContext(io) { store.save(changed) }
                    if (saved) {
                        searches = changed
                        _state.value = changed
                    }
                    change.stored.complete(saved)
                }
            }
        }
    }

    /** The search for [query] goes first: the keyboard's search key, or a result tapped. */
    fun add(query: String) {
        changes.trySend(Change({ withSearch(it, query) }))
    }

    fun remove(query: String) {
        changes.trySend(Change({ withoutSearch(it, query) }))
    }

    /**
     * A backup's [searches] (the newest first) added to the recent ones not there yet, in one write: they count only
     * once stored, and false leaves the recent searches as they were.
     */
    suspend fun addAll(searches: List<String>): Boolean {
        val stored = CompletableDeferred<Boolean>()
        changes.trySend(Change({ now -> searches.filterNot { it in now }.asReversed().fold(now, ::withSearch) }, stored))
        return stored.await()
    }
}
