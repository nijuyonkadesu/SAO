package one.njk.sao.floating

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeMediaFormatTest {
    @Test fun nativeVideoWinsOverGifWithoutReencodingOrLosingAudio() {
        for(mime in listOf("video/mp4","video/webm"))
            assertEquals(mime,NativeMediaFormat.mime(mime,true,setOf(mime,"image/gif")::contains))
    }
    @Test fun compatibleVideoWinsOverGifWhenOriginalContainerIsUnavailable() {
        assertEquals("video/mp4",NativeMediaFormat.mime("video/webm",true,setOf("video/mp4","image/gif")::contains))
    }
    @Test fun gifFallbackRequiresInstagramAndAnExplicitGifCapability() {
        assertEquals("image/gif",NativeMediaFormat.mime("video/mp4",true,setOf("image/gif")::contains))
        assertEquals("video/mp4",NativeMediaFormat.mime("video/mp4",false,setOf("image/gif")::contains))
        assertEquals("video/mp4",NativeMediaFormat.mime("video/mp4",true,setOf("image/jpeg")::contains))
    }
}
