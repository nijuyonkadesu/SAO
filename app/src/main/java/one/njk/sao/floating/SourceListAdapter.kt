package one.njk.sao.floating

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import one.njk.sao.R
import one.njk.sao.databinding.ItemSourceProfileBinding

/** Shared native list for the inline picker and settings; rows contain presentation values only. */
class SourceListAdapter(private val select: (String?) -> Unit,
    private val setFloatingEnabled: ((String, Boolean) -> Unit)? = null, private val onEdit: (String) -> Unit) :
    ListAdapter<SourceListAdapter.Row, SourceListAdapter.Holder>(object : DiffUtil.ItemCallback<Row>() {
        override fun areItemsTheSame(a: Row, b: Row) = a.id == b.id
        override fun areContentsTheSame(a: Row, b: Row) = a == b
    }) {
    data class Row(val id: String?, val name: String, val description: String, val selected: Boolean,
        val floatingEnabled: Boolean = true, val interactive: Boolean = true, val blocked: Boolean = false)
    class Holder(val binding: ItemSourceProfileBinding) : RecyclerView.ViewHolder(binding.root)
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        ItemSourceProfileBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = getItem(position)
        holder.binding.apply {
            name.text = row.name; description.text = row.description
            description.maxLines = if(row.blocked) 4 else 2
            check.visibility = if (row.selected) View.VISIBLE else View.INVISIBLE
            root.isSelected = row.selected
            root.strokeWidth = if (row.selected) (2 * root.resources.displayMetrics.density).toInt() else 0
            root.strokeColor = MaterialColors.getColor(root, androidx.appcompat.R.attr.colorPrimary)
            root.contentDescription = if (row.selected) root.context.getString(R.string.source_selected, row.name) else row.name
            root.setOnClickListener { select(row.id) }
            root.isEnabled = row.interactive && !row.blocked
            edit.isVisible = row.id != null && row.id != ALL_SOURCE_ID
            edit.contentDescription = root.context.getString(R.string.source_edit, row.name)
            edit.setOnClickListener { row.id?.let(onEdit) }
            edit.isEnabled = row.interactive
            floatingEnabled.isVisible = setFloatingEnabled != null && row.id != null && row.id != ALL_SOURCE_ID
            floatingEnabled.setOnCheckedChangeListener(null)
            floatingEnabled.isChecked = row.floatingEnabled
            floatingEnabled.isEnabled = row.interactive && !row.blocked
            floatingEnabled.contentDescription = root.context.getString(R.string.source_floating_enabled, row.name)
            floatingEnabled.setOnCheckedChangeListener { _, checked -> row.id?.let { setFloatingEnabled?.invoke(it, checked) } }
        }
    }
}
