package one.njk.sao.source.settings

import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup

/** Model-owned choice: rendering never writes preferences or invokes a selection callback. */
class SourceChipChoices<T>(private val group: ChipGroup,private val options: List<Pair<T,Chip>>,selected: (T)->Unit) {
    private var rendering=false
    init {
        require(options.isNotEmpty() && options.map { it.first }.distinct().size==options.size)
        group.isSingleSelection=true; group.isSelectionRequired=true
        group.setOnCheckedStateChangeListener { _,ids ->
            if(!rendering) options.firstOrNull { it.second.id in ids }?.let { selected(it.first) }
        }
    }
    fun render(value: T,enabled: Boolean=true) {
        rendering=true
        try { options.forEach { it.second.isEnabled=enabled }; group.check(options.single { it.first==value }.second.id) }
        finally { rendering=false }
    }
}
