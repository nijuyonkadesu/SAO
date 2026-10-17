package one.njk.sao.floating

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import one.njk.sao.source.*
import javax.inject.Inject

@AndroidEntryPoint
class SourceSetupActivity : AppCompatActivity() {
    @Inject lateinit var extensions: SourceExtensions
    private var extension: InstalledSourceExtension? = null
    private val launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        lifecycleScope.launch {
            try {
                val component = ComponentName.unflattenFromString(intent.getStringExtra("component").orEmpty())
                val found = extensions.installed().firstOrNull { it.component == component } ?: error("Extension unavailable")
                extensions.refresh(found,if (result.resultCode == RESULT_OK) result.data?.getStringExtra(ExtensionProtocol.PROFILE) else null)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { android.widget.Toast.makeText(this@SourceSetupActivity,one.njk.sao.R.string.source_extension_failed,android.widget.Toast.LENGTH_LONG).show() }
            finish()
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        lifecycleScope.launch {
            try {
                val component = ComponentName.unflattenFromString(intent.getStringExtra("component").orEmpty())
                val found = extensions.installed().firstOrNull { it.component == component } ?: error("Extension unavailable")
                extension = found
                extensions.migrate(found)
                launcher.launch(Intent().setComponent(ComponentName(found.component.packageName,found.settings))
                    .putExtra(ExtensionProtocol.PROFILE,intent.getStringExtra(ExtensionProtocol.PROFILE)))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { android.widget.Toast.makeText(this@SourceSetupActivity,one.njk.sao.R.string.source_extension_failed,android.widget.Toast.LENGTH_LONG).show(); finish() }
        }
    }
    companion object {
        fun intent(context: Context, extension: InstalledSourceExtension, profile: String? = null) = Intent(context,SourceSetupActivity::class.java)
            .putExtra("component",extension.component.flattenToString()).putExtra(ExtensionProtocol.PROFILE,profile)
    }
}
