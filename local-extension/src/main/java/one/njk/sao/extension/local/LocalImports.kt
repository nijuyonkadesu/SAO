package one.njk.sao.extension.local

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import one.njk.sao.source.*

/** Local write permissions, atomic publication and idempotent receipts belong to this extension. */
internal class LocalImports(private val context: Context, private val store: LocalStore, private val library: Library) {
    private fun folders() = library.folders.filter { folder -> folder.kind==CollectionKind.TREE &&
        context.contentResolver.persistedUriPermissions.any { it.uri.toString()==folder.uri && it.isWritePermission } }
    suspend fun options(): ImportOptions {
        val writable=folders().filter { folder ->
            val tree=Uri.parse(folder.uri)
            val root=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree))
            try { context.contentResolver.query(root,arrayOf(DocumentsContract.Document.COLUMN_FLAGS),null,null,null)?.use {
                it.moveToFirst() && it.getInt(0) and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE != 0
            }==true } catch(_: SecurityException) { false }
        }
        val overview=store.overview(library.id)
        return ImportOptions(LocalFormats.mimeTypes,writable.map { ImportTarget(digest(it.uri),it.name) },
            collections=library.folders.map { folder ->
                val index=overview.folders[folder.uri]
                ImportCollection(folder.name,context.getString(if(folder.kind==CollectionKind.TREE) R.string.import_collection_folder else R.string.import_collection_picked,
                    index?.total ?: 0,index?.pending ?: 0))
            },settingsLabel=context.getString(R.string.import_settings))
    }
    suspend fun copy(request: MediaImportRequest): ImportReceipt = lock.withLock {
        val folder=folders().firstOrNull { digest(it.uri)==request.target } ?: throw ExtensionFailure("PERMISSION_REQUIRED")
        if(LocalFormats.mime(request.filename,request.mime)==null) throw ExtensionFailure("UNSUPPORTED_MEDIA")
        val db=Receipts(context).writableDatabase
        try {
            var desired: String?=null
            val saved=db.query("receipts",null,"id=?",arrayOf(request.id),null,null,null).use { c ->
                if(!c.moveToFirst()) null else {
                    if(c.getString(1)!=library.id || c.getString(2)!=request.target || c.getString(3)!=request.checksum) throw ExtensionFailure("IMPORT_CONFLICT")
                    desired=c.getString(6)
                    Uri.parse(c.getString(4)) to (c.getInt(5)!=0)
                }
            }
            if(saved?.second==true) return@withLock ImportReceipt(digest(saved.first.toString()),true)
            val tree=Uri.parse(folder.uri)
            val root=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree))
            val pending=".sao-${request.id}.pending"
            val names=mutableSetOf<String>(); var recovered: Uri?=null; var published: Uri?=null
            MediaDocuments.children(context.contentResolver,tree,DocumentsContract.getTreeDocumentId(tree)) {
                names+=it.name; if(it.name==pending) recovered=it.uri
                if(it.name==desired) published=it.uri
            }
            if(published!=null) {
                // Recover a crash after rename but before the receipt commit, verifying only this candidate.
                copyImport(context,request.copy(uri=requireNotNull(published)),Discard,currentCoroutineContext())
                register(folder,requireNotNull(published))
                db.update("receipts",ContentValues().apply { put("uri",published.toString()); put("complete",1) },"id=?",arrayOf(request.id))
                return@withLock ImportReceipt(digest(published.toString()),true)
            }
            val uri=saved?.first ?: recovered ?: DocumentsContract.createDocument(context.contentResolver,root,"application/octet-stream",pending)
                ?: throw ExtensionFailure("TARGET_UNAVAILABLE")
            db.insertWithOnConflict("receipts",null,ContentValues().apply {
                put("id",request.id); put("profile",library.id); put("target",request.target); put("checksum",request.checksum)
                put("uri",uri.toString()); put("complete",0)
            },SQLiteDatabase.CONFLICT_REPLACE)
            context.contentResolver.openOutputStream(uri,"rwt")?.use { copyImport(context,request,it,currentCoroutineContext()) }
                ?: throw ExtensionFailure("TARGET_UNAVAILABLE")
            val preferred=LocalFormats.importFilename(request.filename,request.mime)
            val filename=if(preferred !in names) preferred else {
                val ext=preferred.substringAfterLast('.',""); val base=preferred.substringBeforeLast('.',preferred)
                "${base.take(200)} (${request.id.take(8)})"+if(ext.isBlank()) "" else ".$ext"
            }
            db.update("receipts",ContentValues().apply { put("final_name",filename) },"id=?",arrayOf(request.id))
            // Never expose a partial media file. Providers without rename support fail with the pending file retained for retry.
            val finished=DocumentsContract.renameDocument(context.contentResolver,uri,filename) ?: throw ExtensionFailure("TARGET_UNAVAILABLE")
            register(folder,finished)
            db.update("receipts",ContentValues().apply { put("uri",finished.toString()); put("complete",1) },"id=?",arrayOf(request.id))
            ImportReceipt(digest(finished.toString()))
        } finally { db.close() }
    }
    suspend fun cancel(ids: List<String>) = lock.withLock {
        require(ids.size<=100)
        val db=Receipts(context).writableDatabase
        try { for(id in ids) {
            java.util.UUID.fromString(id)
            val uri=db.rawQuery("SELECT uri FROM receipts WHERE id=? AND profile=? AND complete=0",arrayOf(id,library.id)).use {
                if(it.moveToFirst()) Uri.parse(it.getString(0)) else null
            } ?: continue
            try {
                val entry=MediaDocuments.details(context.contentResolver,uri)
                if(entry.name==".sao-$id.pending") DocumentsContract.deleteDocument(context.contentResolver,uri)
                db.delete("receipts","id=? AND complete=0",arrayOf(id))
            } catch(_: java.io.FileNotFoundException) { db.delete("receipts","id=? AND complete=0",arrayOf(id)) }
        } } finally { db.close() }
    }
    suspend fun abandon() {
        if(!context.getDatabasePath("import-receipts.db").exists()) return
        val db=Receipts(context).readableDatabase
        val ids=try { db.rawQuery("SELECT id FROM receipts WHERE profile=? AND complete=0",arrayOf(library.id)).use { c ->
            buildList { while(c.moveToNext()) add(c.getString(0)) }
        } } finally { db.close() }
        ids.chunked(100).forEach { batch ->
            try { cancel(batch) }
            catch(cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch(_: Exception) { /* A revoked provider cannot be cleaned; profile removal still works. */ }
        }
    }
    private suspend fun register(folder: Folder,uri: Uri) {
        val entry=MediaDocuments.details(context.contentResolver,uri)
        store.imported(library,folder,entry.copy(mime=LocalFormats.mime(entry.name,entry.mime) ?: throw ExtensionFailure("UNSUPPORTED_MEDIA")))
    }
    private object Discard: java.io.OutputStream() {
        override fun write(value: Int)=Unit
        override fun write(bytes: ByteArray,offset: Int,length: Int)=Unit
    }
    private class Receipts(context: Context): SQLiteOpenHelper(context,"import-receipts.db",null,1) {
        override fun onCreate(db: SQLiteDatabase) { db.execSQL("CREATE TABLE receipts(id TEXT PRIMARY KEY,profile TEXT NOT NULL,target TEXT NOT NULL,checksum TEXT NOT NULL,uri TEXT NOT NULL,complete INTEGER NOT NULL,final_name TEXT)") }
        override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) = Unit
    }
    companion object { private val lock=Mutex() }
}
