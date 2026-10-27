package one.njk.sao.floating.nativebridge

import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class InstagramVideoDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext

    private fun fixture(name: String): File {
        val file = File(app.filesDir, "floating_exports/ig-test-$name").apply { parentFile!!.mkdirs() }
        instrumentation.context.assets.open(name).use { input -> file.outputStream().use { input.copyTo(it) } }
        return file
    }
    @Test fun preparesRealMp4OffMainAndRetainsOnlySubmittedFiles() = runBlocking {
        val original = fixture("preview-video.mp4")
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", original)
        try {
            val video = withContext(Dispatchers.IO) { InstagramVideoFile.prepare(app, uri) }
            assertTrue(video.width > 0 && video.height > 0 && video.durationMs > 0)
            assertArrayEquals(original.readBytes(), video.file.readBytes())
            video.close(); assertFalse(video.file.exists())
            val submitted = withContext(Dispatchers.IO) { InstagramVideoFile.prepare(app, uri) }
            try {
                submitted.submitted(); submitted.close(); assertTrue(submitted.file.exists())
            } finally { submitted.file.delete() }
            instrumentation.runOnMainSync {
                try { InstagramVideoFile.prepare(app, uri); fail("Preparation must reject Main") }
                catch (_: IllegalStateException) { }
            }
        } finally { original.delete() }
    }
    @Test fun rejectsInvalidAndUnsupportedVideoWithoutLeavingPartialFiles() = runBlocking {
        val invalid = File(app.filesDir, "floating_exports/ig-test-invalid.mp4").apply { parentFile!!.mkdirs(); writeText("invalid") }
        val webm = fixture("preview-video.webm")
        val directory = File(app.cacheDir, InstagramMediaFile.DIRECTORY)
        val before = directory.list()?.toSet().orEmpty()
        try {
            for (file in listOf(invalid, webm)) {
                val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
                try { withContext(Dispatchers.IO) { InstagramVideoFile.prepare(app, uri) }; fail("Unsupported file accepted") }
                catch (_: java.io.IOException) { }
                catch (_: IllegalArgumentException) { }
            }
            assertEquals(before, directory.list()?.toSet().orEmpty())
        } finally { invalid.delete(); webm.delete() }
    }
    @Test fun preparesWebmWithoutChangingItsBytesAndRejectsMislabeledContainers() = runBlocking {
        val original = fixture("preview-video.webm")
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", original)
        try {
            val metadata = withContext(Dispatchers.IO) { NativeVideoMetadata.read(app, uri, "video/webm") }
            assertTrue(metadata.width > 0 && metadata.height > 0 && metadata.durationMs > 0)
            val video = withContext(Dispatchers.IO) { InstagramVideoFile.prepare(app, uri, "video/webm") }
            try {
                assertTrue(video.file.name.endsWith(".webm"))
                assertArrayEquals(original.readBytes(), video.file.readBytes())
                assertEquals(metadata.durationMs, video.durationMs)
            } finally { video.close() }
            assertFalse(video.file.exists())
            try { withContext(Dispatchers.IO) { NativeVideoMetadata.read(app, uri, "video/mp4") }; fail("Mislabeled video accepted") }
            catch (_: java.io.IOException) { }
            instrumentation.runOnMainSync {
                try { NativeVideoMetadata.read(app, uri, "video/webm"); fail("Metadata must reject Main") }
                catch (_: IllegalStateException) { }
            }
        } finally { original.delete() }
    }
}
