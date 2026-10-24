package one.njk.sao.floating

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import one.njk.sao.R
import one.njk.sao.databinding.ItemInstalledExtensionBinding
import one.njk.sao.databinding.ItemInstalledExtensionsHeadingBinding
import one.njk.sao.extensions.*

/** A package appears once, even when it has several services or no configured profiles. */
internal class InstalledExtensionsAdapter(private val info: (String) -> Unit, private val uninstall: (String) -> Unit, private val review: (InstalledExtensionApp) -> Unit,
    private val install: (CatalogExtension)->Unit = {},private val cancel: ()->Unit = {}) :
    ListAdapter<InstalledExtensionsAdapter.Row, RecyclerView.ViewHolder>(object : DiffUtil.ItemCallback<Row>() {
        override fun areItemsTheSame(old: Row, new: Row) = old.app?.packageName == new.app?.packageName
        override fun areContentsTheSame(old: Row, new: Row) = old == new
        override fun getChangePayload(old: Row,new: Row): Any? =
            if(old.app!=null && old.app==new.app && old.interactive==new.interactive) Unit else null
    }) {
    data class Row(val app: InstalledExtensionApp? = null, val empty: Boolean = false, val interactive: Boolean = true,val entry: CatalogExtension?=null,val installState: InstallRowState=InstallRowState())
    fun submitApps(apps: List<InstalledExtensionApp>, loaded: Boolean, saving: Boolean = false,catalog: CatalogState=CatalogState()) {
        submitList(listOf(Row(empty = loaded && apps.isEmpty())) + apps.map { Row(it,interactive=loaded && !saving,entry=catalog.entries.firstOrNull { entry -> entry.packageName==it.packageName },installState=catalog.installRow(it.packageName)) })
    }
    override fun getItemViewType(position: Int) = if (getItem(position).app == null) 0 else 1
    override fun onCreateViewHolder(parent: ViewGroup, type: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (type == 0) Heading(ItemInstalledExtensionsHeadingBinding.inflate(inflater,parent,false))
            else App(ItemInstalledExtensionBinding.inflate(inflater,parent,false))
    }
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val row = getItem(position)
        when (holder) {
            is Heading -> holder.binding.description.setText(if (row.empty) R.string.source_extensions_empty else R.string.source_extensions_description)
            is App -> {
                val app = checkNotNull(row.app)
                val context = holder.itemView.context
                holder.binding.icon.setImageDrawable(app.icon ?: extensionIconPlaceholder(holder.binding.icon))
                holder.binding.name.text = app.name
                holder.binding.details.text = listOf(app.version, app.packageName).filter(String::isNotBlank).joinToString(" · ")
                holder.binding.trustStatus.setText(when(app.approval) {
                    ExtensionApproval.PUBLISHER -> R.string.source_trust_publisher
                    ExtensionApproval.USER -> R.string.source_trust_user
                    ExtensionApproval.UNTRUSTED -> R.string.source_trust_untrusted
                })
                holder.binding.trust.isVisible=app.approval!=ExtensionApproval.PUBLISHER
                holder.binding.trust.isEnabled=app.compatible && row.interactive
                holder.binding.trust.setText(if(app.approval==ExtensionApproval.USER) R.string.source_trust_revoke else R.string.source_trust_review)
                holder.binding.trust.setOnClickListener { review(app) }
                holder.binding.appInfo.contentDescription = context.getString(R.string.source_extension_app_info_name,app.name)
                holder.binding.uninstall.contentDescription = context.getString(R.string.source_extension_uninstall_name,app.name)
                holder.binding.appInfo.setOnClickListener { info(app.packageName) }
                holder.binding.uninstall.setOnClickListener { uninstall(app.packageName) }
                bindInstall(holder.binding,row.entry,app,row.installState,row.interactive,install,cancel)
            }
        }
    }
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder,position: Int,payloads: MutableList<Any>) {
        if(payloads.isEmpty() || holder !is App) onBindViewHolder(holder,position)
        else getItem(position).let { row -> bindInstall(holder.binding,row.entry,row.app,row.installState,row.interactive,install,cancel) }
    }
    private class Heading(val binding: ItemInstalledExtensionsHeadingBinding) : RecyclerView.ViewHolder(binding.root)
    private class App(val binding: ItemInstalledExtensionBinding) : RecyclerView.ViewHolder(binding.root)
}
