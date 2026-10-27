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
    private val store: MediaExportStore = MediaExportStore(context, dispatchers),
    private val gifs: VideoGifTranscoder = VideoGifTranscoder(context,dispatchers)) {
    /** Home Share uses the displayed rendition, including animation/video; never fetch its original. */
    suspend fun displayed(item: MediaItem): ExportedMedia = withContext(dispatchers.io) {
        require(item.id.matches(Regex("[a-z0-9_-]+")))
        if(item.isVideo) source.withRendition(item, Rendition.PLAYBACK) { handle ->
            snapshot(item, Preview.Local(handle.requireFile()))
        } else source.withPreview(item) { snapshot(item,it) }
    }

    private suspend fun snapshot(item: MediaItem, preview: Preview): ExportedMedia {
        fun input(): java.io.InputStream = when(preview) {
            is Preview.Local -> preview.file.inputStream()
            is Preview.Uri -> requireNotNull(context.contentResolver.openInputStream(preview.uri))
            is Preview.Bytes -> java.io.ByteArrayInputStream(preview.buffer.remainingBytes())
            is Preview.Decoded -> java.io.ByteArrayInputStream(preview.bitmap.toPng())
        }
        // A JPEG preview may represent a PNG/HEIC original. Advertise the actual displayed format.
        val mime=if(item.isVideo) {
            val file=(preview as? Preview.Local)?.file ?: error("A video preview must be a local file")
            val retriever=android.media.MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_MIMETYPE) ?: item.mimeType
            } finally { retriever.release() }
        } else {
            val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
            input().use { BitmapFactory.decodeStream(it,null,bounds) }
            bounds.outMimeType ?: item.mimeType
        }
        val extension=android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "bin"
        val file=store.write(MediaExportKey(item,MediaExportPolicy.Displayed),extension) { staging ->
            input().use { from -> staging.outputStream().use { MediaExportStore.copy(from,it) } }
        }
        return store.exported(file,mime)
    }

    private fun java.nio.ByteBuffer.remainingBytes(): ByteArray {
        val copy=duplicate()
        return ByteArray(copy.remaining()).also { copy.get(it) }
    }

    private fun android.graphics.Bitmap.toPng(): ByteArray = java.io.ByteArrayOutputStream().use {
        check(compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)); it.toByteArray()
    }

    suspend fun prepare(item: MediaItem, rendition: ExportRendition,
        sticker: StickerRequirements = StickerRequirements.WHATSAPP): ExportedMedia = withContext(dispatchers.io) {
        require(!item.hasMotion || rendition == ExportRendition.MEDIA) { "Animated sticker conversion is unavailable" }
        require(item.id.matches(Regex("[a-z0-9_-]+")))
        val (extension,mime) = when(rendition) {
            ExportRendition.MEDIA -> (android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(item.mimeType) ?: "bin") to item.mimeType
            ExportRendition.STICKER_WEBP -> "webp" to "image/webp"
            ExportRendition.STICKER_PNG -> "png" to "image/png"
        }
        val file=store.write(MediaExportKey(item,MediaExportPolicy.Prepared(rendition,sticker)),extension) { staging ->
            if(rendition==ExportRendition.MEDIA) {
                source.withRendition(item, Rendition.ORIGINAL) { handle ->
                    handle.openStream().use { input -> staging.outputStream().use { MediaExportStore.copy(input,it) } }
                }
            } else source.withRendition(item, Rendition.STILL) { handle ->
                handle.openStream().use { input ->
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
        }
        store.exported(file,mime)
    }

    private suspend fun writeSticker(input: java.io.FileInputStream, staging: File, rendition: ExportRendition, requirements: StickerRequirements) {
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
        val file=store.write(MediaExportKey(item,MediaExportPolicy.CompatibleMp4),"mp4") { staging ->
            source.withRendition(item, Rendition.ORIGINAL) { handle ->
                val input=handle.file ?: run {
                    val scratch=File.createTempFile("original-", ".pending",store.directory)
                    try {
                        handle.openStream().use { from -> scratch.outputStream().use { MediaExportStore.copy(from,it) } }
                        scratch
                    } catch (error: Exception) { scratch.delete(); throw error }
                }
                try { store.conversions.withPermit { transcoder.mp4(input,staging) } }
                finally { if (input !== handle.file) input.delete() }
            }
        }
        store.exported(file,"video/mp4")
    }

    /** Only the explicit Instagram GIF fallback uses this cache policy; ordinary copies stay exact. */
    suspend fun instagramGif(item: MediaItem): ExportedMedia = withContext(dispatchers.io) {
        require(item.isVideo)
        require(item.id.matches(Regex("[a-z0-9_-]+")))
        val file=store.write(MediaExportKey(item,MediaExportPolicy.InstagramGif),"gif") { staging ->
            source.withRendition(item,Rendition.ORIGINAL) { handle ->
                val scratch=if(handle.file==null) File.createTempFile("gif-input-",".pending",store.directory) else null
                try {
                    if(scratch!=null) handle.openStream().use { from -> scratch.outputStream().use { MediaExportStore.copy(from,it) } }
                    store.conversions.withPermit { gifs.gif(handle.file ?: requireNotNull(scratch),staging) }
                } finally { scratch?.delete() }
            }
        }
        store.exported(file,"image/gif")
    }

    /** Called only after the user explicitly chooses the receiving app's smaller-copy option. */
    suspend fun smaller(item: MediaItem, original: ExportedMedia, maxBytes: Long): ExportedMedia = withContext(dispatchers.io) {
        require(maxBytes>0 && AttachmentSizePolicy.canReduce(original.mimeType,item.animated))
        if(original.file.length()<=maxBytes) return@withContext original
        val mime=if(item.isVideo) "video/mp4" else original.mimeType
        val extension=if(item.isVideo) "mp4" else if(mime=="image/jpeg") "jpg" else "png"
        val file=store.write(MediaExportKey(item,MediaExportPolicy.Limited(mime,maxBytes)),extension) { staging ->
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
