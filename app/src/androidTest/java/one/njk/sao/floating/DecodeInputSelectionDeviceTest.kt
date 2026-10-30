package one.njk.sao.floating

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
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
