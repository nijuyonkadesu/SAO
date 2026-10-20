package one.njk.sao.importing

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import one.njk.sao.floating.ExtensionConfig
import one.njk.sao.floating.SourceExtensions
import one.njk.sao.floating.SourceProfiles
import one.njk.sao.source.*
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@EntryPoint @InstallIn(SingletonComponent::class)
interface ImportAccess { fun imports(): MediaImports }
internal fun Context.imports() = EntryPointAccessors.fromApplication(applicationContext,ImportAccess::class.java).imports()

@Singleton
class MediaImports @Inject constructor(@ApplicationContext private val app: Context,
    private val profiles: SourceProfiles,private val extensions: SourceExtensions) {
    internal val store=ImportStore(app)
    private val transferLock=Mutex()
    private val localTransferLock=Mutex()
    private val scanLock=Mutex()
    private val active=java.util.concurrent.ConcurrentHashMap<String,String>()
    private val lifecycle=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    init {
        // Source removal is an event, not a timer. Selections and ordinary browsing do no cleanup work.
        lifecycle.launch { profiles.catalogs.map { it.profiles.map { profile -> profile.id }.toSet() }.distinctUntilChanged().collect { ids ->
            for(orphan in store.sources()-ids) transferLock.withLock { localTransferLock.withLock { scanLock.withLock {
                if(profiles.current().profiles.none { it.id==orphan }) {
                    val trees=store.routes(orphan).map { it.tree }
                    store.forget(orphan).forEach { releaseInput(Uri.parse(it)) }
                    trees.filter { tree -> store.routes().none { it.tree==tree } }.forEach { tree ->
                        try { app.contentResolver.releasePersistableUriPermission(Uri.parse(tree),Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch(_: SecurityException) { }
                    }
                    ImportJobs.configure(app,store.routes().isNotEmpty())
                }
            } } }
        } }
    }
    data class Destination(val source: SourceProfile,val options: ImportOptions)
    internal suspend fun types(uris: List<Uri>)=withContext(Dispatchers.IO) { uris.map { MediaDocuments.details(app.contentResolver,it).mime }.toSet() }
    suspend fun destinations(source: String?=null): List<Destination> = withContext(Dispatchers.IO) {
        val all=profiles.current().profiles.filter { it.configuration is ExtensionConfig && (source==null || it.id==source) }
        val requests=Dispatchers.IO.limitedParallelism(3)
        coroutineScope { all.map { profile -> async(requests) {
            try { Destination(profile,ImportWire.options(call(profile,ExtensionProtocol.IMPORT_OPTIONS))) }
            catch(cancelled: CancellationException) { throw cancelled }
            catch(error: ExtensionFailure) { if(source!=null && error.code!="UNSUPPORTED") throw error; null }
        } }.awaitAll().filterNotNull() }
    }
    private suspend fun call(profile: SourceProfile,operation: String,payload: JSONObject=JSONObject()): JSONObject {
        val config=profile.configuration as? ExtensionConfig ?: throw ExtensionFailure("UNSUPPORTED")
        extensions.requireInstalled(config)
        return ExtensionClient(app,config.component).request(config.profile,operation,payload.put("source",profile.identity).put("revision",config.revision))
    }
    suspend fun source(id: String): SourceProfile = profiles.current().profiles.firstOrNull { it.id==id } ?: throw ExtensionFailure("PROFILE_MISSING")
    suspend fun enqueue(destination: Destination,target: ImportTarget,uris: List<Uri>,progress: (Int,Int)->Unit={_,_->}) = withContext(Dispatchers.IO) {
        require(destination.options.targets.any { it.id==target.id })
        var added=0
        try { uris.distinct().forEachIndexed { index, uri ->
            currentCoroutineContext().ensureActive()
            val details=MediaDocuments.details(app.contentResolver,uri)
            if(!destination.options.accepts(details.mime)) throw ExtensionFailure("UNSUPPORTED_MEDIA")
            val entry=details.copy(name=safeFilename(details.name))
            val persisted=try {
                app.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION); true
            } catch(_: SecurityException) { false }
            var staging: Snapshot?=null
            if(!persisted) staging=snapshot(entry,UUID.randomUUID().toString())
            if(store.add(destination.source.id,destination.source.identity,target.id,entry,staged=staging?.file,checksum=staging?.checksum,network=destination.options.network)) added++
            else releaseInput(uri)
            progress(index+1,uris.size)
        } } finally {
            store.added()
            if(added>0) {
                if(destination.options.network) {
                    ImportJobs.recovery(app)
                    if(!ImportJobs.userInitiated(app)) ImportJobs.enqueue(app,automatic=false)
                } else ImportJobs.enqueue(app,automatic=false,network=false)
            }
        }
    }
    suspend fun connect(destination: Destination,target: ImportTarget,tree: Uri) = withContext(Dispatchers.IO) {
        require(destination.options.connectedFolders && destination.options.targets.any { it.id==target.id })
        app.contentResolver.takePersistableUriPermission(tree,Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val root=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree))
        val name=MediaDocuments.details(app.contentResolver,root).name
        store.connect(destination.source.id,destination.source.identity,target.id,target.name,tree.toString(),name)
        ImportJobs.configure(app,store.routes().isNotEmpty()); ImportJobs.scan(app)
    }
    internal suspend fun remove(route: ImportStore.Route) = withContext(Dispatchers.IO) {
        transferLock.withLock { scanLock.withLock {
            cancelPrepared(store.unfinished(route.source,route.id)); store.remove(route.id)
        } }
        if(store.routes().none { it.tree==route.tree }) {
            try { app.contentResolver.releasePersistableUriPermission(Uri.parse(route.tree),Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch(_: SecurityException) { }
        }
        ImportJobs.configure(app,store.routes().isNotEmpty())
    }
    internal suspend fun scan(): Boolean = withContext(Dispatchers.IO) { scanLock.withLock {
        val deadline=SystemClock.elapsedRealtime()+120_000
        for(route in store.routes()) {
            if(store.stats(route.source).paused || System.currentTimeMillis()-route.checked<300_000) continue
            try {
                val profile=source(route.source)
                if(profile.identity!=route.identity) throw ExtensionFailure("PROFILE_CHANGED")
                val tree=Uri.parse(route.tree)
                while(true) {
                    currentCoroutineContext().ensureActive()
                    val directory=store.directory(route) ?: break
                    var position=directory.second
                    try {
                        MediaDocuments.children(app.contentResolver,tree,directory.first,position) { entry ->
                            if(SystemClock.elapsedRealtime()>=deadline) throw ScanSlice()
                            if(entry.directory) store.child(route.id,requireNotNull(entry.document))
                            else if(entry.media) store.add(route.source,route.identity,route.target,entry.copy(name=safeFilename(entry.name)),route.id,network=true)
                            position++
                            if(position%100==0) store.checkpoint(route.id,directory.first,position)
                        }
                    } finally { store.checkpoint(route.id,directory.first,position) }
                    store.directoryDone(route.id,directory.first)
                }
                store.checked(route.id)
            } catch(cancelled: CancellationException) { throw cancelled }
            catch(_: ScanSlice) { store.added(); if(store.pending()) ImportJobs.enqueue(app,true); return@withLock false }
            catch(error: Exception) { store.checked(route.id,code(error)) }
        }
        store.added(); if(store.pending()) ImportJobs.enqueue(app,true)
        true
    } }
    internal suspend fun transfer(automatic: Boolean,unlimited: Boolean=false,network: Boolean=true) = withContext(Dispatchers.IO) { (if(network) transferLock else localTransferLock).withLock {
        cleanupSnapshots()
        val deadline=if(unlimited) Long.MAX_VALUE else SystemClock.elapsedRealtime()+8*60_000
        try {
            while(SystemClock.elapsedRealtime()<deadline-10_000) {
                currentCoroutineContext().ensureActive()
                val task=store.next(automatic,network) ?: break
                active[task.source]=task.id
                try {
                    val profile=source(task.source)
                    if(profile.identity!=task.identity) throw ExtensionFailure("PROFILE_CHANGED")
                    val entry=MediaDocuments.Entry(Uri.parse(task.uri),task.name,task.mime,task.size,task.modified)
                    val ready=task.staged?.let(::File)?.takeIf(File::isFile)?.let { file -> task.checksum?.let { Snapshot(file,it,file.length()) } }
                        ?: run {
                            val now=MediaDocuments.details(app.contentResolver,entry.uri)
                            if(now.fingerprint!=entry.fingerprint) throw ExtensionFailure("MEDIA_CHANGED")
                            snapshot(entry,task.id).also { store.snapshot(task.id,it.file,it.checksum,it.size) }
                        }
                    if(store.task(task.id)==null) { ready.file.delete(); continue }
                    val config=profile.configuration as ExtensionConfig
                    val uri=Uri.Builder().scheme("content").authority("${app.packageName}.imports").appendPath(task.id).build()
                    app.grantUriPermission(config.packageName,uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    try {
                        val request=MediaImportRequest(task.id,task.target,uri,task.name,task.mime,ready.size,task.modified,ready.checksum)
                        val receipt=withTimeout(minOf(8*60_000L,(deadline-SystemClock.elapsedRealtime()).coerceAtLeast(1))) {
                            ImportWire.receipt(call(profile,ExtensionProtocol.IMPORT_MEDIA,ImportWire.request(request)))
                        }
                        store.done(task.id,receipt.asset); ready.file.delete(); releaseInput(entry.uri)
                    } finally { app.revokeUriPermission(config.packageName,uri,Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                } catch(timeout: TimeoutCancellationException) {
                    currentCoroutineContext().ensureActive(); store.failed(task,"TIMEOUT",retryAt(task,"TIMEOUT",null))
                } catch(cancelled: CancellationException) { throw cancelled }
                catch(error: Exception) {
                    val code=code(error)
                    store.failed(task,code,retryAt(task,code,(error as? ExtensionFailure)?.retryAfter))
                } finally { active.remove(task.source,task.id) }
            }
        } finally {
            withContext(NonCancellable) {
                for((owner,identity) in store.unfinalized()) {
                    try {
                        val profile=source(owner)
                        if(profile.identity==identity) withTimeout(10_000) { call(profile,ExtensionProtocol.IMPORT_COMPLETE) }
                        store.finalized(owner,identity)
                    } catch(error: Exception) {
                        if(error is ExtensionFailure && error.code=="UNSUPPORTED") store.finalized(owner,identity)
                    }
                }
            }
        }
    } }
    private data class Snapshot(val file: File,val checksum: String,val size: Long)
    private suspend fun snapshot(entry: MediaDocuments.Entry,id: String): Snapshot {
        val dir=File(app.noBackupFilesDir,"media-imports").apply { mkdirs() }
        val pending=File(dir,"$id.pending"); val completed=File(dir,"$id.media")
        val hash=MessageDigest.getInstance("SHA-1"); var size=0L
        val job=currentCoroutineContext()
        try {
            readImportStream(app,entry.uri,job) { input -> pending.outputStream().use { output ->
                val bytes=ByteArray(64*1024)
                while(true) {
                    job.ensureActive()
                    val read=input.read(bytes); if(read<0) break
                    if(size%(4*1024*1024)==0L && dir.usableSpace<read+16L*1024*1024) throw ExtensionFailure("STORAGE_FULL")
                    size+=read; hash.update(bytes,0,read); output.write(bytes,0,read)
                }
            } }
            if(entry.size>=0 && entry.size!=size) throw ExtensionFailure("MEDIA_CHANGED")
            val after=MediaDocuments.details(app.contentResolver,entry.uri)
            if(after.fingerprint!=entry.fingerprint) throw ExtensionFailure("MEDIA_CHANGED")
            check(pending.renameTo(completed))
            return Snapshot(completed,hash.digest().joinToString("") { "%02x".format(it) },size)
        } finally { pending.delete() }
    }
    private class ScanSlice: Exception()
    internal suspend fun pause(source: String) = withContext(Dispatchers.IO) {
        val paused=!store.stats(source).paused
        store.pause(source,paused)
        if(!paused) resumeJobs()
    }
    internal suspend fun retry(source: String) = withContext(Dispatchers.IO) { store.retry(source); resumeJobs() }
    internal suspend fun check(source: String) = withContext(Dispatchers.IO) { store.rescan(source); ImportJobs.scan(app) }
    internal suspend fun clear(source: String) = withContext(Dispatchers.IO) {
        store.pause(source,true); scanLock.withLock {
            cancelPrepared(store.unfinished(source).filter { it.id!=active[source] })
            store.clear(source,active[source]).forEach { releaseInput(Uri.parse(it)) }
        }
    }
    private suspend fun cancelPrepared(tasks: List<ImportStore.Task>) {
        for(group in tasks.filter { it.checksum!=null }.groupBy { it.source to it.identity }.values) {
            try {
                val profile=source(group.first().source)
                if(profile.identity!=group.first().identity) continue
                group.chunked(100).forEach { chunk -> withTimeout(10_000) {
                    call(profile,ExtensionProtocol.IMPORT_CANCEL,JSONObject().put("ids",SourceWire.strings(chunk.map { it.id })))
                } }
            } catch(timeout: TimeoutCancellationException) { currentCoroutineContext().ensureActive() }
            catch(cancelled: CancellationException) { throw cancelled }
            catch(_: Exception) { /* Unavailable extensions cannot clean private partial writes until restored. */ }
        }
    }
    private fun releaseInput(uri: Uri) {
        if(store.needsUri(uri.toString())) return
        if(app.contentResolver.persistedUriPermissions.any { it.uri==uri }) {
            try { app.contentResolver.releasePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch(_: SecurityException) { }
        }
    }
    private fun cleanupSnapshots() {
        val used=store.stagedPaths(); val old=System.currentTimeMillis()-24*3600_000L
        File(app.noBackupFilesDir,"media-imports").listFiles()?.filter { it.lastModified()<old && it.absolutePath !in used }?.forEach { it.delete() }
    }
    private fun resumeJobs() {
        ImportJobs.enqueue(app,true); ImportJobs.enqueue(app,false); ImportJobs.enqueue(app,false,network=false); ImportJobs.scan(app)
    }
    companion object {
        internal fun safeFilename(value: String)=value.filterNot { it=='/' || it=='\\' || it.isISOControl() }.take(255).ifBlank { "media" }
        internal fun code(error: Exception) = when(error) {
            is ExtensionFailure -> error.code
            is SecurityException -> "PERMISSION_REQUIRED"
            is java.io.FileNotFoundException -> "MEDIA_MISSING"
            is java.io.IOException -> "NETWORK"
            else -> "REQUEST_FAILED"
        }
        internal fun retryAt(task: ImportStore.Task,code: String,retryAfter: String?): Long? {
            if(task.attempts>=4 || code !in setOf("NETWORK","DISCONNECTED","TIMEOUT","HTTP_408","HTTP_429","HTTP_500","HTTP_502","HTTP_503","HTTP_504")) return null
            val now=System.currentTimeMillis()
            val seconds=retryAfter?.toLongOrNull()
            if(seconds!=null && seconds !in 0..7*24*3600L) return null
            val server=seconds?.times(1000)?.plus(now)
                ?: retryAfter?.let { runCatching { java.time.ZonedDateTime.parse(it,java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull() }
            if(retryAfter!=null && (server==null || server>now+7*24*3600_000L)) return null
            return maxOf(server ?: 0,now+(30_000L shl task.attempts)+kotlin.random.Random.nextLong(5_000))
        }
    }
}
