package one.njk.sao.source.settings

import android.content.Intent
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.view.doOnLayout
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.*
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import one.njk.sao.source.*
import one.njk.sao.source.settings.databinding.SourceIntakeSectionBinding

/** Native M3 section in the extension's existing scroll surface; never loads extension code into SAO. */
class SourceIntakeSection(private val activity: AppCompatActivity,container: android.view.View,private val scroll: NestedScrollView) {
    private val binding=SourceIntakeSectionBinding.bind(container)
    private val model=ViewModelProvider(activity)[SourceIntakeViewModel::class.java]
    private var restoreScroll=model.scrollPosition
    private var host: String?=null
    private var profile: String?=null
    private var revision: String?=null
    private var targets: List<ImportTarget>?=null
    private var routes: List<SourceIntakeViewModel.Route>?=null
    private val picker=activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { model.reload() }
    init {
        activity.lifecycle.addObserver(object: DefaultLifecycleObserver {
            override fun onPause(owner: LifecycleOwner) { model.scrollPosition=scroll.scrollY }
        })
        binding.intakeAddMedia.setOnClickListener { launch("photos") }
        binding.intakeAddFiles.setOnClickListener { launch("files") }
        binding.intakeConnectFolder.setOnClickListener { launch("folder") }
        binding.intakeReload.setOnClickListener { model.reload() }
        binding.intakePause.setOnClickListener { model.action(ImportSettings.PAUSE) }
        binding.intakeRetry.setOnClickListener { model.action(ImportSettings.RETRY) }
        binding.intakeCheckFolders.setOnClickListener { model.action(ImportSettings.CHECK) }
        binding.intakeClear.setOnClickListener { confirm(R.string.source_intake_clear_title,R.string.source_intake_clear_help) { model.action(ImportSettings.CLEAR) } }
        activity.lifecycleScope.launch { activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
            launch { model.watch() }; launch { model.state.collect(::render) }
        } }
    }
    fun configure(host: String,profile: String,revision: String,options: suspend ()->ImportOptions) {
        this.host=host; this.profile=profile; this.revision=revision
        binding.root.isVisible=true; model.configure(host,profile,revision,options)
    }
    fun editable(value: Boolean)=model.editable(value)
    private fun launch(action: String) {
        val target=model.state.value.target ?: return
        if(!model.state.value.editable || model.state.value.busy) return
        picker.launch(Intent().setComponent(ImportSettings.activity(requireNotNull(host)))
            .putExtra(ExtensionProtocol.PROFILE,profile).putExtra(ImportSettings.REVISION,revision)
            .putExtra(ImportSettings.TARGET,target.id).putExtra(ImportSettings.ACTION,action))
    }
    private fun render(state: SourceIntakeViewModel.State) {
        if(host==null) return
        val options=state.options; val snapshot=state.snapshot
        val enabled=state.editable && !state.loading && !state.busy && snapshot!=null
        val localCollections=options?.network==false && options.collections.isNotEmpty()
        binding.intakeTitle.setText(if(localCollections && options!!.targets.isEmpty()) R.string.source_intake_local_title else R.string.source_intake_title)
        binding.intakeSummary.text=activity.getString(when {
            !state.editable -> R.string.source_intake_dirty
            state.loading -> R.string.source_intake_loading
            options?.targets.isNullOrEmpty() -> if(localCollections) R.string.source_intake_local_read_only else R.string.source_intake_read_only
            options?.network==true -> R.string.source_intake_upload_summary
            else -> R.string.source_intake_summary
        },state.target?.name.orEmpty())
        binding.intakeProgress.visibility=if(state.loading || state.busy) android.view.View.VISIBLE else android.view.View.INVISIBLE
        binding.intakeError.isVisible=state.error!=null || snapshot?.error!=null
        binding.intakeError.setText(errorResource(state.error ?: snapshot?.error))
        binding.intakeReload.isVisible=state.error!=null
        val all=options?.targets.orEmpty()
        if(targets!=all) {
            targets=all
            binding.intakeTarget.setAdapter(ArrayAdapter(activity,android.R.layout.simple_list_item_1,all.map { it.name }))
            binding.intakeTarget.setOnItemClickListener { _,_,position,_->all.getOrNull(position)?.let { model.target(it.id) } }
        }
        if(binding.intakeTarget.text.toString()!=state.target?.name.orEmpty()) binding.intakeTarget.setText(state.target?.name.orEmpty(),false)
        binding.intakeTargetBox.isVisible=all.size>1; binding.intakeTarget.isEnabled=enabled
        binding.intakeActions.isVisible=all.isNotEmpty()
        listOf(binding.intakeAddMedia,binding.intakeAddFiles,binding.intakeConnectFolder,binding.intakeCheckFolders,binding.intakePause,binding.intakeRetry,binding.intakeClear).forEach { it.isEnabled=enabled }
        val counts=if(snapshot==null) "" else listOfNotNull(
            snapshot.done.takeIf { it>0 }?.let { activity.getString(R.string.source_intake_added,it) },
            snapshot.pending.takeIf { it>0 }?.let { activity.getString(R.string.source_intake_pending,it) },
            snapshot.blocked.takeIf { it>0 }?.let { activity.getString(R.string.source_intake_blocked,it) }).joinToString(" · ")
        binding.intakeStatus.isVisible=counts.isNotEmpty() || snapshot?.paused==true
        binding.intakeStatus.text=if(snapshot?.paused==true) activity.getString(R.string.source_intake_paused,counts) else counts
        binding.intakeQueueActions.isVisible=snapshot!=null && (snapshot.pending+snapshot.blocked>0 || snapshot.paused)
        binding.intakePause.setText(if(snapshot?.paused==true) R.string.source_intake_resume else R.string.source_intake_pause)
        binding.intakeRetry.isVisible=(snapshot?.blocked ?: 0)>0
        binding.intakeClear.isVisible=(snapshot?.pending ?: 0)+(snapshot?.blocked ?: 0)>0
        binding.intakeFolderSection.isVisible=options?.connectedFolders==true
        binding.intakeConnectFolder.isVisible=all.isNotEmpty()
        binding.intakeCheckFolders.isVisible=!snapshot?.routes.isNullOrEmpty()
        val shown=snapshot?.routes.orEmpty()
        if(routes!=shown) {
            routes=shown; binding.intakeFolders.removeAllViews()
            shown.forEach { route ->
                val row=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL; setPadding(0,8.dp,0,8.dp) }
                row.addView(TextView(activity).apply {
                    text="${route.name} → ${route.target}"; setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
                })
                row.addView(TextView(activity).apply {
                    text=if(route.error!=null) activity.getString(errorResource(route.error)) else if(route.checked==0L) activity.getString(R.string.source_intake_waiting)
                        else activity.getString(R.string.source_intake_checked,android.text.format.DateUtils.getRelativeTimeSpanString(route.checked,System.currentTimeMillis(),60_000))
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                })
                row.addView((activity.layoutInflater.inflate(R.layout.source_intake_disconnect,row,false) as MaterialButton).apply {
                    isEnabled=enabled
                    setOnClickListener { confirm(R.string.source_intake_disconnect,R.string.source_intake_disconnect_help) { model.action(ImportSettings.DISCONNECT,route.id) } }
                }); binding.intakeFolders.addView(row)
            }
        }
        if(!state.loading && snapshot!=null) restoreScroll?.let { position ->
            restoreScroll=null
            // Async queue/folder rows must exist before Android can restore the full scroll range.
            scroll.doOnLayout { scroll.post { scroll.scrollTo(0,position) } }
        }
        for(index in 0 until binding.intakeFolders.childCount) (binding.intakeFolders.getChildAt(index) as? LinearLayout)?.let { row ->
            (row.getChildAt(row.childCount-1) as? MaterialButton)?.isEnabled=enabled
        }
    }
    private fun confirm(title: Int,message: Int,action: ()->Unit) {
        MaterialAlertDialogBuilder(activity).setTitle(title).setMessage(message).setNegativeButton(android.R.string.cancel,null)
            .setPositiveButton(android.R.string.ok) { _,_->action() }.show()
    }
    private val Int.dp get()=(this*activity.resources.displayMetrics.density).toInt()
    private fun errorResource(code: String?)=when(code) {
        "HTTP_401","HTTP_403" -> R.string.source_intake_auth
        "PROFILE_CHANGED","PROFILE_MISSING" -> R.string.source_intake_profile
        "PERMISSION_REQUIRED","TARGET_UNAVAILABLE","FOLDER_UNAVAILABLE" -> R.string.source_intake_permission
        "MEDIA_CHANGED","MEDIA_MISSING" -> R.string.source_intake_file
        "STORAGE_FULL" -> R.string.source_intake_storage
        "NETWORK","TIMEOUT","HTTP_429","HTTP_503","DISCONNECTED" -> R.string.source_intake_network
        "UNAVAILABLE","UNTRUSTED","UNSUPPORTED","INCOMPATIBLE","REQUEST_FAILED" -> R.string.source_intake_error
        else -> R.string.source_intake_generic
    }
}
