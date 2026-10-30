package one.njk.sao.floating

import androidx.test.platform.app.InstrumentationRegistry
import one.njk.sao.media.NativeDecoder
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import one.njk.sao.source.FloatingDispatchers

class SoftwareMediaDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext
    private fun fixture(asset: String) = File.createTempFile("software-media-", ".bin", app.cacheDir).apply {
        instrumentation.context.assets.open(asset).use { input -> outputStream().use { input.copyTo(it) } }
    }
    @Test fun oddWidthMpeg4FallsBackWithoutChangingDimensionsTimingOrColors() {
        val input = fixture("odd-mpeg4.mp4")
        val before = input.readBytes()
        try {
            assertFalse("The fixture must reproduce the platform's two-pixel alignment limit", MediaDecodeInputs.platformSupports(input))
            var count = 0
            val timestamps = mutableListOf<Long>()
            val result = VideoFrameDecoder(app).decode(input, EmptyCoroutineContext) { index, width, height, stride, rgba, time ->
                assertEquals(count++, index)
                assertEquals(357, width); assertEquals(372, height); assertTrue(stride >= width * 4)
                assertTrue("Red source pixels must remain red", (rgba.get(0).toInt() and 255) > 220)
                assertTrue((rgba.get(1).toInt() and 255) < 30)
                assertTrue((rgba.get(2).toInt() and 255) < 30)
                assertEquals(255, rgba.get(3).toInt() and 255)
                timestamps += time
            }
            assertEquals(10, count)
            assertEquals((0 until 10).map { it * 40_000L }, timestamps)
            assertEquals(357, result.width); assertEquals(372, result.height)
            assertEquals(400_000L, result.durationUs)
            assertEquals(VideoFrameDecoder.Backend.SOFTWARE, result.backend)
            assertArrayEquals(before, input.readBytes())
        } finally { input.delete() }
    }
    @Test fun animatedPngUsesContentDetectionAndPreservesAlphaAndFrameDurations() {
        val input = fixture("software-animation.png")
        try {
            NativeDecoder.open(app, input, 1_000_000).use { assertTrue(it.hasMultipleFrames()) }
            NativeDecoder.open(app, input, 1_000_000).use { decoder ->
                assertEquals(64, decoder.info.width); assertEquals(48, decoder.info.height)
                val rgba = ByteBuffer.allocateDirect(64 * 48 * 4)
                val times = mutableListOf<Long>(); val durations = mutableListOf<Long>(); val alphas = mutableListOf<Int>()
                while (decoder.next(rgba)) {
                    times += decoder.presentationTimeUs; durations += decoder.frameDurationUs
                    alphas += rgba.get(3).toInt() and 255
                }
                assertEquals(listOf(0L, 100_000L, 300_000L), times)
                assertEquals(listOf(100_000L, 200_000L, 300_000L), durations)
                assertEquals(listOf(255, 128, 255), alphas)
            }
        } finally { input.delete() }
    }
    @Test fun softwareHdrFramesUseTheGpuToneMappingPath() {
        val input = fixture("export/hdr.webm")
        try {
            NativeDecoder.open(app, input, 1_000_000).use { assertEquals(8, it.info.bytesPerPixel) }
            var frames = 0
            val result = VideoFrameDecoder(app).decodeSoftware(input, EmptyCoroutineContext) { _, width, height, stride, rgba, _ ->
                assertEquals(128, width); assertEquals(128, height)
                assertTrue(stride >= width * 4); assertTrue(rgba.remaining() >= width * height * 4)
                frames++
            }
            assertTrue(frames > 0); assertTrue(result.durationUs > 0)
        } finally { input.delete() }
    }
    @Test fun softwareFrameBudgetRejectsUnsafeAllocation() {
        val input = fixture("odd-mpeg4.mp4")
        try {
            try { NativeDecoder.open(app, input, 100).use { }; fail("Oversized frames were accepted") }
            catch (_: java.io.IOException) { }
        } finally { input.delete() }
    }
    @Test fun softwareRotationMatchesTheExistingPlatformOrientationAndColors() {
        val input = fixture("gif/rotated.mp4")
        try {
            var checked = false
            val result = VideoFrameDecoder(app).decodeSoftware(input, EmptyCoroutineContext) { _, width, height, stride, rgba, _ ->
                if (!checked) {
                    assertEquals(64, width); assertEquals(96, height)
                    fun channel(x: Int, y: Int, offset: Int) = rgba.get(rgba.position() + y * stride + x * 4 + offset).toInt() and 255
                    assertTrue(channel(16, 24, 0) > 220)
                    assertTrue(channel(48, 24, 2) > 220)
                    assertTrue(channel(16, 72, 1) > 90)
                    checked = true
                }
            }
            assertTrue(checked); assertEquals(64, result.width); assertEquals(96, result.height)
        } finally { input.delete() }
    }

    @Test fun mp4ExportReusesSoftwareDecodingAndKeepsAudioAndOrientation(): Unit = runBlocking {
        val input = fixture("software-export.mp4")
        val output = File.createTempFile("software-export-", ".mp4", app.cacheDir).apply { delete() }
        val before = input.readBytes()
        try {
            val originalPixels = mutableListOf<Int>()
            fun sample(pixels: ByteBuffer, stride: Int, destination: MutableList<Int>) {
                for ((x, y) in listOf(32 to 24, 96 to 24, 32 to 72, 96 to 72))
                    repeat(3) { channel -> destination += pixels.get(pixels.position() + y * stride + x * 4 + channel).toInt() and 255 }
            }
            VideoFrameDecoder(app).decodeSoftware(input, EmptyCoroutineContext) { index, _, _, stride, rgba, _ ->
                if (index == 0) sample(rgba, stride, originalPixels)
            }
            val transcoder = VideoTranscoder(app, FloatingDispatchers())
            assertFalse("The 4:4:4 fixture must require software decoding", MediaDecodeInputs.platformSupports(input))
            withTimeout(30_000) { transcoder.mp4(input, output) }
            val actual = transcoder.inspect(output)
            assertEquals(128, actual.width); assertEquals(96, actual.height)
            assertEquals("audio/mp4a-latm", actual.audioMime)
            val exportedPixels = mutableListOf<Int>()
            val decoded = VideoFrameDecoder(app).decode(output, EmptyCoroutineContext) { index, _, _, stride, rgba, _ ->
                if (index == 0) sample(rgba, stride, exportedPixels)
            }
            assertTrue(decoded.durationUs in 900_000..1_100_000)
            assertEquals(originalPixels.size, exportedPixels.size)
            originalPixels.zip(exportedPixels).forEach { (expected, actual) ->
                assertTrue("Export must preserve frame orientation and colors: $expected versus $actual", kotlin.math.abs(expected - actual) < 35)
            }
            assertArrayEquals(before, input.readBytes())
        } finally { input.delete(); output.delete() }
    }

    @Test fun gifExportUsesTheSameOddWidthSoftwareFrames(): Unit = runBlocking {
        val input = fixture("odd-mpeg4.mp4")
        val output = File.createTempFile("software-animation-", ".gif", app.cacheDir)
        try {
            withTimeout(30_000) { VideoGifTranscoder(app).gif(input, output) }
            val movie = requireNotNull(android.graphics.Movie.decodeFile(output.path))
            assertEquals(357, movie.width()); assertEquals(372, movie.height())
            assertEquals(400, movie.duration())
        } finally { input.delete(); output.delete() }
    }

    @Test fun aConsumerFailureIsNotReplayedThroughTheSoftwareDecoder() {
        val input = fixture("gif/colors.mp4")
        val rejected = IllegalStateException("Synthetic consumer rejection")
        var calls = 0
        try {
            try {
                VideoFrameDecoder(app).decode(input, EmptyCoroutineContext) { _, _, _, _, _, _ -> calls++; throw rejected }
                fail("Consumer failure was swallowed")
            } catch (actual: IllegalStateException) { assertSame(rejected, actual) }
            assertEquals(1, calls)
        } finally { input.delete() }
    }
}
