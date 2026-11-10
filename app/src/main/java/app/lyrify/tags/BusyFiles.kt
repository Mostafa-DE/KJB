package app.lyrify.tags

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import app.lyrify.lyrics.AiPaths
import app.lyrify.media.NowPlayingListener
import app.lyrify.media.SessionTrack
import app.lyrify.media.TrackResolver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Whether a song may be written now. */
enum class Busy {
    FREE,

    /** A player, KJB's tone preview or the AI has it: left unchanged, tried once more at the end. */
    IN_USE,

    /** The media sessions can't be listed (no notification access): nothing at all is written. */
    UNKNOWN,
}

/**
 * The busy check the tag writer asks before it touches a song (the lyrics pipeline's per-path lock,
 * [app.lyrify.data.PathLocks], is the writer's own second check). Blocking: may query MediaStore.
 */
interface BusyFiles {
    fun check(path: String): Busy
}

/**
 * Songs KJB itself has open, by path: the tone preview, and KJB's own player's current song (milestone P1). One per
 * process ([TagsGraph.held]); a song held twice stays held until both let go.
 */
class HeldPaths {
    private val counts = MutableStateFlow<Map<String, Int>>(emptyMap())

    fun hold(path: String) = counts.update { it + (path to (it[path] ?: 0) + 1) }

    fun release(path: String) = counts.update { held ->
        when (val count = held[path]) {
            null -> held
            1 -> held - path
            else -> held + (path to count - 1)
        }
    }

    operator fun contains(path: String): Boolean = path in counts.value
}

/**
 * [BusyFiles] on the phone: a song is in use while it is the current item of any media session, playing or paused
 * ([sessions], null when the sessions can't be listed), while KJB holds it ([held]), or while the AI works on it ([ai]).
 */
class PhoneBusyFiles(private val sessions: () -> Set<String>?, private val held: HeldPaths, private val ai: AiPaths) : BusyFiles {
    override fun check(path: String): Busy {
        val playing = sessions() ?: return Busy.UNKNOWN
        return if (path in playing || path in held || path in ai) Busy.IN_USE else Busy.FREE
    }
}

/**
 * The files behind the current item of every media session on the phone, KJB's own included, playing or paused: each
 * file [TrackResolver] says the item may be. Null when the sessions can't be listed: that needs notification access,
 * and without it nothing is written. An item that names no file on the phone (a stream) adds none. Blocking.
 */
fun sessionFiles(context: Context, resolver: TrackResolver): Set<String>? {
    val manager = context.getSystemService(MediaSessionManager::class.java) ?: return null
    val controllers = try {
        manager.getActiveSessions(ComponentName(context, NowPlayingListener::class.java))
    } catch (_: SecurityException) {
        return null
    }
    return controllers.flatMapTo(mutableSetOf()) { controller ->
        val metadata = controller.metadata ?: return@flatMapTo emptyList()
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() } ?: return@flatMapTo emptyList()
        val track = SessionTrack(
            title = title,
            artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST),
            album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM),
            durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION).takeIf { it > 0 },
            mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
            mediaUri = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_URI),
        )
        resolver.resolve(track).map { it.path }
    }
}
