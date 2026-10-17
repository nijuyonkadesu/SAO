package one.njk.sao.floating

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import com.caverock.androidsvg.SVG
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import one.njk.sao.rays.RaysClient
import one.njk.sao.rays.RaysException
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

data class RaysConfig(val packageName: String = RaysClient.PACKAGE) : SourceConfiguration {
    init { require(packageName in RaysClient.packages) }
    override val kind = "rays"
    override val identity get() = digest(packageName)
}

class RaysConfigurationCodec @Inject constructor() : SourceConfigurationCodec {
    override fun encode(configuration: SourceConfiguration) = JSONObject()
        .put("package", (configuration as RaysConfig).packageName)
    override fun decode(json: JSONObject) = RaysConfig(json.getString("package"))
}

class RaysSourceFactory @Inject constructor(@ApplicationContext private val context: Context,
    private val dispatchers: FloatingDispatchers) : MediaSourceFactory {
    override fun create(profile: SourceProfile) = RaysMediaSource(context,
        profile.configuration as RaysConfig, profile.identity, dispatchers)
}

/** Read-through library: no query cache, private database access, or idle IPC connection. */
class RaysMediaSource(private val context: Context, private val config: RaysConfig,
    private val sourceId: String, private val dispatchers: FloatingDispatchers) : ManagedMediaSource {
    private val client = RaysClient(context, config.packageName, dispatchers.io)
    @Volatile private var closed = false
    override suspend fun page(query: MediaQuery, cursor: String?): MediaPage = withContext(dispatchers.io) {
        check(!closed)
        val keyword = listOfNotNull(query.text.takeIf { it.isNotBlank() }, query.filter).joinToString(" ")
        val offset = cursor?.toIntOrNull() ?: if (cursor == null) 0 else error("Invalid Rays cursor")
        val page = try { client.search(keyword, offset) }
            catch (error: RaysException) { throw SourceReadException(error.problem.messageRes(), error) }
        val items = page.items.map { sticker ->
            coroutineContext.ensureActive()
            val animated = when (sticker.mimeType) {
                "image/gif", "image/apng" -> true
                "image/webp" -> context.contentResolver.openInputStream(sticker.uri)?.use {
                    val header = ByteArray(21)
                    val count = it.read(header)
                    count == header.size && String(header, 12, 4, Charsets.US_ASCII) == "VP8X" &&
                        header[20].toInt() and 2 != 0
                } ?: false
                else -> false
            }
            MediaItem("rays_${digest(sourceId + sticker.id + sticker.revision).take(48)}",
                sticker.title.ifBlank { sticker.tags.take(3).joinToString(" · ") }, sticker.uri.toString(),
                sticker.mimeType, sticker.tags, emptyList(), animated,
                RemoteMedia(sourceId, sticker.id, false))
        }
        check(!closed)
        MediaPage(items, page.nextIndex?.toString())
    }
    override suspend fun filters() = emptyList<String>()
    private fun uri(item: MediaItem): Uri {
        check(!closed && item.remote?.sourceId == sourceId)
        return item.asset.toUri().also {
            require(it.scheme == "content" && it.authority == "${config.packageName}.fileprovider")
        }
    }
    override suspend fun preview(item: MediaItem): Any = if (item.mimeType == "image/svg+xml")
        withContext(dispatchers.io) { raster(item).use { java.nio.ByteBuffer.wrap(it.readBytes()) } }
        else uri(item)
    @SuppressLint("Recycle") // MediaSource transfers stream ownership to the caller.
    override suspend fun original(item: MediaItem): InputStream = withContext(dispatchers.io) {
        context.contentResolver.openInputStream(uri(item)) ?: error("Rays image is unavailable")
    }
    override suspend fun raster(item: MediaItem): InputStream {
        if (item.mimeType != "image/svg+xml") return original(item)
        return withContext(dispatchers.conversion) {
            val svg = original(item).use { SVG.getFromInputStream(it) }
            val picture = svg.renderToPicture(512, 512)
            val bitmap = createBitmap(512, 512, Bitmap.Config.ARGB_8888)
            try {
                picture.draw(Canvas(bitmap))
                ByteArrayInputStream(ByteArrayOutputStream().use {
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)); it.toByteArray()
                })
            } finally { bitmap.recycle() }
        }
    }
    override fun close() { closed = true }
}
