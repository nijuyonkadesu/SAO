package one.njk.sao.floating

import android.os.Bundle
import android.widget.Toast
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivitySourceSettingsBinding.inflate(layoutInflater); setContentView(binding.root)
        val padding = (24 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(padding + bars.left, padding + bars.top, padding + bars.right, padding + bars.bottom); insets
        }
        binding.back.setOnClickListener { finish() }
        binding.addSource.setOnClickListener { startActivity(ImmichSettingsActivity.intent(this)) }
        val adapter = SourceListAdapter(model::select) { id ->
            if (model.state.value.catalog.profiles.firstOrNull { it.id == id }?.configuration is ImmichConfig)
                startActivity(ImmichSettingsActivity.intent(this, id))
            else Toast.makeText(this, R.string.source_unavailable, Toast.LENGTH_SHORT).show()
        }
        binding.sourceList.layoutManager = LinearLayoutManager(this); binding.sourceList.adapter = adapter
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { model.state.collect { state ->
            adapter.submitList(sourceRows(state.catalog)); binding.status.isVisible = state.error
            binding.sourceList.isEnabled = state.loaded && !state.saving
            binding.addSource.isEnabled = state.loaded && !state.saving
        } } }
    }
}
