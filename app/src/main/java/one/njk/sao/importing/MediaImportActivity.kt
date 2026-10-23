package one.njk.sao.importing

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import one.njk.sao.R
import one.njk.sao.databinding.ActivityMediaImportBinding
import one.njk.sao.floating.SourceSettingsActivity
import one.njk.sao.floating.editMediaSource

@AndroidEntryPoint
class MediaImportActivity: AppCompatActivity() {
    private val model: MediaImportViewModel by viewModels()
    private lateinit var binding: ActivityMediaImportBinding
    private val photos=registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { if(it.isNotEmpty()) model.receive(it) }
    private val files=registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { if(it.isNotEmpty()) model.receive(it) }
    private val folder=registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(model::connect) }
    private var renderedSources: List<MediaImports.Destination>?=null
    private var renderedRoutes: List<ImportStore.Route>?=null
    private var renderedTargets: List<one.njk.sao.source.ImportTarget>?=null
    private var renderedCollections: List<one.njk.sao.source.ImportCollection>?=null
    private var redirected=false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        binding=ActivityMediaImportBinding.inflate(layoutInflater); setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view,insets ->
            val bars=insets.getInsets(WindowInsetsCompat.Type.systemBars()); view.setPadding(bars.left,bars.top,bars.right,bars.bottom); insets
        }
        if(savedInstanceState==null && intent.action in setOf(Intent.ACTION_SEND,Intent.ACTION_SEND_MULTIPLE)) {
            val uris=if(intent.action==Intent.ACTION_SEND) listOfNotNull(IntentCompat.getParcelableExtra(intent,Intent.EXTRA_STREAM,Uri::class.java))
                else IntentCompat.getParcelableArrayListExtra(intent,Intent.EXTRA_STREAM,Uri::class.java).orEmpty()
            val clipped=intent.clipData?.let { clip -> (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri } }.orEmpty()
            model.incoming((uris+clipped).filter { it.scheme=="content" })
        }
        binding.back.setOnClickListener { finish() }
        binding.addMedia.setOnClickListener { photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }
        binding.addFiles.setOnClickListener { files.launch(arrayOf("image/*","video/*")) }
        binding.connectFolder.setOnClickListener { folder.launch(null) }
        binding.pause.setOnClickListener { model.pause() }
        binding.retry.setOnClickListener { model.retry() }
        binding.checkFolders.setOnClickListener { model.check() }
        binding.clear.setOnClickListener { MaterialAlertDialogBuilder(this).setTitle(R.string.import_clear_title)
            .setMessage(R.string.import_clear_help).setNegativeButton(android.R.string.cancel,null)
            .setPositiveButton(R.string.import_clear) { _,_->model.clear() }.show() }
        binding.configure.setOnClickListener {
            val profile=model.state.value.profile
            if(profile==null) startActivity(Intent(this,SourceSettingsActivity::class.java)) else editMediaSource(profile)
        }
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { model.state.collect(::render) } }
    }
    override fun onResume() { super.onResume(); model.refresh() }
    private fun render(state: MediaImportViewModel.State) {
        val profile=state.profile; val options=state.selected?.options
        if(state.loaded && profile!=null && state.selected==null && state.error==null && !redirected) {
            redirected=true; editMediaSource(profile); finish(); return
        }
        val destinations=if(state.inspecting) emptyList() else state.destinations.filter { destination ->
            destination.options.targets.isNotEmpty() && state.incomingTypes.all(destination.options::accepts)
        }
        binding.title.text=profile?.name ?: getString(R.string.import_title)
        binding.summary.text=when {
            state.working && state.preparing -> getString(R.string.import_preparing,state.prepared,state.total)
            state.working -> getString(R.string.import_updating)
            profile==null -> getString(if(state.loaded && !state.inspecting && destinations.isEmpty()) R.string.import_no_sources else R.string.import_choose_source)
            options?.targets.isNullOrEmpty() -> getString(if(options==null || options.types.isEmpty()) R.string.import_read_only else R.string.import_setup_folder)
            else -> getString(R.string.import_target_summary,state.target?.name.orEmpty())
        }
        binding.progress.isVisible=!state.loaded || state.working || state.inspecting
        val error=state.error ?: state.stats.error
        binding.error.isVisible=error!=null; binding.error.text=error?.let(::errorText)
        if(renderedSources!=destinations) {
            renderedSources=destinations; binding.destinations.removeAllViews()
            destinations.forEach { destination ->
                binding.destinations.addView(MaterialButton(this,null,com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text=destination.source.name; contentDescription="${destination.source.name}, ${destination.options.targets.joinToString { it.name }}"
                    setOnClickListener { model.select(destination) }
                })
            }
        }
        binding.destinations.isVisible=profile==null
        val targets=options?.targets.orEmpty()
        if(renderedTargets!=targets) {
            renderedTargets=targets
            binding.target.setAdapter(ArrayAdapter(this,android.R.layout.simple_list_item_1,targets.map { it.name }))
            binding.target.setOnItemClickListener { _,_,position,_ -> targets.getOrNull(position)?.let(model::target) }
        }
        if(binding.target.text.toString()!=state.target?.name.orEmpty()) binding.target.setText(state.target?.name.orEmpty(),false)
        binding.targetBox.isVisible=targets.size>1
        binding.actions.isVisible=profile!=null && targets.isNotEmpty()
        binding.actions.isEnabled=!state.working
        listOf(binding.addMedia,binding.addFiles,binding.connectFolder,binding.configure).forEach { it.isEnabled=state.loaded && !state.working }
        val stats=state.stats
        binding.status.isVisible=stats.done+stats.pending+stats.blocked>0
        binding.status.text=if(stats.paused) getString(R.string.import_counts_paused,stats.done,stats.pending,stats.blocked)
            else listOfNotNull(getString(R.string.import_added_count,stats.done),stats.pending.takeIf { it>0 }?.let { getString(R.string.import_pending_count,it) },
                stats.blocked.takeIf { it>0 }?.let { getString(R.string.import_attention_count,it) }).joinToString(" · ")
        binding.queueActions.isVisible=stats.pending+stats.blocked>0 || stats.paused
        binding.clear.isVisible=stats.pending+stats.blocked>0
        binding.pause.setText(if(stats.paused) R.string.import_resume else R.string.import_pause)
        binding.retry.isVisible=stats.blocked>0 || stats.error!=null
        val collections=options?.collections.orEmpty()
        val showFolders=options?.connectedFolders==true || state.routes.isNotEmpty() || collections.isNotEmpty()
        binding.foldersTitle.setText(if(collections.isNotEmpty()) R.string.import_collections else R.string.import_connected_folders)
        listOf(binding.foldersTitle,binding.folders,binding.connectFolder,binding.checkFolders,binding.folderHelp).forEach { it.isVisible=showFolders }
        binding.connectFolder.isVisible=options?.connectedFolders==true && targets.isNotEmpty()
        binding.checkFolders.isVisible=state.routes.isNotEmpty()
        binding.folderHelp.isVisible=options?.connectedFolders==true
        binding.configure.text=options?.settingsLabel ?: getString(R.string.import_source_settings)
        if(renderedRoutes!=state.routes || renderedCollections!=collections) {
            renderedRoutes=state.routes; renderedCollections=collections; binding.folders.removeAllViews()
            collections.forEach { collection ->
                binding.folders.addView(TextView(this).apply {
                    text="${collection.name}\n${collection.summary}"; setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                    setPadding(0,8.dp,0,8.dp)
                })
            }
            state.routes.forEach { route ->
                val row=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(0,12.dp,0,12.dp) }
                row.addView(TextView(this).apply { text="${route.name} → ${route.label}"; setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge) })
                row.addView(TextView(this).apply {
                    text=route.error?.let(::errorText) ?: if(route.checked==0L) getString(R.string.import_folder_pending)
                        else getString(R.string.import_last_checked,android.text.format.DateUtils.getRelativeTimeSpanString(route.checked,System.currentTimeMillis(),60_000))
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                    setTextColor(com.google.android.material.color.MaterialColors.getColor(this,com.google.android.material.R.attr.colorOnSurfaceVariant))
                })
                row.addView((layoutInflater.inflate(R.layout.item_import_disconnect,row,false) as MaterialButton).apply {
                    setOnClickListener { MaterialAlertDialogBuilder(this@MediaImportActivity).setTitle(R.string.import_disconnect)
                        .setMessage(R.string.import_disconnect_help).setNegativeButton(android.R.string.cancel,null)
                        .setPositiveButton(R.string.import_disconnect) { _,_->model.disconnect(route) }.show() }
                })
                binding.folders.addView(row)
            }
        }
    }
    private val Int.dp get()=(this*resources.displayMetrics.density).toInt()
    private fun errorText(code: String)=getString(when(code) {
        "HTTP_401","HTTP_403" -> R.string.import_error_auth
        "PROFILE_CHANGED","PROFILE_MISSING","TARGET_MISSING" -> R.string.import_error_profile
        "PERMISSION_REQUIRED","TARGET_UNAVAILABLE","FOLDER_UNAVAILABLE" -> R.string.import_error_permission
        "MEDIA_CHANGED","MEDIA_MISSING" -> R.string.import_error_changed
        "STORAGE_FULL" -> R.string.import_error_storage
        "UNSUPPORTED_MEDIA" -> R.string.import_error_format
        "DUPLICATE_TRASHED" -> R.string.import_error_trashed
        "NETWORK","TIMEOUT","HTTP_429","HTTP_503","DISCONNECTED" -> R.string.import_error_network
        else -> R.string.import_error_generic
    })
}
