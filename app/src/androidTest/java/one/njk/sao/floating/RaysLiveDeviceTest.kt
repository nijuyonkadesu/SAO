package one.njk.sao.floating

import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import one.njk.sao.rays.RaysClient
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID

/** Opt-in cross-UID test, using only the synthetic library prepared by Rays' API tests. */
class RaysLiveDeviceTest {
    @Test fun installedRaysSearchPagingOriginalAndAnimatedExport(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("raysFixture") == "true")
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val config = RaysConfig(RaysClient.DEBUG_PACKAGE)
        val profile = SourceProfile(UUID.randomUUID().toString(), "Rays fixtures", config)
        val source = RaysMediaSource(app, config, profile.identity, FloatingDispatchers())
        try {
            val first = source.page(MediaQuery("api-fixture"))
            assertEquals(60, first.items.size)
            assertNotNull(first.nextCursor)
            val withheld = java.util.UUID.nameUUIDFromBytes("rays-api-fixture-0".toByteArray()).toString()
            val withheldUri = android.net.Uri.parse("content://${config.packageName}.fileprovider/files/$withheld")
            try {
                app.contentResolver.openInputStream(withheldUri)?.close()
                fail("An unreturned matching URI received a grant")
            } catch (_: SecurityException) { /* Expected: only page one was granted. */ }
            val second = source.page(MediaQuery("api-fixture"), first.nextCursor)
            assertEquals(5, second.items.size)
            assertNull(second.nextCursor)
            assertEquals(65, (first.items + second.items).map { it.remote!!.assetId }.distinct().size)
            assertTrue(app.contentResolver.openInputStream(withheldUri)!!.use { it.read() >= 0 })
            val static = source.page(MediaQuery("api-fixture 062")).items.single { it.title == "api-fixture 062" }
            assertEquals("image/webp", static.mimeType)
            assertFalse(static.animated)
            assertEquals("image/webp", MediaExports(app, source).prepare(static, Rendition.STICKER_WEBP).mimeType)
            val webp = source.page(MediaQuery("api-fixture 063")).items.single { it.title == "api-fixture 063" }
            assertTrue(webp.animated)
            assertEquals("image/webp", webp.mimeType)
            val webpExport = MediaExports(app, source).prepare(webp, Rendition.MEDIA)
            assertArrayEquals(source.original(webp).use { it.readBytes() }, webpExport.file.readBytes())
            val svg = source.page(MediaQuery("api-fixture 061")).items.single { it.title == "api-fixture 061" }
            assertEquals("image/svg+xml", svg.mimeType)
            assertTrue(source.preview(svg) is java.nio.ByteBuffer)
            assertTrue(source.raster(svg).use { android.graphics.BitmapFactory.decodeStream(it) != null })
            assertEquals("image/png", MediaExports(app, source).prepare(svg, Rendition.STICKER_PNG).mimeType)
            val video = source.page(MediaQuery("api-fixture 060")).items.single { it.title == "api-fixture 060" }
            assertEquals("video/mp4", video.mimeType)
            assertTrue(video.isVideo)
            assertTrue(source.preview(video) is android.graphics.Bitmap)
            assertTrue(source.video(video)!!.isFile)
            val movie = source.page(MediaQuery("api-fixture 059")).items.single { it.title == "api-fixture 059" }
            assertEquals("video/webm", movie.mimeType)
            val originalMovie = source.original(movie).use { it.readBytes() }
            val ticks = java.util.concurrent.atomic.AtomicInteger()
            val heartbeat = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                while (isActive) { ticks.incrementAndGet(); kotlinx.coroutines.delay(16) }
            }
            val converted = try { kotlinx.coroutines.withTimeout(30_000) { MediaExports(app, source).mp4(movie) } }
                finally { heartbeat.cancel() }
            assertTrue("Main looper should remain responsive during export", ticks.get() > 0)
            assertEquals("video/mp4", converted.mimeType)
            val extractor = android.media.MediaExtractor()
            try {
                extractor.setDataSource(converted.file.path)
                assertTrue((0 until extractor.trackCount).any {
                    extractor.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME) == "video/avc"
                })
            } finally { extractor.release() }
            assertArrayEquals(originalMovie, source.original(movie).use { it.readBytes() })
            val emoji = source.page(MediaQuery("🧪"))
            assertEquals(1, emoji.items.size)
            val animated = emoji.items.single()
            assertTrue(animated.animated)
            assertEquals("image/gif", animated.mimeType)
            assertTrue(source.preview(animated) is android.net.Uri)
            val original = source.original(animated).use { it.readBytes() }
            assertEquals("GIF", original.take(3).toByteArray().toString(Charsets.US_ASCII))
            val export = MediaExports(app, source).prepare(animated, Rendition.MEDIA)
            assertEquals("image/gif", export.mimeType)
            assertArrayEquals(original, export.file.readBytes())
        } finally { source.close() }
    }
    @Test fun copiedLocalImagesExportAsStaticStickers(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("realRaysFixture") == "true")
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val config = RaysConfig(RaysClient.DEBUG_PACKAGE)
        val profile = SourceProfile(UUID.randomUUID().toString(), "Local test copies", config)
        val source = RaysMediaSource(app, config, profile.identity, FloatingDispatchers())
        try {
            val page = source.page(MediaQuery("real-fixture"))
            val images = page.items.filterNot { it.isVideo }
            assertEquals(3, images.size)
            images.forEach { item ->
                assertEquals("image/webp", item.mimeType)
                assertFalse(item.animated)
                val export = MediaExports(app, source).prepare(item, Rendition.STICKER_PNG)
                val bitmap = android.graphics.BitmapFactory.decodeFile(export.file.path)
                assertNotNull(bitmap)
                assertEquals(512, bitmap.width); assertEquals(512, bitmap.height)
                bitmap.recycle()
            }
            val video = page.items.single { it.isVideo }
            val original = source.original(video).use { it.readBytes() }
            val converted = kotlinx.coroutines.withTimeout(30_000) { MediaExports(app, source).mp4(video) }
            assertEquals("video/mp4", converted.mimeType)
            assertTrue(converted.file.length() > 0)
            assertArrayEquals(original, source.original(video).use { it.readBytes() })
        } finally { source.close() }
    }
}
