package one.njk.sao.floating

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Movie
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import one.njk.sao.gif.GifskiEncoder
import one.njk.sao.source.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.UUID

@Suppress("DEPRECATION") // Movie is a second, independent GIF decoder used only for pixel assertions.
class VideoGifDeviceTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val app=instrumentation.targetContext
    private fun fixture(name: String)=File.createTempFile("gif-test-",".mp4",app.cacheDir).apply {
        instrumentation.context.assets.open(name).use { from -> outputStream().use { from.copyTo(it) } }
    }
    private fun item()=MediaItem(UUID.randomUUID().toString().replace('-','_'),"test.mp4","fixture","video/mp4",emptyList(),emptyList())
    private class Files(private val file: File): MediaSource {
        var originals=0; var held=false
        val opened=CompletableDeferred<Unit>()
        override val identity=kotlinx.coroutines.flow.flowOf(LOCAL_SOURCE_ID)
        override suspend fun owns(item: MediaItem)=true
        override suspend fun page(query: MediaQuery,cursor: String?)=MediaPage(emptyList(),null)
        override suspend fun filters()=emptyList<String>()
        override suspend fun <T> withPreview(item: MediaItem,block: suspend (Preview)->T): T=error("Use original")
        override suspend fun <T> withRendition(item: MediaItem,kind: Rendition,block: suspend (MediaHandle)->T): T {
            if (kind != Rendition.ORIGINAL) throw java.io.IOException("No compatible fixture rendition")
            originals++; held=true; opened.complete(Unit)
            try { return block(MediaHandle.Local(file)) } finally { held=false }
        }
    }
    private fun colors(file: File,width: Int,height: Int): Bitmap {
        val movie=requireNotNull(Movie.decodeFile(file.path))
        assertEquals(width,movie.width()); assertEquals(height,movie.height())
        return Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888).also { movie.setTime(0); movie.draw(Canvas(it),0f,0f) }
    }
    private fun near(expected: Int,actual: Int) {
        for(channel in listOf(Color::red,Color::green,Color::blue)) assertTrue("Expected $expected, got $actual",kotlin.math.abs(channel(expected)-channel(actual))<20)
    }
    @Test fun mp4ExportHasRealGifBytesCorrectColorsDurationAndIndependentCache(): Unit=runBlocking {
        val input=fixture("gif/colors.mp4"); val original=input.readBytes()
        val source=Files(input); val exports=MediaExports(app,source); val item=item()
        val output=mutableListOf<File>()
        try {
            val gif=withTimeout(30_000) { exports.asGif(item) }; output+=gif.file
            assertEquals("image/gif",gif.mimeType); assertEquals("image/gif",app.contentResolver.getType(gif.uri))
            assertEquals("GIF89a",gif.file.inputStream().use { it.readNBytes(6).toString(Charsets.US_ASCII) })
            assertEquals(1000,requireNotNull(Movie.decodeFile(gif.file.path)).duration())
            colors(gif.file,96,64).also { bitmap -> try {
                near(Color.GREEN,bitmap.getPixel(20,16)); near(Color.BLUE,bitmap.getPixel(72,48)); near(Color.RED,bitmap.getPixel(72,16))
            } finally { bitmap.recycle() } }
            assertEquals(gif.uri,exports.asGif(item).uri); assertEquals(1,source.originals)
            assertFalse(source.held)
            val unchanged=exports.prepare(item,ExportRendition.MEDIA); output+=unchanged.file
            assertEquals("video/mp4",unchanged.mimeType); assertNotEquals(gif.uri,unchanged.uri)
            assertArrayEquals(original,unchanged.file.readBytes()); assertArrayEquals(original,input.readBytes())
        } finally { input.delete(); output.forEach(File::delete) }
    }
    @Test fun cachedStickerInputAvoidsRepeatingSourceReadsAndConversionProbes(): Unit=runBlocking {
        val input=fixture("gif/colors.mp4"); val source=Files(input); val exports=MediaExports(app,source); val item=item()
        try {
            val first=exports.nativeStickerInput(item)
            try {
                assertEquals(1,source.originals)
                assertEquals(first.uri,exports.nativeStickerInput(item).uri)
                assertEquals("An unchanged prepared revision must not reopen its source",1,source.originals)
                assertArrayEquals(input.readBytes(),first.file.readBytes())
            } finally { first.file.delete() }
        } finally { input.delete() }
    }
    @Test fun existingGifsStayByteExactAndNeverRunTheVideoEncoder(): Unit=runBlocking {
        val input=fixture("floating/hello.gif"); val source=Files(input); val exports=MediaExports(app,source)
        val media=item().copy(mimeType="image/gif",animated=true)
        try {
            val gif=exports.asGif(media) { error("A GIF does not need another conversion") }
            try { assertArrayEquals(input.readBytes(),gif.file.readBytes()) }
            finally { gif.file.delete() }
        } finally { input.delete() }
    }

    @Test fun rotationIsAppliedOnceWithoutChangingAspectOrSwappingColors(): Unit=runBlocking {
        val input=fixture("gif/rotated.mp4"); val output=File.createTempFile("rotated-",".gif",app.cacheDir)
        try {
            withTimeout(30_000) { VideoGifTranscoder(app).gif(input,output) }
            colors(output,64,96).also { bitmap -> try {
                near(Color.RED,bitmap.getPixel(16,24)); near(Color.BLUE,bitmap.getPixel(48,24)); near(Color.GREEN,bitmap.getPixel(16,72))
            } finally { bitmap.recycle() } }
        } finally { input.delete(); output.delete() }
    }
    @Test fun paddedRgbaAndVariableFinalDelayRemainValid(): Unit=runBlocking(Dispatchers.IO) {
        val output=File.createTempFile("stride-",".gif",app.cacheDir)
        try {
            val width=37; val height=25; val stride=160
            GifskiEncoder(output,1024*1024).use { encoder ->
                for((index,color) in listOf(Color.RED,Color.GREEN,Color.BLUE).withIndex()) {
                    val bytes=ByteBuffer.allocateDirect(stride*height)
                    repeat(height) { y -> repeat(width) { x ->
                        val at=y*stride+x*4
                        bytes.put(at,Color.red(color).toByte()); bytes.put(at+1,Color.green(color).toByte())
                        bytes.put(at+2,Color.blue(color).toByte()); bytes.put(at+3,255.toByte())
                    } }
                    encoder.add(index,width,height,stride,bytes,listOf(0.0,0.08,0.2)[index])
                }
                encoder.finish()
            }
            GifContainer.finish(output,570_000,width,height)
            val movie=requireNotNull(Movie.decodeFile(output.path)); assertEquals(570,movie.duration())
            colors(output,width,height).also { try { near(Color.RED,it.getPixel(18,12)) } finally { it.recycle() } }
            RandomAccessFile(output,"rw").use { it.setLength(it.length()-1) }
            try { GifContainer.finish(output,570_000,width,height); fail("Truncated output must not enter the cache") } catch(_: java.io.IOException) { }
        } finally { output.delete() }
    }
    @Test fun largerFramesNeverUseGifskiDefaultAutomaticDownsampling(): Unit=runBlocking(Dispatchers.IO) {
        val output=File.createTempFile("full-size-",".gif",app.cacheDir)
        val width=1280; val height=720
        try {
            GifskiEncoder(output,1024*1024).use { encoder ->
                for((index,color) in listOf(Color.RED,Color.BLUE).withIndex()) {
                    val bytes=ByteBuffer.allocateDirect(width*height*4)
                    repeat(width*height) { bytes.put(Color.red(color).toByte()).put(Color.green(color).toByte()).put(Color.blue(color).toByte()).put(255.toByte()) }
                    encoder.add(index,width,height,width*4,bytes,index*0.1)
                }
                encoder.finish()
            }
            GifContainer.finish(output,200_000,width,height)
            colors(output,width,height).also { try { near(Color.RED,it.getPixel(width/2,height/2)) } finally { it.recycle() } }
        } finally { output.delete() }
    }
    @Test fun cancellingConversionJoinsWorkersBeforeReleasingSourceAndAllowsRetry(): Unit=runBlocking {
        val input=fixture("export/busy.webm"); val source=Files(input)
        val store=MediaExportStore(app); val exports=MediaExports(app,source,store=store); val media=item()
        val previous=store.directory.listFiles().orEmpty().filter { it.name.endsWith(".pending") }.toSet()
        val running=async(Dispatchers.IO) { exports.asGif(media) }
        try {
            withTimeout(10_000) { source.opened.await() }; delay(150)
            withTimeout(10_000) { running.cancelAndJoin() }
            assertFalse("The scoped input is released only after native/GL cleanup",source.held)
            assertFalse(File(store.directory,MediaExportKey(media,MediaExportPolicy.AsGif).filename("gif")).exists())
            assertEquals(previous,store.directory.listFiles().orEmpty().filter { it.name.endsWith(".pending") }.toSet())
            val small=fixture("gif/colors.mp4")
            try { val retry=withTimeout(30_000) { MediaExports(app,Files(small),store=store).asGif(media) }; retry.file.delete() }
            finally { small.delete() }
        } finally { running.cancelAndJoin(); input.delete() }
    }

    @Test fun hdrIsExplicitlyToneMappedOrRejectedWhileOriginalRemainsExact(): Unit=runBlocking {
        val input=fixture("export/hdr.webm"); val original=input.readBytes()
        try {
            val exports=MediaExports(app,Files(input))
            try {
                val converted=withTimeout(30_000) { exports.asGif(item()) }
                try {
                    assertEquals("image/gif",converted.mimeType)
                    val decoded=Movie.decodeFile(converted.file.path)
                    assertNotNull(decoded); assertTrue(decoded!!.width()>0 && decoded.height()>0)
                } finally { converted.file.delete() }
            } catch(error: MediaExportException) {
                assertTrue(error.messageRes in setOf(one.njk.sao.R.string.media_export_gif_unavailable,one.njk.sao.R.string.media_export_decode_failed))
            }
            assertArrayEquals(original,input.readBytes())
        } finally { input.delete() }
    }

    @Test fun nativeWriteBudgetFailsWithoutRecompressingOrExceedingItsLimit(): Unit=runBlocking(Dispatchers.IO) {
        val output=File.createTempFile("budget-",".gif",app.cacheDir)
        try {
            val pixels=ByteBuffer.allocateDirect(96*64*4)
            val random=java.util.Random(42)
            repeat(96*64) { pixels.put(random.nextInt().toByte()).put(random.nextInt().toByte()).put(random.nextInt().toByte()).put(255.toByte()) }
            try {
                GifskiEncoder(output,128).use { it.add(0,96,64,96*4,pixels,0.0); it.finish() }
                fail("An oversized GIF must fail rather than silently reducing quality")
            } catch(_: java.io.IOException) { }
            assertTrue(output.length()<=128)
        } finally { output.delete() }
    }
}
