package one.njk.sao.extensions

import android.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class CatalogIconDeviceTest {
    @Test fun vectorRenderingIsBoundedAndPreservesTransparency(): Unit=runBlocking {
        // A UI caller can request decoding; SVG parsing/rendering dispatches itself to IO.
        val bitmap=withContext(Dispatchers.Main) {
            CatalogIconDecoder.decode(Buffer().writeUtf8("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100"><path fill="#4B5D92" d="M25 25H75V75H25Z"/></svg>"""),96)
        }
        try {
            assertEquals(96,bitmap.width); assertEquals(96,bitmap.height)
            assertEquals(Color.TRANSPARENT,bitmap.getPixel(0,0))
            assertEquals(Color.rgb(75,93,146),bitmap.getPixel(48,48))
        } finally { bitmap.recycle() }
    }
    @Test fun publishedArtworkDecodesThroughCoil(): Unit=runBlocking {
        val app=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val files=java.io.File(app.cacheDir,"catalog-icon-fixtures").listFiles().orEmpty().filter { it.extension=="svg" }
        org.junit.Assume.assumeTrue("Stage the built catalog SVGs in cache/catalog-icon-fixtures",files.isNotEmpty())
        val loader=one.njk.sao.di.AppModule.provideImageLoader(app)
        try {
            for(file in files) {
                val models=mutableListOf<Any>(file)
                if(androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("liveCatalogIcons")=="true")
                    models+="https://raw.githubusercontent.com/nijuyonkadesu/SAO-extensions/repo/icons/${file.name}?artwork=1"
                for(data in models) {
                    val result=loader.execute(coil3.request.ImageRequest.Builder(app).data(data).size(105,105)
                        .decoderFactory(CatalogIconDecoder.factory(105)).build())
                    val error=(result as? coil3.request.ErrorResult)?.throwable
                    assertTrue("$data: ${error?.stackTraceToString()}",result is coil3.request.SuccessResult)
                }
            }
        } finally { loader.shutdown() }
    }
    @Test fun oversizedIconsAndEntityDocumentsAreRejectedBeforeRendering(): Unit=runBlocking {
        for(text in listOf(" ".repeat(64*1024+1),"<!DOCTYPE svg [<!ENTITY x 'expansion'>]><svg>&x;</svg>")) {
            try { CatalogIconDecoder.decode(Buffer().writeUtf8(text),96); fail("Unsafe icon accepted") }
            catch(_: IllegalArgumentException) { }
        }
    }
}
