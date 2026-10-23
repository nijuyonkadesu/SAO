package one.njk.sao.floating


import android.os.Bundle
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
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
import kotlinx.coroutines.flow.combine
import one.njk.sao.R
import one.njk.sao.databinding.ActivitySourceSettingsBinding
import one.njk.sao.importing.UploadSettingsViewModel
import one.njk.sao.extensions.*

@AndroidEntryPoint
class SourceSettingsActivity : AppCompatActivity() {
    @javax.inject.Inject lateinit var imageLoader: coil3.ImageLoader
    private val model: SourceProfilesViewModel by viewModels()
    private val uploads: UploadSettingsViewModel by viewModels()
    private val catalog: ExtensionCatalogViewModel by viewModels()
    private val installPermission=registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        catalog.permissionResult(packageManager.canRequestPackageInstalls())
    }
    override fun onResume() { super.onResume(); model.refreshExtensions(); catalog.installReady() }
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
        supportFragmentManager.setFragmentResultListener(ExtensionTrustDialog.RESULT,this) { _,result ->
            if(result.getBoolean(ExtensionTrustDialog.ALLOWED)) model.extensionApproval(requireNotNull(result.getString(ExtensionTrustDialog.PACKAGE)),requireNotNull(result.getString(ExtensionTrustDialog.SIGNER)),true)
        }
        val extensions = InstalledExtensionsAdapter(
            { openPackage(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package",it,null))) },
            { openPackage(Intent(Intent.ACTION_DELETE, Uri.fromParts("package",it,null))) },
            { app ->
                if(app.approval==ExtensionApproval.USER) model.extensionApproval(app.packageName,app.signer,false)
                else if(supportFragmentManager.findFragmentByTag(ExtensionTrustDialog.RESULT)==null)
                    ExtensionTrustDialog.create(app.name,app.packageName,app.signer).show(supportFragmentManager,ExtensionTrustDialog.RESULT)
            },::requestExtensionInstall,catalog::cancel)
        val available=AvailableExtensionsAdapter(catalog::refresh,::requestExtensionInstall,catalog::cancel,imageLoader)
        binding.sourceList.layoutManager = LinearLayoutManager(this)
        binding.sourceList.adapter = ConcatAdapter(header,adapter,extensions,available)
        var initialProfilesShown = savedInstanceState != null
        var userScrolled = false
        binding.sourceList.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(list: androidx.recyclerview.widget.RecyclerView, state: Int) {
                if (state == androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_DRAGGING) userScrolled = true
            }
        })
        var inventoryRevision=0
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { combine(model.state,catalog.state) { state,distribution -> state to distribution }.collect { (state,distribution) ->
            header.update(state.allEnabled,state.loaded && !state.saving)
            adapter.submitList(sourceRows(state.catalog, state.floatingDisabled, state.saving, state.nsfwAllowed)) {
                // Profiles can arrive after the inventory; keep the initial top instead of
                // anchoring the APK cards below newly inserted profiles. Respect a user's drag.
                if (!initialProfilesShown && adapter.itemCount > 0) {
                    initialProfilesShown = true
                    if (!userScrolled) binding.sourceList.scrollToPosition(0)
                }
            }
            extensions.submitApps(state.installedExtensions,state.loaded,state.saving,distribution)
            available.update(distribution,state.installedExtensions,state.loaded && !state.saving)
            if(inventoryRevision!=distribution.inventoryRevision) { inventoryRevision=distribution.inventoryRevision; model.refreshExtensions() }
            if(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) catalog.installReady()
            binding.status.isVisible = state.error
            binding.sourceList.isEnabled = state.loaded && !state.saving
            binding.addSource.isEnabled = state.loaded && !state.saving
        } } }
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.RESUMED) { catalog.installEvents.collect { event ->
            if(event?.confirmation!=null) {
                try { if(catalog.ownsConfirmation(event)) startActivity(event.confirmation) }
                catch(_: ActivityNotFoundException) { unavailablePackageAction() }
                finally { catalog.confirmationStarted(event) }
            }
        } } }
        var uploadErrorShown=false
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { uploads.state.collect { state ->
            header.updateUploads(state.wifiOnly,state.loaded && !state.saving)
            if(state.error && !uploadErrorShown) com.google.android.material.snackbar.Snackbar.make(binding.root,R.string.upload_settings_failed,com.google.android.material.snackbar.Snackbar.LENGTH_LONG).show()
            uploadErrorShown=state.error
        } } }
    }
    private fun requestExtensionInstall(entry: CatalogExtension) {
        if(packageManager.canRequestPackageInstalls()) catalog.start(entry)
        else {
            catalog.permissionNeeded(entry)
            try { installPermission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.fromParts("package",packageName,null))) }
            catch(_: ActivityNotFoundException) { catalog.permissionResult(false); unavailablePackageAction() }
        }
    }
    private fun openPackage(intent: Intent) {
        try { startActivity(intent) }
        catch (_: ActivityNotFoundException) { unavailablePackageAction() }
        catch (_: SecurityException) { unavailablePackageAction() }
    }
    private fun unavailablePackageAction() = Toast.makeText(this,R.string.source_extension_action_failed,Toast.LENGTH_LONG).show()
}
