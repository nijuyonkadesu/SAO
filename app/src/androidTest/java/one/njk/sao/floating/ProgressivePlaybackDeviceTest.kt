package one.njk.sao.floating

import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import one.njk.sao.source.MediaPlayback
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ProgressivePlaybackDeviceTest {
    private val app=InstrumentationRegistry.getInstrumentation().targetContext
    private val uri=Uri.parse("content://one.njk.sao.playbackprobe/file")
    @Test fun seekingReadsOnlyRequestedBytesAndPlaybackReusesItsHostCache(): Unit=runBlocking {
        app.contentResolver.call(uri,"reset",null,null)
        val owner=Job()
        val key="probe-${UUID.randomUUID()}"
        val factory=PlaybackCache.factory(app,MediaPlayback(uri,key),owner)
        suspend fun bytes(position: Long) = withContext(Dispatchers.IO) {
            val data=factory.createDataSource()
            try {
                assertEquals(32L*1024*1024-position,data.open(DataSpec.Builder().setUri(uri).setPosition(position).build()))
                val output=ByteArray(64*1024); var size=0
                while(size<output.size) { val n=data.read(output,size,output.size-size); assertTrue(n>0); size+=n }
                output.forEachIndexed { index,value -> assertEquals(((position+index)%251).toByte(),value) }
            } finally { data.close() }
        }
        try {
            bytes(0); bytes(20L*1024*1024)
            val fetched=app.contentResolver.call(uri,"stats",null,null)!!.getLong("read")
            assertTrue("Playback must not spool a 32 MiB file",fetched<1024*1024)
            bytes(0); bytes(20L*1024*1024)
            assertEquals("Cached spans must satisfy repeated seeks",fetched,app.contentResolver.call(uri,"stats",null,null)!!.getLong("read"))
        } finally { owner.cancel() }
        withTimeout(3000) { while(app.contentResolver.call(uri,"stats",null,null)!!.getLong("active")!=0L) delay(20) }
    }
    @Test fun ffmpegDecodesThroughTheSharedStreamCallbacks(): Unit=runBlocking(Dispatchers.IO) {
        val input=java.io.File.createTempFile("stream-native-",".bin",app.cacheDir)
        InstrumentationRegistry.getInstrumentation().context.assets.open("odd-mpeg4.mp4").use { from -> input.outputStream().use { from.copyTo(it) } }
        val owner=Job()
        try {
            val uri=Uri.fromFile(input)
            val factory=PlaybackCache.factory(app,MediaPlayback(uri,"decode-${UUID.randomUUID()}"),owner)
            var frames=0
            val result=VideoFrameDecoder(app).decodeSoftware(PlaybackDecoderInput(factory,uri,owner),currentCoroutineContext()) { _,width,height,_,pixels,_ ->
                assertEquals(357,width); assertEquals(372,height)
                assertTrue((pixels.get(0).toInt() and 255)>220); frames++
            }
            assertEquals(10,frames); assertEquals(VideoFrameDecoder.Backend.SOFTWARE,result.backend)
        } finally { owner.cancel(); input.delete() }
    }
    @Test fun nativeSoftwareReadsUseTheSameSeekableByteStream(): Unit=runBlocking(Dispatchers.IO) {
        val owner=Job()
        val factory=PlaybackCache.factory(app,MediaPlayback(uri,"native-${UUID.randomUUID()}"),owner)
        try { PlaybackDecoderInput(factory,uri,owner).use { input ->
            assertEquals(32L*1024*1024,input.seek(0,65536))
            assertEquals(9_000_000L,input.seek(9_000_000,0))
            val buffer=java.nio.ByteBuffer.allocateDirect(251)
            assertEquals(251,input.read(buffer)); buffer.flip()
            repeat(251) { assertEquals(((9_000_000L+it)%251).toByte(),buffer.get()) }
            assertEquals(-1L,input.seek(-1,0))
        } } finally { owner.cancel() }
    }
}
