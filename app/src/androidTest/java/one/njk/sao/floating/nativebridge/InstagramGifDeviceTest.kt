package one.njk.sao.floating.nativebridge

import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

class InstagramGifDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext
    private fun bytes() = instrumentation.context.assets.open("floating/hello.gif").use { it.readBytes() }
    private fun stagedFiles() = File(app.cacheDir, InstagramMediaFile.DIRECTORY).list()?.toSet().orEmpty()

    @Test fun uriAndByteStreamsPreserveTheSameAnimatedFile() = runBlocking {
        val bytes = bytes()
        val original = File(app.filesDir, "floating_exports/native-gif-test.gif").apply { parentFile!!.mkdirs(); writeBytes(bytes) }
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", original)
        try {
            withContext(Dispatchers.IO) {
                InstagramGifFile.prepare(app, uri).use { fromUri ->
                    InstagramGifFile.prepare(app) { ByteArrayInputStream(bytes) }.use { fromBytes ->
                        assertTrue(fromUri.width > 0 && fromUri.height > 0)
                        assertEquals(fromUri.width, fromBytes.width)
                        assertEquals(fromUri.height, fromBytes.height)
                        assertArrayEquals(bytes, fromUri.file.readBytes())
                        assertArrayEquals(bytes, fromBytes.file.readBytes())
                        assertNotEquals(fromUri.file, fromBytes.file)
                    }
                }
            }
        } finally { original.delete() }
    }
    @Test fun failedOrCancelledPreparationRemovesPartialFiles() = runBlocking {
        val before = stagedFiles()
        withContext(Dispatchers.IO) {
            val png = instrumentation.context.assets.open("floating/happy.png").use { it.readBytes() }
            for (bytes in listOf(png, "GIF89a".toByteArray(), byteArrayOf())) {
                try { InstagramGifFile.prepare(app) { ByteArrayInputStream(bytes) }.use { }; fail("Invalid GIF accepted") }
                catch (_: IOException) { }
            }
            try {
                InstagramMediaFile.prepare(app, { ByteArrayInputStream(ByteArray(17)) }, ".gif", 16).use { }
                fail("Bounded copy accepted oversized input")
            } catch (_: IOException) { }
            try {
                InstagramGifFile.prepare(app) { object : InputStream() {
                    override fun read(): Int = throw IOException("Source was revoked")
                } }.use { }
                fail("Revoked source accepted")
            } catch (_: IOException) { }
        }
        assertEquals(before, stagedFiles())
    }
    @Test fun mainThreadDoesNotOpenTheSourceAndSubmissionRetainsTheFile() = runBlocking {
        var opened = false
        instrumentation.runOnMainSync {
            try {
                InstagramGifFile.prepare(app) { opened = true; ByteArrayInputStream(byteArrayOf()) }
                fail("Preparation must reject Main")
            } catch (_: IllegalStateException) { }
        }
        assertFalse(opened)
        withContext(Dispatchers.IO) {
            val gif = InstagramGifFile.prepare(app) { ByteArrayInputStream(bytes()) }
            try {
                gif.submitted(); gif.close()
                assertTrue(gif.file.exists())
            } finally { gif.file.delete() }
        }
    }
}
