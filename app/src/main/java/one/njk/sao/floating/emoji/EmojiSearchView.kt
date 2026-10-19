package one.njk.sao.floating.emoji

import android.content.Context
import android.util.AttributeSet
import one.njk.sao.floating.search.SearchCompletionView

/** Existing picker XML/API name; emoji and extension completions share one Material popup. */
class EmojiSearchView @JvmOverloads constructor(context: Context,attrs: AttributeSet?=null,
    defStyleAttr: Int=androidx.appcompat.R.attr.autoCompleteTextViewStyle) : SearchCompletionView(context,attrs,defStyleAttr)
