package one.njk.sao.floating

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Build
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import one.njk.sao.R
import java.io.File

/** Explicit delivery-only reduction: retain dimensions, orientation and transparency. */
internal object AttachmentImageEncoder {
    suspend fun write(input: File, output: File, mime: String, maxBytes: Long) {
        require(mime in setOf("image/jpeg","image/png") && maxBytes>0)
        currentCoroutineContext().ensureActive()
        val bitmap=ImageDecoder.decodeBitmap(ImageDecoder.createSource(input)) { decoder,info,_ ->
            require(!info.isAnimated) { "Animated image reduction is unavailable" }
            // Refuse an unsafe allocation rather than silently reducing the dimensions.
            if(info.size.width.toLong()*info.size.height*8 > Runtime.getRuntime().maxMemory()/2)
                throw MediaExportException(R.string.media_export_image_memory)
            decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
        }
        try {
            if(Build.VERSION.SDK_INT>=34 && bitmap.hasGainmap())
                throw MediaExportException(R.string.media_export_hdr_unavailable)
            suspend fun attempt(quality: Int): Boolean {
                currentCoroutineContext().ensureActive()
                output.outputStream().use {
                    check(bitmap.compress(if(mime=="image/png") Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG,quality,it))
                }
                currentCoroutineContext().ensureActive()
                return output.length()<=maxBytes
            }
            if(attempt(100)) return
            // PNG stays lossless with alpha; never flatten it into JPEG to hit a byte target.
            if(mime=="image/png") throw MediaExportException(R.string.media_export_size_unavailable)
            if(!attempt(10)) throw MediaExportException(R.string.media_export_size_unavailable)
            var best=10; var low=10; var high=100
            repeat(5) {
                val quality=(low+high)/2
                if(attempt(quality)) { best=quality; low=quality } else high=quality
            }
            check(attempt(best))
        } finally { bitmap.recycle() }
    }
}
