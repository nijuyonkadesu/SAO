package one.njk.sao.extension.local

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import android.provider.MediaStore
import android.widget.LinearLayout
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.*
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch
import one.njk.sao.extension.local.databinding.ActivityLocalSettingsBinding
import one.njk.sao.extension.local.databinding.ItemFolderBinding
import one.njk.sao.source.ExtensionProtocol

class LocalSettingsActivity : AppCompatActivity() {
    private val model by lazy { ViewModelProvider(this)[LocalSettingsViewModel::class.java] }
    private val modelPicker=registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if(uri!=null) model.modelFolder(uri) }
    private val picker=registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if(uri!=null) model.folder(uri) }
    private val photos=registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { model.media(it) }
    private val files=registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { model.media(it) }
    private fun launchImport(target: String?=null,method: ImportMethod=model.state.value.importMethod) {
        model.importTarget=target
        val pickerMethod=if(target!=null && method==ImportMethod.FOLDER) ImportMethod.PHOTOS else method
        try {
            when(pickerMethod) {
                ImportMethod.FOLDER -> picker.launch(null)
                ImportMethod.PHOTOS -> photos.launch(photoAlbumRequest())
                ImportMethod.FILES -> files.launch(arrayOf("image/*","video/*"))
            }
        } catch(_: android.content.ActivityNotFoundException) { model.importTarget=null; model.pickerUnavailable() }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        val caller=callingPackage
        if(caller !in setOf("one.njk.sao","one.njk.sao.testing") || packageManager.checkSignatures(packageName,requireNotNull(caller))!=PackageManager.SIGNATURE_MATCH) {
            super.onCreate(savedInstanceState); finish(); return
        }
        DynamicColors.applyToActivityIfAvailable(this); super.onCreate(savedInstanceState); enableEdgeToEdge()
        val binding=ActivityLocalSettingsBinding.inflate(layoutInflater); setContentView(binding.root); insets(binding.root)
        var populated=false; var shown: List<Folder>?=null; var renderingContext=false
        var rows=emptyList<Pair<Folder,ItemFolderBinding>>()
        binding.back.setOnClickListener { finish() }
        binding.sourceName.doAfterTextChanged { if(populated) model.name=it?.toString().orEmpty() }
        binding.addFolder.setOnClickListener { launchImport() }
        binding.addWholeFolder.setOnClickListener { launchImport(method=ImportMethod.FOLDER) }
        val cloudSettings=Intent(MediaStore.ACTION_PICK_IMAGES_SETTINGS)
        val cloudSettingsAvailable=Build.VERSION.SDK_INT>=33 && cloudSettings.resolveActivity(packageManager)!=null
        binding.cloudMediaSettings.setOnClickListener {
            try { startActivity(cloudSettings) }
            catch(_: android.content.ActivityNotFoundException) { model.pickerUnavailable() }
            catch(_: SecurityException) { model.pickerUnavailable() }
        }
        binding.importMethod.setSimpleItems(ImportMethod.entries.map { getString(it.label) }.toTypedArray())
        binding.importMethod.setOnItemClickListener { _,_,position,_ -> model.importMethod(position) }
        binding.saveLibrary.setOnClickListener { model.save() }
        binding.rescan.setOnClickListener { model.rescan() }
        binding.pauseIndexing.setOnClickListener { model.pause() }
        binding.contextSearch.setOnCheckedChangeListener { _,checked -> if(!renderingContext) model.useContext(checked) }
        binding.modelChoice.setSimpleItems(ClipCatalog.all.map { getString(it.label) }.toTypedArray())
        binding.modelChoice.setOnItemClickListener { _,_,position,_ -> model.selectModel(position) }
        binding.chooseModelFolder.setOnClickListener { modelPicker.launch(ModelStorage.tree(this,model.state.value.selectedModel)) }
        binding.downloadModel.setOnClickListener {
            MaterialAlertDialogBuilder(this).setTitle(R.string.download_model).setMessage(getString(R.string.model_download_size,(model.state.value.selectedModel.bytes+999_999)/1_000_000))
                .setNegativeButton(android.R.string.cancel,null).setPositiveButton(R.string.download_model) { _,_ -> model.downloadModel() }.show()
        }
        binding.pauseDownload.setOnClickListener { model.pauseDownload() }
        binding.deleteLibrary.setOnClickListener { model.delete() }
        binding.browseMetadata.setOnClickListener { startActivity(Intent(this,MetadataActivity::class.java).putExtra(ExtensionProtocol.PROFILE,model.profile)) }
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { model.state.collect { state ->
            if(state.loaded && !populated) { binding.sourceName.setText(model.name); populated=true }
            if(shown!=state.folders) {
                shown=state.folders; binding.folderList.removeAllViews()
                rows=state.folders.map { folder -> folder to folderRow(folder,binding.folderList).also { binding.folderList.addView(it.root) } }
            }
            val methodLabel=getString(state.importMethod.label)
            if(binding.importMethod.text.toString()!=methodLabel) binding.importMethod.setText(methodLabel,false)
            binding.importMethod.isEnabled=state.loaded && !state.working
            binding.importHelp.setText(state.importMethod.help)
            binding.cloudMediaSettings.isVisible=state.importMethod==ImportMethod.PHOTOS && cloudSettingsAvailable
            binding.addFolder.setText(state.importMethod.action)
            binding.addWholeFolder.isVisible=state.importMethod!=ImportMethod.FOLDER
            binding.indexStatus.bind(state.index,state.library?.paused==true,state.indexWork,state.estimates.total)
            val modelStatus=state.model
            val label=getString(state.selectedModel.label)
            if(binding.modelChoice.text.toString()!=label) binding.modelChoice.setText(label,false)
            binding.modelChoice.isEnabled=!state.working
            binding.modelHelp.setText(if(state.selectedModel.quantized) R.string.model_lite_help else R.string.model_immich_help)
            binding.modelLocation.text=when {
                modelStatus.location!=null -> getString(R.string.model_location,modelStatus.location)
                modelStatus.legacy -> getString(R.string.model_legacy)
                else -> getString(R.string.model_folder_help)
            }
            val total=(state.selectedModel.bytes+999_999)/1_000_000
            binding.modelStatus.text=when {
                modelStatus.ready -> getString(R.string.model_ready)
                modelStatus.work==IndexWork.RUNNING -> getString(R.string.model_downloading,modelStatus.bytes/1_000_000,total)
                modelStatus.work==IndexWork.WAITING -> getString(R.string.model_waiting,modelStatus.bytes/1_000_000,total)
                modelStatus.failed -> getString(R.string.model_failed)
                modelStatus.bytes>0 -> getString(R.string.model_paused,modelStatus.bytes/1_000_000,total)
                else -> getString(R.string.model_missing)
            }
            binding.modelProgress.isVisible=!modelStatus.ready && (modelStatus.bytes>0 || modelStatus.work!=IndexWork.IDLE)
            binding.modelProgress.setProgressCompat((modelStatus.bytes*1000/state.selectedModel.bytes).toInt(),false)
            binding.downloadModel.isVisible=!modelStatus.ready && modelStatus.location!=null && modelStatus.work==IndexWork.IDLE
            binding.chooseModelFolder.isEnabled=!state.working && modelStatus.work==IndexWork.IDLE
            binding.pauseDownload.isVisible=!modelStatus.ready && modelStatus.work!=IndexWork.IDLE
            binding.downloadModel.isEnabled=!state.working; binding.pauseDownload.isEnabled=!state.working
            binding.contextSearch.isEnabled=modelStatus.ready && !state.working
            renderingContext=true; binding.contextSearch.isChecked=state.useContext; renderingContext=false
            rows.forEach { (folder,row) ->
                val status=state.folderIndexes[folder.uri]
                row.indexStatus.bind(status,state.library?.paused==true,state.indexWork,state.estimates.folders[folder.uri])
                row.processingActions.isVisible=status!=null
                row.pauseFolder.setText(if(status?.paused==true) R.string.resume_album else R.string.pause_album)
                listOf(row.pauseFolder,row.restartFolder,row.rename,row.remove,row.addMedia).forEach { it.isEnabled=!state.working }
            }
            binding.error.isVisible=state.error!=null; state.error?.let(binding.error::setText)
            listOf(binding.addFolder,binding.addWholeFolder,binding.sourceName,binding.saveLibrary,binding.deleteLibrary).forEach { it.isEnabled=state.loaded && !state.working }
            listOf(binding.rescan,binding.pauseIndexing,binding.browseMetadata).forEach { it.isVisible=state.library!=null; it.isEnabled=!state.working }
            binding.deleteLibrary.isVisible=state.library!=null
            binding.pauseIndexing.setText(if(state.library?.paused==true) R.string.resume else R.string.pause)
            if(state.finished) { setResult(RESULT_OK,Intent().putExtra(ExtensionProtocol.PROFILE,model.profile)); finish() }
        } } }
    }
    private fun folderRow(folder: Folder,parent: LinearLayout) = ItemFolderBinding.inflate(layoutInflater,parent,false).apply {
        name.text=folder.name
        kind.setText(if(folder.kind==CollectionKind.TREE) R.string.picker_folder else R.string.selected_media_help)
        addMedia.isVisible=folder.kind==CollectionKind.SELECTION
        addMedia.setOnClickListener { launchImport(folder.uri) }
        rename.setOnClickListener { rename(folder) }
        remove.setOnClickListener { model.remove(folder) }
        pauseFolder.setOnClickListener { model.pauseFolder(folder) }
        restartFolder.setOnClickListener {
            MaterialAlertDialogBuilder(this@LocalSettingsActivity).setTitle(R.string.restart_title)
                .setMessage(getString(R.string.restart_message,folder.name)).setNegativeButton(android.R.string.cancel,null)
                .setPositiveButton(R.string.restart_album) { _,_ -> model.restartFolder(folder) }.show()
        }
    }
    private fun rename(folder: Folder) {
        val input=TextInputEditText(this).apply { setText(folder.name); selectAll() }
        val box=TextInputLayout(this).apply { hint=getString(R.string.folders); setPadding(dp(24),dp(8),dp(24),0); addView(input) }
        MaterialAlertDialogBuilder(this).setTitle(R.string.rename).setView(box).setNegativeButton(android.R.string.cancel,null)
            .setPositiveButton(android.R.string.ok) { _,_ -> model.rename(folder,input.text.toString()) }.show()
    }
}
internal fun AppCompatActivity.dp(value: Int)=(value*resources.displayMetrics.density).toInt()
internal fun AppCompatActivity.insets(root: android.view.View) { ViewCompat.setOnApplyWindowInsetsListener(root) { view,value ->
    val bars=value.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()); view.setPadding(bars.left,bars.top,bars.right,bars.bottom); value
} }

/** A preference for album browsing, with the AndroidX default fallback on older pickers. */
internal fun photoAlbumRequest()=PickVisualMediaRequest.Builder()
    .setMediaType(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
    .setDefaultTab(ActivityResultContracts.PickVisualMedia.DefaultTab.AlbumsTab)
    .build()
