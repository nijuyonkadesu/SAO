package one.njk.sao.floating

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class FloatingLogicTest {
    private val love = MediaItem("love", "Love", "floating/love.png", "image/png", listOf("heart", "affection"), listOf("❤️", "🥰"))
    private val family = MediaItem("family", "Family", "floating/family.png", "image/png", listOf("home"), listOf("👨‍👩‍👧‍👦", "👍🏽"))
    private val gif = MediaItem("hello", "Hello", "floating/hello.gif", "image/gif", listOf("wave"), listOf("👋"), true)

    @Test fun searchMatchesTagsTextAndEmojiPresentationVariants() {
        val items = listOf(love, family, gif)
        assertEquals(listOf(love), MediaSearch.search(items, MediaQuery("HEART")))
        assertEquals(listOf(love), MediaSearch.search(items, MediaQuery("❤")))
        assertEquals(listOf(love), MediaSearch.search(items, MediaQuery("love affection")))
        assertEquals(items, MediaSearch.search(items, MediaQuery("  ")))
        assertTrue(MediaSearch.search(items, MediaQuery("missing")).isEmpty())
        assertEquals(listOf(gif), MediaSearch.search(items, MediaQuery(filter = "👋")))
    }
    @Test fun emojiSequencesAndSkinTonesArePreserved() {
        assertEquals(listOf(family), MediaSearch.search(listOf(family), MediaQuery("👨‍👩‍👧‍👦")))
        assertEquals("👍🏽", MediaSearch.normalize("👍🏽"))
        assertEquals("👨‍👩‍👧‍👦", MediaSearch.normalize("👨‍👩‍👧‍👦"))
    }
    @Test fun searchIsIndependentOfDeviceLocale() {
        val prior = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"))
            assertEquals("wink", MediaSearch.normalize("WINK"))
        } finally { Locale.setDefault(prior) }
    }
    @Test fun preferenceNeverFlattensAnimationIntoASticker() {
        assertEquals(DeliveryChoice(Rendition.MEDIA, true), DeliveryPolicy.clipboard(gif, true, MessagingTarget.WHATSAPP))
        assertEquals(DeliveryChoice(Rendition.MEDIA, false), DeliveryPolicy.clipboard(gif, false, MessagingTarget.WHATSAPP))
    }
    @Test fun videoAlwaysUsesOriginalMediaEvenWithoutAnExplicitAnimationFlag() {
        val video = love.copy(id = "video", mimeType = "video/mp4")
        assertTrue(video.hasMotion)
        for (target in MessagingTarget.entries) {
            assertEquals(DeliveryChoice(Rendition.MEDIA, true), DeliveryPolicy.clipboard(video, true, target))
            assertEquals(DeliveryChoice(Rendition.MEDIA, false), DeliveryPolicy.clipboard(video, false, target))
        }
    }
    @Test fun onlyTheSupportedDestinationGetsTheClipboardStickerPayload() {
        assertEquals(Rendition.STICKER_PNG, DeliveryPolicy.clipboard(love, true, MessagingTarget.WHATSAPP).rendition)
        assertEquals(Rendition.STICKER_PNG, DeliveryPolicy.clipboard(love, true, MessagingTarget.WHATSAPP_BUSINESS).rendition)
        assertEquals(Rendition.MEDIA, DeliveryPolicy.clipboard(love, false, MessagingTarget.WHATSAPP).rendition)
        assertEquals(DeliveryChoice(Rendition.STICKER_WEBP, false), DeliveryPolicy.clipboard(love, true, MessagingTarget.TELEGRAM))
        assertEquals(Rendition.MEDIA, DeliveryPolicy.clipboard(love, false, MessagingTarget.TELEGRAM).rendition)
        assertEquals(DeliveryChoice(Rendition.MEDIA, true), DeliveryPolicy.clipboard(gif, true, MessagingTarget.TELEGRAM))
        assertEquals(DeliveryChoice(Rendition.MEDIA, true), DeliveryPolicy.clipboard(love, true, MessagingTarget.OTHER))
        assertEquals(MessagingTarget.TELEGRAM, MessagingTarget.fromPackage("org.telegram.messenger.web"))
    }
    @Test fun telegramMappingsKeepTheExactForkAndProtectBuiltinFamilies() {
        val fork = "org.example.telegram"
        assertEquals(MessagingTarget.TELEGRAM, AppFamilyResolver.resolve(fork, setOf(fork)))
        assertEquals(MessagingTarget.OTHER, AppFamilyResolver.resolve("org.example.other", setOf(fork)))
        assertEquals(MessagingTarget.WHATSAPP, AppFamilyResolver.resolve("com.whatsapp", setOf("com.whatsapp")))
        assertEquals(MessagingTarget.TELEGRAM, AppFamilyResolver.resolve("org.telegram.messenger", emptySet()))
        assertEquals(MessagingTarget.TELEGRAM, AppFamilyResolver.resolve("nu.gpu.nagramx", emptySet()))
        assertEquals(MessagingTarget.TELEGRAM, AppFamilyResolver.resolve("nu.gpu.nagram", emptySet()))
        assertEquals(MessagingTarget.OTHER, AppFamilyResolver.resolve("nu.gpu.nagramx.fake", emptySet()))
        assertEquals(MessagingTarget.OTHER, AppFamilyResolver.resolve(null, setOf(fork)))
        assertEquals(setOf("org.Example.fork", "org.example.second"), PackageNames.parse(" org.Example.fork,org.Example.fork\norg.example.second ").packages)
        assertEquals(listOf("invalid package", "bad..name"), PackageNames.parse("valid.package, invalid package,bad..name").invalid)
    }
    @Test fun discordKeepsBinaryMediaAndCannotBeRemappedAsTelegram() {
        assertEquals(MessagingTarget.DISCORD, AppFamilyResolver.resolve("com.discord", setOf("com.discord")))
        assertTrue("com.discord" in AppFamilyResolver.supported(emptySet()))
        assertEquals(Rendition.MEDIA, DeliveryPolicy.clipboard(gif, true, MessagingTarget.DISCORD).rendition)
        assertTrue(DeliveryPolicy.supportsPaste(love, MessagingTarget.DISCORD))
        assertTrue(DeliveryPolicy.supportsPaste(gif, MessagingTarget.DISCORD))
        assertFalse(DeliveryPolicy.supportsPaste(love.copy(mimeType = "video/webm"), MessagingTarget.DISCORD))
        assertFalse(DeliveryPolicy.supportsPaste(love.copy(mimeType = "video/mp4"), MessagingTarget.DISCORD))
    }
    @Test fun instagramKeepsMediaWithoutPromisingClipboardStickersOrPaste() {
        assertEquals(MessagingTarget.INSTAGRAM, AppFamilyResolver.resolve("com.instagram.android", setOf("com.instagram.android")))
        assertEquals(MessagingTarget.OTHER, AppFamilyResolver.resolve("com.instagram.android.fake", emptySet()))
        assertTrue("com.instagram.android" in AppFamilyResolver.supported(emptySet()))
        for (item in listOf(love, gif, love.copy(mimeType = "video/mp4"), love.copy(mimeType = "video/webm"))) {
            assertEquals(DeliveryChoice(Rendition.MEDIA, true), DeliveryPolicy.clipboard(item, true, MessagingTarget.INSTAGRAM))
            assertFalse(DeliveryPolicy.supportsPaste(item, MessagingTarget.INSTAGRAM))
        }
    }
    @Test fun foregroundDetectionDoesNotMistakeAnOlderChatForTheCurrentApp() {
        assertEquals("com.android.settings", ForegroundEvents.resolve(listOf(
            AppTransition("com.whatsapp", "Conversation", true),
            AppTransition("com.whatsapp", "Conversation", false),
            AppTransition("com.android.settings", "Main", true))))
        assertNull(ForegroundEvents.resolve(listOf(
            AppTransition("com.whatsapp", "Conversation", true),
            AppTransition("com.whatsapp", "Conversation", false))))
        assertNull(ForegroundEvents.resolve(listOf(
            AppTransition("com.whatsapp", "Conversation", true),
            AppTransition("com.android.settings", "Main", true))))
    }
}
