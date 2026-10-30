package one.njk.sao.source

import org.junit.Assert.*
import org.junit.Test

class SearchSuggestionsTest {
    @Test fun editRangePreservesSurroundingQueryIncludingEmoji() {
        val text="🥳 blue_ha smile"
        val start=text.indexOf("blue"); val end=text.indexOf(' ',start)
        val page=SearchSuggestions(start,end,listOf(SearchSuggestion("blue_hair"))).validate(text,end)
        assertEquals("🥳 blue_hair smile",text.replaceRange(page.start,page.end,page.items.first().value))
    }
    @Test fun malformedRangesAndSurrogateSplitsAreRejected() {
        for(page in listOf(SearchSuggestions(-1,2,emptyList()),SearchSuggestions(0,9,emptyList()),SearchSuggestions(1,2,emptyList()))) {
            try { page.validate("🥳",2); fail("Invalid range accepted") } catch(_: IllegalArgumentException) { }
        }
    }
    @Test fun popupPayloadsAreBoundedAndSingleLine() {
        for(items in listOf(List(21) { SearchSuggestion("tag$it") },listOf(SearchSuggestion("bad\nquery")),listOf(SearchSuggestion("")))) {
            try { SearchSuggestions(0,2,items).validate("ta",2); fail("Invalid payload accepted") } catch(_: IllegalArgumentException) { }
        }
    }
}
