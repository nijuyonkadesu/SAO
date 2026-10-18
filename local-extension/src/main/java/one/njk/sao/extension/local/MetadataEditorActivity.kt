package one.njk.sao.extension.local

import android.app.Application
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.*
import com.google.android.material.color.DynamicColors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import one.njk.sao.extension.local.databinding.ActivityMetadataEditorBinding
import one.njk.sao.source.ExtensionProtocol

class MetadataEditorViewModel(app: Application,saved: SavedStateHandle): AndroidViewModel(app) {
    data class State(val asset: LocalAsset?=null,val working: Boolean=false,val error: Boolean=false,val finished: Boolean=false)
    private val store=LocalStore.get(app)
    private val profile: String=checkNotNull(saved[ExtensionProtocol.PROFILE])
    private val id: Long=checkNotNull(saved["asset"])
    private val mutable=MutableStateFlow(State())
    val state=mutable.asStateFlow()
    var title=""; var description=""; var tags=""; var ocr=""
    init { viewModelScope.launch {
        try {
            val asset=withContext(Dispatchers.IO) { store.asset(id,profile) }
            title=asset.title.orEmpty(); description=asset.description.orEmpty(); tags=asset.effectiveTags.joinToString(", "); ocr=asset.effectiveOcr
            mutable.value=State(asset)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutable.value=State(error=true) }
    } }
    fun save(reset: Boolean=false) {
        if(state.value.working || state.value.asset==null) return
        mutable.value=state.value.copy(working=true)
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { store.corrections(id,profile,title,description,tags,reset,ocr) }; mutable.value=state.value.copy(working=false,finished=true) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.value=state.value.copy(working=false,error=true) }
        }
    }
}
class MetadataEditorActivity : AppCompatActivity() {
    private val model by lazy { ViewModelProvider(this)[MetadataEditorViewModel::class.java] }
    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this); super.onCreate(savedInstanceState); enableEdgeToEdge()
        val binding=ActivityMetadataEditorBinding.inflate(layoutInflater); setContentView(binding.root); insets(binding.root)
        var populated=false
        binding.back.setOnClickListener { finish() }; binding.save.setOnClickListener { model.save() }; binding.reset.setOnClickListener { model.save(true) }
        binding.mediaTitle.doAfterTextChanged { if(populated) model.title=it.toString() }
        binding.description.doAfterTextChanged { if(populated) model.description=it.toString() }
        binding.tags.doAfterTextChanged { if(populated) model.tags=it.toString() }
        binding.ocr.doAfterTextChanged { if(populated) model.ocr=it.toString() }
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { model.state.collect { state ->
            if(state.asset!=null && !populated) {
                val asset=state.asset
                binding.filename.text=asset.filename
                binding.autoLabels.text=when { asset.failure!=null -> getString(R.string.analysis_failed); asset.pending -> getString(R.string.not_analyzed); else -> getString(R.string.auto_labels,asset.labels.split('\n').joinToString(", ")) }
                binding.mediaTitle.setText(model.title); binding.description.setText(model.description); binding.tags.setText(model.tags); binding.ocr.setText(model.ocr); populated=true
                lifecycleScope.launch {
                    val bitmap=try { withContext(Dispatchers.IO) { MediaBitmaps.read(applicationContext,android.net.Uri.parse(asset.uri),asset.mime,720) } }
                        catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
                    binding.preview.setImageBitmap(bitmap)
                }
            }
            listOf(binding.save,binding.reset,binding.mediaTitle,binding.description,binding.tags,binding.ocr).forEach { it.isEnabled=state.asset!=null && !state.working }
            binding.error.text=if(state.error) getString(R.string.storage_error) else ""
            if(state.finished) finish()
        } } }
    }
}
