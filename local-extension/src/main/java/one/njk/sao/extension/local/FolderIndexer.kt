package one.njk.sao.extension.local

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.os.SystemClock
import android.provider.DocumentsContract
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** One cancellable discovery/analysis pass. Checkpoints survive process death and constrained stops. */
class FolderIndexer(context: Context, parameters: WorkerParameters) : CoroutineWorker(context,parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) { serial.withLock {
        val id=inputData.getString(PROFILE) ?: return@withLock Result.failure()
        val revision=inputData.getString(REVISION) ?: return@withLock Result.failure()
        val store=LocalStore.get(applicationContext)
        val library=try { store.library(id) } catch (_: one.njk.sao.source.ExtensionFailure) { return@withLock Result.success() }
        if(library.revision!=revision || library.paused) return@withLock Result.success()
        if(inputData.getBoolean(RESET,false)) store.restartScan(id)
        val deadline=SystemClock.elapsedRealtime()+120_000
        var scanning=!inputData.getBoolean(ANALYSIS_ONLY,false)
        try {
            if(scanning) {
                val generation=store.scan(library)
                while(true) {
                    ensureActive(); if(!current(store,library)) return@withLock Result.success()
                    val directory=store.directory(id) ?: break
                    if(SystemClock.elapsedRealtime()>=deadline) { continueLater(library,false); return@withLock Result.success() }
                    try { discover(store,library,directory,generation,deadline) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: SliceFinished) { continueLater(library,false); return@withLock Result.success() }
                    catch (_: SecurityException) { store.folderError(id,directory.root,"PERMISSION_REQUIRED") }
                    catch (_: Exception) { store.folderError(id,directory.root,"FOLDER_UNAVAILABLE") }
                    store.directoryDone(id,directory)
                }
                store.scanFinished(id,generation); scanning=false
            }
            if(store.pending(id)==null) return@withLock Result.success()
            val engine=try { MediaAnalyzer() }
                catch (_: Exception) { store.analysisUnavailable(library); return@withLock Result.success() }
                catch (_: LinkageError) { store.analysisUnavailable(library); return@withLock Result.success() }
            engine.use { analyzer ->
                while(true) {
                    ensureActive(); if(!current(store,library)) return@withLock Result.success()
                    val asset=store.pending(id) ?: break
                    if(SystemClock.elapsedRealtime()>=deadline) { continueLater(library,true); return@withLock Result.success() }
                    try {
                        val animated=MediaBitmaps.animated(applicationContext,asset)
                        val analysis=analyzer.analyze(applicationContext,asset)
                        ensureActive(); if(!current(store,library)) return@withLock Result.success()
                        store.analyzed(asset,analysis,animated,null)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: SecurityException) { store.analyzed(asset,null,asset.animated,"PERMISSION_REQUIRED") }
                    catch (_: Exception) { store.analyzed(asset,null,asset.animated,"ANALYSIS_FAILED") }
                }
            }
            Result.success()
        } finally { store.signal(id) }
    } }
    private fun current(store: LocalStore, library: Library) = store.active(library.id,library.revision)
    private suspend fun discover(store: LocalStore, library: Library, directory: LocalStore.Directory, generation: String, deadline: Long) = coroutineScope {
        val uri=DocumentsContract.buildChildDocumentsUriUsingTree(Uri.parse(directory.root),directory.document)
        val columns=arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_LAST_MODIFIED,DocumentsContract.Document.COLUMN_SIZE)
        val signal=CancellationSignal()
        val closer=launch(Dispatchers.Default,start=CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { signal.cancel() } }
        try {
            val cursor=applicationContext.contentResolver.query(uri,columns,null,null,null,signal) ?: throw java.io.IOException("Folder unavailable")
            cursor.use { c ->
                if(directory.position>0) c.moveToPosition(directory.position-1)
                var position=directory.position
                try { while(c.moveToNext()) {
                ensureActive(); if(SystemClock.elapsedRealtime()>=deadline) throw SliceFinished()
                val document=c.getString(0); val name=c.getString(1).orEmpty(); val reported=c.getString(2)
                val path="${directory.path}/$name"
                if(reported==DocumentsContract.Document.MIME_TYPE_DIR) store.enqueue(library.id,LocalStore.Directory(directory.root,document,path))
                else LocalFormats.mime(name,reported)?.let { mime ->
                    val media=DocumentsContract.buildDocumentUriUsingTree(Uri.parse(directory.root),document)
                    store.discovered(library.id,generation,directory.root,media.toString(),name,path,mime,c.getLong(3),c.getLong(4),library.revision)
                }
                position++
                if(position%100==0) store.checkpoint(library.id,directory,position)
            } } finally { store.checkpoint(library.id,directory,position) }
            }
        } finally { closer.cancel() }
    }
    private fun continueLater(library: Library, analysisOnly: Boolean) {
        WorkManager.getInstance(applicationContext).enqueueUniqueWork(name(library.id),ExistingWorkPolicy.APPEND_OR_REPLACE,request(library,analysisOnly))
    }
    private class SliceFinished : Exception()
    companion object {
        private const val PROFILE="profile"
        private const val REVISION="revision"
        private const val ANALYSIS_ONLY="analysisOnly"
        private const val RESET="reset"
        private val serial=Mutex()
        private val requested=ConcurrentHashMap<String,Long>()
        private fun name(id: String)="local-index-$id"
        private fun request(library: Library, analysisOnly: Boolean=false, reset: Boolean=false) = OneTimeWorkRequestBuilder<FolderIndexer>()
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).build())
            .setInputData(workDataOf(PROFILE to library.id,REVISION to library.revision,ANALYSIS_ONLY to analysisOnly,RESET to reset)).build()
        fun schedule(context: Context, library: Library, force: Boolean=false) {
            if(library.paused) return
            val now=SystemClock.elapsedRealtime()
            if(!force && (System.currentTimeMillis()-library.lastScan<300_000 || requested[library.id]?.let { now-it<30_000 }==true)) return
            requested[library.id]=now
            WorkManager.getInstance(context).enqueueUniqueWork(name(library.id),if(force) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,request(library,reset=force))
        }
        fun cancel(context: Context, id: String) { WorkManager.getInstance(context).cancelUniqueWork(name(id)); requested.remove(id) }
    }
}
