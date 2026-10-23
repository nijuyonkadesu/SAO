package one.njk.sao.floating.emoji

import org.junit.Assert.*
import org.junit.Test

class EmojiKeywordIndexTest {
    private val index = EmojiKeywordIndex(EmojiKeywordIndex.read("""
        # emoji dictionary
        😉	winking face	wink	wink|winking face|flirt
        😜	winking face with tongue	stuck_out_tongue_winking_eye	wink|winking|tongue
        ❤️	red heart	heart	love|red heart
        ❤	heart	heart_symbol	heart
        ☕	hot beverage	coffee	café|hot beverage
        👩‍💻	woman technologist	woman_technologist	coder|woman technologist
    """.trimIndent().reader()))

    @Test fun exactAliasWinsOverKeywordMatchesAndEquivalentEmojiAreDeduplicated() {
        assertEquals("😉", index.suggest("wink").first().emoji)
        assertEquals(setOf("😉", "😜"), index.suggest("wink").map { it.emoji }.toSet())
        assertEquals(1, index.suggest("heart").count { it.emoji.replace("\ufe0f", "") == "❤" })
    }
    @Test fun prefixWordsUnderscoresCaseAndAccentsAreSearchable() {
        assertEquals("😉", index.suggest("FLIR").single().emoji)
        assertEquals("👩‍💻", index.suggest("woman_tech").single().emoji)
        assertEquals("👩‍💻", index.suggest("code").single().emoji)
        assertEquals("☕", index.suggest("cafe").single().emoji)
        assertTrue(index.suggest("zzz-unmatched").isEmpty())
        assertTrue(index.suggest("").isEmpty())
    }
}
