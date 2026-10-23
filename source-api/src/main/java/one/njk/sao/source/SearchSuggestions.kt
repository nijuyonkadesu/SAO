package one.njk.sao.source

/** A provider-defined replacement for one range of the current query, not a new search result. */
data class SearchSuggestion(val value: String, val label: String = value, val detail: String? = null)

data class SearchSuggestions(val start: Int, val end: Int, val items: List<SearchSuggestion>) {
    fun validate(text: String, cursor: Int): SearchSuggestions = apply {
        require(start in 0..cursor && cursor <= end && end <= text.length && items.size <= 20)
        require(boundary(text,start) && boundary(text,end) && boundary(text,cursor))
        require(items.all { it.value.isNotBlank() && it.value.length <= 512 && it.label.isNotBlank() &&
            it.label.length <= 256 && (it.detail?.length ?: 0) <= 128 &&
            !it.value.contains('\n') && !it.value.contains('\r') })
    }
    companion object {
        fun empty(cursor: Int) = SearchSuggestions(cursor,cursor,emptyList())
        private fun boundary(text: String,index: Int) = index == 0 || index == text.length ||
            !(text[index].isLowSurrogate() && text[index-1].isHighSurrogate())
    }
}
