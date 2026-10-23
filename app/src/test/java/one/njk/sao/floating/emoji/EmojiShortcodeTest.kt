package one.njk.sao.floating.emoji

import org.junit.Assert.*
import org.junit.Test

class EmojiShortcodeTest {
    @Test fun detectsTheShortcutAtTheCursorAndIncludesItsUnfinishedSuffix() {
        val text = "😀 happy :wink: later"
        val cursor = text.indexOf("wink") + 2
        val token = EmojiShortcode.at(text, cursor, cursor)!!
        assertEquals("wi", token.keyword)
        assertEquals(":wink:", text.substring(token.start, token.end))
        assertEquals("😀 happy 😉 later", text.replaceRange(token.start, token.end, "😉"))
    }
    @Test fun acceptsOrdinaryAliasesAndUnicodeKeywords() {
        for (query in listOf(":wink", ":thumbs_up", ":+1", ":-1", ":100", ":café")) {
            val token = EmojiShortcode.at(query, query.length, query.length)!!
            assertEquals(query.drop(1), token.keyword)
        }
    }
    @Test fun selectionsUrlsTimesEscapesAndPlainTextAreNotShortcuts() {
        for (query in listOf("wink", ":", ": ", "12:30", "http://host:80", "\\:wink", "word:wink", ":wink ", "::wink"))
            assertNull(query, EmojiShortcode.at(query, query.length, query.length))
        assertNull(EmojiShortcode.at(":wink", 0, 5))
        assertNull(EmojiShortcode.at(":wink", -1, -1))
        assertNull(EmojiShortcode.at(":wink", 9, 9))
    }
    @Test fun oversizedPastedTokensCannotBePartiallyReplaced() {
        val text = ":wink" + "x".repeat(500)
        assertNull(EmojiShortcode.at(text, 5, 5))
        assertNull(EmojiShortcode.at(text, text.length, text.length))
    }
}
