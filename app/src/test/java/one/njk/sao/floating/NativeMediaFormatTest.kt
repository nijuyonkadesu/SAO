package one.njk.sao.floating

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeMediaFormatTest {
    @Test fun explicitGifRouteWinsAndDoesNotSilentlyReturnToVideo() {
        for(types in listOf(setOf("video/mp4","image/gif"),setOf("video/mp4")))
            assertEquals("image/gif",NativeMediaFormat.mime("video/mp4",types::contains,MotionDelivery.GIF))
        assertEquals("image/jpeg",NativeMediaFormat.mime("image/jpeg",setOf("image/jpeg","image/gif")::contains,MotionDelivery.GIF))
        assertEquals("image/gif",NativeMediaFormat.mime("video/mp4",setOf("video/mp4","image/gif")::contains,MotionDelivery.GIF))
    }
    @Test fun explicitGifAlsoWorksForAnimatedImageFormatsAcrossApps() {
        for(mime in listOf("image/webp","image/apng","video/x-matroska"))
            assertEquals("image/gif",NativeMediaFormat.mime(mime,{ true },MotionDelivery.GIF,motion=true))
    }
    @Test fun nativeVideoWinsOverGifWithoutReencodingOrLosingAudio() {
        for(mime in listOf("video/mp4","video/webm"))
            assertEquals(mime,NativeMediaFormat.mime(mime,setOf(mime,"image/gif")::contains))
    }
    @Test fun compatibleVideoWinsOverGifWhenOriginalContainerIsUnavailable() {
        assertEquals("video/mp4",NativeMediaFormat.mime("video/webm",setOf("video/mp4","image/gif")::contains))
    }
    @Test fun originalChoiceNeverSilentlyConvertsToGif() {
        assertEquals("video/mp4",NativeMediaFormat.mime("video/mp4",setOf("image/gif")::contains))
        assertEquals("video/mp4",NativeMediaFormat.mime("video/mp4",setOf("image/jpeg")::contains))
    }
}
