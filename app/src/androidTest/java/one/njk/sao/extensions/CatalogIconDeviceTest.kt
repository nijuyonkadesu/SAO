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
    @Test fun oversizedIconsAndEntityDocumentsAreRejectedBeforeRendering(): Unit=runBlocking {
        for(text in listOf(" ".repeat(64*1024+1),"<!DOCTYPE svg [<!ENTITY x 'expansion'>]><svg>&x;</svg>")) {
            try { CatalogIconDecoder.decode(Buffer().writeUtf8(text),96); fail("Unsafe icon accepted") }
            catch(_: IllegalArgumentException) { }
        }
    }
}
