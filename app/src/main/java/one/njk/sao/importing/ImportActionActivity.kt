package one.njk.sao.importing

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import one.njk.sao.R
import one.njk.sao.source.*
import javax.inject.Inject

/** Only system pickers/progress above the extension's settings. No second settings or destination page. */
@AndroidEntryPoint
class ImportActionActivity: AppCompatActivity() {
    private val model: ImportActionViewModel by viewModels()
    private var launched=false
    private var shownError: String?=null
    private var dialog: androidx.appcompat.app.AlertDialog?=null
    private val photos=registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { if(it.isEmpty()) finish() else model.receive(it) }
    private val files=registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { if(it.isEmpty()) finish() else model.receive(it) }
    private val folder=registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { if(it==null) finish() else model.connect(it) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val caller=callingPackage
        if(caller==null || packageManager.checkSignatures(packageName,caller)!=PackageManager.SIGNATURE_MATCH) { finish(); return }
        launched=savedInstanceState?.getBoolean("launched")==true
        model.initialize(caller)
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.RESUMED) { model.state.collect { state ->
            if(state.done) { setResult(RESULT_OK); finish(); return@collect }
            if(state.error!=null) {
                if(shownError!=state.error) {
                    shownError=state.error; dialog?.dismiss()
                    dialog=MaterialAlertDialogBuilder(this@ImportActionActivity).setMessage(R.string.import_error_generic)
                    .setPositiveButton(android.R.string.ok) { _,_->finish() }.setOnCancelListener { finish() }.show()
                }
                return@collect
            }
            if(state.ready && !launched) {
                launched=true
                when(intent.getStringExtra(ImportSettings.ACTION)) {
                    "photos" -> photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                    "files" -> files.launch(arrayOf("image/*","video/*"))
                    "folder" -> folder.launch(null)
                    else -> finish()
                }
            }
            if(state.working && dialog==null) dialog=MaterialAlertDialogBuilder(this@ImportActionActivity)
                .setMessage(R.string.import_in_progress).setCancelable(false).show()
        } } }
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putBoolean("launched",launched); super.onSaveInstanceState(outState) }
    override fun onDestroy() { dialog?.dismiss(); super.onDestroy() }
}

@HiltViewModel
class ImportActionViewModel @Inject constructor(private val repository: MediaImports,private val saved: SavedStateHandle): ViewModel() {
    data class State(val ready: Boolean=false,val working: Boolean=false,val done: Boolean=false,val error: String?=null)
    private val mutable=MutableStateFlow(State())
    val state=mutable.asStateFlow()
    private var initialized=false
    private lateinit var destination: MediaImports.Destination
    private lateinit var target: ImportTarget
    fun initialize(caller: String) {
        if(initialized) return
        initialized=true
        viewModelScope.launch {
            try {
                val source=repository.settingsSource(caller,requireNotNull(saved[ExtensionProtocol.PROFILE]),requireNotNull(saved[ImportSettings.REVISION]))
                destination=repository.destinations(source.id).single()
                target=destination.options.targets.firstOrNull { it.id==saved.get<String>(ImportSettings.TARGET) } ?: throw ExtensionFailure("TARGET_MISSING")
                if(saved.get<String>(ImportSettings.ACTION)=="folder" && !destination.options.connectedFolders) throw ExtensionFailure("UNSUPPORTED")
                mutable.value=State(ready=true); drain()
            } catch(cancelled: CancellationException) { throw cancelled }
            catch(error: Exception) { mutable.value=State(error=MediaImports.code(error)) }
        }
    }
    // Activity results can arrive before async profile validation after process recreation.
    // Retain them in transient saved state until the destination is ready.
    fun receive(uris: List<Uri>) { saved["media"]=ArrayList(uris); drain() }
    fun connect(uri: Uri) { saved["folder"]=uri; drain() }
    private fun drain() {
        if(!state.value.ready || state.value.working || state.value.done) return
        val folder=saved.get<Uri>("folder")
        val media=saved.get<ArrayList<Uri>>("media")
        if(folder!=null) action { repository.connect(destination,target,folder); saved.remove<Uri>("folder") }
        else if(!media.isNullOrEmpty()) action { repository.enqueue(destination,target,media); saved.remove<ArrayList<Uri>>("media") }
    }
    private fun action(block: suspend ()->Unit) {
        if(!state.value.ready || state.value.working || state.value.done) return
        mutable.value=State(ready=true,working=true)
        viewModelScope.launch {
            try { block(); mutable.value=State(done=true) }
            catch(cancelled: CancellationException) { throw cancelled }
            catch(error: Exception) { mutable.value=State(error=MediaImports.code(error)) }
        }
    }
}
