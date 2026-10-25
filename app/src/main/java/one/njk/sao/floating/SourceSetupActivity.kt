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
    private enum class Phase { DISCOVERING, SETTINGS, RECONCILING }
    private var phase = Phase.DISCOVERING
    private var returnedProfile: String? = null
    private var opening: Job? = null
    @Inject lateinit var extensions: SourceExtensions
    @Inject lateinit var trust: ExtensionTrust
    private val launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        returnedProfile = if (result.resultCode == RESULT_OK) result.data?.getStringExtra(ExtensionProtocol.PROFILE) else null
        phase = Phase.RECONCILING
        reconcile()
    }
    private fun reconcile() {
        opening?.cancel()
        opening = lifecycleScope.launch {
            try {
                val component = ComponentName.unflattenFromString(intent.getStringExtra("component").orEmpty())
                val found = extensions.installed().firstOrNull { it.component == component } ?: error("Extension unavailable")
                extensions.refresh(found,returnedProfile,intent.getBooleanExtra(EXTRA_FLOATING, false))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { android.widget.Toast.makeText(this@SourceSetupActivity,one.njk.sao.R.string.source_extension_failed,android.widget.Toast.LENGTH_LONG).show() }
            finish()
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        phase = savedInstanceState?.getString("setup_phase")?.let { name -> Phase.entries.firstOrNull { it.name == name } } ?: Phase.DISCOVERING
        returnedProfile = savedInstanceState?.getString("returned_profile")
        supportFragmentManager.setFragmentResultListener(ExtensionTrustDialog.RESULT,this) { _,result ->
            if(!result.getBoolean(ExtensionTrustDialog.ALLOWED)) { finish(); return@setFragmentResultListener }
            lifecycleScope.launch {
                try {
                    val component=ComponentName.unflattenFromString(intent.getStringExtra("component").orEmpty()) ?: error("Extension unavailable")
                    check(result.getString(ExtensionTrustDialog.PACKAGE)==component.packageName)
                    trust.approve(component.packageName,requireNotNull(result.getString(ExtensionTrustDialog.SIGNER)))
                    open()
                } catch(cancelled: CancellationException) { throw cancelled }
                catch(_: Exception) { unavailable() }
            }
        }
        when (phase) {
            Phase.DISCOVERING -> if (supportFragmentManager.findFragmentByTag(ExtensionTrustDialog.RESULT)==null) open()
            Phase.SETTINGS -> Unit // ActivityResultRegistry restores the outstanding external result.
            Phase.RECONCILING -> reconcile()
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("setup_phase",phase.name)
        outState.putString("returned_profile",returnedProfile)
        super.onSaveInstanceState(outState)
    }
    private fun open() {
        opening?.cancel()
        phase=Phase.DISCOVERING
        opening=lifecycleScope.launch {
        try {
            val component=ComponentName.unflattenFromString(intent.getStringExtra("component").orEmpty())
            val found=extensions.candidates().firstOrNull { it.component==component } ?: error("Extension unavailable")
            if(found.approval==ExtensionApproval.UNTRUSTED) {
                if(supportFragmentManager.findFragmentByTag(ExtensionTrustDialog.RESULT)==null)
                    ExtensionTrustDialog.create(found.name,found.component.packageName,found.signer).show(supportFragmentManager,ExtensionTrustDialog.RESULT)
                return@launch
            }
            extensions.migrate(found)
            phase=Phase.SETTINGS
            launcher.launch(Intent().setComponent(ComponentName(found.component.packageName,found.settings))
                .putExtra(one.njk.sao.source.SourceLocales.EXTRA_LANGUAGE_TAGS,one.njk.sao.AppLanguages.tags)
                .putExtra(ExtensionProtocol.PROFILE,intent.getStringExtra(ExtensionProtocol.PROFILE)))
        } catch(cancelled: CancellationException) { throw cancelled }
        catch(_: Exception) { unavailable() }
        }
    }
    private fun unavailable() {
        android.widget.Toast.makeText(this,one.njk.sao.R.string.source_extension_failed,android.widget.Toast.LENGTH_LONG).show()
        finish()
    }
    companion object {
        const val EXTRA_FLOATING = "floating_selection"
        fun intent(context: Context, extension: InstalledSourceExtension, profile: String? = null, floating: Boolean = false) = Intent(context,SourceSetupActivity::class.java)
            .putExtra(EXTRA_FLOATING,floating).putExtra("component",extension.component.flattenToString()).putExtra(ExtensionProtocol.PROFILE,profile)
    }
}
