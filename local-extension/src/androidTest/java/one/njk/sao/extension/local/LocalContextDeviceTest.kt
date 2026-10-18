package one.njk.sao.extension.local

import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import one.njk.sao.source.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID
import kotlin.math.sqrt

/** Opt-in official model fixtures, never private photos or a remote Immich server. */
class LocalContextDeviceTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val app=instrumentation.targetContext
    private val store=LocalStore.get(app)
    private val profiles=mutableListOf<String>()
    @Before fun requireModels() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("clipModelFixtures")=="true")
        assertTrue("Install the checksum-pinned model fixtures before this suite",ClipModels.ready(app))
    }
    @After fun cleanup()=runBlocking { profiles.forEach { FolderIndexer.change(app,it) { store.delete(it) } } }
    private fun library(document: String="root"): Library {
        val root=DocumentsContract.buildTreeDocumentUri("${app.packageName}.fixtures",document).toString()
        val id=UUID.randomUUID().toString(); profiles+=id
        val library=store.save(id,"Context fixture",listOf(Folder(root,"Reactions")),contextSearch=true)
        store.pause(id,true); return library.copy(paused=true)
    }
    private fun add(library: Library,name: String,mime: String="image/png"): LocalAsset {
        val root=library.folders.single().uri
        val uri=DocumentsContract.buildDocumentUriUsingTree(Uri.parse(root),DocumentsContract.getTreeDocumentId(Uri.parse(root))+"/$name").toString()
        store.discovered(library.id,"fixture",root,uri,name,"Reactions/$name",mime,1,32,library.revision)
        return store.search(library.id,MediaQuery(name,mode=SearchMode.FILENAME)).single()
    }
    private fun references()=JSONObject(instrumentation.context.assets.open("clip-reference.json").bufferedReader().use { it.readText() })
    private fun cosine(first: FloatArray,second: FloatArray): Double {
        val dot=first.indices.sumOf { first[it].toDouble()*second[it] }
        return dot/sqrt(first.sumOf { it.toDouble()*it }*second.sumOf { it.toDouble()*it })
    }
    @Test fun tokenizerAndTextEncoderMatchImmichTokenizerAndOfficialOnnxOutputs()=runBlocking {
        val tokenizer=ClipTokenizer(ClipModels.tokenizer(app,ClipCatalog.immich))
        val cases=references().getJSONArray("textual")
        ClipEncoder(app,false).use { encoder ->
            for(index in 0 until cases.length()) {
                val fixture=cases.getJSONObject(index); val text=fixture.getString("text")
                val tokens=fixture.getJSONArray("ids")
                assertArrayEquals(text,IntArray(77) { tokens.getInt(it) },tokenizer.encode(text))
                val vector=fixture.getJSONArray("vector")
                assertTrue(text,cosine(FloatArray(512) { vector.getDouble(it).toFloat() },encoder.text(text))>.999999)
            }
        }
    }
    @Test fun imageEncoderMatchesImmichBicubicAndNormalizationOnBundledImage()=runBlocking {
        val files=File(app.filesDir,"test-documents").apply { mkdirs() }
        instrumentation.context.assets.open("happy.png").use { input -> File(files,"clip-happy.png").outputStream().use(input::copyTo) }
        val asset=add(library(),"clip-happy.png")
        val expected=references().getJSONArray("happy")
        ClipEncoder(app,true).use { encoder ->
            val vector=encoder.image(app,asset)
            val similarity=cosine(FloatArray(512) { expected.getDouble(it).toFloat() },vector)
            assertTrue("Image embedding differs from reference: $similarity",similarity>.9999)
            assertEquals(1.0,vector.sumOf { it.toDouble()*it },.00001)
        }
    }
    @Test fun cosinePaginationHasNoHiddenLimitKeepsTiesAndHonorsMediaFilters()=runBlocking {
        val library=library()
        val text=ClipTextSession(app)
        try {
            val vector=text.encode("happy person wearing a blue shirt")
            val expected=(0 until 145).map { index ->
                val asset=add(library,"asset-$index.${if(index%3==0) "mp4" else "png"}",if(index%3==0) "video/mp4" else "image/png")
                store.embedded(asset,vector,null); asset
            }
            val search=LocalContextSearch(store,text)
            val query=MediaQuery("happy person wearing a blue shirt")
            val received=mutableListOf<String>(); var cursor: String?=null
            do { val page=search.page(library,"fixture",query,cursor); received+=page.items.map { it.remote!!.assetId }; cursor=page.nextCursor } while(cursor!=null)
            assertEquals(expected.map { it.id.toString() },received)
            assertEquals(145,received.distinct().size)
            val videos=search.page(library,"fixture",query.copy(constraints=SearchConstraints(media=SearchMedia.VIDEOS)),null)
            assertEquals(49,videos.items.size); assertTrue(videos.items.all { it.isVideo })
            val next=search.page(library,"fixture",query,null).nextCursor!!
            try { search.page(library,"fixture",query.copy(text="different"),next); fail("Accepted cursor for another query") }
            catch(_: ExtensionFailure) { }
            val other=library(); assertTrue(search.page(other,"other",query,null).items.isEmpty())
        } finally { text.close() }
    }
    @Test fun changedFilesInvalidateEmbeddingsAndAlbumRestartPreservesMetadataOverrides()=runBlocking {
        val library=library(); val asset=add(library,"reaction.png")
        store.embedded(asset,FloatArray(512) { if(it==0) 1f else 0f },null)
        assertEquals(1,store.overview(library.id).total.contextProcessed)
        store.discovered(library.id,"changed",asset.root,asset.uri,asset.filename,asset.path,asset.mime,2,64,library.revision)
        assertEquals(0,store.overview(library.id).total.contextProcessed)
        assertEquals(1,store.overview(library.id).total.contextPending)
        store.embedded(asset,FloatArray(512),null) // Stale inference must not overwrite the changed file.
        assertEquals(0,store.overview(library.id).total.contextProcessed)
        val changed=store.asset(asset.id,library.id)
        store.embedded(changed,null,"EMBEDDING_FAILED")
        assertEquals(1,store.overview(library.id).total.contextFailed)
        store.retryFailures(library.id); assertEquals(1,store.overview(library.id).total.contextPending)
        store.corrections(asset.id,library.id,"My title","Manual description","😂")
        FolderIndexer.change(app,library.id) { store.restartFolder(library.id,asset.root) }
        assertEquals("My title",store.asset(asset.id,library.id).displayTitle)
        assertEquals(listOf("😂"),store.asset(asset.id,library.id).effectiveTags)
    }
    @Test fun actualWorkerCreatesContextVectorsForImagesAnimationsAndVideos()=runBlocking {
        val files=File(app.filesDir,"test-documents").apply { mkdirs() }
        for(name in listOf("happy.png","hello.gif","preview-video.mp4","preview-video.webm")) {
            instrumentation.context.assets.open(name).use { input -> File(files,"clip-$name").outputStream().use(input::copyTo) }
        }
        val library=library(); store.pause(library.id,false)
        FolderIndexer.schedule(app,store.library(library.id),force=true)
        withTimeout(120_000) { while(store.overview(library.id).total.let { it.total<4 || it.scanning || it.pending>0 || it.contextPending>0 }) delay(100) }
        val status=store.overview(library.id).total
        assertEquals(0,status.contextFailed); assertTrue(status.contextProcessed>=4)
        val text=ClipTextSession(app)
        try {
            val page=LocalContextSearch(store,text).page(store.library(library.id),"fixture",MediaQuery("a smiling face"),null)
            assertTrue(page.items.isNotEmpty())
            assertEquals(status.total,page.items.size)
        } finally { text.close() }
    }
    @Test fun largeLibraryRankingUsesBoundedResultsAndCancellableStreaming()=runBlocking {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("localScaleCheck")=="true")
        val library=library(); val text=ClipTextSession(app)
        try {
            val vector=text.encode("a smiling face")
            val blob=java.nio.ByteBuffer.allocate(512*4).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply { asFloatBuffer().put(vector) }.array()
            val db=store.writableDatabase
            db.beginTransaction()
            try {
                db.compileStatement("INSERT INTO assets(profile,root,uri,filename,filename_search,path,path_search,mime,modified,size,signature,seen,pending) VALUES(?,?,?,?,?,?,?,'image/png',1,32,?,'fixture',0)").use { asset ->
                    db.compileStatement("INSERT INTO embeddings(asset,model,signature,vector) VALUES(?,?,?,?)").use { embedding ->
                        repeat(120_000) { index ->
                            asset.bindString(1,library.id); asset.bindString(2,library.folders.single().uri)
                            asset.bindString(3,"fixture:$index"); asset.bindString(4,"$index.png"); asset.bindString(5,"$index.png")
                            asset.bindString(6,"Reactions/$index.png"); asset.bindString(7,"Reactions/$index.png"); asset.bindString(8,"$index")
                            val id=asset.executeInsert()
                            embedding.bindLong(1,id); embedding.bindString(2,ClipModels.MODEL); embedding.bindString(3,"$index"); embedding.bindBlob(4,blob); embedding.executeInsert()
                        }
                    }
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            val start=android.os.SystemClock.elapsedRealtime()
            val search=LocalContextSearch(store,text)
            val first=search.page(library,"fixture",MediaQuery("a smiling face"),null)
            val elapsed=android.os.SystemClock.elapsedRealtime()-start
            assertEquals(60,first.items.size); assertNotNull(first.nextCursor)
            android.util.Log.i("LocalContextScale","120000 vectors, first 60: ${elapsed}ms")
            assertEquals(120_000,store.overview(library.id).total.contextProcessed)
            try { withTimeout(1) { search.page(library,"fixture",MediaQuery("a smiling face"),null) }; fail("Streaming rank did not cancel") }
            catch(_: TimeoutCancellationException) { }
        } finally { text.close() }
    }
    @Test fun contextAppearsBeforeOcrFinishesAndAlbumPauseResumesActualWork()=runBlocking {
        val name="interleave-${UUID.randomUUID()}"
        val folder=File(app.filesDir,"test-documents/$name").apply { mkdirs() }
        try {
            val bytes=instrumentation.context.assets.open("happy.png").use { it.readBytes() }
            repeat(65) { File(folder,"$it.png").writeBytes(bytes) }
            val library=library("root/$name"); store.pause(library.id,false)
            FolderIndexer.schedule(app,store.library(library.id),force=true)
            withTimeout(60_000) { while(store.overview(library.id).total.let { it.contextProcessed==0 || it.pending==0 }) delay(20) }
            FolderIndexer.change(app,library.id) { store.pauseFolder(library.id,library.folders.single().uri,true) }
            val paused=store.overview(library.id).total
            assertTrue(paused.paused); assertTrue(paused.contextProcessed>0); assertTrue(paused.pending>0)
            delay(300)
            assertEquals(paused,store.overview(library.id).total)
            FolderIndexer.change(app,library.id) { store.pauseFolder(library.id,library.folders.single().uri,false) }
            FolderIndexer.schedule(app,store.library(library.id),force=true,reset=false)
            withTimeout(120_000) { while(store.overview(library.id).total.let { it.scanning || it.pending>0 || it.contextPending>0 }) delay(100) }
            assertEquals(65,store.overview(library.id).total.contextProcessed)
            assertEquals(0,store.overview(library.id).total.contextFailed)
        } finally { folder.deleteRecursively() }
    }
}
