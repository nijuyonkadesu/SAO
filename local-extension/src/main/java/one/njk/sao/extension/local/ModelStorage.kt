package one.njk.sao.extension.local

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.InputStream
import java.security.MessageDigest

/** User-owned SAF files, read directly through seekable descriptors, never copied into internal storage. */
class ModelStorage(private val context: Context,val model: ClipModel,val tree: Uri) {
    private val resolver=context.contentResolver
    private fun child(parent: Uri,name: String,folder: Boolean,create: Boolean): Uri? {
        val children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,DocumentsContract.getDocumentId(parent))
        resolver.query(children,arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME),null,null,null)?.use { rows ->
            while(rows.moveToNext()) if(rows.getString(1)==name) return DocumentsContract.buildDocumentUriUsingTree(tree,rows.getString(0))
        }
        return if(create) DocumentsContract.createDocument(resolver,parent,if(folder) DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream",name) else null
    }
    fun document(path: String,create: Boolean=false): Uri? {
        var parent=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree))
        val names=path.split('/')
        names.forEachIndexed { index,name -> parent=child(parent,name,index<names.lastIndex,create) ?: return null }
        return parent
    }
    data class Info(val size: Long,val modified: Long)
    fun info(uri: Uri): Info=resolver.query(uri,arrayOf(DocumentsContract.Document.COLUMN_SIZE,DocumentsContract.Document.COLUMN_LAST_MODIFIED),null,null,null)!!.use {
        check(it.moveToFirst()); Info(it.getLong(0),it.getLong(1))
    }
    fun length(path: String)=document(path)?.let { info(it).size } ?: 0L
    fun input(uri: Uri): InputStream=requireNotNull(resolver.openInputStream(uri))
    fun descriptor(path: String): ParcelFileDescriptor {
        val descriptor=requireNotNull(resolver.openFileDescriptor(requireNotNull(document(path)),"r"))
        try { Os.lseek(descriptor.fileDescriptor,0,OsConstants.SEEK_SET); return descriptor }
        catch(error: Exception) { descriptor.close(); throw java.io.IOException("Choose a model folder on this device with seekable files",error) }
    }
    fun output(uri: Uri,offset: Long): ParcelFileDescriptor.AutoCloseOutputStream {
        val descriptor=requireNotNull(resolver.openFileDescriptor(uri,"rw"))
        try {
            Os.lseek(descriptor.fileDescriptor,offset,OsConstants.SEEK_SET)
            Os.ftruncate(descriptor.fileDescriptor,offset)
            return ParcelFileDescriptor.AutoCloseOutputStream(descriptor)
        } catch(error: Exception) { descriptor.close(); throw error }
    }
    suspend fun verify(uri: Uri,part: ClipModels.Part): Boolean = info(uri).size==part.size && input(uri).use { hash(it)==part.sha256 }
    private fun stamp(): String? {
        val entries=model.parts.map { part ->
            val uri=document(part.path) ?: return null
            val info=info(uri); if(info.size!=part.size) return null
            "$uri:${info.size}:${info.modified}"
        }
        return model.id+"|"+tree+"|"+entries.joinToString("|")
    }
    fun ready(): Boolean=try { stamp()?.let { it==preferences(context).getString("verified:${model.id}",null) }==true } catch(_: Exception) { false }
    suspend fun validate(): Boolean {
        for(part in model.parts) {
            currentCoroutineContext().ensureActive()
            val uri=document(part.path) ?: return false
            if(!verify(uri,part)) return false
            descriptor(part.path).close()
        }
        val stamp=requireNotNull(stamp())
        check(preferences(context).edit().putString("verified:${model.id}",stamp).commit())
        return true
    }
    companion object {
        private fun preferences(context: Context)=context.getSharedPreferences("context-models",Context.MODE_PRIVATE)
        fun tree(context: Context,model: ClipModel): Uri?=preferences(context).getString("tree:${model.id}",null)?.let(Uri::parse)
        fun get(context: Context,model: ClipModel)=tree(context,model)?.let { ModelStorage(context,model,it) }
        fun usedTrees(context: Context)=ClipCatalog.all.mapNotNull { tree(context,it)?.toString() }.toSet()
        fun select(context: Context,model: ClipModel,tree: Uri) {
            require(DocumentsContract.isTreeUri(tree))
            // Read-only folders can supply already-downloaded models; downloads need a writable folder.
            try { context.contentResolver.takePersistableUriPermission(tree,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            catch(_: SecurityException) { context.contentResolver.takePersistableUriPermission(tree,Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            check(preferences(context).edit().putString("tree:${model.id}",tree.toString()).remove("verified:${model.id}").commit())
        }
        internal suspend fun hash(input: InputStream): String {
            val digest=MessageDigest.getInstance("SHA-256"); val buffer=ByteArray(256*1024)
            while(true) { currentCoroutineContext().ensureActive(); val count=input.read(buffer); if(count<0) break; digest.update(buffer,0,count) }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
