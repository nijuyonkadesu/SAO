package one.njk.sao.extension.immich

import android.content.Context
import kotlinx.coroutines.currentCoroutineContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import one.njk.sao.immich.*
import one.njk.sao.source.*
import java.time.Instant

/** Immich permissions, deduplication and album semantics never escape this APK. */
internal class ImmichImports(private val context: Context, private val connection: ConnectionStore.Connection) {
    suspend fun options(): ImportOptions = ImmichClient(connection.config.connection,false).use { client ->
        val permissions=client.api.apiKey()
        if(!permissions.allows("asset.upload")) return@use ImportOptions()
        val album=connection.config.albumId
        val target=if(album==null) "All media" else {
            if(!permissions.allows("album.read") || !permissions.allows("albumAsset.create")) return@use ImportOptions()
            // Shared read-only albums must not become upload destinations by accident.
            client.api.albums(owned=true).firstOrNull { it.id==album }?.albumName ?: return@use ImportOptions()
        }
        ImportOptions(setOf("image/*","video/*"),listOf(ImportTarget("default",target)),connectedFolders=true,network=true)
    }
    suspend fun upload(request: MediaImportRequest): ImportReceipt = ImmichClient(connection.config.connection,false).use { client ->
        if(request.target!="default") throw ExtensionFailure("TARGET_MISSING")
        val checked=client.api.checkUploads(UploadCheck(listOf(UploadCheckItem(request.id,request.checksum))))
            .results.singleOrNull { it.id==request.id } ?: throw ExtensionFailure("INVALID_RESPONSE")
        if(checked.isTrashed==true) throw ExtensionFailure("DUPLICATE_TRASHED")
        val existing=checked.assetId?.takeIf { checked.action.equals("reject",true) && checked.reason.equals("duplicate",true) }
        var duplicate=existing!=null
        if(existing==null && !checked.action.equals("accept",true)) throw ExtensionFailure("UPLOAD_REJECTED")
        val id=existing ?: run {
            val job=currentCoroutineContext()
            val body=object : RequestBody() {
                override fun contentType() = request.mime.toMediaType()
                override fun contentLength() = request.size
                override fun isOneShot() = true
                override fun writeTo(sink: BufferedSink) = copyImport(context,request,sink.outputStream(),job)
            }
            val text="text/plain".toMediaType()
            val date=Instant.ofEpochMilli(request.modified.takeIf { it>0 } ?: System.currentTimeMillis()).toString().toRequestBody(text)
            client.api.upload(MultipartBody.Part.createFormData("assetData",request.filename,body),date,date,request.checksum).let {
                duplicate=it.status.equals("duplicate",true); it.id
            }
        }
        connection.config.albumId?.let { album ->
            val result=client.api.addToAlbum(album,BulkIds(listOf(id))).singleOrNull { it.id==id }
                ?: throw ExtensionFailure("INVALID_RESPONSE")
            if(!result.success && !result.error.equals("duplicate",true)) throw ExtensionFailure("ALBUM_ADD_FAILED")
        }
        ImportReceipt(id,duplicate)
    }
}
