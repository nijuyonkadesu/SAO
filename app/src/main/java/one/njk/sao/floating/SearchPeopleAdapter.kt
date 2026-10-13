package one.njk.sao.floating

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil3.ImageLoader
import coil3.request.Disposable
import coil3.request.ImageRequest
import coil3.request.target
import kotlinx.coroutines.*
import one.njk.sao.R
import one.njk.sao.databinding.ItemSearchPersonBinding

internal class SearchPeopleAdapter(private val source: MediaSource, private val loader: ImageLoader,
    private val scope: CoroutineScope, private val choose: (SearchPerson) -> Unit) :
    ListAdapter<SearchPerson, SearchPeopleAdapter.Holder>(object : DiffUtil.ItemCallback<SearchPerson>() {
        override fun areItemsTheSame(a: SearchPerson, b: SearchPerson) = a.id == b.id && a.sourceId == b.sourceId
        override fun areContentsTheSame(a: SearchPerson, b: SearchPerson) = a == b
    }) {
    private val holders = mutableSetOf<Holder>()
    var selected: Set<String> = emptySet()
        set(value) { if (field != value) { field = value; holders.forEach { it.binding.personSelected.isChecked = it.person?.id in value } } }
    class Holder(val binding: ItemSearchPersonBinding) : RecyclerView.ViewHolder(binding.root) {
        var person: SearchPerson? = null
        var job: Job? = null
        var request: Disposable? = null
    }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(ItemSearchPersonBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun onBindViewHolder(holder: Holder, position: Int) {
        clear(holder)
        val person = getItem(position); holder.person = person
        val name = person.name.ifBlank { holder.binding.root.context.getString(R.string.search_person_unnamed) }
        holder.binding.personName.text = name
        holder.binding.personSelected.isChecked = person.id in selected
        holder.binding.root.contentDescription = name
        holder.binding.root.setOnClickListener { choose(person) }
        holder.binding.personSelected.setOnClickListener { choose(person) }
        load(holder)
    }
    private fun load(holder: Holder) {
        val person = holder.person ?: return
        holder.binding.portrait.setImageResource(R.drawable.ic_picker_person)
        holder.job = scope.launch {
            try {
                val data = source.personPreview(person) ?: return@launch
                if (holder.person != person) return@launch
                holder.request = loader.enqueue(ImageRequest.Builder(holder.binding.root.context).data(data)
                    .size(128, 128).target(holder.binding.portrait).build())
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* A missing portrait does not prevent selecting a person. */ }
        }
    }
    private fun clear(holder: Holder) {
        holder.job?.cancel(); holder.job = null; holder.request?.dispose(); holder.request = null
        holder.binding.portrait.setImageDrawable(null)
    }
    override fun onViewAttachedToWindow(holder: Holder) {
        holders += holder
        if (holder.job == null && holder.request == null) load(holder)
    }
    override fun onViewDetachedFromWindow(holder: Holder) { holders -= holder; clear(holder) }
    override fun onViewRecycled(holder: Holder) { holders -= holder; clear(holder); holder.person = null }
    fun close() { holders.forEach(::clear); holders.clear() }
}
