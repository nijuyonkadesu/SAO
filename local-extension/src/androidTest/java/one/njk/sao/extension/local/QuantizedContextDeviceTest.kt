package one.njk.sao.extension.local

import android.content.ComponentCallbacks2
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import one.njk.sao.source.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID
import kotlin.math.sqrt

class QuantizedContextDeviceTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val app=instrumentation.targetContext
    private val model=ClipCatalog.lite
    @Before fun requireModels() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("externalModelFixtures")=="true")
        assertNotNull(ModelStorage.get(app,model)); assertTrue(ClipModels.ready(app,model))
    }
    private fun reference()=JSONObject(instrumentation.context.assets.open("clip-8bit-reference.json").bufferedReader().use { it.readText() })
    private fun cosine(a: FloatArray,b: FloatArray)=a.indices.sumOf { a[it].toDouble()*b[it] }/sqrt(a.sumOf { it.toDouble()*it }*b.sumOf { it.toDouble()*it })
    @Test fun quantizedTokenizerAndEightBitTextMatchPinnedReferenceIncludingEmoji()=runBlocking {
        val tokenizer=ClipTokenizer(ClipModels.tokenizer(app,model))
        val cases=reference().getJSONArray("textual")
        ClipEncoder(app,false,model).use { encoder -> for(index in 0 until cases.length()) {
            val fixture=cases.getJSONObject(index); val text=fixture.getString("text"); val ids=fixture.getJSONArray("ids")
            assertArrayEquals(text,IntArray(77) { ids.getInt(it) },tokenizer.encode(text))
            val vector=fixture.getJSONArray("vector"); val actual=encoder.text(text)
            val similarity=cosine(FloatArray(512) { vector.getDouble(it).toFloat() },actual)
            // Dynamic 8-bit kernels round differently across desktop AVX512 and Android CPUs.
            // This is numeric conformance, not a claim of equivalent retrieval recall to FP32.
            assertTrue("$text: $similarity",similarity>.999)
        } }
    }
    @Test fun quantizedImageEncoderMatchesBicubicResizeAndCenterCrop()=runBlocking {
        val files=File(app.cacheDir,"int8-fixture-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            instrumentation.context.assets.open("happy.png").use { input -> File(files,"happy.png").outputStream().use(input::copyTo) }
            val gradient=Bitmap.createBitmap(381,213,Bitmap.Config.ARGB_8888)
            for(y in 0 until 213) for(x in 0 until 381) gradient.setPixel(x,y,Color.rgb((x*7+y*3)%256,(x+y*11)%256,(x*5+y)%256))
            File(files,"gradient.png").outputStream().use { gradient.compress(Bitmap.CompressFormat.PNG,100,it) }; gradient.recycle()
            ClipEncoder(app,true,model).use { encoder -> for(name in listOf("happy","gradient")) {
                val asset=LocalAsset(1,"fixture","fixture",Uri.fromFile(File(files,"$name.png")).toString(),"$name.png",name,"image/png",1,1,"fixture")
                val vector=reference().getJSONObject("images").getJSONArray(name)
                val actual=encoder.image(app,asset)
                val bitmap=MediaBitmaps.read(app,Uri.parse(asset.uri),asset.mime,2048,Color.BLACK)
                val pixels=try { ClipEncoder.pixels(bitmap) } finally { bitmap.recycle() }
                val bytes=java.nio.ByteBuffer.allocate(pixels.size*4).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply { asFloatBuffer().put(pixels) }.array()
                val hash=java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                assertEquals("$name preprocessing differs from Pillow",reference().getJSONObject("pixel_sha256").getString(name),hash)
                val similarity=cosine(FloatArray(512) { vector.getDouble(it).toFloat() },actual)
                // The input pixels must match exactly; the quantized CPU kernels have a looser numeric tolerance.
                assertTrue("$name: $similarity",similarity>.99)
            } }
        } finally { files.deleteRecursively() }
    }
    @Test fun textWeightsLoadOnlyForQueryAndReleaseOnIdlePressureAndClose()=runBlocking {
        val text=ClipTextSession(app,200)
        try {
            assertFalse(text.isLoaded()); text.encode("blue shirt",model); assertTrue(text.isLoaded())
            withTimeout(4000) { while(text.isLoaded()) delay(25) }
            text.encode("😊",model); assertTrue(text.isLoaded())
            text.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
            withTimeout(4000) { while(text.isLoaded()) delay(25) }
            text.encode("blue shirt",model); text.close()
            withTimeout(4000) { while(text.isLoaded()) delay(25) }
        } finally { text.close() }
    }
    @Test fun modelSwitchInvalidatesVectorsAndCursorButKeepsLabelsAndCorrections()=runBlocking {
        val store=LocalStore.get(app); val id=UUID.randomUUID().toString()
        val root="content://fixture/tree/root"; val folders=listOf(Folder(root,"Reactions"))
        val first=store.save(id,"Models",folders,contextSearch=true)
        val text=ClipTextSession(app)
        try {
            store.pause(id,true)
            repeat(65) { index -> store.discovered(id,"fixture",root,"content://fixture/$index","$index.png","Reactions/$index.png","image/png",1,1,first.revision) }
            val assets=store.search(id,MediaQuery(),limit=100)
            val vector=text.encode("blue shirt")
            assets.forEach { store.analyzed(it,Analysis(listOf("Smile"),"text"),false,null); store.embedded(it,vector,null) }
            store.corrections(assets.first().id,id,"My title","Manual description","😊")
            val cursor=LocalContextSearch(store,text).page(first,"fixture",MediaQuery("blue shirt"),null).nextCursor!!
            val second=store.save(id,"Models",folders,contextSearch=true,contextModel=model.id)
            assertNotEquals(first.revision,second.revision)
            assertEquals(0,store.overview(id).total.contextProcessed); assertEquals(65,store.overview(id).total.contextPending)
            assertEquals(0,store.overview(id).total.pending)
            assertEquals("My title",store.asset(assets.first().id,id).displayTitle)
            assertEquals(listOf("😊"),store.asset(assets.first().id,id).effectiveTags)
            try { LocalContextSearch(store,text).page(second,"fixture",MediaQuery("blue shirt"),cursor); fail("Mixed-model cursor accepted") }
            catch(_: ExtensionFailure) { }
            val mobile=text.encode("blue shirt",model)
            assets.forEach { store.embedded(it,mobile,null,model.id) }
            assertEquals(65,store.overview(id).total.contextProcessed)
            assertEquals(60,LocalContextSearch(store,text).page(second,"fixture",MediaQuery("blue shirt"),null).items.size)
            assertTrue(store.search(id,MediaQuery("smile",mode=SearchMode.DESCRIPTION)).isNotEmpty())
        } finally { text.close(); FolderIndexer.change(app,id) { store.delete(id) } }
    }
    @Test fun actualIndexWorkerUsesChosenEightBitPairForImagesGifMp4AndWebm()=runBlocking {
        val store=LocalStore.get(app); val id=UUID.randomUUID().toString(); val name="int8-${UUID.randomUUID()}"
        val files=File(app.filesDir,"test-documents/$name").apply { mkdirs() }
        try {
            for(file in listOf("happy.png","hello.gif","preview-video.mp4","preview-video.webm")) {
                instrumentation.context.assets.open(file).use { input -> File(files,file).outputStream().use(input::copyTo) }
            }
            val root=android.provider.DocumentsContract.buildTreeDocumentUri("${app.packageName}.fixtures","root/$name").toString()
            val library=store.save(id,"8-bit fixtures",listOf(Folder(root,"Reactions")),contextSearch=true,contextModel=model.id)
            FolderIndexer.schedule(app,library,force=true)
            withTimeout(120_000) { while(store.overview(id).total.let { it.total<4 || it.scanning || it.pending>0 || it.contextPending>0 }) delay(100) }
            val status=store.overview(id).total
            assertEquals(4,status.contextProcessed); assertEquals(0,status.contextFailed); assertEquals(0,status.failed)
            val text=ClipTextSession(app)
            try {
                val page=LocalContextSearch(store,text).page(library,"fixture",MediaQuery("a smiling face"),null)
                assertEquals(setOf("image/png","image/gif","video/mp4","video/webm"),page.items.map { it.mimeType }.toSet())
            } finally { text.close() }
        } finally { FolderIndexer.change(app,id) { store.delete(id) }; files.deleteRecursively() }
    }
    @Test fun folderAccessSurvivesLibraryRemovalAndDetectsChangedFiles()=runBlocking {
        val store=LocalStore.get(app); val id=UUID.randomUUID().toString()
        val storage=ModelStorage.get(app,model)!!
        val saved=store.save(id,"Temporary",listOf(Folder("content://fixture/tree/root","Reactions")))
        store.delete(saved.id); releaseUnusedGrants(app,store)
        assertTrue("Library cleanup revoked model grant",storage.ready())
        // Re-selecting the same external folder verifies it again, independent of a private ready marker.
        ClipModels.select(app,model,storage.tree)
        withTimeout(30_000) { while(!storage.ready()) delay(50) }
        assertTrue(ClipModels.ready(app,model))
    }
}
