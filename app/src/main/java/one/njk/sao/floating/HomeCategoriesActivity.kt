package one.njk.sao.floating

import android.content.Intent
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.chip.Chip
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import one.njk.sao.R
import one.njk.sao.databinding.ActivityHomeCategoriesBinding
import one.njk.sao.floating.search.SourceCompletionContext
import one.njk.sao.source.*

/** SAO owns home presentation; extension settings continue to own credentials and domain configuration. */
@AndroidEntryPoint
class HomeCategoriesActivity : AppCompatActivity() {
    private val model: HomeCategoriesViewModel by viewModels()
    private val sources: SourceProfilesViewModel by viewModels()
    override fun onResume() { super.onResume(); sources.refreshExtensions() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding=ActivityHomeCategoriesBinding.inflate(layoutInflater); setContentView(binding.root)
        WindowCompat.getInsetsController(window,window.decorView).isAppearanceLightStatusBars =
            resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK != android.content.res.Configuration.UI_MODE_NIGHT_YES
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view,insets ->
            val bars=insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            if(view.paddingBottom!=bars.bottom || view.paddingTop!=bars.top) {
                view.setPadding(bars.left,bars.top,bars.right,bars.bottom)
                view.doOnLayout {
                    currentFocus?.takeIf { it===binding.query || it===binding.label }?.let { field ->
                        field.requestRectangleOnScreen(android.graphics.Rect(0,0,field.width,field.height),true)
                    }
                }
            }
            insets
        }
        var rendering=false
        var choices=emptyList<SourceProfile>()
        var modes=emptyList<SearchMode>()
        var rendered: Pair<List<SourceCategory>,String?>?=null
        var draftId: String?=null
        val back=object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() { binding.query.dismissSuggestions(); model.cancel() }
        }
        onBackPressedDispatcher.addCallback(this,back)
        binding.back.setOnClickListener { if(model.state.value.draft!=null) back.handleOnBackPressed() else finish() }
        binding.manageSources.setOnClickListener { startActivity(Intent(this,SourceSettingsActivity::class.java)) }
        binding.profile.setOnItemClickListener { _,_,index,_ -> choices.getOrNull(index)?.let { model.select(it.id) } }
        binding.mode.setOnItemClickListener { _,_,index,_ -> modes.getOrNull(index)?.let(model::mode) }
        binding.addCategory.setOnClickListener { model.edit() }
        binding.resetCategories.setOnClickListener { model.reset() }
        binding.saveCategory.setOnClickListener { binding.query.dismissSuggestions(); model.save() }
        binding.cancelCategory.setOnClickListener { binding.query.dismissSuggestions(); model.cancel() }
        binding.retry.setOnClickListener { model.retry() }
        binding.label.doAfterTextChanged { if(!rendering) model.label(it.toString()) }
        binding.query.doAfterTextChanged { if(!rendering) model.query(it.toString()) }
        binding.query.bind(null,this,binding.queryBox) { model.state.value.draft!=null && !model.state.value.saving }
        binding.query.sourceSuggestions(context = {
            val state=model.state.value
            state.draft?.category?.query?.takeIf { state.loaded && it.mode in state.capabilities.suggestionModes }
                ?.let { query -> state.profile?.let { SourceCompletionContext(it.identity,query) } }
        },lookup=model::suggestions)
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) {
            launch { sources.state.collect { state ->
                choices=state.browsingCatalog.profiles
                binding.profile.setSimpleItems(choices.map { it.name }.toTypedArray())
                model.catalog(state.browsingCatalog)
            } }
            launch { model.state.collect { state ->
                rendering=true
                val draft=state.draft?.category
                val profileName=choices.firstOrNull { it.id==state.profile?.id }?.name.orEmpty()
                if(binding.profile.text.toString()!=profileName) binding.profile.setText(profileName,false)
                binding.profile.isEnabled=choices.isNotEmpty() && draft==null && !state.saving
                binding.empty.isVisible=state.profile==null
                binding.manageSources.isVisible=state.profile==null
                binding.progress.isVisible=state.profile!=null && !state.loaded && state.error==null || state.saving
                binding.error.isVisible=state.error!=null; state.error?.let(binding.error::setText)
                binding.retry.isVisible=state.error!=null
                binding.categoryChoices.isVisible=state.loaded
                binding.addCategory.isEnabled=state.loaded && state.categories.size<40 && !state.saving && draft==null
                binding.resetCategories.isEnabled=state.loaded && state.customized && !state.saving && draft==null
                val key=state.categories to draft?.id
                if(rendered!=key) {
                    rendered=key
                    binding.categoryChoices.removeAllViews()
                    state.categories.forEach { category -> binding.categoryChoices.addView(Chip(this@HomeCategoriesActivity).apply {
                        text=category.label; isCheckable=true; isChecked=category.id==draft?.id; isCloseIconVisible=true
                        setOnClickListener { model.edit(category) }; setOnCloseIconClickListener { model.remove(category.id) }
                        contentDescription=getString(R.string.home_category_edit,category.label)
                    }) }
                }
                for(index in 0 until binding.categoryChoices.childCount) binding.categoryChoices.getChildAt(index).isEnabled=!state.saving
                binding.editor.isVisible=draft!=null
                back.isEnabled=draft!=null && !state.saving
                if(draft!=null) {
                    if(binding.label.text.toString()!=draft.label) binding.label.setText(draft.label)
                    if(binding.query.text.toString()!=draft.query.text) binding.query.setText(draft.query.text)
                    val availableModes=SearchMode.entries.filter { it in state.capabilities.modes }
                    if(modes!=availableModes) {
                        modes=availableModes
                        binding.mode.setSimpleItems(modes.map { getString(modeLabel(it)) }.toTypedArray())
                    }
                    val modeName=getString(modeLabel(draft.query.mode))
                    if(binding.mode.text.toString()!=modeName) binding.mode.setText(modeName,false)
                    binding.modeBox.isVisible=modes.size>1
                    binding.queryBox.helperText=getString(if(draft.query.mode in state.capabilities.suggestionModes)
                        R.string.home_category_suggestion_help else R.string.home_category_query_help)
                }
                listOf(binding.label,binding.query,binding.mode,binding.saveCategory,binding.cancelCategory).forEach { it.isEnabled=!state.saving }
                rendering=false
                binding.query.refreshSuggestions()
                if(draftId!=draft?.id) {
                    draftId=draft?.id
                    if(draft!=null) binding.editor.doOnLayout { it.requestRectangleOnScreen(android.graphics.Rect(0,0,it.width,it.height),true) }
                    else binding.query.dismissSuggestions()
                }
            } }
        } }
    }
    private fun modeLabel(mode: SearchMode)=when(mode) {
        SearchMode.CONTEXT -> R.string.search_mode_context; SearchMode.DESCRIPTION -> R.string.search_mode_description
        SearchMode.FILENAME -> R.string.search_mode_filename; SearchMode.PATH -> R.string.search_mode_path; SearchMode.OCR -> R.string.search_mode_ocr
    }
}
