package one.njk.sao.extension.local

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.os.SystemClock
import android.provider.DocumentsContract
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** One cancellable discovery/analysis pass. Checkpoints survive process death and constrained stops. */
class FolderIndexer(context: Context, parameters: WorkerParameters) : CoroutineWorker(context,parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val id=inputData.getString(PROFILE) ?: return@withContext Result.failure()
        serial.withLock { control(id).withLock index@{
        val revision=inputData.getString(REVISION) ?: return@index Result.failure()
        val store=LocalStore.get(applicationContext)
        val library=try { store.library(id) } catch (_: one.njk.sao.source.ExtensionFailure) { return@index Result.success() }
        if(library.revision!=revision || library.paused) return@index Result.success()
        if(inputData.getBoolean(RESET,false)) store.beginIndexRequest(library,this@FolderIndexer.id.toString())
        val deadline=SystemClock.elapsedRealtime()+120_000
        var scanning=!inputData.getBoolean(ANALYSIS_ONLY,false)
        try {
            if(scanning) {
                val generation=store.scan(library)
                while(true) {
                    ensureActive(); if(!current(store,library)) return@index Result.success()
                    val directory=store.directory(id) ?: break
                    if(SystemClock.elapsedRealtime()>=deadline) { continueLater(library,false); return@index Result.success() }
                    try { discover(store,library,directory,generation,deadline) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: SliceFinished) { continueLater(library,false); return@index Result.success() }
                    catch (_: SecurityException) { store.folderError(id,directory.root,"PERMISSION_REQUIRED") }
                    catch (_: Exception) { store.folderError(id,directory.root,"FOLDER_UNAVAILABLE") }
                    store.directoryDone(id,directory)
                }
                store.scanFinished(id,generation); scanning=false
            }
            val contextReady=library.contextSearch && ClipModels.ready(applicationContext)
            var embeddingAfter=0L
            while(true) {
                ensureActive(); if(!current(store,library)) return@index Result.success()
                val labels=store.pending(id)
                val contextual=if(contextReady) store.pendingEmbedding(id,embeddingAfter) else null
                if(contextReady && contextual==null) embeddingAfter=store.lastAssetId(id)
                if(labels==null && contextual==null) break
                if(SystemClock.elapsedRealtime()>=deadline) { continueLater(library,true); return@index Result.success() }
                // Complete both stages for early files before moving through the rest of a large album.
                // Close one model before loading the other to bound native memory use.
                if(contextual!=null && (labels==null || contextual.id<labels.id)) {
                    val encoder=try { ClipEncoder(applicationContext,true) }
                        catch (_: Exception) { store.embeddingsUnavailable(library); continue }
                        catch (_: LinkageError) { store.embeddingsUnavailable(library); continue }
                    encoder.use {
                        var next: LocalAsset?=contextual
                        for(index in 0 until if(labels==null) Int.MAX_VALUE else 32) {
                            ensureActive(); if(!current(store,library)) return@index Result.success()
                            val asset=next ?: break
                            if(SystemClock.elapsedRealtime()>=deadline) { continueLater(library,true); return@index Result.success() }
                            try {
                                val vector=encoder.image(applicationContext,asset)
                                ensureActive(); if(!current(store,library)) return@index Result.success()
                                store.embedded(asset,vector,null)
                            } catch(cancelled: CancellationException) { throw cancelled }
                            catch(_: SecurityException) { store.embedded(asset,null,"PERMISSION_REQUIRED") }
                            catch(_: Exception) { store.embedded(asset,null,"EMBEDDING_FAILED") }
                            embeddingAfter=asset.id
                            next=store.pendingEmbedding(id,embeddingAfter)
                        }
                    }
                } else {
                    val analyzer=try { MediaAnalyzer() }
                        catch (_: Exception) { store.analysisUnavailable(library); continue }
                        catch (_: LinkageError) { store.analysisUnavailable(library); continue }
                    analyzer.use {
                        var next: LocalAsset?=labels
                        for(index in 0 until if(contextual==null) Int.MAX_VALUE else 32) {
                            ensureActive(); if(!current(store,library)) return@index Result.success()
                            val asset=next ?: break
                            if(SystemClock.elapsedRealtime()>=deadline) { continueLater(library,true); return@index Result.success() }
                            var animated=asset.animated
                            try {
                                animated=MediaBitmaps.animated(applicationContext,asset)
                                val analysis=analyzer.analyze(applicationContext,asset)
                                ensureActive(); if(!current(store,library)) return@index Result.success()
                                store.analyzed(asset,analysis,animated,null)
                            } catch(cancelled: CancellationException) { throw cancelled }
                            catch(_: SecurityException) { store.analyzed(asset,null,animated,"PERMISSION_REQUIRED") }
                            catch(_: Exception) { store.analyzed(asset,null,animated,"ANALYSIS_FAILED") }
                            next=store.pending(id)
                        }
                    }
                }
            }
            Result.success()
        } finally { store.signal(id) }
    } } }
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
        private val controls=ConcurrentHashMap<String,Mutex>()
        private fun control(id: String)=controls.getOrPut(id) { Mutex() }
        /** Stop this profile and finish its in-flight file before changing its processing scope. */
        suspend fun change(context: Context, id: String, block: () -> Unit) {
            WorkManager.getInstance(context).cancelUniqueWork(name(id)).result.get()
            control(id).withLock { block() }
        }
        private val requested=ConcurrentHashMap<String,Long>()
        private fun name(id: String)="local-index-$id"
        fun observe(context: Context, id: String) = WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(name(id)).map { work ->
            when {
                work.any { it.state==WorkInfo.State.RUNNING } -> IndexWork.RUNNING
                work.any { it.state==WorkInfo.State.ENQUEUED || it.state==WorkInfo.State.BLOCKED } -> IndexWork.WAITING
                else -> IndexWork.IDLE
            }
        }
        private fun request(library: Library, analysisOnly: Boolean=false, reset: Boolean=false) = OneTimeWorkRequestBuilder<FolderIndexer>()
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).build())
            .setInputData(workDataOf(PROFILE to library.id,REVISION to library.revision,ANALYSIS_ONLY to analysisOnly,RESET to reset)).build()
        fun schedule(context: Context, library: Library, force: Boolean=false, reset: Boolean=force) {
            if(library.paused || library.folders.all { it.paused }) return
            val now=SystemClock.elapsedRealtime()
            if(!force && (System.currentTimeMillis()-library.lastScan<300_000 || requested[library.id]?.let { now-it<30_000 }==true)) return
            requested[library.id]=now
            WorkManager.getInstance(context).enqueueUniqueWork(name(library.id),if(force) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,request(library,reset=reset))
        }
        fun cancel(context: Context, id: String) { WorkManager.getInstance(context).cancelUniqueWork(name(id)); requested.remove(id) }
    }
}
