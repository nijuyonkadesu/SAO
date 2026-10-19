package one.njk.sao.extension.moeview

import one.njk.sao.moeview.BooruApi
import one.njk.sao.source.*
import java.text.NumberFormat
import java.util.Locale

/** Board-specific token syntax stays in the provider. SAO only applies the returned edit range. */
internal object MoeviewSuggestions {
    suspend fun query(profile: MoeviewProfile, query: MediaQuery, cursor: Int, api: BooruApi): SearchSuggestions {
        require(query.text.length<=2048 && cursor in 0..query.text.length)
        SearchSuggestions.empty(cursor).validate(query.text,cursor)
        if(query.mode!=SearchMode.DESCRIPTION || cursor==0) return SearchSuggestions.empty(cursor)
        val text=query.text
        var start=cursor; while(start>0 && !text[start-1].isWhitespace()) start--
        var end=cursor; while(end<text.length && !text[end].isWhitespace()) end++
        val token=text.substring(start,cursor)
        val operator=token.take(1).takeIf { it=="-" || it=="~" }.orEmpty()
        val prefix=token.removePrefix(operator)
        if(prefix.codePointCount(0,prefix.length)<2 || prefix.any { it in ":*%" }) return SearchSuggestions.empty(cursor)
        val numbers=NumberFormat.getIntegerInstance(Locale.getDefault())
        val rows=api.tags(profile.config.board,prefix,12).filter { it.name.startsWith(prefix,true) && it.name.length<=250 }
            .distinctBy { it.name }.sortedByDescending { it.count }.map { tag ->
                SearchSuggestion(operator+tag.name+if(end==text.length) " " else "",tag.name,
                    "${tag.category.label} · ${numbers.format(tag.count)}")
            }
        return SearchSuggestions(start,end,rows).validate(text,cursor)
    }
}
