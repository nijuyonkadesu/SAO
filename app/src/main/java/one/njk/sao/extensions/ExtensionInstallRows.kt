package one.njk.sao.extensions

import android.os.Build
import coil3.ImageLoader
import coil3.request.Disposable
import coil3.request.ImageRequest
import coil3.request.target
import coil3.request.placeholder
import coil3.request.error
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import one.njk.sao.R
import one.njk.sao.databinding.ItemExtensionCatalogHeadingBinding
import one.njk.sao.databinding.ItemInstalledExtensionBinding
import one.njk.sao.floating.InstalledExtensionApp
import one.njk.sao.source.ExtensionProtocol

internal fun extensionIconPlaceholder(view: android.view.View): android.graphics.drawable.Drawable =
    requireNotNull(androidx.appcompat.content.res.AppCompatResources.getDrawable(view.context,R.drawable.ic_picker_source)).mutate().apply {
        setTint(com.google.android.material.color.MaterialColors.getColor(view,com.google.android.material.R.attr.colorOnSurfaceVariant))
    }

internal enum class CatalogAction { INSTALL, UPDATE, CURRENT, NEWER, INCOMPATIBLE, DIFFERENT_PUBLISHER }
internal fun CatalogExtension.action(installedVersion: Long?,installedSigner: String?,sdk: Int,abis: List<String>): CatalogAction = when {
    installedVersion!=null && installedSigner!=signerSha256 -> CatalogAction.DIFFERENT_PUBLISHER
    apiVersion!=ExtensionProtocol.VERSION || sdk<minSdk || artifact(abis)==null -> CatalogAction.INCOMPATIBLE
    installedVersion==null -> CatalogAction.INSTALL
    installedVersion<versionCode -> CatalogAction.UPDATE
    installedVersion==versionCode -> CatalogAction.CURRENT
    else -> CatalogAction.NEWER
}

internal fun bindInstall(binding: ItemInstalledExtensionBinding,entry: CatalogExtension?,installed: InstalledExtensionApp?,
    state: CatalogState,interactive: Boolean,install: (CatalogExtension)->Unit,cancel: ()->Unit) {
    val context=binding.root.context
    val active=entry!=null && state.activePackage==entry.packageName
    val action=entry?.action(installed?.versionCode,installed?.signer,Build.VERSION.SDK_INT,Build.SUPPORTED_ABIS.toList())
    val failure=entry?.let { state.failures[it.packageName] }
    binding.catalogDescription.isVisible=entry!=null
    binding.catalogDescription.text=entry?.description
    binding.installProgress.isVisible=active
    binding.installProgress.isIndeterminate=active && state.phase!=InstallPhase.DOWNLOADING
    if(active && state.phase==InstallPhase.DOWNLOADING) binding.installProgress.setProgressCompat(state.progress,false)
    val size=entry?.artifact(Build.SUPPORTED_ABIS.toList())?.let { Formatter.formatShortFileSize(context,it.size) }.orEmpty()
    binding.installStatus.text=when {
        failure!=null -> context.getString(failure)
        active -> context.getString(when(state.phase) {
            InstallPhase.DOWNLOADING -> R.string.extension_downloading
            InstallPhase.READY -> R.string.extension_ready
            else -> R.string.extension_waiting_installer
        },state.progress)
        action==CatalogAction.INCOMPATIBLE -> context.getString(R.string.extension_not_compatible)
        action==CatalogAction.DIFFERENT_PUBLISHER -> context.getString(R.string.extension_signature_mismatch)
        action==CatalogAction.NEWER -> context.getString(R.string.extension_newer_installed)
        entry!=null -> context.getString(if(entry.channel=="development") R.string.extension_development_version else R.string.extension_catalog_version,entry.versionName,size)
        else -> ""
    }
    binding.installStatus.isVisible=entry!=null
    val cancellable=active && state.phase in setOf(InstallPhase.DOWNLOADING,InstallPhase.READY)
    binding.installExtension.isVisible=cancellable || action in setOf(CatalogAction.INSTALL,CatalogAction.UPDATE)
    binding.installExtension.setText(if(cancellable) android.R.string.cancel else if(action==CatalogAction.UPDATE) R.string.extension_update else R.string.extension_install)
    binding.installExtension.isEnabled=interactive && (state.activePackage==null || cancellable)
    binding.installExtension.contentDescription=context.getString(if(cancellable) R.string.extension_cancel_name else if(action==CatalogAction.UPDATE) R.string.extension_update_name else R.string.extension_install_name,entry?.name.orEmpty())
    binding.installExtension.setOnClickListener { if(cancellable) cancel() else entry?.let(install) }
}

internal class AvailableExtensionsAdapter(private val refresh: ()->Unit,private val install: (CatalogExtension)->Unit,private val cancel: ()->Unit,private val loader: ImageLoader):
    ListAdapter<AvailableExtensionsAdapter.Row,RecyclerView.ViewHolder>(object: DiffUtil.ItemCallback<Row>() {
        override fun areItemsTheSame(a: Row,b: Row)=a.entry?.packageName==b.entry?.packageName
        override fun areContentsTheSame(a: Row,b: Row)=a==b
    }) {
    data class Row(val entry: CatalogExtension?=null,val state: CatalogState=CatalogState(),val interactive: Boolean=true)
    fun update(state: CatalogState,installed: List<InstalledExtensionApp>,interactive: Boolean) {
        val packages=installed.map { it.packageName }.toSet()
        submitList(listOf(Row(state=state,interactive=interactive))+state.entries.filter { it.packageName !in packages }.map { Row(it,state,interactive) })
    }
    override fun getItemViewType(position: Int)=if(getItem(position).entry==null) 0 else 1
    override fun onCreateViewHolder(parent: ViewGroup,viewType: Int): RecyclerView.ViewHolder {
        val inflater=LayoutInflater.from(parent.context)
        return if(viewType==0) Header(ItemExtensionCatalogHeadingBinding.inflate(inflater,parent,false)) else Card(ItemInstalledExtensionBinding.inflate(inflater,parent,false),loader)
    }
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder,position: Int) {
        val row=getItem(position)
        when(holder) {
            is Header -> {
                holder.binding.status.setText(row.state.error ?: if(row.state.loading) R.string.extension_catalog_loading else R.string.extension_catalog_description)
                holder.binding.refresh.setText(if(row.state.error!=null) R.string.home_retry_action else R.string.extension_check_updates)
                holder.binding.refresh.isEnabled=!row.state.loading && row.state.activePackage==null
                holder.binding.refresh.setOnClickListener { refresh() }
            }
            is Card -> {
                val entry=requireNotNull(row.entry)
                holder.bindIcon(entry.iconUrl,entry.versionCode)
                holder.binding.name.text=entry.name
                holder.binding.details.text=entry.packageName
                holder.binding.trustStatus.setText(R.string.source_trust_publisher)
                holder.binding.trust.isVisible=false
                holder.binding.appInfo.isVisible=false; holder.binding.uninstall.isVisible=false
                bindInstall(holder.binding,entry,null,row.state,row.interactive,install,cancel)
            }
        }
    }
    private class Header(val binding: ItemExtensionCatalogHeadingBinding): RecyclerView.ViewHolder(binding.root)
    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if(holder is Card) holder.clearIcon()
        super.onViewRecycled(holder)
    }
    private class Card(val binding: ItemInstalledExtensionBinding,private val loader: ImageLoader): RecyclerView.ViewHolder(binding.root) {
        private var iconUrl: String?=null
        private var request: Disposable?=null
        init { binding.icon.setImageDrawable(extensionIconPlaceholder(binding.icon)) }
        fun bindIcon(url: String?,version: Long) {
            // A published version refreshes artwork; progress/state rebinds reuse its request/cache.
            val versionedUrl=url?.let { "$it?v=$version" }
            if(iconUrl==versionedUrl) return
            clearIcon()
            iconUrl=versionedUrl
            if(versionedUrl==null) return
            val pixels=(40*binding.root.resources.displayMetrics.density).toInt().coerceIn(1,256)
            request=loader.enqueue(ImageRequest.Builder(binding.root.context).data(versionedUrl).size(pixels,pixels)
                .decoderFactory(CatalogIconDecoder.factory(pixels))
                .placeholder(binding.icon.drawable).error(binding.icon.drawable).target(binding.icon).build())
        }
        fun clearIcon() {
            request?.dispose(); request=null; iconUrl=null
            binding.icon.setImageDrawable(extensionIconPlaceholder(binding.icon))
        }
    }
}
