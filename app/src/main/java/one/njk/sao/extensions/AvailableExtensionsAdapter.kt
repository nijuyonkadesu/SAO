package one.njk.sao.extensions

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil3.ImageLoader
import coil3.request.Disposable
import coil3.request.ImageRequest
import coil3.request.error
import coil3.request.placeholder
import coil3.request.target
import one.njk.sao.R
import one.njk.sao.databinding.ItemExtensionCatalogHeadingBinding
import one.njk.sao.databinding.ItemExtensionRepositoryBinding
import one.njk.sao.databinding.ItemInstalledExtensionBinding
import one.njk.sao.floating.InstalledExtensionApp

internal class AvailableExtensionsAdapter(private val refresh: ()->Unit,private val install: (CatalogExtension)->Unit,
    private val cancel: ()->Unit,private val loader: ImageLoader,private val addRepository: ()->Unit,
    private val removeRepository: (String)->Unit): ListAdapter<AvailableExtensionsAdapter.Row,RecyclerView.ViewHolder>(
        object: DiffUtil.ItemCallback<Row>() {
            override fun areItemsTheSame(a: Row,b: Row)=a.key==b.key
            override fun areContentsTheSame(a: Row,b: Row)=a==b
            override fun getChangePayload(a: Row,b: Row): Any? = when {
                a is Row.Card && b is Row.Card && a.entry==b.entry -> Unit
                a is Row.Header && b is Row.Header -> Unit
                a is Row.Repository && b is Row.Repository -> Unit
                else -> null
            }
        }) {
    sealed interface Row {
        val key: String
        data class Header(val status: Int,val canRefresh: Boolean,val canAdd: Boolean): Row { override val key="heading" }
        data class Repository(val url: String,val error: Int?): Row { override val key="repository:$url" }
        data class Card(val entry: CatalogExtension,val installState: InstallRowState,val interactive: Boolean): Row {
            override val key="package:${entry.packageName}"
        }
    }
    fun update(state: CatalogState,installed: List<InstalledExtensionApp>,interactive: Boolean) {
        val packages=installed.map { it.packageName }.toSet()
        val status=state.error ?: when {
            state.loading || !state.repositoriesLoaded -> R.string.extension_catalog_loading
            state.repositories.isEmpty() -> R.string.extension_repositories_empty
            else -> R.string.extension_catalog_description
        }
        submitList(buildList {
            add(Row.Header(status,!state.loading && state.activePackage==null && state.repositories.isNotEmpty(),state.repositoriesLoaded))
            state.repositories.forEach { add(Row.Repository(it,state.repositoryErrors[it])) }
            state.entries.filter { it.packageName !in packages }.forEach { add(Row.Card(it,state.installRow(it.packageName),interactive)) }
        })
    }
    override fun getItemViewType(position: Int)=when(getItem(position)) { is Row.Header -> 0; is Row.Repository -> 1; is Row.Card -> 2 }
    override fun onCreateViewHolder(parent: ViewGroup,viewType: Int): RecyclerView.ViewHolder {
        val inflater=LayoutInflater.from(parent.context)
        return when(viewType) {
            0 -> Header(ItemExtensionCatalogHeadingBinding.inflate(inflater,parent,false))
            1 -> Repository(ItemExtensionRepositoryBinding.inflate(inflater,parent,false))
            else -> Card(ItemInstalledExtensionBinding.inflate(inflater,parent,false),loader)
        }
    }
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder,position: Int) = bind(holder,getItem(position),false)
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder,position: Int,payloads: MutableList<Any>) = bind(holder,getItem(position),payloads.isNotEmpty())
    private fun bind(holder: RecyclerView.ViewHolder,row: Row,partial: Boolean) {
        when {
            holder is Header && row is Row.Header -> holder.binding.apply {
                status.setText(row.status)
                refresh.isEnabled=row.canRefresh; refresh.setOnClickListener { this@AvailableExtensionsAdapter.refresh() }
                addRepository.isEnabled=row.canAdd; addRepository.setOnClickListener { this@AvailableExtensionsAdapter.addRepository() }
            }
            holder is Repository && row is Row.Repository -> holder.binding.apply {
                repositoryUrl.text=row.url
                status.isVisible=row.error!=null; status.text=row.error?.let { root.context.getString(it) }
                remove.contentDescription=root.context.getString(R.string.extension_repository_remove_name,row.url)
                remove.setOnClickListener { removeRepository(row.url) }
            }
            holder is Card && row is Row.Card -> {
                if(!partial) holder.binding.apply {
                    holder.bindIcon(row.entry.iconUrl,row.entry.versionCode)
                    name.text=row.entry.name
                    details.text=root.context.getString(R.string.extension_repository_origin,row.entry.packageName,RepositoryUrl.label(row.entry.repositoryUrl))
                    trustStatus.setText(if(row.entry.publisherVerified) R.string.source_trust_publisher else R.string.extension_repository_unverified)
                    trust.isVisible=false; appInfo.isVisible=false; uninstall.isVisible=false
                }
                bindInstall(holder.binding,row.entry,null,row.installState,row.interactive,install,cancel)
            }
        }
    }
    private class Header(val binding: ItemExtensionCatalogHeadingBinding): RecyclerView.ViewHolder(binding.root)
    private class Repository(val binding: ItemExtensionRepositoryBinding): RecyclerView.ViewHolder(binding.root)
    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if(holder is Card) holder.clearIcon()
        super.onViewRecycled(holder)
    }
    private class Card(val binding: ItemInstalledExtensionBinding,private val loader: ImageLoader): RecyclerView.ViewHolder(binding.root) {
        private var iconKey: String?=null
        private var request: Disposable?=null
        init { binding.icon.setImageDrawable(extensionIconPlaceholder(binding.icon)) }
        fun bindIcon(url: String?,version: Long) {
            val key=url?.let { "catalog-icon:$it|$version" }
            if(iconKey==key) return
            clearIcon(); iconKey=key
            if(key==null) return
            val pixels=(40*binding.root.resources.displayMetrics.density).toInt().coerceIn(1,256)
            request=loader.enqueue(ImageRequest.Builder(binding.root.context).data(url).memoryCacheKey(key).diskCacheKey(key).size(pixels,pixels)
                .decoderFactory(CatalogIconDecoder.factory(pixels))
                .placeholder(binding.icon.drawable).error(binding.icon.drawable).target(binding.icon).build())
        }
        fun clearIcon() {
            request?.dispose(); request=null; iconKey=null
            binding.icon.setImageDrawable(extensionIconPlaceholder(binding.icon))
        }
    }
}
