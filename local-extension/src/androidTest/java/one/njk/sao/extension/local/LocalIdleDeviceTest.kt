package one.njk.sao.extension.local

import android.content.ComponentName
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import kotlinx.coroutines.runBlocking
import one.njk.sao.source.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class LocalIdleDeviceTest {
    @Test fun capabilitiesAndReactiveReadsNeverStartFolderScans()=runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val store=LocalStore.get(app)
        val tree=DocumentsContract.buildTreeDocumentUri("${app.packageName}.fixtures","root").toString()
        val library=store.save(UUID.randomUUID().toString(),"Idle fixture",listOf(Folder(tree,"Folder")))
        val client=ExtensionClient(app,ComponentName(app,LocalExtensionService::class.java))
        fun payload()=JSONObject().put("source","fixture").put("revision",library.revision)
        val work=WorkManager.getInstance(app)
        try {
            repeat(4) {
                client.request(library.id,ExtensionProtocol.CAPABILITIES,payload())
                client.request(library.id,ExtensionProtocol.SEARCH,payload().put("query",SourceWire.query(MediaQuery())))
            }
            assertTrue(work.getWorkInfosForUniqueWork("local-index-${library.id}").get().isEmpty())
            client.request(library.id,ExtensionProtocol.SEARCH,payload().put("query",SourceWire.query(MediaQuery())).put("discover",true))
            assertFalse(work.getWorkInfosForUniqueWork("local-index-${library.id}").get().isEmpty())
        } finally { FolderIndexer.cancel(app,library.id); store.delete(library.id) }
    }
}
