package one.njk.sao.floating

import org.junit.Assert.*
import org.junit.Test

class GifConversionCostTest {
    @Test fun onlyLargeFrameWorkloadsWarnAndArithmeticCannotOverflow() {
        assertFalse(GifConversionCost(96,64,3).large)
        assertEquals(96L*64*3,GifConversionCost(96,64,3).indexedBytes)
        assertTrue(GifConversionCost(1280,720,60).large)
        assertEquals(Long.MAX_VALUE,GifConversionCost(Int.MAX_VALUE,Int.MAX_VALUE,Long.MAX_VALUE).indexedBytes)
    }
}
