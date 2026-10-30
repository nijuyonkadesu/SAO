package one.njk.sao.extensions

import android.graphics.Bitmap
import android.graphics.Canvas
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import com.caverock.androidsvg.SVG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okio.BufferedSource

/** Scoped to catalog icon requests; other media decoders and cache policies stay unchanged. */
internal object CatalogIconDecoder {
    fun factory(pixels: Int) = Decoder.Factory { result,_,_ ->
        Decoder { DecodeResult(decode(result.source.source(),pixels).asImage(),isSampled=false) }
    }
    suspend fun decode(source: BufferedSource,pixels: Int): Bitmap = withContext(Dispatchers.IO) {
        require(pixels in 1..256)
        require(!source.request(64*1024L+1)) { "Catalog icon exceeds 64 KiB" }
        val text=source.readUtf8()
        // AndroidSVG 1.4 allows internal entities by default; do not change its global setting.
        require(!text.contains("<!DOCTYPE",ignoreCase=true) && !text.contains("<!ENTITY",ignoreCase=true))
        currentCoroutineContext().ensureActive()
        val svg=SVG.getFromString(text).apply { documentWidth=pixels.toFloat(); documentHeight=pixels.toFloat() }
        val bitmap=Bitmap.createBitmap(pixels,pixels,Bitmap.Config.ARGB_8888)
        try {
            svg.renderToCanvas(Canvas(bitmap))
            currentCoroutineContext().ensureActive()
            bitmap
        } catch(error: Exception) { bitmap.recycle(); throw error }
    }
}
