package one.njk.sao.extension.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri

object MediaBitmaps {
    /** Bounded software bitmap, including a representative video/GIF frame. Run on IO. */
    fun read(context: Context, uri: Uri, mime: String, edge: Int, background: Int=Color.WHITE): Bitmap {
        val source = if(mime.startsWith("video/")) MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(context,uri)
            retriever.getScaledFrameAtTime(0,MediaMetadataRetriever.OPTION_CLOSEST_SYNC,edge,edge)
                ?: throw java.io.IOException("No decodable video frame")
        } else ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver,uri)) { decoder,info,_ ->
            val scale=minOf(1.0,edge.toDouble()/maxOf(info.size.width,info.size.height))
            decoder.setTargetSize(maxOf(1,(info.size.width*scale).toInt()),maxOf(1,(info.size.height*scale).toInt()))
            decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
        }
        // Composite transparent sticker backgrounds before OCR/labeling/JPEG thumbnails.
        if(!source.hasAlpha()) return source
        return Bitmap.createBitmap(source.width,source.height,Bitmap.Config.ARGB_8888).also { result ->
            Canvas(result).apply { drawColor(background); drawBitmap(source,0f,0f,null) }; source.recycle()
        }
    }
    fun animated(context: Context, asset: LocalAsset): Boolean {
        if(asset.mime=="image/gif") return true
        if(asset.mime!="image/webp") return false
        return context.contentResolver.openInputStream(Uri.parse(asset.uri))?.use {
            val bytes=ByteArray(21); var read=0
            while(read<bytes.size) { val count=it.read(bytes,read,bytes.size-read); if(count<0) break; read+=count }
            read==21 && String(bytes,12,4,Charsets.US_ASCII)=="VP8X" && bytes[20].toInt() and 2 != 0
        } ?: false
    }
}
