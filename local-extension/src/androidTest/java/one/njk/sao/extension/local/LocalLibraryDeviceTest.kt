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
    private fun library(name: String="Fixture", folders: List<Folder> = listOf(Folder(root,"Reactions"))): Library {
        val id=UUID.randomUUID().toString(); ids+=id
        val value=store.save(id,name,folders); store.pause(id,true); return value.copy(paused=true)
    }
    private fun add(library: Library, name: String, time: Long=1, generation: String="fixture", mime: String="image/png", folder: String=root): LocalAsset {
        val uri=DocumentsContract.buildDocumentUriUsingTree(Uri.parse(folder),DocumentsContract.getTreeDocumentId(Uri.parse(folder))+"/$name").toString()
        store.discovered(library.id,generation,folder,uri,name,"Reactions/$name",mime,time,32,library.revision)
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
        store.folderError(library.id,root,"PERMISSION_REQUIRED")
        store.directoryDone(library.id,store.directory(library.id)!!); store.scanFinished(library.id,generation)
        assertEquals(2,store.status(library.id).total)
        val next=store.scan(library); add(library,"retained.png",generation=next)
        store.directoryDone(library.id,store.directory(library.id)!!); store.scanFinished(library.id,next)
        assertEquals(1,store.status(library.id).total)
    }
    @Test fun folderCountsSeparateAnimationsVideosPendingFailuresAndEmptyAlbums() {
        val other=DocumentsContract.buildTreeDocumentUri("${app.packageName}.fixtures","root/other").toString()
        val empty=DocumentsContract.buildTreeDocumentUri("${app.packageName}.fixtures","root/empty").toString()
        val library=library(folders=listOf(Folder(root,"Reactions"),Folder(other,"Clips"),Folder(empty,"Empty")))
        store.analyzed(add(library,"still.png"),Analysis(listOf("Smile"),""),false,null)
        store.analyzed(add(library,"animation.gif",mime="image/gif"),Analysis(emptyList(),""),true,null)
        store.analyzed(add(library,"broken.mp4",mime="video/mp4",folder=other),null,false,"ANALYSIS_FAILED")
        add(library,"remaining.webm",mime="video/webm",folder=other)
        val overview=store.overview(library.id)
        assertEquals(4,overview.total.total); assertEquals(2,overview.total.images)
        assertEquals(1,overview.total.animated); assertEquals(2,overview.total.videos)
        assertEquals(2,overview.total.processed); assertEquals(1,overview.total.pending); assertEquals(1,overview.total.failed)
        assertEquals(2,overview.folders.getValue(root).processed)
        assertEquals(0,overview.folders.getValue(other).processed)
        assertEquals(0,overview.folders.getValue(empty).total)
        store.folderError(library.id,empty,"PERMISSION_REQUIRED")
        assertTrue(store.overview(library.id).folders.getValue(empty).permissionRequired)
        assertFalse(store.overview(library.id).folders.getValue(root).permissionRequired)
    }
    @Test fun pausingOneAlbumPreservesItsCheckpointAndCannotPruneItsUnseenFiles() {
        val other=DocumentsContract.buildTreeDocumentUri("${app.packageName}.fixtures","root/other").toString()
        val library=library(folders=listOf(Folder(root,"Reactions"),Folder(other,"Clips")))
        val retained=add(library,"retained.png"); val active=add(library,"active.mp4",mime="video/mp4",folder=other)
        val generation=store.scan(library)
        val first=store.directory(library.id)!!; store.checkpoint(library.id,first,7)
        store.pauseFolder(library.id,root,true)
        assertEquals(other,store.directory(library.id)!!.root)
        assertEquals(active.id,store.pending(library.id)!!.id)
        store.discovered(library.id,generation,other,active.uri,active.filename,active.path,active.mime,active.modified,active.size,library.revision)
        store.directoryDone(library.id,store.directory(library.id)!!); store.scanFinished(library.id,generation)
        assertEquals(retained.id,store.asset(retained.id,library.id).id)
        assertTrue(store.overview(library.id).folders.getValue(root).paused)
        store.analyzed(active,Analysis(emptyList(),""),false,null)
        assertTrue(store.overview(library.id).total.paused)
        store.pauseFolder(library.id,root,false)
        assertEquals(7,store.directory(library.id)!!.position)
        assertEquals(generation,store.scan(store.library(library.id)))
        assertEquals(retained.id,store.pending(library.id)!!.id)
    }
    @Test fun restartingOneAlbumReprocessesOnlyItsFilesAndPreservesCorrections()=runBlocking {
        val other=DocumentsContract.buildTreeDocumentUri("${app.packageName}.fixtures","root/other").toString()
        val library=library(folders=listOf(Folder(root,"Reactions"),Folder(other,"Clips")))
        val first=add(library,"first.png"); val untouched=add(library,"other.png",folder=other)
        for(asset in listOf(first,untouched)) store.analyzed(asset,Analysis(listOf("Smile"),"Old text"),false,null)
        store.corrections(first.id,library.id,"My title","My description","😂",ocr="Corrected")
        store.pauseFolder(library.id,root,true)
        FolderIndexer.change(app,library.id) { store.restartFolder(library.id,root) }
        val current=store.asset(first.id,library.id)
        assertTrue(current.pending); assertEquals("My title",current.displayTitle)
        assertEquals(listOf("😂"),current.effectiveTags); assertEquals("Corrected",current.effectiveOcr)
        assertFalse(store.asset(untouched.id,library.id).pending)
        assertFalse(store.library(library.id).folders.first().paused)
        assertEquals(root,store.directory(library.id)!!.root)
        assertEquals(0,store.directory(library.id)!!.position)
        val renamed=store.save(library.id,"Renamed",store.library(library.id).folders)
        assertEquals(library.revision,renamed.revision)
    }
    @Test fun versionOneUpgradeKeepsLibraryMetadataAndExistingScanDates() {
        val file=File(app.cacheDir,"migration-${UUID.randomUUID()}.db")
        try { android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file,null).use { db ->
            db.execSQL("CREATE TABLE profiles(id TEXT PRIMARY KEY,last_scan INTEGER)")
            db.execSQL("CREATE TABLE folders(profile TEXT,uri TEXT,name TEXT,error TEXT)")
            db.execSQL("CREATE TABLE assets(id INTEGER PRIMARY KEY,profile TEXT,tags TEXT)")
            db.execSQL("INSERT INTO profiles VALUES('fixture',123)")
            db.execSQL("INSERT INTO folders VALUES('fixture','tree','Reactions',NULL)")
            db.execSQL("INSERT INTO assets VALUES(1,'fixture','😂')")
            store.onUpgrade(db,1,6)
            db.rawQuery("SELECT name,paused,last_scan FROM folders",null).use {
                assertTrue(it.moveToFirst()); assertEquals("Reactions",it.getString(0)); assertEquals(0,it.getInt(1)); assertEquals(123,it.getLong(2))
            }
            db.rawQuery("SELECT context_model FROM profiles",null).use { assertTrue(it.moveToFirst()); assertEquals(ClipModels.MODEL,it.getString(0)) }
            db.rawQuery("SELECT tags FROM assets",null).use { assertTrue(it.moveToFirst()); assertEquals("😂",it.getString(0)) }
        } } finally { file.delete() }
    }
    @Test fun inferenceDoesNotRegisterTelemetryUploadJobs() {
        val info=app.packageManager.getPackageInfo(app.packageName,android.content.pm.PackageManager.GET_SERVICES or
            android.content.pm.PackageManager.GET_RECEIVERS or android.content.pm.PackageManager.GET_META_DATA)
        assertFalse(info.services.orEmpty().any { it.name.endsWith("JobInfoSchedulerService") })
        assertFalse(info.receivers.orEmpty().any { it.name.endsWith("AlarmManagerSchedulerBroadcastReceiver") })
        val backend=info.services.orEmpty().firstOrNull { it.name.endsWith("TransportBackendDiscovery") }
        assertFalse(backend?.metaData?.containsKey("backend:com.google.android.datatransport.cct.CctBackendFactory")==true)
    }
    @Test fun constrainedWorkRestartKeepsItsCheckpointButNewRescanStartsFresh() {
        val library=library()
        store.beginIndexRequest(library,"same-job")
        val generation=store.scan(library)
        val directory=store.directory(library.id)!!; store.checkpoint(library.id,directory,73)
        store.beginIndexRequest(library,"same-job")
        assertEquals(generation,store.scan(library)); assertEquals(73,store.directory(library.id)!!.position)
        store.beginIndexRequest(library,"new-job")
        assertNotEquals(generation,store.scan(library)); assertEquals(0,store.directory(library.id)!!.position)
        val changed=store.save(library.id,library.name,library.folders,contextSearch=true)
        try { store.beginIndexRequest(library,"stale-job"); fail("Stale worker reset new configuration") }
        catch(_: CancellationException) { }
        assertEquals(changed.revision,store.library(library.id).revision)
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
        assertFalse(store.library(library.id).contextSearch)
    }
    @Test fun prepareHostFixture() = runBlocking {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("prepareLocalFixture")=="true")
        val files=File(app.filesDir,"test-documents").apply { mkdirs() }
        for(name in listOf("happy.png","hello.gif","preview-video.mp4","preview-video.webm")) {
            instrumentation.context.assets.open(name).use { input -> File(files,name).outputStream().use(input::copyTo) }
        }
        val context=InstrumentationRegistry.getArguments().getString("prepareContextFixture")=="true"
        if(context) assertTrue(ClipModels.ready(app))
        val library=store.save(HOST_PROFILE,"Local test library",listOf(Folder(root,"Reactions")),contextSearch=context)
        val priorScan=store.library(library.id).lastScan
        store.pause(library.id,false); FolderIndexer.schedule(app,library.copy(paused=false),true)
        withTimeout(120_000) { while(store.library(library.id).lastScan==priorScan || store.overview(library.id).total.let { it.total<4 || it.pending!=0 || it.scanning || context && it.contextPending>0 }) delay(100) }
        if(context) assertEquals(0,store.overview(library.id).total.contextFailed)
        // Retained only for the explicitly requested host test, not part of the ordinary suite.
    }
    @Test fun removeHostFixture() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("removeLocalFixture")=="true")
        FolderIndexer.cancel(app,HOST_PROFILE); store.delete(HOST_PROFILE)
    }
    companion object { const val HOST_PROFILE="de89d2d4-3110-4fb4-894b-5cef5e304e70" }
}
