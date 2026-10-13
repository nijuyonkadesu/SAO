package one.njk.sao.floating

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import one.njk.sao.R
import one.njk.sao.databinding.ActivityImmichSettingsBinding

@AndroidEntryPoint
class ImmichSettingsActivity : AppCompatActivity() {
    private val model: ImmichSettingsViewModel by viewModels()
    private lateinit var binding: ActivityImmichSettingsBinding
    private var populated = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityImmichSettingsBinding.inflate(layoutInflater); setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        binding.back.setOnClickListener { finish() }
        binding.sourceName.doAfterTextChanged { if (populated) model.name = it?.toString().orEmpty() }
        binding.serverUrl.doAfterTextChanged { if (populated) model.url = it?.toString().orEmpty() }
        binding.apiKey.doAfterTextChanged { if (populated) model.key = it?.toString().orEmpty() }
        binding.album.setOnItemClickListener { _, _, position, _ -> model.albumId = model.state.value.albums.getOrNull(position - 1)?.id }
        binding.testConnection.setOnClickListener { model.check(false) }
        binding.saveConnection.setOnClickListener { model.check(true) }
        binding.useLocal.setOnClickListener { model.useLocal() }
        binding.deleteSource.isVisible = model.editingId != null
        binding.deleteSource.setOnClickListener { model.delete() }
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { model.state.collect { state ->
            if (state.loaded && !populated) {
                binding.sourceName.setText(model.name); binding.serverUrl.setText(model.url); binding.apiKey.setText(model.key)
                populated = true
            }
            val names = listOf(getString(R.string.immich_all_photos)) + state.albums.map { it.albumName }
            binding.album.setAdapter(ArrayAdapter(this@ImmichSettingsActivity, android.R.layout.simple_list_item_1, names))
            binding.album.setText(state.albums.firstOrNull { it.id == model.albumId }?.albumName ?:
                if (model.albumId == null) getString(R.string.immich_all_photos) else getString(R.string.immich_saved_album), false)
            binding.status.text = getString(state.status) + (state.version?.let { " ($it)" } ?: "")
            binding.progress.isVisible = state.working || !state.loaded
            listOf(binding.sourceName, binding.deleteSource, binding.serverUrl, binding.apiKey, binding.album,
                binding.testConnection, binding.saveConnection, binding.useLocal).forEach { it.isEnabled = state.loaded && !state.working }
        } } }
    }
    companion object {
        const val EXTRA_PROFILE = "source_profile_id"
        fun intent(context: Context, profileId: String? = null) = Intent(context, ImmichSettingsActivity::class.java)
            .putExtra(EXTRA_PROFILE, profileId)
    }
}
