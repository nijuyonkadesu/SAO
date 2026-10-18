package one.njk.sao.extension.local

import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import one.njk.sao.source.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

/** Picker collections use the same durable index and streaming path as folder collections. */
class SelectedMediaDeviceTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val app=instrumentation.targetContext
    private val store=LocalStore.get(app)
    private val id=UUID.randomUUID().toString()
    private val root="selection:$id"
    private val folder=Folder(root,"Picked reactions",kind=CollectionKind.SELECTION)
    private val directory=File(app.filesDir,"test-documents/selected-$id")
    private fun uri(name: String)=DocumentsContract.buildDocumentUri("${app.packageName}.fixtures","root/selected-$id/$name").toString()
    @After fun cleanup()=runBlocking { FolderIndexer.change(app,id) { store.delete(id) }; directory.deleteRecursively(); Unit }
    @Test fun selectedImagesAnimationsAndVideosIndexAndExportWithoutTreeAccess()=runBlocking {
        directory.mkdirs()
        val names=listOf("happy.png","hello.gif","preview-video.mp4","preview-video.webm")
        names.forEach { name -> instrumentation.context.assets.open(name).use { input -> File(directory,name).outputStream().use { input.copyTo(it) } } }
        val library=store.save(id,"Picked media",listOf(folder),selections=mapOf(root to names.map(::uri)))
        assertEquals(CollectionKind.SELECTION,store.library(id).folders.single().kind)
        FolderIndexer.schedule(app,library,force=true)
        withTimeout(60_000) { while(store.overview(id).total.let { it.total!=4 || it.pending!=0 || it.scanning }) delay(50) }
        val status=store.overview(id).total
        assertEquals(0,status.failed); assertEquals(2,status.images); assertEquals(2,status.videos); assertEquals(1,status.animated)
        val rows=store.search(id,MediaQuery())
        assertEquals(names.toSet(),rows.map { it.filename }.toSet())
        rows.forEach { asset -> assertArrayEquals(File(directory,asset.filename).readBytes(),app.contentResolver.openInputStream(Uri.parse(asset.uri))!!.use { it.readBytes() }) }
        assertTrue(store.search(id,MediaQuery("smile",mode=SearchMode.DESCRIPTION)).any { it.filename=="happy.png" })
        val before=store.library(id).revision
        FolderIndexer.schedule(app,store.library(id),force=true)
        withTimeout(15_000) { while(store.overview(id).total.let { it.pending!=0 || it.scanning }) delay(50) }
        assertEquals(before,store.library(id).revision)
    }
    @Test fun appendingDeduplicatesAndPreservesCorrectionsAndMembership() {
        val first=store.save(id,"Picked media",listOf(folder),selections=mapOf(root to listOf(uri("happy.png"))))
        store.discovered(id,"fixture",root,uri("happy.png"),"happy.png","Picked/happy.png","image/png",1,20,first.revision)
        val asset=store.search(id,MediaQuery()).single()
        store.corrections(asset.id,id,"My image","A description","cat",ocr="Corrected text")
        val second=store.save(id,"Picked media",listOf(folder),selections=mapOf(root to listOf(uri("happy.png"),uri("hello.gif"),uri("hello.gif"))))
        assertNotEquals(first.revision,second.revision)
        assertEquals(2,store.selectedMedia(id,root).size)
        assertEquals("My image",store.asset(asset.id,id).displayTitle)
        assertEquals("Corrected text",store.asset(asset.id,id).effectiveOcr)
        val third=store.save(id,"Renamed",second.folders,selections=mapOf(root to listOf(uri("hello.gif"))))
        assertEquals(second.revision,third.revision)
        store.pauseFolder(id,root,true); assertTrue(store.library(id).folders.single().paused)
        store.pauseFolder(id,root,false); store.restartFolder(id,root)
        assertEquals(root,store.directory(id)!!.root)
        assertEquals("My image",store.asset(asset.id,id).displayTitle)
        store.delete(id); assertTrue(store.selectedUris(id).isEmpty())
    }
    @Test fun selectedDiscoveryCheckpointsHaveStableBoundedPages() {
        val uris=(1..245).map { uri("$it.png") }
        val library=store.save(id,"Many selected",listOf(folder),selections=mapOf(root to uris))
        val generation=store.scan(library)
        val work=store.directory(id)!!
        assertEquals("selected",work.document)
        assertEquals(100,store.selectedMedia(id,root).size)
        store.checkpoint(id,work,100)
        assertEquals(uris[100],store.selectedMedia(id,root,store.directory(id)!!.position).first().uri)
        store.pauseFolder(id,root,true); assertNull(store.directory(id))
        store.pauseFolder(id,root,false); assertEquals(100,store.directory(id)!!.position)
        store.directoryDone(id,work); store.scanFinished(id,generation)
        assertTrue(store.library(id).folders.single().lastScan>0)
    }
}
