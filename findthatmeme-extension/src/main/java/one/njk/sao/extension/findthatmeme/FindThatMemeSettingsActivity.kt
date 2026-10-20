package one.njk.sao.extension.findthatmeme

import android.content.Intent
import android.os.Bundle
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.*
import kotlinx.coroutines.launch
import one.njk.sao.extension.findthatmeme.databinding.ActivityFindthatmemeSettingsBinding
import one.njk.sao.source.ExtensionProtocol
import one.njk.sao.source.settings.SourceSettingsActivity
import one.njk.sao.source.settings.SourceSettingsGuard

class FindThatMemeSettingsActivity : SourceSettingsActivity() {
    private val model by lazy { ViewModelProvider(this)[FindThatMemeSettingsViewModel::class.java] }

    override fun onSourceCreate(savedInstanceState: Bundle?, caller: String) {
        val binding = ActivityFindthatmemeSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        var rendering = false
        val guard = SourceSettingsGuard(this, binding.saveSource, { model.hasChanges }, { model.state.value.working }, model::save)
        binding.back.setOnClickListener { guard.leave() }
        binding.sourceName.doAfterTextChanged { if (!rendering) model.name(it.toString()) }
        binding.baseTerms.doAfterTextChanged { if (!rendering) model.configure(model.state.value.config.copy(terms = it.toString())) }
        binding.includeSensitive.setOnCheckedChangeListener { _, value ->
            if (!rendering) model.configure(model.state.value.config.copy(sensitive = value))
        }
        binding.checkService.setOnClickListener { model.check() }
        binding.saveSource.setOnClickListener { model.save() }
        binding.removeSource.setOnClickListener { model.delete() }
        binding.removeSource.isVisible = model.editing != null
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.state.collect { state ->
                    rendering = true
                    if (binding.sourceName.text.toString() != state.name) binding.sourceName.setText(state.name)
                    if (binding.baseTerms.text.toString() != state.config.terms) binding.baseTerms.setText(state.config.terms)
                    binding.includeSensitive.isChecked = state.config.sensitive
                    rendering = false
                    listOf(binding.sourceName, binding.baseTerms, binding.includeSensitive, binding.checkService, binding.saveSource, binding.removeSource)
                        .forEach { it.isEnabled = state.loaded && !state.working }
                    binding.status.setText(state.status)
                    binding.progress.isVisible = state.working
                    if (state.finished) {
                        setResult(RESULT_OK, Intent().putExtra(ExtensionProtocol.PROFILE, model.profile))
                        finish()
                    }
                }
            }
        }
    }
}
