package one.njk.sao.floating

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.activity.enableEdgeToEdge
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
import one.njk.sao.databinding.ActivityRaysSettingsBinding
import one.njk.sao.rays.RaysClient

@AndroidEntryPoint
class RaysSettingsActivity : AppCompatActivity() {
    private val model: RaysSettingsViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val binding = ActivityRaysSettingsBinding.inflate(layoutInflater); setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        binding.back.setOnClickListener { finish() }
        var populated = false
        binding.sourceName.doAfterTextChanged { if (populated) model.name = it?.toString().orEmpty() }
        binding.testConnection.setOnClickListener { model.check(false) }
        binding.saveConnection.setOnClickListener { model.check(true) }
        binding.deleteSource.isVisible = model.editingId != null
        binding.deleteSource.setOnClickListener { model.delete() }
        binding.openRays.setOnClickListener {
            packageManager.getLaunchIntentForPackage(model.packageName)?.let(::startActivity)
        }
        binding.authorization.text = getString(R.string.rays_authorization, packageName)
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { model.state.collect { state ->
            if (state.loaded && !populated) { binding.sourceName.setText(model.name); populated = true }
            binding.provider.text = getString(if (model.packageName == RaysClient.DEBUG_PACKAGE)
                R.string.source_rays_debug else R.string.source_rays)
            binding.status.setText(state.status)
            binding.progress.isVisible = !state.loaded || state.working
            listOf(binding.sourceName, binding.testConnection, binding.saveConnection, binding.deleteSource)
                .forEach { it.isEnabled = state.loaded && !state.working }
            binding.openRays.isEnabled = state.loaded && RaysClient.installed(this@RaysSettingsActivity, model.packageName)
        } } }
    }
    companion object {
        const val EXTRA_PROFILE = "source_profile_id"
        const val EXTRA_PACKAGE = "rays_package"
        fun intent(context: Context, profileId: String? = null, packageName: String = RaysClient.PACKAGE) =
            Intent(context, RaysSettingsActivity::class.java).putExtra(EXTRA_PROFILE, profileId)
                .putExtra(EXTRA_PACKAGE, packageName)
    }
}
