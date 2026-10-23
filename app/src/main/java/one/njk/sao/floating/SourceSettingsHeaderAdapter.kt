package one.njk.sao.floating

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import one.njk.sao.databinding.ItemSourceSettingsHeaderBinding

/** General controls share the page's scroll surface with profiles and installed APKs. */
internal class SourceSettingsHeaderAdapter(private val enableAll: (Boolean) -> Unit,private val uploadsWifiOnly: (Boolean)->Unit) : RecyclerView.Adapter<SourceSettingsHeaderAdapter.Holder>() {
    private var checked = false
    private var enabled = false
    private var wifiOnly=false
    private var uploadsEnabled=false
    fun updateUploads(checked: Boolean,enabled: Boolean) {
        if(wifiOnly==checked && uploadsEnabled==enabled) return
        wifiOnly=checked; uploadsEnabled=enabled; notifyItemChanged(0)
    }
    fun update(checked: Boolean, enabled: Boolean) {
        if (this.checked == checked && this.enabled == enabled) return
        this.checked = checked; this.enabled = enabled; notifyItemChanged(0)
    }
    override fun getItemCount() = 1
    override fun onCreateViewHolder(parent: ViewGroup, type: Int) = Holder(ItemSourceSettingsHeaderBinding.inflate(LayoutInflater.from(parent.context),parent,false))
    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.binding.allSources.apply {
            setOnCheckedChangeListener(null)
            isChecked = checked; isEnabled = enabled
            setOnCheckedChangeListener { _, value -> enableAll(value) }
        }
        holder.binding.uploadsWifiOnly.apply {
            setOnCheckedChangeListener(null)
            isChecked=wifiOnly; isEnabled=uploadsEnabled
            setOnCheckedChangeListener { _,value -> uploadsWifiOnly(value) }
        }
    }
    class Holder(val binding: ItemSourceSettingsHeaderBinding) : RecyclerView.ViewHolder(binding.root)
}
