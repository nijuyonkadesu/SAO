package one.njk.sao.floating

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.ExifInterface
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import one.njk.sao.R
import one.njk.sao.source.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Synthetic files only. Real platform decoders/codecs and the host's exported-URI path. */
class MediaExportDeviceTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val app=instrumentation.targetContext
    private val dispatchers=FloatingDispatchers()
    private fun name()="export_${UUID.randomUUID().toString().replace("-","")}"
    private fun fixture(asset: String)=File(app.cacheDir,name()+"."+asset.substringAfterLast('.')).apply {
        instrumentation.context.assets.open(asset).use { input -> outputStream().use { input.copyTo(it) } }
    }
    private fun picture(width: Int=1200,height: Int=600,format: Bitmap.CompressFormat=Bitmap.CompressFormat.PNG): File {
        val file=File(app.cacheDir,name()+if(format==Bitmap.CompressFormat.JPEG) ".jpg" else ".png")
        val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        try {
            Canvas(bitmap).apply {
                drawColor(Color.BLUE)
                drawRect(0f,0f,width/2f,height.toFloat(),Paint().apply { color=Color.RED })
            }
            file.outputStream().use { check(bitmap.compress(format,100,it)) }
        } finally { bitmap.recycle() }
        return file
    }
    private fun item(mime: String) = MediaItem(name(),"Synthetic export","",mime,emptyList(),emptyList())
    private class Files(private val file: File) : MediaSource {
        val originals=AtomicInteger(); val playbacks=AtomicInteger(); val stills=AtomicInteger()
        override val identity=kotlinx.coroutines.flow.flowOf(LOCAL_SOURCE_ID)
        override suspend fun owns(item: MediaItem)=true
        override suspend fun page(query: MediaQuery,cursor: String?)=MediaPage(emptyList())
        override suspend fun filters()=emptyList<String>()
        override suspend fun preview(item: MediaItem): Preview=Preview.Local(file)
        override suspend fun <T> withRendition(item: MediaItem,kind: Rendition,
            block: suspend (MediaHandle)->T): T {
            when(kind) {
                Rendition.ORIGINAL -> originals.incrementAndGet()
                Rendition.PLAYBACK -> playbacks.incrementAndGet()
                Rendition.STILL -> stills.incrementAndGet()
            }
            return block(MediaHandle.Local(file))
        }
    }

    @Test fun largeStillUsesTargetSizedSoftwareDecode(): Unit=runBlocking(dispatchers.conversion) {
        val file=picture(4096,4096)
        try { file.inputStream().use { input ->
            val decoded=StaticStickerEncoder.decode(input,ExportRendition.STICKER_WEBP,StickerRequirements.WHATSAPP)
            try {
                assertTrue(decoded.bitmap.width<=496 && decoded.bitmap.height<=496)
                assertTrue(decoded.bitmap.allocationByteCount<=496*496*8)
                assertNotEquals(Bitmap.Config.HARDWARE,decoded.bitmap.config)
                assertFalse(decoded.reusable)
            } finally { decoded.bitmap.recycle() }
        } } finally { file.delete() }
    }

    @Test fun generatedStickersBakeRotationAndReflectionWithoutChangingOriginals(): Unit=runBlocking {
        for(orientation in listOf(ExifInterface.ORIENTATION_ROTATE_90,ExifInterface.ORIENTATION_FLIP_HORIZONTAL)) {
            val input=picture(format=Bitmap.CompressFormat.JPEG)
            try {
                ExifInterface(input.path).apply { setAttribute(ExifInterface.TAG_ORIENTATION,orientation.toString()); saveAttributes() }
                val original=input.readBytes()
                val source=Files(input); val exports=MediaExports(app,source)
                val media=item("image/jpeg")
                val sticker=exports.prepare(media,ExportRendition.STICKER_PNG)
                val bitmap=BitmapFactory.decodeFile(sticker.file.path)
                try {
                    assertEquals(512,bitmap.width); assertEquals(512,bitmap.height)
                    if(orientation==ExifInterface.ORIENTATION_ROTATE_90) {
                        assertTrue(Color.red(bitmap.getPixel(256,128))>220)
                        assertTrue(Color.blue(bitmap.getPixel(256,384))>220)
                    } else {
                        assertTrue(Color.blue(bitmap.getPixel(128,256))>220)
                        assertTrue(Color.red(bitmap.getPixel(384,256))>220)
                    }
                    assertEquals(0,Color.alpha(bitmap.getPixel(0,0)))
                    assertArrayEquals(original,exports.prepare(media,ExportRendition.MEDIA).file.readBytes())
                } finally { bitmap.recycle(); sticker.file.delete() }
            } finally { input.delete() }
        }
    }

    @Test fun compliantPngAndWebpAreReusedAndSimpleGraphicsStayLossless(): Unit=runBlocking {
        for(rendition in listOf(ExportRendition.STICKER_PNG,ExportRendition.STICKER_WEBP)) {
            val input=picture(512,512,if(rendition==ExportRendition.STICKER_PNG) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.WEBP_LOSSLESS)
            try {
                val source=Files(input)
                val export=MediaExports(app,source).prepare(item(if(rendition==ExportRendition.STICKER_PNG) "image/png" else "image/webp"),rendition)
                assertArrayEquals(input.readBytes(),export.file.readBytes())
                export.file.delete()
            } finally { input.delete() }
        }
        val input=picture()
        try {
            val export=MediaExports(app,Files(input)).prepare(item("image/png"),ExportRendition.STICKER_WEBP)
            val bitmap=BitmapFactory.decodeFile(export.file.path)
            try {
                assertEquals(Color.RED,bitmap.getPixel(128,256))
                assertEquals(Color.BLUE,bitmap.getPixel(384,256))
                assertEquals(0,Color.alpha(bitmap.getPixel(0,0)))
                assertTrue(export.file.length()<=100*1024)
            } finally { bitmap.recycle(); export.file.delete() }
        } finally { input.delete() }
    }

    @Test fun noisyStickerMeetsOnlyItsReceivingAppsBudget(): Unit=runBlocking {
        val input=picture(512,512)
        val bitmap=Bitmap.createBitmap(512,512,Bitmap.Config.ARGB_8888)
        try {
            val random=java.util.Random(420)
            bitmap.setPixels(IntArray(512*512) { Color.rgb(random.nextInt(256),random.nextInt(256),random.nextInt(256)) },0,512,0,0,512,512)
            input.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
            val exports=MediaExports(app,Files(input)); val media=item("image/png")
            val small=exports.prepare(media,ExportRendition.STICKER_WEBP,StickerRequirements.WHATSAPP)
            val large=exports.prepare(media,ExportRendition.STICKER_WEBP,StickerRequirements.TELEGRAM)
            try {
                assertTrue(small.file.length()<=100*1024)
                assertTrue(large.file.length()<=512*1024)
                assertNotEquals(small.uri,large.uri)
                assertTrue(large.file.length()>=small.file.length())
                assertArrayEquals(input.readBytes(),exports.prepare(media,ExportRendition.MEDIA).file.readBytes())
            } finally { small.file.delete(); large.file.delete() }
        } finally { bitmap.recycle(); input.delete() }
    }

    @Test fun actualAnimationCannotBeFlattenedByAnIncorrectSourceFlag(): Unit=runBlocking {
        val input=fixture("floating/hello.gif")
        try {
            try { MediaExports(app,Files(input)).prepare(item("image/png"),ExportRendition.STICKER_WEBP); fail("Must reject an actual animated input") }
            catch(_: IllegalArgumentException) { }
        } finally { input.delete() }
    }

    @Test fun aWaitingConversionDoesNotBlockAnUnrelatedCopyAndSameOutputIsWrittenOnce(): Unit=runBlocking {
        val store=MediaExportStore(app)
        val started=CompletableDeferred<Unit>(); val release=CompletableDeferred<Unit>()
        val slow=async(Dispatchers.IO) { store.write(name()+".mp4") { started.complete(Unit); release.await(); it.writeBytes(byteArrayOf(1)) } }
        try {
            started.await()
            val input=picture(); val source=Files(input); val media=item("image/png")
            try {
                val exports=MediaExports(app,source,dispatchers,VideoTranscoder(app,dispatchers),store)
                val copies=withTimeout(3000) { awaitAll(async { exports.prepare(media,ExportRendition.MEDIA) },async { exports.prepare(media,ExportRendition.MEDIA) }) }
                assertEquals(copies[0].file,copies[1].file)
                assertEquals(1,source.originals.get())
                assertArrayEquals(input.readBytes(),copies[0].file.readBytes())
                copies[0].file.delete()
            } finally { input.delete() }
        } finally { release.complete(Unit); slow.await().delete() }
    }

    @Test fun cancellationRemovesPartialOutputAndAllowsTheNextAttempt(): Unit=runBlocking {
        val store=MediaExportStore(app); val output=name()+".png"
        val entered=CountDownLatch(1); val resume=CountDownLatch(1)
        val job=launch(Dispatchers.IO) { store.write(output) { pending ->
            val input=object: java.io.InputStream() {
                override fun read(): Int=error("Bulk reads only")
                override fun read(bytes: ByteArray,offset: Int,count: Int): Int {
                    entered.countDown(); check(resume.await(5,TimeUnit.SECONDS)); bytes[offset]=1; return 1
                }
            }
            pending.outputStream().use { MediaExportStore.copy(input,it) }
        } }
        try {
            assertTrue(entered.await(5,TimeUnit.SECONDS)); job.cancel(); resume.countDown(); job.join()
            assertFalse(File(store.directory,output).exists())
            assertFalse(store.directory.listFiles().orEmpty().any { it.name.endsWith(".pending") })
            val result=store.write(output) { it.writeBytes(byteArrayOf(2,3)) }
            assertArrayEquals(byteArrayOf(2,3),result.readBytes()); result.delete()
        } finally { resume.countDown(); job.cancelAndJoin() }
    }

    @Test fun retentionProtectsClipboardAndHandoffsWhileReclaimingOldExports(): Unit=runBlocking {
        val store=MediaExportStore(app)
        val kept=store.write(name()+".png") { it.writeBytes(byteArrayOf(1)) }
        store.clipboard(kept)
        val handoff=store.write(name()+".png") { it.writeBytes(byteArrayOf(2)) }
        val old=File(store.directory.parentFile,"${name()}.old").apply { writeBytes(byteArrayOf(3)) }
        val expired=System.currentTimeMillis()-MediaExportStore.MAX_AGE_MS-1000
        try {
            kept.setLastModified(expired); handoff.setLastModified(expired); old.setLastModified(expired)
            // A fresh store has no in-memory handoff for the current clipboard.
            MediaExportStore(app).write(name()+".png") { it.writeBytes(byteArrayOf(4)) }.delete()
            assertTrue(kept.exists()); assertFalse(old.exists())
            // Same-process handoff protection survives another export even if its mtime is old.
            val active=store.write(name()+".png") { it.writeBytes(byteArrayOf(5)) }
            active.setLastModified(expired)
            store.write(name()+".png") { it.writeBytes(byteArrayOf(6)) }.delete()
            assertTrue(active.exists()); active.delete()
        } finally { store.clipboard(null); kept.delete(); handoff.delete(); old.delete() }
    }

    @Test fun webmCompatibilityReadsTheOriginalOnceWithoutCopyingIt(): Unit=runBlocking {
        val input=fixture("export/sdr.webm"); val original=input.readBytes()
        try {
            val source=Files(input); val exports=MediaExports(app,source); val media=item("video/webm")
            val converted=withTimeout(30_000) { exports.mp4(media) }
            try {
                val result=VideoTranscoder(app,dispatchers).inspect(converted.file)
                assertEquals("video/avc",result.mime); assertEquals("audio/mp4a-latm",result.audioMime)
                assertEquals(1,source.originals.get()); assertEquals(0,source.playbacks.get())
                assertEquals(converted.uri,exports.mp4(media).uri); assertEquals(1,source.originals.get())
                assertArrayEquals(original,input.readBytes())
            } finally { converted.file.delete() }
        } finally { input.delete() }
    }

    @Test fun scopedCacheInputCannotBeEvictedDuringConversion(): Unit=runBlocking {
        val store=MediaExportStore(app)
        val input=store.write(name()+".png") { it.writeBytes(byteArrayOf(1,2,3)) }
        val cache=SourceMediaCache(app)
        val owner=name(); val key="leased"
        val folder=File(app.cacheDir,"source-media/${digest(owner).take(32)}")
        try {
            cache.withFile(owner,key,1024,{ store.exported(input,"image/png").uri }) { leased ->
                leased.setLastModified(1)
                val disposable=File(folder,digest("oversized"))
                RandomAccessFile(disposable,"rw").use { it.setLength(193L*1024*1024) }
                disposable.setLastModified(2)
                cache.get(owner,"next",1024) { store.exported(input,"image/png").uri }
                assertTrue("The oldest active input is protected",leased.exists())
                assertFalse("Unleased cache bytes are evicted instead",disposable.exists())
                assertArrayEquals(byteArrayOf(1,2,3),leased.readBytes())
            }
        } finally { cache.remove(owner); input.delete() }
    }

    @Test fun hdrOriginalStaysExactAndCompatibilityNeverFallsBackToSdr(): Unit=runBlocking {
        val input=fixture("export/hdr.webm"); val original=input.readBytes()
        try {
            val source=Files(input); val exports=MediaExports(app,source); val media=item("video/webm")
            val originalExport=exports.prepare(media,ExportRendition.MEDIA)
            try {
                assertArrayEquals(original,originalExport.file.readBytes())
                assertTrue(VideoTranscoder(app,dispatchers).inspect(originalExport.file).hdr)
                try {
                    val compatible=withTimeout(30_000) { exports.mp4(media) }
                    try {
                        val actual=VideoTranscoder(app,dispatchers).inspect(compatible.file)
                        assertTrue(actual.hdr); assertEquals("video/hevc",actual.mime)
                    } finally { compatible.file.delete() }
                } catch(error: MediaExportException) {
                    assertTrue("${app.getString(error.messageRes)}: ${error.cause?.stackTraceToString()}",error.messageRes in setOf(R.string.media_export_hdr_unavailable,R.string.media_export_encode_failed,
                        R.string.media_export_decode_failed))
                }
                assertArrayEquals(original,input.readBytes())
            } finally { originalExport.file.delete() }
        } finally { input.delete() }
    }

    @Test fun explicitJpegReductionKeepsDimensionsAndOriginalBytes(): Unit=runBlocking {
        val bitmap=Bitmap.createBitmap(512,512,Bitmap.Config.ARGB_8888)
        val random=java.util.Random(42)
        bitmap.setPixels(IntArray(512*512) { Color.rgb(random.nextInt(256),random.nextInt(256),random.nextInt(256)) },0,512,0,0,512,512)
        val file=File(app.cacheDir,name()+".jpg")
        try {
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG,100,it)) }
            val original=file.readBytes(); val source=Files(file); val exports=MediaExports(app,source)
            val media=item("image/jpeg"); val exported=exports.prepare(media,ExportRendition.MEDIA)
            val budget=original.size/2L
            val smaller=exports.smaller(media,exported,budget)
            try {
                assertTrue(smaller.file.length()<=budget)
                assertArrayEquals(original,exported.file.readBytes())
                BitmapFactory.decodeFile(smaller.file.path).also {
                    try { assertEquals(512,it.width); assertEquals(512,it.height) } finally { it.recycle() }
                }
                assertNotEquals(exported.uri,smaller.uri)
            } finally { smaller.file.delete(); exported.file.delete() }
        } finally { bitmap.recycle(); file.delete() }
    }

    @Test fun explicitVideoBudgetKeepsResolutionAndAudio(): Unit=runBlocking {
        val input=fixture("export/busy.webm")
        val original=input.readBytes()
        try {
            val media=item("video/webm"); val exports=MediaExports(app,Files(input))
            val untouched=exports.prepare(media,ExportRendition.MEDIA)
            try {
                assertTrue(original.size>150_000)
                val reduced=withTimeout(30_000) { exports.smaller(media,untouched,150_000) }
                try {
                    assertTrue(reduced.file.length()<=150_000)
                    val info=VideoTranscoder(app,dispatchers).inspect(reduced.file)
                    assertEquals(320,info.width); assertEquals(240,info.height)
                    assertEquals("audio/mp4a-latm",info.audioMime)
                    assertArrayEquals(original,untouched.file.readBytes())
                } finally { reduced.file.delete() }
            } finally { untouched.file.delete() }
        } finally { input.delete() }
    }

    @Test fun transparentPngIsNeverFlattenedToMeetAnImpossibleBudget(): Unit=runBlocking {
        val bitmap=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888)
        val random=java.util.Random(42)
        bitmap.setPixels(IntArray(256*256) { Color.argb(random.nextInt(256),random.nextInt(256),random.nextInt(256),random.nextInt(256)) },0,256,0,0,256,256)
        val input=File(app.cacheDir,name()+".png")
        try {
            input.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
            val original=input.readBytes(); val media=item("image/png"); val exports=MediaExports(app,Files(input))
            val untouched=exports.prepare(media,ExportRendition.MEDIA)
            try {
                try { exports.smaller(media,untouched,1024); fail("Must not flatten alpha or reduce dimensions") }
                catch(error: MediaExportException) { assertEquals(R.string.media_export_size_unavailable,error.messageRes) }
                assertArrayEquals(original,untouched.file.readBytes())
            } finally { untouched.file.delete() }
        } finally { bitmap.recycle(); input.delete() }
    }
}
