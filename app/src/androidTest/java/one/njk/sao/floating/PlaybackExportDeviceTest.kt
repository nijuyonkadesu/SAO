package one.njk.sao.floating

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import one.njk.sao.source.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class PlaybackExportDeviceTest {
    @Test fun displayedShareUsesActualPlaybackContainerAndExplicitExportPreservesOriginal(): Unit = runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val app=instrumentation.targetContext
        val playback=File.createTempFile("playback-", ".mp4", app.cacheDir)
        val expected=instrumentation.context.assets.open("preview-video.mp4").use { it.readBytes() }
        playback.writeBytes(expected)
        val original=byteArrayOf(0x1a, 0x45, 0xdf.toByte(), 0xa3.toByte()) + ByteArray(100) { 7 }
        var originals=0
        var playbackLease=false
        val source=object : MediaSource {
            override val identity=kotlinx.coroutines.flow.flowOf(LOCAL_SOURCE_ID)
            override suspend fun owns(item: MediaItem)=true
            override suspend fun page(query: MediaQuery, cursor: String?)=MediaPage(emptyList(), null)
            override suspend fun filters()=emptyList<String>()
            override suspend fun preview(item: MediaItem): Preview=error("Video uses its playback rendition")
            override suspend fun <T> withRendition(item: MediaItem, kind: Rendition,
                block: suspend (MediaHandle)->T): T=when(kind) {
                Rendition.PLAYBACK -> {
                    playbackLease=true
                    try { block(MediaHandle.Local(playback)) } finally { playbackLease=false }
                }
                Rendition.ORIGINAL -> { originals++; block(MediaHandle.Stream(original.inputStream())) }
                Rendition.STILL -> error("Video is not rasterized")
            }
        }
        val item=MediaItem("playback_${UUID.randomUUID().toString().replace('-', '_')}", "huge.mkv", "fixture",
            "video/x-matroska", emptyList(), emptyList())
        val exports=MediaExports(app,source)
        val output=mutableListOf<File>()
        try {
            val shared=exports.displayed(item); output+=shared.file
            assertFalse(playbackLease)
            assertEquals(0, originals)
            assertEquals("video/mp4", shared.mimeType)
            assertTrue(shared.file.name.endsWith(".mp4"))
            assertArrayEquals(expected, shared.file.readBytes())
            val copied=exports.prepare(item,ExportRendition.MEDIA); output+=copied.file
            assertEquals(1, originals)
            assertEquals("video/x-matroska", copied.mimeType)
            assertArrayEquals(original, copied.file.readBytes())
            assertNotEquals(shared.file, copied.file)
        } finally { playback.delete(); output.forEach { it.delete() } }
    }
}
