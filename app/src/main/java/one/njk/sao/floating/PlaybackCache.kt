package one.njk.sao.floating

import android.content.Context
import androidx.media3.common.C
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.*
import androidx.media3.datasource.cache.*
import kotlinx.coroutines.*
import one.njk.sao.source.MediaPlayback
import java.io.File

/** One host-owned, bounded cache of bytes actually read by playback; no idle prefetch or worker. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class PlaybackCache private constructor(context: Context) {
    private val cache = SimpleCache(File(context.cacheDir, "playback-media"),
        LeastRecentlyUsedCacheEvictor(64L * 1024 * 1024), StandaloneDatabaseProvider(context))
    fun factory(context: Context, media: MediaPlayback, owner: Job): DataSource.Factory {
        val upstream = DataSource.Factory {
            if (media.uri.scheme == "content") ProviderMediaDataSource(context, owner)
            else DefaultDataSource.Factory(context).createDataSource()
        }
        val cached = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(upstream)
            .setCacheWriteDataSinkFactory(CacheDataSink.Factory().setCache(cache).setFragmentSize(2L * 1024 * 1024))
        return ResolvingDataSource.Factory(cached) { input -> input.buildUpon()
            .setKey(media.cacheKey ?: input.uri.toString())
            .setFlags(input.flags or DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION).build() }
    }
    companion object {
        private val stores = mutableMapOf<String, PlaybackCache>()
        suspend fun factory(context: Context, media: MediaPlayback, owner: Job): DataSource.Factory = withContext(Dispatchers.IO) {
            val app = context.applicationContext
            val store = synchronized(stores) { stores.getOrPut(app.cacheDir.absolutePath) { PlaybackCache(app) } }
            store.factory(app, media, owner)
        }
        suspend fun remove(context: Context, identity: String) = withContext(Dispatchers.IO) {
            val prefix = one.njk.sao.source.digest(identity).take(32) + ":"
            val app=context.applicationContext
            val cache=synchronized(stores) { stores.getOrPut(app.cacheDir.absolutePath) { PlaybackCache(app) } }.cache
            cache.keys.filter { it.startsWith(prefix) }.forEach(cache::removeResource)
        }
        suspend fun <T> withFile(context: Context, media: MediaPlayback, maxBytes: Long, block: suspend (File) -> T): T {
            val file = withContext(Dispatchers.IO) { File.createTempFile("playback-", ".pending", context.cacheDir) }
            try {
                withContext(Dispatchers.IO) {
                    val data = factory(context, media, currentCoroutineContext().job).createDataSource()
                    try {
                        data.open(DataSpec.Builder().setUri(media.uri).build())
                        file.outputStream().use { output ->
                            val buffer = ByteArray(32 * 1024); var total = 0L
                            while (true) {
                                ensureActive()
                                val n = data.read(buffer, 0, buffer.size)
                                if (n == C.RESULT_END_OF_INPUT) break
                                total += n
                                if (total > maxBytes) throw one.njk.sao.source.ExtensionFailure("MEDIA_TOO_LARGE")
                                output.write(buffer, 0, n)
                            }
                            if (total == 0L) throw one.njk.sao.source.ExtensionFailure("INVALID_RESPONSE")
                        }
                    } finally { data.close() }
                }
                return block(file)
            } finally { withContext(NonCancellable + Dispatchers.IO) { file.delete() } }
        }
    }
}
