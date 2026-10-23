package one.njk.sao.floating

import android.icu.text.BreakIterator
import java.util.Locale

/** Split user input into complete emoji, preserving flags, skin tones and ZWJ sequences. */
internal object EmojiShortcuts {
    const val DEFAULT = "😊 😂 ❤️ 😭 😡 👍 🎉 🐱"
    fun parse(value: String): List<String> {
        val iterator=BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(value) }
        val result=linkedSetOf<String>(); var start=iterator.first(); var end=iterator.next()
        while(end!=BreakIterator.DONE && result.size<32) {
            val item=value.substring(start,end)
            if(item.codePoints().anyMatch { it in 0x1f000..0x1faff || it in 0x2600..0x27ff || it==0x20e3 || it==0x00a9 || it==0x00ae }) result+=item
            start=end; end=iterator.next()
        }
        return result.toList()
    }
}
