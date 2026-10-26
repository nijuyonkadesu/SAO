package one.njk.sao.floating

import one.njk.sao.ui.SaoMotion

import one.njk.sao.source.*

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import one.njk.sao.R
import one.njk.sao.databinding.ActivityTelegramAppsBinding
import javax.inject.Inject

@AndroidEntryPoint
class TelegramAppsActivity : AppCompatActivity() {
    @Inject lateinit var preferences: FloatingPreferences
    @Inject lateinit var catalog: AppCatalog
    @Inject lateinit var dispatchers: FloatingDispatchers
    private lateinit var binding: ActivityTelegramAppsBinding
    private var saved = emptySet<String>()
    private var all = emptyList<AppChoice>()
    private var filtering: Job? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTelegramAppsBinding.inflate(layoutInflater); setContentView(binding.root)
        SaoMotion(this).enter(binding.root,savedInstanceState!=null)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left + dp(16), bars.top, bars.right + dp(16), bars.bottom); insets
        }
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.apps.layoutManager = LinearLayoutManager(this)
        binding.appSearch.doAfterTextChanged { filter() }
        binding.addPackages.setOnClickListener {
            val parsed = PackageNames.parse(binding.packages.text?.toString().orEmpty())
            binding.packagesField.error = if (parsed.invalid.isEmpty()) null else getString(R.string.floating_invalid_packages, parsed.invalid.joinToString(", "))
            if (parsed.invalid.isEmpty()) {
                lifecycleScope.launch { preferences.telegramPackages(saved + parsed.packages); binding.packages.setText(""); binding.status.setText(R.string.floating_packages_saved) }
            }
        }
        lifecycleScope.launch {
            preferences.options.collectLatest { options ->
                saved = options.telegramPackages
                all = catalog.load(saved)
                filter()
            }
        }
    }
    private fun filter() {
        filtering?.cancel()
        val query = binding.appSearch.text?.toString().orEmpty().trim()
        val snapshot = all; val selected = saved
        filtering = lifecycleScope.launch {
            val matching = withContext(dispatchers.cpu) { snapshot.filter { query.isBlank() || it.label.contains(query, true) || it.packageName.contains(query, true) } }
            binding.apps.adapter = AppChoiceAdapter(matching, selected) { item ->
                val next = if (item.packageName in saved) saved - item.packageName else saved + item.packageName
                saved = next
                lifecycleScope.launch { preferences.telegramPackages(next) }
                filter()
            }
            if (matching.isEmpty()) binding.status.setText(R.string.floating_no_apps) else binding.status.text = ""
        }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
