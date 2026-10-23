package one.njk.sao.importing

import android.content.ContextWrapper
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import one.njk.sao.source.MediaDocuments
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class ImportStoreDeviceTest {
    private val app=InstrumentationRegistry.getInstrumentation().targetContext
    private fun database(block: (ImportStore)->Unit) {
        val dir=File(app.cacheDir,"imports-${UUID.randomUUID()}").apply { mkdirs() }
        val store=ImportStore(object: ContextWrapper(app) { override fun getNoBackupFilesDir()=dir })
        try { block(store) } finally { store.close(); dir.deleteRecursively() }
    }
    private fun entry(name: String,time: Long)=MediaDocuments.Entry(Uri.parse("content://fixture/$name"),name,"image/png",42,time)
    @Test fun aNewFileWithAnOlderDateIsStillDiscoveredAndCompletedFilesAreNotRewritten()=database { store ->
        assertTrue(store.add("source","rev","album",entry("recent.png",100)))
        val first=store.next(false,network=false)!!; store.done(first.id,"remote-id")
        val version=store.readableDatabase.rawQuery("SELECT total_changes()",null).use { it.moveToFirst(); it.getLong(0) }
        assertFalse(store.add("source","rev","album",entry("recent.png",100)))
        val after=store.readableDatabase.rawQuery("SELECT total_changes()",null).use { it.moveToFirst(); it.getLong(0) }
        assertEquals(version,after)
        assertTrue(store.add("source","rev","album",entry("old-but-new.png",1)))
        assertEquals("old-but-new.png",store.next(false,network=false)!!.name)
        assertEquals(1,store.stats("source").done)
    }
    @Test fun changedFilesAndProfileRevisionsGetIndependentReceipts()=database { store ->
        store.add("source","rev1","album",entry("one.png",1)); store.done(store.next(false,network=false)!!.id,"id")
        store.add("source","rev1","album",entry("one.png",2))
        assertEquals(0,store.stats("source").done)
        store.add("source","rev2","album",entry("one.png",2))
        assertEquals(2,store.stats("source").pending)
    }
    @Test fun retriesKeepTheSnapshotAndPausingStopsOnlyQueuedWork()=database { store ->
        store.add("source","rev","album",entry("one.png",1)); val task=store.next(false,network=false)!!
        val file=File(app.cacheDir,"snapshot-${task.id}").apply { writeText("fixture") }
        try {
            store.snapshot(task.id,file,"a".repeat(40),file.length())
            store.failed(task,"NETWORK",System.currentTimeMillis()+3600_000)
            assertNull(store.next(false,network=false)); assertEquals(file.absolutePath,store.task(task.id)!!.staged)
            store.retry("source"); store.pause("source",true); assertNull(store.next(false,network=false))
            store.pause("source",false); assertEquals(task.id,store.next(false,network=false)!!.id)
        } finally { file.delete() }
    }
    @Test fun retriesAreBoundedAndRespectServerCooldowns()=database { store ->
        store.add("source","rev","album",entry("one.png",1)); val task=store.next(false,network=false)!!
        assertNull(MediaImports.retryAt(task,"HTTP_403",null))
        assertNull(MediaImports.retryAt(task.copy(attempts=4),"NETWORK",null))
        assertTrue(MediaImports.retryAt(task,"HTTP_429","3600")!! >= System.currentTimeMillis()+3590_000)
        assertNull(MediaImports.retryAt(task,"HTTP_429","999999999"))
        assertNull(MediaImports.retryAt(task,"HTTP_429","invalid"))
    }
    @Test fun offlineLocalTasksDoNotWaitForNetworkUploads()=database { store ->
        store.add("cloud","rev","album",entry("online.png",1),network=true)
        store.add("local","rev","folder",entry("offline.png",1),network=false)
        assertEquals("offline.png",store.next(false,network=false)!!.name)
        assertEquals("online.png",store.next(false,network=true)!!.name)
    }
    @Test fun folderTasksAreEligibleOnlyForTheAutomaticQueue()=database { store ->
        store.add("cloud","rev","album",entry("automatic.png",1),route="folder",network=true)
        assertNull(store.next(false))
        assertNotNull(store.next(true))
        assertNull(store.nextDelay(false))
        assertNotNull(store.nextDelay(true))
    }
    @Test fun offlineBatchCompletionDoesNotFinalizeNetworkUploads()=database { store ->
        store.add("cloud","rev","album",entry("online.png",1),network=true)
        store.add("local","rev","folder",entry("offline.png",1),network=false)
        store.done(store.next(false,network=true)!!.id,"cloud-asset")
        store.done(store.next(false,network=false)!!.id,"local-asset")
        assertEquals(listOf("local" to "rev"),store.unfinalized(network=false))
        assertEquals(listOf("cloud" to "rev"),store.unfinalized(network=true))
    }
    @Test fun connectedFolderRetainsItsNetworkPolicyAndLegacyRoutesRemainOnline()=database { store ->
        store.connect("local","rev","folder","Folder","content://fixture/local","Local",network=false)
        store.connect("cloud","rev","album","Album","content://fixture/cloud","Cloud",network=true)
        assertFalse(store.routes("local").single().network)
        assertTrue(store.routes("cloud").single().network)
        val file=File(app.cacheDir,"legacy-imports-${UUID.randomUUID()}.db")
        try { android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file,null).use { db ->
            db.execSQL("CREATE TABLE routes(id TEXT PRIMARY KEY)")
            db.execSQL("INSERT INTO routes VALUES('old-route')")
            store.onUpgrade(db,1,2)
            db.rawQuery("SELECT network FROM routes",null).use { assertTrue(it.moveToFirst()); assertEquals(1,it.getInt(0)) }
        } } finally { file.delete() }
    }
}
