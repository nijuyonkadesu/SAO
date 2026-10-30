package one.njk.sao.floating

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import one.njk.sao.source.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException

class DecodeInputSelectionDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext
    private fun fixture(asset: String) = File.createTempFile("decode-input-", ".bin", app.cacheDir).apply {
        instrumentation.context.assets.open(asset).use { from -> outputStream().use { from.copyTo(it) } }
    }
    private class Source(val original: File, val playback: File?) : MediaSource {
        override val identity = flowOf("fixture")
        val requests = mutableListOf<Rendition>()
        var held = 0
        override suspend fun owns(item: MediaItem) = true
        override suspend fun page(query: MediaQuery, cursor: String?) = MediaPage(emptyList())
        override suspend fun <T> withPreview(item: MediaItem, block: suspend (Preview) -> T): T = error("Unused")
        override suspend fun <T> withRendition(item: MediaItem, kind: Rendition, block: suspend (MediaHandle) -> T): T {
            requests += kind
            val file = if (kind == Rendition.ORIGINAL) original else playback ?: throw IOException("No compatible rendition")
            held++
            try { return block(MediaHandle.Local(file)) } finally { held-- }
        }
    }
    private fun item() = MediaItem("opaque:ID", "fixture", "", "video/mp4", emptyList(), emptyList())
    @Test fun compatibleSourceRenditionPrecedesSoftwareAndRetainsBothLeases(): Unit = runBlocking {
        val original = fixture("odd-mpeg4.mp4"); val playback = fixture("preview-video.mp4")
        val source = Source(original, playback)
        try {
            var calls = 0
            MediaDecodeInputs.withInput(source, item(), app.cacheDir) { file, compatible ->
                calls++; assertTrue(compatible); assertEquals(playback, file); assertEquals(2, source.held)
            }
            assertEquals(1, calls); assertEquals(0, source.held)
            assertEquals(listOf(Rendition.ORIGINAL, Rendition.PLAYBACK), source.requests)
        } finally { original.delete(); playback.delete() }
    }
    @Test fun unavailableRenditionUsesTheOriginalForSoftwareWithoutARequestLoop(): Unit = runBlocking {
        val original = fixture("odd-mpeg4.mp4"); val source = Source(original, null)
        try {
            var calls = 0
            MediaDecodeInputs.withInput(source, item(), app.cacheDir) { file, compatible ->
                calls++; assertFalse(compatible); assertEquals(original, file); assertEquals(1, source.held)
            }
            assertEquals(1, calls); assertEquals(0, source.held)
            assertEquals(listOf(Rendition.ORIGINAL, Rendition.PLAYBACK), source.requests)
        } finally { original.delete() }
    }
    @Suppress("DEPRECATION") // Independent GIF decoder verifies final dimensions/duration.
    @Test fun upscaledRenditionIsRejectedAndGifUsesOriginalSoftwareDecode(): Unit = runBlocking(Dispatchers.IO) {
        val original = fixture("odd-mpeg4.mp4"); val playback = fixture("gif/upscaled-playback.mp4")
        val source = Source(original, playback)
        val media = item().copy(id = java.util.UUID.randomUUID().toString().replace('-', '_'))
        try {
            assertFalse(MediaDecodeInputs.platformSupports(original))
            val gif = withTimeout(30_000) { MediaExports(app, source).asGif(media) { cost ->
                cost?.let { assertEquals(357, it.width); assertEquals(372, it.height) }
            } }
            try {
                val decoded = requireNotNull(android.graphics.Movie.decodeFile(gif.file.path))
                assertEquals(357, decoded.width()); assertEquals(372, decoded.height())
                assertTrue(decoded.duration() > 0)
                assertEquals(listOf(Rendition.ORIGINAL, Rendition.PLAYBACK), source.requests)
                assertEquals(0, source.held)
            } finally { gif.file.delete() }
        } finally { original.delete(); playback.delete() }
    }
    @Test fun rotationCorrectedEdgeCannotGrowEvenIfTotalPixelCountIsSmaller(): Unit = runBlocking {
        val original = fixture("odd-mpeg4.mp4"); val playback = fixture("gif/rotated-playback.mp4")
        val source = Source(original, playback)
        try {
            // Coded 128x360 fits 357x372; displayed 360x128 exceeds the original width.
            MediaDecodeInputs.withInput(source, item(), app.cacheDir) { file, compatible ->
                assertFalse(compatible); assertEquals(original, file)
            }
            assertEquals(listOf(Rendition.ORIGINAL, Rendition.PLAYBACK), source.requests)
            assertEquals(0, source.held)
        } finally { original.delete(); playback.delete() }
    }
    @Test fun unknownOriginalGeometryDoesNotGuessThatPlaybackIsSafe(): Unit = runBlocking {
        val original = File.createTempFile("unknown-input-", ".bin", app.cacheDir).apply { writeText("Not a video") }
        val playback = fixture("preview-video.mp4"); val source = Source(original, playback)
        try {
            MediaDecodeInputs.withInput(source, item(), app.cacheDir) { file, compatible ->
                assertFalse(compatible); assertEquals(original, file)
            }
            assertEquals(listOf(Rendition.ORIGINAL), source.requests)
            assertEquals(0, source.held)
        } finally { original.delete(); playback.delete() }
    }
    @Test fun aConversionFailureIsNotRetriedAgainstAnotherInput(): Unit = runBlocking {
        val original = fixture("odd-mpeg4.mp4"); val playback = fixture("preview-video.mp4")
        val source = Source(original, playback)
        try {
            var calls = 0
            try {
                MediaDecodeInputs.withInput(source, item(), app.cacheDir) { _, _ -> calls++; throw IOException("Conversion failed") }
                fail("The consumer failure was swallowed")
            } catch (_: IOException) { }
            assertEquals(1, calls); assertEquals(0, source.held)
        } finally { original.delete(); playback.delete() }
    }
}
