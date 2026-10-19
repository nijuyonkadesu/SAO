package one.njk.sao.extension.local

import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.activity.result.contract.ActivityResultContracts
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class IndexEtaDeviceTest {
    @Test fun realIndexingPublishesAnEstimateAndCancellationClearsTransientMeasurements(): Unit=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation(); val app=instrumentation.targetContext
        val store=LocalStore.get(app); val id=UUID.randomUUID().toString()
        val relative="eta-$id"; val files=File(app.filesDir,"test-documents/$relative").apply { mkdirs() }
        val root=DocumentsContract.buildTreeDocumentUri("${app.packageName}.fixtures","root/$relative").toString()
        try {
            val bytes=instrumentation.context.assets.open("happy.png").use { it.readBytes() }
            repeat(256) { File(files,"reaction-$it.png").writeBytes(bytes) }
            val library=store.save(id,"ETA fixture",listOf(Folder(root,"Reactions")))
            FolderIndexer.schedule(app,library,force=true)
            val measured=withTimeout(60_000) { IndexTimings.current.first { it?.profile==id && it.labelMillis!=null }!! }
            val status=store.overview(id)
            assertTrue("Files remain when the rate warms up",status.total.pending>0)
            assertTrue(indexEstimates(status,IndexWork.RUNNING,measured).total is IndexEta.Remaining)
            assertTrue(indexEstimates(status,IndexWork.RUNNING,measured).folders[root] is IndexEta.Remaining)
            FolderIndexer.change(app,id) { store.pause(id,true) }
            assertNull(IndexTimings.current.value?.takeIf { it.profile==id })
            assertNull(indexEstimates(store.overview(id),IndexWork.IDLE,null).total)
        } finally {
            FolderIndexer.change(app,id) { store.delete(id) }
            files.deleteRecursively()
        }
    }
    @Test fun photoImportPrefersNativeAlbumsWithMultipleImagesAndVideos() {
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val intent=ActivityResultContracts.PickMultipleVisualMedia().createIntent(app,photoAlbumRequest())
        assertEquals(MediaStore.ACTION_PICK_IMAGES,intent.action)
        assertEquals(MediaStore.PICK_IMAGES_TAB_ALBUMS,intent.getIntExtra(MediaStore.EXTRA_PICK_IMAGES_LAUNCH_TAB,-1))
        assertTrue(intent.getIntExtra(MediaStore.EXTRA_PICK_IMAGES_MAX,0)>1)
    }
}
