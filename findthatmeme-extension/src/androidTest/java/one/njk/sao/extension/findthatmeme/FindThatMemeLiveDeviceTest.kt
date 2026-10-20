package one.njk.sao.extension.findthatmeme

import android.content.ComponentName
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import one.njk.sao.source.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.util.UUID

/** Explicit opt-in. Only public catalog requests; no saved profiles or private credentials. */
class FindThatMemeLiveDeviceTest {
    @Test fun publicSearchPaginationAndActualMediaWorkThroughBinderAndProvider(): Unit = runBlocking {
        assumeTrue("Enable only with publicLive=true", InstrumentationRegistry.getArguments().getString("publicLive") == "true")
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val store = FindThatMemeRuntime.get(app).profiles
        val row = store.save(UUID.randomUUID().toString(), "Public live check", FindThatMemeConfig())
        val client = ExtensionClient(app, ComponentName(app.packageName, FindThatMemeExtensionService::class.java.name))
        fun payload() = JSONObject().put("revision", row.revision).put("source", "public-live:${row.id}")
        suspend fun page(query: MediaQuery, cursor: String? = null) = SourceWire.page(client.request(row.id,
            ExtensionProtocol.SEARCH, payload().put("query", SourceWire.query(query)).put("cursor", cursor)))
        suspend fun bytes(item: MediaItem, kind: String): ByteArray {
            val uri = Uri.parse(client.request(row.id, ExtensionProtocol.MEDIA,
                payload().put("item", SourceWire.item(item)).put("kind", kind)).getString("uri"))
            return app.contentResolver.openFileDescriptor(uri, "r")!!.use { fd ->
                val data = ParcelFileDescriptor.AutoCloseInputStream(fd).readBytes(); fd.checkError(); data
            }
        }
        try {
            val query = MediaQuery("this is fine")
            val first = page(query)
            assertTrue(first.items.isNotEmpty()); assertNotNull(first.nextCursor)
            val second = page(query, first.nextCursor)
            assertTrue(second.items.isNotEmpty())
            assertTrue(first.items.map { it.remote!!.assetId }.intersect(second.items.map { it.remote!!.assetId }.toSet()).isEmpty())
            val picture = first.items.first { it.mimeType == "image/jpeg" }
            val image = bytes(picture, "original")
            assertTrue(FindThatMemeApi.matches(image, "image/jpeg"))
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(image))).let { assertTrue(it.width > 0); it.recycle() }
            val videos = page(MediaQuery("this is fine", constraints = SearchConstraints(media = SearchMedia.VIDEOS)))
            assertTrue(videos.items.isNotEmpty()); assertTrue(videos.items.all { it.isVideo })
            assertTrue(FindThatMemeApi.matches(bytes(videos.items.first(), "video"), "video/mp4"))
            val poster = bytes(videos.items.first(), "preview")
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(poster))).let { assertTrue(it.height > 0); it.recycle() }
            val captions = page(MediaQuery("i have no idea what i am doing"))
            val gif = captions.items.first { it.mimeType == "image/gif" }
            assertTrue(FindThatMemeApi.matches(bytes(gif, "original"), "image/gif"))
            assertTrue(gif.animated)
        } finally { store.delete(row.id, row.revision) }
    }
}
