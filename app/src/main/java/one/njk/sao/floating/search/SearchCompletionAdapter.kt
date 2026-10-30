package one.njk.sao.floating.search

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Filter
import android.widget.Filterable
import androidx.core.view.isVisible
import one.njk.sao.databinding.ItemSourceSuggestionBinding
import one.njk.sao.floating.emoji.EmojiSuggestion
import one.njk.sao.floating.emoji.EmojiSuggestionAdapter
import one.njk.sao.source.SearchSuggestion

/** One native popup can show local emoji shortcuts or provider completions, never competing popups. */
internal class SearchCompletionAdapter : BaseAdapter(), Filterable {
    private val emoji=EmojiSuggestionAdapter()
    var rows: List<Any> = emptyList()
        private set
    fun emoji(rows: List<EmojiSuggestion>) { emoji.submit(rows); submit(rows) }
    fun source(rows: List<SearchSuggestion>) = submit(rows)
    private fun submit(value: List<Any>) { if(rows!=value) { rows=value; notifyDataSetChanged() } }
    override fun getCount()=rows.size
    override fun getItem(position: Int)=rows[position]
    override fun getItemId(position: Int)=position.toLong()
    override fun getView(position: Int,convertView: View?,parent: ViewGroup): View {
        if(getItem(position) is EmojiSuggestion) return emoji.getView(position,convertView,parent)
        val binding=convertView?.tag as? ItemSourceSuggestionBinding ?: ItemSourceSuggestionBinding.inflate(
            LayoutInflater.from(parent.context),parent,false).also { it.root.tag=it }
        val row=getItem(position) as SearchSuggestion
        binding.label.text=row.label; binding.detail.text=row.detail; binding.detail.isVisible=!row.detail.isNullOrBlank()
        return binding.root
    }
    fun value(item: Any?) = when(item) { is EmojiSuggestion -> item.emoji; is SearchSuggestion -> item.value; else -> "" }
    private val filter=object : Filter() {
        override fun performFiltering(constraint: CharSequence?)=FilterResults()
        override fun publishResults(constraint: CharSequence?,results: FilterResults?)=Unit
        override fun convertResultToString(resultValue: Any?)=value(resultValue)
    }
    override fun getFilter()=filter
}
