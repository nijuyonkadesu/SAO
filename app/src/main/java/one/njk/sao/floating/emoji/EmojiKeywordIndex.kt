package one.njk.sao.floating.emoji

import java.io.Reader
import java.text.Normalizer
import java.util.Locale

internal data class EmojiKeywordEntry(val emoji: String, val description: String, val aliases: List<String>, val keywords: List<String>)
internal data class EmojiSuggestion(val emoji: String, val keyword: String, val description: String)

/** Immutable dictionary, not a cache of previous queries or media search results. */
internal class EmojiKeywordIndex(entries: List<EmojiKeywordEntry>) {
    private data class Term(val key: String, val display: String, val entry: EmojiKeywordEntry, val alias: Boolean)
    private data class Hit(val term: Term, val rank: Int)
    private val terms = entries.flatMap { entry ->
        val aliases = entry.aliases.map { Term(normalize(it), it, entry, true) }
        val keywords = entry.keywords.flatMap { phrase ->
            (listOf(phrase) + WORD.findAll(phrase).map { it.value }).distinct().map { Term(normalize(it), it, entry, false) }
        }
        aliases + keywords
    }.distinctBy { Triple(it.key, it.entry.emoji, it.alias) }.sortedBy { it.key }

    fun suggest(keyword: String): List<EmojiSuggestion> {
        val key = normalize(keyword)
        if (key.isEmpty()) return emptyList()
        var low = 0; var high = terms.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (terms[middle].key < key) low = middle + 1 else high = middle
        }
        val hits = HashMap<String, Hit>()
        val order = compareBy<Hit> { it.rank }.thenBy { it.term.key.length }.thenBy { it.term.entry.description }.thenBy { it.term.entry.emoji }
        var index = low
        while (index < terms.size && terms[index].key.startsWith(key)) {
            val term = terms[index++]
            val hit = Hit(term, when { term.alias && term.key == key -> 0; term.alias -> 1; term.key == key -> 2; else -> 3 })
            val identity = term.entry.emoji.replace("\ufe0f", "")
            val old = hits[identity]
            if (old == null || order.compare(hit, old) < 0) hits[identity] = hit
        }
        return hits.values.sortedWith(order).map { EmojiSuggestion(it.term.entry.emoji, it.term.display, it.term.entry.description) }
    }

    companion object {
        private val WORD = Regex("[\\p{L}\\p{N}+-]+")
        private fun normalize(value: String) = Normalizer.normalize(value, Normalizer.Form.NFD)
            .lowercase(Locale.ROOT).filterNot { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }.replace('_', ' ')

        fun read(reader: Reader): List<EmojiKeywordEntry> = reader.buffered().useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith('#') }.map { line ->
                val fields = line.split('\t')
                require(fields.size == 4)
                EmojiKeywordEntry(fields[0], fields[1], fields[2].split('|'), fields[3].split('|'))
            }.toList()
        }
    }
}
