package one.njk.sao.extension.local

import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.*
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.DynamicColors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import one.njk.sao.extension.local.databinding.ActivityMetadataBinding
import one.njk.sao.source.*

@OptIn(FlowPreview::class,ExperimentalCoroutinesApi::class)
class MetadataViewModel(app: Application, saved: SavedStateHandle) : AndroidViewModel(app) {
    data class State(val items: List<LocalAsset> = emptyList(),val more: Boolean=false,val loading: Boolean=false,val error: Boolean=false)
    val profile: String=checkNotNull(saved[ExtensionProtocol.PROFILE])
    private val store=LocalStore.get(app)
    private val mutable=MutableStateFlow(State())
    val state=mutable.asStateFlow()
    val query=MutableStateFlow("")
    private var work: Job?=null
    init { viewModelScope.launch {
        mutable.subscriptionCount.map { it>0 }.distinctUntilChanged().flatMapLatest { visible ->
            if(visible) combine(query,store.changes) { text,_ -> text }.debounce(250)
            else { work?.cancel(); emptyFlow() }
        }.collect { load(it,false) }
    } }
    @OptIn(FlowPreview::class)
    private fun load(text: String, more: Boolean) {
        work?.cancel()
        val previous=state.value.items.takeIf { more || text==lastQuery }.orEmpty()
        lastQuery=text
        mutable.value=state.value.copy(loading=true,error=false)
        work=viewModelScope.launch {
            try {
                val boundary=if(more) previous.lastOrNull()?.let { it.modified to it.id } else null
                val rows=withContext(Dispatchers.IO) { store.search(profile,MediaQuery(text,mode=SearchMode.DESCRIPTION),boundary)
                    .map { it.copy(ocr="",ocrOverride=null,description=null) } }
                val merged=if(more) previous+rows.take(60) else {
                    val updated=rows.associateBy { it.id }; previous.map { updated[it.id] ?: it }+rows.take(60)
                }
                mutable.value=State(merged.distinctBy { it.id },rows.size>60)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.value=State(previous,error=true) }
        }
    }
    private var lastQuery=""
    fun more() { if(!state.value.loading && state.value.more) load(query.value,true) }
}

class MetadataActivity : AppCompatActivity() {
    private val model by lazy { ViewModelProvider(this)[MetadataViewModel::class.java] }
    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this); super.onCreate(savedInstanceState); enableEdgeToEdge()
        val binding=ActivityMetadataBinding.inflate(layoutInflater); setContentView(binding.root); insets(binding.root)
        val adapter=AssetRows(this,lifecycleScope) { asset -> startActivity(Intent(this,MetadataEditorActivity::class.java)
            .putExtra(ExtensionProtocol.PROFILE,model.profile).putExtra("asset",asset.id)) }
        binding.items.layoutManager=LinearLayoutManager(this); binding.items.adapter=adapter
        binding.back.setOnClickListener { finish() }; binding.more.setOnClickListener { model.more() }
        binding.search.setText(model.query.value); binding.search.doAfterTextChanged { model.query.value=it?.toString().orEmpty() }
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { model.state.collect { state ->
            adapter.submitList(state.items)
            binding.status.isVisible=state.error || state.items.isEmpty()
            binding.status.setText(if(state.error) R.string.storage_error else R.string.empty)
            binding.more.isVisible=state.more; binding.more.isEnabled=!state.loading
        } } }
    }
}
private class AssetRows(private val activity: AppCompatActivity,private val scope: CoroutineScope,private val click: (LocalAsset)->Unit) : ListAdapter<LocalAsset,AssetRows.Holder>(object : DiffUtil.ItemCallback<LocalAsset>() {
    override fun areItemsTheSame(old: LocalAsset,new: LocalAsset)=old.id==new.id
    override fun areContentsTheSame(old: LocalAsset,new: LocalAsset)=old==new
}) {
    class Holder(val card: MaterialCardView,val image: ImageView,val title: TextView,val detail: TextView): RecyclerView.ViewHolder(card) { var work: Job?=null }
    override fun onCreateViewHolder(parent: ViewGroup,viewType: Int): Holder {
        val card=MaterialCardView(activity).apply { isClickable=true; isFocusable=true
            layoutParams=RecyclerView.LayoutParams(-1,-2).apply { bottomMargin=activity.dp(8) }
        }
        val row=LinearLayout(activity).apply { orientation=LinearLayout.HORIZONTAL; gravity=android.view.Gravity.CENTER_VERTICAL; setPadding(activity.dp(12),activity.dp(12),activity.dp(12),activity.dp(12)) }
        val image=ImageView(activity).apply { scaleType=ImageView.ScaleType.CENTER_CROP; contentDescription=activity.getString(R.string.thumbnail) }
        row.addView(image,LinearLayout.LayoutParams(activity.dp(64),activity.dp(64)))
        val labels=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL; setPadding(activity.dp(12),0,0,0) }
        val title=TextView(activity).apply { setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium); maxLines=2; ellipsize=android.text.TextUtils.TruncateAt.END }
        val detail=TextView(activity).apply { setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall); maxLines=2; ellipsize=android.text.TextUtils.TruncateAt.END }
        labels.addView(title); labels.addView(detail); row.addView(labels,LinearLayout.LayoutParams(0,-2,1f)); card.addView(row)
        return Holder(card,image,title,detail)
    }
    override fun onBindViewHolder(holder: Holder,position: Int) {
        val asset=getItem(position); holder.work?.cancel(); holder.image.setImageDrawable(null)
        holder.title.text=asset.displayTitle
        holder.detail.text=when { asset.failure!=null -> activity.getString(R.string.analysis_failed); asset.pending -> activity.getString(R.string.not_analyzed); else -> asset.effectiveTags.joinToString(", ") }
        holder.card.setOnClickListener { click(asset) }
        holder.work=scope.launch {
            val bitmap=try { withContext(Dispatchers.IO) { MediaBitmaps.read(activity.applicationContext,android.net.Uri.parse(asset.uri),asset.mime,128) } }
                catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
            holder.image.setImageBitmap(bitmap)
        }
    }
    override fun onViewRecycled(holder: Holder) { holder.work?.cancel(); holder.image.setImageDrawable(null) }
}
