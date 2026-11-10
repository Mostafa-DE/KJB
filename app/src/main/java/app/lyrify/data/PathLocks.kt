package app.lyrify.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

/**
 * The per-path lock every part that changes a song or its `.lrc` shares (one per process, [app.lyrify.Lyrify.locks]):
 * the lyrics pipeline, the tag writer, and the journal recovery, which holds each song it settles. A path is the song's
 * absolute path. Never blocks a thread: waiting suspends.
 *
 * A song can also be [pin]ned for the rest of the process: one whose tag write could not be settled (its journal
 * intent stays open). [tryHold] refuses it and [contains] says it is held, so tag writes, the AI and the tone preview
 * stay off it; [exclusive] and [hold] do not wait for a pin, so the lyrics pipeline, which never touches a song's bytes,
 * is not kept waiting for good.
 */
class PathLocks {
    private val held = MutableStateFlow<Set<String>>(emptySet())
    private val pins = MutableStateFlow<Set<String>>(emptySet())

    /** Runs [block] once nobody holds [path], holding it meanwhile. */
    suspend fun <T> exclusive(path: String, block: suspend () -> T): T {
        hold(path)
        try {
            return block()
        } finally {
            release(path)
        }
    }

    /** Takes [path] if nobody holds it, without waiting: false when it is held. [release] gives it back. */
    fun tryHold(path: String): Boolean {
        if (pinned(path)) return false
        while (true) {
            val now = held.value
            if (path in now) return false
            if (held.compareAndSet(now, now + path)) return true
        }
    }

    /** Takes [path] once nobody holds it. [release] gives it back. */
    suspend fun hold(path: String) {
        while (true) {
            val free = held.first { path !in it }
            if (held.compareAndSet(free, free + path)) return
        }
    }

    fun release(path: String) = held.update { it - path }

    /** Keeps [path] held for the rest of the process (see the class). */
    fun pin(path: String) = pins.update { it + path }

    fun pinned(path: String): Boolean = path in pins.value

    operator fun contains(path: String): Boolean = path in held.value || pinned(path)
}
