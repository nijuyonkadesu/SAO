/*
 * Cursor-token detection adapted from Telegram Android's MentionsAdapter.java,
 * copyright Nikolai Kudashov, 2013-2018, GNU GPL v2 or later.
 * Source and modifications: docs/emoji-autocomplete.md; third_party/telegram/LICENSE.
 */
package one.njk.sao.floating.emoji

/** UTF-16 offsets match Editable/Selection, including text containing surrogate pairs. */
internal data class EmojiShortcode(val start: Int, val end: Int, val keyword: String) {
    companion object {
        fun at(text: CharSequence, selectionStart: Int, selectionEnd: Int): EmojiShortcode? {
            if (selectionStart != selectionEnd || selectionStart !in 1..text.length) return null
            var start = selectionStart - 1
            while (start >= 0 && selectionStart - start <= 128 && isKeywordChar(text[start])) start--
            if (start < 0 || text[start] != ':' || start == selectionStart - 1) return null
            // Do not mistake a URL/port/time or an escaped colon for a shortcut.
            if (start > 0 && (text[start - 1].isLetterOrDigit() || text[start - 1] in ":/\\")) return null
            var end = selectionStart
            while (end < text.length && end - start <= 128 && isKeywordChar(text[end])) end++
            if (end - start > 128) return null
            if (end < text.length && text[end] == ':') end++
            return EmojiShortcode(start, end, text.subSequence(start + 1, selectionStart).toString())
        }

        private fun isKeywordChar(char: Char) = char.isLetterOrDigit() || char in "_+-"
    }
}
