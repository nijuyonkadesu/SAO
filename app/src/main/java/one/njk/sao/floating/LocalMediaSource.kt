package one.njk.sao.floating

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.flowOf
import org.json.JSONArray
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalMediaSource @Inject constructor(@ApplicationContext private val context: Context,
    private val dispatchers: FloatingDispatchers = FloatingDispatchers()) : MediaSource {
    override val randomBrowse = flowOf(true)
    private val catalog by lazy {
        val json = context.assets.open("floating/catalog.json").bufferedReader().use { JSONArray(it.readText()) }
        List(json.length()) { index ->
            val item = json.getJSONObject(index)
            fun strings(key: String): List<String> = item.getJSONArray(key).let { values ->
                List(values.length()) { values.getString(it) }
            }
            MediaItem(item.getString("id"), item.getString("title"), item.getString("asset"),
                item.getString("mimeType"), strings("tags"), strings("emojis"), item.optBoolean("animated"))
        }.also { items ->
            require(items.map { it.id }.distinct().size == items.size) { "Duplicate media IDs" }
            require(items.all { it.asset.startsWith("floating/") && ".." !in it.asset })
        }
    }

    override suspend fun page(query: MediaQuery, cursor: String?): MediaPage {
        if (cursor != null) return MediaPage(emptyList())
        val items = all()
        return MediaPage(withContext(dispatchers.cpu) {
            val matches = MediaSearch.search(items, if (query.randomRequest == null) query else query.copy(text = ""))
            if (query.randomRequest == null) matches else matches.shuffled()
        })
    }
    suspend fun all(): List<MediaItem> = withContext(dispatchers.io) { catalog }
    override suspend fun filters() = all().flatMap { it.emojis.take(1) }.distinct()
    override suspend fun preview(item: MediaItem): Any = item.preview
    override suspend fun original(item: MediaItem): InputStream = withContext(dispatchers.io) { openOriginal(item) }
    override suspend fun raster(item: MediaItem): InputStream = original(item)
    fun openOriginal(item: MediaItem): InputStream = context.assets.open(item.asset)
}
