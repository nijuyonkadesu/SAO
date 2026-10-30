package one.njk.sao.source.settings

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.google.android.material.color.DynamicColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import one.njk.sao.source.SourceAccess
import one.njk.sao.source.SourceLocales

/** Authenticate before creating a private settings form. Cross-signer authorization stays off main. */
abstract class SourceSettingsActivity : AppCompatActivity() {
    private var intakeResult: (() -> Unit)?=null
    private var pendingIntakeResult=false
    // Register before STARTED; caller authorization may finish after onStart().
    private val intake=registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { if(intakeResult==null) pendingIntakeResult=true else intakeResult?.invoke() }
    internal fun intakeLauncher(result: () -> Unit): androidx.activity.result.ActivityResultLauncher<android.content.Intent> {
        intakeResult=result
        if(pendingIntakeResult) { pendingIntakeResult=false; result() }
        return intake
    }
    final override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val caller=callingPackage
        lifecycleScope.launch {
            if(caller==null || !withContext(Dispatchers.IO) { SourceAccess.isHost(this@SourceSettingsActivity,caller) }) {
                finish(); return@launch
            }
            // Use the supported per-app API only after authenticating the caller. Android 13+
            // owns persistence; pre-33 uses AppCompat's process locale (no mirrored preferences).
            val tags=SourceLocales.validated(intent.getStringExtra(SourceLocales.EXTRA_LANGUAGE_TAGS))
            if(tags!=null && AppCompatDelegate.getApplicationLocales().toLanguageTags()!=tags) {
                val locales=LocaleListCompat.forLanguageTags(tags)
                AppCompatDelegate.setApplicationLocales(locales)
            }
            onSourceCreate(savedInstanceState,caller)
        }
    }
    protected abstract fun onSourceCreate(savedInstanceState: Bundle?,caller: String)
}
