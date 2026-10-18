package one.njk.sao.floating


import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import one.njk.sao.R
import one.njk.sao.databinding.ActivitySourceSettingsBinding

@AndroidEntryPoint
class SourceSettingsActivity : AppCompatActivity() {
    private val model: SourceProfilesViewModel by viewModels()
    override fun onResume() { super.onResume(); model.refreshExtensions() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivitySourceSettingsBinding.inflate(layoutInflater); setContentView(binding.root)
        val padding = (24 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(padding + bars.left, padding + bars.top, padding + bars.right, padding + bars.bottom); insets
        }
        binding.allSources.setOnCheckedChangeListener { _, checked -> model.enableAll(checked) }
        binding.back.setOnClickListener { finish() }
        binding.addSource.setOnClickListener { addMediaSource() }
        val adapter = SourceListAdapter(model::select) { id ->
            editMediaSource(model.state.value.catalog.profiles.firstOrNull { it.id == id })
        }
        binding.sourceList.layoutManager = LinearLayoutManager(this); binding.sourceList.adapter = adapter
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { model.state.collect { state ->
            binding.allSources.isChecked = state.allEnabled
            binding.allSources.isEnabled = state.loaded && !state.saving
            adapter.submitList(sourceRows(state.catalog)); binding.status.isVisible = state.error
            binding.sourceList.isEnabled = state.loaded && !state.saving
            binding.addSource.isEnabled = state.loaded && !state.saving
        } } }
    }
}
