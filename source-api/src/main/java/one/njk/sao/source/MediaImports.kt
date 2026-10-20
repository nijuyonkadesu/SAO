package one.njk.sao.source

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.io.InputStream
import java.security.MessageDigest

/** Optional write contract. Destinations are opaque; neither credentials nor folder paths cross IPC. */
data class ImportTarget(val id: String, val name: String)
data class ImportCollection(val name: String,val summary: String)
data class ImportOptions(val types: Set<String> = emptySet(), val targets: List<ImportTarget> = emptyList(),
    val connectedFolders: Boolean = false, val network: Boolean = false,
    val collections: List<ImportCollection> = emptyList(),val settingsLabel: String?=null) {
    fun accepts(mime: String) = types.any { it == mime || it.endsWith("/*") && mime.startsWith(it.dropLast(1)) }
}
data class MediaImportRequest(val id: String, val target: String, val uri: Uri, val filename: String,
    val mime: String, val size: Long, val modified: Long, val checksum: String) {
    init {
        java.util.UUID.fromString(id)
        require(uri.scheme == "content" && filename.isNotBlank() && filename.length <= 255)
        require(filename.none { it == '/' || it == '\\' || it.isISOControl() })
        require(size >= 0 && checksum.matches(Regex("[a-f0-9]{40}")))
    }
}
data class ImportReceipt(val asset: String, val existing: Boolean = false)

object ImportWire {
    fun options(value: ImportOptions) = JSONObject().put("types",SourceWire.strings(value.types))
        .put("folders",value.connectedFolders).put("network",value.network).put("targets",JSONArray(value.targets.map { JSONObject().put("id",it.id).put("name",it.name) }))
        .put("collections",JSONArray(value.collections.map { JSONObject().put("name",it.name).put("summary",it.summary) })).put("settingsLabel",value.settingsLabel)
    fun options(json: JSONObject): ImportOptions {
        val rows=json.getJSONArray("targets"); require(rows.length() <= 512)
        val collections=json.optJSONArray("collections"); require(collections==null || collections.length()<=512)
        return ImportOptions(SourceWire.strings(json.getJSONArray("types")).toSet(),List(rows.length()) {
            rows.getJSONObject(it).let { row -> ImportTarget(row.getString("id"),row.getString("name")) }
        },json.optBoolean("folders"),json.optBoolean("network"),
            if(collections==null) emptyList() else List(collections.length()) { collections.getJSONObject(it).let { row -> ImportCollection(row.getString("name"),row.getString("summary")) } },SourceWire.nullable(json,"settingsLabel"))
    }
    fun request(value: MediaImportRequest) = JSONObject().put("id",value.id).put("target",value.target)
        .put("uri",value.uri.toString()).put("filename",value.filename).put("mime",value.mime)
        .put("size",value.size).put("modified",value.modified).put("checksum",value.checksum)
    fun request(json: JSONObject, caller: String): MediaImportRequest {
        val uri=Uri.parse(json.getString("uri"))
        // A host-owned, individually read-granted snapshot, never an arbitrary provider/URL supplied by a client.
        require(uri.authority == "$caller.imports")
        return MediaImportRequest(json.getString("id"),json.getString("target"),uri,json.getString("filename"),
            json.getString("mime"),json.getLong("size"),json.getLong("modified"),json.getString("checksum"))
    }
    fun receipt(value: ImportReceipt) = JSONObject().put("asset",value.asset).put("existing",value.existing)
    fun receipt(json: JSONObject) = ImportReceipt(json.getString("asset"),json.optBoolean("existing"))
}

/** Shared streaming primitive: bounded memory, cancellation and verification, without recompression. */
fun copyImport(context: Context, request: MediaImportRequest, output: OutputStream, job: kotlin.coroutines.CoroutineContext) {
    val hash=MessageDigest.getInstance("SHA-1"); var total=0L
    readImportStream(context,request.uri,job,{ output.close() }) { input ->
        val buffer=ByteArray(64*1024)
        while(true) {
            job.ensureActive()
            val count=input.read(buffer); if(count<0) break
            total+=count; if(total>request.size) throw ExtensionFailure("MEDIA_CHANGED")
            hash.update(buffer,0,count); output.write(buffer,0,count)
        }
    }
    if(total!=request.size || hash.digest().joinToString("") { "%02x".format(it) }!=request.checksum)
        throw ExtensionFailure("MEDIA_CHANGED")
}

/** Close provider pipes on cancellation, including a provider that blocks opening or reading a cloud file. */
fun <T> readImportStream(context: Context,uri: Uri,job: kotlin.coroutines.CoroutineContext,
    onCancel: () -> Unit = {},block: (InputStream) -> T): T {
    val signal=android.os.CancellationSignal()
    val current=java.util.concurrent.atomic.AtomicReference<InputStream?>()
    val closer=CoroutineScope(job+Dispatchers.IO).launch(start=CoroutineStart.UNDISPATCHED) {
        try { awaitCancellation() } finally {
            signal.cancel(); runCatching { current.get()?.close() }
            if(!job.isActive) runCatching(onCancel)
        }
    }
    try {
        val descriptor=context.contentResolver.openAssetFileDescriptor(uri,"r",signal) ?: throw ExtensionFailure("MEDIA_MISSING")
        return descriptor.use { it.createInputStream().use { input -> current.set(input); block(input) } }
    } catch(error: Exception) { job.ensureActive(); throw error
    } finally { closer.cancel() }
}
