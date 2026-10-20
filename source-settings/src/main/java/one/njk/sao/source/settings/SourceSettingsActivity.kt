package one.njk.sao.source.settings

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.color.DynamicColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import one.njk.sao.source.SourceAccess

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
            onSourceCreate(savedInstanceState,caller)
        }
    }
    protected abstract fun onSourceCreate(savedInstanceState: Bundle?,caller: String)
}
