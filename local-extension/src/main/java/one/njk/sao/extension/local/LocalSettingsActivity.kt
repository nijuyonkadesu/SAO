package one.njk.sao.extension.local

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.*
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch
import one.njk.sao.extension.local.databinding.ActivityLocalSettingsBinding
import one.njk.sao.source.ExtensionProtocol

class LocalSettingsActivity : AppCompatActivity() {
    private val model by lazy { ViewModelProvider(this)[LocalSettingsViewModel::class.java] }
    private val picker=registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if(uri!=null) model.folder(uri) }
    override fun onCreate(savedInstanceState: Bundle?) {
        val caller=callingPackage
        if(caller !in setOf("one.njk.sao","one.njk.sao.testing") || packageManager.checkSignatures(packageName,requireNotNull(caller))!=PackageManager.SIGNATURE_MATCH) {
            super.onCreate(savedInstanceState); finish(); return
        }
        DynamicColors.applyToActivityIfAvailable(this); super.onCreate(savedInstanceState); enableEdgeToEdge()
        val binding=ActivityLocalSettingsBinding.inflate(layoutInflater); setContentView(binding.root); insets(binding.root)
        var populated=false; var shown: List<Folder>?=null
        binding.back.setOnClickListener { finish() }
        binding.sourceName.doAfterTextChanged { if(populated) model.name=it?.toString().orEmpty() }
        binding.addFolder.setOnClickListener { picker.launch(null) }
        binding.saveLibrary.setOnClickListener { model.save() }
        binding.rescan.setOnClickListener { model.rescan() }
        binding.pauseIndexing.setOnClickListener { model.pause() }
        binding.deleteLibrary.setOnClickListener { model.delete() }
        binding.browseMetadata.setOnClickListener { startActivity(Intent(this,MetadataActivity::class.java).putExtra(ExtensionProtocol.PROFILE,model.profile)) }
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { model.state.collect { state ->
            if(state.loaded && !populated) { binding.sourceName.setText(model.name); populated=true }
            if(shown!=state.folders) {
                shown=state.folders; binding.folderList.removeAllViews()
                state.folders.forEach { folder -> binding.folderList.addView(folderRow(folder,binding.folderList)) }
            }
            val status=state.index
            val text=when {
                state.library==null -> getString(R.string.saved_first)
                state.library.paused -> getString(R.string.paused)
                status==null -> getString(R.string.saved_first)
                status.scanning -> getString(R.string.discovering,status.total,status.pending)
                status.pending>0 -> getString(R.string.analyzing,status.total,status.pending)
                else -> getString(R.string.indexed,status.total)
            }
            binding.indexStatus.text=if(status!=null && status.failed+status.folderErrors>0) getString(R.string.index_errors,text,status.failed,status.folderErrors) else text
            binding.error.isVisible=state.error!=null; state.error?.let(binding.error::setText)
            listOf(binding.addFolder,binding.sourceName,binding.saveLibrary,binding.deleteLibrary).forEach { it.isEnabled=state.loaded && !state.working }
            listOf(binding.rescan,binding.pauseIndexing,binding.browseMetadata).forEach { it.isVisible=state.library!=null; it.isEnabled=!state.working }
            binding.deleteLibrary.isVisible=state.library!=null
            binding.pauseIndexing.setText(if(state.library?.paused==true) R.string.resume else R.string.pause)
            if(state.finished) { setResult(RESULT_OK,Intent().putExtra(ExtensionProtocol.PROFILE,model.profile)); finish() }
        } } }
    }
    private fun folderRow(folder: Folder,parent: LinearLayout): LinearLayout = (layoutInflater.inflate(R.layout.item_folder,parent,false) as LinearLayout).apply {
        findViewById<TextView>(R.id.name).text=folder.name
        findViewById<MaterialButton>(R.id.rename).setOnClickListener { rename(folder) }
        findViewById<MaterialButton>(R.id.remove).setOnClickListener { model.remove(folder) }
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
