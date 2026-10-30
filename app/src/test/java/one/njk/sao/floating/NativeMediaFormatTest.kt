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
    @Test fun whatsappAnimatedImagesHaveAGifRouteWithoutEnablingTheGifPreference() {
        for(target in listOf(MessagingTarget.WHATSAPP,MessagingTarget.WHATSAPP_BUSINESS)) {
            for(mime in listOf("image/webp","image/png","image/apng","image/avif")) {
                assertEquals("image/gif",DeliveryPolicy.mediaMime(mime,target,motion=true))
                assertEquals("image/gif",NativeMediaFormat.mime(mime,{ it=="image/gif" },motion=true,target=target))
                assertEquals(mime,DeliveryPolicy.mediaMime(mime,target,motion=false))
            }
            assertEquals("image/gif",DeliveryPolicy.mediaMime("image/gif",target,motion=true))
        }
        assertEquals("image/webp",DeliveryPolicy.mediaMime("image/webp",MessagingTarget.TELEGRAM,motion=true))
    }
    @Test fun whatsappVideoCompatibilityKeepsAudioUnlessGifWasExplicitlyChosen() {
        for(target in listOf(MessagingTarget.WHATSAPP,MessagingTarget.WHATSAPP_BUSINESS)) {
            for(mime in listOf("video/webm","video/x-matroska","video/mp4")) {
                assertEquals("video/mp4",DeliveryPolicy.mediaMime(mime,target,motion=true))
                assertEquals("image/gif",DeliveryPolicy.mediaMime(mime,target,motion=true,route=MotionDelivery.GIF))
            }
        }
        assertEquals("video/webm",DeliveryPolicy.mediaMime("video/webm",MessagingTarget.TELEGRAM,motion=true))
    }
}
