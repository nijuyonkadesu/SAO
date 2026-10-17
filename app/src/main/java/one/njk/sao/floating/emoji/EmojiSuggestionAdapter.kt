package one.njk.sao.floating.emoji

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Filter
import android.widget.Filterable
import one.njk.sao.R
import one.njk.sao.databinding.ItemEmojiSuggestionBinding

/** Android owns popup input/navigation. Lookup is handled by the lifecycle-bound coroutine. */
internal class EmojiSuggestionAdapter : BaseAdapter(), Filterable {
    var suggestions: List<EmojiSuggestion> = emptyList()
        private set
    fun submit(value: List<EmojiSuggestion>) {
        if (suggestions == value) return
        suggestions = value; notifyDataSetChanged()
    }
    override fun getCount() = suggestions.size
    override fun getItem(position: Int) = suggestions[position]
    override fun getItemId(position: Int) = position.toLong()
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val binding = convertView?.tag as? ItemEmojiSuggestionBinding ?:
            ItemEmojiSuggestionBinding.inflate(LayoutInflater.from(parent.context), parent, false).also { it.root.tag = it }
        val item = getItem(position)
        binding.emoji.text = item.emoji
        binding.keyword.text = parent.context.getString(R.string.emoji_suggestion_keyword, item.keyword)
        binding.root.contentDescription = parent.context.getString(R.string.emoji_suggestion_insert, item.description)
        return binding.root
    }
    private val noFilter = object : Filter() {
        override fun performFiltering(constraint: CharSequence?) = FilterResults()
        override fun publishResults(constraint: CharSequence?, results: FilterResults?) = Unit
        override fun convertResultToString(resultValue: Any?) = (resultValue as? EmojiSuggestion)?.emoji.orEmpty()
    }
    override fun getFilter(): Filter = noFilter
}
