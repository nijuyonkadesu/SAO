package one.njk.sao.extension.local

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import one.njk.sao.source.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

class LocalLibraryDeviceTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val app=instrumentation.targetContext
    private val store=LocalStore.get(app)
    private val ids=mutableListOf<String>()
    private val root=DocumentsContract.buildTreeDocumentUri("${app.packageName}.fixtures","root").toString()
    private fun library(name: String="Fixture"): Library {
        val id=UUID.randomUUID().toString(); ids+=id
        val value=store.save(id,name,listOf(Folder(root,"Reactions"))); store.pause(id,true); return value.copy(paused=true)
    }
    private fun add(library: Library, name: String, time: Long=1, generation: String="fixture", mime: String="image/png"): LocalAsset {
        val uri=DocumentsContract.buildDocumentUriUsingTree(Uri.parse(root),"root/$name").toString()
        store.discovered(library.id,generation,root,uri,name,"Reactions/$name",mime,time,32,library.revision)
        return store.search(library.id,MediaQuery(name,mode=SearchMode.FILENAME)).single()
    }
    @After fun cleanup() { ids.forEach { FolderIndexer.cancel(app,it); store.delete(it) } }
    @Test fun metadataSearchKeepsUnicodeAndModeBoundariesAndOverridesSurviveRescan() {
        val library=library(); val asset=add(library,"photo.png")
        store.analyzed(asset,Analysis(listOf("Cat","Comics"),"HELLO WORLD"),false,null)
        assertEquals(1,store.search(library.id,MediaQuery("cat hello",mode=SearchMode.DESCRIPTION)).size)
        assertTrue(store.search(library.id,MediaQuery("cat hello",mode=SearchMode.OCR)).isEmpty())
        assertEquals(1,store.search(library.id,MediaQuery("hello world",mode=SearchMode.OCR)).size)
        store.corrections(asset.id,library.id,"My reaction","a skill issue","😂, 👩🏽‍💻",ocr="CORRECTED TEXT")
        assertEquals(1,store.search(library.id,MediaQuery("skill 😂",mode=SearchMode.DESCRIPTION)).size)
        assertEquals(1,store.search(library.id,MediaQuery("👩🏽‍💻",mode=SearchMode.DESCRIPTION)).size)
        assertTrue(store.search(library.id,MediaQuery("cat",mode=SearchMode.DESCRIPTION)).isEmpty())
        assertTrue(store.search(library.id,MediaQuery("hello",mode=SearchMode.OCR)).isEmpty())
        val changed=add(library,"photo.png",time=2)
        store.analyzed(changed,Analysis(listOf("Dog"),"new recognition"),false,null)
        val current=store.asset(asset.id,library.id)
        assertEquals("My reaction",current.displayTitle); assertEquals("CORRECTED TEXT",current.effectiveOcr)
        assertEquals(listOf("😂","👩🏽‍💻"),current.effectiveTags)
        store.corrections(asset.id,library.id,"","","",reset=true)
        assertEquals(listOf("Dog"),store.asset(asset.id,library.id).effectiveTags)
        assertEquals("new recognition",store.asset(asset.id,library.id).effectiveOcr)
    }
    @Test fun pagingAndSequenceKeepEveryItemWithTiedSavedTimesAndExactAnchor() {
        val library=library(); val all=(0..144).map { add(library,"reaction-$it.png",time=(it/3).toLong()) }
        val catalog=LocalCatalog(store,library,"source")
        val received=mutableListOf<String>(); var cursor: String?=null
        do { val page=catalog.page(MediaQuery(),cursor); received+=page.items.map { it.remote!!.assetId }; cursor=page.nextCursor } while(cursor!=null)
        assertEquals(145,received.size); assertEquals(145,received.distinct().size)
        val anchor=catalog.item(all[73]); val initial=catalog.open(anchor)
        assertEquals(1,SourceWire.sequence(initial).items.count { it.remote!!.assetId==anchor.remote!!.assetId })
        val ids=SourceWire.sequence(initial).items.map { it.remote!!.assetId }
        assertEquals(received.subList(51,92),ids)
        try { catalog.page(MediaQuery("different"),catalog.page(MediaQuery(),null).nextCursor); fail("Accepted wrong query cursor") } catch(_: ExtensionFailure) { }
    }
    @Test fun profilesAreIsolatedAndOnlyScopeChangesInvalidateTheirRevision() {
        val first=library(); val second=library("Other"); val asset=add(first,"same.png")
        assertTrue(store.search(second.id,MediaQuery()).isEmpty())
        val renamed=store.save(first.id,"Renamed",first.folders); assertEquals(first.revision,renamed.revision)
        val changed=store.save(first.id,"Renamed",first.folders+Folder(DocumentsContract.buildTreeDocumentUri("${app.packageName}.fixtures","other").toString(),"Other"))
        assertNotEquals(first.revision,changed.revision)
        try { add(first,"stale.png"); fail("Old indexer wrote after scope changed") } catch(_: CancellationException) { }
        assertEquals(asset.id,store.search(first.id,MediaQuery()).single().id)
    }
    @Test fun partialAndInaccessibleScansNeverPruneExistingMetadata() {
        val library=library(); add(library,"retained.png"); add(library,"missing.png")
        val generation=store.scan(library); add(library,"retained.png",generation=generation)
        assertEquals(2,store.status(library.id).total)
        store.folderError(library.id,root,"PERMISSION_REQUIRED"); store.scanFinished(library.id,generation)
        assertEquals(2,store.status(library.id).total)
        val next=store.scan(library); add(library,"retained.png",generation=next); store.scanFinished(library.id,next)
        assertEquals(1,store.status(library.id).total)
    }
    @Test fun analyzerStartupFailureIsVisibleAndRetryPreservesManualCorrections() {
        val library=library(); val asset=add(library,"reaction.png")
        store.corrections(asset.id,library.id,"My title","Corrected description","😂")
        store.pause(library.id,false); store.analysisUnavailable(library)
        assertEquals(0,store.status(library.id).pending); assertEquals(1,store.status(library.id).failed)
        assertEquals(1,store.search(library.id,MediaQuery("😂",mode=SearchMode.DESCRIPTION)).size)
        store.retryFailures(library.id); assertEquals(1,store.status(library.id).pending)
        store.analyzed(asset,Analysis(listOf("Smile"),"Automatic text"),false,null)
        assertEquals(0,store.status(library.id).failed)
        assertEquals("My title",store.asset(asset.id,library.id).displayTitle)
        assertEquals(listOf("😂"),store.asset(asset.id,library.id).effectiveTags)
    }
    @Test fun bundledModelsIndexRecursiveImagesGifAndVideosWithoutNetworkOrGmsSetup() = runBlocking {
        val files=File(app.filesDir,"test-documents").apply { deleteRecursively(); mkdirs() }
        File(files,"nested").mkdirs()
        for(name in listOf("happy.png","hello.gif","preview-video.mp4","preview-video.webm")) {
            instrumentation.context.assets.open(name).use { input -> File(files,"nested/$name").outputStream().use(input::copyTo) }
        }
        val bitmap=Bitmap.createBitmap(1000,400,Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply { drawColor(Color.WHITE); val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.BLACK; textSize=78f }
            drawText("SAO LOCAL INDEX",50f,130f,paint); drawText("HELLO WORLD",50f,270f,paint) }
        File(files,"text.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
        val library=library(); store.pause(library.id,false)
        FolderIndexer.schedule(app,store.library(library.id),true)
        withTimeout(120_000) { while(store.status(library.id).let { it.total!=5 || it.pending!=0 || it.scanning }) delay(100) }
        assertEquals(0,store.status(library.id).failed)
        val recognized=store.search(library.id,MediaQuery("hello world",mode=SearchMode.OCR)).single()
        assertTrue(store.search(library.id,MediaQuery("smile",mode=SearchMode.DESCRIPTION)).isNotEmpty())
        assertTrue(store.search(library.id,MediaQuery("😊",mode=SearchMode.DESCRIPTION)).isNotEmpty())
        assertEquals(2,store.search(library.id,MediaQuery(constraints=SearchConstraints(media=SearchMedia.VIDEOS))).size)
        // An unchanged file must retain its prior automatic analysis without re-running inference.
        store.analyzed(recognized,Analysis(listOf("Unchanged sentinel"),recognized.ocr),false,null)
        val priorScan=store.library(library.id).lastScan
        FolderIndexer.schedule(app,store.library(library.id),true)
        withTimeout(30_000) { while(store.library(library.id).lastScan==priorScan || store.status(library.id).scanning) delay(100) }
        assertEquals("Unchanged sentinel",store.asset(recognized.id,library.id).labels)
        assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED,app.checkSelfPermission(android.Manifest.permission.INTERNET))
    }
    @Test fun prepareHostFixture() = runBlocking {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("prepareLocalFixture")=="true")
        val files=File(app.filesDir,"test-documents").apply { mkdirs() }
        for(name in listOf("happy.png","hello.gif","preview-video.mp4","preview-video.webm")) {
            instrumentation.context.assets.open(name).use { input -> File(files,name).outputStream().use(input::copyTo) }
        }
        val library=store.save(HOST_PROFILE,"Local test library",listOf(Folder(root,"Reactions")))
        val priorScan=store.library(library.id).lastScan
        store.pause(library.id,false); FolderIndexer.schedule(app,library.copy(paused=false),true)
        withTimeout(120_000) { while(store.library(library.id).lastScan==priorScan || store.status(library.id).let { it.total<4 || it.pending!=0 || it.scanning }) delay(100) }
        // Retained only for the explicitly requested host test, not part of the ordinary suite.
    }
    @Test fun removeHostFixture() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("removeLocalFixture")=="true")
        FolderIndexer.cancel(app,HOST_PROFILE); store.delete(HOST_PROFILE)
    }
    companion object { const val HOST_PROFILE="de89d2d4-3110-4fb4-894b-5cef5e304e70" }
}
