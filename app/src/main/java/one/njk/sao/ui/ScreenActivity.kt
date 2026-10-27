package one.njk.sao.ui

import android.os.Bundle
import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.core.view.WindowCompat
import one.njk.sao.R
import one.njk.sao.databinding.ActivityScreenBinding

/** Ordinary Activity navigation. Window entrance, Back and predictive Back belong to Android. */
abstract class ScreenActivity: AppCompatActivity() {
    protected abstract val screen: Class<out Fragment>
    @get:StringRes protected abstract val titleResource: Int
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding=ActivityScreenBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowCompat.getInsetsController(window,window.decorView).isAppearanceLightStatusBars=
            resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK!=Configuration.UI_MODE_NIGHT_YES
        setSupportActionBar(binding.topAppBar)
        supportActionBar?.setTitle(titleResource)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        if(savedInstanceState==null) supportFragmentManager.beginTransaction()
            .setReorderingAllowed(true).replace(R.id.screen_content,screen,null).commitNow()
    }
    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
