package app.lyrify.similar

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.lyrify.BuildConfig
import app.lyrify.LOG_TAG
import app.lyrify.Lyrify
import app.lyrify.library.LibraryGraph
import app.lyrify.library.LibraryState
import app.lyrify.stats.StatsGraph
import app.lyrify.stats.listeningOf
import app.lyrify.tags.TagsGraph
import app.lyrify.tags.lookup.MusicBrainz
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/** Similar artists' one cache per process, `similar-artists.json`: the lookups write it, suggestions read it. */
object SimilarGraph {
    private var cache: SimilarCache? = null

    @Synchronized
    fun cache(context: Context): SimilarCache =
        cache ?: SimilarCache(SimilarStore(File(context.applicationContext.filesDir, "similar-artists.json")) { Log.w(LOG_TAG, it) }).also { cache = it }
}

/**
 * Similar artists' background run, looked at every [RUN_EVERY_HOURS] hours while the phone is online and the battery is
 * not low, the first [FIRST_RUN_HOURS] after it is scheduled: with "Find similar artists online" on, the artists the user
 * played, the most played first, are looked up ([SimilarLookups]) through Covers & tags' one MusicBrainz client, so
 * MusicBrainz's gate holds app-wide. Nothing waits for it: suggestions read only the cache.
 */
class SimilarWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext
        if (!Lyrify.settings(app).settings.first().library.findSimilarOnline) return Result.success()
        // The plays are joined with the library: none while it can't be read.
        if (LibraryGraph.repository(app).state.first { it !is LibraryState.Loading } !is LibraryState.Ready) return Result.success()
        // All time, the most played first.
        val played = listeningOf(StatsGraph.log(app).totals(), (LibraryGraph.repository(app).state.value as? LibraryState.Ready)?.library).artists
        val written = withContext(Dispatchers.IO) { SimilarRules.writtenByPath(TagsGraph.undo(app).memory().entries) }
        val client = TagsGraph.client(app)
        SimilarLookups(SimilarGraph.cache(app), MusicBrainz(client), client, BuildConfig.VERSION_CODE, log = { Log.i(LOG_TAG, it) })
            .run(played.map { top -> PlayedArtist(top.item.name, top.item.songs.firstNotNullOfOrNull { written[it.path] }) })
        return Result.success()
    }

    companion object {
        private const val WORK: String = "similar-artists"
        private const val RUN_EVERY_HOURS: Long = 12
        private const val FIRST_RUN_HOURS: Long = 1

        /**
         * Follows "Find similar artists online" for the life of the process, from KJB's start: on, the run is scheduled
         * (KEEP leaves a schedule already there as it is, its timing too, so a start writes nothing to WorkManager's
         * database and asks JobScheduler for nothing; PW1); off, it is cancelled, a run going on included. A release that
         * changes the request must schedule it once with UPDATE.
         */
        fun follow(context: Context, scope: CoroutineScope) {
            val app = context.applicationContext
            scope.launch {
                Lyrify.settings(app).settings.map { it.library.findSimilarOnline }.distinctUntilChanged().collect { on ->
                    val work = WorkManager.getInstance(app)
                    if (on) {
                        val request = PeriodicWorkRequestBuilder<SimilarWorker>(RUN_EVERY_HOURS, TimeUnit.HOURS)
                            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build())
                            .setInitialDelay(FIRST_RUN_HOURS, TimeUnit.HOURS)
                            .build()
                        work.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
                    } else {
                        work.cancelUniqueWork(WORK)
                    }
                }
            }
        }
    }
}
