package one.njk.sao.floating

import one.njk.sao.source.*

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class ExportedMedia(val uri: Uri, val mimeType: String, val file: File)

@Singleton
class MediaExports @Inject constructor(@ApplicationContext private val context: Context,
    private val source: MediaSource,
    private val dispatchers: FloatingDispatchers = FloatingDispatchers(),
    private val transcoder: VideoTranscoder = VideoTranscoder(context, dispatchers),
    private val store: MediaExportStore = MediaExportStore(context, dispatchers)) {
    /** Home Share uses the displayed rendition, including animation/video; never fetch its original. */
    suspend fun displayed(item: MediaItem): ExportedMedia = withContext(dispatchers.io) {
        require(item.id.matches(Regex("[a-z0-9_-]+")))
        if(item.isVideo) source.withPlaybackFile(item) { snapshot(item,it) } else snapshot(item,source.preview(item))
    }

    private suspend fun snapshot(item: MediaItem, data: Any): ExportedMedia {
        fun input(): java.io.InputStream = when(data) {
            is File -> data.inputStream()
            is Uri -> requireNotNull(context.contentResolver.openInputStream(data))
            is String -> {
                require(data.startsWith("file:///android_asset/"))
                context.assets.open(data.removePrefix("file:///android_asset/"))
            }
            else -> error("Unsupported display media")
        }
        // A JPEG preview may represent a PNG/HEIC original. Advertise the actual displayed format.
        val mime=if(item.isVideo) {
            val retriever=android.media.MediaMetadataRetriever()
            try {
                require(data is File)
                retriever.setDataSource(data.absolutePath)
                retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_MIMETYPE) ?: item.mimeType
            } finally { retriever.release() }
        } else {
            val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
            input().use { BitmapFactory.decodeStream(it,null,bounds) }
            bounds.outMimeType ?: item.mimeType
        }
        val extension=android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "bin"
        val file=store.write("${item.id}-display.$extension") { staging ->
            input().use { from -> staging.outputStream().use { MediaExportStore.copy(from,it) } }
        }
        return store.exported(file,mime)
    }

    suspend fun prepare(item: MediaItem, rendition: Rendition,
        sticker: StickerRequirements = StickerRequirements.WHATSAPP): ExportedMedia = withContext(dispatchers.io) {
        require(!item.hasMotion || rendition == Rendition.MEDIA) { "Animated sticker conversion is unavailable" }
        require(item.id.matches(Regex("[a-z0-9_-]+")))
        val (extension,mime) = when(rendition) {
            Rendition.MEDIA -> (android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(item.mimeType) ?: "bin") to item.mimeType
            Rendition.STICKER_WEBP -> "webp" to "image/webp"
            Rendition.STICKER_PNG -> "png" to "image/png"
        }
        val budget=if(rendition==Rendition.STICKER_WEBP) "-${sticker.maxBytes}" else ""
        val file=store.write("${item.id}-${rendition.name.lowercase()}$budget.$extension") { staging ->
            if(rendition==Rendition.MEDIA) {
                source.original(item).use { input -> staging.outputStream().use { MediaExportStore.copy(input,it) } }
            } else source.raster(item).use { input ->
                // Cached extension images already have a seekable FD. A pipe/SVG gets one
                // temporary disk spool; neither path allocates the compressed file on the heap.
                val seekable=input as? java.io.FileInputStream
                if(seekable!=null && runCatching { seekable.channel.position(0) }.isSuccess)
                    writeSticker(seekable,staging,rendition,sticker)
                else {
                    val scratch=File.createTempFile("raster-", ".pending",store.directory)
                    try {
                        scratch.outputStream().use { MediaExportStore.copy(input,it) }
                        scratch.inputStream().use { writeSticker(it,staging,rendition,sticker) }
                    } finally { scratch.delete() }
                }
            }
        }
        store.exported(file,mime)
    }

    private suspend fun writeSticker(input: java.io.FileInputStream, staging: File, rendition: Rendition, requirements: StickerRequirements) {
        val bytes=store.conversions.withPermit {
            withContext(dispatchers.conversion) { StaticStickerEncoder.encode(input,rendition,requirements) }
        }
        if(bytes==null) {
            input.channel.position(0)
            staging.outputStream().use { MediaExportStore.copy(input,it) }
        } else staging.writeBytes(bytes)
    }

    suspend fun mp4(item: MediaItem): ExportedMedia = withContext(dispatchers.io) {
        require(item.mimeType=="video/webm")
        require(item.id.matches(Regex("[a-z0-9_-]+")))
        val file=store.write("${item.id}-compatible.mp4") { staging ->
            source.withVideoFile(item) { input ->
                store.conversions.withPermit { transcoder.mp4(input,staging) }
            }
        }
        store.exported(file,"video/mp4")
    }

    /** Called only after the user explicitly chooses the receiving app's smaller-copy option. */
    suspend fun smaller(item: MediaItem, original: ExportedMedia, maxBytes: Long): ExportedMedia = withContext(dispatchers.io) {
        require(maxBytes>0 && AttachmentSizePolicy.canReduce(original.mimeType,item.animated))
        if(original.file.length()<=maxBytes) return@withContext original
        val mime=if(item.isVideo) "video/mp4" else original.mimeType
        val extension=if(item.isVideo) "mp4" else if(mime=="image/jpeg") "jpg" else "png"
        val file=store.write("${item.id}-limited-$maxBytes.$extension") { staging ->
            store.using(original.file) {
                store.conversions.withPermit {
                    if(item.isVideo) transcoder.mp4(original.file,staging,maxBytes)
                    else withContext(dispatchers.conversion) { AttachmentImageEncoder.write(original.file,staging,mime,maxBytes) }
                }
            }
            if(staging.length()>maxBytes) throw MediaExportException(one.njk.sao.R.string.media_export_size_unavailable)
        }
        store.exported(file,mime)
    }
}
