package one.njk.sao.floating


import android.os.Bundle
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.viewModels
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ConcatAdapter
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import one.njk.sao.R
import one.njk.sao.databinding.ActivitySourceSettingsBinding
import one.njk.sao.importing.UploadSettingsViewModel

@AndroidEntryPoint
class SourceSettingsActivity : AppCompatActivity() {
    private val model: SourceProfilesViewModel by viewModels()
    private val uploads: UploadSettingsViewModel by viewModels()
    override fun onResume() { super.onResume(); model.refreshExtensions() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val binding = ActivitySourceSettingsBinding.inflate(layoutInflater); setContentView(binding.root)
        val padding = (24 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(padding + bars.left, padding + bars.top, padding + bars.right, padding + bars.bottom); insets
        }
        binding.back.setOnClickListener { finish() }
        binding.addSource.setOnClickListener { addMediaSource() }
        val adapter = SourceListAdapter(model::select, model::enableInFloating) { id ->
            editMediaSource(model.state.value.catalog.profiles.firstOrNull { it.id==id })
        }
        val header = SourceSettingsHeaderAdapter(model::enableAll,uploads::wifiOnly)
        val extensions = InstalledExtensionsAdapter(
            { openPackage(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package",it,null))) },
            { openPackage(Intent(Intent.ACTION_DELETE, Uri.fromParts("package",it,null))) })
        binding.sourceList.layoutManager = LinearLayoutManager(this)
        binding.sourceList.adapter = ConcatAdapter(header,adapter,extensions)
        var initialProfilesShown = savedInstanceState != null
        var userScrolled = false
        binding.sourceList.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(list: androidx.recyclerview.widget.RecyclerView, state: Int) {
                if (state == androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_DRAGGING) userScrolled = true
            }
        })
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { model.state.collect { state ->
            header.update(state.allEnabled,state.loaded && !state.saving)
            adapter.submitList(sourceRows(state.catalog, state.floatingDisabled, state.saving, state.nsfwAllowed)) {
                // Profiles can arrive after the inventory; keep the initial top instead of
                // anchoring the APK cards below newly inserted profiles. Respect a user's drag.
                if (!initialProfilesShown && adapter.itemCount > 0) {
                    initialProfilesShown = true
                    if (!userScrolled) binding.sourceList.scrollToPosition(0)
                }
            }
            extensions.submitApps(state.installedExtensions,state.loaded)
            binding.status.isVisible = state.error
            binding.sourceList.isEnabled = state.loaded && !state.saving
            binding.addSource.isEnabled = state.loaded && !state.saving
        } } }
        var uploadErrorShown=false
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { uploads.state.collect { state ->
            header.updateUploads(state.wifiOnly,state.loaded && !state.saving)
            if(state.error && !uploadErrorShown) com.google.android.material.snackbar.Snackbar.make(binding.root,R.string.upload_settings_failed,com.google.android.material.snackbar.Snackbar.LENGTH_LONG).show()
            uploadErrorShown=state.error
        } } }
    }
    private fun openPackage(intent: Intent) {
        try { startActivity(intent) }
        catch (_: ActivityNotFoundException) { unavailablePackageAction() }
        catch (_: SecurityException) { unavailablePackageAction() }
    }
    private fun unavailablePackageAction() = Toast.makeText(this,R.string.source_extension_action_failed,Toast.LENGTH_LONG).show()
}
