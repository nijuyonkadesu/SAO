package one.njk.sao.extension.immich

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.color.DynamicColors
import kotlinx.coroutines.launch
import one.njk.sao.extension.immich.databinding.ActivityImmichSettingsBinding
import one.njk.sao.source.ExtensionProtocol
import one.njk.sao.source.settings.SourceIntakeSection
import one.njk.sao.source.settings.SourceSettingsGuard

class ImmichSettingsActivity : AppCompatActivity() {
    private val model by lazy { ViewModelProvider(this)[ImmichSettingsViewModel::class.java] }
    override fun onCreate(savedInstanceState: Bundle?) {
        val caller = callingPackage
        if (caller !in setOf("one.njk.sao","one.njk.sao.testing") ||
            packageManager.checkSignatures(packageName,requireNotNull(caller)) != PackageManager.SIGNATURE_MATCH) {
            super.onCreate(savedInstanceState); finish(); return
        }
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        val binding = ActivityImmichSettingsBinding.inflate(layoutInflater); setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view,insets ->
            val bars=insets.getInsets(WindowInsetsCompat.Type.systemBars()); view.setPadding(bars.left,bars.top,bars.right,bars.bottom); insets
        }
        val intake=SourceIntakeSection(this,binding.intake.root,binding.root)
        val guard=SourceSettingsGuard(this,binding.saveConnection,{ model.hasChanges },{ model.state.value.working }) { model.check(true) }
        var populated=false
        binding.back.setOnClickListener { guard.leave() }
        binding.sourceName.doAfterTextChanged { if(populated) model.name=it?.toString().orEmpty() }
        binding.serverUrl.doAfterTextChanged { if(populated) { model.url=it?.toString().orEmpty(); intake.editable(model.importsUnchanged) } }
        binding.apiKey.doAfterTextChanged { if(populated) { model.key=it?.toString().orEmpty(); intake.editable(model.importsUnchanged) } }
        binding.album.setOnItemClickListener { _,_,index,_ -> model.albumId=model.state.value.albums.getOrNull(index-1)?.id; intake.editable(model.importsUnchanged) }
        binding.testConnection.setOnClickListener { model.check(false) }
        binding.saveConnection.setOnClickListener { model.check(true) }
        binding.deleteSource.isVisible=model.editingId!=null
        binding.deleteSource.setOnClickListener { model.delete() }
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { model.state.collect { state ->
            if(state.loaded && !populated) {
                binding.sourceName.setText(model.name); binding.serverUrl.setText(model.url); binding.apiKey.setText(model.key); populated=true
            }
            model.importConnection?.let { connection ->
                val app=applicationContext
                intake.configure(requireNotNull(caller),connection.id,connection.revision) { ImmichImports(app,connection).options() }
                intake.editable(model.importsUnchanged && !state.working)
            }
            binding.album.setAdapter(ArrayAdapter(this@ImmichSettingsActivity,android.R.layout.simple_list_item_1,
                listOf(getString(R.string.immich_all_photos))+state.albums.map { it.albumName }))
            binding.album.setText(state.albums.firstOrNull { it.id==model.albumId }?.albumName ?: getString(
                if(model.albumId==null) R.string.immich_all_photos else R.string.immich_saved_album),false)
            binding.status.text=state.version?.let { getString(R.string.connection_status_version,getString(state.status),it) } ?: getString(state.status)
            binding.status.isVisible=state.status!=R.string.immich_setup_description
            binding.progress.isVisible=state.working || !state.loaded
            listOf(binding.sourceName,binding.serverUrl,binding.apiKey,binding.album,binding.testConnection,binding.saveConnection,binding.deleteSource)
                .forEach { it.isEnabled=state.loaded && !state.working }
            if(state.finished) {
                setResult(RESULT_OK,Intent().putExtra(ExtensionProtocol.PROFILE,model.profileId)); finish()
            }
        } } }
    }
}
