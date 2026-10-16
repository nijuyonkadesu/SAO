package one.njk.sao

import android.os.Bundle
import android.provider.Settings
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupActionBarWithNavController
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import one.njk.sao.databinding.ActivityMainBinding
import one.njk.sao.floating.FloatingLaunchState
import one.njk.sao.floating.FloatingPreferences
import one.njk.sao.floating.FloatingRuntime
import one.njk.sao.floating.FloatingSession
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController
    @Inject lateinit var floatingPreferences: FloatingPreferences
    @Inject lateinit var floatingRuntime: FloatingRuntime
    private val floatingLaunchState by viewModels<FloatingLaunchState>()
    private var floatingStart: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.topAppBar)

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment_content_main) as NavHostFragment
        navController = navHostFragment.navController
        setupActionBarWithNavController(navController)

    }

    override fun onStart() {
        super.onStart()
        if (floatingLaunchState.skipNextStart) {
            floatingLaunchState.skipNextStart = false
            return
        }
        // One read per app opening. DataStore reads asynchronously; no permission prompts or polling.
        floatingStart = lifecycleScope.launch {
            if (!floatingPreferences.current().showOnLaunch || FloatingSession.active.value ||
                !Settings.canDrawOverlays(this@MainActivity)) return@launch
            floatingRuntime.show()
        }
    }

    override fun onStop() {
        // Retry an unfinished read after rotation, but don't undo a deliberate Stop on recreation.
        floatingLaunchState.skipNextStart = isChangingConfigurations && floatingStart?.isCompleted != false
        floatingStart?.cancel()
        super.onStop()
    }

    override fun onSupportNavigateUp(): Boolean {
        return navController.navigateUp()
                || super.onSupportNavigateUp()
    }
}
